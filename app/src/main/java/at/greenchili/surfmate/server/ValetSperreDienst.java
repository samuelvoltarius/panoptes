package at.greenchili.surfmate.server;

import at.greenchili.surfmate.logging.DaemonLogger;
import at.greenchili.surfmate.overlay.ValetRegel;

import org.json.JSONObject;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Daemon-Seite der Display-Sperre (Regeln in {@link ValetRegel}). Hält den Zustand NUR im Speicher (ein Neustart des
 * Daemons hebt die Sperre auf), prüft alle {@link #TAKT_MS} Gang, Tempo und App im Vordergrund und schickt dem
 * Overlay im App-Prozess „zeigen ja/nein“ über die geschützte Brücke. Nach je 5 Fehlversuchen eine Meldung ans Handy.
 */
public final class ValetSperreDienst {

    private static final DaemonLogger logger = DaemonLogger.getInstance("ValetSperre");
    static final long TAKT_MS = 1_500L;
    static final String KATEGORIE = "display.valet";

    /** Lage von außen (Tests ersetzen sie). */
    interface Lage {
        String bewegungsGrund();
        boolean rueckwaerts();
        String vordergrund();
    }

    /** Overlay erreichen (Tests ersetzen es). */
    interface Overlay {
        void senden(JSONObject befehl);
    }

    static final Lage ECHT = new Lage() {
        @Override public String bewegungsGrund() {
            try {
                return at.greenchili.surfmate.byd.routing.DrivingSafetyGuard.getMovementBlockReason();
            } catch (Throwable t) {
                return "state_unknown";
            }
        }

        @Override public boolean rueckwaerts() {
            try {
                at.greenchili.surfmate.monitor.GearMonitor g = at.greenchili.surfmate.monitor.GearMonitor.getInstance();
                return g != null && g.getCurrentGearIfFresh() == at.greenchili.surfmate.monitor.GearMonitor.GEAR_R;
            } catch (Throwable t) {
                return false;
            }
        }

        @Override public String vordergrund() {
            return vordergrundAus(AppVordergrund.lese());
        }
    };

    private static final Pattern VORDERGRUND = Pattern.compile("u\\d+ ([A-Za-z][A-Za-z0-9_.]*)/");

    /** Paket aus einer {@code mResumedActivity}-Zeile, sonst null. */
    static String vordergrundAus(String zeile) {
        if (zeile == null) return null;
        Matcher m = VORDERGRUND.matcher(zeile);
        return m.find() ? m.group(1) : null;
    }

    private static final ValetSperreDienst INSTANZ = new ValetSperreDienst(ECHT, befehl -> {
        try {
            new RemoteDevViewBridgeClient().send(befehl);
        } catch (Throwable ignored) {
        }
    });

    public static ValetSperreDienst get() {
        return INSTANZ;
    }

    private final ValetRegel regel = new ValetRegel();
    private final Lage lage;
    private final Overlay overlay;
    private volatile ValetRegel.Lage letzte = null;
    private ScheduledExecutorService takt;
    private ScheduledFuture<?> lauf;

    ValetSperreDienst(Lage lage, Overlay overlay) {
        this.lage = lage;
        this.overlay = overlay;
    }

    ValetRegel regel() {
        return regel;
    }

    /** Einschalten. Gründe siehe {@link ValetRegel#anschalten}. */
    public synchronized String anschalten(String pin) {
        String g = regel.anschalten(pin, lage.bewegungsGrund());
        if (g == null) {
            BefehlsLog.ereignis("display.sperre", "an", null, null);
            starteTakt();
            tick();
        }
        return g;
    }

    /** Aufheben ohne PIN (nur Login aus der Ferne, vom Handler geprüft). */
    public synchronized void aufheben(String grund) {
        if (!regel.aktiv()) return;
        regel.aufheben(grund);
        BefehlsLog.ereignis("display.sperre", "aus_" + grund, null, null);
        tick();
    }

    public ValetRegel.Versuch entsperren(String pin, long now) {
        ValetRegel.Versuch v;
        synchronized (this) {
            v = regel.entsperren(pin, now);
        }
        BefehlsLog.ereignis("display.sperre", v.entsperrt ? "entsperrt" : v.grund, null, null);
        if (v.melden) melden(v.fehlversuche);
        if (v.entsperrt) tick();
        return v;
    }

    /** Ein Takt: Lage prüfen, ggf. aufheben, Overlay bestätigen. Öffentlich für Tests. */
    public void tick() {
        ValetRegel.Lage l;
        boolean warAktiv;
        synchronized (this) {
            warAktiv = regel.aktiv();
            l = regel.takt(warAktiv ? lage.bewegungsGrund() : null, warAktiv && lage.rueckwaerts(),
                    warAktiv ? lage.vordergrund() : null, false);
        }
        if (warAktiv && !l.aktiv) {
            BefehlsLog.ereignis("display.sperre", "aus_" + l.grund, null, null);
            logger.info("Display-Sperre aufgehoben: " + l.grund);
        }
        letzte = l;
        try {
            overlay.senden(new JSONObject().put("command", "valet").put("zeigen", l.zeigen).put("aktiv", l.aktiv)
                    .put("texte", texte()));
        } catch (Exception ignored) {
        }
        if (!l.aktiv) stoppeTakt();
    }

    /** Für {@code GET /api/display}. */
    public JSONObject stand() throws Exception {
        ValetRegel.Lage l = letzte;
        JSONObject j = new JSONObject();
        j.put("aktiv", regel.aktiv());
        j.put("sichtbar", l != null && l.zeigen);
        j.put("grund", l == null || l.grund == null ? JSONObject.NULL : l.grund);
        j.put("fehlversuche", regel.fehlversuche());
        j.put("imStand", lage.bewegungsGrund() == null);
        return j;
    }

    private void melden(int fehlversuche) {
        try {
            JSONObject d = new JSONObject().put("fehlversuche", fehlversuche);
            at.greenchili.surfmate.notifications.NotificationBus.get().publish(
                    new at.greenchili.surfmate.notifications.NotificationEvent(KATEGORIE,
                            at.greenchili.surfmate.notifications.NotificationEvent.Severity.WARN,
                            Messages.text("display.valet_fehlversuche_titel", "Display lock: wrong PIN"),
                            Messages.text("display.valet_fehlversuche_text",
                                    "Someone entered a wrong PIN {0} times at the display.", fehlversuche),
                            "display-valet", "/local/system.html#anzeige", d));
        } catch (Throwable t) {
            logger.warn("Meldung Fehlversuche: " + t.getMessage());
        }
    }

    static JSONObject texte() {
        JSONObject t = new JSONObject();
        try {
            t.put("titel", Messages.text("display.valet_titel", "Display locked"));
            t.put("hilfe", Messages.text("display.valet_hilfe", "To unlock, press and hold, then enter the PIN."));
            t.put("pin", Messages.text("display.valet_pin", "PIN"));
            t.put("ok", Messages.text("display.valet_ok", "OK"));
            t.put("falsch", Messages.text("display.valet_falsch", "Wrong PIN."));
            t.put("warten", Messages.text("display.valet_warten", "Please wait {s} seconds."));
            t.put("fehler", Messages.text("display.valet_fehler", "Not possible right now. Please try again."));
        } catch (Exception ignored) {
        }
        return t;
    }

    private synchronized void starteTakt() {
        if (lauf != null && !lauf.isDone()) return;
        if (takt == null) {
            takt = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread th = new Thread(r, "valet-sperre");
                th.setDaemon(true);
                return th;
            });
        }
        lauf = takt.scheduleWithFixedDelay(() -> {
            try {
                tick();
            } catch (Throwable t) {
                logger.warn("Takt: " + t.getMessage());
            }
        }, TAKT_MS, TAKT_MS, TimeUnit.MILLISECONDS);
    }

    private synchronized void stoppeTakt() {
        if (lauf != null) lauf.cancel(false);
        lauf = null;
    }

    /** Liest die App im Vordergrund (eine Zeile von {@code dumpsys activity activities}). */
    static final class AppVordergrund {
        private static volatile long zuletzt;
        private static volatile String wert;

        static String lese() {
            long now = System.currentTimeMillis();
            if (now - zuletzt < 1_000L) return wert;
            zuletzt = now;
            String out = at.greenchili.surfmate.launcher.AppSperre.ECHT.run(java.util.Arrays.asList("sh", "-c",
                    "dumpsys activity activities 2>/dev/null | grep -m1 -E 'mResumedActivity|topResumedActivity'"));
            wert = out;
            return out;
        }
    }
}
