package at.greenchili.surfmate.reichweite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * F1 „Reicht es noch?“ (F1_KONZEPT.md). Trockenlauf mit nachgebildeter Fahrt entlang einer
 * geraden Planlinie (Ost-West, 47,8° N), keine Wirkung am Auto.
 */
public class RangeWatchTest {

    private static final double LAT = 47.8;
    /** km je Längengrad bei 47,8° N. */
    private static final double KM_PRO_GRAD = RangeWatch.distKm(LAT, 13.0, LAT, 14.0);

    private static double lonBeiKm(double km) { return 13.0 + km / KM_PRO_GRAD; }

    /** 200 km Linie, Stopp bei 120 km (an 20 %, ab 80 %), Start 90 %, Ankunft 50 %, Reserve 10 %. */
    private static RangeWatch.Plan plan() {
        List<double[]> linie = new ArrayList<>();
        for (int km = 0; km <= 200; km += 5) linie.add(new double[] {LAT, lonBeiKm(km)});
        return new RangeWatch.Plan(linie,
                Arrays.asList(new RangeWatch.Stopp("Ionity Ansfelden", 120, 20, 80)), 90, 50, 10, "Wien");
    }

    @Test
    public void sollCurveAndPlanConsumptionAcrossAStop() {
        RangeWatch.Plan p = plan();
        assertEquals(200, p.zielKm, 0.5);
        assertEquals(90, p.soll(0), 1e-9);
        assertEquals(55, p.soll(60), 1e-6);           // halbe Strecke bis zum Stopp
        assertEquals(20, p.soll(120), 1e-6);          // Ankunft am Stopp
        assertEquals(65, p.soll(160), 0.2);           // nach dem Laden: 80 → 50 auf 80 km
        assertEquals(70, p.planVerbrauch(0, 120), 1e-6);
        assertEquals(70 + 15, p.planVerbrauch(0, 160), 0.2);   // Laden zählt nicht als Verbrauch
    }

    @Test
    public void onPlanIsPasst() {
        RangeWatch w = new RangeWatch(plan());
        RangeWatch.Lage l = null;
        for (int km = 0; km <= 100; km++) {   // 1 km je Messung (real: ~0,4 km je 10 s)
            l = w.messung(km * 30_000L, LAT, lonBeiKm(km), plan().soll(km), 15, false);
        }
        assertTrue(l.aktiv);
        assertEquals(RangeWatch.Stufe.PASST, l.stufe);
        assertEquals(100, l.kmGefahren, 0.3);
        assertEquals(1.0, l.faktor, 0.02);
        assertEquals(20, l.prognoseProzent, 0.6);    // Prognose am Stopp = Plan
        assertEquals("Ionity Ansfelden", l.naechsterName);
        assertEquals(20, l.naechsterKm, 0.5);
        assertFalse(l.naechsterIstZiel);
    }

    @Test
    public void overConsumptionLeadsToReichtNichtOnlyAfterTwoMinutes() {
        RangeWatch.Plan p = plan();
        RangeWatch w = new RangeWatch(p);
        RangeWatch.Lage l = null;
        long t = 0;
        // 35 % Mehrverbrauch ab Start
        for (int km = 0; km <= 60; km++) {
            double soc = 90 - (90 - p.soll(km)) * 1.35;
            t = km * 50_000L;
            l = w.messung(t, LAT, lonBeiKm(km), soc, 15, false);
        }
        assertTrue("Faktor gedämpft > 1", l.faktor > 1.1 && l.faktor <= 1.4);
        assertTrue("Prognose am Stopp unter Reserve", l.prognoseProzent < 10);
        assertEquals(RangeWatch.Stufe.REICHT_NICHT, l.stufe);
        // Flatterschutz: ein kurzer Ausreißer ändert die Stufe nicht
        RangeWatch.Lage kurz = w.messung(t + 30_000L, LAT, lonBeiKm(60.5), 90, 15, false);
        assertEquals(RangeWatch.Stufe.REICHT_NICHT, kurz.stufe);
    }

