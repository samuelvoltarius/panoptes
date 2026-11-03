package at.greenchili.surfmate.updater;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.logging.LogArchiv;
import at.greenchili.surfmate.logging.LogArchivShellTest;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 0.1.36: Das Update löschte bisher alle Daemon-Logs (Auto-Builds haben LOG_CAPTURE=false). Jetzt landen dieselben
 * Dateien im Log-Archiv, das Installations-Log bleibt, wo es ist.
 */
public class UpdateLogArchivTest {

    @Test
    public void updateLoeschtKeineLogsMehr() throws Exception {
        String s = UpdateLifecycle.LOG_ARCHIV_SKRIPT;
        assertTrue(s.contains("log_archivieren \"$lf\""));
        assertFalse(s.contains("rm -f \"$lf\""));
        assertTrue(s.contains("LOGARCHIV=\"/data/user_de/0/com.android.shell/surfmate/logarchiv\""));
        Path quelle = Paths.get("app/src/main/java/at/greenchili/surfmate/updater/UpdateLifecycle.java");
        if (!Files.exists(quelle)) quelle = Paths.get("..").resolve(quelle);
        String code = new String(Files.readAllBytes(quelle), StandardCharsets.UTF_8);
        assertFalse("kein Löschen der Logs nach dem Update", code.contains("rm -f \\\"$lf\\\""));
        assertTrue(code.contains("LOG_ARCHIV_SKRIPT +"));
    }

    @Test
    public void updateSkriptVerschiebtLogsInsArchiv() throws Exception {
        org.junit.Assume.assumeTrue(LogArchivShellTest.shell() != null);
        File dir = Files.createTempDirectory("update-logs").toFile();
        File archiv = new File(dir, LogArchiv.ORDNER);
        try {
            Files.write(new File(dir, "cam_daemon.log").toPath(), "vor dem update\n".getBytes(StandardCharsets.UTF_8));
            Files.write(new File(dir, "cameradaemon.log.1").toPath(), "rotiert\n".getBytes(StandardCharsets.UTF_8));
            Files.write(new File(dir, "surfmate_install.log").toPath(), "install\n".getBytes(StandardCharsets.UTF_8));
            LogArchivShellTest.fuehreAus(UpdateLifecycle.logArchivSkript(LogArchivShellTest.pfad(dir)));
            assertFalse(new File(dir, "cam_daemon.log").exists());
            assertFalse(new File(dir, "cameradaemon.log.1").exists());
            assertTrue("Installations-Log bleibt", new File(dir, "surfmate_install.log").exists());
            String[] namen = archiv.list((d, n) -> n.endsWith(".log"));
            assertEquals(2, namen.length);
            StringBuilder inhalt = new StringBuilder();
            for (String n : namen) {
                inhalt.append(new String(Files.readAllBytes(new File(archiv, n).toPath()), StandardCharsets.UTF_8));
            }
            assertTrue(inhalt.toString().contains("vor dem update"));
            assertTrue(inhalt.toString().contains("rotiert"));
        } finally {
            File[] l = archiv.listFiles();
            if (l != null) for (File f : l) f.delete();
            archiv.delete();
            for (File f : dir.listFiles()) f.delete();
            dir.delete();
        }
    }
}
