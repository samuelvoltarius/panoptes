package at.greenchili.surfmate.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 0.1.38.9 Schnellstart 2: Kill und Prüfung nur noch für EIGENE Prozesse (kein killall/grep -F auf fremde Daemons mit
 * gleichen Namen), eine ps-Abfrage im Hard-Reset, Erkennung fremder Werkzeug-Apps.
 */
public class EigeneProzesseTest {

    private static Path mainJava() {
        Path p = Paths.get("src/main/java");
        return java.nio.file.Files.exists(p) ? p : Paths.get("app/src/main/java");
    }

    private static String quelle(String rel) throws Exception {
        return new String(Files.readAllBytes(mainJava().resolve(rel)), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    public void beendenPrueftKlassenpfadOderEigenenOrdner() {
        String s = EigeneProzesse.beenden("byd_cam_daemon");
        assertTrue(s.contains("grep -F -e 'byd_cam_daemon'"));
        assertTrue("Klassenpfad aus der Umgebung des Daemons", s.contains("/proc/$pn_pid/environ") && s.contains("^CLASSPATH="));
        assertTrue("Befehlszeile des Watchdog-Skripts", s.contains("/proc/$pn_pid/cmdline"));
        assertTrue(s.contains("*at.greenchili.surfmate*|*com.android.shell/surfmate*) kill -9 $pn_pid"));
        assertTrue("eigene Shell nie beenden", s.contains("[ \"$pn_pid\" != \"$MY_PID\" ]"));
        assertTrue("nicht lesbar: wie bisher beenden (Update darf nie an einem Altprozess hängen)",
                s.contains("[ -r /proc/$pn_pid/environ ] || kill -9 $pn_pid"));
        assertFalse(s.contains("killall"));
        assertFalse("kein Zeilenumbruch, verkettbar", s.contains("\n"));
    }

    @Test
    public void mehrereMusterEineAbfrage() {
        String s = EigeneProzesse.beendenMehrere(Arrays.asList("start_cam_daemon", "cam_daemon", "sentry_daemon"));
        assertEquals("genau ein ps für alle Muster", 1, count(s, "ps -A -o PID,ARGS"));
        assertTrue(s.contains("-e 'start_cam_daemon' -e 'cam_daemon' -e 'sentry_daemon'"));
    }

    @Test
    public void fremdeAuflistenKilltNichts() {
        String s = EigeneProzesse.fremdeAuflisten(Arrays.asList("byd_cam_daemon"));
        assertFalse(s.contains("kill"));
        assertTrue(s.contains("echo \"FREMD $pn_pid"));
        assertTrue("nur nicht eigene, nur lesbare", s.contains("[ -r /proc/$pn_pid/environ ] && echo"));
    }

    @Test
    public void hardResetNutztEineAbfrageUndZweitenDurchgang() throws Exception {
        String u = quelle("at/greenchili/surfmate/updater/UpdateLifecycle.java");
        assertTrue(u.contains("EIGENE_PROZESSE_KILL"));
        assertEquals("Durchgang vor und nach dem Settle", 2, count(u, "EIGENE_PROZESSE_KILL + \"\\n\""));
        assertFalse(u.contains("psAwkKillLine(\"byd_cam_daemon\")"));
        assertFalse(u.contains("psAwkKillLine(\"cam_daemon\")"));
    }

    @Test
    public void keinKillAufFremdeProzessnamenMehrImQuelltext() throws Exception {
        String[] verboten = {"killall -9 byd_cam_daemon", "pkill -9 byd_cam_daemon", "grep -F cam_daemon | grep -v grep",
                "grep -F start_cam_daemon", "grep -F 'cam_daemon'", "killall -9 $CAMERA_DAEMON_PROCESS",
                "killall -9 \" + processName", "pkill -x"};
        List<Path> dateien;
        try (Stream<Path> s = Files.walk(mainJava())) {
            dateien = s.filter(p -> p.toString().endsWith(".kt") || p.toString().endsWith(".java")).collect(Collectors.toList());
        }
        for (Path p : dateien) {
            if (p.getFileName().toString().equals("EigeneProzesse.kt")) continue;   // erwähnt die alten Befehle im Kommentar
            String t = readQuiet(p);
            for (String v : verboten) assertFalse(p + " enthält " + v, t.contains(v));
        }
    }

    @Test
    public void psAwkKillBeendetFuerEigeneMusterNurEigene() throws Exception {
        String d = quelle("at/greenchili/surfmate/launcher/DaemonLauncher.kt");
        assertTrue(d.contains("if (pattern in EIGENE_MUSTER) EigeneProzesse.beenden(pattern) else"));
        for (String m : new String[]{"\"cam_daemon\"", "\"byd_cam_daemon\"", "\"start_cam_daemon\"", "\"sentry_daemon\"",
                "\"acc_sentry_daemon\"", "\"telegram_bot_daemon\""}) {
            assertTrue(m, d.contains(m));
        }
    }

    private static String readQuiet(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private static int count(String s, String t) {
        int n = 0, i = 0;
        while ((i = s.indexOf(t, i)) >= 0) { n++; i += t.length(); }
        return n;
    }
}
