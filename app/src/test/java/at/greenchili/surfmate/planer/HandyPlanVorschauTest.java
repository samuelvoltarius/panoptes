package at.greenchili.surfmate.planer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * 0.1.38.6 Handy plant über Panoptes (PLANER_VERTRAG §4): {@code plan_vorschau} ruft den Planer mit Auto-Standort und
 * Geräte-Schlüssel, reicht {@code plan-ergebnis/1} samt {@code plan_id} unverändert weiter; {@code route {plan_id}}
 * übernimmt genau diesen Plan ({@code an-auto {plan_id, auto}}), 2 h, danach ehrlich „neu planen“.
 */
public class HandyPlanVorschauTest {

    final List<String> aufrufe = new ArrayList<>();
    final List<String> bodies = new ArrayList<>();
    double[] pos = {48.3069, 14.2858};
    String[] token = {"X-Geraete-Token", "t"};
    int planCode = 200, anAutoCode = 200;
    JSONObject planAntwort;
    int neuGeladen;

    final HandyRoute.Umgebung env = new HandyRoute.Umgebung() {
        public double[] position() { return pos; }
        public double soc() { return 80.2; }
        public String autoId() { return "a-1"; }
        public String[] token() { return token; }
        public Object[] http(String m, String op, String[] t, String body, int leseMs) {
            aufrufe.add(m + " " + op);
            bodies.add(body);
            try {
                if (op.equals("plan")) return new Object[]{planCode, planAntwort != null ? planAntwort : ergebnis("p_abc123")};
                if (op.equals("an-auto")) return new Object[]{anAutoCode, new JSONObject().put("ok", anAutoCode == 200)};
                return new Object[]{200, new JSONObject().put("ok", true)};
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        public String planBody(String body) { return body; }
        public void routeNeuLaden() { neuGeladen++; }
    };
    final HandyPlanVorschau v = new HandyPlanVorschau(env, Runnable::run);
    final HandyRoute route = new HandyRoute(env, Runnable::run, v);
    final long t0 = 1_800_000_000_000L;

    /** Form wie plan-ergebnis/1 (PLANER_VERTRAG §2), verkürzt. */
    static JSONObject ergebnis(String planId) throws Exception {
        JSONArray geo = new JSONArray();
        for (int i = 0; i < 50; i++) geo.put(new JSONArray().put(48.3 + i * 0.01).put(14.28));
        return new JSONObject().put("ok", true).put("vertrag", "plan-ergebnis/1")
                .put("plan_id", planId == null ? JSONObject.NULL : planId)
                .put("start", new JSONObject().put("lat", 48.3069).put("lon", 14.2858).put("name", "Linz"))
                .put("ziel", new JSONObject().put("lat", 52.52).put("lon", 13.405).put("name", "Berlin"))
                .put("km", 731.6).put("fahrbar", true).put("nicht_fahrbar", JSONObject.NULL)
                .put("zeit", new JSONObject().put("gesamt_min", 515).put("verkehr_min", JSONObject.NULL).put("inkl_verkehr", false))
                .put("akku", new JSONObject().put("soc_start", 80).put("soc_ankunft", 23).put("reserve", 10))
                .put("etappen", new JSONArray().put(new JSONObject().put("nr", 1).put("art", "laden").put("name", "Lidl Regensburg")
                        .put("kw", 150).put("kosten", 13.95).put("live", JSONObject.NULL)))
                .put("kosten", new JSONObject().put("summe", 41.2))
                .put("quellen", new JSONArray().put("© OpenStreetMap-Mitwirkende (ODbL)"))
                .put("geometrie", geo).put("geometrie_punkte", 8292)
                .put("unbekanntes_neues_feld", 7);
    }

    static JSONObject ziel() throws Exception {
        return new JSONObject().put("lat", 52.52).put("lon", 13.405).put("name", "Berlin");
    }

    JSONObject start(String kid, JSONObject req, long t) throws Exception {
        return v.starten(kid, req, t);
    }

    // ------------------------------------------------------------- Vorschau

    @Test
    public void ergebnisUnveraendertMitPlanIdUndOhneStandortVomHandy() throws Exception {
        JSONObject req = new JSONObject().put("ziel", ziel())
                .put("start", new JSONArray().put(1.0).put(2.0))        // ein vom Handy mitgeschickter Standort zählt nie
                .put("lat", 1.0).put("lon", 2.0);
        assertEquals("plant", start("k1", req, t0).getString("status"));
        JSONObject b = new JSONObject(bodies.get(0));
        assertEquals("POST plan", aufrufe.get(0));
        assertEquals(48.3069, b.getJSONArray("start").getDouble(0), 0);   // Auto-Position
        assertEquals(14.2858, b.getJSONArray("start").getDouble(1), 0);
        assertEquals("kompakt", b.getString("ausgabe"));
        assertEquals("kurz", b.getString("geometrie"));
        assertEquals("auto", b.getString("vorlieben_quelle"));
        assertEquals("a-1", b.getString("auto"));
        assertEquals(80, b.getInt("soc_start"));
        JSONObject s = v.stand("k1");
        assertEquals("bereit", s.getString("status"));
        assertEquals("p_abc123", s.getString("plan_id"));
        JSONObject p = s.getJSONObject("ergebnis");
        JSONObject orig = ergebnis("p_abc123");
        orig.remove("geometrie");                                         // Handy wollte keine Linie
        assertEquals(orig.toString().length(), p.toString().length());
        assertEquals(7, p.getInt("unbekanntes_neues_feld"));              // unbekannte Felder bleiben
        assertEquals("plan-ergebnis/1", p.getString("vertrag"));
        assertEquals(731.6, p.getDouble("km"), 0);
        assertTrue(p.isNull("nicht_fahrbar"));                            // null bleibt null, nie geraten
        assertFalse(p.has("geometrie"));
    }

    @Test
    public void geometrieNurAufWunsch() throws Exception {
        start("k1", new JSONObject().put("ziel", ziel()).put("geometrie", true), t0);
        assertEquals(50, v.stand("k1").getJSONObject("ergebnis").getJSONArray("geometrie").length());
        start("k1", new JSONObject().put("ziel", ziel()).put("geometrie", "kurz"), t0 + 61_000L);
        assertTrue(v.stand("k1").getJSONObject("ergebnis").has("geometrie"));
        start("k1", new JSONObject().put("ziel", ziel()).put("geometrie", "keine"), t0 + 122_000L);   // so ruft die App
        assertFalse(v.stand("k1").getJSONObject("ergebnis").has("geometrie"));
        assertEquals("kurz", new JSONObject(bodies.get(2)).getString("geometrie"));                    // zum Planer immer kurz
    }

    @Test
    public void keinStandortKeinPlanerKeinPlan() throws Exception {
        pos = null;
        assertEquals("keine_position", start("k1", new JSONObject().put("ziel", ziel()), t0).getString("grund"));
        pos = new double[]{48.3, 14.2};
        token = null;
        assertEquals("kein_planer", start("k1", new JSONObject().put("ziel", ziel()), t0).getString("grund"));
        token = new String[]{"X-Geraete-Token", "t"};
        assertEquals("werte", start("k1", new JSONObject().put("ziel", new JSONObject().put("lat", 0).put("lon", 0)), t0).getString("grund"));
        assertEquals("werte", start("k1", new JSONObject(), t0).getString("grund"));
        JSONArray zz = new JSONArray();
        for (int i = 0; i < 6; i++) zz.put(ziel());
        assertEquals("zu_viele_zwischenziele", start("k1", new JSONObject().put("ziel", ziel()).put("zwischenziele", zz), t0).getString("grund"));
        assertTrue(aufrufe.isEmpty());                                    // nichts ging zum Planer
    }

    @Test
    public void ratenlimitZehnJeMinute() throws Exception {
        int ok = 0;
        String letzter = "";
        for (int i = 0; i < 12; i++) {
            JSONObject r = start("k1", new JSONObject().put("ziel", ziel()), t0 + i * 100L);
            if (r.optBoolean("ok")) ok++; else letzter = r.getString("grund");
        }
        assertEquals(10, ok);
        assertEquals("zu_oft", letzter);
        assertTrue(start("k1", new JSONObject().put("ziel", ziel()), t0 + 61_000L).getBoolean("ok"));   // Minute vorbei
    }

    @Test
    public void ehrlicheFehler() throws Exception {
        int[] codes = {401, 403, 429, 503};
        String[] gruende = {"kein_planer", "planer_recht_fehlt", "zu_oft", "planer_fehler"};
        for (int i = 0; i < codes.length; i++) {
            planCode = codes[i];
            start("k1", new JSONObject().put("ziel", ziel()), t0 + i * 61_000L);
            JSONObject s = v.stand("k1");
            assertEquals("fehler", s.getString("status"));
            assertEquals(gruende[i], s.getString("grund"));
            assertEquals(gruende[i], s.getString("code"));
            assertFalse(s.has("ergebnis"));
        }
        planCode = 200;
        planAntwort = new JSONObject().put("ok", true).put("km", 5);      // alter Planer ohne Vertrag
        start("k1", new JSONObject().put("ziel", ziel()), t0 + 400_000L);
        assertEquals("planer_alt", v.stand("k1").getString("grund"));
        planAntwort = ergebnis(null);                                    // anonym: plan_id null, Ergebnis trotzdem da
        start("k1", new JSONObject().put("ziel", ziel()), t0 + 500_000L);
        assertTrue(v.stand("k1").isNull("plan_id"));
        assertEquals("keiner", v.stand("nie").getString("status"));
    }

    // ------------------------------------------------------------- Ins Auto mit plan_id

    @Test
    public void insAutoUebernimmtGenauDiesenPlan() throws Exception {
        start("k1", new JSONObject().put("ziel", ziel()), t0);
        aufrufe.clear(); bodies.clear();
        JSONObject r = route.starten("k1", new JSONObject().put("plan_id", "p_abc123"), t0 + 60_000L);
        assertEquals("plant", r.getString("status"));
        assertFalse("kein erneutes Planen", aufrufe.contains("POST plan"));
        int i = aufrufe.indexOf("POST an-auto");
        JSONObject b = new JSONObject(bodies.get(i));
        assertEquals("p_abc123", b.getString("plan_id"));
        assertEquals("a-1", b.getString("auto"));
        assertFalse(b.has("plan"));
        JSONObject s = route.stand("k1");
        assertEquals("bereit", s.getString("status"));
        assertEquals("Berlin", s.getString("titel"));
        assertEquals(1, neuGeladen);
    }

    @Test
    public void abgelaufenUnbekanntFremd() throws Exception {
        start("k1", new JSONObject().put("ziel", ziel()), t0);
        aufrufe.clear();
        // älter als 2 h: sofort ehrlich, nichts zum Planer
        JSONObject alt = route.starten("k1", new JSONObject().put("plan_id", "p_abc123"), t0 + HandyPlanVorschau.GUELTIG_MS);
        assertFalse(alt.getBoolean("ok"));
        assertEquals("plan_abgelaufen", alt.getString("grund"));
        assertTrue(aufrufe.isEmpty());
        // nie gesehen / nach Neustart: dasselbe, ehrlich „neu planen“
        assertEquals("plan_abgelaufen", route.starten("k1", new JSONObject().put("plan_id", "p_nie1"), t0).getString("grund"));
        // fremde Kopplung bekommt nie den Plan einer anderen
        start("k1", new JSONObject().put("ziel", ziel()), t0 + 61_000L);
        assertEquals("plan_unbekannt", route.starten("k2", new JSONObject().put("plan_id", "p_abc123"), t0 + 62_000L).getString("grund"));
        assertEquals("werte", route.starten("k1", new JSONObject().put("plan_id", "x"), t0).getString("grund"));
        assertEquals("werte", route.starten("k1", new JSONObject().put("plan_id", "p_a b"), t0).getString("grund"));
    }

    @Test
    public void planerAntwortetAbgelaufenOderFremd() throws Exception {
        start("k1", new JSONObject().put("ziel", ziel()), t0);
        anAutoCode = 410;
        route.starten("k1", new JSONObject().put("plan_id", "p_abc123"), t0 + 1_000L);
        assertEquals("plan_abgelaufen", route.stand("k1").getString("grund"));
        assertEquals("plan_abgelaufen", route.starten("k1", new JSONObject().put("plan_id", "p_abc123"), t0 + 2_000L).getString("grund"));   // vergessen
        start("k1", new JSONObject().put("ziel", ziel()), t0 + 61_000L);
        anAutoCode = 404;
        route.starten("k1", new JSONObject().put("plan_id", "p_abc123"), t0 + 62_000L);
        assertEquals("plan_unbekannt", route.stand("k1").getString("grund"));
        start("k1", new JSONObject().put("ziel", ziel()), t0 + 122_000L);
        anAutoCode = 403;
        route.starten("k1", new JSONObject().put("plan_id", "p_abc123"), t0 + 123_000L);
        assertEquals("planer_recht_fehlt", route.stand("k1").getString("grund"));
        assertEquals(0, neuGeladen);
    }

    @Test
    public void routeOhnePlanIdBleibtWieBisher() throws Exception {
        assertEquals("plant", route.starten("k1", new JSONObject().put("ziel", ziel()), t0).getString("status"));
        assertTrue(aufrufe.contains("POST plan"));
        assertNull(new JSONObject(bodies.get(0)).optString("ausgabe", null));   // altes Verhalten: volle Antwort
    }

    @Test
    public void quelltextKeinNeuerWeg() throws Exception {
        String src = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "src/main/java/at/greenchili/surfmate/planer/HandyPlanVorschau.java")), java.nio.charset.StandardCharsets.UTF_8);
        // nur der vorhandene Netzweg des Planers (env.http), keine eigene Verbindung, kein Standort aus dem Handy-Body
        assertFalse(src.contains("HttpURLConnection"));
        assertFalse(src.contains("req.opt(\"start\")"));
        assertFalse(src.contains("req.opt(\"lat\")"));
        assertTrue(src.contains("env.position()"));
    }
}
