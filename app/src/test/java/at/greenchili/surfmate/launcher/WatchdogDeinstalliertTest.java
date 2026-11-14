package at.greenchili.surfmate.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.ui.daemon.DaemonStartPlan;
import at.greenchili.surfmate.ui.daemon.StartStufe;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Assume;
import org.junit.Test;

/**
 * 0.1.43.5 (N1): Der Wächter (start_acc_sentry.sh) beendet sich, wenn die App deinstalliert ist (pm path leer),
 * und zieht sich bei Abstürzen in Serie mit Pause zurück. Notzugang/PARKED bleiben unverändert.
 */
public class WatchdogDeinstalliertTest {

    private static String skript() {
        return String.join("\n", DaemonLauncher.Companion.buildAccSentryWatchdogScript("/data/app/base.apk", ""));
    }

    @Test
    public void leeresPmPathBeendetDenWaechterVorDemDaemonStart() {
        String s = skript();
        int exit = s.indexOf("App nicht mehr installiert");
        int start = s.indexOf("app_process");
        assertTrue(exit > 0 && start > exit);
        String block = s.substring(s.lastIndexOf("RESOLVED_APK=", exit), s.indexOf("\n", exit + 80) + 20);
        assertTrue(block.contains("[ -z \"$RESOLVED_APK\" ]"));
        assertTrue(block.contains("exit 0"));
        // kurzes Aussetzen des Paketdienstes darf den Wächter nicht beenden: erst Wiederholungen
        assertTrue(s.contains("PM_VERSUCHE -lt 3"));
    }

    @Test
    public void absturzSerieZiehtSichMitPauseZurueck() {
        String s = skript();
        assertTrue(s.contains("CRASH_SERIE=0"));
        assertTrue(s.contains("T_START=$(date +%s)"));
        assertTrue(s.contains("[ $T_LAUF -lt 30 ]"));
        assertTrue(s.contains("[ $CRASH_SERIE -ge 3 ]"));
        assertTrue(s.contains("-gt 300"));
        assertTrue("Sentinel bricht die Pause ab", s.contains("[ ! -f \"$SENTINEL\" ]"));
        assertTrue("normaler Neustart bleibt bei 2 s", s.contains("      sleep 2"));
    }

    @Test
    public void parkedUndNotzugangBleibenUnveraendert() {
        String s = skript();
        assertTrue(s.contains("PARKED_RESPAWNS=$((PARKED_RESPAWNS + 1))"));
        assertTrue(s.contains("backing off 5 min"));
        assertTrue(s.contains("Daemon disabled by user (sentinel file exists). Exiting watchdog."));
    }

    @Test
    public void skriptIstGueltigeShell() throws Exception {
        File sh = new File("C:/Program Files/Git/usr/bin/sh.exe");
        if (!sh.isFile()) sh = new File("/bin/sh");
        Assume.assumeTrue(sh.isFile());
        File t = File.createTempFile("wdskript", ".sh");
        try {
            Files.write(t.toPath(), skript().replace("\r", "").getBytes(StandardCharsets.UTF_8));
            Process p = new ProcessBuilder(sh.getPath(), "-n", t.getPath()).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(out, 0, p.waitFor());
        } finally {
            t.delete();
        }
    }

    @Test
    public void telegramFristIstLaenger() {
        assertEquals(60_000L, DaemonStartPlan.beobachtungMs(StartStufe.REST));
    }
}
