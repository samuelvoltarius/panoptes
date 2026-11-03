package at.greenchili.surfmate.tacho.oem;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ExternalNavigationContractTest {
    @Test
    public void notificationListenerIsPrivateAndRequiresAndroidConsent() throws Exception {
        String manifest = read("app/src/main/AndroidManifest.xml");
        int service = manifest.indexOf("ExternalNavigationNotificationService");
        assertTrue(service >= 0);
        String declaration = manifest.substring(service, Math.min(manifest.length(), service + 500));
        assertTrue(declaration.contains("android:exported=\"false\""));
        assertTrue(declaration.contains("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"));
    }

    @Test
    public void externalSourceHasSeparateOptInAndDoesNotForwardPrivateRouteData() throws Exception {
        String client = read("app/src/main/java/at/greenchili/surfmate/navmap/oem/OemNavigationClient.kt");
        assertTrue(client.contains("oemTurnGuidance"));
        assertTrue(client.contains("externalNotificationGuidance"));
        assertFalse(client.contains(".put(\"lat\""));
        assertFalse(client.contains(".put(\"lng\""));
        assertFalse(client.contains(".put(\"street"));
    }

    @Test
    public void switchAndPermissionExplanationExistInEveryLanguage() throws Exception {
        String html = read("app/src/main/assets/web/local/road-sense.html");
        assertTrue(html.contains("id=\"rsExternalNavGuidance\""));
        assertTrue(html.contains("external_nav_guidance_permission"));
        for (String language : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject roadSense = new JSONObject(read("app/src/main/assets/web/i18n/" + language + ".json"))
                    .getJSONObject("road_sense");
            assertTrue(roadSense.getString("external_nav_guidance").length() > 5);
            assertTrue(roadSense.getString("external_nav_guidance_desc").length() > 20);
            assertTrue(roadSense.getString("external_nav_guidance_permission").length() > 5);
        }
    }

    private static String read(String relative) throws Exception {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new IllegalStateException("missing " + relative);
    }
}
