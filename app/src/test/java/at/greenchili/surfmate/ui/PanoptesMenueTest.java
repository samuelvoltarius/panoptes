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

    @Test
    public void dreiEintraegeMitWebSeite() throws Exception {
        String nav = lies("src/main/res/navigation/nav_graph.xml");
        String[][] e = {{"waechterFragment", "/waechter"}, {"naviFragment", "/navi"}, {"systemFragment", "/system"}};
        for (String[] x : e) {
            int i = nav.indexOf("android:id=\"@+id/" + x[0] + "\"");
            assertTrue(x[0], i >= 0);
            int ende = nav.indexOf("</fragment>", i);
            String block = nav.substring(i, ende);
            assertTrue(block.contains("ui.fragment.WebViewFragment"));
            assertTrue(block.contains("android:defaultValue=\"" + x[1] + "\""));
        }
        for (String l : new String[]{"layout", "layout-land"}) {
            String lay = lies("src/main/res/" + l + "/activity_main_new.xml");
            for (String id : new String[]{"railDestWaechter", "railDestNavi", "railDestSystem"}) {
                assertTrue(l + " " + id, lay.contains("android:id=\"@+id/" + id + "\""));
            }
        }
        String main = lies("src/main/java/at/greenchili/surfmate/ui/MainActivity.kt");
        assertTrue(main.contains("RailItem(NavigationRailCatalog.WAECHTER, R.id.railDestWaechter, R.id.waechterFragment"));
        assertTrue(main.contains("RailItem(NavigationRailCatalog.NAVI, R.id.railDestNavi, R.id.naviFragment"));
        assertTrue(main.contains("RailItem(NavigationRailCatalog.SYSTEM, R.id.railDestSystem, R.id.systemFragment"));
        String kat = lies("src/main/java/at/greenchili/surfmate/ui/navigation/NavigationRailCatalog.kt");
        assertTrue(kat.contains("NavigationRailOption(WAECHTER"));
        assertTrue(kat.contains("NavigationRailOption(NAVI"));
        assertTrue(kat.contains("NavigationRailOption(SYSTEM"));
    }

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

    @Test
    public void abrpNurOptional() throws Exception {
        String f = lies("src/main/java/at/greenchili/surfmate/ui/fragment/IntegrationsFragment.kt");
        assertFalse(f.contains("telegramConfigured && abrpConnected"));
        assertTrue(lies("src/main/res/values-de/strings.xml").contains("Optional, Standard aus: schickt Akkustand"));
    }

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
        assertTrue(nav.contains("android:id=\"@+id/dashboardFragment\""));   // alte Oberfläche bleibt
        String w = lies("src/main/java/at/greenchili/surfmate/ui/fragment/WebViewFragment.kt");
        assertTrue(w.contains("if (vollbild) spliceAppChrome(roh) else spliceEmbedChrome(roh)"));
        assertTrue(w.contains("settings.setSupportZoom(false)"));
        assertTrue(w.contains("if (w != null && w.canGoBack()) w.goBack() else activity?.moveTaskToBack(true)"));
        assertFalse(w.contains("w.goBack() else oeffneErweitert()"));
        assertTrue(w.contains("const val ERWEITERT_URL = \"panoptes-app://erweitert\""));
        assertTrue(w.contains("const val DISPLAY_UA = \"PanoptesDisplay/1\""));
        assertTrue(w.contains("fun openLegacy(): String = navigate(\"erweitert\")"));
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
