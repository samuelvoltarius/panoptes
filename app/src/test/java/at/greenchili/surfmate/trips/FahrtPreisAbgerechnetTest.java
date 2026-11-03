package at.greenchili.surfmate.trips;

import static org.junit.Assert.assertEquals;

import at.greenchili.surfmate.charging.LadeAbrechnung;

import org.json.JSONObject;
import org.junit.Test;

/**
 * 03.11.2026: Fahrtkosten mit derselben Regel wie die Ladeanzeige. Die Fahrt verbraucht Akku-kWh, bezahlt wurde die
 * abgerechnete Menge – also Preis je Akku-kWh = abgerechnete Kosten ÷ Akku-kWh der letzten Ladung.
 */
public class FahrtPreisAbgerechnetTest {

    @Test
    public void satzNimmtAbgerechnetenPreis_sonstDenGespeicherten() throws Exception {
        TripAnalyticsManager.Satz s = TripAnalyticsManager.satz(
                new JSONObject().put("rate", 0.29).put("rateAbgerechnet", 0.3132).put("tariffLabel", "DC"));
        assertEquals("charge", s.quelle);
        assertEquals(0.3132, s.rate, 1e-9);
        assertEquals("DC", s.label);
        assertEquals(0.29, TripAnalyticsManager.satz(new JSONObject().put("rate", 0.29)).rate, 1e-9);   // ältere Antwort
    }

    @Test
    public void preisJeAkkuKwh_passtZurLadeanzeige() {
        // ohne Eintrag: DC-Vorschlag 8 %
        assertEquals(0.29 * 1.08, LadeAbrechnung.preisJeAkkuKwh(0.29, 25.5, true, Double.NaN, Double.NaN, null), 1e-9);
        // kWh laut Säule: 28 / 25,5 mehr
        assertEquals(0.29 * 28 / 25.5, LadeAbrechnung.preisJeAkkuKwh(0.29, 25.5, true, 28, Double.NaN, null), 1e-9);
        // Betrag gesetzt (auch über „Kosten ändern“): genau Betrag ÷ Akku-kWh, kein Zuschlag
        assertEquals(8.12 / 25.5, LadeAbrechnung.preisJeAkkuKwh(0.29, 25.5, true, Double.NaN, 8.12, null), 1e-9);
        // gratis bleibt gratis
        assertEquals(0, LadeAbrechnung.preisJeAkkuKwh(0, 18, false, Double.NaN, Double.NaN, null), 0);
        // Fahrt × Preis = Anteil der abgerechneten Ladekosten: 10 kWh verbraucht von 25,5 geladen
        double kosten = LadeAbrechnung.berechne(25.5, 0.29 * 25.5, true, Double.NaN, Double.NaN, null).kosten;
        assertEquals(kosten * 10 / 25.5, 10 * LadeAbrechnung.preisJeAkkuKwh(0.29, 25.5, true, Double.NaN, Double.NaN, null), 1e-9);
    }
}
