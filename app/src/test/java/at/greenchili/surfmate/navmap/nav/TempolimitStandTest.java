package at.greenchili.surfmate.navmap.nav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.einstellungen.Feld;
import at.greenchili.surfmate.einstellungen.Katalog;
import at.greenchili.surfmate.testutil.Quelltext;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.nio.file.Paths;

/**
 * 0.1.44 (P3) Vertrag Kern: Tempolimit an EINER Stelle, nur belegt (Quelle osm) und frisch; summary.tempolimit = null
 * sonst; Einstellung launcher.tempoWarnung ab Werk aus, über Kopplung nur lesen, Texte 5 Sprachen, gezeichnet.
 */
public class TempolimitStandTest {

    @After
    public void weg() {
        TempolimitStand.loeschen();
    }

    @Test
    public void nurBelegtUndFrisch() throws Exception {
        assertNull(TempolimitStand.json(1000));
        TempolimitStand.setze(50, TempolimitStand.QUELLE_OSM, 1000);
        JSONObject j = TempolimitStand.json(3000);
        assertEquals(50, j.getInt("kmh"));
        assertEquals("osm", j.getString("quelle"));
        assertEquals(2000L, j.getLong("alterMs"));
        assertNull("zu alt", TempolimitStand.json(1000 + TempolimitStand.FRISCH_MS + 1));
        assertNull("Uhr rückwärts", TempolimitStand.json(500));
        TempolimitStand.setze(50, "geraten", 4000);
        assertNull("fremde Quelle löscht", TempolimitStand.json(4000));
        TempolimitStand.setze(0, TempolimitStand.QUELLE_OSM, 5000);
        assertNull("0 = kein Limit", TempolimitStand.json(5000));
    }

    @Test
    public void summaryUndEinstellung() throws Exception {
        String h = Quelltext.lies(Quelltext.aufloesen(Paths.get("src/main/java/at/greenchili/surfmate/server/LauncherApiHandler.java")));
        assertTrue(h.contains("root.put(\"tempolimit\", limit == null ? JSONObject.NULL : limit);"));
        assertTrue(h.contains("root.put(\"tempoWarnung\", tempoWarnung());"));
        Feld f = null;
        for (Feld x : Katalog.felder(new Katalog.Kontext())) if ("launcher.tempoWarnung".equals(x.key)) f = x;
        assertTrue(f != null);
        assertEquals(Boolean.FALSE, f.standard);
        assertTrue(f.nurLesendExtern);
        assertFalse(f.verborgen);
        for (String sp : new String[]{"de", "en", "fr", "it", "nl"}) {
            JSONObject t = new JSONObject(Quelltext.lies(Quelltext.aufloesen(Paths.get("src/main/assets/server-i18n/" + sp + ".json"))))
                    .getJSONObject("settings").getJSONObject("launcher").getJSONObject("tempoWarnung");
            assertFalse(sp, t.getString("title").trim().isEmpty());
            assertTrue(sp, t.getString("help").contains("OpenStreetMap"));
        }
        assertTrue(Quelltext.lies(Quelltext.aufloesen(Paths.get("src/main/assets/web/local/display.html")))
                .contains("'launcher.tempoWarnung'"));
    }
}
