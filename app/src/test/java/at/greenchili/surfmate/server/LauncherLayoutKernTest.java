package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** 0.1.38 Launcher-Layout im Kern (LAUNCHER_LAYOUT_API.md): Prüfung wie Raster.fromJson, Speicher, Konflikte. */
public class LauncherLayoutKernTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final Set<String> APPS = new HashSet<>(Arrays.asList("at.greenchili.surfmate", "org.example.karte",
            "org.example.musik"));

    private static JSONObject w(String id, String k, int x, int y, int b, int h) throws Exception {
        return new JSONObject().put("id", id).put("typ", "widget").put("kachel", k).put("x", x).put("y", y).put("b", b).put("h", h);
    }

    private static JSONObject app(String id, String p, int x, int y) throws Exception {
        return new JSONObject().put("id", id).put("typ", "app").put("paket", p).put("x", x).put("y", y);
    }

    private static JSONObject layout(JSONArray seite) throws Exception {
        return new JSONObject().put("format", "panoptes-launcher-layout").put("version", 1)
                .put("seiten", new JSONArray().put(seite)).put("dock", new JSONArray().put("at.greenchili.surfmate"))
                .put("hintergrund", "panoptes").put("zaehler", 3);
    }

    // ---------- Prüfung ----------

    @Test
    public void falschesFormatOderVersionIstUngueltig() throws Exception {
        assertNull(LauncherLayoutPruefung.pruefen(new JSONObject().put("format", "x").put("version", 1), APPS));
        assertNull(LauncherLayoutPruefung.pruefen(layout(new JSONArray()).put("version", 2), APPS));
        assertNull(LauncherLayoutPruefung.pruefen(null, APPS));
    }

    @Test
    public void bereinigtStattAblehnen() throws Exception {
        JSONArray s = new JSONArray()
                .put(w("w1", "fahrzeug", 0, 0, 4, 3))
                .put(w("w2", "fahrzeug", 4, 0, 1, 1))     // doppeltes Widget
                .put(w("w3", "rakete", 4, 0, 1, 1))       // unbekannte Kachel
                .put(w("w4", "akku", 4, 0, 5, 1))         // Größe nicht in der Liste
                .put(w("w5", "uhr", 3, 0, 2, 1))          // überlappt
                .put(app("a6", "org.example.karte", 4, 0))
                .put(app("a7", "org.fremd.nichtda", 5, 0)) // nicht installiert
                .put(app("a8", "kein paket", 5, 1))
                .put(w("w9", "musik", 5, 2, 1, 1))
                .put(app("w1", "org.example.musik", 4, 2));   // doppelte id
        JSONObject roh = layout(s);
        roh.put("dock", new JSONArray().put("at.greenchili.surfmate").put("com.byd.androidauto").put("com.byd.carplay.ui")
                .put("org.fremd.nichtda").put("at.greenchili.surfmate"));
        roh.put("hintergrund", "disco");
        LauncherLayoutPruefung.Ergebnis e = LauncherLayoutPruefung.pruefen(roh, APPS);
        assertNotNull(e);
        JSONArray seite = e.layout.getJSONArray("seiten").getJSONArray(0);
        assertEquals(3, seite.length());
        assertEquals("w1", seite.getJSONObject(0).getString("id"));
        assertEquals("a6", seite.getJSONObject(1).getString("id"));
        assertEquals("w9", seite.getJSONObject(2).getString("id"));
        // Android Auto und CarPlay dürfen im Dock stehen, auch wenn sie fehlen
        assertEquals(new JSONArray().put("at.greenchili.surfmate").put("com.byd.androidauto").put("com.byd.carplay.ui").toString(),
                e.layout.getJSONArray("dock").toString());
        assertEquals("panoptes", e.layout.getString("hintergrund"));
        assertEquals(10, e.bereinigt);
        assertEquals(9, e.layout.getInt("zaehler"));
    }

    @Test
    public void grenzenWieImLauncher() throws Exception {
        JSONArray seiten = new JSONArray();
        for (int i = 0; i < 7; i++) seiten.put(new JSONArray());
        JSONArray ordnerApps = new JSONArray();
        for (int i = 0; i < 30; i++) ordnerApps.put("org.example.a" + i);
        seiten.getJSONArray(0).put(new JSONObject().put("id", "o1").put("typ", "ordner").put("x", 0).put("y", 0)
                .put("name", "\u0007Ein sehr langer Ordnername, der abgeschnitten wird").put("apps", ordnerApps));
        JSONArray dock = new JSONArray();
        for (int i = 0; i < 12; i++) dock.put("org.example.d" + i);
        JSONObject roh = layout(new JSONArray()).put("seiten", seiten).put("dock", dock);
        LauncherLayoutPruefung.Ergebnis e = LauncherLayoutPruefung.pruefen(roh, null);   // Liste unbekannt: nur Form
        assertEquals(5, e.layout.getJSONArray("seiten").length());
        JSONObject o = e.layout.getJSONArray("seiten").getJSONArray(0).getJSONObject(0);
        assertEquals(24, o.getJSONArray("apps").length());
        assertEquals(24, o.getString("name").length());
        assertFalse(o.getString("name").contains("\u0007"));
        assertEquals(8, e.layout.getJSONArray("dock").length());
    }

    @Test
    public void standardWieRasterStandard() throws Exception {
        JSONObject s = LauncherLayoutPruefung.standard(null);
        assertEquals(2, s.getJSONArray("seiten").length());
        assertEquals("fahrzeug", s.getJSONArray("seiten").getJSONArray(0).getJSONObject(0).getString("kachel"));
        assertEquals(4, s.getJSONArray("seiten").getJSONArray(0).getJSONObject(0).getInt("b"));
        assertEquals(new JSONArray().put("at.greenchili.surfmate").put("com.byd.androidauto").put("com.byd.carplay.ui").toString(),
                s.getJSONArray("dock").toString());
        assertEquals("panoptes", s.getString("hintergrund"));
        assertEquals("cockpit", LauncherLayoutPruefung.standard("cockpit").getString("hintergrund"));
        assertEquals(2, LauncherLayoutPruefung.standard("kompakt").getJSONArray("seiten").getJSONArray(1).length());
        assertEquals("navi", LauncherLayoutPruefung.standard("fahren").getJSONArray("seiten").getJSONArray(1)
                .getJSONObject(0).getString("kachel"));
        // Der Standard besteht die eigene Prüfung ohne Abzug
        assertEquals(0, LauncherLayoutPruefung.pruefen(s, null).bereinigt);
    }

    // ---------- Speicher ----------

    private String body(JSONObject l, long basis) throws Exception {
        return new JSONObject().put("layout", l).put("basis", basis).toString();
    }

    @Test
    public void ohneDateiGiltDerStandard() throws Exception {
        File f = new File(tmp.getRoot(), LauncherLayoutSpeicher.DATEI);
        JSONObject s = LauncherLayoutSpeicher.lesen(f, null);
        assertTrue(s.getBoolean("standard"));
        assertEquals(0L, s.getLong("geaendert"));
        assertEquals("panoptes-launcher-layout", s.getJSONObject("layout").getString("format"));
        assertFalse(f.exists());
        assertEquals(0L, LauncherLayoutSpeicher.geaendert(f));
    }

    @Test
    public void schreibenUndNeuesteGewinnt() throws Exception {
        File f = new File(tmp.getRoot(), LauncherLayoutSpeicher.DATEI);
        JSONObject l = layout(new JSONArray().put(w("w1", "uhr", 0, 0, 2, 1)));
        LauncherLayoutSpeicher.Antwort a = LauncherLayoutSpeicher.schreiben(f, body(l, 0), APPS, "web", 1000L);
        assertEquals(200, a.status);
        assertEquals(1000L, a.koerper.getLong("geaendert"));
        JSONObject gelesen = LauncherLayoutSpeicher.lesen(f, null);
        assertEquals("web", gelesen.getString("quelle"));
        assertFalse(gelesen.getBoolean("standard"));
        assertFalse(new File(tmp.getRoot(), LauncherLayoutSpeicher.DATEI + ".tmp").exists());

        // Launcher kannte 0 → Kern ist neuer → 409 mit aktuellem Stand
        LauncherLayoutSpeicher.Antwort alt = LauncherLayoutSpeicher.schreiben(f, body(LauncherLayoutPruefung.standard(null), 0),
                APPS, "auto", 2000L);
        assertEquals(409, alt.status);
        assertEquals(1000L, alt.koerper.getLong("geaendert"));
        assertEquals("uhr", alt.koerper.getJSONObject("layout").getJSONArray("seiten").getJSONArray(0).getJSONObject(0)
                .getString("kachel"));
        // mit der neuen Basis geht es, und die Zeit wächst immer
        LauncherLayoutSpeicher.Antwort neu = LauncherLayoutSpeicher.schreiben(f, body(l, 1000L), APPS, "auto", 500L);
        assertEquals(200, neu.status);
        assertEquals(1001L, neu.koerper.getLong("geaendert"));
        assertEquals("auto", LauncherLayoutSpeicher.lesen(f, null).getString("quelle"));
    }

    @Test
    public void ablehnungen() throws Exception {
        File f = new File(tmp.getRoot(), LauncherLayoutSpeicher.DATEI);
        assertEquals(400, LauncherLayoutSpeicher.schreiben(f, "kaputt", APPS, "web", 1L).status);
        assertEquals(400, LauncherLayoutSpeicher.schreiben(f, "{\"basis\":0}", APPS, "web", 1L).status);
        assertEquals(400, LauncherLayoutSpeicher.schreiben(f, new JSONObject().put("layout", layout(new JSONArray())).toString(),
                APPS, "web", 1L).status);
        assertEquals(400, LauncherLayoutSpeicher.schreiben(f, body(new JSONObject().put("format", "x"), 0), APPS, "web", 1L).status);
        StringBuilder gross = new StringBuilder();
        while (gross.length() <= LauncherLayoutSpeicher.MAX_BYTES) gross.append("xxxxxxxxxx");
        JSONObject l = layout(new JSONArray()).put("muell", gross.toString());
        LauncherLayoutSpeicher.Antwort a = LauncherLayoutSpeicher.schreiben(f, body(l, 0), APPS, "web", 1L);
        assertEquals(413, a.status);
        assertFalse(f.exists());
        // unbekannte Quelle wird „auto“
        assertEquals("auto", LauncherLayoutSpeicher.quelle("telegram"));
    }

    @Test
    public void kaputteDateiGiltAlsStandard() throws Exception {
        File f = new File(tmp.getRoot(), LauncherLayoutSpeicher.DATEI);
        Files.write(f.toPath(), "{nicht json".getBytes(StandardCharsets.UTF_8));
        assertTrue(LauncherLayoutSpeicher.lesen(f, null).getBoolean("standard"));
        assertEquals(200, LauncherLayoutSpeicher.schreiben(f, body(layout(new JSONArray()), 0), APPS, "web", 5L).status);
    }

    @Test
    public void zurueckAufStandardBleibtGespeichert() throws Exception {
        File f = new File(tmp.getRoot(), LauncherLayoutSpeicher.DATEI);
        LauncherLayoutSpeicher.schreiben(f, body(layout(new JSONArray()), 0), APPS, "web", 1000L);
        LauncherLayoutSpeicher.Antwort a = LauncherLayoutSpeicher.standard(f, "kompakt", "handy", 10L);
        assertEquals(200, a.status);
        assertEquals(1001L, a.koerper.getLong("geaendert"));
        JSONObject s = LauncherLayoutSpeicher.lesen(f, null);
        assertTrue(s.getBoolean("standard"));
        assertEquals(1001L, s.getLong("geaendert"));   // ≠ 0: ein Launcher mit altem lokalem Layout lädt es nicht wieder hoch
        assertEquals("handy", s.getString("quelle"));
    }

    // ---------- Apps ----------

    @Test
    public void appListeOhneLauncherMitVideo() throws Exception {
        JSONArray roh = new JSONArray()
                .put(new JSONObject().put("package", LauncherApiHandler.LAUNCHER_PAKET).put("label", "Launcher"))
                .put(new JSONObject().put("package", "com.google.android.youtube").put("label", "YouTube"))
                .put(new JSONObject().put("package", "org.example.film").put("label", "Film").put("video", true))
                .put(new JSONObject().put("package", "org.example.karte").put("label", "Karte"))
                .put(new JSONObject().put("package", "kaputt").put("label", "x"));
        JSONArray a = LauncherApiHandler.layoutApps(roh);
        assertEquals(3, a.length());
        assertEquals("com.google.android.youtube", a.getJSONObject(0).getString("paket"));
        assertTrue(a.getJSONObject(0).getBoolean("video"));
        assertTrue(a.getJSONObject(1).getBoolean("video"));
        assertFalse(a.getJSONObject(2).getBoolean("video"));
        assertEquals("Karte", a.getJSONObject(2).getString("name"));
    }
}
