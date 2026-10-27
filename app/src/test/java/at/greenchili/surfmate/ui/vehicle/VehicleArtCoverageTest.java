package at.greenchili.surfmate.ui.vehicle;

import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Keeps the dashboard and Vehicle Control lite art in sync with the model manifest. */
public class VehicleArtCoverageTest {

    /**
     * 0.1.20 (Koordination: Pressefotos ohne Lizenz raus, Repo öffentlich): Ein eigenes Bild gibt es genau für die
     * Modelle mit CC-BY-3D-Modell im Katalog (Render), alle anderen zeigen die neutrale Silhouette.
     */
    @Test
    public void nurLizenzierteRenderOderSilhouette() throws Exception {
        JSONObject manifest = new JSONObject(new String(
                Files.readAllBytes(repositoryPath(
                        "app/src/main/assets/web/shared/models/manifest.json")),
                StandardCharsets.UTF_8));
        JSONArray models = manifest.getJSONArray("models");
        int fallback = VehicleArt.INSTANCE.drawableFor("__unknown_vehicle__");
        java.util.Set<String> mitRender = new java.util.HashSet<>(java.util.Arrays.asList(
                "seal", "seal-u", "seal-u-dmi", "dolphin", "atto3", "seagull"));
        for (int i = 0; i < models.length(); i++) {
            JSONObject m = models.getJSONObject(i);
            String id = m.getString("id");
            boolean eigenes = VehicleArt.INSTANCE.drawableFor(id) != fallback;
            assertTrue("Bild ohne Render-Grundlage: " + id, !eigenes || mitRender.contains(id));
            if (eigenes && !id.equals("seal-u-dmi")) {
                assertTrue("Render nur aus CC-BY-Modell: " + id, "CC-BY-4.0".equals(m.optString("license")));
                assertTrue("Namensnennung fehlt: " + id, m.optString("attribution").contains("CC BY 4.0"));
            }
        }
        assertTrue("Shark nutzt kein fremdes Bild", VehicleArt.INSTANCE.drawableFor("shark") == fallback);
    }

    @Test
    public void pressefotosSindEntferntUndNamensnennungSteht() throws Exception {
        for (String alt : new String[] {"sealion7", "atto3_evo", "atto2", "han", "tang", "m6", "destroyer05"}) {
            Path p = Paths.get(System.getProperty("user.dir")).resolve("src/main/res/drawable-nodpi/vehicle_" + alt + ".webp");
            assertTrue("noch da: " + alt, !Files.exists(p));
        }
        String notices = new String(Files.readAllBytes(repositoryPath("THIRD_PARTY_NOTICES.md")), StandardCharsets.UTF_8);
        assertTrue(notices.contains("tools/render_vehicle_art.py"));
        assertTrue(notices.contains("Nazh Design"));
    }

    private static Path repositoryPath(String relativePath) {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath()
                .normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) return candidate;

            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) return fromModule;
            current = current.getParent();
        }
        throw new AssertionError("Could not locate repository file: " + relativePath);
    }
}
