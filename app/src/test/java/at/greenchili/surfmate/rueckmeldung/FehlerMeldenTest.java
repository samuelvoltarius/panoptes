package at.greenchili.surfmate.rueckmeldung;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.server.ExternGuard;
import at.greenchili.surfmate.testutil.Quelltext;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/** 0.1.31 „Fehler melden“: nur die vereinbarten Felder, ausdrücklich, ohne Netz gemerkt, 5 je Tag. */
public class FehlerMeldenTest {

    private static final long T0 = 1_793_000_000_000L;
    private static final long TAG = 86_400_000L;

    private JSONObject stand;
    private final List<JSONObject> gesendet = new ArrayList<>();
    private boolean ohneNetz;
    private int status = 201;

    private final FehlerMelden.Speicher speicher = new FehlerMelden.Speicher() {
        public JSONObject lese() { return stand; }
        public void schreibe(JSONObject s) { stand = s; }
    };

    private final FehlerMelden.Sender sender = (url, json) -> {
        if (ohneNetz) throw new java.net.UnknownHostException("byd.serveone.at");
        assertEquals(FehlerMelden.STANDARD_URL, url);
        gesendet.add(new JSONObject(json));
        return new FehlerMelden.Antwort(status, "{\"code\":\"AB2C-DE3F\"}");
    };

    private JSONObject k(String text) throws Exception {
        return FehlerMelden.koerper(text, "0.1.31", 3, "Dolphin Surf", "de", null);
    }

    @Test
    public void nurDieVereinbartenFelder() throws Exception {
        JSONObject k = FehlerMelden.koerper(" Karte weiß ", "0.1.31", 3, "Dolphin Surf", "de", "[camera]\nzeile");
        HashSet<String> felder = new HashSet<>();
        for (java.util.Iterator<String> it = k.keys(); it.hasNext(); ) felder.add(it.next());
        assertEquals(new HashSet<>(Arrays.asList("text", "version", "dilink", "modell", "sprache", "diagnose")), felder);
        assertEquals("Karte weiß", k.getString("text"));
        assertFalse("ohne Haken keine Diagnose", k("x").has("diagnose"));
        assertFalse("DiLink unbekannt fehlt", FehlerMelden.koerper("x", "0.1.31", null, null, "de", null).has("dilink"));
        assertEquals("unbekannt", FehlerMelden.koerper("x", "0.1.31", null, null, "de", null).getString("modell"));
    }

    /** „Ergebnis senden“ am Tacho-Test: gleicher Weg, nur mit art. Andere Werte gibt es nicht. */
    @Test
    public void tachoErgebnisGehtMitArtUndNurMitBekannterArt() throws Exception {
        char[] z = new char[1500];
        Arrays.fill(z, 'a');
        JSONObject k = FehlerMelden.koerper(new String(z), "0.1.31", 3, "Seal", "de", null, FehlerMelden.ART_TACHO);
        assertEquals("tacho_test", k.getString("art"));
        assertNull("sonst gilt die normale Grenze", FehlerMelden.koerper(new String(z), "0.1.31", 3, "Seal", "de", null, "x"));
        assertFalse(FehlerMelden.koerper("x", "0.1.31", 3, "Seal", "de", null, "erfunden").has("art"));
        String h = Quelltext.lies(Paths.get("src/main/assets/web/local/testfunktionen.html"));
        assertTrue("eigener Klick nach Vorschau", h.contains("id=\"ttSendenJa\"") && h.contains("art: 'tacho_test'"));
    }

    @Test
    public void textPflichtUndHoechstens1000Zeichen() throws Exception {
        assertNull(k("   "));
        assertNull(k(null));
        char[] z = new char[1000];
        Arrays.fill(z, 'a');
        assertNotNull(k(new String(z)));
        assertNull(k(new String(z) + "a"));
        assertEquals(FehlerMelden.Stand.UNGUELTIG, FehlerMelden.absenden(T0, null, speicher, sender).stand);
        assertTrue(gesendet.isEmpty());
    }

    @Test
    public void groesseBleibtUnter64KB() throws Exception {
        char[] z = new char[200_000];
        Arrays.fill(z, '"');   // jedes Zeichen wird im JSON zu zweien
        JSONObject k = FehlerMelden.koerper("x", "0.1.31", 3, "Seal", "de", new String(z));
        assertTrue(k.toString().getBytes(StandardCharsets.UTF_8).length <= FehlerMelden.KOERPER_MAX);
        assertTrue(k.getString("diagnose").length() > 0);
    }

