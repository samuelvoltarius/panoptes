package at.greenchili.surfmate.server;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

public class ModelAvailabilityContractTest {
    private static String read(String path) throws Exception {
        java.nio.file.Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            java.nio.file.Path candidate = current.resolve(path);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            java.nio.file.Path fromModule = current.resolve(path.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = at.greenchili.surfmate.testutil.RepoWurzel.hoch(current);
        }
        throw new java.nio.file.NoSuchFileException(path);
    }

    @Test
    public void unavailableModelsAreNotDownloadedOrRendered() throws Exception {
        String api = read("app/src/main/java/at/greenchili/surfmate/server/ModelsApiHandler.java");
        String card = read("app/src/main/assets/web/shared/ev-card-3d.js");
        String controls = read("app/src/main/assets/web/shared/vehicle-control.js");

        assertTrue(api.contains("!entry.optBoolean(\"verfuegbar\", true)"));
        assertTrue(card.contains("entry.verfuegbar === false"));
        assertTrue(controls.contains("entry.verfuegbar === false"));
        assertTrue(controls.contains("if (m.verfuegbar === false) continue;"));
    }
}
