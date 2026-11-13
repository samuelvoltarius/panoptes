package at.greenchili.surfmate.tacho.oem;

import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import at.greenchili.surfmate.config.UnifiedConfigManager;
import at.greenchili.surfmate.logging.DaemonLogger;
import at.greenchili.surfmate.tacho.TachoFaehigkeit;

/**
 * Daemon-side, opt-in publisher for BYD's native instrument-cluster turn arrows.
 * Full app/map projection is deliberately not involved.
 *
 * <p>0.1.44 (N2a): two outputs. If a BYD AmapService package is installed and the platform is released,
 * frames go as the public Amap-Auto broadcast to that package ({@link AmapServiceBroadcast}); otherwise,
 * as before, directly to AutoContainer ({@link OemNavigationTransport}).
 */
public final class OemNavigationBridge {
    private static final DaemonLogger logger = DaemonLogger.getInstance("OemNavigationBridge");
    private static final long CAPABILITY_CACHE_MS = 10 * 60 * 1000L;
    private static final long STALE_MS = 12_000L;
    private static final long TICK_MS = 2_000L;
    private static final int GUIDING = 1;
    private static final int STOPPED = 9;
    private static final int NO_ICON = -1;

    static final int AUSGANG_KEIN = 0;
    static final int AUSGANG_AMAP = 1;
    static final int AUSGANG_AUTOCONTAINER = 2;

