package at.greenchili.surfmate.ui.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 0.1.38.7 Darstellung-Sync, Hell/Dunkel an der Quelle: der Kern-Wert anzeige.thema (dark|light) geht vor der
 * Native-Wahl, nur auto lässt sie gelten. Kein Gegenstempeln mehr.
 */
public class KernThemaTest {

    private static String quelle(String rel) throws Exception {
        Path p = Paths.get(rel);
        if (!Files.exists(p)) p = Paths.get("..").resolve(rel);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    public void kernWertGehtVorDerNativeWahl() {
        assertEquals("light", KernThema.aufloesen("light", "dark", true));
        assertEquals("dark", KernThema.aufloesen("dark", "light", false));
    }

    @Test
    public void autoLaesstNativeWahlGelten() {
        assertEquals("dark", KernThema.aufloesen("auto", "dark", false));
        assertEquals("light", KernThema.aufloesen("auto", "light", true));
        assertEquals("dark", KernThema.aufloesen("auto", null, true));
        assertEquals("light", KernThema.aufloesen("auto", null, false));
    }

    @Test
    public void ausKernLiestAnzeigeThema() throws Exception {
        assertEquals(null, KernThema.ausKern(null));
        assertEquals(null, KernThema.ausKern(new org.json.JSONObject()));
        assertEquals(null, KernThema.ausKern(new org.json.JSONObject().put("anzeige", new org.json.JSONObject())));
        assertEquals("light", KernThema.ausKern(new org.json.JSONObject().put("anzeige", new org.json.JSONObject().put("thema", "light"))));
        assertEquals("auto", KernThema.ausKern(new org.json.JSONObject().put("anzeige", new org.json.JSONObject().put("thema", "x"))));
    }

    @Test
    public void ungueltigesWirdAuto() {
        assertEquals("auto", KernThema.pruefen(null));
        assertEquals("auto", KernThema.pruefen(""));
        assertEquals("auto", KernThema.pruefen("DARK"));
        assertEquals("auto", KernThema.pruefen("<script>"));
        assertEquals("dark", KernThema.pruefen("dark"));
        assertEquals("light", KernThema.aufloesen(null, "light", true));
    }

    @Test
    public void quelleLiestDenKernWertZuerst() throws Exception {
        String wv = quelle("app/src/main/java/at/greenchili/surfmate/ui/fragment/WebViewFragment.kt");
        assertTrue("Brücke setKernThema", wv.contains("fun setKernThema(v: String?)"));
        assertTrue("getAppTheme liest den Kern zuerst", wv.contains("PreferencesManager.getKernThema()"));
        int erst = wv.indexOf("fun getAppTheme()");
        int kern = wv.indexOf("getKernThema()", erst);
        int nativ = wv.indexOf("PreferencesManager.getThemeMode()", erst);
        assertTrue(kern > 0 && kern < nativ);
        int rat = wv.indexOf("private fun resolveActiveTheme()");
        int kern2 = wv.indexOf("getKernThema()", rat);
        int nativ2 = wv.indexOf("PreferencesManager.getThemeMode()", rat);
        assertTrue("onPageFinished-Stempel liest den Kern zuerst", kern2 > 0 && kern2 < nativ2);
        String pm = quelle("app/src/main/java/at/greenchili/surfmate/ui/util/PreferencesManager.kt");
        assertTrue("Kern-Config direkt gelesen", pm.contains("KernThema.ausKern(at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig())"));
        assertTrue(pm.contains("fun getKernThema(): String") && pm.contains("fun setKernThema(v: String?): Boolean"));
        String theme = quelle("app/src/main/assets/web/shared/theme.js");
        assertTrue("Seite meldet den Kern-Wert an die App", theme.contains("window.AndroidBridge.setKernThema(v)"));
        assertTrue("auto fragt die App-Wahl zurück", theme.contains("window.AndroidBridge.getAppTheme() === 'light'"));
    }
}
