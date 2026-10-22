package at.greenchili.surfmate.logging;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single source of truth mapping a stable daemon KEY (used in API/IPC/Telegram
 * requests) to its on-disk log file path. Mirrors the native
 * DaemonAdapter.getLogFilePath table, but lives in the logging package so it's
 * reachable from the daemon process (UID 2000, no Android UI classes) too.
 *
 * Keys are short, lowercase, stable identifiers — safe to type into Telegram
 * (`/sendlog camera`) and to pass as a query param (`?daemon=camera`).
 */
public final class DaemonLogPaths {

    private DaemonLogPaths() {}

    // LinkedHashMap so the order is stable for "list available daemons" UIs.
    private static final Map<String, String> PATHS = new LinkedHashMap<>();
    static {
        PATHS.put("camera",     at.greenchili.surfmate.util.DaemonStorage.rebase("/data/user_de/0/com.android.shell/surfmate/cam_daemon.log"));
        PATHS.put("accsentry",  at.greenchili.surfmate.util.DaemonStorage.rebase("/data/user_de/0/com.android.shell/surfmate/acc_sentry_daemon.log"));
        PATHS.put("sentry",     at.greenchili.surfmate.util.DaemonStorage.rebase("/data/user_de/0/com.android.shell/surfmate/sentry_daemon.log"));
        PATHS.put("telegram",   at.greenchili.surfmate.util.DaemonStorage.rebase("/data/user_de/0/com.android.shell/surfmate/telegrambotdaemon.log"));
        PATHS.put("tailscale",  at.greenchili.surfmate.util.DaemonStorage.rebase("/data/user_de/0/com.android.shell/surfmate/.tailscale/tailscale.log"));
        PATHS.put("wireguard",  at.greenchili.surfmate.wireguard.WireGuardPaths.LOG);
    }

    /** @return the log path for a daemon key, or null if unknown. */
    public static String pathFor(String key) {
        if (key == null) return null;
        return PATHS.get(key.trim().toLowerCase());
    }

    /** Stable, ordered set of known daemon keys. */
    public static java.util.Set<String> keys() {
        return PATHS.keySet();
    }

    /** Comma-joined key list for help text / error messages. */
    public static String keyList() {
        return String.join(", ", PATHS.keySet());
    }
}
