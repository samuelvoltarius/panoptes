package at.greenchili.surfmate.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 0.1.38.14 P1 (06.11.: „Irgendwo ist noch das Design von Overdrive das muss raus!“): Ein Nutzer erreicht nie
 * mehr eine alte native Oberfläche – keine Einstiege, keine alte Leiste, nach Neuaufbau immer zurück ins Web.
 */
public class AltDesignRausTest {

    private static final String W = "src/main/java/at/greenchili/surfmate/ui/fragment/WebViewFragment.kt";
    private static final String M = "src/main/java/at/greenchili/surfmate/ui/MainActivity.kt";

    @Test
    public void aufnahmenBleibenInDerWebOberflaeche() throws Exception {
        String w = lies(W);
        assertFalse("kein Abfangen von events.html mehr", w.contains("url.contains(\"/events.html\")"));
        assertFalse(w.contains("R.id.recordingsFragment"));
        assertFalse("kein Player ohne Fahrsperre über Deep-Link", w.contains("openEventByFilename"));
        // events.js spielt ?id= / ?file= selbst ab, anzeige.js sperrt events.html während der Fahrt
        String e = lies("src/main/assets/web/shared/events.js");
        assertTrue(e.contains("const idParam = urlParams.get('id');"));
        assertTrue(e.contains("if (found) this.playVideo(idParam);"));
        String a = lies("src/main/assets/web/shared/anzeige.js");
        assertTrue(a.contains("var FAHRT_SEITEN = { 'index.html': 1, 'navi.html': 1, 'waechter.html': 1, '': 1 };"));
        assertFalse("events.html ist keine Fahrt-Seite", a.contains("'events.html': 1"));
        assertTrue(lies("src/main/assets/web/local/waechter.html").contains("'events.html?filter=sentry&id='"));
    }

    @Test
    public void keineEinstiegeInDieAlteOberflaeche() throws Exception {
        String w = lies(W);
        assertTrue(w.contains("if (url.startsWith(ERWEITERT_URL)) return true"));
        assertFalse(w.contains("oeffneErweitert"));
        assertFalse(w.contains("\"erweitert\" -> R.id.dashboardFragment"));
        assertTrue(w.contains("fun openLegacy(): String = \"entfernt\""));
        String a = lies("src/main/assets/web/shared/anzeige.js");
        assertFalse("kein Menüpunkt „Erweitert (alt)“", a.contains("erweitertEinbauen"));
        assertFalse(a.contains("panoptes-app://erweitert"));
        assertFalse(a.contains("openLegacy"));
    }

    @Test
    public void alteLeisteNieSichtbar() throws Exception {
        String m = lies(M);
        assertTrue(m.contains("findViewById<View>(R.id.navigationRailContainer)?.visibility = View.GONE"));
        assertFalse(m.contains("navigationRailContainer)?.visibility = if (voll) View.GONE else View.VISIBLE"));
    }

    @Test
    public void nachNeuaufbauJedesAlteZielZurueckInsWeb() throws Exception {
        int start = 1, projektion = 2, dashboard = 3, aufnahmen = 4, player = 5;
        Set<Integer> nativ = new HashSet<>(Collections.singletonList(projektion));
        assertFalse(NeuaufbauZiel.INSTANCE.altesZiel(start, start, nativ));
        assertFalse("Funktionsfläche bleibt", NeuaufbauZiel.INSTANCE.altesZiel(projektion, start, nativ));
        assertTrue(NeuaufbauZiel.INSTANCE.altesZiel(dashboard, start, nativ));
        assertTrue(NeuaufbauZiel.INSTANCE.altesZiel(aufnahmen, start, nativ));
        assertTrue(NeuaufbauZiel.INSTANCE.altesZiel(player, start, nativ));
        assertFalse(NeuaufbauZiel.INSTANCE.altesZiel(null, start, nativ));
        String m = lies(M);
        assertTrue(m.contains("NeuaufbauZiel.altesZiel(navController.currentDestination?.id, R.id.panoptesStartFragment,"));
        assertTrue(m.contains("setOf(R.id.projectionFragment)"));
    }

    private static String lies(String rel) throws Exception {
        Path p = Paths.get(rel);
        if (!Files.exists(p)) p = Paths.get("app").resolve(rel);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
