package at.greenchili.surfmate.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** 0.1.38.14 (P4, Projektinhaber: „Auch das Navi leichter machen“): Ziel → Vorschau → Start frei, Zwischenstopps hinter „Mehr“, Hinweis „Karte aus“. */
public class NaviLeichterTest {

    private static String lies(String pfad) throws Exception {
        File b = new File("src/main/assets").isDirectory() ? new File(".") : new File("app");
        return new String(Files.readAllBytes(new File(b, pfad).toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    public void zwischenstoppsHinterMehrKarteAusGrosseKnoepfe() throws Exception {
        String n = lies("src/main/assets/web/local/navi.html");
        int v = n.indexOf("viaZeigen: function () {");
        String f = n.substring(v, n.indexOf("var h = '<div class=\"n-via-kopf\">", v));
        assertTrue("ohne Stopps nur „Mehr“", f.contains("if (!L.length && !this.viaNeu && !this.viaMehr) {") && f.contains("data-v=\"mehr\""));
        assertTrue(n.contains("PanoptesKartenquelle.get() === 'keine'") && n.contains("id=\"nKarteAus\""));
        assertTrue("große Knöpfe nur am Display", n.contains("if (imAuto()) document.body.className += ' n-gross';"));
        // 0.1.38.14 Integration: P3 hebt kartenquelle.js auf v=6 (alle Seiten einheitlich), P4 fasst die Datei nicht an
        assertTrue("kartenquelle.js v=6 aus P3", n.contains("kartenquelle.js?v=6"));
        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject navi = new JSONObject(lies("src/main/assets/web/i18n/" + l + ".json")).getJSONObject("navi");
            assertFalse(l, navi.optString("via_mehr").isEmpty() || navi.optString("karte_aus").isEmpty());
        }
    }
}
