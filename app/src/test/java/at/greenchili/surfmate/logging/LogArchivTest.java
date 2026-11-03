package at.greenchili.surfmate.logging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 0.1.36 Log-Archiv: Grenzen, Aufbewahrung je Art, wenig Platz, Aufräumen im Ordner. */
public class LogArchivTest {

    static final long JETZT = 1_800_000_000_000L;
    static final long TAG = 86_400_000L;
    static final long MB = 1024L * 1024;

    static LogArchiv.Datei d(String art, long alterMs, long groesse, int n) {
        String name = art + ".20261101-120000-100-" + n + ".log";
        return new LogArchiv.Datei(name, groesse, JETZT - alterMs, null);
    }

    static Set<String> namen(List<LogArchiv.Datei> l) {
        Set<String> s = new HashSet<>();
        for (LogArchiv.Datei x : l) s.add(x.name);
        return s;
    }

    @Test
    public void standardSiebenTageFuenfzigMb() {
        LogArchiv.Grenzen g = LogArchiv.grenzen(null);
        assertEquals(7, g.tage);
        assertEquals(50, g.mb);
        g = LogArchiv.grenzen(new JSONObject());
        assertEquals(7, g.tage);
        assertEquals(50, g.mb);
    }

    @Test
    public void einstellbarUndGeklemmt() throws Exception {
        JSONObject root = new JSONObject().put("logs", new JSONObject().put("tage", 3).put("mb", 20));
        LogArchiv.Grenzen g = LogArchiv.grenzen(root);
        assertEquals(3, g.tage);
        assertEquals(20, g.mb);
        root = new JSONObject().put("logs", new JSONObject().put("tage", 0).put("mb", 99_999));
        g = LogArchiv.grenzen(root);
        assertEquals(LogArchiv.MIN_TAGE, g.tage);
        assertEquals(LogArchiv.MAX_MB, g.mb);
        // Text statt Zahl: Standard, nicht kaputt
        root = new JSONObject().put("logs", new JSONObject().put("tage", "viele").put("mb", "x"));
        g = LogArchiv.grenzen(root);
        assertEquals(7, g.tage);
        assertEquals(50, g.mb);
    }

    @Test
    public void wenigPlatzGrenztEnger() {
        LogArchiv.Grenzen g = new LogArchiv.Grenzen(7, 50);
        LogArchiv.Grenzen viel = LogArchiv.wirksam(g, 8L * 1024 * MB);
        assertEquals(7, viel.tage);
        assertEquals(50, viel.mb);
        LogArchiv.Grenzen wenig = LogArchiv.wirksam(g, 600 * MB);
        assertEquals(7, wenig.tage);
        assertEquals(10, wenig.mb);
        LogArchiv.Grenzen knapp = LogArchiv.wirksam(g, 50 * MB);
        assertEquals(1, knapp.tage);
        assertEquals(2, knapp.mb);
        LogArchiv.Grenzen unbekannt = LogArchiv.wirksam(g, -1);
        assertEquals(50, unbekannt.mb);
        // nie mehr als eingestellt
        assertEquals(5, LogArchiv.wirksam(new LogArchiv.Grenzen(7, 5), 600 * MB).mb);
        assertEquals(1, LogArchiv.wirksam(new LogArchiv.Grenzen(1, 5), 50 * MB).tage);
    }

    @Test
    public void aelterAlsSiebenTageFaelltWeg() {
        List<LogArchiv.Datei> l = new ArrayList<>();
        l.add(d("cam_daemon", 1 * TAG, 1000, 1));
        l.add(d("cam_daemon", 6 * TAG, 1000, 2));
        l.add(d("cam_daemon", 8 * TAG, 1000, 3));
        Set<String> weg = namen(LogArchiv.zuLoeschen(l, JETZT, new LogArchiv.Grenzen(7, 50)));
        assertEquals(1, weg.size());
        assertTrue(weg.contains(l.get(2).name));
    }

    @Test
    public void ueberFuenfzigMbFallenDieAeltestenJeArt() {
        List<LogArchiv.Datei> l = new ArrayList<>();
        // cam: 4 x 20 MB, neueste zuerst bleiben 2 (40 MB), die 3. hebt über 50
        for (int i = 0; i < 4; i++) l.add(d("cam_daemon", i * 3_600_000L, 20 * MB, i));
        // acc: klein, eigene Grenze
        for (int i = 0; i < 4; i++) l.add(d("acc_sentry_daemon", i * 3_600_000L, MB, i));
        Set<String> weg = namen(LogArchiv.zuLoeschen(l, JETZT, new LogArchiv.Grenzen(7, 50)));
        assertEquals(2, weg.size());
        assertTrue(weg.contains(l.get(2).name));
        assertTrue(weg.contains(l.get(3).name));
        for (int i = 4; i < 8; i++) assertFalse(weg.contains(l.get(i).name));
    }

