package at.greenchili.surfmate.planer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** 0.1.25 Route ins Auto vom Handy (Weg A): planen ab Auto-Position, ins Planer-Postfach; nichts startet von selbst. */
public class HandyRouteTest {

    final List<String> aufrufe = new ArrayList<>();
    final List<String> bodies = new ArrayList<>();
    double[] pos = {48.306, 14.286};   // öffentlicher Ort (Linz Hauptplatz), keine Park-Position
    String[] token = {"X-Geraete-Token", "t"};
    int planCode = 200, anAutoCode = 200;
    int neuGeladen;

    final HandyRoute r = new HandyRoute(new HandyRoute.Umgebung() {
        public double[] position() { return pos; }
        public double soc() { return 64.4; }
        public String autoId() { return "a-1"; }
        public String[] token() { return token; }
        public Object[] http(String m, String op, String[] t, String body, int leseMs) {
            aufrufe.add(m + " " + op);
            bodies.add(body);
            try {
                if (op.equals("plan")) return new Object[]{planCode, planErgebnis()};
                if (op.equals("an-auto")) return new Object[]{anAutoCode, new JSONObject().put("ok", anAutoCode == 200)};
                return new Object[]{200, new JSONObject().put("ok", true)};
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        public String planBody(String body) { return body; }
        public void routeNeuLaden() { neuGeladen++; }
    }, Runnable::run);   // im Test sofort statt im Hintergrund

    /** Form wie das echte Ergebnis von POST plan (Routenplaner 28.10.): route.{km,minuten,geometrie}, fahrzeug-Objekt, saeule. */
    static JSONObject planErgebnis() throws Exception {
        JSONArray geo = new JSONArray();
        for (int i = 0; i < 4289; i++) geo.put(new JSONArray().put(48.3 - i * 0.00002).put(14.29 + i * 0.0005));
        JSONObject saeule = new JSONObject().put("name", "Ansfelden").put("lat", 48.21).put("lon", 14.29).put("km_auf_route", 160.2)
                .put("kw", 150).put("cpo", "Ionity");
        return new JSONObject().put("ok", true)
                .put("route", new JSONObject().put("km", 302.7).put("minuten", 161).put("geometrie", geo))
                .put("fahrzeug", new JSONObject().put("name", "BYD Dolphin Surf").put("kwh", 43.2))
                .put("soc_ankunft", 18)
                .put("stopps", new JSONArray().put(new JSONObject().put("saeule", saeule).put("soc_vorher", 24).put("soc_nachher", 70)
                        .put("ladedauer_min", 24.6).put("kwh", 37.8)))
                .put("zwischenziele", new JSONArray().put(new JSONObject().put("name", "Linz").put("lat", 48.3).put("lon", 14.29)
                        .put("km_auf_route", 130.0).put("aufenthalt_min", 0)));
    }

    static JSONObject ziel() throws Exception {
        return new JSONObject().put("ziel", new JSONObject().put("lat", 48.21).put("lon", 16.37).put("name", "Wien"));
    }

    @Test
    public void plantAbAutoPositionUndLegtInsPostfach() throws Exception {
        JSONObject req = ziel().put("zwischenziele", new JSONArray().put(new JSONObject().put("lat", 48.3).put("lon", 14.29).put("name", "Linz")));
        JSONObject a = r.starten("k-1", req, 1L);
        assertTrue(a.toString(), a.getBoolean("ok"));
        assertEquals("plant", a.getString("status"));
        assertEquals("[POST plan, GET fahrtenbuch/offen?auto=a-1, POST an-auto]", aufrufe.toString());
        JSONObject plan = new JSONObject(bodies.get(0));
        assertEquals(48.306, plan.getJSONArray("start").getDouble(0), 1e-9);
        assertEquals(16.37, plan.getJSONArray("ziel").getDouble(1), 1e-9);
        assertEquals("Linz", plan.getJSONArray("via").getJSONObject(0).getString("name"));
        assertEquals(64, plan.getInt("soc_start"));
        assertEquals("a-1", plan.getString("auto"));
        JSONObject an = new JSONObject(bodies.get(2));
        assertEquals("nur die eigene Kennung", "a-1", an.getString("auto"));
        JSONObject pp = an.getJSONObject("plan");
        assertFalse(pp.has("ok"));
        assertFalse("nicht das rohe Ergebnis", pp.has("route"));
        assertEquals("Wien", pp.getString("titel"));
        assertEquals(302.7, pp.getDouble("km"), 1e-9);
        assertEquals(161, pp.getInt("minuten"));
        assertEquals("BYD Dolphin Surf", pp.getString("fahrzeug"));
        assertEquals(64, pp.getInt("soc_start"));
        assertEquals(18, pp.getInt("soc_ankunft"));
        JSONObject st = pp.getJSONArray("stopps").getJSONObject(0);
        assertEquals("Ansfelden", st.getString("name"));
        assertEquals(160.2, st.getDouble("km"), 1e-9);
        assertEquals(24, st.getInt("soc_an"));
        assertEquals(70, st.getInt("soc_ab"));
        assertEquals(25, st.getInt("dauer_min"));
        assertEquals(130.0, pp.getJSONArray("zwischenziele").getJSONObject(0).getDouble("km"), 1e-9);
        JSONArray g = pp.getJSONArray("geometrie");
        assertEquals("ausgedünnt", HandyRoute.MAX_PUNKTE, g.length());
        assertEquals("letzter Punkt bleibt", 14.29 + 4288 * 0.0005, g.getJSONArray(g.length() - 1).getDouble(1), 1e-9);
        assertTrue("klein genug für den Planer (400 KB)", bodies.get(2).length() < 100_000);
        assertEquals("Karte am Display neu lesen", 1, neuGeladen);
        JSONObject s = r.stand("k-1");
        assertEquals("bereit", s.getString("status"));
        assertEquals(302.7, s.getDouble("km"), 1e-9);
        assertEquals("keiner", r.stand("k-andere").getString("status"));
    }

    @Test
    public void ehrlicheGruende() throws Exception {
        assertEquals("werte", r.starten("k", new JSONObject().put("ziel", new JSONObject().put("lat", 0).put("lon", 0)), 1).getString("grund"));
        JSONArray sechs = new JSONArray();
        for (int i = 0; i < 6; i++) sechs.put(new JSONObject().put("lat", 48).put("lon", 14 + i * 0.1));
        assertEquals("zu_viele_zwischenziele", r.starten("k", ziel().put("zwischenziele", sechs), 1).getString("grund"));
        pos = null;
        assertEquals("keine_position", r.starten("k", ziel(), 1).getString("grund"));
        pos = new double[]{48.2085, 16.3731};   // Wien Stephansplatz (öffentlich)
        token = null;
        assertEquals("kein_planer", r.starten("k", ziel(), 1).getString("grund"));
        assertTrue("nichts ging ins Netz", aufrufe.isEmpty());
        token = new String[]{"X-Geraete-Token", "t"};
        anAutoCode = 403;
        r.starten("k", ziel(), 1);
        assertEquals("planer_recht_fehlt", r.stand("k").getString("grund"));
        anAutoCode = 200;
        planCode = 400;
        r.starten("k", ziel(), 1);
        assertEquals("planer", r.stand("k").getString("grund"));
        assertEquals("ohne Plan kein an-auto", 0, neuGeladen);
    }

    /**
     * Echtes Ergebnis von {@code POST plan} (Projekt-Server, 28.10.2026, ohne Schlüssel = ohne Mitgliedsdaten): Linz Hauptplatz →
     * Wien Stephansplatz → Graz Hauptplatz, Dolphin Surf Boost, nur öffentliche Orte (0.1.26.1: das frühere Fixture
     * begann nahe einer privaten Park-Position und ist entfernt). Roh 461 KB – über der 400-KB-Grenze von an-auto
     * (Befund Routenplaner); abgebildet wie der Web-Planer muss es weit darunter liegen und alle Pflichtfelder haben.
     */
    @Test
    public void echterPlanPasstInsPostfach() throws Exception {
        String roh;
        try (java.io.InputStream in = HandyRouteTest.class.getResourceAsStream("/planer/plan_linz_wien_graz_20261028.json")) {
            roh = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        JSONObject erg = new JSONObject(roh);
        assertTrue("Ausgangslage: roh über 400 KB", roh.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 400 * 1024);
        assertFalse("Fixture ohne Mitgliedsdaten", erg.optJSONObject("meine_karten") != null
                && erg.getJSONObject("meine_karten").optInt("anzahl", 0) > 0);
        JSONObject p = HandyRoute.fuerPostfach(erg, new double[]{48.3059, 14.2862},
                new JSONObject().put("lat", 47.0707).put("lon", 15.4382).put("name", "Graz Hauptplatz"), 80);
        String body = new JSONObject().put("auto", "a-1").put("plan", p).toString();
        int groesse = body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertTrue("Body " + groesse + " B unter 400 KB", groesse < 400 * 1024);
        assertTrue("deutlich darunter (≈ 100 KB laut Planer-Probe)", groesse < 150 * 1024);
        assertEquals(HandyRoute.MAX_PUNKTE, p.getJSONArray("geometrie").length());
        assertEquals(erg.getJSONObject("route").getDouble("km"), p.getDouble("km"), 1e-9);
        assertEquals(erg.getJSONObject("route").getDouble("minuten"), p.getDouble("minuten"), 1e-9);
        assertEquals("BYD Dolphin Surf Boost 43,2 kWh", p.getString("fahrzeug"));
        assertEquals(erg.getJSONArray("stopps").length(), p.getJSONArray("stopps").length());
        JSONObject s0 = p.getJSONArray("stopps").getJSONObject(0);
        assertTrue(s0.getString("name"), s0.getString("name").endsWith("chlarn, HOFER Manker Str."));   // Umlaut kommt vom Planer als U+FFFD
        for (String f : new String[]{"lat", "lon", "km", "kw", "cpo", "soc_an", "soc_ab", "dauer_min", "kwh"}) assertTrue(f, s0.has(f));
        JSONObject z0 = p.getJSONArray("zwischenziele").getJSONObject(0);
        assertEquals("Wien Stephansplatz", z0.getString("name"));
        assertTrue(z0.has("km"));
        for (String f : new String[]{"titel", "start", "ziel", "km", "minuten", "soc_start", "soc_ankunft", "fahrzeug", "stopps", "geometrie"}) {
            assertTrue("Pflichtfeld " + f, p.has(f));
        }
        assertFalse("keine Rohfelder", p.has("route") || p.has("fahrtplan") || p.has("alternativen"));
    }

    @Test
    public void nieDirektInsNavi() throws Exception {
        String q = at.greenchili.surfmate.server.WebHandyGuardTest.quelle("java/at/greenchili/surfmate/planer/HandyRoute.java");
        assertFalse(q.contains("Telenav") || q.contains("navigate_to") || q.contains("startNavigation"));
        assertTrue(q.contains("PlanerRoute.get().neuLaden()"));
    }
}
