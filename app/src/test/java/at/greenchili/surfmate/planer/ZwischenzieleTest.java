package at.greenchili.surfmate.planer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** §3-Erweiterung (Planer §14): plan.zwischenziele[] → Z1…Z5 in der Panoptes-Routenanzeige, nur Anzeige. */
public class ZwischenzieleTest {

    private static JSONObject plan(String zwischen) throws Exception {
        return new JSONObject("{\"titel\":\"Innsbruck → Wien\",\"erstellt\":\"2026-10-22T08:00:00Z\","
                + "\"ziel\":{\"name\":\"Wien\",\"lat\":48.2,\"lon\":16.37},\"km\":300,"
                + "\"stopps\":[{\"name\":\"Ladepark A\",\"lat\":48.1,\"lon\":14.3,\"km\":120},{\"name\":\"Ladepark B\",\"lat\":48.2,\"lon\":15.6,\"km\":230}]"
                + (zwischen == null ? "" : ",\"zwischenziele\":" + zwischen) + "}");
    }

    @Test
    public void ohneZwischenziele_wieBisher() throws Exception {
        JSONObject a = PlanerRoute.antwort(plan(null), null, null);
        assertFalse(a.has("zwischenziele"));
        assertFalse(a.has("wegpunkte"));
        assertEquals(2, a.getJSONArray("stopps").length());
        assertEquals(PlanerRoute.antwort(plan(null), null, null).toString(), PlanerRoute.antwort(plan("[]"), null, null).toString());
    }

    @Test
    public void nachKmZwischenDieLadestopps() throws Exception {
        JSONObject a = PlanerRoute.antwort(plan("[{\"name\":\"Linz\",\"lat\":48.3,\"lon\":14.29,\"km\":130,\"aufenthalt_min\":45},"
                + "{\"name\":\"Enns\",\"lat\":48.35,\"lon\":14.585,\"km\":25}]"), null, null);
        JSONArray z = a.getJSONArray("zwischenziele");
        assertEquals("Z1", z.getJSONObject(0).getString("nr"));       // Fahrreihenfolge des Planers bleibt die Nummer
        assertEquals("Linz", z.getJSONObject(0).getString("name"));
        assertEquals(45, z.getJSONObject(0).getInt("aufenthaltMin"));
        assertEquals(14.29, z.getJSONObject(0).getDouble("lng"), 1e-9);
        JSONArray w = a.getJSONArray("wegpunkte");
        String folge = "";
        for (int i = 0; i < w.length(); i++) folge += w.getJSONObject(i).getString("nr") + " ";
        assertEquals("Z2 L1 Z1 L2 ", folge);
        assertEquals(2, a.getJSONArray("stopps").length());          // stopps unverändert (alte Leser)
    }

    @Test
    public void ohneKm_ehrlichErstZwischenzieleDannLadestopps() throws Exception {
        JSONObject a = PlanerRoute.antwort(plan("[{\"name\":\"Linz\",\"lat\":48.3,\"lon\":14.29}]"), null, null);
        JSONArray w = a.getJSONArray("wegpunkte");
        assertEquals("Z1", w.getJSONObject(0).getString("nr"));
        assertEquals("L1", w.getJSONObject(1).getString("nr"));
        assertEquals("L2", w.getJSONObject(2).getString("nr"));
    }

    @Test
    public void hoechstensFuenf_ungueltigeFallenWeg_nameEinzeilig() throws Exception {
        StringBuilder sb = new StringBuilder("[{\"name\":\"kaputt\",\"lat\":999,\"lon\":1},{\"name\":\"null\",\"lat\":0,\"lon\":0},");
        for (int i = 0; i < 7; i++) sb.append("{\"name\":\"A\\n").append(i).append("\",\"lat\":47.").append(i + 1).append(",\"lon\":13.1,\"km\":").append(i * 10).append("},");
        sb.setLength(sb.length() - 1);
        sb.append("]");
        JSONArray z = Zwischenziele.lies(plan(sb.toString()));
        assertEquals(Zwischenziele.MAX, z.length());
        assertEquals("A 0", z.getJSONObject(0).getString("name"));
        assertEquals("Z5", z.getJSONObject(4).getString("nr"));
        assertTrue(Zwischenziele.lies(new JSONObject()).length() == 0);
        assertTrue(Zwischenziele.lies(null).length() == 0);
    }
}
