package at.greenchili.surfmate.gesundheit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/** 0.1.38.1: Entscheidung der Selbstprüfung und exakte Erkennung des Watchdogs über /proc. */
public class WatchdogSelbstpruefungTest {

    @Test
    public void erstNachZweiTaktenHeilen() {
        assertEquals(WatchdogSelbstpruefung.Aktion.NICHTS, WatchdogSelbstpruefung.entscheide(false, true, 5));
        assertEquals(WatchdogSelbstpruefung.Aktion.NICHTS, WatchdogSelbstpruefung.entscheide(true, false, 5));
        assertEquals(WatchdogSelbstpruefung.Aktion.WARTEN, WatchdogSelbstpruefung.entscheide(false, false, 0));
        assertEquals(WatchdogSelbstpruefung.Aktion.HEILEN, WatchdogSelbstpruefung.entscheide(false, false, 1));
        assertEquals(WatchdogSelbstpruefung.Aktion.HEILEN, WatchdogSelbstpruefung.entscheide(false, false, 4));
    }

    /** Erste Prüfung nach 30 s, Takt 30 s, zweiter Verdacht heilt: geheilt spätestens nach 2 min (PFLICHT 4). */
    @Test
    public void heilungInnerhalbVonZweiMinuten() {
        long spaetestens = WatchdogSelbstpruefung.TAKT_S * WatchdogSelbstpruefung.VERDACHT_BIS_HEILEN
                + WatchdogSelbstpruefung.TAKT_S;
        assertTrue(spaetestens <= 120);
        assertTrue(WatchdogSelbstpruefung.ERSTE_PRUEFUNG_S <= 30);
    }

    @Test
    public void meldungEinmalNachDreiFehlversuchen() {
        long t = 1_000_000_000L;
        assertFalse(WatchdogSelbstpruefung.meldenNoetig(2, 0, t));
        assertTrue(WatchdogSelbstpruefung.meldenNoetig(3, 0, t));
        assertFalse("höchstens einmal am Tag", WatchdogSelbstpruefung.meldenNoetig(9, t - 3_600_000L, t));
        assertTrue(WatchdogSelbstpruefung.meldenNoetig(3, t - WatchdogSelbstpruefung.MELDE_ABSTAND_MS, t));
    }

    @Test
    public void meldungOhneFachwort() {
        String[] m = WatchdogSelbstpruefung.meldung("kamera");
        String alles = (m[0] + " " + m[1]).toLowerCase();
        for (String fach : new String[] {"watchdog", "daemon", "skript", "script", "adb", "prozess", "pid"}) {
            assertFalse(alles + " enthält " + fach, alles.contains(fach));
        }
    }

    private static void prozess(File proc, int pid, String... argv) throws Exception {
        File d = new File(proc, Integer.toString(pid));
        d.mkdirs();
        Files.write(new File(d, "cmdline").toPath(), (String.join("\0", argv) + "\0").getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void watchdogNurBeiExakterArgv() throws Exception {
        File proc = Files.createTempDirectory("proc").toFile();
        String s = "/data/user_de/0/com.android.shell/surfmate/start_cam_daemon.sh";
        prozess(proc, 10, "sh", s);
        prozess(proc, 11, "/system/bin/sh", s, "anhaengen", "4711");
        prozess(proc, 12, "sh", "-c", "printf x > " + s + ".neu.12");     // Schreiber, kein Watchdog
        prozess(proc, 13, "sh", "/data/user_de/0/com.android.shell/surfmate/.adb_script_1.sh");
        prozess(proc, 14, "byd_cam_daemon");
        prozess(proc, 15, "sh", s + ".alt");
        new File(proc, "self").mkdirs();
        java.util.List<Integer> w = new java.util.ArrayList<>(WatchdogSelbstpruefung.watchdogPids(proc, s));
        java.util.Collections.sort(w);
        assertEquals(Arrays.asList(10, 11), w);
        assertTrue(WatchdogSelbstpruefung.istWatchdog(proc, 11, s));
        assertFalse(WatchdogSelbstpruefung.istWatchdog(proc, 12, s));
        assertFalse(WatchdogSelbstpruefung.istWatchdog(proc, 99, s));
        assertEquals(Arrays.asList("sh", "-c", "printf x > " + s + ".neu.12"), WatchdogSelbstpruefung.argv(proc, 12));
    }
}
