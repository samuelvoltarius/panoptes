package at.greenchili.surfmate.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.testutil.Quelltext;

import org.junit.Test;

import java.nio.file.Paths;

/**
 * 0.1.7.1 (Alfreds Test): Die native App führt zu den neuen Panoptes-Seiten Wächter (/waechter), Navi (/navi) und
 * System (/system mit „Gerät koppeln“ und „Routenplaner-Konto“). ABRP ist nur noch optional.
 */
public class PanoptesMenueTest {

    static String lies(String p) throws Exception {
        return Quelltext.lies(Paths.get(p));
    }

    // 0.1.39 A1: dreiEintraegeMitWebSeite entfällt – die alte native Oberfläche ist gelöscht (Leiste, Web-Ziele)

    @Test
    public void texteInFuenfSprachen() throws Exception {
        for (String l : new String[]{"", "-de", "-fr", "-it", "-nl"}) {
            String s = lies("src/main/res/values" + l + "/strings.xml");
            for (String k : new String[]{"rail_waechter", "rail_navi", "rail_system"}) {
                assertTrue(l + " " + k, s.contains("<string name=\"" + k + "\">"));
            }
        }
        assertTrue(lies("src/main/res/values-de/strings.xml").contains("<string name=\"rail_waechter\">Wächter</string>"));
    }

    // 0.1.39 A1: abrpNurOptional entfällt – die alte native Oberfläche ist gelöscht (IntegrationsFragment)

    /** Launcher-Symbol unter neuem Ressourcen-Namen (BYD-Launcher-Cache hielt das alte OverDrive-Bild). */
    @Test
    public void launcherSymbolNeuerName() throws Exception {
        String m = lies("src/main/AndroidManifest.xml");
        assertTrue(m.contains("android:icon=\"@mipmap/ic_panoptes\""));
        assertTrue(m.contains("android:roundIcon=\"@mipmap/ic_panoptes_round\""));
        assertFalse(m.contains("ic_launcher"));
        for (String d : new String[]{"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"}) {
            assertTrue(java.nio.file.Files.exists(Paths.get("src/main/res/mipmap-" + d + "/ic_panoptes.webp")));
            assertFalse(java.nio.file.Files.exists(Paths.get("src/main/res/mipmap-" + d + "/ic_launcher.webp")));
        }
        assertFalse(java.nio.file.Files.exists(Paths.get("src/main/assets/web/shared/tmp_dark.svg")));
    }

    /** Eine Oberfläche: Start vollflächig in der Web-Oberfläche „/“, alte native Oberfläche nur über „Erweitert“. */
    @Test
    public void startVollflaechigWeb() throws Exception {
        String nav = lies("src/main/res/navigation/nav_graph.xml");
        assertTrue(nav.contains("app:startDestination=\"@id/panoptesStartFragment\""));
        int i = nav.indexOf("android:id=\"@+id/panoptesStartFragment\"");
        String block = nav.substring(i, nav.indexOf("</fragment>", i));
        assertTrue(block.contains("ui.fragment.WebViewFragment"));
        assertTrue(block.contains("android:defaultValue=\"/\""));
        assertTrue(block.contains("android:name=\"vollbild\""));
        // 0.1.38.14 P1: das Ziel gibt es noch (unerreichbar), siehe AltDesignRausTest
        String w = lies("src/main/java/at/greenchili/surfmate/ui/fragment/WebViewFragment.kt");
        assertTrue(w.contains("if (vollbild) spliceAppChrome(roh) else spliceEmbedChrome(roh)"));
        assertTrue(w.contains("settings.setSupportZoom(false)"));
        assertTrue("0.1.39 Z1: Zurück über WebZurueck", w.contains("override fun handleOnBackPressed() { webZurueck() }")
                && w.contains("WebZurueck.Schritt.HINTERGRUND -> activity?.moveTaskToBack(true)"));
        assertFalse(w.contains("w.goBack() else oeffneErweitert()"));
        assertTrue(w.contains("const val ERWEITERT_URL = \"panoptes-app://erweitert\""));
        assertTrue(w.contains("const val DISPLAY_UA = \"PanoptesDisplay/1\""));
        assertTrue("0.1.38.14 P1: alte Oberfläche raus", w.contains("fun openLegacy(): String = \"entfernt\""));
        // Web-Navigation darf in der Vollflächen-Hülle nie versteckt werden (sonst gefangen auf „/“)
        assertTrue(w.contains("if (!vollbild) view?.evaluateJavascript(INJECT_JS, null)"));
        assertFalse(w.replace("if (!vollbild) view?.evaluateJavascript(INJECT_JS, null)", "")
                .contains("evaluateJavascript(INJECT_JS"));
        assertTrue(w.contains("settings.userAgentString + \" \" + DISPLAY_UA"));
        String m = lies("src/main/java/at/greenchili/surfmate/ui/MainActivity.kt");
        assertTrue(m.contains("val voll = destination.id == R.id.panoptesStartFragment"));
    }

    @Test
    public void appChromeBehaeltWebNavigation() {
        String h = at.greenchili.surfmate.ui.fragment.WebViewFragment.spliceAppChrome("<html><head><title>x</title></head></html>");
        assertTrue(h.contains("data-android-app"));
        assertFalse(h.contains(".sidebar"));
        assertTrue(at.greenchili.surfmate.ui.fragment.WebViewFragment.spliceEmbedChrome("<html><head></head></html>").contains(".sidebar"));
    }
}
