package at.greenchili.surfmate.gesundheit;

import at.greenchili.surfmate.logging.DaemonLogger;
import at.greenchili.surfmate.server.Messages;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Rechte-Selbstheilung im Kamera-Daemon (läuft als {@code shell}): {@link RechteHeilung#durchgang} kurz nach dem
 * Start und danach täglich. Fehlt nach der Reparatur noch etwas, gibt es eine Meldung am Display und über Telegram
 * mit dem, was zu tun ist. Dieselbe Meldung höchstens einmal am Tag. Keine Fahrzeugfunktion, nur Rechte der App.
 */
public final class RechteHeilungsDienst {

    private static final DaemonLogger logger = DaemonLogger.getInstance("RechteHeilung");
    /** Erste Prüfung nach dem Start: nach dem {@code PermissionGranter}, der beim Start ohnehin alles setzt. */
    static final long ERSTE_PRUEFUNG_S = 5 * 60;
    static final long TAKT_S = 24L * 3600;
    private static final long BEFEHL_MAX_MS = 15_000L;

    private static volatile RechteHeilungsDienst instance;

    private final String pkg;
    private volatile JSONObject letzterStand = new JSONObject();
    private List<String> gemeldet = new ArrayList<>();
    private long gemeldetMs;

    private RechteHeilungsDienst(String pkg) {
        this.pkg = pkg;
    }

    public static synchronized void start(String pkg) {
        if (instance != null) return;
        RechteHeilungsDienst d = new RechteHeilungsDienst(pkg);
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "RechteHeilung");
            t.setDaemon(true);
            return t;
        });
        exec.scheduleWithFixedDelay(() -> {
            try {
                d.takt(System.currentTimeMillis());
            } catch (Throwable t) {
                logger.warn("takt: " + t.getMessage());
            }
        }, ERSTE_PRUEFUNG_S, TAKT_S, TimeUnit.SECONDS);
        instance = d;
    }

    /** Letzter Befund ({@code zustand}, {@code offen}, {@code repariert}, {@code ts}); leer vor der ersten Prüfung. */
    public static JSONObject status() {
        RechteHeilungsDienst d = instance;
        return d == null ? new JSONObject() : d.letzterStand;
    }

    void takt(long now) {
        RechteHeilung.Ergebnis e = RechteHeilung.durchgang(pkg, RechteHeilungsDienst::shell);
        JSONObject s = new JSONObject();
        try {
            s.put("zustand", e.zustand).put("offen", new JSONArray(e.offen)).put("repariert", new JSONArray(e.repariert))
                    .put("ts", now / 1000);
        } catch (Exception ignored) {
        }
        letzterStand = s;
        if ("geheilt".equals(e.zustand)) logger.warn("Rechte wiederhergestellt: " + e.repariert);
        else if ("offen".equals(e.zustand)) logger.warn("Rechte fehlen weiter: " + e.offen + " (repariert: " + e.repariert + ")");
        else logger.info("Rechte: " + e.zustand);
        if (RechteHeilung.meldenNoetig(e.offen, gemeldet, gemeldetMs, now)) {
            melden(e.offen);
            gemeldet = new ArrayList<>(e.offen);
            gemeldetMs = now;
        } else if (e.offen.isEmpty()) {
            gemeldet = new ArrayList<>();
            gemeldetMs = 0;
        }
    }

    /** Text der Meldung: Titel und was zu tun ist (Server-Kataloge, 5 Sprachen). */
    static String[] meldung(List<String> offen) {
        StringBuilder liste = new StringBuilder();
        for (String w : offen) {
            if (liste.length() > 0) liste.append(", ");
            liste.append(Messages.text("rechte_heilung.wort_" + w, w));
        }
        String titel = Messages.text("rechte_heilung.titel", "Panoptes has lost permissions");
        String text = Messages.text("rechte_heilung.text",
                "The car took these permissions away and Panoptes could not restore them: {0}. Please open Panoptes "
                        + "once on the car screen. If that does not help: Android settings, Apps, Panoptes, Permissions, "
                        + "and allow the ones listed.", liste.toString());
        return new String[] {titel, text};
    }

    private static void melden(List<String> offen) {
        String[] m = meldung(offen);
        try {
            at.greenchili.surfmate.byd.MessageOverlayController.showDialog(m[0], m[1], "OK", "warning", 0);
        } catch (Throwable t) {
            logger.warn("Display-Meldung: " + t.getMessage());
        }
        try {
            at.greenchili.surfmate.telegram.TelegramNotifier.sendMessage("⚠️ *" + m[0] + "*\n" + m[1], "CRITICAL");
        } catch (Throwable t) {
            logger.warn("Telegram-Meldung: " + t.getMessage());
        }
    }

    /** {@code sh -c} als {@code shell}, Ausgabe oder null; höchstens {@link #BEFEHL_MAX_MS}. */
    static String shell(String befehl) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[] {"sh", "-c", befehl});
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String z;
                while ((z = r.readLine()) != null && sb.length() < 512 * 1024) sb.append(z).append('\n');
            }
            if (!p.waitFor(BEFEHL_MAX_MS, TimeUnit.MILLISECONDS)) {
                p.destroy();
                return null;
            }
            return sb.toString();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            if (p != null) {
                try { p.getErrorStream().close(); } catch (Exception ignored) { }
            }
        }
    }
}
