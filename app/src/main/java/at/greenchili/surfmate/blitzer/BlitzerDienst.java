package at.greenchili.surfmate.blitzer;

import at.greenchili.surfmate.config.UnifiedConfigManager;
import at.greenchili.surfmate.logging.DaemonLogger;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Blitzer-Warnung im Daemon: alle 2 s bei fahrendem Auto prüfen ({@link BlitzerWarner}), Hinweis als
 * Band am Mittelschirm (Entfernung, Tempolimit), Ansage nur mit {@code speedcam.ansage} (Standard aus).
 * Daten offline aus {@code <daemon>/blitzer/<land>.json}, täglich vom Projekt-Server erneuert (ohne Position:
 * es wird immer der ganze Landesdatensatz geholt).
 *
 * <p>Config: {@code speedcam.enabled} (Standard AUS seit 0.1.8, wirkt nur in AT), {@code speedcam.ansage}
 * (Standard aus), {@code speedcam.testBis} (ms, Alfreds Testschalter, nur per Login, 24 h).
 */
public final class BlitzerDienst {

    private static final DaemonLogger logger = DaemonLogger.getInstance("Blitzer");
    static final long TAKT_MS = 2_000L;
    static final long ERNEUERN_MS = 24 * 3600_000L;
    static final String QUELLE = "https://byd.serveone.at/tiles/blitzer/";
    static final long ANZEIGE_MS = 6_000L;

    private static volatile BlitzerDienst instance;

    private final BlitzerWarner warner = new BlitzerWarner();
    private final Map<String, List<BlitzerWarner.Blitzer>> daten = new HashMap<>();
    private final Map<String, Long> geholt = new HashMap<>();
    private ScheduledExecutorService exec;

    private BlitzerDienst() {}

