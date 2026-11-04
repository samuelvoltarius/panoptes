package at.greenchili.surfmate.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.testutil.Quelltext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

/** Sicherheits- und Einbindungsvertrag der lokalen schwebenden Kameraansicht. */
public class CameraPipAssetTest {

    @Test
    public void onlyLocalSettingsPagesLoadPip() throws Exception {
        Path local = locate("app/src/main/assets/web/local");
        List<String> pages = new ArrayList<>();
        try (java.nio.file.DirectoryStream<Path> files = Files.newDirectoryStream(local, "*.html")) {
            for (Path page : files) {
                if (read(page).contains("../shared/pip.js")) {
                    pages.add(page.getFileName().toString());
                }
            }
        }
        Collections.sort(pages);
        // 0.1.38.5: nur noch im Wächter („Es soll in den Wächter rein, sonst nirgends.“)
        assertEquals(java.util.Arrays.asList("surveillance.html"), pages);
        for (String forbidden : new String[] {
                "index.html", "login.html", "testfunktionen.html",
                "remote-dev-view.html", "vehicle-control-3d-test.html"}) {
            assertFalse(forbidden, read(local.resolve(forbidden)).contains("pip.js"));
        }
    }

    @Test
    public void hostCameraAndParkedGatesAreFailClosed() throws Exception {
        String pip = read(locate("app/src/main/assets/web/shared/pip.js"));
        assertFalse("nicht mehr auf der Einstellungsseite", pip.contains("'system.html': true"));
        assertTrue(pip.contains("'surveillance.html': true"));
        assertTrue(pip.contains("root.getAttribute('data-einbettung') === 'handy'"));
        assertTrue(pip.contains("root.hasAttribute('data-android-app')"));
        assertTrue(pip.contains("/PanoptesDisplay/.test"));
        assertTrue(pip.contains("caps.kameras.status === 'ja'"));
        assertTrue(pip.contains("document.documentElement.getAttribute('data-fahrt') === '1'"));
        assertTrue(pip.contains("gear === 'P' || acc === false"));
        assertTrue(pip.contains("if (!allowed && (this.isVisible || this.streamStarted))"));
        assertTrue(pip.contains("document.hidden && this.streamStarted"));
    }

    @Test
    public void currentStreamContractIsCheckedAndReleased() throws Exception {
        String pip = read(locate("app/src/main/assets/web/shared/pip.js"));
        assertTrue(pip.contains("this.json('/api/stream/view/' + viewMode, { method: 'POST' })"));
        assertTrue(pip.contains("this.json('/api/stream/enable', { method: 'POST' })"));
        assertTrue(pip.contains("this.json('/api/stream/quality')"));
        assertTrue(pip.contains("this.json('/api/stream/disable', { method: 'POST', keepalive: true })"));
        assertTrue(pip.contains("opts.credentials = 'same-origin'"));
        assertTrue(pip.contains("if (!response.ok) throw new Error"));
        assertTrue(pip.contains("if (enabled.success === false) throw new Error"));
        assertTrue(pip.contains("loadDecoderDependencies"));
        assertTrue(pip.contains("../shared/vendor/jmuxer-2.0.4.min.js"));
    }

    @Test
    public void safetyMessagesExistInEveryWebLanguage() throws Exception {
        for (String lang : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject root = new JSONObject(read(locate(
                    "app/src/main/assets/web/i18n/" + lang + ".json")));
            JSONObject pip = root.getJSONObject("pip");
            assertFalse(lang, pip.getString("parked_only").trim().isEmpty());
            assertFalse(lang, pip.getString("unavailable").trim().isEmpty());
        }
    }

    private static String read(Path path) throws Exception {
        return Quelltext.lies(path);
    }

    private static Path locate(String relative) throws Exception {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            for (Path candidate : new Path[] {
                    current.resolve(relative), current.resolve(relative.replaceFirst("^app/", ""))}) {
                if (Files.exists(candidate)) return candidate;
            }
            current = current.getParent();
        }
        throw new AssertionError("Not found: " + relative);
    }
}