    @Test
    public void hysteresisNeedsTwoMinutesAndExtraMarginBackToPasst() {
        assertEquals(RangeWatch.Stufe.KNAPP, RangeWatch.bewerte(16, 0, 10, false, RangeWatch.Stufe.KNAPP));
        assertEquals(RangeWatch.Stufe.PASST, RangeWatch.bewerte(16, 0, 10, false, RangeWatch.Stufe.PASST));
        assertEquals(RangeWatch.Stufe.PASST, RangeWatch.bewerte(17, 0, 10, false, RangeWatch.Stufe.KNAPP));
        assertEquals(RangeWatch.Stufe.KNAPP, RangeWatch.bewerte(30, -6, 10, false, RangeWatch.Stufe.PASST));
        assertEquals(RangeWatch.Stufe.REICHT_NICHT, RangeWatch.bewerte(9.9, 0, 10, false, RangeWatch.Stufe.PASST));
        assertEquals(RangeWatch.Stufe.REICHT_NICHT, RangeWatch.bewerte(50, 0, 10, true, RangeWatch.Stufe.PASST));
    }

    @Test
    public void offRouteIsInactiveAndJumpsNeedConfirmation() {
        RangeWatch.Plan p = plan();
        RangeWatch w = new RangeWatch(p);
        assertFalse("2 km neben der Linie", w.messung(0, LAT + 0.02, lonBeiKm(10), 80, 15, false).aktiv);
        w.messung(0, LAT, lonBeiKm(10), p.soll(10), 15, false);
        RangeWatch.Lage sprung = w.messung(10_000, LAT, lonBeiKm(50), p.soll(50), 15, false);
        assertEquals("einzelner Sprung um 40 km wird nicht übernommen", 10, sprung.kmGefahren, 0.3);
        w.messung(20_000, LAT, lonBeiKm(50.2), p.soll(50), 15, false);
        RangeWatch.Lage drei = w.messung(30_000, LAT, lonBeiKm(50.4), p.soll(50), 15, false);
        assertEquals("nach 3 übereinstimmenden Messungen", 50.4, drei.kmGefahren, 0.3);
        RangeWatch.Lage zurueck = w.messung(40_000, LAT, lonBeiKm(45), p.soll(45), 15, false);
        assertEquals("nur vorwärts", 50.4, zurueck.kmGefahren, 0.3);
    }

    @Test
    public void verlaufStaysSmallAndCoversTheTrip() {
        RangeWatch.Plan p = plan();
        RangeWatch w = new RangeWatch(p);
        RangeWatch.Lage l = null;
        for (int km = 0; km <= 199; km++) {
            l = w.messung(km * 30_000L, LAT, lonBeiKm(km), p.soll(km) - 1, 15, false);
        }
        assertTrue(l.verlauf.size() <= RangeWatch.VERLAUF_MAX);
        assertTrue(l.verlauf.size() >= 25);
        assertEquals(0, l.verlauf.get(0)[0], 0.5);
        assertTrue("reicht bis zur aktuellen Position", l.verlauf.get(l.verlauf.size() - 1)[0] > 190);
        double[] q = l.verlauf.get(10);
        assertEquals(p.soll(q[0]), q[1], 1e-6);
        assertEquals("Ist = Soll - 1 (Projektion weicht nur minimal ab)", q[1] - 1, q[2], 0.01);
    }

    @Test
    public void coldRaisesReserve() {
        RangeWatch.Plan p = plan();
        RangeWatch warm = new RangeWatch(p);
        RangeWatch kalt = new RangeWatch(p);
        RangeWatch.Lage lw = warm.messung(0, LAT, lonBeiKm(100), p.soll(100) - 4, 5, false);
        RangeWatch.Lage lk = kalt.messung(0, LAT, lonBeiKm(100), p.soll(100) - 4, -2, false);
        assertEquals(10, lw.reserveWirksam, 0);
        assertEquals(13, lk.reserveWirksam, 0);
        assertEquals(RangeWatch.Stufe.PASST, lw.stufe);       // Prognose ~16 % ≥ 10 + 5
        assertEquals(RangeWatch.Stufe.KNAPP, lk.stufe);       // 13 ≤ 16 < 18
    }

    @Test
    public void fullStopIsReichtNichtButNotAfterTheLastStop() {
        RangeWatch.Plan p = plan();
        RangeWatch w = new RangeWatch(p);
        assertEquals(RangeWatch.Stufe.REICHT_NICHT, w.messung(0, LAT, lonBeiKm(100), p.soll(100), 15, true).stufe);
        RangeWatch nachStopp = new RangeWatch(p);
        RangeWatch.Lage l = nachStopp.messung(0, LAT, lonBeiKm(150), p.soll(150), 15, true);
        assertTrue(l.naechsterIstZiel);
        assertEquals("Wien", l.naechsterName);
        assertEquals("„voll“ gilt nur für einen Ladestopp", RangeWatch.Stufe.PASST, l.stufe);
    }
}
