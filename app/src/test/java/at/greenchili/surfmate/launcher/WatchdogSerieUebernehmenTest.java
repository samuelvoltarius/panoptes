package at.greenchili.surfmate.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Assume;
import org.junit.Test;

/**
 * 0.1.43.5 N2/H1: Der Absturzzähler des ACC-Wächters überlebt einen neuen Wächter (Datei neben der Sperre), und
 * der Kamera-Wächter beendet sich, wenn pm path nach Wiederholungen leer bleibt.
 */
public class WatchdogSerieUebernehmenTest {

    private static String acc() {
        return String.join("\n", DaemonLauncher.Companion.buildAccSentryWatchdogScript("/data/app/base.apk", ""));
    }

    private static String cam() {
        return String.join("\n", DaemonLauncher.Companion.buildCamDaemonWatchdogScript(
                "/data/app/base.apk", "/data/app/lib", "/sdcard/out", ""));
    }

    private static File sh() {
        File sh = new File("C:/Program Files/Git/usr/bin/sh.exe");
        if (!sh.isFile()) sh = new File("/bin/sh");
        return sh;
    }

    @Test
    public void zaehlerWirdGemerktUebernommenUndZurueckgesetzt() {
        String s = acc();
        assertTrue(s.contains("CRASH_DATEI=\"/data/user_de/0/com.android.shell/surfmate/acc_sentry_watchdog.lock.crash\""));
        int lesen = s.indexOf("read CS_SERIE CS_ZEIT < \"$CRASH_DATEI\"");
        int schleife = s.indexOf("while true; do");
        assertTrue("Zähler wird vor der Hauptschleife gelesen", lesen > 0 && lesen < schleife);
        assertTrue(s.contains("-lt 600"));
        assertTrue(s.contains("echo \"$CRASH_SERIE $(date +%s)\" > \"$CRASH_DATEI\""));
        assertTrue(s.contains("rm -f \"$CRASH_DATEI\""));
        // Sperre/Besitz und PARKED unveraendert
        assertTrue(s.contains("backing off 5 min"));
    }

    @Test
    public void kameraWaechterBeendetSichOhneApp() {
        String s = cam();
        int exit = s.indexOf("App nicht mehr installiert");
        assertTrue(exit > 0);
        assertTrue(s.contains("APK_FEHLT=0"));
        assertTrue(s.contains("[ $APK_FEHLT -ge 6 ]"));
        assertTrue(s.substring(exit, exit + 200).contains("exit 0"));
        assertTrue("Wiederholung bleibt davor", s.indexOf("retrying in 10s") > exit);
    }

    @Test
    public void beideSkripteSindGueltigeShell() throws Exception {
        File sh = sh();
        Assume.assumeTrue(sh.isFile());
        for (String skript : new String[] {acc(), cam()}) {
            File t = File.createTempFile("wdserie", ".sh");
            try {
                Files.write(t.toPath(), skript.replace("\r", "").getBytes(StandardCharsets.UTF_8));
                Process p = new ProcessBuilder(sh.getPath(), "-n", t.getPath()).redirectErrorStream(true).start();
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertEquals(out, 0, p.waitFor());
            } finally {
                t.delete();
            }
        }
    }

    /** Der Lese-Block des Zählers, in einer echten Shell ausgeführt: frische Datei wird übernommen, alte/kaputte nicht. */
    @Test
    public void leseBlockUebernimmtNurFrischeDatei() throws Exception {
        File sh = sh();
        Assume.assumeTrue(sh.isFile());
        String s = acc();
        int a = s.indexOf("if [ -f \"$CRASH_DATEI\" ]; then");
        int b = s.indexOf("\nfi\n", a) + 4;
        assertTrue(a > 0 && b > a);
        String block = s.substring(a, b);
        long jetzt = System.currentTimeMillis() / 1000L;
        assertEquals("4", lauf(sh, block, "4 " + jetzt));
        assertEquals("0", lauf(sh, block, "4 " + (jetzt - 700)));
        assertEquals("0", lauf(sh, block, "kaputt"));
        assertEquals("0", lauf(sh, block, null));
    }

    private static String lauf(File sh, String block, String inhalt) throws Exception {
        File d = File.createTempFile("wdcrash", ".txt");
        File t = File.createTempFile("wdlese", ".sh");
        try {
            if (inhalt == null) d.delete(); else Files.write(d.toPath(), (inhalt + "\n").getBytes(StandardCharsets.UTF_8));
            String pfad = d.getPath().replace('\\', '/');
            String skript = "CRASH_SERIE=0\nCRASH_DATEI='" + pfad + "'\n" + block + "\necho $CRASH_SERIE\n";
            Files.write(t.toPath(), skript.getBytes(StandardCharsets.UTF_8));
            Process p = new ProcessBuilder(sh.getPath(), t.getPath().replace('\\', '/')).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor();
            return out;
        } finally {
            d.delete();
            t.delete();
        }
    }
}
