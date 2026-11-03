package at.greenchili.surfmate.tacho.oem;

import org.json.JSONObject;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import at.greenchili.surfmate.config.UnifiedConfigManager;
import at.greenchili.surfmate.logging.DaemonLogger;
import at.greenchili.surfmate.tacho.TachoFaehigkeit;

/**
 * Daemon-side, opt-in publisher for BYD's native instrument-cluster turn arrows.
 * Full app/map projection is deliberately not involved.
 */
public final class OemNavigationBridge {
    private static final DaemonLogger logger = DaemonLogger.getInstance("OemNavigationBridge");
    private static final long CAPABILITY_CACHE_MS = 10 * 60 * 1000L;
    private static final long STALE_MS = 12_000L;
    private static final int GUIDING = 1;
    private static final int STOPPED = 9;
    private static final int NO_ICON = -1;
    private static final ScheduledExecutorService watchdog =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "OemNavigationWatchdog");
                t.setDaemon(true);
                return t;
            });
    private static final AtomicLong generation = new AtomicLong();
    private static volatile long capabilityAt;
    private static volatile boolean capability;
    private static volatile boolean active;
    private static volatile boolean modeEnabled;

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
        if (!supported()) {
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

        if (!modeEnabled) {
            modeEnabled = OemNavigationTransport.enableNavigationMode();
            if (!modeEnabled) return out.put("status", "error").put("message", "mode rejected");
        }
        byte[] payload = NaviInfoPayload.build(GUIDING, "", distance,
                OemTurnIcon.fromValhalla(type), remainingSeconds, remainingDistance, exit);
        if (!OemNavigationTransport.sendNavigationPayload(payload)) {
            modeEnabled = false;
            return out.put("status", "error").put("message", "frame rejected");
        }
        active = true;
        long mine = generation.incrementAndGet();
        watchdog.schedule(() -> {
            synchronized (OemNavigationBridge.class) {
                if (active && generation.get() == mine) stopInternal("stale");
            }
        }, STALE_MS, TimeUnit.MILLISECONDS);
        return out.put("status", "ok").put("active", true);
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

    private static boolean supported() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (capabilityAt != 0L && now - capabilityAt < CAPABILITY_CACHE_MS) return capability;
        boolean result = false;
        try {
            TachoFaehigkeit.Eingaben input = TachoFaehigkeit.erhebe();
            JSONObject platform = TachoFaehigkeit.bewerte(input).optJSONObject("plattform");
            // Safe, proven subset: dual-OS DiLink 3/4 with the real AutoContainer service
            // and shell daemon. The raw property must explicitly say dual-OS: an absent
            // property is not evidence. DiLink 5 and Single-OS/Surf stay off until proven.
            result = supported(input, platform);
        } catch (Throwable t) {
            logger.warn("OEM navigation capability check failed: " + t.getMessage());
        }
        capability = result;
        capabilityAt = now;
        return result;
    }

    private static void stopInternal(String reason) {
        generation.incrementAndGet();
        if (active || modeEnabled) {
            byte[] clear = NaviInfoPayload.build(STOPPED, "", -1, NO_ICON,
                    -1, -1, 0);
            if (!OemNavigationTransport.sendNavigationPayload(clear)) {
                logger.warn("OEM navigation clear failed (" + reason + ")");
            }
        }
        active = false;
        modeEnabled = false;
    }

    static boolean isSupportedForTest(TachoFaehigkeit.Eingaben input) {
        try {
            JSONObject p = TachoFaehigkeit.bewerte(input).getJSONObject("plattform");
            return supported(input, p);
        } catch (Throwable ignored) { return false; }
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
