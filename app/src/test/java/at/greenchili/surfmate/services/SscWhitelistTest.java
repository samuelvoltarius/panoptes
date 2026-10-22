package at.greenchili.surfmate.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.testutil.Quelltext;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/** ssc_whitelist-Weg (vorbereitet, nicht aktiv; Koordination 22.10. nach AUTOSTART_BEFUND Zustand b). */
public class SscWhitelistTest {

    private static final String PKG = "at.greenchili.surfmate";

    @Test
    public void nurAnhaengenNieUeberschreiben() {
        assertEquals("com.overdrive.app,com.byd.tripstats," + PKG,
                SscWhitelist.ergaenze("com.overdrive.app,com.byd.tripstats", PKG));
        assertNull("schon drin", SscWhitelist.ergaenze("com.overdrive.app," + PKG + ",com.byd.tripstats", PKG));
        assertEquals(PKG, SscWhitelist.ergaenze("null", PKG));
        assertEquals(PKG, SscWhitelist.ergaenze("", PKG));
        // Teilstring zählt nicht
        assertEquals(PKG + ".x," + PKG, SscWhitelist.ergaenze(PKG + ".x", PKG));
        assertFalse(SscWhitelist.enthaelt("com.a,com.b", "com"));
    }

    private static final class Schein implements SscWhitelist.Shell {
        final Map<String, String> w = new HashMap<>();
        boolean schreibenWirkt = true;
        int schreibAufrufe;
        @Override public String lies(String ns) { return w.getOrDefault(ns, "null"); }
        @Override public boolean schreibe(String ns, String wert) {
            schreibAufrufe++;
            if (schreibenWirkt) w.put(ns, wert);
            return true;
        }
    }

    @Test
    public void sicherstellenMitZuruecklesen() throws Exception {
        Schein s = new Schein();
        s.w.put("global", "com.overdrive.app,com.byd.tripstats");
        s.w.put("secure", "com.overdrive.app,com.byd.tripstats," + PKG);
        JSONObject r = SscWhitelist.sicherstellen(PKG, s);
        assertTrue(r.getBoolean("ok"));
        assertEquals("ergaenzt", r.getString("global"));
        assertEquals("schon_da", r.getString("secure"));
        assertEquals("com.overdrive.app,com.byd.tripstats," + PKG, s.w.get("global"));
        assertEquals(1, s.schreibAufrufe);
        // Schreiben ohne Wirkung → kein „ok“ (nur Belegtes zählt)
        Schein t = new Schein();
        t.schreibenWirkt = false;
        JSONObject r2 = SscWhitelist.sicherstellen(PKG, t);
        assertFalse(r2.getBoolean("ok"));
        assertEquals("fehler", r2.getString("global"));
    }

    @Test
    public void standardSichtbarUndNichtAktiv() throws Exception {
        assertEquals("sichtbar", SscWhitelist.weg(null));
        assertEquals("sichtbar", SscWhitelist.weg(new JSONObject().put("autostart", new JSONObject().put("weg", "quatsch"))));
        assertEquals("settings", SscWhitelist.weg(new JSONObject().put("autostart", new JSONObject().put("weg", "settings"))));
        // Nicht aktiviert: niemand außerhalb der Klasse ruft sicherstellen(...) auf (erst nach Befund a + GO)
        try (Stream<Path> s = Files.walk(Paths.get("src/main/java"))) {
            long aufrufer = s.filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".kt"))
                    .filter(p -> !p.toString().endsWith("SscWhitelist.java"))
                    .filter(p -> {
                        try { return Quelltext.lies(p).contains("SscWhitelist.sicherstellen"); }
                        catch (Exception e) { return false; }
                    }).count();
            assertEquals(0, aufrufer);
        }
    }
}
