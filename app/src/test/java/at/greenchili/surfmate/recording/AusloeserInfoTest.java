package at.greenchili.surfmate.recording;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 0.1.36 Auslöser je Clip: Werte und Schwellen kommen an, nie ein Ort; Datei liegt neben dem Clip und geht mit ihm.
 */
public class AusloeserInfoTest {

    private static final long TAKT = 250L;
    /** Wien, öffentlicher Ort – nur um zu prüfen, dass solche Werte nie durchkommen. */
    private static final double LAT = 48.2082, LNG = 16.3738;

    static void keinOrt(JSONObject o) {
        String s = o.toString();
        for (String verboten : new String[] {"lat", "lng", "lon", "48.2", "16.37", "ort\"", "kennzeichen", "geo",
                "place", "bild", "path", "pfad", "/storage", "gesicht"}) {
            assertFalse(verboten + " in " + s, s.toLowerCase().contains(verboten));
        }
    }

    @Test
    public void vollbremsungMitWertenSchwellenGangUndTempo() throws Exception {
        EreignisErkenner e = new EreignisErkenner();
        List<double[]> l = new ArrayList<>();
        long t = 0;
        for (; t < 3000; t += TAKT) l.add(new double[] {t, 80});
        double v = 80;
        while (v > 0) {
            v = Math.max(0, v - 9.0 * 3.6 * TAKT / 1000.0);
            l.add(new double[] {t, Math.round(v)});
            t += TAKT;
        }
        for (long ende = t + 3000; t < ende; t += TAKT) l.add(new double[] {t, 0});
        EreignisErkenner.Grund g = null;
        for (double[] p : l) {
            EreignisErkenner.Grund x = e.tempo((long) p[0], p[1], true);
            if (x != null) g = x;
        }
        assertEquals(EreignisErkenner.Grund.VERZOEGERUNG, g);
        EreignisErkenner.Details d = e.letzteDetails();
        assertNotNull(d);
        assertEquals("tempo", d.quelle);
        assertEquals("verzoegerung", d.regel);
        assertTrue(d.werte.get("verzoegerungMs2") >= 5.0);
        assertEquals(5.0, d.schwellen.get("verzoegerungMs2"), 0.0);
        assertTrue(d.werte.get("kmhVorher") > d.werte.get("kmhNachher"));

        JSONObject o = EreignisMonitor.ausloeserInfo(g, d, 1_793_000_000_000L, 42.4, "D");
        JSONObject sauber = AusloeserInfo.bereinigt(o);
        assertNotNull(sauber);
        assertEquals("bremsen", sauber.getString("art"));
        assertEquals("tempo", sauber.getString("quelle"));
        assertEquals("D", sauber.getString("gang"));
        assertEquals(42.4, sauber.getDouble("kmh"), 0.001);
        assertEquals(5.0, sauber.getJSONObject("schwellen").getDouble("verzoegerungMs2"), 0.0);
        assertTrue(sauber.getJSONObject("werte").getDouble("verzoegerungMs2") >= 5.0);
        keinOrt(sauber);
    }

    @Test
    public void stossMitSpitzeUndSchwelle() {
        EreignisErkenner e = new EreignisErkenner();
        e.tempo(0, 50, true);
        for (long t = 0; t < 5_000; t += 40) e.imu(t, 0f, 9.81f, 0f);
        assertNull(e.imu(5_000, 0f, 9.81f, 18f));
        assertEquals(EreignisErkenner.Grund.STOSS, e.imu(5_040, 0f, 9.81f, 17f));
        EreignisErkenner.Details d = e.letzteDetails();
        assertEquals("sensor", d.quelle);
        assertEquals("stoss_doppelt", d.regel);
        assertEquals(17.0, d.werte.get("stossMs2"), 0.5);
        assertEquals(EreignisErkenner.STOSS_MS2, d.schwellen.get("stossMs2"), 0.0);
        JSONObject o = AusloeserInfo.bereinigt(
                EreignisMonitor.ausloeserInfo(EreignisErkenner.Grund.STOSS, d, 1_793_000_000_000L, 50, "D"));
        assertEquals("stoss", o.optString("art"));
    }

