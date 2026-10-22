package at.greenchili.surfmate.ui;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Display-Leistung (0.1.8.1): „leicht“ ist am Display Standard (gemessen: 3D-Karte auf Start 6 Bilder/s,
 * leicht 60 Bilder/s bei Chrome-74-Niveau und 6× CPU-Drosselung) und sofort umschaltbar.
 */
public class DarstellungLeichtTest {

    private static final String WEB = "app/src/main/assets/web/";

    @Test
    public void displayStandardIstLeicht() throws Exception {
        String shell = read(WEB + "shared/app-shell.js");
        assertTrue(shell.contains("function dstWert() { return dstLokal() || (dstDisplay() ? 'leicht' : 'voll'); }"));
        assertTrue(shell.contains("DST_FELD = 'anzeige.darstellung'"));
        assertTrue("ohne backdrop-filter", shell.contains("backdrop-filter:none!important"));
    }

    @Test
    public void karteLeichtNur2d() throws Exception {
        String map = read(WEB + "shared/panoptes-map.js");
        assertTrue(map.contains("return leicht() ? '2d' :"));
        assertTrue("Wechsel ohne Neuladen", map.contains("window.addEventListener('pn-darstellung'"));
    }

    @Test
    public void schalterAufSystem() throws Exception {
        String sys = read(WEB + "local/system.html");
        assertTrue(sys.contains("id=\"anzeigeWahl\""));
        assertTrue(sys.contains("PanoptesDarstellung.waehler(ab)"));
    }

    private static String read(String relativePath) throws Exception {
        return new String(Files.readAllBytes(repositoryPath(relativePath)), StandardCharsets.UTF_8);
    }

    private static Path repositoryPath(String relativePath) {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
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
