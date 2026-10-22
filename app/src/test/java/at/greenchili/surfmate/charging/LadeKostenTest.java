package at.greenchili.surfmate.charging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.bericht.Monatsbericht;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.TimeZone;

/** „Was kosten 100 km?“, Ladungsliste, Monatsbericht mit Nutzer-/Standardpreisen. Synthetische Zahlen. */
public class LadeKostenTest {

    static final TimeZone WIEN = TimeZone.getTimeZone("Europe/Vienna");

    private static JSONObject s(long id, long start, double kwh, Object cost, String quelle, String tarif) throws Exception {
        return new JSONObject().put("id", id).put("startTime", start).put("endTime", start + 3_600_000L).put("inProgress", false)
                .put("energyAdded", kwh).put("cost", cost).put("electricityRate", cost instanceof Number && kwh > 0 ? ((Number) cost).doubleValue() / kwh : JSONObject.NULL)
                .put("currency", "€").put("preisQuelle", quelle).put("tariffId", tarif == null ? JSONObject.NULL : tarif)
                .put("tariffLabel", tarif == null ? JSONObject.NULL : "Lidl").put("isDc", true).put("placeLabel", "Lidl Leoben")
                .put("lat", 47.38).put("lng", 15.09);
    }

    @Test
    public void kosten_je100km_gratisNull_ohnePreisNichtNull() throws Exception {
        JSONArray a = new JSONArray()
                .put(s(1, 1000, 30, 8.70, "tarif", "t1"))       // Lidl 0,29
                .put(s(2, 2000, 10, 0.0, "nutzer", null))       // zuhause gratis
                .put(s(3, 3000, 20, JSONObject.NULL, "fehlt", null))
                .put(new JSONObject().put("inProgress", true).put("energyAdded", 5));   // läuft: zählt nicht
        JSONObject k = LadeKosten.kosten(a, 400, "monat", 0, 9);
        assertEquals(60, k.getDouble("kwh_geladen"), 0.01);
        assertEquals(8.70, k.getDouble("kosten_summe"), 0.001);
        assertEquals(2.175, k.getDouble("kosten_je_100km"), 0.006);   // auf Cent gerundet
        assertEquals(15.0, k.getDouble("kwh_je_100km"), 0.01);
        assertEquals(3, k.getInt("ladungen"));
        assertEquals(1, k.getInt("ladungen_ohne_preis"));
        assertEquals(1, k.getInt("gratis_ladungen"));
        assertFalse(k.getBoolean("vollstaendig"));
        assertEquals(0.333, k.getDouble("anteil_ohne_preis_kwh"), 0.001);
        assertEquals("€", k.getString("waehrung"));
        assertTrue(LadeKosten.kosten(a, 0.5, "monat", 0, 9).isNull("kosten_je_100km"));   // ohne km keine Quote
        assertTrue(LadeKosten.kosten(new JSONArray().put(s(1, 1, 30, 8.7, "tarif", "t1")), 300, "30t", 0, 9).getBoolean("vollstaendig"));
    }

    @Test
    public void ladungen_seiten_monate_ohneKoordinaten() throws Exception {
        long okt = 1792454400000L;   // 2026-10-20
        long sep = okt - 30L * 86_400_000L;
        JSONArray a = new JSONArray().put(s(1, sep, 30, 8.7, "tarif", "t1")).put(s(2, okt, 10, 0.0, "nutzer", null))
                .put(s(3, okt + 1000, 20, JSONObject.NULL, "fehlt", null));
        JSONObject l = LadeKosten.ladungen(a, 0, 2, WIEN);
        assertEquals(3, l.getInt("gesamt"));
        assertTrue(l.getBoolean("mehr"));
        assertEquals(3, l.getJSONArray("ladungen").getJSONObject(0).getLong("id"));   // neueste zuerst
        assertTrue(l.getJSONArray("ladungen").getJSONObject(0).isNull("kosten"));
        assertEquals("Lidl Leoben", l.getJSONArray("ladungen").getJSONObject(0).getString("ort"));
        assertFalse("keine Koordinaten", l.toString().contains("47.38"));
        JSONArray m = l.getJSONArray("monate");
        assertEquals(2, m.length());
        JSONObject oktM = m.getJSONObject(0);
        assertEquals("2026-10", oktM.getString("monat"));
        assertEquals(1, oktM.getInt("ohne_preis"));
        assertFalse(oktM.getBoolean("vollstaendig"));
        assertTrue(m.getJSONObject(1).getBoolean("vollstaendig"));
        assertFalse(LadeKosten.ladungen(a, 1, 2, WIEN).getBoolean("mehr"));
    }

    @Test
    public void zeitraeume() {
        long jetzt = 1792670400000L;   // 2026-10-22T12:00Z
        long[] m = LadeKosten.zeitraum("monat", jetzt, WIEN);
        assertEquals(jetzt, m[1]);
        assertTrue(m[0] < jetzt && jetzt - m[0] < 23L * 86_400_000L);
        assertEquals(30L * 86_400_000L, LadeKosten.zeitraum("30t", jetzt, WIEN)[1] - LadeKosten.zeitraum("30t", jetzt, WIEN)[0]);
        assertEquals(0, LadeKosten.zeitraum("gesamt", jetzt, WIEN)[0]);
        assertTrue(LadeKosten.zeitraum("jahr", jetzt, WIEN)[0] < m[0]);
        assertFalse(LadeKosten.gueltig("woche"));
    }

    @Test
    public void monatsbericht_nutzerUndStandardSindBekannt() throws Exception {
        JSONArray a = new JSONArray().put(s(1, 1000, 30, 8.7, "nutzer", null)).put(s(2, 2000, 10, 0.0, "standard", null));
        JSONObject l = Monatsbericht.laden(a, null);
        assertEquals(8.7, l.getJSONObject("unterwegs").getDouble("kosten"), 0.001);
        JSONArray b = new JSONArray().put(s(1, 1000, 30, 8.7, "nutzer", null)).put(s(3, 3000, 20, JSONObject.NULL, "fehlt", null));
        assertTrue(Monatsbericht.laden(b, null).getJSONObject("unterwegs").isNull("kosten"));
        JSONArray c = new JSONArray().put(s(4, 4000, 20, 6.0, "ersatz", null));
        assertTrue("Ersatzwert ist kein bekannter Preis", Monatsbericht.laden(c, null).getJSONObject("unterwegs").isNull("kosten"));
    }
}
