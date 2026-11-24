package at.greenchili.surfmate.recording;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.roadsense.detect.ImuAccelSample;
import at.greenchili.surfmate.roadsense.detect.ImuFrameCodec;
import at.greenchili.surfmate.roadsense.detect.ImuGyroSample;
import at.greenchili.surfmate.roadsense.detect.ImuSource;
import at.greenchili.surfmate.surveillance.ShockDetector;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 0.1.43.20 (FIX_014320_IMU): Ereigniserkennung mit zwei IMU-Chips und Kreisel, synthetische Sensorreihen.
 *
 * <p>Nachgebaut wie am Surf (Inventur 20.11.2026): Hauptchip smi230 mit Schwerkraft auf x (9,74 / −0,62 / 0,01),
 * Rauschen σ 0,011; zweiter Chip icm40607 mit Schwerkraft auf y (0 / 9,80 / 0), gedämpft. Beide 25 Hz (40 ms).
 */
public class ZweiSensorenTest {

    private static final long SCHRITT = 40L;
    private static final double[] G1 = {9.74, -0.62, 0.01};
    private static final double[] G2 = {0.0, 9.80, 0.0};

    /** Eine Fahrt: zwei Chips, Tempo alle 250 ms. Dynamik je Schritt in den Achsen des jeweiligen Chips. */
    static final class Sim {
        final ZweiSensoren z = new ZweiSensoren();
        final EreignisErkenner e = new EreignisErkenner(z);
        final Random r = new Random(7);
        final List<EreignisErkenner.Grund> out = new ArrayList<>();
        long t;
        long letztesTempo = Long.MIN_VALUE;
        double kmh = 50;
        boolean fahrstufe = true;
        boolean zweiter = true;
        /** Zweiter Chip ist eine Attrappe: konstante Werte, bewegt sich nie. */
        boolean attrappe;
        /** Zweiter Chip liefert so viel später (Bündelung). */
        long zweitVerzugMs;
        /** Zweiter Chip liefert gar nichts mehr. */
        boolean zweitStumm;
        private final ArrayDeque<Object[]> spaeter = new ArrayDeque<>();

        Sim() {
            e.gleicherChip(true);
        }

        double rausch(double s) { return r.nextGaussian() * s; }

        void schritt(double[] a1, double[] a2, double[] w1, double[] w2) {
            if (letztesTempo == Long.MIN_VALUE || t - letztesTempo >= 250) {
                letztesTempo = t;
                nimm(e.tempo(t, Math.round(kmh), fahrstufe));
            }
            if (zweiter && !zweitStumm) {
                double[] b = attrappe ? new double[] {0.01, 9.80, 0.0}
                        : new double[] {G2[0] + a2[0] + rausch(0.004), G2[1] + a2[1] + rausch(0.004), G2[2] + a2[2]};
                double[] d = attrappe ? new double[] {0, 0, 0} : w2;
                spaeter.add(new Object[] {t, b, d});
                while (!spaeter.isEmpty() && (long) spaeter.peek()[0] <= t - zweitVerzugMs) {
                    Object[] p = spaeter.poll();
                    long tt = (long) p[0];
                    double[] bb = (double[]) p[1], dd = (double[]) p[2];
                    nimm(e.imu2(tt, (float) bb[0], (float) bb[1], (float) bb[2]));
                    nimm(e.gyro2(tt, (float) dd[0], (float) dd[1], (float) dd[2]));
                }
            }
            nimm(e.gyro(t, (float) (w1[0] + rausch(0.002)), (float) (w1[1] + rausch(0.002)), (float) (w1[2] + rausch(0.002))));
            nimm(e.imu(t, (float) (G1[0] + a1[0] + rausch(0.011)), (float) (G1[1] + a1[1] + rausch(0.011)),
                    (float) (G1[2] + a1[2] + rausch(0.011))));
            t += SCHRITT;
        }

        void nimm(EreignisErkenner.Grund g) { if (g != null) out.add(g); }

