package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.sprache.SprachBefehle;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * 0.1.44 (P2) Kern-Route der Sprachbefehle: nur die Panoptes-App direkt am Auto, nur mit Schalter, nur feste Befehle,
 * Fahrzeugbefehle erst nach Rückfrage mit einmaliger Marke, Klima über die Stufen-Regeln der Launcher-Klima-Kachel.
 */
public class SprachApiHandlerTest {

    private final List<String> klima = new ArrayList<>();
    private final List<String> tasten = new ArrayList<>();
    private boolean schalter = true;
    private Integer km = 287;
    private String ort = null;
    private SprachApiHandler.Kern vorher;

    @Before
    public void auf() {
        vorher = SprachApiHandler.kern;
        SprachApiHandler.kern = new SprachApiHandler.Kern() {
            @Override public boolean schalterAn() { return schalter; }
            @Override public Object[] klima(String aktion, long jetzt) throws Exception {
                klima.add(aktion);
                return new Object[]{200, new JSONObject().put("ok", true).put("aktion", aktion)};
            }
            @Override public boolean medientaste(String taste) { tasten.add(taste); return true; }
            @Override public Integer reichweiteKm() { return km; }
            @Override public String ort() { return ort; }
        };
    }

    @After
    public void ab() {
        SprachApiHandler.kern = vorher;
        SprachAufrufer.vergessen();
    }

    private static JSONObject j(Object[] r) { return (JSONObject) r[1]; }

    private static Object[] rufe(String body) throws Exception { return SprachApiHandler.ausfuehren(body, System.currentTimeMillis()); }

    @Test
    public void abWerkAusUndNurFesteBefehle() throws Exception {
        schalter = false;
        Object[] r = rufe("{\"befehl\":\"klima_an\"}");
        assertEquals(409, r[0]);
        assertEquals("schalter_aus", j(r).getString("grund"));
        assertTrue(klima.isEmpty());
        schalter = true;
        for (String b : new String[] {"hupe", "horn", "licht_an", "blinker", "alarm", "aufsperren", "fenster_auf", "verboten",
                "navi_heim", "ja", "", "KLIMA_AN"}) {
            r = rufe(new JSONObject().put("befehl", b).toString());
            assertEquals(b, 400, r[0]);
        }
        assertEquals(400, rufe("kein json")[0]);
        assertTrue(klima.isEmpty() && tasten.isEmpty());
    }

    @Test
    public void klimaErstNachRueckfrageMitEinmaligerMarke() throws Exception {
        Object[] r = rufe("{\"befehl\":\"klima_an\"}");
        assertEquals(200, r[0]);
        assertTrue(j(r).getBoolean("rueckfrage"));
        String marke = j(r).getString("marke");
        assertFalse(marke.isEmpty());
        assertTrue("ohne Ja passiert nichts", klima.isEmpty());
        assertEquals(409, rufe(new JSONObject().put("befehl", "klima_an").put("marke", "falsch").toString())[0]);
        assertEquals("Marke gilt nur für ihren Befehl", 409,
                rufe(new JSONObject().put("befehl", "klima_aus").put("marke", marke).toString())[0]);
        Object[] neu = rufe("{\"befehl\":\"waermer\"}");
        String m2 = j(neu).getString("marke");
        r = rufe(new JSONObject().put("befehl", "waermer").put("marke", m2).toString());
        assertEquals(200, r[0]);
        assertEquals("waermer", j(r).getString("befehl"));
        assertEquals(java.util.Collections.singletonList("temp_hoch"), klima);
        assertEquals("einmal", 409, rufe(new JSONObject().put("befehl", "waermer").put("marke", m2).toString())[0]);
        assertEquals(1, klima.size());
    }

    @Test
    public void musikReichweiteOrt() throws Exception {
        assertTrue(j(rufe("{\"befehl\":\"musik_weiter\"}")).getBoolean("ok"));
        rufe("{\"befehl\":\"musik_zurueck\"}");
        rufe("{\"befehl\":\"musik_pause\"}");
        assertEquals(java.util.Arrays.asList("next", "previous", "pause"), tasten);
        assertTrue("Musik ohne Rückfrage, kein Fahrzeugbefehl", klima.isEmpty());
        JSONObject r = j(rufe("{\"befehl\":\"reichweite\"}"));
        assertEquals(287, r.getInt("km"));
        km = null;
        assertEquals("unbekannt", j(rufe("{\"befehl\":\"reichweite\"}")).getString("grund"));
        assertEquals("unbekannt", j(rufe("{\"befehl\":\"wo_bin_ich\"}")).getString("grund"));
        ort = "Salzburg";
        assertEquals("Salzburg", j(rufe("{\"befehl\":\"wo_bin_ich\"}")).getString("ort"));
    }

