package at.greenchili.surfmate.charging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class WallboxEnergieProtokollTest {

    private static JSONObject status(boolean eingerichtet, boolean erreichbar, JSONObject lp) throws Exception {
        JSONObject o = new JSONObject().put("eingerichtet", eingerichtet).put("erreichbar", erreichbar);
        o.put("ladepunkte", lp == null ? new JSONArray() : new JSONArray().put(lp));
        return o;
    }

    @Test
    public void nichtEingerichtetKeineZeile() throws Exception {
        assertNull(WallboxEnergieProtokoll.zeile(status(false, false, null), 1L, 70));
        assertNull(WallboxEnergieProtokoll.zeile(null, 1L, 70));
    }

    @Test
    public void werteWerdenProtokolliertNichtVerrechnet() throws Exception {
        JSONObject lp = new JSONObject().put("energieSitzungKwh", 32.4567).put("zaehlerRoh", 123456);
        String z = WallboxEnergieProtokoll.zeile(status(true, true, lp), 1762250000000L, 70.0);
        assertTrue(z, z.contains("energie_sitzung_kwh=32.457"));
        assertTrue(z, z.contains("zaehler_roh=123456"));
        assertTrue(z, z.contains("dSoc=70.0"));
        assertTrue(z, z.contains("NICHT im SOH-Anker"));
    }

    @Test
    public void nichtErreichbarUndOhneWert() throws Exception {
        assertTrue(WallboxEnergieProtokoll.zeile(status(true, false, null), 1L, 5).contains("nicht erreichbar"));
        assertTrue(WallboxEnergieProtokoll.zeile(status(true, true, new JSONObject().put("id", "x")), 1L, 5)
                .contains("kein Sitzungswert"));
        // Unsinnswerte werden verworfen
        JSONObject neg = new JSONObject().put("energieSitzungKwh", -1.0);
        assertTrue(WallboxEnergieProtokoll.zeile(status(true, true, neg), 1L, 5).contains("kein Sitzungswert"));
        assertEquals(true, WallboxEnergieProtokoll.zeile(status(true, true, new JSONObject()
                .put("energieSitzungKwh", 1.5)), 1L, 5).contains("zaehler_roh=n/a"));
    }
}
