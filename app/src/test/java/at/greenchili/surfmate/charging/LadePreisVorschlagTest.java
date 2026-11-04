package at.greenchili.surfmate.charging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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
        return new JSONObject().put("id", id).put("land", "AT").put("startTime", start).put("betreiber", betreiber == null ? JSONObject.NULL : betreiber)
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
        JSONArray v = LadePreisVorschlag.vorschlaege(LIDL, "AT", 1, null, s, 99);
        assertEquals(1, v.length());
        assertEquals("eigen", v.getJSONObject(0).getString("quelle"));
        assertEquals(0.2899, v.getJSONObject(0).getDouble("preisKwh"), 1e-4);
        // AC-Ladung bei Lidl: eigener AC-Preis 0,19
        v = LadePreisVorschlag.vorschlaege(LIDL, "AT", 0, null, s, 99);
        assertEquals(0.19, v.getJSONObject(0).getDouble("preisKwh"), 1e-9);
        // Betreiber unbekannt oder Stromart unbekannt: kein eigener Vorschlag
        assertEquals(0, LadePreisVorschlag.vorschlaege("", "AT", 1, null, s, 99).length());
        assertEquals(0, LadePreisVorschlag.vorschlaege(LIDL, "AT", -1, null, s, 99).length());
        // die Ladung selbst zählt nicht
        assertEquals(0, LadePreisVorschlag.vorschlaege(LIDL, "AT", 1, null, s, 1).length());
    }

    @Test
    public void reihenfolgeEigenKartePlaner_mehrereTarifeAlsAuswahl() throws Exception {
        JSONArray s = new JSONArray().put(sitzung(1, 1000, LIDL, false).put("saeuleKwh", 20).put("saeuleBetrag", 5.0));
        JSONObject st = station("AC",
                new Object[] {"Lidl Plus App", 0.19, false, false, "2026-10-21"},
                new Object[] {"ad hoc", 0.45, true, false, "2026-11-03"},
                new Object[] {"Meine Karte", 0.39, false, true, "2026-10-30"});
        JSONArray v = LadePreisVorschlag.vorschlaege(LIDL, "AT", 0, st, s, 99);
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
        assertEquals(0, LadePreisVorschlag.vorschlaege(LIDL, "AT", 1, ac, new JSONArray(), 99).length());
        // DC-Säule ohne Preis → kein Vorschlag
        assertEquals(0, LadePreisVorschlag.vorschlaege(LIDL, "AT", 1, station("DC"), new JSONArray(), 99).length());
        // DC-Säule mit Preis → Vorschlag
        JSONArray v = LadePreisVorschlag.vorschlaege(LIDL, "AT", 1, station("DC",
                new Object[] {"ad hoc", 0.59, true, false, JSONObject.NULL}), new JSONArray(), 99);
        assertEquals(1, v.length());
        assertTrue(v.getJSONObject(0).isNull("stand"));
    }

    // ------------------------------------------------------------------ 0.1.38.6: eigener Preis je Land

    @Test
    public void lidlAtUndLidlDeSindGetrennt() throws Exception {
        JSONArray s = new JSONArray()
                .put(sitzung(1, 1000, LIDL, false).put("land", "AT").put("saeuleKwh", 20).put("saeuleBetrag", 3.8))   // AT 0,19
                .put(sitzung(2, 2000, LIDL, false).put("land", "DE").put("saeuleKwh", 20).put("saeuleBetrag", 5.0));  // DE 0,25
        assertEquals(0.19, LadePreisVorschlag.vorschlaege(LIDL, "AT", 0, null, s, 99).getJSONObject(0).getDouble("preisKwh"), 1e-9);
        assertEquals(0.25, LadePreisVorschlag.vorschlaege(LIDL, "DE", 0, null, s, 99).getJSONObject(0).getDouble("preisKwh"), 1e-9);
        assertEquals("kein Preis aus einem dritten Land", 0, LadePreisVorschlag.vorschlaege(LIDL, "CH", 0, null, s, 99).length());
        // nur AT vorhanden: eine DE-Ladung bekommt nie den AT-Preis, auch wenn er der jüngste ist
        JSONArray nurAt = new JSONArray().put(sitzung(3, 3000, LIDL, false).put("saeuleKwh", 20).put("saeuleBetrag", 3.8));
        assertEquals(0, LadePreisVorschlag.vorschlaege(LIDL, "DE", 0, null, nurAt, 99).length());
        assertEquals(1, LadePreisVorschlag.vorschlaege(LIDL, "at", 0, null, nurAt, 99).length());   // Schreibweise egal
    }

    @Test
    public void ohneLandKeinEigenerPreis() throws Exception {
        JSONArray s = new JSONArray().put(sitzung(1, 1000, LIDL, false).put("saeuleKwh", 20).put("saeuleBetrag", 3.8));
        assertEquals(0, LadePreisVorschlag.vorschlaege(LIDL, null, 0, null, s, 99).length());
        assertEquals(0, LadePreisVorschlag.vorschlaege(LIDL, "", 0, null, s, 99).length());
        assertEquals(0, LadePreisVorschlag.vorschlaege(LIDL, "Oesterreich", 0, null, s, 99).length());
        // Ladung ohne Land und ohne Position zählt nie als Quelle, bleibt aber unverändert in den Daten
        JSONObject ohne = sitzung(2, 2000, LIDL, false).put("saeuleKwh", 20).put("saeuleBetrag", 3.8);
        ohne.remove("land");
        assertEquals(0, LadePreisVorschlag.vorschlaege(LIDL, "AT", 0, null, new JSONArray().put(ohne), 99).length());
        assertFalse(ohne.has("land"));
    }

    @Test
    public void bestandLandAusDerGespeichertenPositionAbgeleitet() throws Exception {
        // Ladungen aus der DB haben kein land, aber lat/lng (Kernbereiche, synthetische Städte-Mittelpunkte)
        JSONObject wien = sitzung(1, 1000, LIDL, false).put("lat", 48.21).put("lng", 16.37).put("saeuleKwh", 20).put("saeuleBetrag", 3.8);
        JSONObject muenchen = sitzung(2, 2000, LIDL, false).put("lat", 48.14).put("lng", 11.58).put("saeuleKwh", 20).put("saeuleBetrag", 5.0);
        wien.remove("land");
        muenchen.remove("land");
        JSONArray s = new JSONArray().put(wien).put(muenchen);
        assertEquals(0.19, LadePreisVorschlag.vorschlaege(LIDL, "AT", 0, null, s, 99).getJSONObject(0).getDouble("preisKwh"), 1e-9);
        assertEquals(0.25, LadePreisVorschlag.vorschlaege(LIDL, "DE", 0, null, s, 99).getJSONObject(0).getDouble("preisKwh"), 1e-9);
    }

    @Test
    public void landAusPositionNurImKernbereich() throws Exception {
        assertEquals("AT", LadeLand.ausPosition(48.21, 16.37));
        assertEquals("AT", LadeLand.ausPosition(47.07, 15.44));
        assertEquals("AT", LadeLand.ausPosition(47.27, 11.40));
        assertEquals("DE", LadeLand.ausPosition(52.37, 9.73));
        assertEquals("DE", LadeLand.ausPosition(50.04, 8.27));
        assertEquals("CH", LadeLand.ausPosition(46.95, 7.45));
        // Grenznähe, Ausland und Ungültiges: unbekannt
        assertNull(LadeLand.ausPosition(48.57, 13.45));   // Passau
        assertNull(LadeLand.ausPosition(47.55, 7.59));    // Basel
        assertNull(LadeLand.ausPosition(45.0, 9.0));      // Italien
        assertNull(LadeLand.ausPosition(0, 0));
        assertNull(LadeLand.ausPosition(Double.NaN, 10));
        assertNull(LadeLand.von(null));
        assertNull(LadeLand.von(new JSONObject().put("lat", JSONObject.NULL).put("lng", 12.0)));
        assertEquals("DE", LadeLand.von(new JSONObject().put("land", "de").put("lat", 48.21).put("lng", 16.37)));   // ausdrücklich gewinnt
        assertEquals("AT", LadeLand.von(new JSONObject().put("lat", 48.21).put("lon", 16.37)));
    }

    @Test
    public void landAusDerEvseIdDerSaeuleAuchInGrenznaehe() throws Exception {
        assertEquals("AT", LadeLand.ausEvse("AT*LDL*E00000092"));
        assertEquals("DE", LadeLand.ausEvse("de*LDL*E1"));
        assertEquals("CH", LadeLand.ausEvse(" CH*ABC*E9*1"));
        assertNull(LadeLand.ausEvse("ATLDLE00000092"));
        assertNull(LadeLand.ausEvse("1831"));
        assertNull(LadeLand.ausEvse(null));
        // Vorrang: die zugeordnete Säule gewinnt vor land und vor der Position. Koordinaten: Passau (Grenznähe,
        // sonst unbekannt) bzw. Wien, bewusst gegen die Säule gesetzt
        assertEquals("AT", LadeLand.von(new JSONObject().put("stationId", "AT*LDL*E00000092").put("lat", 48.57).put("lng", 13.45)));
        assertEquals("DE", LadeLand.von(new JSONObject().put("stationId", "DE*LDL*E00000007").put("land", "AT").put("lat", 48.21).put("lng", 16.37)));
        assertEquals("AT", LadeLand.von(new JSONObject().put("id", "AT*LDL*E00000092")));                 // Säule der Planer-Antwort
        assertEquals("AT", LadeLand.von(new JSONObject().put("id", 1831).put("stationId", "AT*LDL*E00000092")));   // Ladung mit zugeordneter Säule
        assertNull("numerische Ladungs-ID ist keine EVSE", LadeLand.von(new JSONObject().put("id", 1831)));
        assertNull(LadeLand.von(new JSONObject().put("stationId", JSONObject.NULL)));
        // Lidl AT und Lidl DE nahe der Grenze bleiben getrennt, nur über die EVSE-ID erkennbar
        JSONObject at = sitzung(1, 1000, LIDL, false).put("stationId", "AT*LDL*E00000092").put("saeuleKwh", 20).put("saeuleBetrag", 3.8);
        JSONObject de = sitzung(2, 2000, LIDL, false).put("stationId", "DE*LDL*E00000007").put("saeuleKwh", 20).put("saeuleBetrag", 5.0);
        at.remove("land");
        de.remove("land");
        JSONArray s = new JSONArray().put(at).put(de);
        assertEquals(0.19, LadePreisVorschlag.vorschlaege(LIDL, LadeLand.ausEvse("AT*LDL*E00000092"), 0, null, s, 99).getJSONObject(0).getDouble("preisKwh"), 1e-9);
        assertEquals(0.25, LadePreisVorschlag.vorschlaege(LIDL, LadeLand.ausEvse("DE*LDL*E00000007"), 0, null, s, 99).getJSONObject(0).getDouble("preisKwh"), 1e-9);
    }

    @Test
    public void unsinnigePreiseWerdenIgnoriert() throws Exception {
        JSONArray s = new JSONArray().put(sitzung(1, 1000, LIDL, true).put("saeuleKwh", 0).put("saeuleBetrag", 8.0));
        JSONObject st = station("DC", new Object[] {"ad hoc", 0.0, true, false, "2026-11-03"},
                new Object[] {"kaputt", 99.0, false, false, "2026-11-03"});
        assertFalse(LadePreisVorschlag.vorschlaege(LIDL, "AT", 1, st, s, 99).length() > 0);
    }
}
