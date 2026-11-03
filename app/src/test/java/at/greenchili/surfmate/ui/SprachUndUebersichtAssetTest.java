package at.greenchili.surfmate.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Sichtbare Sprache, Markenname und Web-Navigation bleiben konsistent. */
public class SprachUndUebersichtAssetTest {

    @Test
    public void sichtbareDeutscheKatalogeNutzenGlossarUndPanoptes() throws Exception {
        String web = lies("app/src/main/assets/web/i18n/de.json");
        String server = lies("app/src/main/assets/server-i18n/de.json");
        String app = lies("app/src/main/res/values-de/strings.xml");

        for (String text : new String[] {web, server, app}) {
            assertFalse("sichtbares Glossar darf nicht zu Ladestand zurückfallen",
                    text.contains("Ladestand"));
            assertFalse("sichtbares Glossar heißt Regeln", text.contains("Automationen"));
        }
        assertTrue(app.contains("name=\"settings_hero_overline\">PANOPTES</string>"));
        assertFalse(app.contains("name=\"settings_hero_overline\">SURFMATE</string>"));
    }

    @Test
    public void dynamischeStatusTexteUndBekannteFallbacksSindUebersetzt() throws Exception {
        String system = lies("app/src/main/assets/web/shared/system.js");
        String surveillance = lies("app/src/main/assets/web/shared/surveillance.js");
        assertFalse(system.contains("badge.textContent = 'aus'"));
        assertTrue(system.contains("BYD.i18n.t('system.off')"));
        for (String raw : new String[] {
                "'läuft'", "'startet …'", "'Kamera & Weboberfläche'",
                "'Wächter'", "'Nicht gespeichert: '", "'Nur a–z, 0–9 und Bindestrich'"
        }) {
            assertFalse(raw, system.contains(raw));
        }
        assertFalse(surveillance.contains("summary.textContent = 'Für den Ländercode '"));

        String all = lies("app/src/main/assets/web/local/charging.html")
                + lies("app/src/main/assets/web/local/trips.html")
                + lies("app/src/main/assets/web/local/notifications.html")
                + lies("app/src/main/assets/web/local/surveillance.html");
        for (String raw : new String[] {
                "0 trips stored", "January 2026", "Page 1 of 1",
                "5s before", "10s after", "top · app", "cores (max"
        }) {
            assertFalse(raw, all.contains(raw));
        }
    }

    @Test
    public void modellDownloadZeigtKeineRoheHttpMeldung() throws Exception {
        String script = lies("app/src/main/assets/web/shared/vehicle-control.js");
        assertFalse(script.contains("onDone(null, s.error"));
        assertTrue(script.contains("xhr.status === 404 || xhr.status === 410"));
        assertTrue(script.contains("vehicle.network_starting_download"));
        assertTrue(script.contains("vehicle.model_file_not_found"));
        assertTrue(script.contains("vehicle.model_validation_failed"));
        assertTrue(script.contains("vehicle.model_load_failed"));
        assertTrue(script.contains("vehicle.download_failed"));

        for (String lang : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject vehicle = new JSONObject(lies(
                    "app/src/main/assets/web/i18n/" + lang + ".json"))
                    .getJSONObject("vehicle");
            for (String key : new String[] {
                    "network_starting_download", "model_file_not_found",
                    "model_validation_failed", "model_load_failed", "download_failed"
            }) {
                assertFalse(lang + ":" + key, vehicle.getString(key).trim().isEmpty());
                assertFalse(lang + ":" + key, vehicle.getString(key).contains("HTTP"));
            }
            assertFalse(lang + ": generic must not prescribe an update",
                    vehicle.getString("model_load_failed").toLowerCase().contains("panoptes"));
            assertFalse(lang, vehicle.getString("download_request_failed").contains("{status}"));
        }
    }

    @Test
    public void menueBenenntSystemUndMeldungswegeEindeutig() throws Exception {
        String shell = lies("app/src/main/assets/web/shared/app-shell.js");
        assertTrue(shell.contains("href: 'system.html',          label: 'System'"));
        assertTrue(shell.contains("i18n: 'nav.system'"));

        for (String lang : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject labels = new JSONObject(lies(
                    "app/src/main/assets/web/i18n/" + lang + ".json"))
                    .getJSONObject("shell");
            assertNotEquals(lang,
                    labels.getString("page_notifications"),
                    labels.getString("page_benachrichtigungen"));
        }
        String karte = lies("docs/CODEKARTE.md");
        assertTrue(karte.contains("`testfunktionen.html` bleibt absichtlich"));
        assertTrue(karte.contains("`vehicle-control-3d-test.html` ist ein interner"));
    }

    @Test
    public void neueWebSchluesselSindInAllenSprachenVorhanden() throws Exception {
        for (String lang : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject root = new JSONObject(lies(
                    "app/src/main/assets/web/i18n/" + lang + ".json"));
            JSONObject performance = root.getJSONObject("performance");
            JSONObject system = root.getJSONObject("system");
            JSONObject wallbox = root.getJSONObject("wallbox");
            assertFalse(performance.getString("cpu_top").isEmpty());
            assertFalse(performance.getString("cpu_cores").isEmpty());
            assertFalse(system.getString("off").isEmpty());
            assertFalse(system.getString("connected").isEmpty());
            assertFalse(system.getString("running").isEmpty());
            assertFalse(system.getString("camera_mapping_title").isEmpty());
            assertFalse(system.getString("camera_mapping_lead").isEmpty());
            assertFalse(system.getString("camera_mapping_action").isEmpty());
            assertFalse(system.getString("projection_title").isEmpty());
            assertFalse(system.getString("projection_lead").isEmpty());
            assertFalse(system.getString("projection_action").isEmpty());
            assertFalse(system.getString("projection_unavailable").isEmpty());
            assertFalse(system.getString("daemon_camera").isEmpty());
            assertFalse(system.getString("daemon_enabled").isEmpty());
            assertFalse(system.getString("not_saved").isEmpty());
            for (String value : new String[] {
                    wallbox.getString("region_eu"), wallbox.getString("region_eu_west"),
                    wallbox.getString("region_us_west"), wallbox.getString("region_us_east"),
                    wallbox.getString("region_cn"), wallbox.getString("region_in")
            }) {
                assertFalse(lang, value.trim().isEmpty());
            }
        }
    }

    private static String lies(String rel) throws Exception {
        Path p = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (p != null) {
            for (Path candidate : new Path[] {
                    p.resolve(rel), p.resolve(rel.replaceFirst("^app/", ""))
            }) {
                if (Files.isRegularFile(candidate)) {
                    return new String(Files.readAllBytes(candidate), "UTF-8");
                }
            }
            p = p.getParent();
        }
        throw new java.io.FileNotFoundException(rel);
    }
}