    @Test
    public void klimaNurInStufenWieDieLauncherKachel() throws Exception {
        for (String b : SprachBefehle.FAHRZEUG)
            assertTrue(b, LauncherKlimaBefehl.KLIMA_AKTIONEN.contains(SprachBefehle.KLIMA_AKTION.get(b)));
        assertFalse("keine Gradzahl, keine Komfort-/Sitzaktionen", SprachBefehle.KLIMA_AKTION.containsValue("soll"));
        String q = TestLies.lies("app/src/main/java/at/greenchili/surfmate/server/SprachApiHandler.java");
        assertTrue(q.contains("LauncherKlimaBefehl.ausfuehren(new JSONObject().put(\"aktion\", aktion).toString(), jetzt, \"sprache.klima.\")"));
        assertFalse(q.contains("VehicleCommandRouter") || q.contains("FahrzeugAktionen") || q.contains("ClimateSetTempCommand"));
        String kl = TestLies.lies("app/src/main/java/at/greenchili/surfmate/server/LauncherKlimaBefehl.java");
        assertTrue(kl.contains("return ausfuehren(body, jetzt, \"launcher.klima.\");"));
        String http = TestLies.lies("app/src/main/java/at/greenchili/surfmate/server/HttpServer.java");
        assertTrue(http.contains("return SprachApiHandler.handle(method, path, body, out);"));
    }

    // ------------------------------------------------------------ Aufrufer

    private static SprachAufrufer.Quelle quelle(int port, int uid, String liste) {
        return new SprachAufrufer.Quelle() {
            @Override public int quellport() { return port; }
            @Override public int uid(int a, int b) { return uid; }
            @Override public String paketListe() { return liste; }
            @Override public long jetzt() { return 1000; }
        };
    }

    @Test
    public void nurDiePanoptesAppDirektAmAuto() {
        String liste = "package:at.greenchili.surfmate.extra uid:10999\npackage:at.greenchili.surfmate uid:10123\n";
        assertEquals(10123, SprachAufrufer.uidAus(liste));
        assertEquals(-1, SprachAufrufer.uidAus("package:at.greenchili.surfmate.extra uid:10999"));
        assertEquals(-1, SprachAufrufer.uidAus(null));
        SprachAufrufer.Quelle alt = SprachAufrufer.quelle;
        try {
            SprachAufrufer.vergessen();
            SprachAufrufer.quelle = quelle(0, 10123, liste);
            assertEquals(SprachAufrufer.NICHT_LOKAL, SprachAufrufer.pruefe());
            SprachAufrufer.quelle = quelle(5555, 2000, liste);   // shell
            assertEquals(SprachAufrufer.NICHT_APP, SprachAufrufer.pruefe());
            SprachAufrufer.quelle = quelle(5555, 10999, liste);  // andere App (z. B. Launcher)
            assertEquals(SprachAufrufer.NICHT_APP, SprachAufrufer.pruefe());
            SprachAufrufer.quelle = quelle(5555, 10123, liste);
            assertNull(SprachAufrufer.pruefe());
            SprachAufrufer.vergessen();
            SprachAufrufer.quelle = quelle(5555, 10123, "");      // App nicht gefunden
            assertEquals(SprachAufrufer.NICHT_APP, SprachAufrufer.pruefe());
        } finally {
            SprachAufrufer.quelle = alt;
            SprachAufrufer.vergessen();
        }
        String h = TestLies.lies("app/src/main/java/at/greenchili/surfmate/server/SprachApiHandler.java");
        assertTrue("erst Aufrufer, dann alles andere",
                h.indexOf("SprachAufrufer.pruefe()") < h.indexOf("Object[] r = ausfuehren(body, System.currentTimeMillis());"));
        assertTrue(h.contains("String wer = AuthMiddleware.isDirektAmAuto() ? SprachAufrufer.pruefe() : SprachAufrufer.NICHT_LOKAL;"));
    }

    /** Quelltext lesen (Arbeitsordner Modul oder Projekt). */
    static final class TestLies {
        static String lies(String rel) {
            try {
                java.io.File f = new java.io.File(rel);
                if (!f.isFile()) f = new java.io.File(rel.replaceFirst("^app/", ""));
                return new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8).replace("\r\n", "\n");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
    }
}
