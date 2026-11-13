package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

/** 0.1.41 (LO): Kachel „Android-Widget“ (geraetewidget) mit gebundener Nummer wid, mehrfach erlaubt. */
public class LauncherGeraeteWidgetKernTest {

    private static JSONObject gw(String id, int x, int wid) throws Exception {
        JSONObject o = new JSONObject().put("id", id).put("typ", "widget").put("kachel", "geraetewidget")
                .put("x", x).put("y", 0).put("b", 2).put("h", 1);
        return wid > 0 ? o.put("wid", wid) : o;
    }

    private static JSONObject layout(JSONArray seite) throws Exception {
        return new JSONObject().put("format", "panoptes-launcher-layout").put("version", 1)
                .put("seiten", new JSONArray().put(seite)).put("dock", new JSONArray())
                .put("hintergrund", "panoptes").put("zaehler", 9);
    }

    @Test
    public void widBleibtMehrfachErlaubtOhneWidWeg() throws Exception {
        assertTrue(LauncherLayoutPruefung.KACHELN.contains("geraetewidget"));
        JSONArray s = new JSONArray().put(gw("w1", 0, 17)).put(gw("w2", 2, 18))
                .put(gw("w3", 4, 17))    // gleiche Nummer doppelt
                .put(gw("w4", 4, 0));    // ohne Nummer
        LauncherLayoutPruefung.Ergebnis e = LauncherLayoutPruefung.pruefen(layout(s), Collections.<String>emptySet());
        assertNotNull(e);
        JSONArray seite = e.layout.getJSONArray("seiten").getJSONArray(0);
        assertEquals(2, seite.length());
        assertEquals(17, seite.getJSONObject(0).getInt("wid"));
        assertEquals(18, seite.getJSONObject(1).getInt("wid"));
        assertEquals(2, e.bereinigt);
    }

    @Test
    public void webEditorKenntDieKachelLegtSieAberNichtAn() throws Exception {
        Path cur = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        String html = null;
        while (cur != null && html == null) {
            for (Path p : new Path[] {cur.resolve("src/main/assets/web/local/launcher.html"),
                    cur.resolve("app/src/main/assets/web/local/launcher.html")}) {
                if (Files.isRegularFile(p)) html = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            }
            cur = at.greenchili.surfmate.testutil.RepoWurzel.hoch(cur);
        }
        assertNotNull(html);
        assertTrue(html.contains("'tempo', 'geraetewidget', 'sprache'];"));   // 0.1.44 (P2): Kachel „Sprechen“ am Ende
        assertTrue(html.contains("k !== 'geraetewidget' && !hatWidget(k)"));
    }
}
