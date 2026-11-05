package at.greenchili.surfmate.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.ui.daemon.StartStufe;
import at.greenchili.surfmate.ui.daemon.StufeZustand;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import kotlin.Pair;

/**
 * 0.1.38.10 Vertrag „Anzeige je Dienst beim Start“: Zeilen im Ladebildschirm aus StartStatus, Karte „Start“ auf der
 * Startseite über GET /api/start/status (beantwortet die App selbst), Texte in 5 Sprachen, Chromium 74.
 */
public class StartAnzeigeTest {

    private static String lies(String rel) throws Exception {
        File f = new File(rel);
        if (!f.exists()) f = new File("app/" + rel);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    static final String WVF = "src/main/java/at/greenchili/surfmate/ui/fragment/WebViewFragment.kt";
    static final String[] SPRACHEN = {"de", "en", "fr", "it", "nl"};

    @Test
    public void zeileJeStufeMitZeichen() {
        List<Pair<StartStufe, StufeZustand>> st = Arrays.asList(
                new Pair<>(StartStufe.ZUGANG, StufeZustand.BEREIT),
                new Pair<>(StartStufe.OBERFLAECHE, StufeZustand.STARTET),
                new Pair<>(StartStufe.REST, StufeZustand.STARTET_NICHT));
        String t = StartAnzeige.INSTANCE.text(st, s -> s.getSchluessel(), z -> z.getSchluessel());
        assertEquals("✓  zugang: bereit\n…  oberflaeche: startet\n✗  rest: startet_nicht", t);
        assertEquals("", StartAnzeige.INSTANCE.text(java.util.Collections.emptyList(), s -> "x", z -> "y"));
    }

    @Test
    public void ladebildschirmUndWegeImCode() throws Exception {
        String xml = lies("src/main/res/layout/fragment_webview.xml");
        int spinner = xml.indexOf("@+id/startSpinner"), dienste = xml.indexOf("@+id/startDienste");
        assertTrue("Zeilen unter dem Ladekreis", spinner > 0 && dienste > spinner);
        String wvf = lies(WVF);
        assertTrue(wvf.contains("interceptStartStatus(url)?.let { return it }"));
        assertTrue(wvf.contains("Uri.parse(url).path != \"/api/start/status\""));
        assertTrue("liest die eine Stelle", wvf.contains("StartStatus.json(") && wvf.contains("StartStatus.stand("));
        assertTrue(wvf.contains("startDiensteZeigen()"));
        assertFalse("keine zweite Liste von Diensten im Fragment", wvf.contains("listOf(StartStufe."));
    }

    @Test
    public void texteInFuenfSprachen() throws Exception {
        String[] web = {"titel", "titel_fertig", "zugang", "oberflaeche", "waechter", "rest", "startet", "bereit",
                "startet_nicht", "hinweis_nicht", "ausblenden"};
        String[] res = {"start_dienst_zugang", "start_dienst_oberflaeche", "start_dienst_waechter", "start_dienst_rest",
                "start_zustand_startet", "start_zustand_bereit", "start_zustand_startet_nicht"};
        for (String sp : SPRACHEN) {
            JSONObject home = new JSONObject(lies("src/main/assets/web/i18n/" + sp + ".json")).getJSONObject("home");
            for (String k : web) assertFalse(sp + " " + k, home.getString("start_" + k).trim().isEmpty());
            String xml = lies("src/main/res/" + ("en".equals(sp) ? "values" : "values-" + sp) + "/strings.xml");
            for (String k : res) assertTrue(sp + " " + k, xml.contains("<string name=\"" + k + "\">"));
        }
        // Schlüssel der Karte = Stufen-Schlüssel aus StartStatus
        for (StartStufe s : StartStufe.values()) {
            assertTrue(s.getSchluessel(), new JSONObject(lies("src/main/assets/web/i18n/de.json")).getJSONObject("home")
                    .has("start_" + s.getSchluessel()));
        }
    }

    @Test
    public void startseiteUndChromium74() throws Exception {
        String html = lies("src/main/assets/web/local/index.html");
        assertTrue(html.contains("id=\"hStart\""));
        assertTrue(html.contains("start-karte.js"));
        assertTrue(html.contains("PanoptesStart.init(document.getElementById('hStart'))"));
        String js = lies("src/main/assets/web/shared/start-karte.js");
        assertTrue(js.contains("fetch('/api/start/status'"));
        String code = js.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
        for (String verboten : new String[]{"?.", "??", "=>", "`", "inset", "gap:", "const ", "let ", "replaceAll",
                "aspect-ratio", "clamp("}) {
            assertFalse("Chromium 74: " + verboten, code.contains(verboten));
        }
    }
}