    private static final ScheduledExecutorService watchdog =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "OemNavigationWatchdog");
                t.setDaemon(true);
                return t;
            });
    private static volatile long capabilityAt;
    private static volatile int capability = AUSGANG_KEIN;
    private static volatile String capabilityPaket;
    private static volatile String capabilityGrund = "unsupported";
    private static volatile boolean active;
    private static volatile boolean modeEnabled;
    private static volatile int aktiverAusgang = AUSGANG_KEIN;
    private static volatile String aktivesPaket;
    private static volatile long letzterFrame;
    private static volatile boolean tickLaeuft;
    private static volatile AmapServiceBroadcast.Sender amapSender;

    private OemNavigationBridge() {}

    public static synchronized JSONObject handle(JSONObject command) throws Exception {
        JSONObject out = new JSONObject();
        String op = command == null ? "" : command.optString("op", "");
        if ("stop".equals(op)) {
            stopInternal("client");
            return out.put("status", "ok").put("active", false);
        }
        if (!"frame".equals(op)) return out.put("status", "error").put("message", "invalid op");
        if (!configured()) {
            if (active) stopInternal("disabled");
            return out.put("status", "blocked").put("reason", "disabled");
        }
        int weg = ausgang();
        if (weg == AUSGANG_KEIN) {
            if (active) stopInternal("unsupported");
            return out.put("status", "blocked").put("reason", "unsupported");
        }

        int type = command.optInt("maneuverType", -1);
        int distance = clamp(command.optInt("distanceMeters", -1), 0, 2_000_000);
        int remainingDistance = clamp(command.optInt("remainingMeters", -1), 0, 10_000_000);
        int remainingSeconds = clamp(command.optInt("remainingSeconds", -1), 0, 7 * 24 * 3600);
        int exit = OemTurnIcon.roundaboutExit(type, command.optInt("roundaboutExit", 0));
        if (type < 0 || distance < 0 || remainingDistance < 0 || remainingSeconds < 0) {
            return out.put("status", "error").put("message", "invalid frame");
        }
        // Fremd-Navi (Benachrichtigung) kennt keinen Restweg/keine Restzeit: dann nichts dazu senden.
        boolean mitRest = !command.optBoolean("ohneRest", false);

        if (active && aktiverAusgang != weg) stopInternal("output_changed");
        if (weg == AUSGANG_AMAP) {
            String paket = capabilityPaket;
            if (!sender().sende(paket, AmapServiceBroadcast.fuehrung(type, distance, remainingDistance,
                    remainingSeconds, exit, mitRest, Locale.getDefault()))) {
                return out.put("status", "error").put("message", "frame rejected");
            }
            aktivesPaket = paket;
        } else {
            if (!modeEnabled) {
                modeEnabled = OemNavigationTransport.enableNavigationMode();
                if (!modeEnabled) return out.put("status", "error").put("message", "mode rejected");
            }
            byte[] payload = NaviInfoPayload.build(GUIDING, "", distance,
                    OemTurnIcon.fromValhalla(type), mitRest ? remainingSeconds : 0,
                    remainingDistance, exit);
            if (!OemNavigationTransport.sendNavigationPayload(payload)) {
                modeEnabled = false;
                return out.put("status", "error").put("message", "frame rejected");
            }
        }
        active = true;
        aktiverAusgang = weg;
        letzterFrame = android.os.SystemClock.elapsedRealtime();
        startTick();
        return out.put("status", "ok").put("active", true)
                .put("output", weg == AUSGANG_AMAP ? "amapService" : "autoContainer");
    }

    /**
     * Waechter: alle 2 s. Ohne neuen Frame seit {@code STALE_MS} oder mit ausgeschaltetem Schalter
     * wird die Kombi-Anzeige beendet (Ende-Meldung), auch wenn die App nichts mehr schickt.
     */
    private static void startTick() {
        if (tickLaeuft) return;
        tickLaeuft = true;
        watchdog.scheduleWithFixedDelay(() -> {
            synchronized (OemNavigationBridge.class) {
                if (!active) return;
                long alter = android.os.SystemClock.elapsedRealtime() - letzterFrame;
                if (alter >= STALE_MS) stopInternal("stale");
                else if (!configured()) stopInternal("disabled");
            }
        }, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
    }

    private static int clamp(int value, int min, int max) {
        return value < min || value > max ? -1 : value;
    }

    private static boolean configured() {
        try {
            JSONObject nav = UnifiedConfigManager.loadConfig().optJSONObject("navMap");
            return nav != null && nav.optBoolean("oemTurnGuidance", false);
        } catch (Throwable ignored) { return false; }
    }

    /** DiLink-5-Freigabe nach Tester-Abnahme: ein Schalter, ab Werk aus, nicht in der Oberflaeche. */
    private static boolean dilink5Schalter() {
        try {
            JSONObject nav = UnifiedConfigManager.loadConfig().optJSONObject("navMap");
            return nav != null && nav.optBoolean(AmapServiceBroadcast.SCHALTER_DILINK5, false);
        } catch (Throwable ignored) { return false; }
    }

    private static AmapServiceBroadcast.Sender sender() {
        AmapServiceBroadcast.Sender s = amapSender;
        if (s == null) {
            s = AmapServiceBroadcast.androidSender(at.greenchili.surfmate.daemon.DaemonBootstrap.context());
            amapSender = s;
        }
        return s;
    }

    private static int ausgang() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (capabilityAt != 0L && now - capabilityAt < CAPABILITY_CACHE_MS) return capability;
        int result = AUSGANG_KEIN;
        String paket = null;
        JSONObject platform = null;
        try {
            TachoFaehigkeit.Eingaben input = TachoFaehigkeit.erhebe();
            platform = TachoFaehigkeit.bewerte(input).optJSONObject("plattform");
            paket = AmapServiceBroadcast.erkennePaket(AmapServiceBroadcast.androidInstalliert(
                    at.greenchili.surfmate.daemon.DaemonBootstrap.context()));
            result = waehle(input, platform, paket, dilink5Schalter());
        } catch (Throwable t) {
            logger.warn("OEM navigation capability check failed: " + t.getMessage());
        }
        capability = result;
        capabilityPaket = result == AUSGANG_AMAP ? paket : null;
        capabilityGrund = grund(result, platform);
        capabilityAt = now;
        return result;
    }

    /**
     * E2E 0.1.43b M2: Kann dieses Auto Abbiegepfeile im Fahrerdisplay? Fuer die Oberflaeche (Schalter ausgrauen).
     * Nur lesen (gleiche zwischengespeicherte Pruefung wie {@link #handle}), sendet nichts. Die Kernsperre in
     * {@link #handle} bleibt unabhaengig davon bestehen.
     */
    public static JSONObject verfuegbarkeit() throws org.json.JSONException {
        int weg = ausgang();
        JSONObject o = new JSONObject().put("moeglich", weg != AUSGANG_KEIN);
        if (weg == AUSGANG_KEIN) o.put("grund", capabilityGrund);
        return o;
    }

    /** Grund fuer „nicht moeglich“: single_os (Surf/Single-OS) oder unsupported; leer, wenn ein Weg da ist. */
    static String grund(int weg, JSONObject platform) {
        if (weg != AUSGANG_KEIN) return "";
        return platform != null && platform.optBoolean("singleOs", false) ? "single_os" : "unsupported";
    }

    /** Wegwahl: AmapService-Broadcast, wenn freigegeben; sonst AutoContainer im belegten Dual-OS-Teil; sonst nichts. */
    static int waehle(TachoFaehigkeit.Eingaben input, JSONObject platform, String amapPaket, boolean dilink5Schalter) {
        if (AmapServiceBroadcast.freigegeben(input, platform, amapPaket, dilink5Schalter)) return AUSGANG_AMAP;
        if (supported(input, platform)) return AUSGANG_AUTOCONTAINER;
        return AUSGANG_KEIN;
    }

    private static void stopInternal(String reason) {
        if (active || modeEnabled) {
            if (aktiverAusgang == AUSGANG_AMAP) {
                if (!sender().sende(aktivesPaket, AmapServiceBroadcast.ende())) {
                    logger.warn("AmapService navigation end failed (" + reason + ")");
                }
            } else {
                byte[] clear = NaviInfoPayload.build(STOPPED, "", -1, NO_ICON,
                        -1, -1, 0);
                if (!OemNavigationTransport.sendNavigationPayload(clear)) {
                    logger.warn("OEM navigation clear failed (" + reason + ")");
                }
            }
        }
        active = false;
        modeEnabled = false;
        aktiverAusgang = AUSGANG_KEIN;
        aktivesPaket = null;
    }

    static boolean isSupportedForTest(TachoFaehigkeit.Eingaben input) {
        try {
            JSONObject p = TachoFaehigkeit.bewerte(input).getJSONObject("plattform");
            return supported(input, p);
        } catch (Throwable ignored) { return false; }
    }

    static int ausgangFuerTest(TachoFaehigkeit.Eingaben input, String amapPaket, boolean dilink5Schalter) {
        try {
            JSONObject p = TachoFaehigkeit.bewerte(input).getJSONObject("plattform");
            return waehle(input, p, amapPaket, dilink5Schalter);
        } catch (Throwable ignored) { return AUSGANG_KEIN; }
    }

    private static boolean supported(TachoFaehigkeit.Eingaben input, JSONObject p) {
        if (input == null || p == null) return false;
        String rawSingleOs = input.singleOs == null ? "" : input.singleOs.trim();
        boolean explicitDualOs = "0".equals(rawSingleOs)
                || "false".equalsIgnoreCase(rawSingleOs);
        String dilink = p.optString("dilink", "");
        return explicitDualOs
                && !p.optBoolean("singleOs", true)
                && p.optBoolean("autoContainer", false)
                && p.optBoolean("shellRechte", false)
                && ("3".equals(dilink) || "4".equals(dilink));
    }
}
