package at.greenchili.surfmate.planer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 0.1.38.10 (Koordination 05.11., handy-048): Ziel und Zwischenziele als {@code {ort_id}} eines Favoriten. Das Auto
 * löst die ID selbst auf; die Koordinaten gehen nur zum Planer und nie zurück ans Handy.
 */
public class HandyOrtIdTest {

    final List<String> bodies = new ArrayList<>();
    JSONObject planAntwort;
    Function<String, JSONObject> vorher;

    final HandyRoute.Umgebung env = new HandyRoute.Umgebung() {
        public double[] position() { return new double[]{48.3069, 14.2858}; }
        public double soc() { return 80; }
        public String autoId() { return "a-1"; }
        public String[] token() { return new String[]{"X-Geraete-Token", "t"}; }
        public Object[] http(String m, String op, String[] t, String body, int leseMs) {
            bodies.add(op + " " + body);
            try {
                if (op.equals("plan")) return new Object[]{200, new JSONObject(planAntwort.toString())};
                return new Object[]{200, new JSONObject().put("ok", true)};
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        public String planBody(String body) { return body; }
        public void routeNeuLaden() { }
    };

    @Before
    public void auf() throws Exception {
        vorher = HandyRoute.favorit;
        // „Zuhause“ ist Favorit, „Arbeit“ nicht (die Liste des Handys enthält nur Favoriten)
        HandyRoute.favorit = id -> {
            try {
                return "o_zuhause".equals(id) ? new JSONObject().put("lat", 48.2082).put("lon", 16.3738).put("name", "Zuhause") : null;
            } catch (Exception e) {
                return null;
            }
        };
        planAntwort = new JSONObject().put("ok", true).put("vertrag", "plan-ergebnis/1").put("plan_id", "p_abc123")
                .put("start", new JSONObject().put("lat", 48.3069).put("lon", 14.2858).put("name", "Linz"))
                .put("ziel", new JSONObject().put("lat", 48.20821).put("lon", 16.37381).put("name", "Zuhause"))
                .put("zwischenziele", new JSONArray().put(new JSONObject().put("lat", 48.2).put("lon", 14.0).put("name", "Wels")))
                .put("etappen", new JSONArray().put(new JSONObject().put("art", "laden").put("lat", 48.0).put("lon", 13.5).put("name", "Säule"))
                        .put(new JSONObject().put("art", "ziel").put("lat", 48.2082).put("lng", 16.3738).put("name", "Zuhause")))
                .put("geometrie", new JSONArray().put(new JSONArray().put(48.3).put(14.28)).put(new JSONArray().put(48.2).put(16.37)))
                .put("km", 130);
    }

    @After
    public void ab() {
        HandyRoute.favorit = vorher;
    }

    @Test
    public void ortIdWirdImAutoAufgeloestUndKommtNichtZurueck() throws Exception {
        HandyPlanVorschau v = new HandyPlanVorschau(env, Runnable::run);
        JSONObject r = v.starten("h1", new JSONObject().put("ziel", new JSONObject().put("ort_id", "o_zuhause")
                .put("lat", 1.0).put("lon", 2.0)).put("geometrie", "voll"), 1_800_000_000_000L);
        assertTrue(r.toString(), r.getBoolean("ok"));
        // zum Planer gehen die Koordinaten des Autos, nicht die des Handys
        String plan = bodies.get(0);
        assertTrue(plan, plan.contains("48.2082") && plan.contains("16.3738"));
        assertFalse(plan, plan.contains("\"ziel\":[1,2]") || plan.contains("[1.0,2.0]"));
        JSONObject e = v.stand("h1").getJSONObject("ergebnis");
        String t = e.toString();
        assertFalse("keine Geometrie", e.has("geometrie"));
        assertFalse(t, t.contains("48.208") || t.contains("16.3738"));
        assertEquals("Zuhause", e.getJSONObject("ziel").getString("name"));
        // andere Punkte bleiben (Start, Wels, Säule)
        assertTrue(t, t.contains("48.3069") && t.contains("48.2") && t.contains("Säule"));
        assertEquals(48.0, e.getJSONArray("etappen").getJSONObject(0).getDouble("lat"), 1e-9);
    }

    @Test
    public void zwischenzielAlsOrtId() throws Exception {
        HandyPlanVorschau v = new HandyPlanVorschau(env, Runnable::run);
        JSONObject r = v.starten("h1", new JSONObject().put("ziel", new JSONObject().put("lat", 52.52).put("lon", 13.405).put("name", "Berlin"))
                .put("zwischenziele", new JSONArray().put(new JSONObject().put("ort_id", "o_zuhause"))), 1_800_000_000_000L);
        assertTrue(r.getBoolean("ok"));
        JSONObject via = new JSONObject(bodies.get(0).substring("plan ".length())).getJSONArray("via").getJSONObject(0);
        assertEquals(48.2082, via.getDouble("lat"), 1e-9);
        assertEquals(16.3738, via.getDouble("lon"), 1e-9);
        assertEquals("Zuhause", via.getString("name"));
    }

    @Test
    public void unbekannteOderFremdeIdGehtNicht() throws Exception {
        HandyPlanVorschau v = new HandyPlanVorschau(env, Runnable::run);
        HandyRoute route = new HandyRoute(env, Runnable::run, v);
        for (String id : new String[]{"o_arbeit", "o_unbekannt", "", "x/../o_zuhause", "O_ZUHAUSE"}) {
            JSONObject ziel = new JSONObject().put("ort_id", id).put("lat", 47.0).put("lon", 13.0);
            assertEquals(id, "ort_unbekannt", v.starten("h1", new JSONObject().put("ziel", ziel), 1L).getString("grund"));
            assertEquals(id, "ort_unbekannt", route.starten("h1", new JSONObject().put("ziel", ziel), 1L).getString("grund"));
            JSONObject zz = new JSONObject().put("ziel", new JSONObject().put("lat", 52.52).put("lon", 13.405))
                    .put("zwischenziele", new JSONArray().put(new JSONObject().put("ort_id", id)));
            assertEquals(id, "ort_unbekannt", v.starten("h1", zz, 1L).getString("grund"));
        }
        assertTrue("nichts ging zum Planer", bodies.isEmpty());
    }

    @Test
    public void routeInsAutoMitOrtId() throws Exception {
        HandyRoute route = new HandyRoute(env, Runnable::run, new HandyPlanVorschau(env, Runnable::run));
        planAntwort.put("route", new JSONObject().put("km", 130));
        JSONObject r = route.starten("h1", new JSONObject().put("ziel", new JSONObject().put("ort_id", "o_zuhause")), 1L);
        assertTrue(r.toString(), r.getBoolean("ok"));
        JSONArray z = new JSONObject(bodies.get(0).substring("plan ".length())).getJSONArray("ziel");
        assertEquals(48.2082, z.getDouble(0), 1e-9);
        assertEquals(16.3738, z.getDouble(1), 1e-9);
    }

    @Test
    public void favoritErkennen() throws Exception {
        assertTrue(HandyRoute.istFavorit(new JSONObject().put("eigenschaften", new JSONObject().put("favorit", true))));
        assertFalse(HandyRoute.istFavorit(new JSONObject().put("eigenschaften", new JSONObject().put("favorit", false))));
        assertFalse(HandyRoute.istFavorit(new JSONObject()));
        assertFalse(HandyRoute.istFavorit(null));
    }
}
