package at.greenchili.surfmate.trips;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import java.util.List;

/** 0.1.38.8 Handy-App „Fahrt als Luftlinie“: GET /api/trips/{id}/gps?max=N für Handy-Kopplungen. */
public class FahrtGpsLuftlinieTest {

    /** 2-h-Fahrt mit 5 Hz = 36 000 Punkte. */
    private static List<double[]> zweiStunden() {
        List<double[]> l = new ArrayList<>();
        for (int i = 0; i < 36_000; i++) l.add(new double[]{47.712345678 + i * 1e-6, 13.012345678 + i * 2e-6});
        return l;
    }

    @Test
    public void handyDarfDieSpurLesenAndereNicht() {
        assertFalse(ExternGuard.blocks("GET", "/api/trips/42/gps", "handy"));
        assertFalse(ExternGuard.blocks("GET", "/api/trips/42/gps?max=400", "handy"));
        assertFalse("wie /api/trips: kein eigenes Recht", ExternGuard.noetigesRecht("/api/trips/42/gps") != null);
        assertTrue(ExternGuard.blocks("POST", "/api/trips/42/gps", "handy"));
        assertTrue(ExternGuard.blocks("DELETE", "/api/trips/42", "handy"));
        for (String p : new String[]{"/api/trips/abc/gps", "/api/trips/42/gps/x", "/api/trips/42/telemetry",
                "/api/trips/42", "/api/trips/42/similar", "/api/trips/../gps", "/api/trips//gps", "/api/trips/-1/gps"}) {
            assertTrue(p, ExternGuard.blocks("GET", p, "handy"));
        }
        for (String art : new String[]{"cockpit", "homeassistant", "dienst", "web"}) {
            assertTrue(art, ExternGuard.blocks("GET", "/api/trips/42/gps", art));
        }
        assertFalse("Liste bleibt wie bisher", ExternGuard.blocks("GET", "/api/trips", "handy"));
    }

    @Test
    public void maxBegrenztUndOhneMaxWieBisher() throws Exception {
        assertNull(TripApiHandler.maxPunkte(null));
        assertNull(TripApiHandler.maxPunkte("abc"));
        assertNull(TripApiHandler.maxPunkte("-5"));
        assertEquals(Integer.valueOf(2), TripApiHandler.maxPunkte("0"));
        assertEquals(Integer.valueOf(2), TripApiHandler.maxPunkte("1"));
        assertEquals(Integer.valueOf(400), TripApiHandler.maxPunkte("400"));
        assertEquals(Integer.valueOf(2000), TripApiHandler.maxPunkte("999999999"));

        List<double[]> l = zweiStunden();
        JSONObject alt = TripApiHandler.gpsAntwort(l, null);
        assertEquals(36_000, alt.getJSONArray("gps").length());
        assertEquals("volle Genauigkeit ohne max", 47.712345678, alt.getJSONArray("gps").getJSONArray(0).getDouble(0), 1e-12);
        assertEquals(36_000, alt.getInt("punkte_gesamt"));
    }

    @Test
    public void ausduennenBehaeltStartUndZielUndRundet() throws Exception {
        List<double[]> l = zweiStunden();
        JSONObject r = TripApiHandler.gpsAntwort(l, 400);
        JSONArray g = r.getJSONArray("gps");
        assertTrue(r.getBoolean("success"));
        assertEquals(400, g.length());
        assertEquals(36_000, r.getInt("punkte_gesamt"));
        assertEquals(47.71235, g.getJSONArray(0).getDouble(0), 1e-12);
        assertEquals(13.01235, g.getJSONArray(0).getDouble(1), 1e-12);
        double[] ziel = l.get(l.size() - 1);
        assertEquals(TripApiHandler.runde5(ziel[0]), g.getJSONArray(399).getDouble(0), 1e-12);
        assertEquals(TripApiHandler.runde5(ziel[1]), g.getJSONArray(399).getDouble(1), 1e-12);
        for (int i = 0; i < g.length(); i++) {
            String s = g.getJSONArray(i).toString();
            assertFalse(s, s.matches(".*\\.\\d{6,}.*"));
        }
        // Größe im Bericht: 2-h-Fahrt mit max=400 bzw. ohne max
        int klein = r.toString().getBytes(StandardCharsets.UTF_8).length;
        int gross = TripApiHandler.gpsAntwort(l, null).toString().getBytes(StandardCharsets.UTF_8).length;
        System.out.println("GPS-Antwort 2-h-Fahrt (36000 Punkte): max=400 " + klein + " B, ohne max " + gross + " B");
        assertTrue(klein < 12_000);

        assertEquals("wenige Punkte bleiben", 3, TripApiHandler.ausduennen(l.subList(0, 3), 400).size());
        List<double[]> zwei = TripApiHandler.ausduennen(l, 2);
        assertEquals(2, zwei.size());
        assertTrue(zwei.get(0) == l.get(0) && zwei.get(1) == l.get(l.size() - 1));
        assertEquals(0, TripApiHandler.gpsAntwort(new ArrayList<>(), 400).getJSONArray("gps").length());
    }

    @Test
    public void fehlendeTelemetrieBleibt410() throws Exception {
        String h = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/trips/TripApiHandler.java"));
        int i = h.indexOf("private JSONObject handleGetGpsTrace(long tripId, String maxParam)");
        assertTrue(i > 0);
        String teil = h.substring(i, h.indexOf("static Integer maxPunkte", i));
        assertEquals(2, teil.split("errorResponse\\(\"Telemetry data unavailable\", 410\\)", -1).length - 1);
        assertTrue(h.contains("return handleGetGpsTrace(tripId, params.get(\"max\"));"));
    }
}