        void ruhig(long ms) {
            for (long e = t + ms; t < e; ) schritt(n(), n(), n(), n());
        }

        /** Normale Straße: Rütteln beider Chips (der zweite gedämpft), leichte Kurven. Lässt den zweiten sich bewähren. */
        void strasse(long ms) {
            for (long e = t + ms; t < e; ) {
                double s = t / 1000.0;
                double v1 = 0.8 * Math.sin(2 * Math.PI * 3 * s), v2 = 0.6 * Math.sin(2 * Math.PI * 5 * s);
                double gier = 0.3 * Math.sin(2 * Math.PI * 0.5 * s);
                schritt(new double[] {0.3 * v2, v1, v2}, new double[] {0.6 * v1, 0.2 * v2, 0.6 * v2},
                        new double[] {gier, 0.02, 0}, new double[] {0, gier, 0.02});
            }
        }

        boolean bewaehrt() { return z.beschlBewaehrt() && z.drehBewaehrt(); }
    }

    private static double[] n() { return new double[] {0, 0, 0}; }

    private static Sim gefahren(boolean zweiter) {
        Sim s = new Sim();
        s.zweiter = zweiter;
        s.strasse(15_000);
        return s;
    }

    // ---------------------------------------------------------------- Bewährung

    @Test
    public void zweiterChipBewaehrtSichNurWennErMitgeht() {
        Sim s = gefahren(true);
        assertTrue("geht mit → bewährt", s.bewaehrt());
        assertTrue(s.out.isEmpty());

        Sim a = new Sim();
        a.attrappe = true;   // wie die Seal-Attrappe: eingefroren
        a.strasse(15_000);
        assertFalse(a.z.beschlBewaehrt());
        assertFalse(a.z.drehBewaehrt());
        assertEquals(ZweiSensoren.Urteil.UNBEKANNT,
                a.z.beschlBestaetigt(a.t - 500, a.t, a.t, 20, ZweiSensoren.BESCHL_ANTEIL));
    }

    // ---------------------------------------------------------------- Bremsen

    private static void vollbremsung(Sim s, double zweitAnteil) {
        double v = 80;
        s.kmh = v;
        s.ruhig(2_000);   // glatte Straße vor der Bremsung
        while (v > 0) {
            v = Math.max(0, v - 9.0 * 3.6 * SCHRITT / 1000.0);
            s.kmh = v;
            // Verzögerung waagrecht: Hauptchip z, zweiter Chip x (gedämpft)
            s.schritt(new double[] {0, 0, -9.0}, new double[] {-9.0 * zweitAnteil, 0, 0}, n(), n());
        }
        s.ruhig(3_000);
    }

    @Test
    public void bremsenBeideChipsSichern() {
        Sim s = gefahren(true);
        vollbremsung(s, 0.8);
        assertEquals(Collections.singletonList(EreignisErkenner.Grund.VERZOEGERUNG), s.out);
        assertEquals("tempo+sensor+zweit", s.e.letzteDetails().quelle);
    }

    @Test
    public void bremsenNurAmHauptchipIstEinFehler() {
        Sim s = gefahren(true);
        vollbremsung(s, 0.0);   // zweiter Chip (bewährt) bleibt ruhig
        assertTrue(s.out.isEmpty());
        assertTrue(s.e.letzteAbweisung(), s.e.letzteAbweisung().startsWith("verzoegerung"));
    }

    // ---------------------------------------------------------------- Schlagloch

    @Test
    public void schlaglochLoestNichtsAus() {
        Sim s = gefahren(true);
        // senkrechter Schlag (Hauptchip x = Schwerkraft, zweiter y) 20 m/s² über 80 ms, dazu Nicken 1,5 rad/s kurz
        for (int i = 0; i < 2; i++) {
            s.schritt(new double[] {20, 0, 0}, new double[] {0, 12, 0}, new double[] {0, 1.5, 0}, new double[] {1.5, 0, 0});
        }
        for (int i = 0; i < 2; i++) {
            s.schritt(new double[] {-12, 0, 0}, new double[] {0, -8, 0}, new double[] {0, -1.5, 0}, new double[] {-1.5, 0, 0});
        }
        s.strasse(3_000);
        assertTrue(s.out.toString(), s.out.isEmpty());
    }

