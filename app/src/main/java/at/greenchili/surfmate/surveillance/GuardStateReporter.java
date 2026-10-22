package at.greenchili.surfmate.surveillance;

import at.greenchili.surfmate.logging.DaemonLogger;

import org.json.JSONObject;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Meldungen für den Server-Wächter S1 („Auto meldet sich nicht mehr“, Projekt-Server, nur lesend), damit
 * er geplantes Schlafen von unerwarteter Stille unterscheidet (Spark-Ausstieg-Sitzung, 21.10.2026):
 * <ul>
 *   <li>{@code <base>/guard/state} – RETAINED bei jeder Änderung (plus stündlich aufgefrischt):
 *       {@code {"armed":bool,"guardStopActive":bool,"guardStopReason":str|null,"since":unix_ts}}</li>
 *   <li>{@code <base>/guard/sleep} – NICHT retained, QoS 1, direkt vor jedem geplanten Trennen
 *       (Wächter-Stopp greift, Daemon beendet sich im Modus „nur bei laufendem Auto“):
 *       {@code {"reason":str,"ts":unix_ts}}</li>
 * </ul>
 * {@code <base>} ist das Topic der jeweiligen MQTT-Verbindung (z. B. surfmate/vehicle/telemetry).
 * Keine Position in diesen Meldungen.
 */
public final class GuardStateReporter {

    private static final DaemonLogger logger = DaemonLogger.getInstance("GuardState");
    static final long TICK_MS = 10_000L;
    static final long REFRESH_MS = 60 * 60_000L;
    static final String TOPIC_STATE = "guard/state";
    static final String TOPIC_SLEEP = "guard/sleep";

    /** Veröffentlichen (in Tests ersetzbar). */
    interface Sender {
        boolean send(String topic, String payload, boolean retain, int qos);
    }

    private static volatile GuardStateReporter instance;

    private final Sender sender;
    private String letzterZustand;   // armed|stop|reason – ohne Zeitstempel
    private long seit;
    private long letztesSenden;

    GuardStateReporter(Sender sender) {
        this.sender = sender;
    }

    public static synchronized void start() {
        if (instance != null) return;
        GuardStateReporter r = new GuardStateReporter(GuardStateReporter::sendeUeberAlleVerbindungen);
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(t -> {
            Thread th = new Thread(t, "GuardStateReporter");
            th.setDaemon(true);
            return th;
        });
        exec.scheduleWithFixedDelay(() -> {
            try {
                r.melde(at.greenchili.surfmate.daemon.CameraDaemon.isSentryArmed(),
                        GuardStopMonitor.blocksArming(), GuardStopMonitor.aktuellerGrund(),
                        System.currentTimeMillis());
            } catch (Throwable t) {
                logger.warn("tick failed: " + t.getMessage());
            }
        }, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
        instance = r;
    }

    /** Zustand melden; veröffentlicht nur bei Änderung oder zur stündlichen Auffrischung. */
    synchronized boolean melde(boolean armed, boolean stopAktiv, String grund, long nowMs) {
        String z = armed + "|" + stopAktiv + "|" + (stopAktiv ? grund : null);
        boolean geaendert = !z.equals(letzterZustand);
        if (geaendert) seit = nowMs;
        if (!geaendert && nowMs - letztesSenden < REFRESH_MS) return false;
        JSONObject o = zustandJson(armed, stopAktiv, stopAktiv ? grund : null, seit);
        if (sender.send(TOPIC_STATE, o.toString(), true, -1)) {
            letzterZustand = z;
            letztesSenden = nowMs;
            return true;
        }
        // Nicht gesendet (keine Verbindung): beim nächsten Takt erneut versuchen.
        return false;
    }

    static JSONObject zustandJson(boolean armed, boolean stopAktiv, String grund, long seitMs) {
        JSONObject o = new JSONObject();
        try {
            o.put("armed", armed);
            o.put("guardStopActive", stopAktiv);
            o.put("guardStopReason", grund == null ? JSONObject.NULL : grund);
            o.put("since", seitMs / 1000);
        } catch (Exception ignored) {
        }
        return o;
    }

    static JSONObject schlafJson(String grund, long nowMs) {
        JSONObject o = new JSONObject();
        try {
            o.put("reason", grund == null ? "unbekannt" : grund);
            o.put("ts", nowMs / 1000);
        } catch (Exception ignored) {
        }
        return o;
    }

    /**
     * Direkt vor einem geplanten Trennen aufrufen. Sendet guard/sleep (QoS 1, nicht retained) und
     * frischt guard/state auf.
     */
    public static void schlaf(String grund) {
        GuardStateReporter r = instance;
        Sender s = r != null ? r.sender : GuardStateReporter::sendeUeberAlleVerbindungen;
        try {
            s.send(TOPIC_SLEEP, schlafJson(grund, System.currentTimeMillis()).toString(), false, 1);
            logger.info("guard/sleep gesendet: " + grund);
        } catch (Throwable t) {
            logger.warn("guard/sleep nicht gesendet: " + t.getMessage());
        }
    }

    private static boolean sendeUeberAlleVerbindungen(String topic, String payload, boolean retain, int qos) {
        at.greenchili.surfmate.mqtt.MqttConnectionManager mgr =
                at.greenchili.surfmate.daemon.CameraDaemon.getMqttConnectionManager();
        return mgr != null && mgr.publishToAll(topic, payload, retain, qos) > 0;
    }
}
