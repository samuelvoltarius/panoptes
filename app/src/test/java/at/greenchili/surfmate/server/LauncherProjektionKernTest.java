package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;

/**
 * 0.1.41 (CP): Die Kachel „projektion“ (CarPlay / Android Auto) des Launchers übersteht die Prüfung im Kern; ohne
 * diesen Eintrag würfe der Abgleich sie still weg. Web-Bearbeiter kennt sie und bietet sie nur mit Werks-App an.
 */
public class LauncherProjektionKernTest {

    @Test
    public void kachelBleibtBeimKern() throws Exception {
        assertTrue(LauncherLayoutPruefung.KACHELN.contains("projektion"));
        JSONObject w = new JSONObject().put("id", "w9").put("typ", "widget").put("kachel", "projektion")
                .put("x", 4).put("y", 1).put("b", 2).put("h", 2);
        JSONObject roh = new JSONObject().put("format", "panoptes-launcher-layout").put("version", 1)
                .put("seiten", new JSONArray().put(new JSONArray().put(w)))
                .put("dock", new JSONArray().put("at.greenchili.surfmate").put("com.byd.carplay.ui"))
                .put("hintergrund", "panoptes").put("zaehler", 9);
        LauncherLayoutPruefung.Ergebnis e = LauncherLayoutPruefung.pruefen(roh,
                new HashSet<>(Arrays.asList("at.greenchili.surfmate", "com.byd.carplay.ui")));
        assertEquals(0, e.bereinigt);
        JSONObject k = e.layout.getJSONArray("seiten").getJSONArray(0).getJSONObject(0);
        assertEquals("projektion", k.getString("kachel"));
        assertEquals(2, k.getInt("b"));
    }

    private static String lies(String pfad) throws Exception {
        File b = new File("src/main").isDirectory() ? new File(".") : new File("app");
        return new String(Files.readAllBytes(new File(b, pfad).toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void webBearbeiterKenntKachel() throws Exception {
        String h = lies("src/main/assets/web/local/launcher.html");
        assertTrue(h.contains("'kamera', 'projektion', "));
        assertTrue(h.contains("k !== 'projektion' || projektionDa()"));
        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject j = new JSONObject(lies("src/main/assets/web/i18n/" + l + ".json"));
            assertTrue(l, j.getJSONObject("launcherlayout").getString("k_projektion").contains("CarPlay"));
        }
    }
}
