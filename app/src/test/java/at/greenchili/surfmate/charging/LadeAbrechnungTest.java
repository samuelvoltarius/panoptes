package at.greenchili.surfmate.charging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * Abgerechnete Menge statt nur Akku-kWh (03.11.2026). Anlass: DC 25,5 kWh im Akku, 7,39 €, die Säule rechnete ca.
 * 28 kWh ab. Synthetische Zahlen, keine Orte.
 */
public class LadeAbrechnungTest {

    private static JSONObject cfg(Object ac, Object dc) throws Exception {
        JSONObject l = new JSONObject();
        if (ac != null) l.put("verlustAc", ac);
        if (dc != null) l.put("verlustDc", dc);
        return new JSONObject().put("laden", l);
    }

    @Test
    public void zuschlag_vorschlag_einstellbar_begrenzt() throws Exception {
        assertEquals(LadeAbrechnung.VORSCHLAG_AC, LadeAbrechnung.zuschlagProzent(null, false));
        assertEquals(LadeAbrechnung.VORSCHLAG_DC, LadeAbrechnung.zuschlagProzent(new JSONObject(), true));
        assertEquals(LadeAbrechnung.VORSCHLAG_AC, LadeAbrechnung.zuschlagProzent(null, null));   // unbekannt = AC
        assertEquals(15, LadeAbrechnung.zuschlagProzent(cfg(15, 9), false));
        assertEquals(9, LadeAbrechnung.zuschlagProzent(cfg(15, 9), true));
        assertEquals(0, LadeAbrechnung.zuschlagProzent(cfg(-5, 0), false));
        assertEquals(LadeAbrechnung.MAX_PROZENT, LadeAbrechnung.zuschlagProzent(cfg(99, null), false));
        assertEquals(LadeAbrechnung.VORSCHLAG_DC, LadeAbrechnung.zuschlagProzent(cfg(null, JSONObject.NULL), true));
    }

    @Test
    public void ohneEintrag_zuschlag_geschaetzt_gleicherPreisJeKwh() {
        // 25,5 kWh Akku, 7,39 € gespeichert (0,29 €/kWh), DC 10 %
        LadeAbrechnung.Ergebnis e = LadeAbrechnung.berechne(25.5, 7.39, true, Double.NaN, Double.NaN,
                cfgUnchecked(null, 10));
        assertEquals(28.05, e.kwh, 0.001);
        assertTrue(e.kwhGeschaetzt);
        assertEquals(LadeAbrechnung.QUELLE_ZUSCHLAG, e.quelle);
        assertEquals(7.39 * 1.10, e.kosten, 0.001);
        assertTrue(e.kostenGeschaetzt);
        assertEquals(10, e.verlustProzent, 0.001);
    }

    @Test
    public void echteDcLadung_saeuleUndSchaetzungPassen() {
        // Echte Werte (Projektinhaber, Ladung 31.10.): Akku 59 % × 43,2 = 25,488 kWh (SoC-Schätzung), 0,29 €/kWh ad hoc,
        // Säule 28,18 kWh für 8,17 € → Verlust 10,6 % gegenüber der Akku-Schätzung.
        LadeAbrechnung.Ergebnis eintrag = LadeAbrechnung.berechne(25.488, 25.488 * 0.29, true, 28.18, 8.17, null);
        assertEquals(28.18, eintrag.kwh, 0.0);
        assertEquals(8.17, eintrag.kosten, 0.0);
        assertFalse(eintrag.kostenGeschaetzt);
        assertEquals(10.6, eintrag.verlustProzent, 0.05);
        // ohne Eintrag: DC-Vorschlag 8 % liegt 0,66 kWh bzw. 0,19 € unter der Säule – Größenordnung SoC-Rundung (1 % ≈ 0,43 kWh)
        LadeAbrechnung.Ergebnis schaetzung = LadeAbrechnung.berechne(25.488, 25.488 * 0.29, true, Double.NaN, Double.NaN, null);
        assertEquals(27.53, schaetzung.kwh, 0.01);
        assertEquals(7.98, schaetzung.kosten, 0.01);
        assertTrue(Math.abs(schaetzung.kosten - 8.17) < 0.25);
        // Fahrtpreis je Akku-kWh mit Eintrag: 8,17 / 25,488
        assertEquals(8.17 / 25.488, LadeAbrechnung.preisJeAkkuKwh(0.29, 25.488, true, 28.18, 8.17, null), 1e-9);
    }

    @Test
    public void eintrag_kwh_gilt_kostenMitGespeichertemPreis() {
        LadeAbrechnung.Ergebnis e = LadeAbrechnung.berechne(25.488, 25.488 * 0.29, true, 28.18, Double.NaN, null);
        assertEquals(28.18, e.kwh, 0.0001);
        assertFalse(e.kwhGeschaetzt);
        assertEquals(LadeAbrechnung.QUELLE_SAEULE, e.quelle);
        assertEquals(0.29 * 28.18, e.kosten, 0.0001);
        assertFalse(e.kostenGeschaetzt);
        assertEquals(10.6, e.verlustProzent, 0.001);   // tatsächlicher Unterschied
    }

