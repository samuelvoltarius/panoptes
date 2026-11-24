package at.greenchili.surfmate.charging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Map;

/** 0.1.43.20 Ersparnis gegenüber Benzin/Diesel: Rechnung, Richtwerte, Randfälle. */
public class ErsparnisTest {

    private static final double D = 1e-9;

    static JSONObject benzin(double verbrauch, double preis) throws Exception {
        return Ersparnis.vergleich(new JSONObject().put("kraftstoff", "benzin").put("verbrauch", verbrauch)
                .put("preis", preis).put("preisStand", 1_764_000_000_000L), "€");
    }

    @Test
    public void standardwerteBenzinUndDiesel() throws Exception {
        JSONObject b = Ersparnis.vergleich(null, "€");
        assertEquals("benzin", b.getString("kraftstoff"));
        assertEquals(6.5, b.getDouble("verbrauch"), D);
        assertEquals(1.65, b.getDouble("preis"), D);
        assertTrue(b.getBoolean("preis_richtwert"));
        assertTrue(b.isNull("preis_stand"));
        assertEquals(Ersparnis.RICHTWERT_STAND, b.getString("richtwert_stand"));

        JSONObject d = Ersparnis.vergleich(new JSONObject().put("kraftstoff", "diesel"), "EUR");
        assertEquals(5.5, d.getDouble("verbrauch"), D);
        assertEquals(1.60, d.getDouble("preis"), D);

        // ohne Währung zeigt Panoptes €, daher Richtwert
        assertFalse(Ersparnis.vergleich(null, "").isNull("preis"));
        // unbekannter Kraftstoff → Benzin
        assertEquals("benzin", Ersparnis.vergleich(new JSONObject().put("kraftstoff", "lpg"), "€").getString("kraftstoff"));
    }

    @Test
    public void andereWaehrungOhneRichtwert() throws Exception {
        JSONObject v = Ersparnis.vergleich(null, "CHF");
        assertTrue(v.isNull("preis"));
        assertFalse(v.getBoolean("preis_richtwert"));
        JSONObject r = Ersparnis.rechne(100, 5, true, v);
        assertTrue(r.isNull("ersparnis"));
        assertEquals("preis_fehlt", r.getString("grund"));
        assertEquals(6.5, r.getDouble("liter"), D);   // Liter gehen auch ohne Preis
        // mit eigenem Preis in CHF geht es
        JSONObject chf = Ersparnis.vergleich(new JSONObject().put("preis", 1.85).put("preisStand", 5L), "CHF");
        assertEquals(1.85, chf.getDouble("preis"), D);
        assertEquals(5L, chf.getLong("preis_stand"));
        assertEquals("CHF", chf.getString("waehrung"));
    }

    @Test
    public void eigeneWerteAusserhalbDesBereichsZaehlenNicht() throws Exception {
        JSONObject v = Ersparnis.vergleich(new JSONObject().put("verbrauch", 0.5).put("preis", 0.0), "€");
        assertEquals(6.5, v.getDouble("verbrauch"), D);
        assertFalse(v.getBoolean("verbrauch_eigen"));
        assertTrue(v.getBoolean("preis_richtwert"));
        JSONObject n = Ersparnis.vergleich(new JSONObject().put("verbrauch", JSONObject.NULL).put("preis", JSONObject.NULL), "€");
        assertEquals(6.5, n.getDouble("verbrauch"), D);
        assertTrue(n.getBoolean("preis_richtwert"));
    }

    @Test
    public void rechnungNormalfall() throws Exception {
        // 1000 km, 6,5 l/100 km, 1,70 €/l → 65 l, 110,50 €; Strom 45 € → 65,50 € gespart, 6,55 € je 100 km
        JSONObject r = Ersparnis.rechne(1000, 45, true, benzin(6.5, 1.70));
        assertEquals(65.0, r.getDouble("liter"), D);
        assertEquals(110.5, r.getDouble("sprit_kosten"), D);
        assertEquals(65.5, r.getDouble("ersparnis"), D);
        assertEquals(6.55, r.getDouble("ersparnis_je_100km"), D);
        assertTrue(r.getBoolean("strom_vollstaendig"));
        assertTrue(r.isNull("grund"));
    }

    @Test
    public void randfaelle() throws Exception {
        JSONObject v = benzin(6.5, 1.70);
        // unter 1 km: nie ein Wert, nie 0
        for (double km : new double[] {0, 0.4, -5, Double.NaN, Double.POSITIVE_INFINITY}) {
            JSONObject r = Ersparnis.rechne(km, 1, true, v);
            assertTrue(String.valueOf(km), r.isNull("ersparnis"));
            assertEquals("zu_wenig_km", r.getString("grund"));
        }
        // genau 1 km zählt
        assertFalse(Ersparnis.rechne(1, 0.05, true, v).isNull("ersparnis"));
        // Strom unbekannt (NaN / negativ) → keine Ersparnis, aber Spritkosten
        JSONObject s = Ersparnis.rechne(100, Double.NaN, false, v);
        assertTrue(s.isNull("ersparnis"));
        assertEquals("strom_fehlt", s.getString("grund"));
        assertEquals(11.05, s.getDouble("sprit_kosten"), D);
        assertTrue(Ersparnis.rechne(100, -1, true, v).isNull("ersparnis"));
        // gratis geladen: Strom 0 → ganze Spritkosten gespart
        assertEquals(11.05, Ersparnis.rechne(100, 0, true, v).getDouble("ersparnis"), D);
        // Strom teurer als Sprit (Schnelllader): negative Ersparnis, ehrlich ausgewiesen
        JSONObject t = Ersparnis.rechne(100, 15, true, v);
        assertEquals(-3.95, t.getDouble("ersparnis"), D);
        // unvollständige Strompreise: Wert da, aber markiert
        assertEquals("strom_unvollstaendig", Ersparnis.rechne(100, 3, false, v).getString("grund"));
        // ohne Vergleich
        assertEquals("verbrauch", Ersparnis.rechne(100, 3, true, null).getString("grund"));
    }