    // ---------------------------------------------------------------- Aufprall

    @Test
    public void aufprallBeideChipsSichern() {
        Sim s = gefahren(true);
        for (int i = 0; i < 3; i++) s.schritt(new double[] {0, 0, 30}, new double[] {18, 0, 0}, n(), n());
        s.ruhig(2_000);
        assertEquals(Collections.singletonList(EreignisErkenner.Grund.STOSS), s.out);
        assertEquals("sensor+zweit", s.e.letzteDetails().quelle);
        assertNotNull(s.e.letzteDetails().werte.get("zweitSpitzeMs2"));
    }

    @Test
    public void spitzeNurAmHauptchipIstEinFehler() {
        Sim s = gefahren(true);
        s.schritt(new double[] {0, 0, 30}, n(), n(), n());   // Bitfehler: nur ein Chip, eine Probe
        s.ruhig(2_000);
        assertTrue(s.out.isEmpty());
        assertTrue(s.e.letzteAbweisung(), s.e.letzteAbweisung().startsWith("stoss_einzeln"));
    }

    @Test
    public void zweiterChipSpaetWirdAbgewartet() {
        Sim s = gefahren(true);
        s.zweitVerzugMs = 600;   // Bündelung: der zweite Chip kommt später
        for (int i = 0; i < 3; i++) s.schritt(new double[] {0, 0, 30}, new double[] {18, 0, 0}, n(), n());
        assertTrue("wartet auf den zweiten Chip", s.out.isEmpty());
        s.ruhig(1_000);
        assertEquals(Collections.singletonList(EreignisErkenner.Grund.STOSS), s.out);
        assertEquals("sensor+zweit", s.e.letzteDetails().quelle);
    }

    @Test
    public void zweiterChipVerstummtImZweifelSichern() {
        Sim s = gefahren(true);
        s.zweitStumm = true;   // bewährt, aber liefert ab jetzt nichts mehr
        s.schritt(new double[] {0, 0, 30}, n(), n(), n());
        s.ruhig(2_000);
        assertEquals(Collections.singletonList(EreignisErkenner.Grund.STOSS), s.out);
        assertEquals("sensor", s.e.letzteDetails().quelle);
    }

    @Test
    public void ausstiegInPWaehrendDesWartensSichert() {
        Sim s = gefahren(true);
        s.zweitVerzugMs = 5_000;
        s.schritt(new double[] {0, 0, 30}, n(), n(), n());
        assertTrue(s.out.isEmpty());
        assertEquals(EreignisErkenner.Grund.STOSS, s.e.tempo(s.t + 100, 0, false));
    }

    // ---------------------------------------------------------------- harte Grenze (Koordination 24.11.)

    /** Hauptchip 5 g waagrecht, zweiter Chip (bewährt) ruhig → trotzdem gesichert. */
    @Test
    public void fuenfGAmHauptchipImmerGesichert() {
        Sim s = gefahren(true);
        assertTrue(s.bewaehrt());
        s.schritt(new double[] {0, 0, 5 * 9.80665}, n(), n(), n());
        s.ruhig(2_000);
        assertEquals(Collections.singletonList(EreignisErkenner.Grund.STOSS), s.out);
        assertEquals(1.0, s.e.letzteDetails().werte.get("hart"), 0);
        assertEquals(EreignisErkenner.HART_MS2, s.e.letzteDetails().schwellen.get("hartMs2"), 1e-9);
        assertEquals(0, s.e.abweisungen());
        assertEquals(39.2, EreignisErkenner.HART_MS2, 0.05);
    }

