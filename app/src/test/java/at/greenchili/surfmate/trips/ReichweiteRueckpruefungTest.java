package at.greenchili.surfmate.trips;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 0.1.26.1 (Koordination 28.10., REICHWEITE_ANALYSE.md): Spanne aus dem eigenen 100-km-Rückprüffehler statt
 * Mittel ± σ einzelner Fahrten (Alfreds Auto: 187–409 km), dünne Töpfe → Nachbarn/Gesamt mit „lernt noch“,
 * Klima-Anteil je Fahrt. Synthetische Fahrten – keine Daten vom Auto im Repo.
 */
public class ReichweiteRueckpruefungTest {

    private static final long TAG = 86_400_000L;

    /** Fahrt mit gemessener Energie (kWh-Delta), Stadt, mild, Score 75. */
    private static TripRecord fahrt(int i, double km, double kwhProKm, int temp, double tempo) {
        TripRecord t = new TripRecord();
        t.startTime = 1_780_000_000_000L + i * TAG / 3;
        t.endTime = t.startTime + (long) (km / tempo * 3_600_000L);
        t.distanceKm = km;
        t.durationSeconds = (int) (km / tempo * 3600);
        t.avgSpeedKmh = tempo;
        t.extTempC = temp;
        t.kwhStart = 30;
        t.kwhEnd = 30 - km * kwhProKm;
        t.socStart = 70;
        t.socEnd = 60;
        t.anticipationScore = t.smoothnessScore = t.speedDisciplineScore = t.efficiencyScore = t.consistencyScore = 75;
        return t;
    }

    /** Alltag: längere Fahrten um 0,15 kWh/km (±10 %), dazwischen kalte Kurzfahrten mit grober Auflösung. */
    private static List<TripRecord> alltag(int n) {
        Random r = new Random(7);
        List<TripRecord> l = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (i % 3 == 2) l.add(fahrt(i, 1.0 + r.nextDouble(), 0.2 + 0.2 * r.nextDouble(), 15, 15));   // 0,2–0,4 kWh/km
            else l.add(fahrt(i, 10 + 20 * r.nextDouble(), 0.15 * (0.9 + 0.2 * r.nextDouble()), 15, 30));
        }
        return l;
    }

    @Test
    public void spanneAusDemHundertKmFehlerIstSchmalUndEhrlich() {
        ReichweitenRueckpruefung.Ergebnis e = ReichweitenRueckpruefung.rechne(alltag(150), 43);
        assertNotNull(e);
        assertTrue("genug Fenster", e.fenster >= ReichweitenRueckpruefung.MIN_FENSTER);
        assertTrue("P25 ≤ P75", e.p25 <= e.p75);
        double[] band = RangeEstimator.band(260, e);
        assertNotNull(band);
        // ein Drittel extreme Kurzfahrten im selben Topf: Spanne verschoben, aber begrenzt (alt: 187–409 bei 257)
        assertTrue("untere " + band[0], band[0] > 260 * 0.8);
        assertTrue("obere " + band[1], band[1] < 260 * 1.4);
        assertTrue("Schätzung liegt in der Spanne", band[0] <= 260 && band[1] >= 260);
        // Kurzfahrten heben den Topf-Mittelwert, 100 km verbrauchen weniger: Faktoren < 1, Spanne trotzdem um 260
        double[] schief = RangeEstimator.band(260, new ReichweitenRueckpruefung.Ergebnis(0.80, 0.95, 50));
        assertEquals(260, schief[0], 1e-9);
        assertEquals(325, schief[1], 1e-9);
    }

    @Test
    public void ohneGenugFensterLerntNoch() {
        assertNull(ReichweitenRueckpruefung.rechne(alltag(25), 43));
        assertNull(RangeEstimator.band(260, null));
    }

    @Test
    public void jedeVorhersageNurAusFahrtenDavor() {
        // Verbrauch springt nach Fahrt 60 dauerhaft auf das Doppelte: die Fenster direkt danach müssen es merken
        List<TripRecord> l = new ArrayList<>();
        for (int i = 0; i < 120; i++) l.add(fahrt(i, 20, i < 60 ? 0.15 : 0.30, 15, 30));
        ReichweitenRueckpruefung.Ergebnis e = ReichweitenRueckpruefung.rechne(l, 43);
        assertNotNull(e);
        assertTrue("Zukunft fließt nicht in die Vorhersage ein (sonst wäre P75 ≈ 1)", e.p75 > 1.2);
    }

    @Test
    public void duennerTopfNimmtNachbarnUndSagtLerntNoch() {
        ReichweitenRueckpruefung.SpeicherToepfe t = new ReichweitenRueckpruefung.SpeicherToepfe();
        for (int i = 0; i < 40; i++) t.dazu("city_mild_high", 0.15);
        for (int i = 0; i < 5; i++) t.dazu("city_cold_high", 0.30);   // 5 kalte Kurzfahrten (132-km-Fall)
        RangeEstimator.BucketResult kalt = RangeEstimator.aufloesen(t, 30, 8, 80);
        assertNotNull(kalt);
        assertFalse("kein genauer Topf → lernt noch", kalt.exakt);
        assertTrue("nicht der Extremwert 0,30: " + kalt.mean, kalt.mean < 0.25);
        RangeEstimator.BucketResult mild = RangeEstimator.aufloesen(t, 30, 15, 80);
        assertTrue(mild.exakt);
        assertEquals(0.15, mild.mean, 1e-9);
        assertEquals(10, RangeEstimator.MIN_BUCKET_SAMPLES);
    }

    @Test
    public void verbrauchWieBeimLernen() {
        assertEquals(0.15, RangeEstimator.verbrauch(fahrt(0, 20, 0.15, 15, 30), 43), 1e-9);
        assertTrue("≤ 0,5 km zählt nicht", Double.isNaN(RangeEstimator.verbrauch(fahrt(0, 0.4, 0.15, 15, 30), 43)));
        assertTrue("Ausreißer", Double.isNaN(RangeEstimator.verbrauch(fahrt(0, 10, 0.9, 15, 30), 43)));
    }

    @Test
    public void schaetzungMeldetTemperaturUndLerntNoch() throws Exception {
        RangeEstimate e = new RangeEstimate();
        e.tempC = 8;
        e.lerntNoch = true;
        org.json.JSONObject j = e.toJson();
        assertEquals(8, j.getInt("tempC"));
        assertTrue(j.getBoolean("lerntNoch"));
        assertFalse(j.getBoolean("bandAusRueckpruefung"));
    }

    @Test
    public void klimaAnteilJeFahrt() {
        TripRecord t = new TripRecord();
        assertEquals("alte Fahrt: unbekannt", -1, t.klimaAnteil(), 0);
        for (int i = 0; i < 30; i++) TripDetector.klimaZaehlen(t, 1);
        for (int i = 0; i < 70; i++) TripDetector.klimaZaehlen(t, 0);
        TripDetector.klimaZaehlen(t, -2147483648);   // UNAVAILABLE zählt nicht
        assertEquals(0.30, t.klimaAnteil(), 1e-9);
    }
}