    static JSONObject kosten(double km, double summe, int n, int ohne, boolean voll, Double k100) throws Exception {
        return new JSONObject().put("zeitraum", "monat").put("von", 1L).put("bis", 2L).put("km", km)
                .put("kosten_summe", summe).put("kosten_summe_abgerechnet", summe).put("ladungen", n)
                .put("ladungen_ohne_preis", ohne).put("ladungen_ohne_preis_abgerechnet", ohne)
                .put("vollstaendig", voll).put("vollstaendig_abgerechnet", voll)
                .put("kosten_je_100km", k100 == null ? JSONObject.NULL : (Object) k100)
                .put("kosten_je_100km_abgerechnet", k100 == null ? JSONObject.NULL : (Object) k100);
    }

    @Test
    public void zeitraumAusLadeKosten() throws Exception {
        JSONObject v = benzin(6.5, 1.70);
        JSONObject r = Ersparnis.ausKosten(kosten(500, 20, 3, 0, true, 4.0), null, v);
        assertEquals("zeitraum", r.getString("basis"));
        assertEquals(20.0, r.getDouble("strom_kosten"), D);
        assertEquals(55.25 - 20, r.getDouble("ersparnis"), D);
        assertEquals("monat", r.getString("zeitraum"));
        assertEquals(3, r.getInt("ladungen"));
    }

    @Test
    public void monatOhneLadungNimmtDenDurchschnitt() throws Exception {
        JSONObject v = benzin(6.5, 1.70);
        JSONObject gesamt = kosten(10_000, 450, 40, 0, true, 4.5);
        // Monatsanfang: 200 km gefahren, noch nicht geladen → 200 × 4,50/100 = 9 € Strom
        JSONObject r = Ersparnis.ausKosten(kosten(200, 0, 0, 0, true, null), gesamt, v);
        assertEquals("durchschnitt", r.getString("basis"));
        assertEquals(9.0, r.getDouble("strom_kosten"), D);
        assertEquals(22.1 - 9, r.getDouble("ersparnis"), D);
        // alle Ladungen ohne Preis zählen wie „keine Ladung“
        assertEquals("durchschnitt", Ersparnis.ausKosten(kosten(200, 0, 2, 2, false, null), gesamt, v).getString("basis"));
        // ohne Durchschnitt: Strom unbekannt, nie 0
        JSONObject ohne = Ersparnis.ausKosten(kosten(200, 0, 0, 0, true, null), kosten(0, 0, 0, 0, true, null), v);
        assertTrue(ohne.isNull("ersparnis"));
        assertTrue(ohne.isNull("basis"));
        assertEquals("strom_fehlt", ohne.getString("grund"));
    }

    @Test
    public void pruefenSetztDasDatumNurBeiNeuemPreis() throws Exception {
        String[] f = new String[1];
        Map<String, Object> w = Ersparnis.pruefe(new JSONObject().put("kraftstoff", "diesel").put("verbrauch", 5.25)
                .put("preis", 1.5899), null, 1000L, f);
        assertNotNull(w);
        assertEquals("diesel", w.get("kraftstoff"));
        assertEquals(5.3, (Double) w.get("verbrauch"), D);
        assertEquals(1.59, (Double) w.get("preis"), D);
        assertEquals(1000L, w.get("preisStand"));
        // gleicher Preis → Datum bleibt
        JSONObject alt = new JSONObject().put("preis", 1.59).put("preisStand", 1000L);
        assertFalse(Ersparnis.pruefe(new JSONObject().put("preis", 1.59), alt, 2000L, f).containsKey("preisStand"));
        // neuer Preis → neues Datum
        assertEquals(2000L, Ersparnis.pruefe(new JSONObject().put("preis", 1.62), alt, 2000L, f).get("preisStand"));
        // null löscht Preis und Datum (zurück zum Richtwert)
        Map<String, Object> z = Ersparnis.pruefe(new JSONObject().put("preis", JSONObject.NULL), alt, 2000L, f);
        assertEquals(JSONObject.NULL, z.get("preis"));
        assertEquals(JSONObject.NULL, z.get("preisStand"));
    }

    @Test
    public void pruefenLehntUnsinnAb() throws Exception {
        String[][] faelle = {{"kraftstoff", "\"strom\"", "kraftstoff"}, {"verbrauch", "1.9", "verbrauch"},
                {"verbrauch", "26", "verbrauch"}, {"verbrauch", "\"viel\"", "verbrauch"}, {"preis", "0.05", "preis"},
                {"preis", "1001", "preis"}, {"preis", "\"teuer\"", "preis"}};
        for (String[] c : faelle) {
            String[] f = new String[1];
            try {
                assertNull(c[0] + c[1], Ersparnis.pruefe(new JSONObject("{\"" + c[0] + "\":" + c[1] + "}"), null, 1, f));
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            assertEquals(c[2], f[0]);
        }
        String[] f = new String[1];
        assertNull(Ersparnis.pruefe(new JSONObject(), null, 1, f));
        assertEquals("leer", f[0]);
        // HUF/CZK: hohe Literpreise sind erlaubt
        assertNotNull(Ersparnis.pruefe(new JSONObject().put("preis", 620), null, 1, f));
    }
}
