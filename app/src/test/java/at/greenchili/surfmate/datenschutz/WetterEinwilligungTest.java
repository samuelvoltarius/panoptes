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
        int url = w.indexOf("api.open-meteo.com", f);
        assertTrue(gate > f && gate < url);                       // Sperre vor jedem Netzaufruf
        assertTrue(w.contains("w != null && w.optBoolean(\"online\", false)"));
        assertTrue(w.contains("latitude=%.2f&longitude=%.2f"));    // grob, ≈ 1 km
        assertFalse(w.contains("latitude=%.4f"));
        String k = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/einstellungen/Katalog.java"));
        assertTrue(k.contains("new Feld(\"wetter.online\", Feld.Typ.BOOL)"));
        assertTrue(k.contains("wetter.standard = false"));
    }
}
