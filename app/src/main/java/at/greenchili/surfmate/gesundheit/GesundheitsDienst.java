package at.greenchili.surfmate.gesundheit;

import at.greenchili.surfmate.daemon.CameraDaemon;
import at.greenchili.surfmate.logging.DaemonLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Gesundheitswächter im Kamera-Daemon (läuft auch geparkt): jede Minute {@link Gesundheit}, Eingriff je Stufe,
 * Meldungen. Stufe APP wird nur angefordert (Datei {@code .gesundheit-app-neustart}); ausführen tut sie der
 * sm-watchdog am Auto mit seiner Update-Sperre und seinem Abstand – so kämpfen die beiden nicht gegeneinander.
 *
 * <p>Meldungen: MQTT {@code <base>/health/state} (retained, bei Änderung + stündlich), Telegram Kategorie
 * {@code health} („Wächter war X min aus, repariert“ / „braucht Hilfe“), {@code GET /api/gesundheit}.
 */
public final class GesundheitsDienst {

    private static final DaemonLogger logger = DaemonLogger.getInstance("Gesundheit");
    static final long TAKT_MS = 60_000L;
    static final String TOPIC = "health/state";

    private static volatile GesundheitsDienst instance;

    private final Gesundheit gesundheit = new Gesundheit();
    private volatile JSONObject letzterStand = new JSONObject();
    private long sollSeit = -1;
    private String letzteBefunde = "";
    private long letztesSenden;
    private boolean hilfeGemeldet;

    private GesundheitsDienst() {}

