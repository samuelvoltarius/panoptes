package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 0.1.40 (L2 „Launcher selbst gestalten“): Der Kern reicht das eigene Design geprüft durch (sonst ginge es beim
 * Abgleich verloren) und kennt die neuen Kacheln und den Hintergrund „eigen“.
 */
public class LauncherGestaltenKernTest {

    private static final Set<String> APPS = new HashSet<>(Arrays.asList("at.greenchili.surfmate"));

    private static JSONObject basis() throws Exception {
        JSONArray seite = new JSONArray()
                .put(new JSONObject().put("id", "w1").put("typ", "widget").put("kachel", "schloss").put("x", 0).put("y", 0).put("b", 2).put("h", 1))
                .put(new JSONObject().put("id", "w2").put("typ", "widget").put("kachel", "laden").put("x", 2).put("y", 0).put("b", 2).put("h", 2))
                .put(new JSONObject().put("id", "w3").put("typ", "widget").put("kachel", "kamera").put("x", 4).put("y", 0).put("b", 1).put("h", 1));
        return new JSONObject().put("format", "panoptes-launcher-layout").put("version", 1)
                .put("seiten", new JSONArray().put(seite)).put("dock", new JSONArray()).put("hintergrund", "eigen").put("zaehler", 3);
    }

    @Test
    public void neueKachelnUndEigenesBildBleiben() throws Exception {
        LauncherLayoutPruefung.Ergebnis e = LauncherLayoutPruefung.pruefen(basis(), APPS);
        assertNotNull(e);
        assertEquals(0, e.bereinigt);
        assertEquals(3, e.layout.getJSONArray("seiten").getJSONArray(0).length());
        assertEquals("eigen", e.layout.getString("hintergrund"));
    }

    @Test
    public void designWirdGeprueftDurchgereicht() throws Exception {
        JSONObject roh = basis().put("design", "nacht").put("akzent", "#ffd400").put("abstand", "weit")
                .put("spalte", new JSONArray().put("uhr").put("akku").put("passwort"));
        JSONObject o = LauncherLayoutPruefung.pruefen(roh, APPS).layout;
        assertEquals("nacht", o.getString("design"));
        assertEquals("#FFD400", o.getString("akzent"));
        assertEquals("weit", o.getString("abstand"));
        assertEquals("nur bekannte Teile, feste Reihenfolge", "[\"akku\",\"uhr\"]", o.getJSONArray("spalte").toString());

        JSONObject falsch = LauncherLayoutPruefung.pruefen(basis().put("design", "fremd").put("akzent", "rot").put("abstand", "x"), APPS).layout;
        assertEquals("auto", falsch.getString("design"));
        assertEquals("", falsch.getString("akzent"));
        assertEquals("normal", falsch.getString("abstand"));
        assertEquals("ohne Angabe die Standard-Spalte", LauncherLayoutPruefung.SPALTE_STANDARD.size(), falsch.getJSONArray("spalte").length());
    }
}