    @Test
    public void harterStossBeendetWartenSofort() {
        Sim s = gefahren(true);
        s.zweitVerzugMs = 5_000;
        s.schritt(new double[] {0, 0, 30}, n(), n(), n());   // wartet auf den zweiten Chip
        assertTrue(s.out.isEmpty());
        s.schritt(new double[] {0, 0, 45}, n(), n(), n());   // 4,6 g
        assertEquals(Collections.singletonList(EreignisErkenner.Grund.STOSS), s.out);
        assertEquals(1.0, s.e.letzteDetails().werte.get("hart"), 0);
    }

    /** Messbereich ±4 g: der Chip sättigt bei ~39 m/s², der Anschlag auf einer waagrechten Achse zählt als hart. */
    @Test
    public void anschlagImMessbereichZaehltAlsHart() {
        Sim s = gefahren(true);
        s.e.messbereich(4 * 9.80665);
        s.schritt(new double[] {0, 0, 38.0}, n(), n(), n());   // 38 ≥ 0,95 × 39,2, aber < 4 g
        s.ruhig(2_000);
        assertEquals(Collections.singletonList(EreignisErkenner.Grund.STOSS), s.out);

        Sim u = gefahren(true);   // Messbereich unbekannt: 38 m/s² nur an einem Chip = Fehler
        u.schritt(new double[] {0, 0, 38.0}, n(), n(), n());
        u.ruhig(2_000);
        assertTrue(u.out.isEmpty());
        // Schwerkraft-Achse im Anschlag (senkrechter Schlag) zählt nicht als hart
        Sim v = gefahren(true);
        v.e.messbereich(2 * 9.80665);
        v.schritt(new double[] {9.0, 0, 0}, n(), n(), n());   // x = Schwerkraft-Achse: 18,7 ≥ 0,95 × 19,6
        v.ruhig(2_000);
        assertTrue(v.out.toString(), v.out.isEmpty());
    }

    /** Jede Verwerfung kommt mit den Werten beider Chips ins Log. */
    @Test
    public void verwerfungMitWertenBeiderChips() {
        Sim s = gefahren(true);
        s.schritt(new double[] {0, 0, 30}, n(), n(), n());
        s.ruhig(2_000);
        assertEquals(1, s.e.abweisungen());
        String a = s.e.letzteAbweisung();
        assertTrue(a, a.matches("stoss_einzeln: zweiter Chip ruhig \\(haupt (29|30)\\.\\d\\d m/s², "
                + "zweit 0\\.\\d\\d m/s², noetig (11|12)\\.\\d\\d m/s²\\)"));
        s.kmh = 60;
        for (int i = 0; i < 15; i++) s.schritt(n(), n(), new double[] {1.3, 0, 0}, n());
        s.ruhig(2_000);
        assertEquals(2, s.e.abweisungen());
        assertTrue(s.e.letzteAbweisung(), s.e.letzteAbweisung().contains("rad/s"));
    }

    @Test
    public void waechterFuenfGImmerUndVerwerfungMitWerten() {
        ZweiSensoren z = new ZweiSensoren();
        long[] uhr = new long[1];
        ShockDetector d = waechter(z, uhr);
        long t = uhr[0];
        for (int i = 0; i < 25; i++) z.beschl2(t + i * SCHRITT, 0.0, 9.8, 0);   // zweiter Chip ruhig
        boolean ausgeloest = false;
        for (int i = 0; i < 25; i++, t += SCHRITT) {
            double a = i == 5 || i == 6 ? 5 * 9.80665 : 0.0;
            ausgeloest |= d.feed(t, 9.74, -0.62 + a, 0.01);
        }
        assertTrue("5 g im Stand: immer", ausgeloest);
        assertEquals(0, d.abgewiesen());
    }

    // ---------------------------------------------------------------- nur ein Sensor

