package at.greenchili.surfmate.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.json.JSONObject;
import org.junit.Test;

/** Pins the plain-language, large-touch System navigation used on the car display. */
public class SystemOverviewAccessibilityTest {
    @Test
    public void systemUsesFourLargePlainLanguageAreas() throws Exception {
        String html = read("app/src/main/assets/web/local/system.html");
        String js = read("app/src/main/assets/web/shared/system-overview.js");

        assertTrue(html.contains("class=\"system-groups\""));
        assertTrue(html.contains("min-height: 78px"));
        assertTrue(html.contains("role=\"tablist\""));
        assertTrue(html.contains("aria-live=\"polite\""));
        for (String group : new String[]{"connections", "vehicle", "privacy", "advanced"}) {
            assertTrue(html.contains("data-system-group=\"" + group + "\""));
            assertTrue(js.contains(group + ": ["));
        }
        assertTrue(js.contains("card.hidden = key !== group"));
        assertTrue(js.contains("groupForHash() || storedGroup() || 'connections'"));
        assertFalse(js.contains("?."));
        assertFalse(js.contains("=>"));
    }

    @Test
    public void systemAreaLabelsExistInEveryWebLanguage() throws Exception {
        for (String lang : new String[]{"de", "en", "fr", "it", "nl"}) {
            JSONObject system = new JSONObject(read(
                    "app/src/main/assets/web/i18n/" + lang + ".json"))
                    .getJSONObject("system");
            for (String key : new String[]{
                    "overview_title", "overview_lead", "overview_aria",
                    "group_connections", "group_connections_hint",
                    "group_vehicle", "group_vehicle_hint",
                    "group_privacy", "group_privacy_hint",
                    "group_advanced", "group_advanced_hint"
            }) {
                assertFalse(lang + ":" + key, system.getString(key).trim().isEmpty());
            }
        }
    }

    private static String read(String relative) throws Exception {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(relative.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate repository file: " + relative);
    }
}