    @Test
    public void ortUndFremdeFelderKommenNieDurch() throws Exception {
        JSONObject o = AusloeserInfo.neu(1_793_000_000_000L, AusloeserInfo.MANUELL, AusloeserInfo.QUELLE_TASTE, "manueller_clip");
        AusloeserInfo.wert(o, "lat", LAT);
        AusloeserInfo.wert(o, "startLng", LNG);
        AusloeserInfo.wert(o, "ortX", 1);
        AusloeserInfo.wert(o, "sekundenVorher", 30);
        o.put("geo", new JSONObject().put("lat", LAT).put("lng", LNG));
        o.put("place", "Wien");
        o.put("kennzeichen", "W-12345X");
        o.put("lat", LAT);
        o.put("pfad", "/storage/emulated/0/DCIM/x.mp4");
        o.put("gang", "Z");
        o.getJSONObject("werte").put("longitude", LNG).put("text", "frei");
        JSONObject s = AusloeserInfo.bereinigt(o);
        assertNotNull(s);
        assertEquals("manuell", s.getString("art"));
        assertEquals("taste", s.getString("quelle"));
        assertEquals(30, s.getJSONObject("werte").getInt("sekundenVorher"));
        assertEquals(1, s.getJSONObject("werte").length());
        assertFalse(s.has("gang"));
        keinOrt(s);
        // unbekannte Art oder ohne Zeit: unbrauchbar
        assertNull(AusloeserInfo.bereinigt(new JSONObject().put("v", 1).put("zeit", 5).put("art", "ort")));
        assertNull(AusloeserInfo.bereinigt(new JSONObject().put("v", 1).put("art", "manuell")));
    }

    @Test
    public void dateiNebenDemClipUndWegMitDemClip() throws Exception {
        File dir = Files.createTempDirectory("ausloeser").toFile();
        try {
            File clip = new File(dir, "ereignis_20261031_160100.mp4");
            Files.write(clip.toPath(), new byte[] {1, 2, 3});
            JSONObject o = AusloeserInfo.neu(1_793_000_000_000L, AusloeserInfo.BREMSEN, "tempo", "verzoegerung");
            AusloeserInfo.wert(o, "verzoegerungMs2", 6.25);
            AusloeserInfo.schwelle(o, "verzoegerungMs2", 5.0);
            AusloeserInfo.fahrt(o, "D", 61.0);
            o.put("lat", LAT);
            assertTrue(AusloeserInfo.schreibe(clip, o));
            File f = new File(dir, "ereignis_20261031_160100.ausloeser.json");
            assertEquals(f, AusloeserInfo.datei(clip));
            assertTrue(f.isFile());
            String roh = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            assertFalse(roh, roh.contains("lat"));
            JSONObject gelesen = AusloeserInfo.lies(clip);
            assertEquals("ereignis_20261031_160100.mp4", gelesen.getString("clip"));
            assertEquals(6.25, gelesen.getJSONObject("werte").getDouble("verzoegerungMs2"), 0.0);
            assertEquals("D", gelesen.getString("gang"));
            keinOrt(gelesen);
            assertFalse(new File(dir, "ereignis_20261031_160100.ausloeser.json.tmp").exists());

            // Clip weg -> Waise wird entfernt; Datei eines vorhandenen Clips bleibt
            File zweiter = new File(dir, "replay_20261101_194000.mp4");
            Files.write(zweiter.toPath(), new byte[] {1});
            assertTrue(AusloeserInfo.schreibe(zweiter,
                    AusloeserInfo.neu(1_793_000_000_000L, AusloeserInfo.MANUELL, "taste", null)));
            assertTrue(clip.delete());
            assertEquals(1, AusloeserInfo.waisenEntfernen(dir));
            assertFalse(f.exists());
            assertTrue(AusloeserInfo.datei(zweiter).exists());
            assertTrue(AusloeserInfo.loesche(zweiter));
            assertFalse(AusloeserInfo.datei(zweiter).exists());
        } finally {
            for (File x : dir.listFiles()) x.delete();
            dir.delete();
        }
    }

    @Test
    public void kaputteOderZuGrosseDateiWirdIgnoriert() throws Exception {
        File dir = Files.createTempDirectory("ausloeser-kaputt").toFile();
        try {
            File clip = new File(dir, "ereignis_20261101_194000.mp4");
            Files.write(AusloeserInfo.datei(clip).toPath(), "{kaputt".getBytes(StandardCharsets.UTF_8));
            assertNull(AusloeserInfo.lies(clip));
            Files.write(AusloeserInfo.datei(clip).toPath(), new byte[AusloeserInfo.MAX_BYTES + 1]);
            assertNull(AusloeserInfo.lies(clip));
        } finally {
            for (File x : dir.listFiles()) x.delete();
            dir.delete();
        }
    }
}
