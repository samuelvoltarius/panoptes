package at.greenchili.surfmate.recording;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Ereignis-Clip: Schwellen der Erkennung. Tempo wie vom SDK (ganze km/h, alle 250 ms);
 * Sensor wie der Headunit-Sensor am Surf (Schwerkraft auf y, ~25 Hz).
 */
public class EreignisErkennerTest {

    private static final long TAKT = 250L;

    /** Tempoverlauf mit konstanter Verzögerung (m/s²) von v0 bis v1, ganze km/h. */
    private static List<double[]> bremsung(double v0, double v1, double ms2, double vorlaufS, double nachlaufS) {
        List<double[]> l = new ArrayList<>();
        long t = 0;
        for (; t < vorlaufS * 1000; t += TAKT) l.add(new double[] {t, v0});
        double v = v0;
        while (v > v1) {
            v = Math.max(v1, v - ms2 * 3.6 * TAKT / 1000.0);
            l.add(new double[] {t, Math.round(v)});
            t += TAKT;
        }
        for (long e = t + (long) (nachlaufS * 1000); t < e; t += TAKT) l.add(new double[] {t, v1});
        return l;
    }

    private static List<EreignisErkenner.Grund> fahre(EreignisErkenner e, List<double[]> verlauf, boolean fahrstufe) {
        List<EreignisErkenner.Grund> out = new ArrayList<>();
        for (double[] p : verlauf) {
            EreignisErkenner.Grund g = e.tempo((long) p[0], p[1], fahrstufe);
            if (g != null) out.add(g);
        }
        return out;
    }

    @Test
    public void vollbremsungLoestAus() {
        List<EreignisErkenner.Grund> g = fahre(new EreignisErkenner(), bremsung(80, 0, 9.0, 3, 3), true);
        assertEquals(1, g.size());
        assertEquals(EreignisErkenner.Grund.VERZOEGERUNG, g.get(0));
    }

    @Test
    public void knappUeberSchwelleLoestAus() {
        assertEquals(1, fahre(new EreignisErkenner(), bremsung(70, 10, 5.8, 2, 2), true).size());
    }

    @Test
    public void sanftesBremsenNicht() {
        assertEquals(0, fahre(new EreignisErkenner(), bremsung(100, 0, 2.0, 2, 2), true).size());
    }

    @Test
    public void normalStarkesBremsenNicht() {
        assertEquals(0, fahre(new EreignisErkenner(), bremsung(90, 0, 4.0, 2, 2), true).size());
    }

    @Test
    public void imStandNie() {
        EreignisErkenner e = new EreignisErkenner();
        assertEquals(0, fahre(e, bremsung(80, 0, 9.0, 2, 2), false).size());
        // Gang P: auch ein Stoß am Sensor zählt nicht.
        for (long t = 0; t < 5_000; t += 40) e.imu(t, 0f, 9.81f, 0f);
        assertNull(e.imu(5_000, 30f, 9.81f, 0f));
    }

    @Test
    public void langsamUnterStarttempoNicht() {
        // Rangieren: 15 km/h scharf auf 0 – kein Ereignis.
        assertEquals(0, fahre(new EreignisErkenner(), bremsung(15, 0, 8.0, 2, 2), true).size());
    }

    @Test
    public void einzelnerAusreisserNicht() {
        EreignisErkenner e = new EreignisErkenner();
        long t = 0;
        int n = 0;
        for (int i = 0; i < 40; i++, t += TAKT) {
            double v = (i == 20) ? 0 : 80;   // SDK liefert einmal 0
            if (e.tempo(t, v, true) != null) n++;
        }
        assertEquals(0, n);
    }

    @Test
    public void kurzerAussetzerMitRueckprallNicht() {
        // Zwei Proben 0, dann wieder 80 km/h: physikalisch unmöglich -> Messfehler.
        EreignisErkenner e = new EreignisErkenner();
        long t = 0;
        int n = 0;
        for (int i = 0; i < 40; i++, t += TAKT) {
            double v = (i == 20 || i == 21) ? 0 : 80;
            if (e.tempo(t, v, true) != null) n++;
        }
        assertEquals(0, n);
    }

