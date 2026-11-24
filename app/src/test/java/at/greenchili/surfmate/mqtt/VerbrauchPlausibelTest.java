package at.greenchili.surfmate.mqtt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.ha.HaZustand;

import org.json.JSONObject;
import org.junit.Test;

/** 0.1.43.21: consumption_50km 309,6 kWh/100 km (Befund HA) → null statt falsch; plausible Werte bleiben. */
public class VerbrauchPlausibelTest {

    @Test
    public void unplausibelWirdUnbekannt() {
        assertTrue(Double.isNaN(VerbrauchPlausibel.kwhJe100km(309.6)));
        assertTrue(Double.isNaN(VerbrauchPlausibel.kwhJe100km(0)));
        assertTrue(Double.isNaN(VerbrauchPlausibel.kwhJe100km(1548)));
        assertTrue(Double.isNaN(VerbrauchPlausibel.kwhJe100km(-3)));
        assertTrue(Double.isNaN(VerbrauchPlausibel.kwhJe100km(Double.NaN)));
        assertTrue(Double.isNaN(VerbrauchPlausibel.kwhJe100km(Double.POSITIVE_INFINITY)));
    }

    @Test
    public void plausibelBleibtUnveraendert() {
        assertEquals(15.5, VerbrauchPlausibel.kwhJe100km(15.5), 1e-9);
        assertEquals(5.0, VerbrauchPlausibel.kwhJe100km(5.0), 1e-9);
        assertEquals(60.0, VerbrauchPlausibel.kwhJe100km(60.0), 1e-9);
        assertEquals("Surf nach Graz im Winter", 19.8, VerbrauchPlausibel.kwhJe100km(19.8), 1e-9);
    }

    @Test
    public void haTelemetrieMeldetNullStattFalsch() throws Exception {
        JSONObject t = HaZustand.telemetrie(new JSONObject().put("consumption_50km", 309.6));
        assertTrue(t.has("consumption_50km") && t.isNull("consumption_50km"));
        assertEquals(16.2, HaZustand.telemetrie(new JSONObject().put("consumption_50km", 16.2))
                .getDouble("consumption_50km"), 1e-9);
        assertTrue(HaZustand.telemetrie(new JSONObject().put("consumption_50km", JSONObject.NULL))
                .isNull("consumption_50km"));
    }

    @Test
    public void protokollNurDieZahl() {
        assertEquals("VERBRAUCH_50KM_UNPLAUSIBEL roh=309.6 grenzen=5.0-60.0", VerbrauchPlausibel.protokoll(309.6));
    }
}