    @Test
    public void nurEinSensorWieBisher() {
        Sim s = gefahren(false);
        assertFalse(s.z.beschlBewaehrt());
        s.schritt(new double[] {0, 0, 30}, n(), n(), n());
        s.ruhig(2_000);
        assertEquals(Collections.singletonList(EreignisErkenner.Grund.STOSS), s.out);
        assertEquals("sensor", s.e.letzteDetails().quelle);

        Sim b = gefahren(false);
        vollbremsung(b, 0.0);
        assertEquals(Collections.singletonList(EreignisErkenner.Grund.VERZOEGERUNG), b.out);
        assertEquals("tempo+sensor", b.e.letzteDetails().quelle);
    }

    @Test
    public void attrappeVerhindertNieEinEreignis() {
        Sim s = new Sim();
        s.attrappe = true;
        s.strasse(15_000);
        s.schritt(new double[] {0, 0, 30}, n(), n(), n());
        s.ruhig(2_000);
        assertEquals(Collections.singletonList(EreignisErkenner.Grund.STOSS), s.out);
    }

    // ---------------------------------------------------------------- Kreisel: Schleudern, Überschlag

    @Test
    public void schleudernAbDreissig() {
        Sim s = gefahren(true);
        s.kmh = 60;
        for (int i = 0; i < 15; i++) s.schritt(n(), n(), new double[] {1.3, 0, 0}, new double[] {0, 1.25, 0});
        s.ruhig(1_000);
        assertEquals(Collections.singletonList(EreignisErkenner.Grund.SCHLEUDERN), s.out);
        assertEquals("sensor+zweit", s.e.letzteDetails().quelle);
    }

    @Test
    public void kurveUndLangsameDrehungNicht() {
        Sim s = gefahren(true);
        s.kmh = 50;
        for (int i = 0; i < 125; i++) s.schritt(n(), n(), new double[] {0.5, 0, 0}, new double[] {0, 0.5, 0});   // 5 s Kurve
        s.kmh = 15;   // Parkplatz: enge Drehung, aber langsam
        for (int i = 0; i < 50; i++) s.schritt(n(), n(), new double[] {1.2, 0, 0}, new double[] {0, 1.2, 0});
        assertTrue(s.out.toString(), s.out.isEmpty());
    }

    @Test
    public void drehungNurAmHauptkreiselIstEinFehler() {
        Sim s = gefahren(true);
        s.kmh = 60;
        for (int i = 0; i < 15; i++) s.schritt(n(), n(), new double[] {1.3, 0, 0}, n());
        s.ruhig(2_000);
        assertTrue(s.out.isEmpty());
        assertTrue(s.e.letzteAbweisung(), s.e.letzteAbweisung().startsWith("schleudern"));
    }

    @Test
    public void ueberschlagUmWaagrechteAchse() {
        Sim s = gefahren(true);
        // Rollen um die Längsachse (beim Hauptchip waagrecht: z), 2,5 rad/s über 0,5 s ≈ 72°
        for (int i = 0; i < 13; i++) s.schritt(n(), n(), new double[] {0, 0, 2.5}, new double[] {0, 0, 2.5});
        s.ruhig(1_000);
        assertEquals(Collections.singletonList(EreignisErkenner.Grund.UEBERSCHLAG), s.out);
        assertTrue(s.e.letzteDetails().werte.get("winkelRad") >= EreignisErkenner.UEBERSCHLAG_RAD);
    }

    @Test
    public void rampeUndBordsteinNicht() {
        Sim s = gefahren(true);
        s.kmh = 10;
        for (int i = 0; i < 38; i++) s.schritt(n(), n(), new double[] {0, 0.15, 0}, new double[] {0.15, 0, 0});   // 9° Rampe
        for (int i = 0; i < 3; i++) s.schritt(n(), n(), new double[] {0, 0, 1.2}, new double[] {0, 0, 1.2});      // Bordstein
        for (int i = 0; i < 3; i++) s.schritt(n(), n(), new double[] {0, 0, -1.2}, new double[] {0, 0, -1.2});
        s.ruhig(2_000);
        assertTrue(s.out.toString(), s.out.isEmpty());
    }

