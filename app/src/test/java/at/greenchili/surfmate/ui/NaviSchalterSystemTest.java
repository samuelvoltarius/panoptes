package at.greenchili.surfmate.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.einstellungen.Feld;
import at.greenchili.surfmate.einstellungen.Katalog;
import at.greenchili.surfmate.testutil.Quelltext;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Paths;

/**
 * 0.1.38.10 (Befund backup-8): navi.sprachansagen, planner.ansage und planner.band zeichnet system.html in der Karte
 * „Navigation“ (Reiter „Auto & Anzeige“). Eine Liste in system-overview.js für Karte und Suche, Rechte wie im Katalog.
 */
public class NaviSchalterSystemTest {

    private static final String[] KEYS = {"navi.sprachansagen", "planner.ansage", "planner.band"};

    private static String lies(String p) throws Exception {
        return Quelltext.lies(Paths.get("src/main/assets/web/" + p));
    }

    @Test
    public void eineListeFuerKarteUndSuche() throws Exception {
        String ov = lies("shared/system-overview.js");
        assertTrue(ov.contains("var NAVI_FELDER = ['navi.sprachansagen', 'planner.ansage', 'planner.band'];"));
        assertTrue("Suche führt auf die Karte", ov.contains("if (NAVI_FELDER.indexOf(key) >= 0) return 'system.html#navigation';")
                && ov.contains("var ziel = feldZiel(f.key) ||"));
        assertTrue("Reiter Auto & Anzeige", ov.contains("'blitzer', 'navigation'],"));
        assertTrue(ov.contains("naviFelder: NAVI_FELDER"));
        String sys = lies("local/system.html");
        assertTrue(sys.contains("id=\"navigation\""));
        assertTrue(sys.contains("id=\"navigationFelder\""));
        assertTrue(sys.contains("PanoptesSystemOverview.naviFelder"));
        assertTrue(sys.contains("store.zeichne(document.getElementById('navigationFelder'), { gruppe: 'system', nur: naviKeys });"));
        assertTrue("Zwischenspeicher neu", sys.contains("system-overview.js?v=8"));
        for (String k : KEYS) {
            assertFalse("keine Kopie der Liste in system.html: " + k, sys.contains("'" + k + "'"));
        }
    }

    @Test
    public void rechteWieImKatalog() {
        int gefunden = 0;
        for (Feld f : Katalog.felder(new Katalog.Kontext())) {
            if ("navi.sprachansagen".equals(f.key)) { gefunden++; assertTrue(f.nurLesendExtern); assertEquals("system", f.gruppe); }
            if ("planner.ansage".equals(f.key) || "planner.band".equals(f.key)) { gefunden++; assertEquals("system", f.gruppe); }
        }
        assertEquals(3, gefunden);
    }

    @Test
    public void texteFuenfSprachenUndRichtigerMenuweg() throws Exception {
        String[][] weg = {{"de", "Einstellungen → Alle Einstellungen → Auto & Anzeige → Navigation"},
                {"en", "Settings → All settings → Car & display → Navigation"},
                {"fr", "Réglages → Tous les réglages → Voiture et affichage → Navigation"},
                {"it", "Impostazioni → Tutte le impostazioni → Auto e display → Navigazione"},
                {"nl", "Instellingen → Alle instellingen → Auto en scherm → Navigatie"}};
        for (String[] w : weg) {
            JSONObject j = new JSONObject(lies("i18n/" + w[0] + ".json"));
            JSONObject sys = j.getJSONObject("system");
            assertFalse(w[0], sys.optString("navigation_title").isEmpty());
            assertFalse(w[0], sys.optString("navigation_lead").isEmpty());
            String desc = j.getJSONObject("road_sense").getString("map_open_desc");
            assertTrue(w[0] + ": " + desc, desc.contains(w[1]));
            // Menüweg passt zu den Namen in der Oberfläche
            assertTrue(w[0], w[1].contains(j.getJSONObject("shell").getString("page_einstellungen_alle")));
            assertTrue(w[0], w[1].contains(sys.getString("group_vehicle")));
        }
        assertTrue(lies("local/road-sense.html").contains("Einstellungen → Alle Einstellungen → Auto &amp; Anzeige → Navigation"));
    }

    @Test
    public void chromium74() throws Exception {
        for (String p : new String[] {"shared/system-overview.js", "local/system.html"}) {
            String q = lies(p);
            int a = p.endsWith(".html") ? q.indexOf("0.1.38.10: Navi-Schalter") : 0;
            String teil = p.endsWith(".html") ? q.substring(a, q.indexOf("energie.profil", a)) : q;
            assertFalse(p, teil.contains("?."));
            assertFalse(p, teil.contains("??"));
        }
    }
}