    @Test
    public void aufprallMitStillstandLoestAus() {
        // 50 km/h -> 0 innerhalb einer Probe und bleibt 0 (Aufprall).
        EreignisErkenner e = new EreignisErkenner();
        long t = 0;
        int n = 0;
        for (int i = 0; i < 40; i++, t += TAKT) {
            double v = i < 20 ? 50 : 0;
            if (e.tempo(t, v, true) != null) n++;
        }
        assertEquals(1, n);
    }

    @Test
    public void ruhezeitUndStundenGrenze() {
        EreignisErkenner e = new EreignisErkenner();
        int n = 0;
        long basis = 0;
        for (int k = 0; k < 10; k++) {
            List<double[]> b = bremsung(80, 20, 9.0, 2, 1);
            for (double[] p : b) if (e.tempo(basis + (long) p[0], p[1], true) != null) n++;
            basis += 30_000;   // alle 30 s eine Vollbremsung
        }
        // 10 Bremsungen in 5 min: Ruhezeit 60 s -> höchstens jede zweite.
        assertEquals(5, n);

        EreignisErkenner f = new EreignisErkenner();
        n = 0;
        basis = 0;
        for (int k = 0; k < 20; k++) {
            for (double[] p : bremsung(80, 20, 9.0, 2, 1)) if (f.tempo(basis + (long) p[0], p[1], true) != null) n++;
            basis += 61_000;
        }
        // 20 Bremsungen in ~20 min: Stundengrenze 6.
        assertEquals(EreignisErkenner.MAX_PRO_STUNDE, n);
    }

    @Test
    public void lueckeSetztZurueck() {
        EreignisErkenner e = new EreignisErkenner();
        assertNull(e.tempo(0, 80, true));
        assertNull(e.tempo(250, 80, true));
        // 3 s nichts, dann 30 km/h: kein Abfall über eine Lücke hinweg.
        int n = 0;
        for (long t = 3_250; t < 6_000; t += TAKT) if (e.tempo(t, 30, true) != null) n++;
        assertEquals(0, n);
    }

    // ---------------------------------------------------------------- Sensor

    /** Sensor ruhig (Schwerkraft auf y, kleines Rauschen) von t0 bis t1. */
    private static void sensorRuhig(EreignisErkenner e, long t0, long t1) {
        for (long t = t0; t < t1; t += 40) e.imu(t, (float) (0.02 * Math.sin(t)), 9.81f, 0f);
    }

    @Test
    public void sensorBestaetigtVollbremsung() {
        EreignisErkenner e = new EreignisErkenner();
        e.tempo(-10, 0, true);
        sensorRuhig(e, 0, 4_000);
        int n = 0;
        // Ab 4 s bremst das Auto mit 9 m/s²: Sensor zeigt das waagrecht (z).
        List<double[]> b = bremsung(80, 0, 9.0, 0.25, 2);
        long t0 = 4_000;
        int idx = 0;
        for (long t = t0; t < t0 + 5_000; t += 40) {
            boolean bremst = t >= t0 + 250 && t < t0 + 2_700;
            e.imu(t, 0f, 9.81f, bremst ? -9f : 0f);
            while (idx < b.size() && t0 + (long) b.get(idx)[0] <= t) {
                if (e.tempo(t0 + (long) b.get(idx)[0], b.get(idx)[1], true) != null) n++;
                idx++;
            }
        }
        assertEquals(1, n);
    }

    @Test
    public void sensorRuhigWiderspricht() {
        // Tempo zeigt einen Sprung, der Sensor bleibt ruhig: kein Ereignis.
        EreignisErkenner e = new EreignisErkenner();
        e.tempo(-10, 0, true);
        sensorRuhig(e, 0, 4_000);
        int n = 0;
        long t = 4_000;
        for (int i = 0; i < 20; i++, t += TAKT) {
            sensorRuhig(e, t, t + TAKT);
            if (e.tempo(t, i < 8 ? 80 : 0, true) != null) n++;
        }
        assertEquals(0, n);
    }

    @Test
    public void stossWaagrechtLoestAus() {
        EreignisErkenner e = new EreignisErkenner();
        e.tempo(0, 50, true);   // fährt mit 50 km/h
        sensorRuhig(e, 0, 5_000);
        assertNull(e.imu(5_000, 0f, 9.81f, 18f));
        assertEquals(EreignisErkenner.Grund.STOSS, e.imu(5_040, 0f, 9.81f, 17f));
        assertEquals(17.0, e.letzteSpitze(), 0.5);
    }

