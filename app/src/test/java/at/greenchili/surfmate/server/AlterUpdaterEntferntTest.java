package at.greenchili.surfmate.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.testutil.Quelltext;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Koordination 0.1.9: Der alte Overdrive-Updater (/api/update/*, IPC CHECK_UPDATE/INSTALL_UPDATE …, Kanal-Schalter,
 * „Nach Updates suchen“) ist entfernt. Updates nur noch über den GitHub-Updater /api/appupdate/*.
 */
public class AlterUpdaterEntferntTest {

    @Test
    public void endpunkteUndIpcSindWeg() throws Exception {
        assertFalse(Files.exists(Paths.get("src/main/java/at/greenchili/surfmate/server/UpdateApiHandler.java")));
        String http = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/HttpServer.java"));
        assertFalse(http.contains("return UpdateApiHandler.handle("));
        assertTrue(http.contains("\\\"grund\\\":\\\"entfernt\\\""));
        String ipc = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/SurveillanceIpcServer.java"));
        for (String m : new String[] {"handleCheckUpdate(", "handleInstallUpdate(", "handleSetChannel(",
                "handleListVersions(", "handleGetUpdateProgress("}) {
            assertFalse(m, ipc.contains(m));
        }
        assertTrue(ipc.contains("removed: use /api/appupdate"));
    }

    /** Niemand außerhalb des updater-Pakets startet noch den alten Updater (nur Versions-Helfer bleiben). */
    @Test
    public void keinAufrufMehr() throws Exception {
        List<String> treffer = new ArrayList<>();
        try (Stream<Path> s = Files.walk(Paths.get("src/main/java"))) {
            for (Path p : (Iterable<Path>) s::iterator) {
                String n = p.toString().replace(java.io.File.separatorChar, '/');
                if (!(n.endsWith(".java") || n.endsWith(".kt")) || n.contains("/surfmate/updater/")) continue;
                String q = Quelltext.lies(p);
                if (q.contains("new AppUpdater(") || q.contains("updater.AppUpdater(") || q.contains(".checkForUpdate(")
                        || q.contains("installCompanionApk(") || q.contains("invokeCheckForUpdates")) {
                    treffer.add(n);
                }
            }
        }
        assertTrue("alter Updater noch aufgerufen: " + treffer, treffer.isEmpty());
        String layout = Quelltext.lies(Paths.get("src/main/res/layout/fragment_settings_about.xml"));
        assertFalse(layout.contains("@+id/cardCheckUpdate"));
        assertFalse(layout.contains("@+id/toggleUpdateChannel"));
    }

    @Test
    public void kopplungDarfAuchFrueherNicht() {
        for (String m : new String[] {"POST", "PUT"}) {
            assertFalse(ExternGuard.erlaubt(m, "/api/update/install"));
        }
    }
}
