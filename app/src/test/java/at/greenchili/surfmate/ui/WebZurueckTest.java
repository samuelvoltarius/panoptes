package at.greenchili.surfmate.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;

/**
 * 0.1.39 Z1 Vertrag Android-Zurück (E2E 10.11.: nach Leiste/Link moveTaskToBack trotz history.length=3): Zurück geht
 * immer eine Web-Seite zurück, solange es Verlauf gibt; erst auf der Startseite in den Hintergrund.
 */
public class WebZurueckTest {

    private static final String S = "http://127.0.0.1:8180/";
    private static final String SYS = "http://127.0.0.1:8180/system.html";
    private static final String NAVI = "http://127.0.0.1:8180/navi.html";

    @Test
    public void entscheidungWieVereinbart() {
        assertEquals(WebZurueck.Schritt.WEB_ZURUECK, WebZurueck.INSTANCE.entscheide(true, Collections.emptyList(), SYS));
        // canGoBack() false (Fall Leiste/Link), eigener Verlauf hat die vorige Seite
        assertEquals(WebZurueck.Schritt.VORIGE_LADEN, WebZurueck.INSTANCE.entscheide(false, Arrays.asList(S, SYS), SYS));
        // kein Verlauf, aber nicht auf der Startseite → erst zur Startseite
        assertEquals(WebZurueck.Schritt.START_LADEN, WebZurueck.INSTANCE.entscheide(false, Collections.singletonList(SYS), SYS));
        // Startseite → Hintergrund
        assertEquals(WebZurueck.Schritt.HINTERGRUND, WebZurueck.INSTANCE.entscheide(false, Collections.singletonList(S), S));
        assertEquals(WebZurueck.Schritt.HINTERGRUND,
                WebZurueck.INSTANCE.entscheide(false, Collections.emptyList(), "http://127.0.0.1:8180/index.html?x=1#a"));
        assertEquals("fremde Seite: nie zur Startseite laden", WebZurueck.Schritt.HINTERGRUND,
                WebZurueck.INSTANCE.entscheide(false, Collections.emptyList(), "about:blank"));
        assertEquals(WebZurueck.Schritt.HINTERGRUND, WebZurueck.INSTANCE.entscheide(false, Collections.emptyList(), null));
    }

    @Test
    public void startseite() {
        assertTrue(WebZurueck.INSTANCE.istStart(S));
        assertTrue(WebZurueck.INSTANCE.istStart("http://127.0.0.1:8180"));
        assertTrue(WebZurueck.INSTANCE.istStart("http://127.0.0.1:8180/index.html"));
        assertTrue(WebZurueck.INSTANCE.istStart("http://127.0.0.1:8180/?anzeige=auto"));
        assertFalse(WebZurueck.INSTANCE.istStart(SYS));
        assertFalse(WebZurueck.INSTANCE.istStart("http://127.0.0.1:8180/system.html#einrichtung"));
    }

    @Test
    public void verlaufFolgtDerNavigation() {
        WebZurueck.Verlauf v = new WebZurueck.Verlauf();
        v.besucht(S, false);
        v.besucht(SYS, false);       // Leiste
        v.besucht(SYS, true);        // Neuladen zählt nicht
        v.besucht(SYS, false);       // Wiederholung zählt nicht
        v.besucht("about:blank", false);
        v.besucht(NAVI, false);      // Link
        assertEquals(Arrays.asList(S, SYS, NAVI), v.liste());
        assertEquals(SYS, v.zurueck());
        v.besucht(SYS, false);       // geladen nach dem Zurück: keine Dublette
        assertEquals(Arrays.asList(S, SYS), v.liste());
        assertEquals(S, v.zurueck());
        assertNull(v.zurueck());
        for (int i = 0; i < 70; i++) v.besucht("http://127.0.0.1:8180/s" + i + ".html", false);
        assertEquals(WebZurueck.MAX, v.liste().size());
        v.leeren();
        assertTrue(v.liste().isEmpty());
    }

    @Test
    public void webViewFragmentNutztDenVertrag() throws Exception {
        String w = lies("src/main/java/at/greenchili/surfmate/ui/fragment/WebViewFragment.kt");
        assertTrue(w.contains("override fun handleOnBackPressed() { webZurueck() }"));
        assertTrue(w.contains("seitenVerlauf.besucht(url, isReload)"));
        assertTrue("moveTaskToBack nur noch im Schritt HINTERGRUND",
                w.contains("WebZurueck.Schritt.HINTERGRUND -> activity?.moveTaskToBack(true)"));
        assertEquals(1, w.split("moveTaskToBack", -1).length - 1);
        assertTrue("Ersatz-Zurück leert den WebView-Verlauf", w.contains("if (webVerlaufLeeren) { webVerlaufLeeren = false; view?.clearHistory() }"));
        assertTrue("Diagnose nur Debug-Stufe", w.contains("android.util.Log.isLoggable(\"WebZurueck\", android.util.Log.DEBUG)"));
    }

    private static String lies(String rel) throws Exception {
        Path p = Paths.get(rel);
        if (!Files.exists(p)) p = Paths.get("app").resolve(rel);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
