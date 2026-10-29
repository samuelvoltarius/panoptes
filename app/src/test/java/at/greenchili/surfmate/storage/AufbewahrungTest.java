package at.greenchili.surfmate.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Aufbewahrung 72 h / Behalten (Koordination 21.10.2026, RECHTSLAGE Nr. 7). */
public class AufbewahrungTest {

    private static final long H = 3_600_000L;
    private static final long NOW = 1_800_000_000_000L;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File clip(File dir, String name, long alterMs) throws Exception {
        File f = new File(dir, name);
        Files.write(f.toPath(), new byte[] {1});
        assertTrue(f.setLastModified(NOW - alterMs));
        return f;
    }

    @Test
    public void onlyOlderThanRetentionAndNotKept() throws Exception {
        File d = tmp.newFolder("SurfMate", "surveillance");
        File alt = clip(d, "event_20261001_100000.mp4", 100 * H);
        File aelter = clip(d, "event_20260930_100000.mp4", 120 * H);
        File jung = clip(d, "event_20261020_100000.mp4", 71 * H);
        File behalten = clip(d, "event_20260929_100000.mp4", 200 * H);
        Map<String, Long> keep = new HashMap<>();
        keep.put("event_20260929_100000", NOW - 150 * H);
        List<File> f = Aufbewahrung.faellig(Arrays.asList(alt, aelter, jung, behalten), NOW, 72 * H, keep);
        assertEquals("älteste zuerst, ohne jungen und behaltenen", Arrays.asList(aelter, alt), f);
        assertTrue("0 = aus", Aufbewahrung.faellig(Arrays.asList(alt), NOW, 0, keep).isEmpty());
    }

    @Test
    public void neverTouchesForeignFolders() throws Exception {
        File od = tmp.newFolder("Overdrive", "surveillance");
        File fremd = clip(od, "event_20261001_100000.mp4", 500 * H);
        assertTrue(Aufbewahrung.fremderOrdner(fremd));
        assertTrue(Aufbewahrung.faellig(Arrays.asList(fremd), NOW, 72 * H, new HashMap<>()).isEmpty());
    }

    @Test
    public void stemAndValidation() {
        assertEquals("event_20261021_021120", Aufbewahrung.stamm("event_20261021_021120.mp4"));
        assertEquals("trip_1", Aufbewahrung.stamm("trip_1.jsonl.gz"));
        assertTrue(Aufbewahrung.gueltigerStamm("dvr_20261021_021120"));
        assertFalse("keine Pfade", Aufbewahrung.gueltigerStamm("../config"));
        assertFalse(Aufbewahrung.gueltigerStamm("a/b"));
        assertFalse(Aufbewahrung.gueltigerStamm(""));
    }

    @Test
    public void configDefaultsAndOff() throws Exception {
        assertEquals("Alt-Installation (nicht gesetzt): keine Löschung nach Alter", 0, Aufbewahrung.maxAlterMs(new JSONObject()));
        assertEquals(0, Aufbewahrung.maxAlterMs(new JSONObject("{\"surveillance\":{\"surveillanceEnabled\":true}}")));
        assertEquals("neue Installation (Einrichtung setzt 72)", 72 * H,
                Aufbewahrung.maxAlterMs(new JSONObject("{\"surveillance\":{\"clipRetentionHours\":72}}")));
        assertEquals(0, Aufbewahrung.maxAlterMs(new JSONObject("{\"surveillance\":{\"clipRetentionHours\":0}}")));
        assertEquals(24 * H, Aufbewahrung.maxAlterMs(new JSONObject("{\"surveillance\":{\"clipRetentionHours\":24}}")));
        assertEquals(30, Aufbewahrung.behaltenTage(new JSONObject()));
        assertEquals(90, Aufbewahrung.behaltenTage(new JSONObject("{\"surveillance\":{\"keepDays\":400}}")));
    }

    @Test
    public void keepListWithReminderAfterKeepDays() throws Exception {
        JSONObject root = new JSONObject();
        root.put("aufbewahrung", Aufbewahrung.mitAenderung(root, "event_a", NOW - 31L * 24 * H));
        root.put("aufbewahrung", Aufbewahrung.mitAenderung(root, "event_b", NOW - H));
        root.put("aufbewahrung", Aufbewahrung.mitAenderung(root, "../boese", NOW));   // wird beim Lesen verworfen
        JSONArray l = Aufbewahrung.liste(root, NOW);
        assertEquals(2, l.length());
        assertEquals("event_a", l.getJSONObject(0).getString("datei"));
        assertTrue("nach 30 Tagen Rückfrage, nicht löschen", l.getJSONObject(0).getBoolean("rueckfrage"));
        assertFalse(l.getJSONObject(1).getBoolean("rueckfrage"));
        root.put("aufbewahrung", Aufbewahrung.mitAenderung(root, "event_a", -1));
        assertEquals(1, Aufbewahrung.liste(root, NOW).length());
        assertFalse("behalten heißt nie löschen, auch nach Ablauf",
                Aufbewahrung.leseBehalten(root).containsKey("event_a"));
    }

    @Test
    public void wiring() throws Exception {
        String sm = lies("src/main/java/at/greenchili/surfmate/storage/StorageManager.java");
        int loop = sm.indexOf("for (File file : allFiles) {");
        int skip = sm.indexOf("if (Aufbewahrung.istBehalten(file.getName())) continue;", loop);
        int del = sm.indexOf("boolean deleted = file.delete();", loop);
        assertTrue("Größen-Reaper überspringt Behaltene vor dem Löschen", loop > 0 && skip > loop && skip < del);
        assertTrue(sm.contains("for (File f : Aufbewahrung.faellig(kandidaten, now, maxAgeMs)) {"));
        String cam = lies("src/main/java/at/greenchili/surfmate/daemon/CameraDaemon.java");
        assertTrue(cam.contains("at.greenchili.surfmate.storage.Aufbewahrung.start();"));
        String hs = lies("src/main/java/at/greenchili/surfmate/server/HttpServer.java");
        assertTrue(hs.contains("path.startsWith(\"/api/aufnahmen/behalten\")"));
        // 0.1.27: ohne diese Weiterleitung antwortet /api/ereignisse/offen mit 404 (Emulator 29.10.)
        assertTrue(hs.contains("path.startsWith(\"/api/ereignisse/\")"));
    }

    private static String lies(String p) throws Exception {
        return new String(Files.readAllBytes(Paths.get(p)), StandardCharsets.UTF_8);
    }
}