    // ---------------------------------------------------------------- Stand (Wächter): Tür, Anstoßen

    /** Wächter im Stand mit zweitem Chip, vorher gefahren (Bewährung aus der Fahrt). */
    private static ShockDetector waechter(ZweiSensoren z, long[] uhr) {
        // Fahrt: beide Chips rütteln mit → bewährt
        for (long t = 0; t < 15_000; t += SCHRITT) {
            double v = 0.8 * Math.sin(2 * Math.PI * 3 * t / 1000.0);
            z.beschl2(t, 0.6 * v, 9.8, 0);
            z.beschl1(t, 9.74, -0.62 + v, 0.01);
        }
        assertTrue(z.beschlBewaehrt());
        ShockDetector d = new ShockDetector(ShockDetector.Empfindlichkeit.MITTEL).mitZweitSensor(z);
        long t = 20_000;
        d.reset(t);
        for (long e = t + 25_000; t < e; t += SCHRITT) {   // Beruhigungszeit, beide ruhig
            z.beschl2(t, 0.0, 9.8, 0);
            assertFalse(d.feed(t, 9.74, -0.62, 0.01));
        }
        uhr[0] = t;
        return d;
    }

    @Test
    public void tuerSchlagNurAmHauptchipWirdVerworfen() {
        ZweiSensoren z = new ZweiSensoren();
        long[] uhr = new long[1];
        ShockDetector d = waechter(z, uhr);
        long t = uhr[0];
        boolean ausgeloest = false;
        // kurzer harter Schlag (≤ 80 ms) am Hauptchip; der gedämpfte zweite Chip zeigt davon fast nichts.
        // Wie ShockMonitor: erst das ganze 1-s-Bündel des zweiten Chips, dann der Hauptchip.
        for (int i = 0; i < 25; i++) z.beschl2(t + i * SCHRITT, 0.0, 9.8 + (i == 5 ? 0.05 : 0.0), 0);
        for (int i = 0; i < 25; i++, t += SCHRITT) {
            double a = i == 5 || i == 6 ? 1.6 : 0.0;
            ausgeloest |= d.feed(t, 9.74 + a, -0.62, 0.01);
        }
        assertFalse(ausgeloest);
        assertEquals(1, d.abgewiesen());
        double[] w = d.letzteAbweisung();   // Werte beider Chips fürs Log
        assertEquals(1.6, w[0], 0.2);
        assertFalse(Double.isNaN(w[1]));
        assertTrue(w[1] < ZweiSensoren.BESCHL_ANTEIL * w[0]);
    }

    @Test
    public void anstossenSchaukeltBeideUndLoestAus() {
        ZweiSensoren z = new ZweiSensoren();
        long[] uhr = new long[1];
        ShockDetector d = waechter(z, uhr);
        long t = uhr[0];
        boolean ausgeloest = false;
        // Anstoßen: Karosserie schaukelt mit 1,5 Hz, beide Chips sehen es (der zweite gedämpft)
        for (int i = 0; i < 50; i++) {
            double v = 1.2 * Math.sin(2 * Math.PI * 1.5 * (i * SCHRITT) / 1000.0) * Math.exp(-i / 30.0);
            z.beschl2(t + i * SCHRITT, 0.7 * v, 9.8, 0);
        }
        for (int i = 0; i < 50; i++, t += SCHRITT) {
            double v = 1.2 * Math.sin(2 * Math.PI * 1.5 * (i * SCHRITT) / 1000.0) * Math.exp(-i / 30.0);
            ausgeloest |= d.feed(t, 9.74, -0.62 + v, 0.01);
        }
        assertTrue(ausgeloest);
        assertEquals(0, d.abgewiesen());
    }

