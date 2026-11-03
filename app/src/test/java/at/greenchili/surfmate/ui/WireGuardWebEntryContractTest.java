package at.greenchili.surfmate.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.json.JSONObject;
import org.junit.Test;

/** Keeps WireGuard setup reachable without weakening its login boundary. */
public class WireGuardWebEntryContractTest {

    @Test
    public void systemPageOffersProtectedSetupWithStatusAndRetry() throws Exception {
        String page = read("app/src/main/assets/web/local/system.html");
        String script = read("app/src/main/assets/web/shared/system.js");

        assertTrue(page.contains("id=\"wireguardSetupCard\" style=\"display:none;\""));
        assertTrue(page.contains("id=\"wireguardSetupOpen\" href=\"wireguard.html\""));
        assertTrue(page.contains("id=\"wireguardSetupRetry\""));
        assertTrue(script.contains("fetch('/api/wireguard', { cache: 'no-store' })"));
        assertTrue(script.contains("resp.status === 401 || resp.status === 403"));
        assertTrue(script.contains("card.style.display = 'none'"));
        assertTrue(script.contains("retry.style.display = ''"));
        assertFalse(script.contains("PrivateKey"));
    }

    @Test
    public void pairingAndPhoneWebSessionCannotOpenWireGuardSetup() throws Exception {
        String server = read("app/src/main/java/at/greenchili/surfmate/server/HttpServer.java");
        String phoneGuard = read("app/src/main/java/at/greenchili/surfmate/server/WebHandyGuard.java");

        assertTrue(server.contains("wireGuardRequest"));
        assertTrue(server.contains("? AuthMiddleware.checkJwtOnly"));
        assertTrue(phoneGuard.contains("\"/api/wireguard\""));
        assertTrue(phoneGuard.contains("\"/wireguard\", \"/wireguard.html\""));
    }

    @Test
    public void setupEntryIsTranslatedInEveryWebLanguage() throws Exception {
        for (String lang : new String[]{"de", "en", "fr", "it", "nl"}) {
            JSONObject system = new JSONObject(read(
                    "app/src/main/assets/web/i18n/" + lang + ".json"))
                    .getJSONObject("system");
            for (String key : new String[]{
                    "wireguard_setup_title", "wireguard_setup_lead",
                    "wireguard_not_configured", "wireguard_running_text",
                    "wireguard_stopped_text", "wireguard_setup_action",
                    "wireguard_edit_action", "wireguard_load_failed"
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
            Path module = current.resolve(relative.replaceFirst("^app/", ""));
            if (Files.isRegularFile(module)) {
                return new String(Files.readAllBytes(module), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate " + relative);
    }
}
