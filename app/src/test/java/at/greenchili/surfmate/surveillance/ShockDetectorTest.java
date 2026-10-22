package at.greenchili.surfmate.surveillance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Erschütterung im Parkmodus. Ruhedaten: am Surf aufgezeichnet (nur gelesen, dumpsys sensorservice,
 * icm40607, 21.10.2026). Stöße: nachgebildet als gedämpfte Schwingung über diesem echten Rauschen
 * (eine echte Stoß-Aufzeichnung gibt es noch nicht – nie am Auto ausgelöst).
 */
public class ShockDetectorTest {

    private static final class S {
        final long t; final double x, y, z;
        S(long t, double x, double y, double z) { this.t = t; this.x = x; this.y = y; this.z = z; }
    }

    /** Echte Ruhemessungen (relative Zeit in ms ab 0). */
    private static List<S> ruhe() {
        InputStream in = ShockDetectorTest.class.getResourceAsStream("/shock/surf_ruhe_20261021.txt");
        if (in == null) throw new AssertionError("Testdaten fehlen");
        Pattern p = Pattern.compile("ts=([0-9.]+),.*\\)\\s+(-?[0-9.]+),\\s+(-?[0-9.]+),\\s+(-?[0-9.]+)");
        List<S> out = new ArrayList<>();
        double t0 = -1;
        try (Scanner sc = new Scanner(in, StandardCharsets.UTF_8.name())) {
            while (sc.hasNextLine()) {
                Matcher m = p.matcher(sc.nextLine());
                if (!m.find()) continue;
                double ts = Double.parseDouble(m.group(1));
                if (t0 < 0) t0 = ts;
                out.add(new S(Math.round((ts - t0) * 1000), Double.parseDouble(m.group(2)),
                        Double.parseDouble(m.group(3)), Double.parseDouble(m.group(4))));
            }
        }
        return out;
    }

    /** Ruhe über dauerMs, durch wiederholtes Abspielen der echten Aufzeichnung. */
    private static List<S> ruheLang(long startMs, long dauerMs) {
        List<S> r = ruhe();
        long span = r.get(r.size() - 1).t + 66;
        List<S> out = new ArrayList<>();
        for (long off = 0; off < dauerMs; off += span) {
            for (S s : r) {
                long t = startMs + off + s.t;
                if (t - startMs >= dauerMs) break;
                out.add(new S(t, s.x, s.y, s.z));
            }
        }
        return out;
    }

    /** Stoß: gedämpfte Schwingung (1,5 Hz, Abklingzeit 0,4 s) auf der y-Achse ab tStoss. */
    private static List<S> mitStoss(List<S> basis, long tStoss, double amplitude) {
        List<S> out = new ArrayList<>();
        for (S s : basis) {
            double dt = (s.t - tStoss) / 1000.0;
            double add = dt >= 0 && dt < 2.0 ? amplitude * Math.exp(-dt / 0.4) * Math.cos(2 * Math.PI * 1.5 * dt) : 0;
            out.add(new S(s.t, s.x + add * 0.3, s.y + add, s.z + add * 0.2));
        }
        return out;
    }

    private static int zaehle(ShockDetector d, List<S> daten) {
        int n = 0;
        for (S s : daten) if (d.feed(s.t, s.x, s.y, s.z)) n++;
        return n;
    }

    @Test
    public void recordedRestingDataNeverTriggersEvenOnHighOverOneHour() {
        ShockDetector d = new ShockDetector(ShockDetector.Empfindlichkeit.HOCH);
        d.reset(0);
        assertEquals(0, zaehle(d, ruheLang(0, 3_600_000L)));
    }

    @Test
    public void bumpTriggersOnceAfterSettleTime() {
        ShockDetector d = new ShockDetector(ShockDetector.Empfindlichkeit.MITTEL);
        d.reset(0);
        List<S> daten = mitStoss(ruheLang(0, 60_000L), 30_000L, 1.5);
        assertEquals(1, zaehle(d, daten));
        assertTrue(d.letzteSpitze() >= 0.6);
    }