    /** Erst fahren (über 20 km/h), dann an der Ampel stehen. */
    private static EreignisErkenner nachFahrtImStand() {
        EreignisErkenner e = new EreignisErkenner();
        long t = 0;
        for (; t < 3_000; t += TAKT) e.tempo(t, 50, true);
        for (double v = 50; v > 0; v -= 2, t += TAKT) e.tempo(t, Math.max(0, Math.round(v)), true);
        for (long ende = t + 5_000; t < ende; t += TAKT) e.tempo(t, 0, true);
        sensorRuhig(e, 0, t);
        return e;
    }

    @Test
    public void garagenausfahrtSchrittempoNie() {
        // Erster Lauf am Auto (0.1.27): Stoß bei ~6 km/h nach dem Losfahren aus dem Duplex-Parker.
        EreignisErkenner e = new EreignisErkenner();
        long t = 0;
        for (; t < 30_000; t += TAKT) e.tempo(t, 6, true);
        sensorRuhig(e, 0, 30_000);
        assertNull(e.imu(30_000, 0f, 9.81f, 30f));
        assertNull(e.imu(30_040, 0f, 9.81f, 30f));
        assertNull(e.imu(30_080, 28f, 9.81f, 0f));
    }

    @Test
    public void imStandNachFahrtNurZweiHarteSchlaege() {
        EreignisErkenner e = nachFahrtImStand();
        long t = 20_000;
        sensorRuhig(e, 8_000, t);
        // ein einzelner harter Schlag und zwei mittlere reichen im Stand nicht
        assertNull(e.imu(t, 0f, 9.81f, 30f));
        sensorRuhig(e, t + 40, t + 1_000);
        assertNull(e.imu(t + 1_000, 0f, 9.81f, 18f));
        assertNull(e.imu(t + 1_040, 0f, 9.81f, 18f));
        sensorRuhig(e, t + 1_080, t + 2_000);
        // Auffahrunfall an der Ampel: zwei harte Schläge kurz hintereinander
        assertNull(e.imu(t + 2_000, 0f, 9.81f, 30f));
        assertEquals(EreignisErkenner.Grund.STOSS, e.imu(t + 2_040, 0f, 9.81f, 27f));
    }

    @Test
    public void parkenSetztSchnellZurueck() {
        EreignisErkenner e = nachFahrtImStand();
        e.tempo(9_000, Double.NaN, false);    // Gang P
        long t = 10_000;
        for (; t < 15_000; t += TAKT) e.tempo(t, 5, true);   // wieder losrangieren
        sensorRuhig(e, 9_000, t);
        assertNull(e.imu(t, 0f, 9.81f, 30f));
        assertNull(e.imu(t + 40, 0f, 9.81f, 30f));
    }

    @Test
    public void schlaglochSenkrechtNicht() {
        EreignisErkenner e = new EreignisErkenner();
        e.tempo(0, 50, true);
        sensorRuhig(e, 0, 5_000);
        // Senkrechte Schläge bis 3 g.
        assertNull(e.imu(5_000, 0f, 9.81f + 25f, 0f));
        assertNull(e.imu(5_040, 0f, 9.81f - 20f, 0f));
        assertNull(e.imu(5_080, 0f, 9.81f + 15f, 0f));
    }

    @Test
    public void einzelnerMittlererStossNicht() {
        EreignisErkenner e = new EreignisErkenner();
        e.tempo(0, 50, true);
        sensorRuhig(e, 0, 5_000);
        assertNull(e.imu(5_000, 16f, 9.81f, 0f));
        sensorRuhig(e, 5_040, 6_000);
        assertNull(e.imu(6_000, 16f, 9.81f, 0f));
    }

    @Test
    public void heftigerEinzelstossLoestAus() {
        EreignisErkenner e = new EreignisErkenner();
        e.tempo(0, 50, true);
        sensorRuhig(e, 0, 5_000);
        assertNotNull(e.imu(5_000, 26f, 9.81f, 0f));
    }