    @Test
    public void sendenLiefertDenCode() throws Exception {
        FehlerMelden.Ergebnis e = FehlerMelden.absenden(T0, k("Karte weiß"), speicher, sender);
        assertEquals(FehlerMelden.Stand.GESENDET, e.stand);
        assertEquals("AB2C-DE3F", e.code);
        assertEquals("AB2C-DE3F", stand.getJSONArray("codes").getJSONObject(0).getString("code"));
        assertEquals(FehlerMelden.TAG_MAX - 1, FehlerMelden.rest(stand, T0));
    }

    @Test
    public void ohneNetzGemerktUndSpaeterNachgesendet() throws Exception {
        ohneNetz = true;
        FehlerMelden.Ergebnis e = FehlerMelden.absenden(T0, k("eins"), speicher, sender);
        assertEquals(FehlerMelden.Stand.GEMERKT, e.stand);
        assertNull(e.code);
        FehlerMelden.takt(T0 + 300_000L, speicher, sender);
        assertEquals(1, stand.getJSONArray("offen").length());
        ohneNetz = false;
        status = 503;
        FehlerMelden.takt(T0 + 600_000L, speicher, sender);
        assertEquals("Überlast: bleibt gemerkt", 1, stand.getJSONArray("offen").length());
        status = 201;
        FehlerMelden.takt(T0 + 900_000L, speicher, sender);
        assertEquals(0, stand.getJSONArray("offen").length());
        assertEquals("eins", gesendet.get(gesendet.size() - 1).getString("text"));
        assertEquals("AB2C-DE3F", stand.getJSONArray("codes").getJSONObject(0).getString("code"));
    }

    /** Kein Hintergrund-Senden: Ohne gemerkte Meldung geht im Takt nichts raus. */
    @Test
    public void taktOhneMeldungSendetNichts() {
        FehlerMelden.takt(T0, speicher, sender);
        stand = new JSONObject();
        FehlerMelden.takt(T0, speicher, sender);
        assertTrue(gesendet.isEmpty());
    }

    @Test
    public void abgelehntWirdNichtWiederholt() throws Exception {
        status = 400;
        assertEquals(FehlerMelden.Stand.ABGELEHNT, FehlerMelden.absenden(T0, k("x"), speicher, sender).stand);
        assertEquals("zählt nicht", FehlerMelden.TAG_MAX, FehlerMelden.rest(stand, T0));
        ohneNetz = true;
        FehlerMelden.absenden(T0, k("y"), speicher, sender);
        ohneNetz = false;
        status = 413;
        FehlerMelden.takt(T0 + 1000, speicher, sender);
        assertEquals("verworfen", 0, stand.getJSONArray("offen").length());
    }

    @Test
    public void fuenfJeTagAuchOhneNetz() throws Exception {
        ohneNetz = true;
        for (int i = 0; i < FehlerMelden.TAG_MAX; i++) {
            assertEquals(FehlerMelden.Stand.GEMERKT, FehlerMelden.absenden(T0 + i, k("m" + i), speicher, sender).stand);
        }
        assertEquals(FehlerMelden.Stand.ZUVIEL, FehlerMelden.absenden(T0 + 9, k("zuviel"), speicher, sender).stand);
        assertEquals(FehlerMelden.TAG_MAX, stand.getJSONArray("offen").length());
        ohneNetz = false;
        assertEquals("nächster Tag", FehlerMelden.Stand.GESENDET,
                FehlerMelden.absenden(T0 + TAG, k("neu"), speicher, sender).stand);
    }

    @Test
    public void nachschwaerzenFasstWasDerErsteDurchgangLaesst() throws Exception {
        // Beispielwerte: Wien, kein echter Ort
        String[][] faelle = {
                {"{\"lat\":48.2082,\"lon\":16.3738}", "48.2082"},
                {"Position 48.2082, 16.3738 ok", "16.3738"},
                {"chat_id=123456789 send failed", "123456789"},
                {"peer auto-1.tail1234.ts.net timeout", "ts.net"},
                {"kennung 3f2b8c1e-9a4d-4e21-8f00-1234567890ab stale", "3f2b8c1e"},
        };
        for (String[] f : faelle) {
            assertFalse(f[0], FehlerMelden.nachschwaerzen(f[0]).contains(f[1]));
        }
        assertEquals("Version bleibt lesbar", "Panoptes 0.1.31 failed", FehlerMelden.nachschwaerzen("Panoptes 0.1.31 failed"));

        JSONObject logs = new JSONObject().put("sources", new JSONArray().put(new JSONObject()
                .put("component", "camera").put("lines", new JSONArray().put("[WARN] lat=48.2082"))));
        String t = FehlerMelden.diagnoseText(logs);
        assertTrue(t.startsWith("[camera]\n"));
        assertFalse(t.contains("48.2082"));
    }

