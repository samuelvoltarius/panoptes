package at.greenchili.surfmate.updater;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.launcher.DaemonLauncher;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 0.1.36: Das Update löschte bisher alle Daemon-Logs (Auto-Builds haben LOG_CAPTURE=false). Seit 0.1.38.10 verschiebt
 * der Hard-Reset nichts mehr (am Auto 14 s und mehr); Logs gehen trotzdem nie verloren: er löscht keine, und jeder
 * Wächter archiviert sein Log beim eigenen Start.
 */
public class UpdateLogArchivTest {

    private static String quelle() throws Exception {
        Path q = Paths.get("app/src/main/java/at/greenchili/surfmate/updater/UpdateLifecycle.java");
        if (!Files.exists(q)) q = Paths.get("..").resolve(q);
        return new String(Files.readAllBytes(q), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    public void hardResetLoeschtKeineLogsUndArchiviertNichtMehr() throws Exception {
        String code = quelle();
        assertFalse("kein Löschen der Logs nach dem Update", code.contains("rm -f \\\"$lf\\\""));
        assertFalse("kein *.log im Hard-Reset", code.contains("/*.log "));
        assertFalse("kein Archiv-Schritt im Hard-Reset", code.contains("log_archivieren_nur") || code.contains("LOG_ARCHIV_SKRIPT")
                || code.contains("logArchivSkript"));
        assertTrue("stattdessen Zeitmarken je Schritt", code.contains("hrMarke(\"gesamt\")"));
    }

    @Test
    public void jederWaechterArchiviertSeinLogBeimStart() {
        List<String> kamera = DaemonLauncher.Companion.buildCamDaemonWatchdogScript(
                "/data/app/base.apk", "/data/app/lib", "/sdcard/x", "");
        List<String> acc = DaemonLauncher.Companion.buildAccSentryWatchdogScript("/data/app/base.apk", "");
        for (List<String> skript : java.util.Arrays.asList(kamera, acc)) {
            assertTrue(skript.contains("log_archivieren \"$LOG_FILE\"") || skript.contains("    log_archivieren \"$LOG_FILE\""));
        }
        assertTrue(kamera.stream().anyMatch(z -> z.trim().equals("log_archivieren \"$LOG_FILE\"")));
    }
}
