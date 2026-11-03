package at.greenchili.surfmate.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Contract test for the normal centre-screen app-pair picker. */
public class SplitScreenPickerAssetTest {

    @Test
    public void pickerUsesLaunchableAppsAndVerifiedPairEndpoint() throws Exception {
        String page = read("src/main/assets/web/local/apps.html");

        assertTrue(page.contains("/api/apps/list"));
        assertTrue(page.contains("/api/apps/capabilities"));
        assertTrue(page.contains("secondaryPackage: secondary.value"));
        assertTrue(page.contains("split: true"));
        assertTrue(page.contains("primary.value !== secondary.value"));
        assertTrue(page.contains("result.ok && result.body.success"));
        assertTrue(page.contains("data-nur-display"));
        assertTrue(page.contains("../shared/core.js"));
        assertFalse(page.contains("=>"));
        assertFalse(page.contains("async function"));
    }

    @Test
    public void pickerIsRoutedAndVisibleInNormalNavigation() throws Exception {
        String server = read("src/main/java/at/greenchili/surfmate/server/HttpServer.java");
        String api = read("src/main/java/at/greenchili/surfmate/server/AppsApiHandler.java");
        String shell = read("src/main/assets/web/shared/app-shell.js");

        assertTrue(server.contains("path.equals(\"/apps.html\") || path.equals(\"/apps\")"));
        assertTrue(api.contains("cleanPath.equals(\"/api/apps/capabilities\")"));
        assertTrue(api.contains("AppLauncher.isSplitScreenSupported()"));
        assertTrue(api.contains("\"split_screen_not_supported\""));
        assertTrue(shell.contains("href: 'apps.html'"));
        assertTrue(shell.contains("i18n: 'shell.page_apps'"));
    }

    @Test
    public void pickerHasCompleteLabelsInEveryWebLanguage() throws Exception {
        String[] required = {
                "title", "card_title", "lead", "primary", "secondary", "swap",
                "loading", "launch", "note", "choose_different", "ready", "unsupported",
                "too_few", "load_failed", "opening", "opened", "open_failed"
        };
        for (String language : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject root = new JSONObject(read(
                    "src/main/assets/web/i18n/" + language + ".json"));
            assertFalse(language + ":shell.page_apps",
                    root.getJSONObject("shell").getString("page_apps").trim().isEmpty());
            JSONObject apps = root.getJSONObject("apps");
            for (String key : required) {
                assertFalse(language + ":apps." + key,
                        apps.getString(key).trim().isEmpty());
            }
        }
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }
}