    public static synchronized void start() {
        if (instance != null) return;
        BlitzerDienst d = new BlitzerDienst();
        d.exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Blitzer");
            t.setDaemon(true);
            return t;
        });
        d.exec.scheduleWithFixedDelay(() -> {
            try {
                d.takt(System.currentTimeMillis());
            } catch (Throwable t) {
                logger.warn("takt: " + t.getMessage());
            }
        }, 30, TAKT_MS / 1000, TimeUnit.SECONDS);
        instance = d;
    }

    // ---------------------------------------------------------------- Testschalter (nur Login)

    /** GET /api/test/blitzer. */
    public static JSONObject testStatus(long now) {
        long bis = speedcam().optLong("testBis", 0);
        JSONObject o = new JSONObject();
        try {
            boolean an = BlitzerWarner.testAktiv(bis, now);
            o.put("an", an);
            o.put("bis", an ? bis : JSONObject.NULL);
        } catch (Exception ignored) {
        }
        return o;
    }

    /** PUT /api/test/blitzer {an}: an = jetzt + 24 h, aus = 0. */
    public static boolean testSetzen(boolean an, long now) {
        try {
            return UnifiedConfigManager.updateSection("speedcam",
                    new JSONObject().put("testBis", an ? now + 24 * 3600_000L : 0));
        } catch (Exception e) {
            return false;
        }
    }

    private static JSONObject speedcam() {
        try {
            JSONObject root = UnifiedConfigManager.loadConfig();
            JSONObject s = root == null ? null : root.optJSONObject("speedcam");
            return s == null ? new JSONObject() : s;
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    // ---------------------------------------------------------------- Takt

    void takt(long now) {
        JSONObject cfg = speedcam();
        if (!cfg.optBoolean("enabled", false)) return;   // Standard AUS (Datenschutz/Recht, 21.10.)
        boolean faehrt = false;
        try {
            faehrt = at.greenchili.surfmate.monitor.AccMonitor.isAccStateAuthoritative()
                    && at.greenchili.surfmate.monitor.AccMonitor.isAccOn();
        } catch (Throwable ignored) {
        }
        if (!faehrt) return;
        at.greenchili.surfmate.monitor.GpsMonitor g = at.greenchili.surfmate.monitor.GpsMonitor.getInstance();
        if (g.getLastUpdate() <= 0 || now - g.getLastUpdate() > 10_000L) return;
        boolean test = BlitzerWarner.testAktiv(cfg.optLong("testBis", 0), now);
        double lat = g.getLatitude(), lon = g.getLongitude();
        if (!BlitzerWarner.erlaubt(lat, lon, test)) return;
        List<BlitzerWarner.Blitzer> alle = new ArrayList<>(laden("at", now));
        if (test) {
            for (String l : BlitzerDaten.TEST_LAENDER) if (!"at".equals(l)) alle.addAll(laden(l, now));
        }
        BlitzerWarner.Warnung w = warner.pruefe(now, lat, lon, g.getHeading(), g.getSpeed() * 3.6, test, alle);
        if (w == null) return;
        String titel = at.greenchili.surfmate.server.Messages.get("notifications.blitzer_titel", w.meter);
        String zeile = w.blitzer.maxspeed > 0
                ? at.greenchili.surfmate.server.Messages.get("notifications.blitzer_tempo", w.blitzer.maxspeed) : "";
        at.greenchili.surfmate.byd.MessageOverlayController.showBand(titel, zeile, "knapp", ANZEIGE_MS);
        String ton = ton(cfg);
        if (TON_ANSAGE.equals(ton)) {
            at.greenchili.surfmate.byd.AudioPlaybackController.speak(titel, "voice");
        } else if (TON_HINWEIS.equals(ton)) {
            hinweiston();
        }
        // H3: läuft CarPlay/AA und ist Sichtbarkeit unbelegt bei Ton aus → einmal merken („Noch offen“), nie Ton/Popup
        at.greenchili.surfmate.einrichtung.ProjektionsHinweis.nachBand(ton);
    }

    static final String TON_AUS = "aus", TON_HINWEIS = "hinweiston", TON_ANSAGE = "ansage";

    /**
     * {@code speedcam.ton} (0.1.9.1): aus | hinweiston | ansage. Nicht gesetzt: Bestand mit {@code speedcam.ansage=true}
     * wirkt als „ansage“ (Migration ohne Schreiben), sonst „aus“.
     */
    public static String ton(JSONObject cfg) {
        String t = cfg == null ? "" : cfg.optString("ton", "");
        if (TON_AUS.equals(t) || TON_HINWEIS.equals(t) || TON_ANSAGE.equals(t)) return t;
        return cfg != null && cfg.optBoolean("ansage", false) ? TON_ANSAGE : TON_AUS;
    }

    /** Hinweiston über denselben Warn-Kanal wie der Straßenzustand (Navi-Kanal, Ducking). */
    private static void hinweiston() {
        try {
            at.greenchili.surfmate.roadsense.config.RoadSenseConfig.Snapshot s =
                    at.greenchili.surfmate.roadsense.config.RoadSenseConfig.INSTANCE.snapshot(false);
            int vol = at.greenchili.surfmate.roadsense.config.RoadSenseChimeLevels
                    .effectivePercent(s.getWarnAudioVolume(), 2);
            at.greenchili.surfmate.byd.AudioPlaybackController.playRawResource(
                    "roadsense_chime_moderate", s.getWarnAudioChannel(), vol);
        } catch (Throwable t) {
            try {
                at.greenchili.surfmate.byd.AudioPlaybackController.playRawResource("roadsense_chime_moderate", "navigation", 70);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Datensatz eines Landes: aus dem Speicher, sonst von Platte; erneuert höchstens 1× täglich im Hintergrund. */
    private List<BlitzerWarner.Blitzer> laden(String land, long now) {
        List<BlitzerWarner.Blitzer> l = daten.get(land);
        File f = datei(land);
        if (l == null && f.isFile()) {
            try {
                l = BlitzerDaten.lies(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8), land);
            } catch (Exception ignored) {
            }
            if (l != null) daten.put(land, l);
        }
        Long zuletzt = geholt.get(land);
        boolean alt = !f.isFile() || now - f.lastModified() > ERNEUERN_MS;
        if (alt && (zuletzt == null || now - zuletzt > 3600_000L)) {
            geholt.put(land, now);
            Thread t = new Thread(() -> herunterladen(land), "BlitzerLaden");
            t.setDaemon(true);
            t.start();
        }
        return l == null ? Collections.<BlitzerWarner.Blitzer>emptyList() : l;
    }

    private void herunterladen(String land) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(QUELLE + land + ".json").openConnection();
            c.setConnectTimeout(8_000);
            c.setReadTimeout(20_000);
            if (c.getResponseCode() != 200) return;
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[16384];
                int n, total = 0;
                while ((n = in.read(buf)) > 0 && total < 20_000_000) { b.write(buf, 0, n); total += n; }
            }
            String json = new String(b.toByteArray(), StandardCharsets.UTF_8);
            List<BlitzerWarner.Blitzer> l = BlitzerDaten.lies(json, land);
            if (l == null) return;
            File f = datei(land);
            f.getParentFile().mkdirs();
            File tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream o = new FileOutputStream(tmp)) {
                o.write(json.getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(f)) return;
            synchronized (this) {
                exec.execute(() -> daten.put(land, l));
            }
            logger.info("Blitzer " + land + ": " + l.size());
        } catch (Exception e) {
            // offline: alter Datensatz bleibt
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static File datei(String land) {
        return new File(at.greenchili.surfmate.util.DaemonStorage.rebase(
                "/data/user_de/0/com.android.shell/surfmate/blitzer"), land + ".json");
    }
}