    @Test
    public void bumpDuringSettleTimeIsIgnored() {
        ShockDetector d = new ShockDetector(ShockDetector.Empfindlichkeit.MITTEL);
        d.reset(0);
        assertEquals("eigene Tür beim Abstellen", 0, zaehle(d, mitStoss(ruheLang(0, 15_000L), 5_000L, 3.0)));
    }

    @Test
    public void sensitivityLevels() {
        List<S> leicht = mitStoss(ruheLang(0, 60_000L), 30_000L, 1.0);   // leichtes Anlehnen (Spitze ~1 m/s²)
        ShockDetector hoch = new ShockDetector(ShockDetector.Empfindlichkeit.HOCH);
        hoch.reset(0);
        ShockDetector niedrig = new ShockDetector(ShockDetector.Empfindlichkeit.NIEDRIG);
        niedrig.reset(0);
        assertEquals(1, zaehle(hoch, leicht));
        assertEquals(0, zaehle(niedrig, leicht));
    }

    @Test
    public void cooldownAndHourlyCap() {
        ShockDetector d = new ShockDetector(ShockDetector.Empfindlichkeit.MITTEL);
        d.reset(0);
        List<S> daten = ruheLang(0, 3_600_000L);
        // Stoß alle 20 s über eine Stunde: Ruhegrenze 60 s → höchstens 12 pro Stunde
        for (long t = 30_000L; t < 3_590_000L; t += 20_000L) daten = mitStoss(daten, t, 1.5);
        int n = zaehle(d, daten);
        assertEquals(ShockDetector.MAX_PER_HOUR, n);
        ShockDetector d2 = new ShockDetector(ShockDetector.Empfindlichkeit.MITTEL);
        d2.reset(0);
        List<S> zwei = mitStoss(mitStoss(ruheLang(0, 120_000L), 30_000L, 1.5), 50_000L, 1.5);
        assertEquals("zweiter Stoß innerhalb der Ruhegrenze", 1, zaehle(d2, zwei));
    }

    @Test
    public void slowTiltAndDataGapsDoNotTrigger() {
        ShockDetector d = new ShockDetector(ShockDetector.Empfindlichkeit.HOCH);
        d.reset(0);
        List<S> r = ruheLang(0, 600_000L);
        List<S> kippen = new ArrayList<>();
        for (S s : r) {
            // langsames Einfedern / Wagenheber: 2° über 10 min
            double winkel = Math.toRadians(2.0 * s.t / 600_000.0);
            kippen.add(new S(s.t, s.x + 9.8 * Math.sin(winkel), s.y * Math.cos(winkel), s.z));
        }
        assertEquals(0, zaehle(d, kippen));
        // Lücke von 10 s mit anderer Lage danach (Sensor kurz weg): kein Fehlalarm
        ShockDetector g = new ShockDetector(ShockDetector.Empfindlichkeit.HOCH);
        g.reset(0);
        List<S> l = new ArrayList<>(ruheLang(0, 60_000L));
        for (S s : ruheLang(70_000L, 30_000L)) l.add(new S(s.t, s.x + 0.8, s.y, s.z));
        assertEquals(0, zaehle(g, l));
    }

    @Test
    public void singleSpikeIsNotEnough() {
        ShockDetector d = new ShockDetector(ShockDetector.Empfindlichkeit.HOCH);
        d.reset(0);
        List<S> l = new ArrayList<>();
        for (S s : ruheLang(0, 60_000L)) {
            boolean spike = s.t >= 30_000L && s.t < 30_066L;
            l.add(new S(s.t, s.x, spike ? s.y + 3.0 : s.y, s.z));
        }
        assertEquals(0, zaehle(d, l));
    }

    @Test
    public void sensitivityParsing() {
        assertEquals(ShockDetector.Empfindlichkeit.MITTEL, ShockDetector.Empfindlichkeit.von("mittel"));
        assertEquals(null, ShockDetector.Empfindlichkeit.von("aus"));
        assertFalse(ShockDetector.Empfindlichkeit.HOCH.schwelleMs2 >= ShockDetector.Empfindlichkeit.MITTEL.schwelleMs2);
    }
}
