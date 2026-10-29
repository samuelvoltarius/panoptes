package at.greenchili.surfmate.gewohnheiten;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.automation.Automation;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/** 0.1.23 Gewohnheiten E3 (Karte), E4 (Probelauf), E5 (Aktionen) – GEWOHNHEITEN_KONZEPT §4–§7. */
public class GewohnheitenKarteTest {

    private static final ZoneId Z = ZoneId.of("Europe/Vienna");

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** Automationen im Speicher, Orte mit Heimort „Daheim“. */
    static final class Speicher implements Angebot.Regeln {
        final Map<String, Automation> m = new LinkedHashMap<>();
        int n;

        public Map<String, Automation> alle() { return m; }
        public String speichere(String id, Automation a) {
            String i = id == null ? "r" + (++n) : id;
            m.put(i, a);
            return i;
        }
        public boolean modus(String id, String modus) {
            Automation a = m.get(id);
            if (a == null) return false;
            a.setMode(modus);
            return true;
        }
        public boolean loesche(String id) { return m.remove(id) != null; }
        public JSONArray orte() {
            try {
                return new JSONArray().put(new JSONObject().put("id", "h").put("name", "Daheim").put("lat", 48.3).put("lon", 14.235)
                        .put("r", 50).put("eigenschaften", new JSONObject().put("zuhause", true)));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private Gewohnheiten g;
    private GewohnheitenTest.TestUmgebung u;
    private Speicher s;
    private Angebot a;

    private void aufbau() throws Exception {
        u = new GewohnheitenTest.TestUmgebung();
        u.einstellung = new JSONObject().put("an", true);
        u.t = ms(2026, 10, 27, 12, 0);
        g = new Gewohnheiten(tmp.getRoot(), u, Runnable::run);
        s = new Speicher();
        a = new Angebot(g, s);
        g.setzeAngebot(a);
        // drei Werktage um ~7:00 zu Hause Klima 22 °C (Handy), unter 5 °C
        for (int[] d : new int[][] {{20, 418}, {21, 424}, {22, 421}}) {
            g.log.schreibe(new JSONObject().put("t", ms(2026, 10, d[0], d[1] / 60, d[1] % 60)).put("aktion", Aktionen.KLIMA_AN)
                    .put("wert", new JSONObject().put("temp", 22)).put("quelle", Herkunft.HANDY)
                    .put("kontext", new JSONObject().put("tag", "werktag").put("min", d[1]).put("ort", "zuhause").put("aussen", 3)));
        }
    }

    @Test
    public void vorschlagJaLegtProbelaufAnUndFreigabeNachDreiRichtigen() throws Exception {
        aufbau();
        JSONObject k = a.karte();
        assertEquals("vorschlag", k.getString("art"));
        JSONObject v = k.getJSONObject("vorschlag");
        assertEquals("07:00", v.getString("uhrzeit"));
        assertEquals(Herkunft.HANDY, v.getString("quelle_meist"));

        JSONObject r = a.antworte(new JSONObject().put("art", "vorschlag").put("schluessel", v.getString("schluessel")).put("antwort", "ja"));
        assertTrue(r.toString(), r.getBoolean("ok"));
        Automation regel = s.m.get(r.getString("automation"));
        assertTrue("zuerst nur Probelauf", regel.isProbe());
        assertFalse("Probelauf ist für die Ereignisse aktiv", regel.isDisabled());
        JSONObject j = regel.toJson();
        assertTrue("ältere Builds sehen die Regel als aus", j.getBoolean("disabled"));
        assertTrue(j.getBoolean("probe"));
        assertEquals("setAc", j.getJSONArray("actions").getJSONObject(0).getString("type"));
        String bed = j.getJSONArray("conditions").toString();
        assertTrue("F3: 15 min früher vorheizen", bed.contains("\"value\":405"));
        assertTrue(bed.contains("Daheim") && bed.contains("saturday") && bed.contains("sunday") && bed.contains("outsideTemp"));
        assertTrue("Runde: so geladen, wie gespeichert", Automation.fromJson(j).isProbe());

        assertEquals("kein zweiter Vorschlag für dieselbe Gewohnheit", "keine", a.karte().getString("art"));

        // drei Probeläufe: zweimal „passt“, einmal hat der Nutzer es binnen 30 min selbst gemacht
        String id = r.getString("automation");
        for (int i = 0; i < 2; i++) {
            u.t += 86_400_000L;
            assertTrue(a.ausgeloest(id));
            JSONObject pk = a.karte();
            assertEquals("probelauf", pk.getString("art"));
            assertTrue(a.antworte(new JSONObject().put("art", "probelauf").put("automation", id).put("antwort", "passt")).getBoolean("ok"));
        }
        u.t += 86_400_000L;
        a.ausgeloest(id);
        g.notiere(Aktionen.KLIMA_AN, new JSONObject().put("temp", 22), Herkunft.AUTO, null);
        assertEquals(3, regel.getGewohnheit().getInt("richtig"));

        JSONObject f = a.karte();
        assertEquals("freigabe", f.getString("art"));
        assertTrue(a.antworte(new JSONObject().put("art", "freigabe").put("automation", id).put("antwort", "ja")).getBoolean("ok"));
        assertFalse(regel.isProbe());
        assertEquals(Automation.MODE_AUTOMATIC, regel.getMode());
    }

    @Test
    public void neinSperrtFuerImmerSpaeterDreimalIstNein() throws Exception {
        aufbau();
        String sch = a.karte().getJSONObject("vorschlag").getString("schluessel");
        assertTrue(a.antworte(new JSONObject().put("art", "vorschlag").put("schluessel", sch).put("antwort", "nein")).getBoolean("ok"));
        u.t += 5 * 86_400_000L;
        assertEquals("nie wieder", "keine", a.karte().getString("art"));
        assertTrue(GewohnheitsErkenner.menge(g.gesperrt()).contains(sch));

        tmp.delete();
        tmp.create();
        aufbau();
        for (int i = 1; i <= 3; i++) {
            JSONObject k = a.karte();
            assertEquals("Später Nr. " + i, "vorschlag", k.getString("art"));
            a.antworte(new JSONObject().put("art", "vorschlag").put("schluessel", sch).put("antwort", "spaeter"));
            assertEquals("frühestens in 3 Tagen", "keine", a.karte().getString("art"));
            u.t += 3 * 86_400_000L + 1;
        }
        assertEquals("beim 3. Später gilt es als Nein", "keine", a.karte().getString("art"));
        assertTrue(GewohnheitsErkenner.menge(g.gesperrt()).contains(sch));
    }

    @Test
    public void probelaufFuehrtNieEineAktionAus() throws Exception {
        String q = lies("app/src/main/java/at/greenchili/surfmate/automation/Automations.java");
        int i = q.indexOf("static boolean triggerQueuedActions(");
        assertTrue(q.indexOf("Probelauf.ausgeloest(id, automation)", i) < q.indexOf("cursor.beginAttempt();", i));
        int j = q.indexOf("private static boolean triggerActionsInternal(");
        assertTrue(q.indexOf("automation.isProbe()", j) < q.indexOf("recordStats) automation.recordTriggered", j));
        assertTrue(q.contains("automation.isDisabled() || automation.isProbe()) return true;"));
    }

    @Test
    public void ohneHeimortKeineRegel() throws Exception {
        aufbau();
        JSONObject v = a.karte().getJSONObject("vorschlag");
        try {
            RegelBau.baue(v, new JSONArray());
            assertTrue("muss scheitern", false);
        } catch (RegelBau.Fehler f) {
            assertEquals("ort_unbekannt", f.grund);
        }
    }

    @Test
    public void aktionenAusDemBestehendenKatalog() throws Exception {
        assertEquals("wallboxStrom", RegelBau.aktion(Aktionen.WALLBOX_STROM, new JSONObject().put("a", 16)).getType());
        assertEquals("seat", RegelBau.aktion(Aktionen.SITZHEIZUNG, new JSONObject().put("sitz", "fahrer").put("stufe", 2)).getType());
        assertEquals("surveillance", RegelBau.aktion(Aktionen.WAECHTER_AN, new JSONObject()).getType());
        assertEquals("camping", RegelBau.aktion(Aktionen.CAMPING_AN, new JSONObject()).getType());
        for (String nie : new String[] {Aktionen.WAECHTER_AUS, Aktionen.LADEPLAN, "aufsperren"}) {
            try {
                RegelBau.aktion(nie, new JSONObject());
                assertTrue(nie, false);
            } catch (RegelBau.Fehler f) {
                assertEquals("nicht_vorschlagbar", f.grund);
            }
        }
        String c = lies("app/src/main/java/at/greenchili/surfmate/automation/action/Actions.java");
        assertTrue(c.contains("\"/api/wallbox/automation\"") && c.contains("\"/api/camping\"") && c.contains("\"/api/ladeplan\""));
        String h = lies("app/src/main/java/at/greenchili/surfmate/server/HttpServer.java");
        assertTrue("genau drei Wege, keine Präfixe", h.contains("clean.equals(\"/api/wallbox/automation\") || clean.equals(\"/api/camping\") || clean.equals(\"/api/ladeplan\")"));
        assertTrue(lies("app/src/main/java/at/greenchili/surfmate/server/LadeplanApiHandler.java").contains("HttpServer.isAutomationRequest() && (e != null"));
        assertTrue(lies("app/src/main/java/at/greenchili/surfmate/server/WallboxApiHandler.java").contains("nur_automation"));
    }

    @Test
    public void karteFuerCockpitUndHandy() throws Exception {
        assertTrue(at.greenchili.surfmate.server.ExternGuard.erlaubt("GET", "/api/gewohnheiten/karte"));
        assertTrue(at.greenchili.surfmate.server.ExternGuard.erlaubt("POST", "/api/gewohnheiten/antwort"));
        assertFalse("das Log nie", at.greenchili.surfmate.server.ExternGuard.erlaubt("POST", "/api/gewohnheiten"));
        assertFalse(at.greenchili.surfmate.server.ExternGuard.blocks("POST", "/api/handy/v1/gewohnheit", "handy"));
        assertTrue(lies("app/src/main/java/at/greenchili/surfmate/server/HandyApiHandler.java").contains("case \"/api/handy/v1/gewohnheit\""));
    }

    @Test
    public void ausgeschaltetKeineKarte() throws Exception {
        aufbau();
        u.einstellung = new JSONObject().put("an", false);
        assertEquals("keine", a.karte().getString("art"));
    }

    @Test
    public void probeWirdAlsAusGespeichertFuerAlteBuilds() throws Exception {
        aufbau();
        JSONObject v = a.karte().getJSONObject("vorschlag");
        Automation r = RegelBau.baue(v, s.orte());
        JSONObject j = r.toJson();
        j.remove("probe");   // so liest ein älterer Build: nur „disabled“
        Automation alt = Automation.fromJson(j);
        assertNotNull(alt);
        assertTrue(alt.isDisabled());
        assertFalse(alt.isProbe());
    }

    @Test
    public void karteAmDisplayNurImStandMitAllenSprachen() throws Exception {
        String js = lies("app/src/main/assets/web/shared/gewohnheit-karte.js");
        assertTrue("nur im Stand", js.contains("!k.imStand"));
        assertTrue(js.contains("'/api/gewohnheiten/karte'") && js.contains("'/api/gewohnheiten/antwort'"));
        assertFalse("kein Ton", js.contains("Audio(") || js.contains(".play("));
        String index = lies("app/src/main/assets/web/local/index.html");
        assertTrue(index.contains("shared/gewohnheit-karte.js") && index.contains("PanoptesGewohnheit.init()"));
        JSONObject de = new JSONObject(lies("app/src/main/assets/web/i18n/de.json")).getJSONObject("gewohnheit");
        for (String l : new String[] {"en", "fr", "it", "nl"}) {
            JSONObject x = new JSONObject(lies("app/src/main/assets/web/i18n/" + l + ".json")).getJSONObject("gewohnheit");
            for (java.util.Iterator<String> it = de.keys(); it.hasNext(); ) {
                String k = it.next();
                assertFalse(l + "." + k, x.optString(k).isEmpty());
            }
        }
        for (String a : Aktionen.VORSCHLAGBAR) assertFalse("Satz für " + a, de.optString(a).isEmpty());
    }

    private static long ms(int j, int mo, int d, int h, int mi) {
        return LocalDateTime.of(j, mo, d, h, mi).atZone(Z).toInstant().toEpochMilli();
    }

    private static String lies(String rel) throws Exception {
        Path p = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (p != null) {
            for (Path c : new Path[] {p.resolve(rel), p.resolve(rel.replaceFirst("^app/", ""))}) {
                if (Files.isRegularFile(c)) return new String(Files.readAllBytes(c), "UTF-8");
            }
            p = p.getParent();
        }
        throw new java.io.FileNotFoundException(rel);
    }
}
