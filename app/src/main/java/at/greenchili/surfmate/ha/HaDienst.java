package at.greenchili.surfmate.ha;

import at.greenchili.surfmate.logging.DaemonLogger;
import at.greenchili.surfmate.notifications.NotificationBus;
import at.greenchili.surfmate.notifications.NotificationEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Startet die HA-Hilfen im Kamera-Daemon: Wächter-Meldungen → {@code event: ereignis} (nur Art + Zeit, je Art
 * höchstens alle 20 s), mDNS-Ankündigung (nur mit {@code ha.mdns}, Standard aus).
 */
public final class HaDienst {

    private static final DaemonLogger logger = DaemonLogger.getInstance("HA");
    static final long ENTPRELLEN_MS = 20_000L;

    private static volatile boolean gestartet;
    private static final Map<String, Long> LETZTE = new HashMap<>();

    private HaDienst() {}

    public static synchronized void start() {
        if (gestartet) return;
        gestartet = true;
        NotificationBus.get().subscribe(HaDienst::weiter);
        java.util.concurrent.ScheduledExecutorService ex = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "HA-Anwesenheit");
            t.setDaemon(true);
            return t;
        });
        ex.scheduleWithFixedDelay(() -> {
            try {
                anwesenheitTakt();
            } catch (Throwable t) {
                logger.warn("Anwesenheit: " + t.getMessage());
            }
        }, 60, 30, java.util.concurrent.TimeUnit.SECONDS);
        try {
            HaMdns.start();
        } catch (Throwable t) {
            logger.warn("mDNS: " + t.getMessage());
        }
    }

    static void weiter(NotificationEvent e) {
        if (e == null) return;
        String art = HaEreignisse.artAus(e.category, e.data);
        if (art == null) return;
        if (!durchlassen(art, e.timestamp)) return;
        HaEreignisse.waechterEreignis(art, e.timestamp);
    }

    /** Je Art höchstens ein Ereignis in {@link #ENTPRELLEN_MS} (Meldungen kommen in Stufen mehrfach). */
    static synchronized boolean durchlassen(String art, long zeit) {
        Long l = LETZTE.get(art);
        if (l != null && zeit - l < ENTPRELLEN_MS && zeit >= l) return false;
        LETZTE.put(art, zeit);
        return true;
    }

    private static Anwesenheit anwesenheit;

    private static java.io.File anwesenheitDatei() {
        return new java.io.File(at.greenchili.surfmate.util.DaemonStorage.rebase(
                "/data/user_de/0/com.android.shell/surfmate"), "ha_anwesenheit.txt");
    }

    /** H5: alle 30 s Zone + ACC → ankunft/abfahrt (SSE, Webhook-Empfänger, MQTT {@code <base>/ereignis/<art>}). */
    static void anwesenheitTakt() {
        if (anwesenheit == null) {
            String alt = null;
            try {
                java.io.File f = anwesenheitDatei();
                if (f.exists()) alt = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8).trim();
            } catch (Exception ignored) { }
            anwesenheit = new Anwesenheit(alt);
        }
        String zone = at.greenchili.surfmate.surveillance.SafeLocationManager.getInstance().getCurrentZoneName();
        boolean aus = at.greenchili.surfmate.monitor.AccMonitor.isAccStateAuthoritative()
                && !at.greenchili.surfmate.monitor.AccMonitor.isAccOn();
        String[] e = anwesenheit.takt(zone, aus);
        if (e == null) return;
        long now = System.currentTimeMillis();
        try {
            String g = anwesenheit.gemeldet();
            java.nio.file.Files.write(anwesenheitDatei().toPath(),
                    (g == null ? "" : g).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
        org.json.JSONObject d = new org.json.JSONObject();
        try { d.put("ort", e[1]).put("zeit", now); } catch (Exception ignored) { }
        logger.info(e[0] + " (Zone)");
        HaEreignisse.melde(e[0], d);
        try {
            at.greenchili.surfmate.mqtt.MqttConnectionManager m =
                    at.greenchili.surfmate.daemon.CameraDaemon.getMqttConnectionManager();
            if (m != null) m.publishToAll("ereignis/" + e[0], d.toString(), false, 1);
        } catch (Throwable ignored) { }
    }

    /** Erschütterung (ShockMonitor), nur wenn wirklich eine Aufnahme startete. */
    public static void erschuetterung(long zeit) {
        if (durchlassen("erschuetterung", zeit)) HaEreignisse.waechterEreignis("erschuetterung", zeit);
    }
}
