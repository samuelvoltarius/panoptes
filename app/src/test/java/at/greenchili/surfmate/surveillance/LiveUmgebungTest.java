package at.greenchili.surfmate.surveillance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;

import at.greenchili.surfmate.ai.Detection;

/** 0.1.44 (L1): Vertrag der Live-Umgebung (Kern): Skizzen-Daten, Leistung, Waerme, Vorrang Waechter, nichts speichern. */
public class LiveUmgebungTest {

    private static Detection d(int cls, int x, int y, int w, int h) {
        return new Detection(cls, 0.8f, x, y, w, h);
    }

    @Test
    public void mosaikWirdZuSeiteZoneQuerUndTyp() throws Exception {
        // 640×480: Q0 vorn (oben links), Q1 rechts, Q2 hinten (unten links), Q3 links
        JSONArray a = LiveUmgebung.objekte(Arrays.asList(
                d(2, 10, 20, 60, 110),      // vorn, links im Bild, 110/240 = 0,46 → nah
                d(0, 500, 60, 20, 50),      // rechts, mittleres Drittel? x-Mitte 510-320=190/320=0,59 → 0; 50/240 → mittel
                d(7, 250, 300, 60, 30),     // hinten, rechtes Drittel, 30/240 → fern
                d(1, 330, 250, 20, 50),     // links (Q3), linkes Drittel
                d(16, 100, 100, 30, 30),    // Hund: kein Skizzen-Typ
                d(2, 300, 100, 60, 60)      // ueber der Grenze vorn/rechts → weg
        ), 640, 480);
        assertEquals(4, a.length());
        JSONObject o0 = a.getJSONObject(0);
        assertEquals("front", o0.getString("seite"));
        assertEquals("nah", o0.getString("zone"));
        assertEquals(-1, o0.getInt("quer"));
        assertEquals("auto", o0.getString("typ"));
        assertEquals(Arrays.asList("seite", "quer", "zone", "typ"), keys(o0));
        boolean rechts = false, hinten = false, links = false;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            if (o.getString("seite").equals("right")) { rechts = true; assertEquals("person", o.getString("typ")); assertEquals("mittel", o.getString("zone")); assertEquals(0, o.getInt("quer")); }
            if (o.getString("seite").equals("rear")) { hinten = true; assertEquals("lkw", o.getString("typ")); assertEquals("fern", o.getString("zone")); assertEquals(1, o.getInt("quer")); }
            if (o.getString("seite").equals("left")) { links = true; assertEquals("rad", o.getString("typ")); assertEquals(-1, o.getInt("quer")); }
        }
        assertTrue(rechts && hinten && links);
        assertEquals("nah zuerst", "nah", a.getJSONObject(0).getString("zone"));
        assertEquals(0, LiveUmgebung.objekte(null, 640, 480).length());
        assertEquals(0, LiveUmgebung.objekte(Collections.singletonList(d(2, 0, 0, 10, 10)), 0, 0).length());
    }

    @Test
    public void hoechstensVierundzwanzigObjekte() {
        Detection[] viele = new Detection[40];
        for (int i = 0; i < viele.length; i++) viele[i] = d(0, 5 + i, 5, 4, 30);
        assertEquals(24, LiveUmgebung.objekte(Arrays.asList(viele), 640, 480).length());
    }

    @Test
    public void zonenDrittelTypen() {
        assertEquals("nah", LiveUmgebung.zone(0.40f));
        assertEquals("mittel", LiveUmgebung.zone(0.39f));
        assertEquals("mittel", LiveUmgebung.zone(0.18f));
        assertEquals("fern", LiveUmgebung.zone(0.17f));
        assertEquals(-1, LiveUmgebung.drittel(0.2f));
        assertEquals(0, LiveUmgebung.drittel(0.5f));
        assertEquals(1, LiveUmgebung.drittel(0.8f));
        assertEquals("person", LiveUmgebung.typ(0));
        assertEquals("rad", LiveUmgebung.typ(1));
        assertEquals("rad", LiveUmgebung.typ(3));
        assertEquals("auto", LiveUmgebung.typ(2));
        assertEquals("lkw", LiveUmgebung.typ(5));
        assertEquals("lkw", LiveUmgebung.typ(7));
        assertNull(LiveUmgebung.typ(16));
    }

    @Test
    public void leistungEhrlichUndStatus() {
        assertEquals("startet", LiveUmgebung.leistung(0));
        assertEquals("leistung", LiveUmgebung.leistung(1.9));
        assertEquals("verzoegert", LiveUmgebung.leistung(2.0));
        assertEquals("verzoegert", LiveUmgebung.leistung(3.9));
        assertEquals("live", LiveUmgebung.leistung(4.0));
        assertEquals(2.0, LiveUmgebung.MIN_HZ, 0);
        assertEquals("hoechstens 5 Laeufe/s", 200L, LiveUmgebung.MIN_ABSTAND_MS);
        assertEquals(2, LiveUmgebung.THREADS);

        assertEquals("aus", LiveUmgebung.status(false, false, false, false, 10, 5, 0));
        assertEquals("modell", LiveUmgebung.status(true, true, false, false, 10, 5, 0));
        assertEquals("leistung", LiveUmgebung.status(true, false, true, false, 10, 5, 0));
        assertEquals("warm", LiveUmgebung.status(true, false, false, true, 10, 5, 0));
        assertEquals("startet", LiveUmgebung.status(true, false, false, false, -1, 0, 0));
        assertEquals("altes Ergebnis = keine Erkennung, nie frei", "keine_erkennung",
                LiveUmgebung.status(true, false, false, false, 1_501, 5, 0));
        assertEquals("live", LiveUmgebung.status(true, false, false, false, 100, 4.5, 0));
        assertEquals("verzoegert", LiveUmgebung.status(true, false, false, false, 100, 2.5, 0));
    }

    @Test
    public void ohneErstesErgebnisNachFristKeineErkennung() {
        // E2E 0.1.43b M3: ohne Kamera kommt nie ein Ergebnis – nicht endlos „Erkennung startet …“.
        assertEquals(8_000L, LiveUmgebung.START_FRIST_MS);
        assertEquals("startet", LiveUmgebung.status(true, false, false, false, -1, 0, 0));
        assertEquals("startet", LiveUmgebung.status(true, false, false, false, -1, 0, 7_999));
        assertEquals("keine_erkennung", LiveUmgebung.status(true, false, false, false, -1, 0, 8_000));
        assertEquals("keine_erkennung", LiveUmgebung.status(true, false, false, false, -1, 0, 120_000));
        assertEquals("Sperren gehen vor", "aus", LiveUmgebung.status(false, false, false, false, -1, 0, 120_000));
        assertEquals("warm", LiveUmgebung.status(true, false, false, true, -1, 0, 120_000));
        assertEquals("frisches Ergebnis zaehlt, Frist egal", "live", LiveUmgebung.status(true, false, false, false, 100, 4.5, 120_000));
    }

    @Test
    public void waermeMitAbstand() {
        assertTrue(LiveUmgebung.warmNeu(false, 75));
        assertFalse(LiveUmgebung.warmNeu(false, 74));
        assertTrue("bleibt warm bis 68", LiveUmgebung.warmNeu(true, 70));
        assertFalse(LiveUmgebung.warmNeu(true, 68));
        assertTrue("unbekannt: Zustand bleibt", LiveUmgebung.warmNeu(true, null));
        assertEquals(Integer.valueOf(48), LiveUmgebung.grad("48000"));
        assertEquals(Integer.valueOf(52), LiveUmgebung.grad("52\n"));
        assertNull(LiveUmgebung.grad("-5000"));
        assertNull(LiveUmgebung.grad("999000"));
        assertNull(LiveUmgebung.grad("x"));
        assertNull(LiveUmgebung.grad(null));
    }

    @Test
    public void vorrangWaechterGleicheKameraNichtsGespeichert() throws Exception {
        String gl = lies("app/src/main/java/at/greenchili/surfmate/camera/AiLaneGl.java");
        assertTrue(gl.contains("boolean fuerWaechter = sentry != null && sentry.isActive();"));
        assertTrue(gl.contains("boolean fuerLive = !fuerWaechter"));
        assertTrue(gl.contains("LiveUmgebung.willBild()"));
        assertTrue("Puffer sofort zurueck", gl.contains("aiLaneWorker.zurueckgeben(liveFrame);"));
        String pano = lies("app/src/main/java/at/greenchili/surfmate/camera/PanoramicCameraGpu.java");
        assertTrue(pano.contains("|| at.greenchili.surfmate.surveillance.LiveUmgebung.benoetigt();"));
        String lu = lies("app/src/main/java/at/greenchili/surfmate/surveillance/LiveUmgebung.java");
        for (String v : new String[] {"FileOutputStream", "FileWriter", "Bitmap", "compress(", "AVMCamera", "openCamera",
                "sendBroadcast", "Vibrator", "AudioTrack", "ToneGenerator"})
            assertFalse("Live-Umgebung: " + v, lu.contains(v));
        assertTrue("ab Werk aus", lu.contains("l.optBoolean(SCHALTER, false)"));
        assertTrue("leise", lu.contains("neu.setLeise(true);"));
        assertTrue(lu.contains("System.arraycopy(mosaik, 0, b, 0, n);"));
        String yolo = lies("app/src/main/java/at/greenchili/surfmate/ai/YoloDetector.kt");
        assertTrue(yolo.contains("if (!leise) logger.info(\"Detected ${final.size} objects"));
        assertTrue(yolo.contains("cpuOptions.setNumThreads(cpuThreads.coerceIn(1, 4))"));
        assertTrue("Waechter unveraendert 4 Threads", yolo.contains("private val cpuThreads: Int = 4"));
    }

    @Test
    public void schnittstelleNurLauncherApk() throws Exception {
        String h = lies("app/src/main/java/at/greenchili/surfmate/server/LauncherApiHandler.java");
        String teil = h.substring(h.indexOf("if (cleanPath.equals(UMGEBUNG_PFAD)) {"), h.indexOf("if (cleanPath.equals(FENSTER_PFAD) && \"POST\".equals(method)) {"));
        assertEquals("GET und POST pruefen die Launcher-APK", 2, teil.split("if \\(!launcherDarf\\(out\\)\\) return true;", -1).length - 1);
        assertTrue(h.contains("static final String UMGEBUNG_PFAD = \"/api/launcher/v1/umgebung\";"));
        assertTrue(lies("app/src/main/java/at/greenchili/surfmate/server/LauncherLayoutPruefung.java").contains("\"umgebung\"));"));
    }

    private static java.util.List<String> keys(JSONObject o) {
        java.util.List<String> l = new java.util.ArrayList<>();
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) l.add(it.next());
        Collections.sort(l, (a, b) -> Integer.compare(Arrays.asList("seite", "quer", "zone", "typ").indexOf(a),
                Arrays.asList("seite", "quer", "zone", "typ").indexOf(b)));
        return l;
    }

    private static String lies(String relative) throws Exception {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8).replace("\r\n", "\n");
            }
            current = at.greenchili.surfmate.testutil.RepoWurzel.hoch(current);
        }
        throw new IllegalStateException("missing " + relative);
    }
}