    public static synchronized void start() {
        if (instance != null) return;
        GesundheitsDienst d = new GesundheitsDienst();
        d.gesundheit.laden(d.ladeLeiter(), System.currentTimeMillis());
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Gesundheit");
            t.setDaemon(true);
            return t;
        });
        exec.scheduleWithFixedDelay(() -> {
            try {
                d.takt(System.currentTimeMillis());
            } catch (Throwable t) {
                logger.warn("takt: " + t.getMessage());
            }
        }, 90, TAKT_MS / 1000, TimeUnit.SECONDS);
        instance = d;
    }

    /** GET /api/gesundheit. */
    public static JSONObject status() {
        GesundheitsDienst d = instance;
        return d == null ? new JSONObject() : d.letzterStand;
    }

    void takt(long now) {
        Gesundheit.Fakten f = fakten(now);
        Gesundheit.Entscheidung e = gesundheit.takt(f);
        JSONObject stand = Gesundheit.alsJson(e, f, gesundheit);
        letzterStand = stand;
        if (e.stufe != Gesundheit.Stufe.KEINE) {
            logger.warn("EINGRIFF Stufe " + e.stufe + " (Grund: " + e.grund + ")");
            speichereLeiter();
            ausfuehren(e.stufe, e.grund);
        } else if (!e.gesund() && e.grund != null) {
            logger.info("Befund " + e.befunde + " – kein Eingriff (" + e.grund + ")");
        }
        melden(e, f, stand, now);
    }

    private Gesundheit.Fakten fakten(long now) {
        Gesundheit.Fakten f = new Gesundheit.Fakten();
        f.now = now;
        f.sperre = sperre();
        boolean accAus = false;
        try {
            accAus = at.greenchili.surfmate.monitor.AccMonitor.isAccStateAuthoritative()
                    && !at.greenchili.surfmate.monitor.AccMonitor.isAccOn();
        } catch (Throwable ignored) {
        }
        String zustand = CameraDaemon.surveillanceZustand();
        f.istScharf = CameraDaemon.isSentryArmed();
        f.sollScharf = accAus && ("scharf".equals(zustand) || "startet".equals(zustand));
        if (f.sollScharf && !f.istScharf) {
            if (sollSeit < 0) sollSeit = now;
        } else {
            sollSeit = -1;
        }
        f.sollScharfSeit = sollSeit;
        try {
            at.greenchili.surfmate.surveillance.GpuSurveillancePipeline p = CameraDaemon.getGpuPipeline();
            if (p != null && p.getCamera() != null) f.letztesBild = p.getCamera().getLastFrameTime();
            if (p != null && p.getSentry() != null) f.kiGeladen = p.getSentry().isKiBereit();
            f.kiVerlangt = at.greenchili.surfmate.surveillance.WaechterAusloeser.lage(now).kiPflicht;
        } catch (Throwable ignored) {
        }
        try {
            at.greenchili.surfmate.mqtt.MqttConnectionManager m = CameraDaemon.getMqttConnectionManager();
            JSONArray alle = m == null ? null : m.getAllStatus();
            for (int i = 0; alle != null && i < alle.length(); i++) {
                JSONObject s = alle.optJSONObject(i) == null ? null : alle.optJSONObject(i).optJSONObject("status");
                if (s == null || !s.optBoolean("running", false)) continue;
                f.mqttEingerichtet = true;
                if (s.optBoolean("connected", false)) f.mqttVerbunden = true;
                f.letzteTelemetrie = Math.max(f.letzteTelemetrie, s.optLong("lastPublishTime", 0));
            }
        } catch (Throwable ignored) {
        }
        return f;
    }

    private void ausfuehren(Gesundheit.Stufe s, String grund) {
        switch (s) {
            case SCHARF:
                CameraDaemon.enableSurveillance();
                break;
            case DAEMON:
                CameraDaemon.requestProcessRestartPreservingTrip("Gesundheit: " + grund);
                break;
            case APP:
                appNeustartAnfordern(grund);
                break;
            default:
                break;
        }
    }

    /** Markerdatei für sm-watchdog (letzte Stufe; er prüft Update-Sperre + Abstand selbst). */
    static void appNeustartAnfordern(String grund) {
        try {
            File f = new File(basis(), ".gesundheit-app-neustart");
            try (FileOutputStream o = new FileOutputStream(f)) {
                o.write(("" + (System.currentTimeMillis() / 1000) + " " + grund + "\n").getBytes(StandardCharsets.UTF_8));
            }
            logger.warn("App-Neustart beim sm-watchdog angefordert: " + grund);
        } catch (Exception e) {
            logger.warn("App-Neustart-Anforderung fehlgeschlagen: " + e.getMessage());
        }
    }

    private void melden(Gesundheit.Entscheidung e, Gesundheit.Fakten f, JSONObject stand, long now) {
        String b = String.join(",", e.befunde);
        boolean geaendert = !b.equals(letzteBefunde);
        // MQTT health/state (retained) bei Änderung + stündlich, für S1 und Home Assistant
        if (geaendert || now - letztesSenden >= 3600_000L) {
            try {
                at.greenchili.surfmate.mqtt.MqttConnectionManager m = CameraDaemon.getMqttConnectionManager();
                if (m != null && m.publishToAll(TOPIC, stand.toString(), true, 1) > 0) letztesSenden = now;
            } catch (Throwable ignored) {
            }
        }
        // Telegram: repariert / braucht Hilfe
        if (e.gesund() && gesundheit.ausfallSeit() >= 0) {
            long seit = gesundheit.gesundGeworden();
            long min = Math.max(1, (now - seit) / 60_000L);
            telegram("repariert", min);
            hilfeGemeldet = false;
        } else if (!e.gesund() && gesundheit.letzteStufe() == Gesundheit.Stufe.APP && !hilfeGemeldet) {
            telegram("hilfe", gesundheit.ausfallSeit() >= 0 ? Math.max(1, (now - gesundheit.ausfallSeit()) / 60_000L) : 0);
            hilfeGemeldet = true;
        }
        letzteBefunde = b;
    }

    private static void telegram(String art, long minuten) {
        try {
            at.greenchili.surfmate.telegram.TelegramNotifier.notifyGesundheit(art, minuten);
        } catch (Throwable ignored) {
        }
    }

    private static boolean sperre() {
        if (CameraDaemon.isProcessRestartPending()) return true;
        return new File(basis(), ".deploy.lock").exists();
    }

    private static File basis() {
        return new File(at.greenchili.surfmate.util.DaemonStorage.rebase("/data/user_de/0/com.android.shell/surfmate"));
    }

    private File leiterDatei() {
        return new File(basis(), "gesundheit_leiter.json");
    }

    private JSONObject ladeLeiter() {
        try {
            File f = leiterDatei();
            if (!f.isFile()) return null;
            return new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private void speichereLeiter() {
        try (FileOutputStream o = new FileOutputStream(leiterDatei())) {
            o.write(gesundheit.sichern().toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }
}
