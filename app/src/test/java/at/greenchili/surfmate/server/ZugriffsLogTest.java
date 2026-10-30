package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.auth.Kopplungen;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

public class ZugriffsLogTest {

    private static final long MIN = 60_000L;

    @Before
    public void leer() {
        ZugriffsLog.leerenFuerTest();
    }

    @Test
    public void zaehltJeGeraetUndMinute() throws Exception {
        ZugriffsLog.zaehle("k-1", "/api/vehicle/state", 5 * MIN);
        ZugriffsLog.zaehle("k-1", "/api/vehicle/state", 5 * MIN + 1);
        ZugriffsLog.zaehle(null, "/api/vehicle/state", 6 * MIN);
        JSONArray a = ZugriffsLog.alsJson().getJSONArray("minuten");
        assertEquals(2, a.length());
        assertEquals(2, a.getJSONObject(0).getJSONObject("geraete").getJSONObject("k-1").getInt("/api/vehicle/state"));
        assertEquals(1, a.getJSONObject(1).getJSONObject("geraete").getJSONObject("lokal").getInt("/api/vehicle/state"));
    }

    @Test
    public void pfadOhneAbfrageUndKennung() {
        assertEquals("/api/recordings", ZugriffsLog.pfadKlasse("/api/recordings?type=all&geheim=1"));
        assertEquals("/video/*", ZugriffsLog.pfadKlasse("/video/cam_20261030_101500.mp4"));
        assertEquals("/api/waechter/standbild/*",
                ZugriffsLog.pfadKlasse("/api/waechter/standbild/0123456789abcdef0123456789abcdef"));
        assertEquals("/", ZugriffsLog.pfadKlasse("/"));
    }

    @Test
    public void bleibtBeschraenkt() throws Exception {
        for (int i = 0; i < ZugriffsLog.MINUTEN + 5; i++) ZugriffsLog.zaehle("k", "/a", i * MIN);
        assertEquals(ZugriffsLog.MINUTEN, ZugriffsLog.alsJson().getJSONArray("minuten").length());
        ZugriffsLog.leerenFuerTest();
        for (int i = 0; i < ZugriffsLog.MAX_PFADE + 5; i++) ZugriffsLog.zaehle("k", "/p" + (char) ('A' + i % 26) + i % 7 + "x" + i / 26, 0);
        JSONObject pfade = ZugriffsLog.alsJson().getJSONArray("minuten").getJSONObject(0)
                .getJSONObject("geraete").getJSONObject("k");
        assertTrue(pfade.length() <= ZugriffsLog.MAX_PFADE + 1);
    }

    @Test
    public void nichtFuerKopplungenUndHandyWeb() {
        assertTrue(ExternGuard.blocks("GET", "/api/system/zugriffe", Kopplungen.ART_HANDY));
        assertTrue(WebHandyGuard.blocks("GET", "/api/system/zugriffe"));
        assertFalse(ExternGuard.blocks("GET", "/api/system/capabilities", Kopplungen.ART_HANDY));
    }
}