    @Test
    public void absturzschleifeBegrenztDateizahl() {
        List<LogArchiv.Datei> l = new ArrayList<>();
        for (int i = 0; i < LogArchiv.MAX_DATEIEN + 20; i++) l.add(d("telegrambotdaemon", i * 1000L, 10, i));
        assertEquals(20, LogArchiv.zuLoeschen(l, JETZT, new LogArchiv.Grenzen(7, 50)).size());
    }

    @Test
    public void fremdeNamenBleibenUnberuehrt() {
        assertEquals("cam_daemon", LogArchiv.art("cam_daemon.20261101-120000-4711-1.log"));
        assertNull(LogArchiv.art("cam_daemon.log"));
        assertNull(LogArchiv.art("grenzen"));
        assertNull(LogArchiv.art("../x.20261101-120000-1-1.log"));
        List<LogArchiv.Datei> l = new ArrayList<>();
        l.add(new LogArchiv.Datei("wichtig.txt", 100 * MB, JETZT - 100 * TAG, null));
        assertTrue(LogArchiv.zuLoeschen(l, JETZT, new LogArchiv.Grenzen(1, 5)).isEmpty());
    }

    @Test
    public void pflegeImOrdnerSchreibtGrenzenUndLoeschtAlte() throws Exception {
        File ordner = Files.createTempDirectory("logarchiv-test").toFile();
        try {
            long jetzt = System.currentTimeMillis();
            File alt = new File(ordner, "cam_daemon.20261020-080000-1-1.log");
            File neu = new File(ordner, "cam_daemon.20261102-080000-1-1.log");
            File fremd = new File(ordner, "notiz.txt");
            Files.write(alt.toPath(), "alt".getBytes(StandardCharsets.UTF_8));
            Files.write(neu.toPath(), "neu".getBytes(StandardCharsets.UTF_8));
            Files.write(fremd.toPath(), "x".getBytes(StandardCharsets.UTF_8));
            assertTrue(alt.setLastModified(jetzt - 10 * TAG));
            assertTrue(fremd.setLastModified(jetzt - 10 * TAG));
            JSONObject cfg = new JSONObject().put("logs", new JSONObject().put("tage", 7).put("mb", 30));
            assertEquals(1, LogArchiv.pflege(ordner, cfg, jetzt));
            assertFalse(alt.exists());
            assertTrue(neu.exists());
            assertTrue(fremd.exists());
            assertEquals("7 30\n", new String(Files.readAllBytes(new File(ordner, "grenzen").toPath()),
                    StandardCharsets.US_ASCII));
        } finally {
            for (File f : ordner.listFiles()) f.delete();
            ordner.delete();
        }
    }

    @Test
    public void ordnerLiegtNebenDemLog() {
        assertEquals("/data/user_de/0/com.android.shell/surfmate/logarchiv",
                LogArchiv.ordnerFuer("/data/user_de/0/com.android.shell/surfmate/cam_daemon.log"));
    }

    @Test
    public void shellZeilenOhneEinfacheAnfuehrungszeichenUndOhneAwk() {
        for (String z : LogArchiv.shellFunktionen()) {
            assertFalse(z, z.contains("'"));
            assertFalse(z, z.contains("awk"));
        }
        String befehl = LogArchiv.archivBefehl("/data/x/cam_daemon.log");
        assertFalse(befehl.contains("\n"));
        assertTrue(befehl.endsWith("log_archivieren \"/data/x/cam_daemon.log\"; "));
        // Shell und Java zählen mit denselben Zahlen
        String alles = String.join("\n", LogArchiv.shellFunktionen());
        assertTrue(alles.contains("LA_TAGE=" + LogArchiv.STANDARD_TAGE + "; LA_MB=" + LogArchiv.STANDARD_MB));
        assertTrue(alles.contains("-gt " + LogArchiv.MAX_DATEIEN));
        assertTrue(alles.contains("-lt " + (LogArchiv.KNAPP_FREI_BYTES / 1024)));
        assertTrue(alles.contains("-lt " + (LogArchiv.WENIG_FREI_BYTES / 1024)));
    }
}
