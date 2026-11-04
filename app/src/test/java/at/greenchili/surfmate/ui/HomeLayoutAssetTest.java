package at.greenchili.surfmate.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.testutil.Quelltext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Vertrag der fünf Startansichten: ein DOM, ein Datenzyklus, alte Auto-WebView. */
public class HomeLayoutAssetTest {

    @Test
    public void fiveProfilesReuseEveryHomeWidgetExactlyOnce() throws Exception {
        String page = read("app/src/main/assets/web/local/index.html");
        String layout = read("app/src/main/assets/web/shared/home-layout.js");
        String markup = page.substring(page.indexOf("<body"));
        assertTrue(layout.contains("['panoptes', 'fahren', 'fahrzeug', 'kompakt', 'cockpit']"));
        assertTrue(layout.contains("function wert() { return lokal() || 'panoptes'; }"));
        for (String widget : new String[] {"hero", "quick", "ai", "location", "charging", "condition", "trips"}) {
            assertEquals(widget, 1, count(markup, "data-home-widget=\"" + widget + "\""));
        }
        assertEquals("one existing Home controller", 1, count(page, "var Home = window.Home"));
        assertFalse("layout helper must not create a second data cycle", layout.contains("/api/vehicle/state"));
        assertFalse(layout.contains("/api/trips"));
        assertFalse(layout.contains("/api/charging"));
    }

    @Test
    public void deviceSpecificPersistenceAndMapResizeAreWired() throws Exception {
        String layout = read("app/src/main/assets/web/shared/home-layout.js");
        assertTrue(layout.contains("var KEY = 'pn.startAnsicht'"));
        assertTrue(layout.contains("var FELD = 'anzeige.startAnsicht'"));
        assertTrue(layout.contains("if (!istAuto())"));
        assertTrue(layout.contains("fetch('/api/einstellungen'"));
        assertTrue(layout.contains("Home.map.resize()"));
        assertTrue(layout.contains("data-home-layout"));
        assertTrue(layout.contains("window.PanoptesHomeLayout"));
    }

    @Test
    public void settingsReuseModeCardsAndTouchTargetsStayLarge() throws Exception {
        // 0.1.38.4: Startansicht liegt unter Apps & Display → Display (Abschnitt „Aussehen“)
        String system = read("app/src/main/assets/web/local/display.html");
        String layout = read("app/src/main/assets/web/shared/home-layout.js");
        String page = read("app/src/main/assets/web/local/index.html");
        String styles = read("app/src/main/assets/web/shared/styles.css");
        assertTrue(system.contains("id=\"startAnsichtWahl\""));
        assertTrue(system.contains("home.waehler(hw, 'geraet')") && system.contains("home.waehler(ha, 'auto')"));
        assertTrue(layout.contains("mode-selector home-layout-selector"));
        assertTrue(layout.contains("mode-card"));
        assertTrue(styles.contains(".mode-card {") && styles.contains("min-height: 88px"));
        assertTrue(page.contains(".vehicle-3d-entry { display: none; min-height: 72px"));
        assertTrue(page.contains("var(--sm-line)"));
        assertTrue(page.contains("var(--on-surface)"));
        assertTrue(page.contains(".home-grid[data-home-layout=\"fahren\"] [data-home-widget],"));
        assertTrue(page.contains(".home-grid[data-home-layout=\"kompakt\"] [data-home-widget] { grid-column: span 12; }"));
        assertTrue(page.contains(".home-grid[data-home-layout=\"cockpit\"] [data-home-widget=\"condition\"] { order: 2; grid-column: span 5; }"));
        assertTrue(page.contains(".home-grid[data-home-layout=\"cockpit\"] .vehicle-3d-entry"));
        assertTrue(page.contains(".c-hero,\n            .home-grid[data-home-layout=\"kompakt\"] .c-hero {\n                grid-template-columns: minmax(0, 1fr)"));
    }

    @Test
    public void helperStaysCompatibleWithChrome58() throws Exception {
        String layout = read("app/src/main/assets/web/shared/home-layout.js");
        assertFalse(layout.contains("=>"));
        assertFalse(layout.matches("(?s).*\\bconst\\b.*"));
        assertFalse(layout.matches("(?s).*\\blet\\b.*"));
        assertFalse(layout.contains("?."));
        assertFalse(layout.contains("`"));
    }

    @Test
    public void vehicleProfileLinksExistingManifestBacked3dWithoutSecondCanvas() throws Exception {
        String page = read("app/src/main/assets/web/local/index.html");
        String control = read("app/src/main/assets/web/shared/vehicle-control.js");
        String shell = read("app/src/main/assets/web/shared/app-shell.js");
        String manifest = read("app/src/main/assets/web/shared/models/manifest.json");
        assertTrue(page.contains("data-home-vehicle-entry"));
        assertTrue(page.contains("href=\"vehicle-control.html\""));
        assertTrue(page.contains("home.vehicle_3d_missing"));
        assertTrue(page.contains("p.modellGewaehlt === false || !p.modelId"));
        assertTrue(page.contains("id=\"hVehicle3dText\" data-i18n=\"home.vehicle_3d_ready\""));
        assertTrue(page.contains("text.setAttribute('data-i18n', key)"));
        assertTrue(page.contains("BYD.i18n.onChange(function () { Home.updateVehicle3dEntry(); })"));
        assertFalse("Home must not add another vehicle WebGL canvas", page.contains("id=\"vehicleCanvas\""));
        assertFalse(page.contains("mountVehicleCanvas"));
        assertTrue(control.contains("this.ModelStore.loadManifest"));
        assertTrue(shell.contains("models/manifest.json"));
        assertTrue(manifest.contains("\"models\""));
        assertTrue(manifest.contains("\"default\""));
    }

    private static int count(String text, String needle) {
        int n = 0, from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) { n++; from += needle.length(); }
        return n;
    }

    private static String read(String relative) throws Exception {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            for (Path p : new Path[] {current.resolve(relative), current.resolve(relative.replaceFirst("^app/", ""))}) {
                if (Files.isRegularFile(p)) return Quelltext.lies(p);
            }
            current = current.getParent();
        }
        throw new AssertionError("Not found: " + relative);
    }
}
