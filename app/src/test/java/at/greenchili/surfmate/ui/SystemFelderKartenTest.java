package at.greenchili.surfmate.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.einstellungen.Feld;
import at.greenchili.surfmate.einstellungen.Katalog;
import at.greenchili.surfmate.testutil.Quelltext;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 0.1.38.10 fix-einstellungen-sichtbar: Die übrigen Felder der Gruppe „system“ zeichnet system.html in ihre fachliche
 * Karte (bzw. display.html den Launcher-Video-Schalter). EINE Liste in system-overview.js (KARTEN_FELDER,
 * SEITEN_FELDER) für Zeichnen und Sprungziel der Suche; jede Karte hat ihren Platzhalter, kein Feld doppelt, nur
 * sichtbare Katalog-Felder der Gruppe „system“, keine Kopie der Liste in system.html, Chromium 74.
 */
public class SystemFelderKartenTest {

    private static String lies(String rel) throws Exception {
        return Quelltext.lies(Quelltext.aufloesen(Paths.get("src/main/assets/web/" + rel))).replace("\r\n", "\n");
    }

    /** Karte → Schlüssel aus dem Block {@code var KARTEN_FELDER = {…};}. */
    static Map<String, Set<String>> kartenFelder(String ov) {
        int a = ov.indexOf("var KARTEN_FELDER = {");
        int e = ov.indexOf("};", a);
        assertTrue("KARTEN_FELDER fehlt", a >= 0 && e > a);
        Map<String, Set<String>> out = new LinkedHashMap<>();
        Matcher m = Pattern.compile("(\\w+):\\s*\\[([^\\]]*)\\]").matcher(ov.substring(a, e));
        while (m.find()) {
            Set<String> keys = new HashSet<>();
            Matcher k = Pattern.compile("'([^']+)'").matcher(m.group(2));
            while (k.find()) keys.add(k.group(1));
            out.put(m.group(1), keys);
        }
        return out;
    }

    @Test
    public void listeKartenUndKatalogPassen() throws Exception {
        String ov = lies("shared/system-overview.js");
        String sys = lies("local/system.html");
        Map<String, Set<String>> kf = kartenFelder(ov);
        assertEquals(4, kf.size());
        Katalog.Kontext k = new Katalog.Kontext();
        k.cluster = true;
        k.hud = true;
        Map<String, Feld> katalog = new HashMap<>();
        for (Feld f : Katalog.felder(k)) katalog.put(f.key, f);
        Set<String> gesehen = new HashSet<>();
        for (Map.Entry<String, Set<String>> e : kf.entrySet()) {
            assertTrue("Karte " + e.getKey(), sys.contains("id=\"" + e.getKey() + "\""));
            assertTrue("Platzhalter " + e.getKey(), sys.contains("data-karten-felder=\"" + e.getKey() + "\""));
            for (String key : e.getValue()) {
                assertTrue("doppelt: " + key, gesehen.add(key));
                Feld f = katalog.get(key);
                assertNotNull("nicht im Katalog: " + key, f);
                assertEquals(key, "system", f.gruppe);
                assertFalse("verborgen: " + key, f.verborgen);
                assertFalse("keine Kopie der Liste in system.html: " + key, sys.contains("'" + key + "'"));
            }
        }
        assertTrue("Weitere Einstellungen im Reiter Erweitert", ov.contains("'coreServicesCard', 'weitereEinstellungen']"));
        assertTrue(sys.contains("PanoptesSystemOverview.kartenFelder"));
        assertTrue(sys.contains("store.zeichne(box, { gruppe: 'system', nur: keys });"));
        assertTrue("Suche nutzt dieselbe Liste", ov.contains("var ziel = feldZiel(f.key) ||"));
        // Launcher-Video: fachlich bei den Launcher-Schaltern auf display.html
        assertTrue(ov.contains("var SEITEN_FELDER = { 'display.html#launcher': ['anzeige.launcherVideo', 'launcher.tempoWarnung'] };"));
        assertTrue(lies("local/display.html").contains("nur: ['launcher.startbildschirm', 'anzeige.launcherVideo', 'launcher.tempoWarnung']"));
    }

    @Test
    public void rechteWieImKatalog() throws Exception {
        // Die Karte zeichnet über store.zeichne; Schreiben prüft der Kern (nurLesendExtern). Belegt: Katalog unverändert.
        Katalog.Kontext k = new Katalog.Kontext();
        Map<String, Feld> katalog = new HashMap<>();
        for (Feld f : Katalog.felder(k)) katalog.put(f.key, f);
        for (String key : new String[]{"ha.positionTeilen", "ha.mdns", "planner.autoId", "fahrtenbuch.planer", "wetter.online",
                "gewohnheiten.an", "gewohnheiten.aufbewahrungTage", "gewohnheiten.vergessen", "telegram.zurueckAmAuto",
                "anzeige.launcherVideo"}) {
            assertTrue(key, katalog.get(key).nurLesendExtern);
        }
        assertTrue(katalog.get("planner.autoId").nurLesend);
    }

    @Test
    public void texteUndChromium74() throws Exception {
        for (String sp : new String[]{"de", "en", "fr", "it", "nl"}) {
            JSONObject s = new JSONObject(lies("i18n/" + sp + ".json")).getJSONObject("system");
            assertFalse(sp, s.getString("weitere_title").trim().isEmpty());
            assertFalse(sp, s.getString("weitere_lead").trim().isEmpty());
        }
        String ov = lies("shared/system-overview.js").replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
        for (String v : new String[]{"?.", "??", "=>", "`", "const ", "let ", "replaceAll"}) {
            assertFalse("Chromium 74: " + v, ov.contains(v));
        }
    }
}
