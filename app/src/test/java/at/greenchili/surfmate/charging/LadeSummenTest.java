package at.greenchili.surfmate.charging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** 0.1.43.21 Ladekosten für Home Assistant: Summen, Ø-Preis nur über bepreiste Ladungen, letzte Ladung. */
public class LadeSummenTest {

    private static JSONObject ladung(long start, double kwh, Object kosten, String quelle) throws Exception {
        return new JSONObject().put("startTime", start).put("endTime", start + 3_600_000L).put("energyAdded", kwh)
                .put("cost", kosten).put("preisQuelle", quelle == null ? JSONObject.NULL : quelle)
                .put("electricityRate", kosten instanceof Number ? 0.3 : 0).put("currency", "EUR").put("isDc", false)
                .put("placeLabel", "Graz Zentrum").put("station_name", "Säule Graz");
    }

    @Test
    public void summenUndPreis() throws Exception {
        JSONArray s = new JSONArray()
                .put(ladung(1000, 20, 6.0, "tarif"))
                .put(ladung(2000, 10, 3.0, "nutzer"))
                .put(ladung(3000, 0, 0.0, "tarif"));   // keine Energie → zählt nicht
        JSONObject o = LadeSummen.zeitraum(s, 0, 5000);
        assertEquals(2, o.getInt("ladungen"));
        assertEquals(30.0, o.getDouble("kwh"), 1e-9);
        assertEquals(9.0, o.getDouble("kosten"), 1e-9);
        assertEquals(0.3, o.getDouble("preis_kwh"), 1e-9);
        assertTrue(o.getBoolean("vollstaendig"));
        assertEquals("EUR", o.getString("waehrung"));
        assertFalse("keine Orte", o.toString().contains("Graz"));
    }

    @Test
    public void ohnePreisNieAlsNull() throws Exception {
        JSONArray s = new JSONArray()
                .put(ladung(1000, 20, 6.0, "tarif"))
                .put(ladung(2000, 30, JSONObject.NULL, "fehlt"));
        JSONObject o = LadeSummen.zeitraum(s, 0, 5000);
        assertEquals(2, o.getInt("ladungen"));
        assertEquals(1, o.getInt("ladungen_ohne_preis"));
        assertFalse(o.getBoolean("vollstaendig"));
        assertEquals(6.0, o.getDouble("kosten"), 1e-9);
        assertEquals("Ø nur über bepreiste kWh", 0.3, o.getDouble("preis_kwh"), 1e-9);
    }

    @Test
    public void nurOhnePreis() throws Exception {
        JSONObject o = LadeSummen.zeitraum(new JSONArray().put(ladung(1000, 20, JSONObject.NULL, "fehlt")), 0, 1);
        assertTrue(o.isNull("kosten"));
        assertTrue(o.isNull("preis_kwh"));
        JSONObject leer = LadeSummen.zeitraum(new JSONArray(), 0, 1);
        assertEquals(0, leer.getInt("ladungen"));
        assertTrue(leer.isNull("kosten"));
    }

    @Test
    public void betragLautSaeuleZaehlt() throws Exception {
        JSONObject l = ladung(1000, 20, JSONObject.NULL, "fehlt").put("saeuleBetrag", 11.0).put("kwhAbgerechnet", 22.0);
        JSONObject o = LadeSummen.zeitraum(new JSONArray().put(l), 0, 5000);
        assertEquals(11.0, o.getDouble("kosten"), 1e-9);
        assertEquals(22.0, o.getDouble("kwh_abgerechnet"), 1e-9);
        assertEquals(0.5, o.getDouble("preis_kwh"), 1e-9);
        assertTrue(o.getBoolean("vollstaendig"));
    }

    @Test
    public void letzteLadung() throws Exception {
        JSONArray s = new JSONArray()
                .put(ladung(5000, 12, 3.6, "tarif"))
                .put(ladung(9000, 8, JSONObject.NULL, "fehlt"))
                .put(ladung(7000, 10, 3.0, "tarif"))
                .put(ladung(9500, 5, 1.0, "tarif").put("inProgress", true));   // läuft noch → nicht
        JSONObject l = LadeSummen.letzte(s);
        assertEquals(9000, l.getLong("start"));
        assertEquals(8.0, l.getDouble("kwh"), 1e-9);
        assertTrue(l.isNull("kosten"));
        assertTrue(l.isNull("preis_kwh"));
        assertFalse(l.getBoolean("dc"));
        assertFalse(l.toString().contains("Graz"));
        assertNull(LadeSummen.letzte(new JSONArray()));
    }
}
