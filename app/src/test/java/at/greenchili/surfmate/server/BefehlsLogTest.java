package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.auth.Kopplungen;
import at.greenchili.surfmate.testutil.Quelltext;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

/** 0.1.34 Befehls-Log: wer hat was wann gemacht – dauerhaft, ohne Geheimnisse und Orte. */
public class BefehlsLogTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File dir;
    private final long jetzt = System.currentTimeMillis();

    @Before
    public void ablage() throws Exception {
        dir = tmp.newFolder("befehlslog");
        BefehlsLog.starten(dir);
        BefehlsLog.vergessenFuerTest();
    }

    @After
    public void aus() {
        BefehlsLog.vergessenFuerTest();
        BefehlsLog.starten(null);
    }

    private static BefehlsLog.Mitschnitt antwort() {
        return new BefehlsLog.Mitschnitt(new ByteArrayOutputStream());
    }

    private static void sende(BefehlsLog.Mitschnitt m, int status, String json) throws Exception {
        HttpResponse.sendJson(m, status, json);
    }

    private JSONArray eintraege() throws Exception {
        return BefehlsLog.alsJson(100, jetzt + 1000).getJSONArray("eintraege");
    }

    private String alles() throws Exception {
        StringBuilder b = new StringBuilder();
        File[] f = dir.listFiles();
        for (File x : f == null ? new File[0] : f) b.append(new String(Files.readAllBytes(x.toPath()), StandardCharsets.UTF_8));
        return b.toString();
    }

    @Test
    public void aufsperrenMitKopplungUndFahrzeugbefehl() throws Exception {
        BefehlsLog.Mitschnitt m = antwort();
        BefehlsLog.beginne("POST", "/api/handy/v1/aktion", "{\"aktion\":\"aufsperren\",\"signatur\":\"GEHEIMSIG\","
                        + "\"challenge\":\"GEHEIMCHAL\",\"lat\":48.2082,\"lon\":16.3738}",
                "k7Glass", Kopplungen.ART_HANDY, "Glass Agent", false, true, m, jetzt);
        assertEquals("aufsperren", BefehlsLog.aktion());
        BefehlsLog.fahrzeug("unlock", "SUCCESS", "CLOUD");
        sende(m, 200, "{\"ok\":true,\"weg\":\"cloud\",\"bestaetigt\":false}");
        BefehlsLog.abschliessen();

        JSONArray a = eintraege();
        assertEquals(1, a.length());
        JSONObject e = a.getJSONObject(0);
        assertEquals("k7Glass", e.getString("wer"));
        assertEquals("Glass Agent", e.getString("name"));
        assertEquals("handy", e.getString("art"));
        assertEquals("bruecke", e.getString("weg"));
        assertEquals("/api/handy/v1/aktion", e.getString("pfad"));
        assertEquals("aufsperren", e.getString("aktion"));
        assertEquals("ok", e.getString("ergebnis"));
        assertEquals(200, e.getInt("status"));
        assertEquals("pflicht", e.getString("klasse"));
        assertEquals("fahrzeug.unlock:success@cloud", e.getJSONArray("ereignisse").getString(0));
        String roh = alles();
        assertFalse(roh.contains("GEHEIM"));
        assertFalse(roh.contains("48.2"));
        assertFalse(roh.contains("16.37"));
    }

    @Test
    public void abgelehnterBefehlMitGrund() throws Exception {
        BefehlsLog.Mitschnitt m = antwort();
        BefehlsLog.beginne("POST", "/api/handy/v1/aktion", "{\"aktion\":\"aufsperren\"}", "k2", "handy", "Pixel",
                false, false, m, jetzt);
        sende(m, 200, "{\"ok\":false,\"grund\":\"nicht_erlaubt\"}");
        BefehlsLog.abschliessen();
        JSONObject e = eintraege().getJSONObject(0);
        assertEquals("abgelehnt", e.getString("ergebnis"));
        assertEquals("nicht_erlaubt", e.getString("grund"));
        assertEquals("direkt", e.getString("weg"));

        // Wächter vor dem Handler: 403 mit eigenem Grund, Freitext der Antwort kommt nie ins Log
        m = antwort();
        BefehlsLog.beginne("POST", "/api/kopplung/rechte", "{\"id\":\"k2\",\"rechte\":[\"aufsperren\"]}",
                "k2", "handy", "Pixel", false, false, m, jetzt + 1);
        BefehlsLog.grund("nicht_fuer_kopplung");
        HttpResponse.sendError(m, 403, "Nicht für gekoppelte Geräte");
        BefehlsLog.abschliessen();
        e = eintraege().getJSONObject(0);
        assertEquals("abgelehnt", e.getString("ergebnis"));
        assertEquals("nicht_fuer_kopplung", e.getString("grund"));
        assertEquals(403, e.getInt("status"));

        // technischer Fehler bleibt Fehler, Freitext-Gründe werden zu http_<status>
        m = antwort();
        BefehlsLog.beginne("POST", "/api/handy/v1/aktion", "{\"aktion\":\"was_neues\"}", "k2", "handy", "Pixel",
                false, false, m, jetzt + 2);
        sende(m, 502, "{\"ok\":false,\"error\":\"Kaputt bei 48.2082\"}");
        BefehlsLog.abschliessen();
        e = eintraege().getJSONObject(0);
        assertEquals("unbekannt", e.getString("aktion"));
        assertEquals("fehler", e.getString("ergebnis"));
        assertEquals("http_502", e.getString("grund"));
        assertFalse(alles().contains("48.2"));
    }

    @Test
    public void rechteAenderungMitZielUndRechten() throws Exception {
        final JSONArray[] platte = {new JSONArray()};
        Kopplungen k = new Kopplungen(new Kopplungen.Speicher() {
            public JSONArray lade() { return platte[0]; }
            public boolean speichere(JSONArray g) { platte[0] = g; return true; }
        });
        String id = k.anlegen("Glass Agent", Kopplungen.ART_HANDY, jetzt);
        // ohne laufende Anfrage (z. B. Telegram/Automation): eigener Eintrag
        JSONObject angelegt = eintraege().getJSONObject(0);
        assertEquals("intern", angelegt.getString("art"));
        assertEquals(id, angelegt.getString("ziel"));
        assertTrue(angelegt.getJSONArray("ereignisse").getString(0).startsWith("kopplung.angelegt.handy:ok"));

        BefehlsLog.Mitschnitt m = antwort();
        BefehlsLog.beginne("POST", "/api/kopplung/rechte",
                "{\"id\":\"" + id + "\",\"rechte\":[\"klima\",\"aufsperren\",\"erfunden\"]}", null, null, null,
                true, false, m, jetzt + 5);
        assertTrue(k.rechteSetzen(id, Arrays.asList("klima", "aufsperren")));
        sende(m, 200, "{\"ok\":true}");
        BefehlsLog.abschliessen();

        JSONObject e = eintraege().getJSONObject(0);
        assertEquals("display", e.getString("wer"));
        assertEquals("lokal", e.getString("weg"));
        assertEquals(id, e.getString("ziel"));
        assertEquals("Glass Agent", e.getString("zielName"));
        assertEquals(new JSONArray(Arrays.asList("klima", "aufsperren")).toString(), e.getJSONArray("rechte").toString());
        String ev = e.getJSONArray("ereignisse").getString(0);
        assertTrue(ev, ev.startsWith("kopplung.rechte=") && ev.contains("aufsperren") && ev.endsWith(":gesetzt"));
        assertEquals("ok", e.getString("ergebnis"));
        assertEquals("pflicht", e.getString("klasse"));
    }

    @Test
    public void einstellungenNurNamenKeineWerteKeineOrte() throws Exception {
        BefehlsLog.Mitschnitt m = antwort();
        BefehlsLog.beginne("PUT", "/api/einstellungen?token=GEHEIMTOKEN",
                "{\"werte\":{\"update.mobilfunk\":true,\"telegram.token\":\"GEHEIMTOKEN\",\"heim.lat\":48.2082,"
                        + "\"heim.standort\":\"Hauptplatz 1\"}}",
                null, null, null, false, false, m, jetzt);
        sende(m, 200, "{\"ok\":true,\"gespeichert\":[\"update.mobilfunk\"]}");
        BefehlsLog.abschliessen();
        JSONObject e = eintraege().getJSONObject(0);
        assertEquals("login", e.getString("wer"));
        assertEquals("/api/einstellungen", e.getString("pfad"));
        String felder = e.getJSONArray("felder").toString();
        assertTrue(felder.contains("update.mobilfunk") && felder.contains("telegram.token") && felder.contains("{ort}"));
        String roh = alles();
        assertFalse(roh.contains("GEHEIMTOKEN"));
        assertFalse(roh.contains("48.2"));
        assertFalse(roh.contains("Hauptplatz"));
        assertFalse(roh.contains("heim.lat"));
    }

    @Test
    public void pfadMitKoordinatenOderKennungWirdEntschaerft() throws Exception {
        BefehlsLog.Mitschnitt m = antwort();
        BefehlsLog.beginne("POST", "/api/navi/ziel/48.2082,16.3738", null, null, null, null, true, false, m, jetzt);
        sende(m, 200, "{\"ok\":true}");
        BefehlsLog.abschliessen();
        JSONObject e = eintraege().getJSONObject(0);
        assertEquals("/api/navi/ziel/{x}", e.getString("pfad"));
        assertEquals("andere", e.getString("klasse"));
        assertFalse(alles().contains("48.2"));
    }

    @Test
    public void nurSchreibzugriffeAufApi() throws Exception {
        BefehlsLog.Mitschnitt m = antwort();
        BefehlsLog.beginne("GET", "/api/vehicle/state", null, "k1", "handy", "x", false, false, m, jetzt);
        assertNull(BefehlsLog.aktion());
        BefehlsLog.abschliessen();
        assertEquals(0, eintraege().length());
    }

    @Test
    public void fahrzeugbefehlOhneAnfrageMitHerkunft() throws Exception {
        at.greenchili.surfmate.gewohnheiten.Herkunft.setze(at.greenchili.surfmate.gewohnheiten.Herkunft.TELEGRAM);
        try {
            BefehlsLog.fahrzeug("climate-on", "FAILED", "SDK");
        } finally {
            at.greenchili.surfmate.gewohnheiten.Herkunft.setze(null);
        }
        JSONObject e = eintraege().getJSONObject(0);
        assertEquals("telegram", e.getString("wer"));
        assertEquals("fehler", e.getString("ergebnis"));
        assertEquals("fahrzeug.climate-on:failed@sdk", e.getJSONArray("ereignisse").getString(0));
    }

    @Test
    public void rotationBleibtBegrenztUndUeberstehtNeustart() throws Exception {
        String lang = new String(new char[200]).replace('\0', 'a');
        for (int i = 0; i < 3000; i++) {
            BefehlsLog.schreibe(BefehlsLog.ANDERE, new JSONObject().put("t", jetzt).put("wer", "x").put("pfad", "/api/" + lang));
        }
        File f = new File(dir, "andere.jsonl");
        File alt = new File(dir, "andere.jsonl.1");
        assertTrue(alt.isFile());
        assertTrue(f.length() <= BefehlsLog.MAX_BYTES + 1024);
        assertTrue(alt.length() <= BefehlsLog.MAX_BYTES + 1024);
        // Pflicht-Einträge werden vom Kleinkram nicht verdrängt
        BefehlsLog.schreibe(BefehlsLog.PFLICHT, new JSONObject().put("t", jetzt - 1).put("aktion", "aufsperren"));
        for (int i = 0; i < 3000; i++) {
            BefehlsLog.schreibe(BefehlsLog.ANDERE, new JSONObject().put("t", jetzt).put("pfad", "/api/" + lang));
        }
        // „Neustart“: neue Ablage auf denselben Ordner, Einträge sind noch da
        BefehlsLog.starten(null);
        BefehlsLog.starten(dir);
        JSONObject j = BefehlsLog.alsJson(5000, jetzt + 1000);
        boolean gefunden = false;
        JSONArray a = j.getJSONArray("eintraege");
        for (int i = 0; i < a.length(); i++) gefunden |= "aufsperren".equals(a.getJSONObject(i).optString("aktion"));
        assertTrue(gefunden);

        // älter als 30 Tage: ausgeblendet, und eine ganz alte Vorgängerdatei wird gelöscht
        BefehlsLog.schreibe(BefehlsLog.PFLICHT, new JSONObject().put("t", jetzt - 31 * BefehlsLog.TAG_MS).put("aktion", "zusperren"));
        assertFalse(BefehlsLog.alsJson(5000, jetzt).toString().contains("zusperren"));
        assertTrue(alt.setLastModified(jetzt - 40 * BefehlsLog.TAG_MS));
        BefehlsLog.alsJson(10, jetzt);
        assertFalse(alt.exists());
    }

    @Test
    public void ohneAblageWirdNichtsGeschrieben() throws Exception {
        BefehlsLog.starten(null);
        BefehlsLog.schreibe(BefehlsLog.PFLICHT, new JSONObject().put("t", jetzt));
        assertEquals(0, BefehlsLog.alsJson(10, jetzt).getJSONArray("eintraege").length());
    }

    @Test
    public void schreibZeileMitKopplungUndAktion() {
        assertEquals("SCHREIBEN POST /api/handy/v1/aktion von=gekoppelt kopplung=k7Glass aktion=aufsperren",
                SchreibProtokoll.zeile("POST", "/api/handy/v1/aktion", "gekoppelt", "k7Glass", "aufsperren"));
        assertEquals("SCHREIBEN POST /api/x von=lokal kopplung={x}",
                SchreibProtokoll.zeile("POST", "/api/x", "lokal", "a b", null));
        assertNull(SchreibProtokoll.zeile("GET", "/api/x", "lokal", "k", "aufsperren"));
    }

    @Test
    public void nichtFuerKopplungenUndHandyWeb() {
        assertTrue(ExternGuard.blocks("GET", "/api/system/befehle", Kopplungen.ART_HANDY));
        assertTrue(ExternGuard.blocks("GET", "/api/system/befehle", "dienst"));
        assertTrue(ExternGuard.blocks("GET", "/api/system/befehle", "homeassistant"));
        // 0.1.34: Web-Sitzung der Handy-App kommt durch, der Handler gibt ihr nur die eigenen Einträge
        assertFalse(WebHandyGuard.blocks("GET", "/api/system/befehle"));
        // Handy-Abruf: nur Art handy, Dienst/HA/Cockpit bekommen 403
        assertFalse(ExternGuard.blocks("GET", "/api/handy/v1/protokoll", Kopplungen.ART_HANDY));
        assertTrue(ExternGuard.blocks("GET", "/api/handy/v1/protokoll", Kopplungen.ART_DIENST));
        assertTrue(ExternGuard.blocks("GET", "/api/handy/v1/protokoll", "homeassistant"));
        assertTrue(ExternGuard.blocks("GET", "/api/handy/v1/protokoll", "cockpit"));
        assertTrue(ExternGuard.blocks("GET", "/api/handy/v1/protokoll", null));
        assertTrue(ExternGuard.blocks("POST", "/api/handy/v1/protokoll", Kopplungen.ART_HANDY));
    }

    private static Kopplungen kopplungen(JSONArray start) {
        final JSONArray[] platte = {start};
        return new Kopplungen(new Kopplungen.Speicher() {
            public JSONArray lade() { return platte[0]; }
            public boolean speichere(JSONArray g) { platte[0] = g; return true; }
        });
    }

    @Test
    public void rechtProtokollNieVonSelbst() throws Exception {
        // Bestand ohne Rechte-Stand, alter Stand 1 und 2, und neue Handy-Kopplung: nie „protokoll“
        JSONArray bestand = new JSONArray()
                .put(new JSONObject().put("id", "alt0").put("name", "A").put("art", "handy").put("angelegt", 1))
                .put(new JSONObject().put("id", "alt1").put("name", "B").put("art", "handy").put("angelegt", 2)
                        .put("rechte", new JSONArray().put("klima")).put("rechteStand", 1))
                .put(new JSONObject().put("id", "alt2").put("name", "C").put("art", "handy").put("angelegt", 3)
                        .put("rechte", new JSONArray().put("klima").put("update")).put("rechteStand", 2));
        Kopplungen k = kopplungen(bestand);
        for (String id : new String[]{"alt0", "alt1", "alt2"}) {
            assertFalse(id, k.hatRecht(id, Kopplungen.RECHT_PROTOKOLL));
        }
        String neu = k.anlegen("Neu", Kopplungen.ART_HANDY, jetzt);
        assertFalse(k.hatRecht(neu, Kopplungen.RECHT_PROTOKOLL));
        // nur der Besitzer setzt es (rechteSetzen = Panoptes-Web mit Login / Display)
        assertTrue(k.rechteSetzen(neu, Arrays.asList("klima", Kopplungen.RECHT_PROTOKOLL)));
        assertTrue(k.hatRecht(neu, Kopplungen.RECHT_PROTOKOLL));
        // Dienst, HA und Cockpit können es nicht tragen
        for (String art : new String[]{Kopplungen.ART_DIENST, "homeassistant", "cockpit"}) {
            String id = k.anlegen("x", art, jetzt);
            k.rechteSetzen(id, Arrays.asList(Kopplungen.RECHT_PROTOKOLL));
            assertFalse(art, k.hatRecht(id, Kopplungen.RECHT_PROTOKOLL));
        }
    }

    private void eintrag(String wer, String art, String name, String ziel) throws Exception {
        JSONObject e = new JSONObject().put("t", jetzt).put("wer", wer).put("art", art).put("weg", "direkt")
                .put("pfad", "/api/handy/v1/aktion").put("aktion", "aufsperren").put("ergebnis", "ok");
        if (name != null) e.put("name", name);
        if (ziel != null) e.put("ziel", ziel).put("zielName", "Ziel").put("rechte", new JSONArray().put("aufsperren"));
        BefehlsLog.schreibe(BefehlsLog.PFLICHT, e);
    }

    @Test
    public void handySiehtNurEigeneOhneRecht() throws Exception {
        eintrag("kEigen", "handy", "Mein Handy", null);
        eintrag("kFremd", "handy", "Anderes Handy", null);
        eintrag("display", "display", null, "kFremd");
        JSONObject r = BefehlsLog.fuerKopplung("kEigen", false, 500, jetzt + 1000);
        assertTrue(r.getBoolean("nurEigene"));
        JSONArray a = r.getJSONArray("eintraege");
        assertEquals(1, a.length());
        assertEquals("kEigen", a.getJSONObject(0).getString("wer"));
        assertTrue(a.getJSONObject(0).getBoolean("eigen"));
        assertFalse(r.toString().contains("kFremd"));
        assertFalse(r.toString().contains("Anderes Handy"));

        // mit Recht „protokoll“: alle, aber keine fremden Kopplungs-ids
        r = BefehlsLog.fuerKopplung("kEigen", true, 500, jetzt + 1000);
        assertFalse(r.getBoolean("nurEigene"));
        assertEquals(3, r.getJSONArray("eintraege").length());
        assertFalse(r.toString().contains("kFremd"));
        assertTrue(r.toString().contains("Anderes Handy"));
        assertTrue(r.toString().contains("anderes_geraet"));

        // höchstens 200 je Abruf
        for (int i = 0; i < 250; i++) eintrag("kEigen", "handy", "Mein Handy", null);
        assertEquals(BefehlsLog.MAX_HANDY, BefehlsLog.fuerKopplung("kEigen", false, 9999, jetzt + 1000)
                .getJSONArray("eintraege").length());
    }

    @Test
    public void handyEndpunktMitRatenlimitUndNurHandy() throws Exception {
        Kopplungen k = kopplungen(new JSONArray());
        Kopplungen vorher = HandyApiHandler.kopplungen;
        HandyApiHandler.kopplungen = k;
        try {
            String handy = k.anlegen("Handy", Kopplungen.ART_HANDY, jetzt);
            String ha = k.anlegen("HA", "homeassistant", jetzt);
            eintrag(handy, "handy", "Handy", null);
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            HandyApiHandler.handle("GET", "/api/handy/v1/protokoll", null, o, ha);
            assertTrue(o.toString("UTF-8").startsWith("HTTP/1.1 403"));
            int ok = 0, limit = 0;
            for (int i = 0; i < at.greenchili.surfmate.handy.HandyRatenlimit.AKTIONEN_JE_MINUTE + 2; i++) {
                o = new ByteArrayOutputStream();
                HandyApiHandler.handle("GET", "/api/handy/v1/protokoll?max=5", null, o, handy);
                String s = o.toString("UTF-8");
                if (s.startsWith("HTTP/1.1 200")) {
                    ok++;
                    assertTrue(s.contains("\"nurEigene\":true"));
                }
                if (s.startsWith("HTTP/1.1 429")) limit++;
            }
            assertEquals(at.greenchili.surfmate.handy.HandyRatenlimit.AKTIONEN_JE_MINUTE, ok);
            assertEquals(2, limit);
        } finally {
            HandyApiHandler.kopplungen = vorher;
        }
    }

    @Test
    public void anzeigeImSystemMenue() throws Exception {
        String html = Quelltext.lies(Paths.get("src/main/assets/web/local/system.html"));
        assertTrue(html.contains("id=\"protokoll\"") && html.contains("befehlslog.js")
                && html.contains("PanoptesBefehlslog.init("));
        String gruppen = Quelltext.lies(Paths.get("src/main/assets/web/shared/system-overview.js"));
        assertTrue(gruppen.contains("'kopplung', 'protokoll'"));
        String js = Quelltext.lies(Paths.get("src/main/assets/web/shared/befehlslog.js"));
        assertTrue(js.contains("/api/system/befehle"));
        // Einträge nur als Text, nie als HTML (Gerätenamen kommen vom Gerät)
        assertFalse(java.util.regex.Pattern.compile("innerHTML\\s*=\\s*[^'\\s]").matcher(js).find());
        for (String l : new String[]{"de", "en", "fr", "it", "nl"}) {
            JSONObject k = new JSONObject(Quelltext.lies(Paths.get("src/main/assets/web/i18n/" + l + ".json")));
            assertTrue(l, k.getJSONObject("protokoll").has("titel") && k.getJSONObject("protokoll").has("a_aufsperren"));
        }
    }

    @Test
    public void verdrahtung() throws Exception {
        String h = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/HttpServer.java"));
        int auth = h.indexOf("if (!authenticated) {");
        int beginn = h.indexOf("BefehlsLog.beginne(", auth);
        int guard = h.indexOf("ExternGuard.blocks(method, pathOnly,", auth);
        assertTrue("vor den Wächtern, damit Ablehnungen mitkommen", auth > 0 && beginn > auth && guard > beginn);
        assertTrue(h.contains("new BefehlsLog.Mitschnitt("));
        int fin = h.indexOf("BefehlsLog.abschliessen();");
        assertTrue(fin > 0 && h.lastIndexOf("finally {", fin) > guard);
        String r = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/byd/routing/VehicleCommandRouter.java"));
        assertTrue(r.contains("befehlsLog(cmd, r);"));
        String s = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/SystemApiHandler.java"));
        int p = s.indexOf("\"/api/system/befehle\"");
        int extern = s.indexOf("if (AuthMiddleware.isExternRequest())", p);
        int web = s.indexOf("HandyApiHandler.protokoll(path, kid, out);", p);
        assertTrue("Kopplungen 403, Web-Sitzung nur über den Handy-Filter", p > 0 && extern > p && web > extern);
    }
}
