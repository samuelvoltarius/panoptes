package at.greenchili.surfmate.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Contract for the installable phone-web surface served by the car. */
public class PwaInstallAssetTest {

    private static final Set<String> NON_PRODUCT_PAGES = new HashSet<>(Arrays.asList(
            "login.html", "testfunktionen.html", "vehicle-control-3d-test.html",
            // 0.1.37: nur das 3D-Fahrzeug für die Kachel der Launcher-APK, keine Produktseite
            "launcher-fahrzeug.html",
            // Nur fuer das Fahrerdisplay (TachoAnsichtActivity, ohne Netz), keine Seite der Web-App.
            "tacho-ansicht.html"));

    @Test
    public void everyProductPageLoadsTheCentralPwaBootstrap() throws Exception {
        Path local = locate("app/src/main/assets/web/local");
        try (Stream<Path> pages = Files.list(local)) {
            for (Path page : (Iterable<Path>) pages::iterator) {
                String name = page.getFileName().toString();
                if (!name.endsWith(".html") || NON_PRODUCT_PAGES.contains(name)) continue;
                String html = read(page);
                assertTrue(name + " must load the common shell/PWA bootstrap",
                        html.contains("app-shell.js"));
            }
        }

        String shell = read(locate("app/src/main/assets/web/shared/app-shell.js"));
        assertTrue(shell.contains("link[rel=\"manifest\"]"));
        assertTrue(shell.contains("manifest.href = '/manifest.json'"));
        assertTrue(shell.contains("bootstrap.src = '../shared/pwa-init.js?v=index2'"));
    }

    @Test
    public void androidPromptInstalledStateAndFallbackAreWired() throws Exception {
        String init = read(locate("app/src/main/assets/web/shared/pwa-init.js"));
        String home = read(locate("app/src/main/assets/web/local/index.html"));

        assertTrue(init.contains("beforeinstallprompt"));
        assertTrue(init.contains("event.preventDefault()"));
        assertTrue(init.contains("deferredInstallPrompt"));
        assertTrue(init.contains("prompt.prompt()"));
        assertTrue(init.contains("prompt.userChoice"));
        assertTrue(init.contains("appinstalled"));
        assertTrue(init.contains("(display-mode: standalone)"));
        assertTrue(init.contains("https-required"));
        assertTrue(init.contains("prompt-unavailable"));

        assertTrue(home.contains("id=\"hAppHinweisText\""));
        assertTrue(home.contains("home.pwa_android_text"));
        assertTrue(home.contains("home.pwa_install"));
        assertTrue(home.contains("home.pwa_fallback"));
        assertTrue(home.contains("home.pwa_https"));
        assertTrue(home.contains("zustand.platform === 'ios'"));
        assertTrue(home.contains("zustand.platform === 'android'"));
        assertTrue(home.contains("zustand.installed"));
    }

    @Test
    public void manifestAndAllInstallTextsAreComplete() throws Exception {
        JSONObject manifest = new JSONObject(read(locate(
                "app/src/main/assets/web/local/manifest.json")));
        assertEquals("/", manifest.getString("start_url"));
        assertEquals("/", manifest.getString("scope"));
        assertEquals("standalone", manifest.getString("display"));
        JSONArray icons = manifest.getJSONArray("icons");
        assertTrue(icons.length() >= 3);
        assertTrue(icons.toString().contains("192x192"));
        assertTrue(icons.toString().contains("512x512"));
        assertTrue(icons.toString().contains("maskable"));

        for (String lang : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject home = new JSONObject(read(locate(
                    "app/src/main/assets/web/i18n/" + lang + ".json")))
                    .getJSONObject("home");
            for (String key : new String[] {
                    "pwa_title", "pwa_text", "pwa_android_text", "pwa_install",
                    "pwa_installing", "pwa_fallback", "pwa_https", "pwa_ok"
            }) {
                assertFalse(lang + ":" + key, home.getString(key).trim().isEmpty());
            }
        }
    }

    private static Path locate(String relative) throws Exception {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            for (Path candidate : new Path[] {
                    current.resolve(relative), current.resolve(relative.replaceFirst("^app/", ""))
            }) {
                if (Files.exists(candidate)) return candidate;
            }
            current = at.greenchili.surfmate.testutil.RepoWurzel.hoch(current);
        }
        throw new java.io.FileNotFoundException(relative);
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
