package at.greenchili.surfmate.charging;

import static org.junit.Assert.assertEquals;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** 0.1.11 (Kern 7): BMS-/Heuristik-Wert rastet ±5 % auf die nächste Katalogvariante ein, sonst nicht. */
public class KapazitaetEinrastenTest {

    private static JSONArray surf() throws Exception {
        return new JSONArray()
                .put(new JSONObject().put("id", "active").put("nominalKwh", 30.08))
                .put(new JSONObject().put("id", "comfort_boost").put("nominalKwh", 43.2));
    }

    @Test
    public void alfredsSurfBms441WirdComfortBoost() throws Exception {
        assertEquals(43.2, KapazitaetPlausibel.einrasten(44.1, surf(), KapazitaetPlausibel.EINRASTEN), 1e-9);
    }

    @Test
    public void activeBleibtActive() throws Exception {
        assertEquals(30.08, KapazitaetPlausibel.einrasten(31.0, surf(), KapazitaetPlausibel.EINRASTEN), 1e-9);
    }

    @Test
    public void ausserhalbDerToleranzKeineVariante() throws Exception {
        assertEquals(0, KapazitaetPlausibel.einrasten(37.0, surf(), KapazitaetPlausibel.EINRASTEN), 0);
        assertEquals(0, KapazitaetPlausibel.einrasten(46.0, surf(), KapazitaetPlausibel.EINRASTEN), 0);
    }

    @Test
    public void ohneVariantenOderWertNichts() throws Exception {
        assertEquals(0, KapazitaetPlausibel.einrasten(44.1, new JSONArray(), 0.05), 0);
        assertEquals(0, KapazitaetPlausibel.einrasten(0, surf(), 0.05), 0);
        assertEquals(0, KapazitaetPlausibel.einrasten(44.1, null, 0.05), 0);
    }
}