    @Test
    public void eintrag_betrag_gilt_auchOhnePreis() {
        LadeAbrechnung.Ergebnis e = LadeAbrechnung.berechne(25.488, Double.NaN, true, 28.18, 8.17, null);
        assertEquals(8.17, e.kosten, 0.0001);
        assertFalse(e.kostenGeschaetzt);
        // nur Betrag, keine kWh: Menge geschätzt, Betrag genau
        LadeAbrechnung.Ergebnis b = LadeAbrechnung.berechne(20, -1, false, Double.NaN, 0.0, null);
        assertEquals(0.0, b.kosten, 0.0);
        assertFalse(b.kostenGeschaetzt);
        assertTrue(b.kwhGeschaetzt);
    }

    @Test
    public void preisFehlt_bleibt_fehlt_ungueltigeEintraegeIgnoriert() {
        LadeAbrechnung.Ergebnis e = LadeAbrechnung.berechne(20, -1, false, Double.NaN, Double.NaN, null);
        assertTrue(Double.isNaN(e.kosten));
        LadeAbrechnung.Ergebnis g = LadeAbrechnung.berechne(20, 0, false, Double.NaN, Double.NaN, null);
        assertEquals(0, g.kosten, 0.0);   // gratis bleibt gratis
        LadeAbrechnung.Ergebnis x = LadeAbrechnung.berechne(20, 6, false, -3, 1e9, null);
        assertTrue(x.kwhGeschaetzt);       // -3 kWh und 1e9 € sind keine Einträge
        assertEquals(6 * 1.12, x.kosten, 0.0001);
        LadeAbrechnung.Ergebnis leer = LadeAbrechnung.berechne(Double.NaN, Double.NaN, null, Double.NaN, Double.NaN, null);
        assertTrue(Double.isNaN(leer.kwh));
        assertEquals("", leer.quelle);
    }

    @Test
    public void anreichern_aendertKeineVorhandenenFelder() throws Exception {
        JSONObject o = new JSONObject().put("energyAdded", 25.5).put("cost", 7.39).put("isDc", true)
                .put("electricityRate", 0.29);
        String vorher = o.optString("energyAdded") + o.optString("cost") + o.optString("electricityRate");
        LadeAbrechnung.anreichern(o, Double.NaN, Double.NaN, null);
        assertEquals(vorher, o.optString("energyAdded") + o.optString("cost") + o.optString("electricityRate"));
        assertEquals(25.5, o.getDouble("kwhAkku"), 0.0);
        assertEquals(27.54, o.getDouble("kwhAbgerechnet"), 0.001);   // + 8 % DC-Vorschlag
        assertTrue(o.getBoolean("kwhAbgerechnetGeschaetzt"));
        assertEquals(7.98, o.getDouble("kostenAbgerechnet"), 0.001);
        assertTrue(o.isNull("saeuleKwh"));

        JSONObject f = new JSONObject().put("energyAdded", 10).put("cost", JSONObject.NULL).put("isDc", false);
        LadeAbrechnung.anreichern(f, Double.NaN, Double.NaN, null);
        assertTrue(f.isNull("kostenAbgerechnet"));
        assertEquals("zuschlag", f.getString("abrechnungQuelle"));
    }

    @Test
    public void ladeKosten_nutztAbgerechnet_altfelderBleibenAkku() throws Exception {
        JSONObject a = new JSONObject().put("id", 1).put("startTime", 1000).put("endTime", 2000).put("inProgress", false)
                .put("energyAdded", 25.488).put("cost", 7.39).put("isDc", true).put("preisQuelle", "tarif")
                .put("tariffId", "t1").put("currency", "€");
        LadeAbrechnung.anreichern(a, 28.18, 8.17, null);
        JSONObject b = new JSONObject().put("id", 2).put("startTime", 3000).put("endTime", 4000).put("inProgress", false)
                .put("energyAdded", 10.0).put("cost", JSONObject.NULL).put("isDc", false).put("preisQuelle", "fehlt");
        LadeAbrechnung.anreichern(b, Double.NaN, 3.00, null);   // nur Betrag: Preis jetzt bekannt
        JSONArray l = new JSONArray().put(a).put(b);
        JSONObject k = LadeKosten.kosten(l, 100, "monat", 0, 9);
        assertEquals(35.5, k.getDouble("kwh_geladen"), 0.01);          // Akku, wie bisher
        assertEquals(7.39, k.getDouble("kosten_summe"), 0.001);         // Akku-Basis, wie bisher
        assertEquals(1, k.getInt("ladungen_ohne_preis"));
        assertEquals(28.18 + 11.2, k.getDouble("kwh_abgerechnet"), 0.05);
        assertEquals(11.17, k.getDouble("kosten_summe_abgerechnet"), 0.001);
        assertEquals(11.17, k.getDouble("kosten_je_100km_abgerechnet"), 0.001);
        assertEquals(0, k.getInt("ladungen_ohne_preis_abgerechnet"));
        assertTrue(k.getBoolean("vollstaendig_abgerechnet"));
        assertEquals(1, k.getInt("ladungen_geschaetzt"));
        JSONObject e = LadeKosten.ladungen(l, 0, 10, java.util.TimeZone.getTimeZone("UTC"))
                .getJSONArray("ladungen").getJSONObject(1);
        assertEquals(28.18, e.getDouble("kwh_abgerechnet"), 0.0);
        assertEquals(8.17, e.getDouble("kosten_abgerechnet"), 0.0);
        assertFalse(e.getBoolean("kwh_abgerechnet_geschaetzt"));
    }

    private static JSONObject cfgUnchecked(Object ac, Object dc) {
        try {
            return cfg(ac, dc);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
