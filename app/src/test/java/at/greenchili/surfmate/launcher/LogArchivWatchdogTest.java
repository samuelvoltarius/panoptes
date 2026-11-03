package at.greenchili.surfmate.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.logging.LogArchivShellTest;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 0.1.36 (03.11.: „logs dürfen bitte auch bei updates nicht überschrieben werden“): Kein Wächter-Skript und
 * kein Startweg überschreibt oder kürzt ein Daemon-Log mehr, ohne es vorher ins Archiv zu legen.
 */
public class LogArchivWatchdogTest {

    static String cam() {
        return String.join("\n", DaemonLauncher.Companion.buildCamDaemonWatchdogScript(
                "/data/app/base.apk", "/data/app/lib", "/sdcard/Android/data/at.greenchili.surfmate/files", ""));
    }

    static String acc() {
        return String.join("\n", DaemonLauncher.Companion.buildAccSentryWatchdogScript("/data/app/base.apk", ""));
    }

    static String telegram() {
        return String.join("\n", DaemonLauncher.Companion.buildTelegramWatchdogScript("/data/app/base.apk", ""));
    }

    /** Schreibende Umleitung mit genau einem {@code >} auf das Daemon-Log (Überschreiben). */
    static final Pattern UEBERSCHREIBT = Pattern.compile("(^|[^>0-9&])>\\s*\\\\?\"?\\$\\{?LOG_FILE");

    @Test
    public void keinWaechterUeberschreibtOderKuerztOhneArchiv() {
        for (String s : new String[] {cam(), acc(), telegram()}) {
            Matcher m = UEBERSCHREIBT.matcher(s);
            while (m.find()) {
                // einzige erlaubte Stelle: log_kopieren_kuerzen (kopiert vorher ins Archiv)
                int fn = s.lastIndexOf("log_kopieren_kuerzen() {", m.start());
                int ende = fn < 0 ? -1 : s.indexOf("\n}", fn);
                assertTrue("überschreibt Log: " + s.substring(Math.max(0, m.start() - 40), m.end() + 10),
                        fn >= 0 && m.start() < ende);
            }
            assertFalse(s.contains("Log truncated in place"));
            assertTrue(s.contains("LOGARCHIV=\"/data/user_de/0/com.android.shell/surfmate/logarchiv\""));
        }
    }

    @Test
    public void archivNachLockVorStartUndJeLauf() {
        for (String s : new String[] {cam(), acc(), telegram()}) {
            int lock = s.indexOf("if ! acquire_watchdog_lock; then");
            int ersterArchiv = s.indexOf("log_archivieren \"$LOG_FILE\"");
            int schleife = s.indexOf("while true; do", lock);
            int start = s.indexOf("app_process", schleife);
            assertTrue(lock > 0 && ersterArchiv > lock && ersterArchiv < schleife);
            // in der Schleife: nach einem Lauf archivieren, vor dem nächsten Start
            int imLoop = s.indexOf("if [ \"$LA_GELAUFEN\" = \"1\" ]; then", schleife);
            assertTrue(imLoop > schleife && imLoop < start);
            int warten = s.indexOf("wait $DAEMON_PID", start);
            assertTrue(s.indexOf("LA_GELAUFEN=1", warten) > warten);
            // laufend: kopieren + kürzen statt nur kürzen
            assertTrue(s.contains("log_kopieren_kuerzen \"$LOG_FILE\""));
        }
        assertTrue(acc().contains("echo \"=== WATCHDOG STARTED ===\" >> $LOG_FILE"));
    }

    @Test
    public void startwegeOhneWaechterHaengenAn() throws Exception {
        String befehl = DaemonLauncher.nohupMitArchiv("CLASSPATH=/x app_process /system/bin X",
                "/data/user_de/0/com.android.shell/surfmate/sentry_daemon.log");
        assertTrue(befehl.contains("log_archivieren \"/data/user_de/0/com.android.shell/surfmate/sentry_daemon.log\""));
        assertTrue(befehl.endsWith("nohup sh -c 'CLASSPATH=/x app_process /system/bin X' >> "
                + "/data/user_de/0/com.android.shell/surfmate/sentry_daemon.log 2>&1 &"));
        String quelle = new String(Files.readAllBytes(quelle(
                "app/src/main/java/at/greenchili/surfmate/launcher/DaemonLauncher.kt")), StandardCharsets.UTF_8);
        for (String log : new String[] {"CAMERA_DAEMON_LOG", "ACC_SENTRY_DAEMON_LOG", "SENTRY_DAEMON_LOG "}) {
            assertFalse(log, quelle.contains("' > $" + log.trim() + " 2>&1"));
        }
        String telegram = new String(Files.readAllBytes(quelle(
                "app/src/main/java/at/greenchili/surfmate/daemon/telegram/DaemonCommandHandler.java")),
                StandardCharsets.UTF_8);
        assertTrue(telegram.contains("LogArchiv.archivBefehl(logPfad)"));
    }

    @Test
    public void waechterSkripteSindGueltigesShell() throws Exception {
        String sh = LogArchivShellTest.shell();
        org.junit.Assume.assumeTrue(sh != null);
        for (List<String> zeilen : List.of(
                DaemonLauncher.Companion.buildCamDaemonWatchdogScript("/a/base.apk", "/a/lib", "/a/out", ""),
                DaemonLauncher.Companion.buildAccSentryWatchdogScript("/a/base.apk", ""),
                DaemonLauncher.Companion.buildTelegramWatchdogScript("/a/base.apk", ""))) {
            Path f = Files.createTempFile("waechter", ".sh");
            try {
                Files.write(f, (String.join("\n", zeilen) + "\n").getBytes(StandardCharsets.UTF_8));
                Process p = new ProcessBuilder(sh, "-n", f.toAbsolutePath().toString().replace('\\', '/'))
                        .redirectErrorStream(true).start();
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertEquals(out, 0, p.waitFor());
            } finally {
                Files.deleteIfExists(f);
            }
        }
    }

    static Path quelle(String rel) {
        Path p = Paths.get(rel);
        if (Files.exists(p)) return p;
        return Paths.get("..").resolve(rel);
    }

    @Test
    public void schreibwegDurchPrintfBleibtByteGleich() throws Exception {
        String sh = LogArchivShellTest.shell();
        org.junit.Assume.assumeTrue(sh != null);
        File dir = Files.createTempDirectory("waechter-schreiben").toFile();
        try {
            String ziel = LogArchivShellTest.pfad(new File(dir, "start_cam_daemon.sh"));
            List<String> zeilen = DaemonLauncher.Companion.buildCamDaemonWatchdogScript("/a/base.apk", "/a/lib", "/a/out", "");
            LogArchivShellTest.fuehreAus(DaemonLauncher.Companion.buildWatchdogScriptWriteCommand(ziel, zeilen));
            String geschrieben = new String(Files.readAllBytes(new File(dir, "start_cam_daemon.sh").toPath()),
                    StandardCharsets.UTF_8);
            assertEquals(String.join("\n", zeilen) + "\n", geschrieben);
        } finally {
            for (File f : dir.listFiles()) f.delete();
            dir.delete();
        }
    }
}
