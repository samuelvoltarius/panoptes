package at.greenchili.surfmate.recording;

import at.greenchili.surfmate.logging.DaemonLogger;

import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Laufzeit des Ereignis-Clips im Kamera-Daemon (Logik: {@link EreignisErkenner}, Ablage:
 * {@link EreignisClip}, {@link EreignisAblage}).
 *
 * <ul>
 *   <li>Takt {@link #TAKT_MS}: Tempo aus dem SDK + Fahrstufe → Erkenner. Kostet nichts, solange der
 *       Schalter aus ist (nur alle 5 s die Config lesen).</li>
 *   <li>Sensor: Liefert RoadSense nicht schon Daten, fordert der Monitor beim Fahren den sparsamen
 *       Sensor-Takt (PARK, 25 Hz) an – wie der Wächter geparkt.</li>
 *   <li>Auslösung → {@link ManualClipService#requestEreignisClip} (20 s davor, 20 s danach). Ist der
 *       Ring gerade belegt (Replay läuft), wird bis {@link #NACHHER_WARTEN_S} s nachgefasst, mit
 *       verschobenem Fenster, damit der Zeitpunkt gleich bleibt.</li>
 *   <li>Danach: kurze Meldung „Ereignis gesichert“ am Display (ohne Ton, ohne Licht), offene Frage
 *       „Behalten?“ für das Aussteigen bzw. den nächsten Start, optional Telegram nur als Text.</li>
 * </ul>
 * Am Auto wird nichts geschaltet; es entsteht nur eine Videodatei im eigenen Ordner.
 */
public final class EreignisMonitor {

    private static final DaemonLogger logger = DaemonLogger.getInstance("EreignisClip");
    static final long TAKT_MS = 250L;
    static final long CONFIG_MS = 5_000L;
    static final long SENSOR_NEUSTART_MS = 30_000L;
    static final int NACHHER_WARTEN_S = 15;
    public static final String ACTION_GESICHERT = "at.greenchili.surfmate.action.EREIGNIS_GESICHERT";
    public static final String AKTIVITAET = "at.greenchili.surfmate/.ui.EreignisFrageActivity";

    private static volatile EreignisMonitor instance;

    private final EreignisErkenner erkenner = new EreignisErkenner();
    private ScheduledExecutorService exec;
    private volatile boolean aktiv;
    private long configGelesen = Long.MIN_VALUE / 2;
    private long sensorBefehl;
    private boolean sensorVonUns;
    private boolean warFahrend;
    private boolean warAccAn;
    private volatile boolean sichertGerade;

    private EreignisMonitor() {}

    /** 0.1.31: gerade wird ein Ereignis gesichert (der Update-Hinweis wartet dann). */
    public static boolean sichertGerade() {
        EreignisMonitor m = instance;
        return m != null && m.sichertGerade;
    }

    public static synchronized void start() {
        if (instance != null) return;
        EreignisMonitor m = new EreignisMonitor();
        m.exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "EreignisMonitor");
            t.setDaemon(true);
            return t;
        });
        m.exec.scheduleWithFixedDelay(() -> {
            try {
                m.takt();
            } catch (Throwable t) {
                logger.warn("takt: " + t.getMessage());
            }
        }, 2_000L, TAKT_MS, TimeUnit.MILLISECONDS);
        instance = m;
    }

    /** Braucht der Ereignis-Clip gerade den Sensor? (für den Wächter, bevor er ihn abschaltet) */
    public static boolean brauchtSensor() {
        EreignisMonitor m = instance;
        return m != null && m.aktiv && m.warFahrend;
    }

    /** Für /status. */
    public static void putStatus(java.util.Map<String, Object> status) {
        EreignisMonitor m = instance;
        status.put("ereignisClipAktiv", m != null && m.aktiv);
    }

    private void takt() {
        long jetzt = System.currentTimeMillis();
        if (jetzt - configGelesen >= CONFIG_MS) {
            configGelesen = jetzt;
            boolean neu = EreignisClip.aktiv();
            if (neu != aktiv) {
                aktiv = neu;
                logger.info("Ereignis-Clip " + (neu ? "an" : "aus"));
                schalterGeaendert();
            }
        }

        at.greenchili.surfmate.recording.RecordingModeManager rmm =
                at.greenchili.surfmate.daemon.CameraDaemon.getRecordingModeManager();
        boolean accAn = rmm != null && rmm.isAccOn();
        boolean fahrend = rmm != null && accAn
                && RecordingModeManager.isDrivingGear(rmm.getCurrentGear());

        // „Behalten?“ beim Aussteigen (Fahrstufe → P) und beim nächsten Start (ACC an im Stand).
        if ((warFahrend && !fahrend && accAn) || (!warAccAn && accAn && !fahrend)) frageZeigen();
        warAccAn = accAn;

        if (!aktiv) {
            if (warFahrend) erkenner.tempo(jetzt, Double.NaN, false);
            warFahrend = false;
            sensorFreigeben();
            return;
        }
        warFahrend = fahrend;
        double kmh = Double.NaN;
        if (fahrend) {
            try {
                kmh = at.greenchili.surfmate.byd.BydDataCollector.getInstance().readSpeedNowKmh();
            } catch (Throwable ignored) {
            }
        }
        EreignisErkenner.Grund g = erkenner.tempo(jetzt, kmh, fahrend);
        if (g != null) ausloesen(g);

        if (fahrend) sensorAnfordern(jetzt);
        else sensorFreigeben();
    }

    /** Schalter umgelegt: Ringgröße und Nur-Ring-Betrieb neu bestimmen. */
    private void schalterGeaendert() {
        try {
            ManualClipService.getInstance().reapplyLiveRetention();
        } catch (Throwable ignored) {
        }
        try {
            RecordingModeManager rmm = at.greenchili.surfmate.daemon.CameraDaemon.getRecordingModeManager();
            if (rmm != null) rmm.ereignisSchalterGeaendert();
        } catch (Throwable t) {
            logger.warn("Nur-Ring-Betrieb nicht umgestellt: " + t.getMessage());
        }
    }

    // ---------------------------------------------------------------- Sensor

    private void sensorAnfordern(long jetzt) {
        if (roadSenseLaeuft()) return;   // RoadSense liefert schon (und setzt seine Rate selbst)
        if (jetzt - sensorBefehl < SENSOR_NEUSTART_MS) return;
        sensorBefehl = jetzt;
        sensorVonUns = true;
        try {
            at.greenchili.surfmate.roadsense.sidecar.RoadSenseImuSidecarService.Companion.start(
                    at.greenchili.surfmate.roadsense.sidecar.RoadSenseImuSidecarService.ImuRate.PARK);
        } catch (Throwable ignored) {
        }
    }

    private void sensorFreigeben() {
        if (!sensorVonUns) return;
        sensorVonUns = false;
        sensorBefehl = 0;
        if (roadSenseLaeuft() || at.greenchili.surfmate.surveillance.ShockMonitor.istAktiv()) return;
        try {
            at.greenchili.surfmate.roadsense.sidecar.RoadSenseImuSidecarService.Companion.stop();
        } catch (Throwable ignored) {
        }
    }

    private static boolean roadSenseLaeuft() {
        try {
            at.greenchili.surfmate.roadsense.RoadSenseController rs =
                    at.greenchili.surfmate.daemon.CameraDaemon.getRoadSense();
            return rs != null && rs.isStarted();
        } catch (Throwable t) {
            return false;
        }
    }

    /** IMU_BATCH vom Sidecar (IPC-Lesethread). Kostet nichts, solange nicht aktiv. */
    public static void onImuBatch(JSONObject frame) {
        EreignisMonitor m = instance;
        if (m == null || !m.aktiv || !m.warFahrend || frame == null) return;
        at.greenchili.surfmate.roadsense.detect.ImuFrameCodec.Decoded d;
        try {
            d = at.greenchili.surfmate.roadsense.detect.ImuFrameCodec.INSTANCE.decode(frame);
        } catch (Throwable t) {
            return;
        }
        if (d == null) return;
        EreignisErkenner.Grund g = null;
        for (at.greenchili.surfmate.roadsense.detect.ImuAccelSample s : d.getAccel()) {
            EreignisErkenner.Grund x = m.erkenner.imu(s.getTMs(), s.getAx(), s.getAy(), s.getAz());
            if (x != null && g == null) g = x;
        }
        if (g != null) m.ausloesen(g);
    }

    // ---------------------------------------------------------------- Sichern

    private void ausloesen(EreignisErkenner.Grund g) {
        if (!aktiv) return;
        if (sichertGerade) {
            logger.info("Ereignis erkannt (" + g + "), Sicherung läuft schon");
            return;
        }
        sichertGerade = true;
        final long ereignisMs = System.currentTimeMillis();
        logger.info(String.format(java.util.Locale.ROOT, "Ereignis erkannt: %s – sichere Clip (Tempo %.0f km/h%s)",
                g, erkenner.letzteKmh(),
                g == EreignisErkenner.Grund.STOSS
                        ? String.format(java.util.Locale.ROOT, ", Stoß %.1f m/s²", erkenner.letzteSpitze()) : ""));
        Thread t = new Thread(() -> {
            try {
                sichern(g, ereignisMs);
            } finally {
                sichertGerade = false;
            }
        }, "EreignisSichern");
        t.setDaemon(true);
        t.start();
    }

    /** Fenster je Versuch: erst 20/20, bei zu kleinem Ring kürzer (erste Fahrt nach dem Einschalten). */
    static int[][] fenster(int verzugS) {
        int vorher = EreignisClip.VORHER_S + verzugS;
        int nachher = Math.max(0, EreignisClip.NACHHER_S - verzugS);
        return new int[][] {
                {Math.min(vorher, ManualClipWindow.MAX_SECONDS - nachher), nachher},
                {Math.min(10 + verzugS, 30), Math.max(0, 10 - verzugS)},
                {Math.min(3 + verzugS, 10), Math.max(0, 2 - verzugS)},
        };
    }

    private void sichern(EreignisErkenner.Grund g, long ereignisMs) {
        ManualClipService mcs = ManualClipService.getInstance();
        final Object warten = new Object();
        final File[] ergebnis = new File[1];
        final boolean[] fertig = new boolean[1];
        ManualClipService.EreignisFertig rueckmeldung = datei -> {
            synchronized (warten) {
                ergebnis[0] = datei;
                fertig[0] = true;
                warten.notifyAll();
            }
        };
        boolean angenommen = false;
        while (!angenommen) {
            int verzug = (int) ((System.currentTimeMillis() - ereignisMs) / 1000L);
            if (verzug > NACHHER_WARTEN_S) break;
            ManualClipService.Status letzter = null;
            for (int[] f : fenster(verzug)) {
                if (f[0] + f[1] < 1) continue;
                ManualClipService.RequestResult r = mcs.requestEreignisClip(f[0], f[1], rueckmeldung);
                letzter = r.status;
                if (r.isAccepted()) {
                    angenommen = true;
                    logger.info("Ereignis-Clip angenommen: " + f[0] + " s davor, " + f[1] + " s danach");
                    break;
                }
                if (r.status != ManualClipService.Status.RESTART_REQUIRED) break;
            }
            if (angenommen) break;
            if (letzter == ManualClipService.Status.PIPELINE_NOT_READY
                    && System.currentTimeMillis() - ereignisMs > 3_000L) break;
            try {
                Thread.sleep(1_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (!angenommen) {
            logger.warn("Ereignis-Clip nicht gesichert (Kamera nicht bereit oder belegt)");
            return;
        }
        File datei;
        synchronized (warten) {
            long bis = System.currentTimeMillis() + 120_000L;
            while (!fertig[0] && System.currentTimeMillis() < bis) {
                try {
                    warten.wait(1_000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            datei = ergebnis[0];
        }
        if (datei == null) return;
        gesichert(g, datei, ereignisMs);
    }

    private void gesichert(EreignisErkenner.Grund g, File datei, long ereignisMs) {
        logger.info("Ereignis gesichert (" + g + "): " + datei.getName());
        EreignisClip.frageMerken(EreignisClip.stamm(datei.getName()), ereignisMs);
        EreignisAblage.pflege();
        meldungAmDisplay(ereignisMs);
        try {
            // 0.1.28 P1: eigene Kategorie „Ereignisse“ (vorher CRITICAL → mit „Alarme aus“ verschwunden).
            // Schalter prüft TelegramNotifier frisch; nur Text, ohne Video und ohne Ort (Rechtslage Nr. 8).
            at.greenchili.surfmate.telegram.TelegramNotifier.sendMessage(
                    EreignisMeldung.telegramText(ereignisMs), EreignisMeldung.TELEGRAM_KATEGORIE);
        } catch (Throwable t) {
            logger.debug("Telegram-Text nicht gesendet: " + t.getMessage());
        }
        try {
            // Handy-Push (Kategorie ereignis.gesichert, je Gerät abschaltbar). TelegramSink lässt sie aus.
            at.greenchili.surfmate.notifications.NotificationBus.get().publish(
                    EreignisMeldung.pushEreignis(ereignisMs));
        } catch (Throwable t) {
            logger.debug("Push nicht gesendet: " + t.getMessage());
        }
    }

    /** Kurze Meldung über die App (Toast: ohne Ton, ohne Licht). */
    private static void meldungAmDisplay(long ereignisMs) {
        amAufruf(new String[] {"am", "broadcast", "-a", ACTION_GESICHERT, "-p", "at.greenchili.surfmate",
                "--el", "zeit", String.valueOf(ereignisMs)});
    }

    /** Offene „Behalten?“-Frage am Display, wenn es eine gibt. */
    private static void frageZeigen() {
        try {
            if (EreignisClip.offen(at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig()).length() == 0) {
                return;
            }
        } catch (Throwable t) {
            return;
        }
        amAufruf(new String[] {"am", "start", "-n", AKTIVITAET});
    }

    private static void amAufruf(String[] cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            final java.io.InputStream is = p.getInputStream();
            Thread drain = new Thread(() -> {
                byte[] buf = new byte[256];
                try { while (is.read(buf) != -1) { /* verwerfen */ } } catch (Throwable ignored) {}
            }, "ereignis-am");
            drain.setDaemon(true);
            drain.start();
        } catch (Throwable t) {
            logger.debug("am-Aufruf fehlgeschlagen: " + t.getMessage());
        }
    }
}