    @Test
    public void sensorEinschwingenZaehltNicht() {
        EreignisErkenner e = new EreignisErkenner();
        e.tempo(0, 50, true);
        // Erste Probe schräg, dann sofort ein großer Wert: in der Einschwingzeit nie.
        e.imu(0, 0f, 9.81f, 0f);
        assertNull(e.imu(40, 30f, 9.81f, 0f));
    }

    @Test
    public void sensorGebuendeltBestaetigtSpaeter() {
        // Sensor im sparsamen Takt: 25 Hz, Proben kommen gesammelt einmal pro Sekunde.
        EreignisErkenner e = new EreignisErkenner();
        e.tempo(-10, 0, true);
        sensorRuhig(e, 0, 4_000);
        List<double[]> b = bremsung(80, 0, 9.0, 1, 2);
        long t0 = 4_000;
        long ende = t0 + (long) b.get(b.size() - 1)[0];
        long bremsVon = t0 + 1_000, bremsBis = t0 + 3_500;
        int n = 0;
        int idx = 0;
        long imuGeliefert = t0;
        for (long t = t0; t <= ende; t += 50) {
            while (idx < b.size() && t0 + (long) b.get(idx)[0] <= t) {
                if (e.tempo(t0 + (long) b.get(idx)[0], b.get(idx)[1], true) != null) n++;
                idx++;
            }
            if (t - imuGeliefert >= 1_000) {   // Bündel der letzten Sekunde
                for (long s = imuGeliefert; s < t; s += 40) {
                    boolean bremst = s >= bremsVon && s < bremsBis;
                    if (e.imu(s, 0f, 9.81f, bremst ? -9f : 0f) != null) n++;
                }
                imuGeliefert = t;
            }
        }
        assertEquals(1, n);
    }

    @Test
    public void sensorGebuendeltRuhigWiderspricht() {
        EreignisErkenner e = new EreignisErkenner();
        e.tempo(-10, 0, true);
        sensorRuhig(e, 0, 4_000);
        int n = 0;
        long imuGeliefert = 4_000;
        long t = 4_000;
        for (int i = 0; i < 24; i++, t += TAKT) {
            if (e.tempo(t, i < 8 ? 80 : 0, true) != null) n++;
            if (t - imuGeliefert >= 1_000) {
                for (long s = imuGeliefert; s < t; s += 40) if (e.imu(s, 0f, 9.81f, 0f) != null) n++;
                imuGeliefert = t;
            }
        }
        assertEquals(0, n);
    }

    /** Nach einer schnelleren Fahrt langsam rangieren (v km/h) bis t. */
    private static long rangieren(EreignisErkenner e, double v) {
        long t = 0;
        for (; t < 3_000; t += TAKT) e.tempo(t, 50, true);
        for (double x = 50; x > v; x -= 2, t += TAKT) e.tempo(t, Math.round(x), true);
        for (long ende = t + 5_000; t < ende; t += TAKT) e.tempo(t, v, true);
        sensorRuhig(e, 0, t);
        return t;
    }

    @Test
    public void anprallBeimRangierenBisStillstandLoestAus() {
        EreignisErkenner e = new EreignisErkenner();
        long t = rangieren(e, 5);
        assertNull(e.imu(t, 0f, 9.81f, 30f));
        assertNull(e.imu(t + 40, 0f, 9.81f, 28f));     // rollt noch: wartet
        assertNull(e.tempo(t + 250, 3, true));
        assertEquals(EreignisErkenner.Grund.STOSS, e.tempo(t + 500, 0, true));
        assertEquals(30.0, e.letzteSpitze(), 3.0);
    }

    @Test
    public void schwelleUeberrollenNicht() {
        EreignisErkenner e = new EreignisErkenner();
        long t = rangieren(e, 6);
        assertNull(e.imu(t, 0f, 9.81f, 30f));
        assertNull(e.imu(t + 40, 0f, 9.81f, 28f));
        int n = 0;
        for (long x = t + 250; x < t + 4_000; x += TAKT) if (e.tempo(x, 6, true) != null) n++;
        // auch ein späteres Anhalten (nach 1,5 s) macht daraus kein Ereignis
        for (long x = t + 4_000; x < t + 6_000; x += TAKT) if (e.tempo(x, 0, true) != null) n++;
        assertEquals(0, n);
    }
}
