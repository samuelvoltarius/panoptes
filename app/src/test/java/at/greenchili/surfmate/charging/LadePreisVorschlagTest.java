package at.greenchili.surfmate.charging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * Preis-Vorschlag „~“: getrennt nach Stromart, je Säule, Reihenfolge eigen → Karte → Planer (Ergänzung Projektinhaber
 * 03.11.2026: Planer AC 0,19 App / 0,45 ad hoc, bezahlt DC 0,29). Synthetische Werte, kein Ort.
 */
public class LadePreisVorschlagTest {

    static final String LIDL = "Lidl Test GmbH";

    static JSONObject sitzung(long id, long start, String betreiber, Boolean dc) throws Exception {
        return new JSONObject().put("id", id).put("startTime", start).put("betreiber", betreiber == null ? JSONObject.NULL : betreiber)
                .put("isDc", dc == null ? JSONObject.NULL : dc).put("saeuleKwh", JSONObject.NULL)
                .put("saeuleBetrag", JSONObject.NULL).put("preisQuelle", "").put("electricityRate", JSONObject.NULL);
    }

    static JSONObject station(String art, Object[]... tarife) throws Exception {
        JSONArray t = new JSONArray();
        for (Object[] x : tarife) {
            t.put(new JSONObject().put("name", x[0]).put("preisKwh", x[1]).put("adhoc", x[2]).put("meine", x[3])
                    .put("stand", x[4]));
        }
        return new JSONObject().put("id", "AT*TST*1").put("art", art).put("tarife", t);
    }

    @Test
    public void eigenerPreisNurGleicherBetreiberUndStromart() throws Exception {
        JSONArray s = new JSONArray()
                .put(sitzung(1, 1000, LIDL, true).put("saeuleKwh", 28.18).put("saeuleBetrag", 8.17))   // DC 0,2899
                .put(sitzung(2, 2000, "Anderer Betreiber", true).put("saeuleKwh", 10).put("saeuleBetrag", 5))
                .put(sitzung(3, 3000, LIDL, false).put("preisQuelle", "nutzer").put("electricityRate", 0.19));
        // DC-Ladung bei Lidl: eigener DC-Preis, nicht der AC-Preis, nicht der fremde Betreiber
        JSONArray v = LadePreisVorschlag.vorschlaege(LIDL, 1, null, s, 99);
        assertEquals(1, v.length());
        assertEquals("eigen", v.getJSONObject(0).getString("quelle"));
        assertEquals(0.2899, v.getJSONObject(0).getDouble("preisKwh"), 1e-4);
        // AC-Ladung bei Lidl: eigener AC-Preis 0,19
        v = LadePreisVorschlag.vorschlaege(LIDL, 0, null, s, 99);
        assertEquals(0.19, v.getJSONObject(0).getDouble("preisKwh"), 1e-9);
        // Betreiber unbekannt oder Stromart unbekannt: kein eigener Vorschlag
        assertEquals(0, LadePreisVorschlag.vorschlaege("", 1, null, s, 99).length());
        assertEquals(0, LadePreisVorschlag.vorschlaege(LIDL, -1, null, s, 99).length());
        // die Ladung selbst zählt nicht
        assertEquals(0, LadePreisVorschlag.vorschlaege(LIDL, 1, null, s, 1).length());
    }

    @Test
    public void reihenfolgeEigenKartePlaner_mehrereTarifeAlsAuswahl() throws Exception {
        JSONArray s = new JSONArray().put(sitzung(1, 1000, LIDL, false).put("saeuleKwh", 20).put("saeuleBetrag", 5.0));
        JSONObject st = station("AC",
                new Object[] {"Lidl Plus App", 0.19, false, false, "2026-10-21"},
                new Object[] {"ad hoc", 0.45, true, false, "2026-11-03"},
                new Object[] {"Meine Karte", 0.39, false, true, "2026-10-30"});
        JSONArray v = LadePreisVorschlag.vorschlaege(LIDL, 0, st, s, 99);
        assertEquals(4, v.length());
        assertEquals("eigen", v.getJSONObject(0).getString("quelle"));
        assertEquals(0.25, v.getJSONObject(0).getDouble("preisKwh"), 1e-9);
        assertEquals("karte", v.getJSONObject(1).getString("quelle"));
        assertEquals("planer", v.getJSONObject(2).getString("quelle"));
        assertEquals(0.19, v.getJSONObject(2).getDouble("preisKwh"), 1e-9);
        assertEquals("2026-10-21", v.getJSONObject(2).getString("stand"));
        assertEquals(0.45, v.getJSONObject(3).getDouble("preisKwh"), 1e-9);
        assertTrue(v.getJSONObject(3).getBoolean("adhoc"));
    }

    @Test
    public void planerOhnePassendeStromart_keinPlanerVorschlag() throws Exception {
        // Lidl-AC-Säule mit 0,19/0,45 – eine DC-Ladung bekommt diese Preise nie
        JSONObject ac = station("AC",
                new Object[] {"Lidl Plus App", 0.19, false, false, "2026-10-21"},
                new Object[] {"ad hoc", 0.45, true, false, "2026-11-03"});
        assertEquals(0, LadePreisVorschlag.vorschlaege(LIDL, 1, ac, new JSONArray(), 99).length());
        // DC-Säule ohne Preis → kein Vorschlag
        assertEquals(0, LadePreisVorschlag.vorschlaege(LIDL, 1, station("DC"), new JSONArray(), 99).length());
        // DC-Säule mit Preis → Vorschlag
        JSONArray v = LadePreisVorschlag.vorschlaege(LIDL, 1, station("DC",
                new Object[] {"ad hoc", 0.59, true, false, JSONObject.NULL}), new JSONArray(), 99);
        assertEquals(1, v.length());
        assertTrue(v.getJSONObject(0).isNull("stand"));
    }

    @Test
    public void unsinnigePreiseWerdenIgnoriert() throws Exception {
        JSONArray s = new JSONArray().put(sitzung(1, 1000, LIDL, true).put("saeuleKwh", 0).put("saeuleBetrag", 8.0));
        JSONObject st = station("DC", new Object[] {"ad hoc", 0.0, true, false, "2026-11-03"},
                new Object[] {"kaputt", 99.0, false, false, "2026-11-03"});
        assertFalse(LadePreisVorschlag.vorschlaege(LIDL, 1, st, s, 99).length() > 0);
    }
}
