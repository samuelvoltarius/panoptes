package at.greenchili.surfmate.datenschutz;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.testutil.Quelltext;

import org.junit.Test;

import java.nio.file.Paths;

/** Regel Nr. 2: Wetter-Abruf mit Position nur nach Einwilligung (wetter.online, Standard aus), und nur grob. */
public class WetterEinwilligungTest {

    @Test
    public void ohneEinwilligungKeinAbruf() throws Exception {
        String w = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/weather/WeatherTemperature.java"));
        int f = w.indexOf("public static double fetchNow(double lat, double lon) {");
        int gate = w.indexOf("if (!onlineErlaubt()) return getCached();", f);
        int url = w.indexOf(".url(url(lat, lon))", f);
        assertTrue(gate > f && gate < url);                       // Sperre vor jedem Netzaufruf
        assertTrue(w.contains("w != null && w.optBoolean(\"online\", false)"));
        // 0.1.27: nur noch der eigene Server, Zelle 0,1° (≈ 10 km); nie mehr direkt zu Open-Meteo
        assertFalse(w.contains("api.open-meteo.com"));
        assertFalse(w.contains("latitude="));
        assertTrue(w.contains("\"https://byd.serveone.at/api/wetter/v1/jetzt\""));
        String de = Quelltext.lies(Paths.get("src/main/assets/server-i18n/de.json"));
        assertTrue(de.contains("auf ca. 10 km ungenau"));
        String k = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/einstellungen/Katalog.java"));
        assertTrue(k.contains("new Feld(\"wetter.online\", Feld.Typ.BOOL)"));
        assertTrue(k.contains("wetter.standard = false"));
    }
}
