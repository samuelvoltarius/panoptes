package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.testutil.Quelltext;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Paths;

/** 0.1.7.1: übernommenes „en“ aus der Overdrive-Config einmalig auf die Systemsprache (sonst de); Nutzerwahl gewinnt. */
public class LocaleUebernahmeTest {

    @Test
    public void overdriveEnMitSystemDeWirdDeutsch() throws Exception {
        assertEquals("de", LocaleManager.uebernommenesEnZiel(new JSONObject().put("locale", "en"), "de-DE"));
        assertEquals("fr", LocaleManager.uebernommenesEnZiel(new JSONObject().put("locale", "en"), "fr-CH"));
        assertEquals("de", LocaleManager.uebernommenesEnZiel(new JSONObject().put("locale", "en"), null));
        assertEquals("de", LocaleManager.uebernommenesEnZiel(new JSONObject().put("locale", "en"), "zh-CN"));
    }

    @Test
    public void nichtsAendernWennGewaehltMigriertOderEnglischesSystem() throws Exception {
        assertNull(LocaleManager.uebernommenesEnZiel(new JSONObject().put("locale", "en"), "en-US"));
        assertNull(LocaleManager.uebernommenesEnZiel(
                new JSONObject().put("locale", "en").put("localeGewaehlt", true), "de-DE"));
        assertNull(LocaleManager.uebernommenesEnZiel(
                new JSONObject().put("locale", "en").put("localeMigriert", true), "de-DE"));
        assertNull(LocaleManager.uebernommenesEnZiel(new JSONObject().put("locale", "fr"), "de-DE"));
        assertNull(LocaleManager.uebernommenesEnZiel(new JSONObject(), "de-DE"));
        assertNull(LocaleManager.uebernommenesEnZiel(null, "de-DE"));
    }

    /** Jede Wahl im Sprachwähler (auch „Auto“) setzt den Merker, damit die Migration sie nie überschreibt. */
    @Test
    public void wahlSetztMerker() throws Exception {
        String s = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/LocaleManager.java"));
        int set = s.indexOf("public static String set(String tag)");
        int auto = s.indexOf("public static void setAuto()");
        assertTrue(s.indexOf("delta.put(K_GEWAEHLT, true)", set) > set);
        assertTrue(s.indexOf("delta.put(K_GEWAEHLT, true)", auto) > auto);
        assertTrue(s.contains("uebernahmeEnMigrieren();"));
    }
}
