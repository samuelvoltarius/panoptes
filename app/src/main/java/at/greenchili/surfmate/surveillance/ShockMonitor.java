package at.greenchili.surfmate.surveillance;

import at.greenchili.surfmate.config.UnifiedConfigManager;
import at.greenchili.surfmate.logging.DaemonLogger;

import org.json.JSONObject;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Laufzeit der Erschütterungserkennung im Kamera-Daemon (Logik: {@link ShockDetector}).
 *
 * <p>Aktiv nur, wenn der Wächter wirklich scharf ist, ACC autoritativ aus ist, kein Wächter-Stopp
 * gilt und eine Empfindlichkeit gewählt ist ({@link #empfindlichkeit}). Dann läuft der App-Sidecar
 * in der sparsamen Rate PARK (25 Hz, nur Beschleunigung, 1 s Bündelung) und liefert IMU_BATCH an
 * {@link #onImuBatch}. Sonst ist der Sensor aus (Ausnahme: RoadSense besitzt ihn beim Fahren).
 * Eine Erschütterung startet über {@link SurveillanceEngineGpu#triggerShockEvent} eine Aufnahme.
 * Am Auto wird hier nichts ausgelöst außer einer Videoaufnahme.
 */
public final class ShockMonitor {

    private static final DaemonLogger logger = DaemonLogger.getInstance("ShockMonitor");
    static final long TICK_MS = 5_000L;
    /** Sidecar-Start regelmäßig wiederholen (idempotent), falls RoadSense/OS ihn beendet hat. */
    static final long RESTART_MS = 30_000L;

    private static volatile ShockMonitor instance;

    private final Object lock = new Object();
    private ShockDetector detector;
    private ShockDetector.Empfindlichkeit stufe;
    private volatile boolean aktiv;
    private long letzterStartBefehl;
    private ScheduledExecutorService exec;

    private ShockMonitor() {}

    public static synchronized void start() {
        if (instance != null) return;
        ShockMonitor m = new ShockMonitor();
        m.exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ShockMonitor");
            t.setDaemon(true);
            return t;
        });
        m.exec.scheduleWithFixedDelay(() -> {
            try {
                m.tick(System.currentTimeMillis());
            } catch (Throwable t) {
                logger.warn("tick failed: " + t.getMessage());
            }
        }, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
        instance = m;
    }

    /**
     * Gewählte Empfindlichkeit oder null (aus). {@code surveillance.shockSensitivity}:
     * aus | niedrig | mittel | hoch | auto/fehlt. „auto“ = mittel, sobald ein Wächter-Auslöser
     * gewählt ist (neue Installation); Alt-Installationen ohne Wahl bleiben exakt wie heute (aus).
     */
    public static ShockDetector.Empfindlichkeit empfindlichkeit(JSONObject surveillance) {
        String s = surveillance == null ? null : surveillance.optString("shockSensitivity", null);
        String trig = surveillance == null ? null : surveillance.optString("triggerMode", null);
        // „Wächter aus“: auch keine Erschütterung
        if (at.greenchili.surfmate.einstellungen.TriggerModus.AUS.equals(trig)) return null;
        if (s == null || s.isEmpty() || "auto".equals(s)) {
            // 0.1.9.1: nicht gewählt = nur Erschütterung → Sensor an (mittel), wie bei jeder Wahl
            return ShockDetector.Empfindlichkeit.MITTEL;
        }
        return ShockDetector.Empfindlichkeit.von(s);   // "aus" -> null
    }

    void tick(long now) {
        ShockDetector.Empfindlichkeit e = null;
        try {
            JSONObject root = UnifiedConfigManager.loadConfig();
            e = empfindlichkeit(root == null ? null : root.optJSONObject("surveillance"));
        } catch (Throwable ignored) {
        }
        boolean soll = e != null
                && at.greenchili.surfmate.monitor.AccMonitor.isAccStateAuthoritative()
                && !at.greenchili.surfmate.monitor.AccMonitor.isAccOn()
                && at.greenchili.surfmate.daemon.CameraDaemon.isSentryArmed()
                && !GuardStopMonitor.blocksArming()
                && !at.greenchili.surfmate.camping.Camping.ohneAbschreckung();
        synchronized (lock) {
            if (soll && (!aktiv || e != stufe)) {
                detector = new ShockDetector(e);
                detector.reset(now);
                stufe = e;
                aktiv = true;
                letzterStartBefehl = 0L;
                logger.info("Erschütterungserkennung an (" + e.name().toLowerCase() + ", "
                        + e.schwelleMs2 + " m/s²)");
            } else if (!soll && aktiv) {
                aktiv = false;
                detector = null;
                logger.info("Erschütterungserkennung aus");
                sidecarStoppenWennFrei();
            }
            if (aktiv && now - letzterStartBefehl >= RESTART_MS) {
                letzterStartBefehl = now;
                at.greenchili.surfmate.roadsense.sidecar.RoadSenseImuSidecarService.Companion.start(
                        at.greenchili.surfmate.roadsense.sidecar.RoadSenseImuSidecarService.ImuRate.PARK);
            }
        }
    }

    /** Läuft die Erschütterungserkennung gerade (braucht den Sensor)? */
    public static boolean istAktiv() {
        ShockMonitor m = instance;
        return m != null && m.aktiv;
    }

    private static void sidecarStoppenWennFrei() {
        // Der Ereignis-Clip beim Fahren braucht den Sensor womöglich noch.
        if (at.greenchili.surfmate.recording.EreignisMonitor.brauchtSensor()) return;
        try {
            at.greenchili.surfmate.roadsense.RoadSenseController rs =
                    at.greenchili.surfmate.daemon.CameraDaemon.getRoadSense();
            if (rs == null || !rs.isStarted()) {
                at.greenchili.surfmate.roadsense.sidecar.RoadSenseImuSidecarService.Companion.stop();
            }
            // Läuft RoadSense, setzt es beim nächsten Regime-Wechsel seine eigene Rate.
        } catch (Throwable ignored) {
        }
    }

    /** IMU_BATCH vom Sidecar (IPC-Lesethread). Kostet nichts, solange nicht aktiv. */
    public static void onImuBatch(JSONObject frame) {
        ShockMonitor m = instance;
        if (m == null || !m.aktiv || frame == null) return;
        m.verarbeite(frame);
    }

    private void verarbeite(JSONObject frame) {
        at.greenchili.surfmate.roadsense.detect.ImuFrameCodec.Decoded d;
        try {
            d = at.greenchili.surfmate.roadsense.detect.ImuFrameCodec.INSTANCE.decode(frame);
        } catch (Throwable t) {
            return;
        }
        if (d == null) return;
        double spitze = -1;
        synchronized (lock) {
            ShockDetector det = detector;
            if (!aktiv || det == null) return;
            for (at.greenchili.surfmate.roadsense.detect.ImuAccelSample s : d.getAccel()) {
                if (det.feed(s.getTMs(), s.getAx(), s.getAy(), s.getAz())) spitze = det.letzteSpitze();
            }
        }
        if (spitze >= 0) ausloesen(spitze);
    }

    private static void ausloesen(double spitze) {
        logger.info(String.format(java.util.Locale.ROOT, "Erschütterung erkannt (%.2f m/s²) – starte Aufnahme", spitze));
        try {
            GpuSurveillancePipeline p = at.greenchili.surfmate.daemon.CameraDaemon.getGpuPipeline();
            SurveillanceEngineGpu e = p == null ? null : p.getSentry();
            if (e == null || !e.triggerShockEvent(spitze)) {
                logger.info("Erschütterung: keine neue Aufnahme (läuft schon oder Wächter nicht bereit)");
            } else {
                at.greenchili.surfmate.ha.HaDienst.erschuetterung(System.currentTimeMillis());
            }
        } catch (Throwable t) {
            logger.warn("Erschütterung: Aufnahme fehlgeschlagen: " + t.getMessage());
        }
    }

    /** Für /api/surveillance/status. */
    public static void putStatus(java.util.Map<String, Object> status) {
        ShockMonitor m = instance;
        status.put("shockActive", m != null && m.aktiv);
        if (m != null && m.stufe != null && m.aktiv) status.put("shockSensitivity", m.stufe.name().toLowerCase());
    }
}
