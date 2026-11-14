package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.ui.NavigateZiel;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** 0.1.43.4: Aufnahmen-Liste für den Launcher (nur lesen, nur Anzeigefelder) und die Deep-Links dazu. */
public class LauncherAufnahmenTest {

    private static JSONObject r(String id, String typ, long ts, long size, int cam, boolean da) throws Exception {
        return new JSONObject().put("id", id).put("type", typ).put("timestamp", ts).put("size", size)
                .put("cameraId", cam).put("available", da).put("path", "/geheim/pfad.mp4").put("startLat", 1.0);
    }

    @Test
    public void zeitUndSortierungNurAusFesterListe() {
        assertEquals("7", LauncherAufnahmen.zeit("7"));
        assertEquals("alle", LauncherAufnahmen.zeit("1; drop"));
        assertEquals("alle", LauncherAufnahmen.zeit(null));
        assertEquals("alt", LauncherAufnahmen.sortierung("alt"));
        assertEquals("neu", LauncherAufnahmen.sortierung("x"));
    }

    @Test
    public void zeitfenster() {
        long jetzt = 100L * 24 * 3600_000L;
        assertNull(LauncherAufnahmen.vonMs("alle", jetzt, 5L));
        assertEquals(Long.valueOf(5L), LauncherAufnahmen.vonMs("heute", jetzt, 5L));
        assertEquals(Long.valueOf(jetzt - 7L * 24 * 3600_000L), LauncherAufnahmen.vonMs("7", jetzt, 5L));
        assertEquals(Long.valueOf(jetzt - 30L * 24 * 3600_000L), LauncherAufnahmen.vonMs("30", jetzt, 5L));
    }

    @Test
    public void quelleUndAusloeser() {
        assertEquals("allein", LauncherAufnahmen.quelleArt("sentry"));
        assertEquals("allein", LauncherAufnahmen.quelleArt("proximity"));
        assertEquals("fahrt", LauncherAufnahmen.quelleArt("normal"));
        assertEquals("manuell", LauncherAufnahmen.ausloeser("replay"));
        assertEquals("auto", LauncherAufnahmen.ausloeser("ereignis"));
        assertNull(LauncherAufnahmen.ausloeser("normal"));
    }

    @Test
    public void antwortSortiertZaehltUndZeigtKeineGeheimnisse() throws Exception {
        List<JSONObject> l = new ArrayList<>();
        l.add(r("a", "normal", 100, 10, 3, true));
        l.add(r("b", "sentry", 300, 20, 1, false));
        l.add(r("c", "replay", 200, 30, 0, true));
        l.add(new JSONObject().put("type", "normal"));   // ohne Kennung: weg
        JSONObject neu = LauncherAufnahmen.antwort(l, "neu");
        assertTrue(neu.getBoolean("ok"));
        assertEquals(3, neu.getInt("gespeichert"));
        JSONArray a = neu.getJSONArray("aufnahmen");
        assertEquals("b", a.getJSONObject(0).getString("id"));
        assertEquals("a", a.getJSONObject(2).getString("id"));
        assertFalse(a.getJSONObject(0).optBoolean("da", true));
        assertEquals("a", LauncherAufnahmen.antwort(l, "alt").getJSONArray("aufnahmen").getJSONObject(0).getString("id"));
        String roh = neu.toString();
        assertFalse(roh.contains("geheim"));
        assertFalse(roh.contains("startLat"));
        assertFalse(roh.contains("path"));
    }

    @Test
    public void hoechstensMaxZeilenAberGesamtzahlStimmt() throws Exception {
        List<JSONObject> l = new ArrayList<>();
        for (int i = 0; i < LauncherAufnahmen.MAX_ZEILEN + 20; i++) l.add(r("x" + i, "normal", i, 1, 0, true));
        JSONObject o = LauncherAufnahmen.antwort(l, "neu");
        assertEquals(LauncherAufnahmen.MAX_ZEILEN + 20, o.getInt("gespeichert"));
        assertEquals(LauncherAufnahmen.MAX_ZEILEN, o.getInt("angezeigt"));
    }

    @Test
    public void wegIstNurLesenUndNurFuerDieLauncherApk() throws Exception {
        File f = new File("src/main/java/at/greenchili/surfmate/server/LauncherApiHandler.java");
        if (!f.isFile()) f = new File("app/src/main/java/at/greenchili/surfmate/server/LauncherApiHandler.java");
        String q = new String(Files.readAllBytes(f.toPath()), "UTF-8");
        int i = q.indexOf("LauncherAufnahmen.PFAD");
        assertTrue(i > 0);
        String block = q.substring(i, i + 700);
        assertTrue(block.contains("launcherDarf(out)"));
        assertTrue(block.contains("nur_lesen"));
    }

    @Test
    public void qrQuelleNurFuerGueltigeKennungenUndNurArtAufnahme() throws Exception {
        assertTrue(LauncherAufnahmen.idGueltig("rec_1-2.mp4"));
        assertFalse(LauncherAufnahmen.idGueltig("../etc/passwd"));
        assertFalse(LauncherAufnahmen.idGueltig(null));
        assertFalse(LauncherAufnahmen.idGueltig(""));
        assertNull(LauncherAufnahmen.quelle("../x"));
        assertNull(LauncherAufnahmen.quelle(null));
        File f = new File("src/main/java/at/greenchili/surfmate/server/DateiQrApiHandler.java");
        if (!f.isFile()) f = new File("app/src/main/java/at/greenchili/surfmate/server/DateiQrApiHandler.java");
        String q = new String(Files.readAllBytes(f.toPath()), "UTF-8");
        assertTrue(q.contains("\"aufnahme\".equals(art) ? LauncherAufnahmen.quelle(id) : null"));
    }

    @Test
    public void deepLinksKameraUndAufnahmeSindEngGeprueft() {
        assertEquals("/live-view?cam=3", NavigateZiel.livePfad("3"));
        assertEquals("/live-view", NavigateZiel.livePfad("9"));
        assertEquals("/live-view", NavigateZiel.livePfad("1; x"));
        assertEquals("/live-view", NavigateZiel.livePfad(null));
        assertEquals("/events?filter=all&id=abc_1.mp4", NavigateZiel.aufnahmePfadAlle("abc_1.mp4"));
        assertEquals("/events", NavigateZiel.aufnahmePfadAlle("../x"));
    }
}
