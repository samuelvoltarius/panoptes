package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Collections;

/**
 * 0.1.36: Der Auslöser kommt als neues, optionales Feld {@code ausloeser} in die Handy-Clip-Liste. Alte Felder bleiben
 * gleich; Clips ohne Auslöser sehen aus wie bisher; nie Ort, Pfad oder Dateiname.
 */
public class HandyClipsAusloeserTest {

    static final String E = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";

    static JSONObject zeile(JSONObject ausloeser) throws Exception {
        JSONObject r = new JSONObject().put("id", E).put("filename", "ereignis_20261031_160100.mp4")
                .put("type", "ereignis").put("timestamp", 1_793_000_000_000L).put("size", 41_234_567L)
                .put("available", true).put("path", "/storage/emulated/0/x/ereignis_20261031_160100.mp4")
                .put("startLat", 48.2082).put("startLng", 16.3738);
        if (ausloeser != null) r.put("ausloeser", ausloeser);
        return HandyClipsApiHandler.zeile(r, Collections.emptySet(), false);
    }

    @Test
    public void ohneAusloeserWieBisher() throws Exception {
        JSONObject c = zeile(null);
        assertEquals(8, c.length());   // id typ art zeit groesse behalten frageOffen weg
        assertFalse(c.has("ausloeser"));
    }

    @Test
    public void mitAusloeserNurErlaubteFelder() throws Exception {
        JSONObject a = new JSONObject().put("v", 1).put("zeit", 1_793_000_000_000L).put("zeitText", "2026-10-31 16:01:00")
                .put("art", "bremsen").put("quelle", "tempo").put("regel", "verzoegerung")
                .put("werte", new JSONObject().put("verzoegerungMs2", 6.2).put("lat", 48.2082))
                .put("schwellen", new JSONObject().put("verzoegerungMs2", 5.0))
                .put("gang", "D").put("kmh", 61.0).put("clip", "ereignis_20261031_160100.mp4")
                .put("geo", new JSONObject().put("lng", 16.3738));
        JSONObject c = zeile(a);
        // bestehende Felder unverändert
        assertEquals(E, c.getString("id"));
        assertEquals("ereignis", c.getString("typ"));
        assertEquals("ereignis", c.getString("art"));
        assertEquals(1_793_000_000_000L, c.getLong("zeit"));
        assertEquals(41_234_567L, c.getLong("groesse"));
        assertEquals("bruecke_medien", c.getString("weg"));
        JSONObject x = c.getJSONObject("ausloeser");
        assertEquals("bremsen", x.getString("art"));
        assertEquals("D", x.getString("gang"));
        assertEquals(6.2, x.getJSONObject("werte").getDouble("verzoegerungMs2"), 0.0);
        assertEquals(5.0, x.getJSONObject("schwellen").getDouble("verzoegerungMs2"), 0.0);
        String s = c.toString();
        for (String verboten : new String[] {"lat", "lng", "48.2", "16.37", "geo", "clip", "ereignis_2026", "/storage",
                "filename", "path"}) {
            assertFalse(verboten, s.contains(verboten));
        }
        assertTrue(s.contains("\"ausloeser\""));
    }
}
