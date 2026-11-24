package at.greenchili.surfmate.surveillance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** 0.1.43.21 Teil B: 12-V/SOC-Verlauf im Stand und Warnung bei 30 min unter 12,2 V. */
public class StandVerlauf12vTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final List<Double> gemeldet = new ArrayList<>();
    private StandVerlauf12v.Melder vorher;
    private File verlauf;
    private File marke;

    private static final long MIN = 60_000L;
    private static final long T0 = 1_763_900_000_000L;

    @Before
    public void auf() {
        vorher = StandVerlauf12v.melder;
        StandVerlauf12v.melder = gemeldet::add;
        verlauf = new File(tmp.getRoot(), "verlauf.csv");
        marke = new File(tmp.getRoot(), "marke.txt");
    }

    @After
    public void ab() {
        StandVerlauf12v.melder = vorher;
    }

    private StandVerlauf12v neu() {
        return new StandVerlauf12v(verlauf.getPath(), marke.getPath());
    }

    /** Jede Minute ein frischer Wert. */
    private static void minuten(StandVerlauf12v s, long phase, long ab, int n, double v) {
        for (int i = 0; i < n; i++) {
            long t = ab + i * MIN;
            s.takt(t, phase, v, t - 5_000L, 40);
        }
    }

    @Test
    public void warntNachDreissigMinutenEinmalJeStandphase() {
        StandVerlauf12v s = neu();
        long phase = T0;
        minuten(s, phase, T0, 30, 12.1);           // 0..29 min unter der Grenze
        assertTrue(gemeldet.isEmpty());
        assertTrue(s.takt(T0 + 30 * MIN, phase, 12.0, T0 + 30 * MIN - 5_000L, 40));
        assertEquals(Arrays.asList(12.0), gemeldet);
        minuten(s, phase, T0 + 31 * MIN, 60, 11.9);  // weiter tief: nicht noch einmal
        assertEquals(1, gemeldet.size());

        // Neue Standphase (nach einer Fahrt): wieder möglich
        long phase2 = T0 + 5 * 60 * MIN;
        minuten(s, phase2, phase2, 31, 12.1);
        assertEquals(2, gemeldet.size());
    }

    @Test
    public void wertUeberDerGrenzeSetztDieDauerZurueck() {
        StandVerlauf12v s = neu();
        minuten(s, T0, T0, 20, 12.1);
        s.takt(T0 + 20 * MIN, T0, 12.6, T0 + 20 * MIN - 1_000L, 40);  // Nachladen
        minuten(s, T0, T0 + 21 * MIN, 29, 12.1);
        assertTrue(gemeldet.isEmpty());
        minuten(s, T0, T0 + 50 * MIN, 2, 12.1);
        assertEquals(1, gemeldet.size());
    }

    @Test
    public void veralteteOderFehlendeWerteZaehlenNicht() {
        StandVerlauf12v s = neu();
        for (int i = 0; i <= 40; i++) {
            long t = T0 + i * MIN;
            s.takt(t, T0, 11.5, t - 10 * MIN, 40);      // zu alt
            s.takt(t, T0, Double.NaN, 0L, 40);           // keiner
            s.takt(t, T0, 0.0, t, 40);                   // Platzhalter
        }
        assertTrue(gemeldet.isEmpty());
        assertFalse(StandVerlauf12v.frisch(12.0, T0, T0 + StandVerlauf12v.FRISCH_MS + 1));
        assertTrue(StandVerlauf12v.frisch(12.0, T0, T0 + StandVerlauf12v.FRISCH_MS));
    }

    @Test
    public void daemonNeustartMeldetNichtErneut() {
        minuten(neu(), T0, T0, 31, 12.0);
        assertEquals(1, gemeldet.size());
        minuten(neu(), T0, T0 + 40 * MIN, 31, 12.0);   // gleiche Standphase, neuer Prozess
        assertEquals(1, gemeldet.size());
    }

    @Test
    public void verlaufAlleZehnMinutenUndVierzehnTage() throws Exception {
        StandVerlauf12v s = neu();
        minuten(s, T0, T0, 25, 12.8);
        List<String> z = Files.readAllLines(verlauf.toPath(), StandardCharsets.UTF_8);
        assertEquals(3, z.size());                       // 0, 10, 20 min
        assertEquals(T0 + ";12.80;40.0", z.get(0));
        assertEquals("5;;", StandVerlauf12v.zeile(5, Double.NaN, Double.NaN));
        assertEquals("5;12.25;", StandVerlauf12v.zeile(5, 12.25, 0));

        List<String> alt = new ArrayList<>(Arrays.asList(
                (T0 - StandVerlauf12v.BEHALTEN_MS - 1) + ";12.5;40",
                (T0 - 1000) + ";12.6;40",
                "kaputt",
                (T0 + 5000) + ";12.7;40"));               // Zukunft
        assertEquals(Arrays.asList((T0 - 1000) + ";12.6;40"), StandVerlauf12v.beschneiden(alt, T0));
        // Ringpuffer-Größe: 14 Tage zu je 10 min
        assertEquals(14 * 24 * 6, StandVerlauf12v.BEHALTEN_MS / StandVerlauf12v.TAKT_MS);
    }

    @Test
    public void telegramTextInFuenfSprachenUndVerdrahtet() throws Exception {
        for (String sp : new String[] {"de", "en", "fr", "it", "nl"}) {
            String roh = at.greenchili.surfmate.launcher.Daemon014321Test.read("app/src/main/assets/server-i18n/" + sp + ".json");
            String t = new JSONObject(roh).getJSONObject("telegram").getJSONObject("alerts").getString("battery12v_dauer");
            assertTrue(sp, t.contains("{0}"));
            assertTrue(sp, t.contains("12,2") || t.contains("12.2"));
            assertTrue(sp, t.contains("30"));
        }
        String bot = at.greenchili.surfmate.launcher.Daemon014321Test.read(
                "app/src/main/java/at/greenchili/surfmate/daemon/TelegramBotDaemon.java");
        assertTrue(bot.contains("\"dauer\".equals(bStufe) ? \"alerts.battery12v_dauer\""));
        String gs = at.greenchili.surfmate.launcher.Daemon014321Test.read(
                "app/src/main/java/at/greenchili/surfmate/surveillance/GuardStopMonitor.java");
        assertTrue(gs.contains("StandVerlauf12v.instanz().takt(now, parkedSinceMs,"));
    }
}
