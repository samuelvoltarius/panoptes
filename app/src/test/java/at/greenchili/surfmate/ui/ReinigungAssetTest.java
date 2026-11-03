package at.greenchili.surfmate.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.testutil.Quelltext;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Paths;

/**
 * Bildschirm-Reinigungsmodus: 30 s alle Berührungen schlucken, Countdown, Text in 5 Sprachen, nur in P, lang drücken
 * beendet. Reine Anzeige: keine Fahrzeugfunktion, kein schreibender Aufruf.
 */
public class ReinigungAssetTest {

    private static String lies(String p) throws Exception {
        return Quelltext.lies(Paths.get("src/main/assets/web/" + p));
    }

    @Test
    public void dreissigSekundenAlleBeruehrungenNurInP() throws Exception {
        String r = lies("shared/reinigung.js");
        assertTrue(r.contains("var DAUER_S = 30"));
        assertTrue("lang drücken beendet", r.contains("LANG_MS = 1500") && r.contains("aktiv.lang = setTimeout(ende, LANG_MS)"));
        for (String e : new String[] {"'pointerdown'", "'touchstart'", "'click'", "'contextmenu'", "'wheel'", "'keydown'"}) {
            assertTrue(e, r.contains(e));
        }
        assertTrue("Capture-Phase, nicht passiv", r.contains("{ capture: true, passive: false }"));
        assertTrue(r.contains("e.preventDefault()") && r.contains("e.stopImmediatePropagation()"));
        assertTrue("nur in P", r.contains("if (gang) return gang === 'P';"));
        assertTrue("Tempo sperrt", r.contains("tempo > 1.5"));
        assertTrue("verlässt P → sofort Schluss", r.contains("if (aktiv && s && !erlaubt(s)) ende();"));
        assertTrue("Countdown", r.contains("zahl.textContent = String(Math.max(0, rest));"));
    }

    @Test
    public void keineFahrzeugfunktion() throws Exception {
        String r = lies("shared/reinigung.js");
        assertFalse(r.contains("method: 'POST'") || r.contains("method:'POST'"));
        assertFalse(r.contains("/api/vehicle") || r.contains("/api/quick-controls") || r.contains("/api/keymap"));
        assertFalse(r.contains("brightness") || r.contains("/api/"));
        assertTrue("liest nur /status", r.contains("fetch('/status'"));
    }

    @Test
    public void knopfAmAutoUndTexteInFuenfSprachen() throws Exception {
        String s = lies("local/system.html");
        assertTrue(s.contains("data-reinigung data-nur-display"));
        assertTrue(s.contains("../shared/reinigung.js"));
        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject g = new JSONObject(lies("i18n/" + l + ".json")).getJSONObject("reinigung");
            for (String k : new String[] {"titel", "text", "beenden", "nur_p", "hilfe", "knopf"}) {
                assertTrue(l + " " + k, g.optString(k).length() > 3);
            }
            assertTrue(l, g.getString("text").contains("{n}"));
        }
    }
}
