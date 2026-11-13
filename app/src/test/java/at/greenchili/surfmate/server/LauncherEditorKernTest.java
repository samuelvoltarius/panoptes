package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Collections;

/** 0.1.44 (E2 freier Editor): Kern-Abgleich kennt Version 2 (feine Lage, Stil, Text, Dock-/Reiterleiste). */
public class LauncherEditorKernTest {

    private static JSONObject roh(JSONArray seite, int version) throws Exception {
        return new JSONObject().put("format", "panoptes-launcher-layout").put("version", version)
                .put("seiten", new JSONArray().put(seite)).put("dock", new JSONArray()).put("hintergrund", "panoptes").put("zaehler", 9);
    }

    private static JSONObject frei(String id, String typ, int fx, int fy, int fb, int fh) throws Exception {
        JSONObject o = new JSONObject().put("id", id).put("typ", typ).put("x", 0).put("y", 0).put("b", 1).put("h", 1)
                .put("fx", fx).put("fy", fy).put("fb", fb).put("fh", fh);
        if ("widget".equals(typ)) o.put("kachel", "uhr");
        if ("text".equals(typ)) o.put("name", "Hallo");
        return o;
    }

    private static LauncherLayoutPruefung.Ergebnis pruefe(JSONArray seite, int version) throws Exception {
        return LauncherLayoutPruefung.pruefen(roh(seite, version), Collections.<String>emptySet());
    }

    @Test
    public void version2MitFeinerLageUndStil() throws Exception {
        assertEquals(2, LauncherLayoutPruefung.VERSION);
        JSONObject t = frei("t1", "text", 10, 2, 9, 3).put("ecke", 12).put("deckung", 70).put("unschaerfe", 2).put("akzent", "#b5f23d");
        LauncherLayoutPruefung.Ergebnis e = pruefe(new JSONArray().put(t), 2);
        assertNotNull(e);
        assertEquals(0, e.bereinigt);
        JSONObject o = e.layout.getJSONArray("seiten").getJSONArray(0).getJSONObject(0);
        assertEquals(10, o.getInt("fx")); assertEquals(9, o.getInt("fb"));
        assertEquals("umfassende Zellen neu berechnet", 1, o.getInt("x")); assertEquals(2, o.getInt("b")); assertEquals(1, o.getInt("h"));
        assertEquals(12, o.getInt("ecke")); assertEquals(70, o.getInt("deckung")); assertEquals(2, o.getInt("unschaerfe"));
        assertEquals("#B5F23D", o.getString("akzent"));
        assertEquals(2, e.layout.getInt("version"));
    }

    @Test
    public void ungueltigesFaelltWeg() throws Exception {
        // zu klein (Kachel unter 8 × 6), über den Rand, Text ohne feine Lage
        JSONArray s = new JSONArray().put(frei("w1", "widget", 0, 0, 6, 6)).put(frei("w2", "widget", 44, 0, 8, 6))
                .put(new JSONObject().put("id", "t3").put("typ", "text").put("x", 0).put("y", 0).put("b", 1).put("h", 1).put("name", "x"));
        LauncherLayoutPruefung.Ergebnis e = pruefe(s, 2);
        assertEquals(0, e.layout.getJSONArray("seiten").getJSONArray(0).length());
        assertEquals(3, e.bereinigt);
        // ungültiger Stil fällt weg, das Element bleibt
        JSONObject k = frei("w4", "widget", 0, 0, 8, 6).put("deckung", 10).put("ecke", 99).put("unschaerfe", 7).put("akzent", "rot");
        JSONObject o = pruefe(new JSONArray().put(k), 2).layout.getJSONArray("seiten").getJSONArray(0).getJSONObject(0);
        assertFalse(o.has("deckung") || o.has("ecke") || o.has("unschaerfe") || o.has("akzent"));
        assertNull("unbekannte Version", pruefe(new JSONArray(), 3));
    }

    @Test
    public void ueberlappenInFeinenEinheiten() throws Exception {
        // zwei halbe Zellen nebeneinander: erlaubt; überlappend: das zweite fällt weg
        JSONArray s = new JSONArray().put(frei("t1", "text", 0, 0, 4, 2)).put(frei("t2", "text", 4, 0, 4, 2)).put(frei("t3", "text", 6, 1, 4, 2));
        LauncherLayoutPruefung.Ergebnis e = pruefe(s, 2);
        assertEquals(2, e.layout.getJSONArray("seiten").getJSONArray(0).length());
        assertEquals(1, e.bereinigt);
        // altes Zell-Element und feines Element in derselben Zelle: überlappt
        JSONArray m = new JSONArray().put(new JSONObject().put("id", "w1").put("typ", "widget").put("kachel", "akku").put("x", 0).put("y", 0).put("b", 2).put("h", 1))
                .put(frei("t2", "text", 8, 2, 4, 2));
        assertEquals(1, pruefe(m, 2).layout.getJSONArray("seiten").getJSONArray(0).length());
    }

    @Test
    public void alteVersion1UndNeueLeisten() throws Exception {
        JSONArray s = new JSONArray().put(new JSONObject().put("id", "w1").put("typ", "widget").put("kachel", "fahrzeug").put("x", 0).put("y", 0).put("b", 4).put("h", 3));
        LauncherLayoutPruefung.Ergebnis e = pruefe(s, 1);
        assertEquals(1, e.layout.getJSONArray("seiten").getJSONArray(0).length());
        assertEquals(0, e.bereinigt);
        assertTrue(LauncherLayoutPruefung.KACHELN.contains("dockleiste"));
        assertTrue(LauncherLayoutPruefung.KACHELN.contains("reiterleiste"));
        JSONObject dock = frei("w2", "widget", 0, 19, 48, 5).put("kachel", "dockleiste");
        assertEquals(1, pruefe(new JSONArray().put(dock), 2).layout.getJSONArray("seiten").getJSONArray(0).length());
    }
}
