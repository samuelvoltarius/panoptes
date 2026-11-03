package at.greenchili.surfmate.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 0.1.37 UI-Aufbau: Die Steuerungsseite zeigt die Befehle zusätzlich als Kategorie-Kacheln mit Favoriten.
 * Die Kacheln lösen nie selbst einen Befehl aus, sie öffnen nur das Bedienfeld des vorhandenen Reiters.
 */
public class BefehleKachelnAssetTest {

    private static String lies(String pfad) throws Exception {
        File basis = new File("src/main/assets").isDirectory() ? new File(".") : new File("app");
        return new String(Files.readAllBytes(new File(basis, pfad).toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void kachelnSindEingebundenUndNurEinWegZumReiter() throws Exception {
        String html = lies("src/main/assets/web/local/vehicle-control.html");
        assertTrue(html.contains("befehle-kacheln.js?v="));
        assertTrue(html.contains("befehle-kacheln.css?v="));
        String js = lies("src/main/assets/web/shared/befehle-kacheln.js");
        // kein eigener Befehlsweg: nur der Reiter wird angetippt
        assertFalse(js.contains("fetch("));
        assertFalse(js.contains("/api/"));
        assertTrue(js.contains("tab.click()"));
        // ES5 für alte WebViews
        assertFalse(js.contains("=>"));
        assertFalse(js.contains("?."));
        assertFalse(js.matches("(?s).*\\b(let|const)\\s.*"));
    }

    @Test
    public void texteInAllenSprachen() throws Exception {
        for (String lang : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject vc = new JSONObject(lies("src/main/assets/web/i18n/" + lang + ".json"))
                    .getJSONObject("vehicle_control");
            for (String k : new String[] {"kacheln_favoriten", "kacheln_kategorien", "kacheln_fav_leer",
                    "kacheln_stern_an", "kacheln_stern_aus", "ansicht_kacheln", "ansicht_auto"}) {
                assertFalse(lang + ":" + k, vc.optString(k).trim().isEmpty());
            }
        }
    }
}