    @Test
    public void wachterOhneZweitenChipWieBisher() {
        ShockDetector d = new ShockDetector(ShockDetector.Empfindlichkeit.MITTEL);
        long t = 0;
        d.reset(t);
        for (; t < 25_000; t += SCHRITT) assertFalse(d.feed(t, 9.74, -0.62, 0.01));
        boolean ausgeloest = false;
        for (int i = 0; i < 20; i++, t += SCHRITT) ausgeloest |= d.feed(t, 9.74 + (i == 5 || i == 6 ? 1.6 : 0), -0.62, 0.01);
        assertTrue("Tür-Schlag löst ohne zweiten Chip aus wie bisher", ausgeloest);
    }

    // ---------------------------------------------------------------- Auswahl und Übertragung

    @Test
    public void zweiterChipNurVonAnderemBaustein() {
        // Surf: smi230 (Haupt) + icm40607
        assertEquals("icm40607-accel", ImuSource.INSTANCE.zweitName(
                Arrays.asList("icm40607-accel", "smi230-accel-iner"), "smi230-accel-iner"));
        assertEquals("icm40607-gyro", ImuSource.INSTANCE.zweitName(
                Arrays.asList("icm40607-gyro", "smi230-gyro-iner"), "smi230-accel-iner"));
        // Seal: smi130-accel ist die Attrappe desselben Chips → kein zweiter
        assertNull(ImuSource.INSTANCE.zweitName(Arrays.asList("smi130-accel", "smi130-accel-iner"), "smi130-accel-iner"));
        assertNull(ImuSource.INSTANCE.zweitName(Arrays.asList("default", "smi230-accel-iner"), "smi230-accel-iner"));
        assertNull(ImuSource.INSTANCE.zweitName(Collections.singletonList("smi230-accel-iner"), "smi230-accel-iner"));
        assertNull(ImuSource.INSTANCE.zweitName(Arrays.asList("icm40607-accel"), null));
        assertEquals("smi230", ImuSource.INSTANCE.chip("SMI230-gyro-iner"));
    }

    @Test
    public void rahmenTraegtZweitenChipRueckwaertsVertraeglich() {
        List<ImuAccelSample> a = Collections.singletonList(new ImuAccelSample(1L, 9.74f, -0.62f, 0.01f));
        List<ImuGyroSample> g = Collections.singletonList(new ImuGyroSample(1L, 0.1f, 0f, 0f));
        List<ImuAccelSample> a2 = Collections.singletonList(new ImuAccelSample(2L, 0f, 9.8f, 0f));
        List<ImuGyroSample> g2 = Collections.singletonList(new ImuGyroSample(2L, 0f, 0.1f, 0f));
        ImuFrameCodec.Decoded d = ImuFrameCodec.INSTANCE.decode(ImuFrameCodec.INSTANCE.encode(a, g, a2, g2, true).trim());
        assertNotNull(d);
        assertEquals(1, d.getAccel().size());
        assertEquals(1, d.getAccel2().size());
        assertEquals(1, d.getGyro2().size());
        assertTrue(d.getGleicherChip());
        assertEquals(9.8f, d.getAccel2().get(0).getAy(), 1e-4);
        assertTrue(Double.isNaN(d.getBereich1()));
        ImuFrameCodec.Decoded r = ImuFrameCodec.INSTANCE.decode(
                ImuFrameCodec.INSTANCE.encode(a, g, a2, g2, true, 39.2266f).trim());
        assertNotNull(r);
        assertEquals(39.2266, r.getBereich1(), 1e-3);
        // alte App ohne zweiten Chip: leer, kein Kennzeichen
        ImuFrameCodec.Decoded alt = ImuFrameCodec.INSTANCE.decode(ImuFrameCodec.INSTANCE.encode(a, g).trim());
        assertNotNull(alt);
        assertTrue(alt.getAccel2().isEmpty());
        assertTrue(alt.getGyro2().isEmpty());
        assertFalse(alt.getGleicherChip());
    }
}
