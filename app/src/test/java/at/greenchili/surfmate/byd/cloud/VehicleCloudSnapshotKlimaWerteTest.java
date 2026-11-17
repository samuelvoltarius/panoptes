package at.greenchili.surfmate.byd.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.byd.KlimaStufe;


import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Klima-Werte der Cloud (pyBYD Issue #64, Dolphin Surf) richtig lesen, nur lesend; Platzhalter aus
 * Issue #20 gelten als unbekannt. Der Fernstart bleibt unverändert.
 */
public class VehicleCloudSnapshotKlimaWerteTest {

    @Before
    public void surf() {
        KlimaStufe.fuerTest(Boolean.TRUE);
    }

    @After
    public void zurueck() {
        KlimaStufe.fuerTest(null);
    }

    private static VehicleCloudSnapshot snap(long zeitMs, Object... kv) throws Exception {
        JSONObject vi = new JSONObject().put("time", zeitMs).put("onlineState", 1);
        for (int i = 0; i < kv.length; i += 2) vi.put((String) kv[i], kv[i + 1]);
        return VehicleCloudSnapshot.fromVehicleInfo(vi).build();
    }

    @Test
    public void klimaWerteRichtigZugeordnet() throws Exception {
        VehicleCloudSnapshot s = snap(System.currentTimeMillis(),
                "windPosition", 4, "windMode", 2, "cycleChoice", 1, "acSwitch", 2, "mainSettingTemp", 9);
        assertEquals(4, s.luefterStufe());
        assertEquals(2, s.luftrichtung());
        assertEquals(Boolean.FALSE, s.umluft());        // 1 = Frischluft (nicht 0/1)
        assertEquals(Boolean.TRUE, s.kompressorAn());   // 2 = an
        assertEquals(25, s.sollSdk());                  // Surf: Stufe 9 = 25
        JSONObject k = s.klimaJson();
        assertEquals(4, k.getInt("luefterStufe"));
        assertEquals("armaturenbrett_fussraum", k.getString("luftrichtung"));
        assertFalse(k.getBoolean("umluft"));
        assertTrue(k.getBoolean("kompressor"));
        assertEquals(25, k.getInt("sollSdk"));

        VehicleCloudSnapshot u = snap(System.currentTimeMillis(), "cycleChoice", 2, "acSwitch", 1,
                "windMode", 5, "mainSettingTemp", 17);
        assertEquals(Boolean.TRUE, u.umluft());
        assertEquals(Boolean.FALSE, u.kompressorAn());
        assertEquals("scheibe", u.klimaJson().getString("luftrichtung"));
        assertEquals(33, u.sollSdk());
        assertEquals(17, snap(0L, "mainSettingTemp", 1).sollSdk());
    }

    @Test
    public void platzhalterSindUnbekannt() throws Exception {
        VehicleCloudSnapshot s = snap(System.currentTimeMillis(),
                "windPosition", 0, "windMode", -1, "cycleChoice", 0, "acSwitch", -1, "mainSettingTemp", 0,
                "tempOutCar", -129);
        assertEquals(-1, s.luefterStufe());
        assertEquals(-1, s.luftrichtung());
        assertNull(s.umluft());
        assertNull(s.kompressorAn());
        assertEquals(-1, s.sollSdk());
        assertEquals(0, s.klimaJson().length());
        assertFalse(s.hasOutsideTemp());
        assertEquals(-1, snap(0L, "windPosition", 8).luefterStufe());
        assertEquals(-1, snap(0L, "mainSettingTemp", 18).sollSdk());
        // Ganz ohne Felder: alles unbekannt
        assertEquals(0, snap(0L).klimaJson().length());
    }

    @Test
    public void statusNowWirdGelesen() throws Exception {
        JSONObject vi = new JSONObject().put("onlineState", 1)
                .put("statusNow", new JSONObject().put("windPosition", 3).put("cycleChoice", 2));
        VehicleCloudSnapshot s = VehicleCloudSnapshot.fromVehicleInfo(vi).build();
        assertEquals(3, s.luefterStufe());
        assertEquals(Boolean.TRUE, s.umluft());
    }

    @Test
    public void fernstartBleibtUnveraendert_keinWindLevel() throws Exception {
        // Senden der Lüfterstufe ist am Surf nicht gesichert (Koordination 17.11.): OPENAIR wie bisher
        JSONObject p = BydCloudClient.climateStartParams(25, 20);
        assertFalse(p.has("windLevel"));
        assertEquals(2, p.getInt("cycleMode"));
        assertEquals(9, p.getInt("mainSettingTemp"));
    }
}