    @Test
    public void modellNurAusFesterListe() throws Exception {
        JSONObject v = new JSONObject().put("modelId", "seagull").put("modelSource", "user");
        assertEquals("Dolphin Surf", AutoAngaben.modell(new JSONObject().put("vehicle", v)));
        v.put("modelSource", "auto").put("modelId", "atto3");
        assertEquals("Atto 3", AutoAngaben.modell(new JSONObject().put("vehicle", v)));
        v.put("modelId", "etwas-anderes");
        assertEquals(AutoAngaben.UNBEKANNT, AutoAngaben.modell(new JSONObject().put("vehicle", v)));
        v.put("modelId", "seal").put("modelSource", "default");
        assertEquals("Anzeige-Rückfall zählt nicht", AutoAngaben.UNBEKANNT, AutoAngaben.modell(new JSONObject().put("vehicle", v)));
        assertEquals(AutoAngaben.UNBEKANNT, AutoAngaben.modell(new JSONObject()));
        v.put("modelId", "seagull").put("modelSource", "legacy");
        assertEquals("0.1.32.1: Alt-Wahl zählt", "Dolphin Surf", AutoAngaben.modell(new JSONObject().put("vehicle", v)));
        v.remove("modelSource");
        assertEquals("ohne Herkunft = legacy", "Dolphin Surf", AutoAngaben.modell(new JSONObject().put("vehicle", v)));
        v.put("modelId", "seal").put("modelSource", "legacy");
        assertEquals("seal aus alter Neuinstallation zählt nie", AutoAngaben.UNBEKANNT, AutoAngaben.modell(new JSONObject().put("vehicle", v)));
        v.put("modelId", "seagull").put("modelSource", "unset");
        assertEquals(AutoAngaben.UNBEKANNT, AutoAngaben.modell(new JSONObject().put("vehicle", v)));

        JSONArray m = new JSONObject(Quelltext.lies(Paths.get("src/main/assets/web/shared/models/manifest.json")))
                .getJSONArray("models");
        for (int i = 0; i < m.length(); i++) {
            String id = m.getJSONObject(i).getString("id");
            String name = AutoAngaben.NAMEN.get(id);
            assertNotNull("Manifest-Modell fehlt: " + id, name);
            assertTrue(name, name.matches("[A-Za-z0-9][A-Za-z0-9 +.-]{0,23}"));
        }
    }

    @Test
    public void dilinkNichtRaten() {
        assertEquals(Integer.valueOf(5), AutoAngaben.dilink(true, false, 33));
        assertEquals(Integer.valueOf(4), AutoAngaben.dilink(false, true, 31));
        assertEquals(Integer.valueOf(3), AutoAngaben.dilink(false, false, 29));
        assertNull(AutoAngaben.dilink(false, false, 33));
    }

    @Test
    public void gekoppelteGeraeteKommenNichtRan() throws Exception {
        assertFalse(ExternGuard.erlaubt("POST", "/api/rueckmeldung/fehler"));
        assertFalse(ExternGuard.erlaubt("GET", "/api/rueckmeldung/fehler"));
        assertFalse(ExternGuard.erlaubt("POST", "/api/rueckmeldung/zaehlen"));
        String h = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/RueckmeldungApiHandler.java"));
        assertTrue(h.contains("isExternRequest()"));
        assertTrue("Frage nur im Stand", h.contains("VehicleCommunicationSafety.isParked()"));
    }

    @Test
    public void testseiteHatAlleSchluesselInAllenSprachen() throws Exception {
        String h = Quelltext.lies(Paths.get("src/main/assets/web/local/testfunktionen.html"));
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("testf\\.([a-z0-9_]+)").matcher(h);
        HashSet<String> keys = new HashSet<>();
        while (m.find()) keys.add(m.group(1));
        assertTrue(keys.size() > 25);
        for (String l : new String[]{"de", "en", "fr", "it", "nl"}) {
            JSONObject o = new JSONObject(Quelltext.lies(Paths.get("src/main/assets/web/i18n/" + l + ".json"))).getJSONObject("testf");
            for (String k : keys) assertTrue(l + " testf." + k, o.has(k));
        }
    }

    @Test
    public void texteInAllenSprachen() throws Exception {
        JSONObject de = new JSONObject(Quelltext.lies(Paths.get("src/main/assets/web/i18n/de.json"))).getJSONObject("rueckmeldung");
        for (String l : new String[]{"en", "fr", "it", "nl"}) {
            JSONObject o = new JSONObject(Quelltext.lies(Paths.get("src/main/assets/web/i18n/" + l + ".json"))).getJSONObject("rueckmeldung");
            assertEquals(l, de.length(), o.length());
            for (java.util.Iterator<String> it = de.keys(); it.hasNext(); ) {
                String key = it.next();
                assertTrue(l + " " + key, o.has(key));
            }
        }
    }
}
