package at.greenchili.surfmate.updater;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.util.Log;

import at.greenchili.surfmate.BuildConfig;
import at.greenchili.surfmate.launcher.AdbShellExecutor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Checks GitHub Releases for app updates and handles download + silent install.
 *
 * Release model:
 * - Fixed tags: "alpha", "debug", "prod" (future)
 * - APK is replaced in-place on the same release
 * - Update detection: compare asset updated_at vs last installed timestamp
 * - Debug tag is ignored in release builds
 *
 * API: 
 */
public class AppUpdater {

    private static final String TAG = "AppUpdater";
    // Kein GitHub-Abruf mehr (F16, 0.1.9.1): Updates nur über appupdate/AppUpdateDienst (panoptes-releases).
    private static final String GITHUB_REPO = "";
    // SurfMate is installed by its owner via adb; it never pulls updates itself.
    private static final boolean UPDATES_DISABLED = true;
    private static final String PREFS_NAME = "app_updater";
    // LEGACY (pre-channel) baseline key/file. Still read once by
    // migrateBaseline() to seed the per-channel "alpha" slot, then unused.
    // The live baseline is per-channel — see prefKeyForChannel /
    // timestampFileForChannel — so a braveheart asset timestamp can never
    // false-suppress against a stored alpha timestamp (or vice-versa).
    private static final String PREF_LAST_UPDATE_TIME = "last_update_timestamp";
    private static final String PREF_JUST_UPDATED = "just_updated";
    private static final String PREF_UPDATED_VERSION = "updated_version";
    // The `ts` of the last failed-install progress record this app process has
    // already surfaced. consumeFailedUpdateError leaves the phase=error record
    // in /data/user_de/0/com.android.shell/surfmate/surfmate_update_progress.json IN PLACE (the
    // app-process unlink in a sticky shell-owned dir is a no-op anyway, and the
    // record must survive for the web's reconnect re-read once the daemon is
    // back up — otherwise the web falls through to a false "Updated to X").
    // This flag is the one-shot guard so a later NORMAL launch doesn't re-toast
    // the same failure; a NEW failure carries a different `ts` and re-arms.
    private static final String PREF_LAST_CONSUMED_FAILURE_TS = "last_consumed_failure_ts";
    // Also persist to filesystem (survives app reinstall, unlike SharedPreferences)
    private static final String UPDATE_TIMESTAMP_FILE = at.greenchili.surfmate.util.DaemonStorage.rebase("/data/user_de/0/com.android.shell/surfmate/surfmate_update_timestamp");

    /** Per-channel SharedPreferences baseline key. */
    private static String prefKeyForChannel(String channel) {
        return PREF_LAST_UPDATE_TIME + "_" + channel;
    }
    /** Per-channel filesystem baseline path (survives app reinstall). */
    private static String timestampFileForChannel(String channel) {
        return UPDATE_TIMESTAMP_FILE + "_" + channel;
    }

    /** Channels the app understands. Used to validate runtime selection. */
    public static final String CHANNEL_ALPHA = "alpha";
    public static final String CHANNEL_BRAVEHEART = "braveheart";

    // ==================== SHARED INSTALL GATE ====================
    //
    // One install at a time, ACROSS all three trigger surfaces (web HTTP,
    // app IPC, Telegram IPC) — they all run downloadAndInstall in the SAME
    // camera-daemon JVM, so a single static gate serializes them. Before this
    // existed, only the web path (UpdateApiHandler) had an installInFlight
    // flag; the IPC path (app + Telegram) ignored it, so two near-simultaneous
    // installs could each spawn the detached install script + pkill cascade,
    // and a queued second downloadAndInstall could rm the APK the first was
    // about to pm-install (spurious INSTALL_PARSE_FAILED + bogus rollback).
    //
    // tryBeginInstall() CAS-acquires; endInstall() releases. The detached
    // install path deliberately never calls onSuccess (the process dies first),
    // so the flag would latch — INSTALL_STALE_MS self-recovers it, mirroring the
    // progress-file staleness window. Callers MUST endInstall() on every
    // pre-spawn bail and on onError so a failed install before process death
    // doesn't wedge the gate.
    private static volatile boolean installInFlight = false;
    // MONOTONIC (System.nanoTime) — this head unit picks up cell time, and a
    // forward wall-clock correction mid-install would instantly "age" the
    // gate past staleness, handing a second update the right to clobber the
    // first (a backward jump would merely delay recovery). 0 == unset.
    private static volatile long installStartedAtNanos = 0;
    // Staleness MUST exceed the complete legitimate workflow, otherwise a
    // second update can seize the gate mid-first and clobber the shared
    // /data/user_de/0/com.android.shell/surfmate staging (the exact race the gate exists to prevent).
    // Worst case: GitHub API (~30s) + download DOWNLOAD_DEADLINE_MS (640s)
    // + wait margin + size check (~60s) + kill cascade prep (~60s) +
    // install INSTALL_DEADLINE_MS (300s) + wait margin + rollback shell
    // commands ≈ 1150s. 30 minutes clears that with real margin.
    //
    // Age-based takeover is only sound because EVERY leg is absolutely
    // bounded: the ADB tunnel via the bulk-lane watchdogs, the direct
    // OkHttp download via doStreamingDownload's shared monotonic budget
    // (per-read timeouts alone never bound a slow-but-flowing stream), and
    // direct process execution via runShellDirect's destroy-at-deadline
    // reaper. The flag is static/in-JVM — process death (the SUCCESS path
    // of a core install) clears it instantly — so this threshold only
    // delays recovery from a missed endInstall() bug, never normal use.
    private static final long INSTALL_STALE_MS = 30 * 60 * 1000L;

    /**
     * Try to acquire the single-install gate. Returns true if the caller now
     * owns the install (must pair with {@link #endInstall()} on failure paths),
     * false if an install is already in flight and not yet stale.
     */
    public static synchronized boolean tryBeginInstall() {
        if (installInFlight) {
            // Self-recover a wedged flag: if the prior install started more than
            // INSTALL_STALE_MS ago and this process is still alive, the kill
            // cascade clearly missed us — let a new attempt through.
            long ageMs = (System.nanoTime() - installStartedAtNanos) / 1_000_000L;
            if (installStartedAtNanos != 0 && ageMs > INSTALL_STALE_MS) {
                Log.w(TAG, "Clearing stale installInFlight (started " + ageMs + "ms ago)");
            } else {
                return false;
            }
        }
        installInFlight = true;
        installStartedAtNanos = System.nanoTime();
        return true;
    }

    /** Release the install gate (call on pre-spawn bail or onError). */
    public static synchronized void endInstall() {
        installInFlight = false;
    }
    // Version file readable by daemon process (SharedPreferences are per-process)
    public static final String VERSION_FILE = at.greenchili.surfmate.util.DaemonStorage.rebase("/data/user_de/0/com.android.shell/surfmate/surfmate_version");
    // Sentinels for the post-update handshake (see UpdateLifecycle).
    private static final String UPDATE_IN_PROGRESS_FILE = UpdateLifecycle.UPDATE_IN_PROGRESS_FILE;

    /**
     * Build a ps+awk+kill snippet that kills processes whose argv contains
     * {@code pattern}, excluding the calling shell's own PID. Replaces
     * every {@code pkill -9 -f '<pattern>'} that used to live in shell
     * payloads — pkill -f matches against /proc/&lt;pid&gt;/cmdline, so the
     * calling sh -c wrapper (whose cmdline contains the literal pattern)
     * self-matches and gets SIGKILLed before subsequent commands run.
     * ps+awk+kill keys on PID instead, with a {@code $$} guard.
     */
    private static String psAwkKillLine(String pattern) {
        return "MY_PID=$$; ps -A -o PID,ARGS | grep -F '" + pattern + "' | grep -v grep "
            + "| awk '{print $1}' | while read pid; do "
            + "if [ \"$pid\" != \"$MY_PID\" ]; then kill -9 $pid 2>/dev/null; fi; done\n";
    }

    private static boolean isDiLink5ModeSelected() {
        try {
            at.greenchili.surfmate.camera.dilink5.DiLink5Platform
                    .refreshActiveMode();
            return at.greenchili.surfmate.camera.dilink5.DiLink5Platform
                    .isSelected();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String diLink5CaptureKillScript() {
        if (!isDiLink5ModeSelected()) return "";
        return psAwkKillLine("fast_cam_capture")
                + psAwkKillLine("qcarcam_test")
                + "killall -9 fast_cam_capture 2>/dev/null\n"
                + "killall -9 qcarcam_test 2>/dev/null\n";
    }

    private static String diLink5CaptureVerificationScript() {
        if (!isDiLink5ModeSelected()) return "";
        return "CAPTURE_PIDS=$(ps -A -o PID,ARGS | awk -v self=$$ "
                + "'$1 != self && ($0 ~ /fast_cam_capture/ || "
                + "$0 ~ /qcarcam_test/) {print $1}')\n"
                + "if [ -n \"$CAPTURE_PIDS\" ]; then "
                + "echo \"camera capture child still running: $CAPTURE_PIDS\" >&2; "
                + "exit 1; fi\n";
    }
    private static final String POST_UPDATE_FILE = UpdateLifecycle.POST_UPDATE_FILE;

    private final Context context;
    private volatile boolean cancelled = false;
    /**
     * Single-threaded executor SHARED across every {@link AppUpdater}
     * instance. MainActivity instantiates a fresh AppUpdater for the
     * periodic 6h check, manual checks, and the actual install — and the
     * daemon-process API handler does the same. Per-instance executors
     * leaked one non-daemon thread per check, accumulating across days.
     * One static executor with a daemon thread keeps the process clean
     * across instance churn AND serializes concurrent install attempts
     * naturally (the second one waits behind the first).
     */
    private static final ExecutorService executor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "AppUpdater");
                t.setDaemon(true);
                return t;
            });
    // Null in the daemon process — Looper.getMainLooper() returns null when no
    // thread has been designated as the main looper (the daemon's main() only
    // does Looper.prepare(), not prepareMainLooper()). Callbacks fall back to
    // inline execution; see runCallback().
    private final Handler mainHandler = resolveMainHandler();

    private static Handler resolveMainHandler() {
        Looper looper = Looper.getMainLooper();
        return looper != null ? new Handler(looper) : null;
    }

    private void runCallback(Runnable r) {
        if (mainHandler != null) mainHandler.post(r);
        else r.run();
    }

    /**
     * "Are we in a process that can write {@code /data/user_de/0/com.android.shell/surfmate}?"
     * Used to decide between the direct OkHttp path and the
     * AdbDaemonLauncher-tunnelled shell path.
     *
     * UID 2000 (shell) is the canonical answer — that's what the daemon
     * runs as via app_process. But some vendor-modded BYD ROMs launch the
     * daemon as system (UID 1000) or root (UID 0). A bare `myUid() == 2000`
     * check would silently route those into the slow shell-tunnel path
     * even though they have direct write access. Probe the actual
     * permission once and cache.
     */
    private static volatile Boolean canWriteTmpCached = null;

    private static boolean canWriteLocalTmp() {
        Boolean cached = canWriteTmpCached;
        if (cached != null) return cached;
        File probe = new File(at.greenchili.surfmate.util.DaemonStorage.rebase("/data/user_de/0/com.android.shell/surfmate/.surfmate_updater_probe"));
        boolean ok;
        try {
            try (FileOutputStream fos = new FileOutputStream(probe)) {
                fos.write((byte) 0);
                fos.flush();
            }
            ok = true;
        } catch (Exception e) {
            ok = false;
        } finally {
            try { probe.delete(); } catch (Exception ignored) {}
        }
        canWriteTmpCached = ok;
        return ok;
    }
    private AdbShellExecutor adb; // Lazy — only created when install is triggered
    private at.greenchili.surfmate.launcher.AdbDaemonLauncher adbLauncher; // For daemon management

    private String latestDownloadUrl;
    private long latestApkSizeBytes;
    private String releaseNotes;
    private String remoteVersion;
    private String remoteUpdatedAt;
    /**
     * Channel the pending install BELONGS to, captured at the SEED point
     * (checkForUpdate = the resolved channel; prepareInstall = alpha, since the
     * catalog only surfaces alpha tags). downloadAndInstall keys its
     * per-channel baseline advance/rollback on THIS, not a re-resolve at
     * install start — otherwise a channel toggle racing an in-flight install,
     * or an alpha-pick whose tag's channel differs from the live channel, would
     * advance/roll back the WRONG channel's baseline.
     */
    private volatile String pendingChannel;

    /**
     * Build an OkHttpClient that routes through whichever proxy the rest of
     * the app is using — probed via {@link at.greenchili.surfmate.mqtt.ProxyHelper}
     * so we share a single probe cache with MQTT, ABRP, BYD Cloud, and
     * the Telegram daemon (no separate timing windows where one consumer
     * thinks the proxy is up and another doesn't).
     *
     * The proxy is installed as a per-call selector returning
     * {@code ProxyHelper.proxyRouteChain(...)} — the live proxy first, DIRECT
     * second — never a frozen {@code builder.proxy(...)}. The probe is a blind
     * loopback connect and prefers the Tailscale SOCKS port, which on most
     * cars is tailnet-only: api.github.com is unreachable THROUGH it while the
     * listener itself stays healthy. A frozen proxy therefore wedged every
     * update check for as long as that listener was up (the streaming APK
     * download already carried its own manual proxy→direct retry; the
     * metadata client used to fail before ever reaching it). With the chain,
     * OkHttp falls through to direct in the same call, and sing-box /
     * exit-node cars keep proxy-first behavior.
     *
     * Both timeouts are in seconds. Pass {@code 0} for {@code readTimeout}
     * when streaming a large body — we still want a connect deadline but
     * we don't want OkHttp to abort a healthy slow download as "read
     * timed out". For tiny JSON fetches (release metadata) keep both
     * non-zero.
     */
    private static OkHttpClient buildClient(long connectTimeout, long readTimeout) {
        return new OkHttpClient.Builder()
                .connectTimeout(connectTimeout, TimeUnit.SECONDS)
                .readTimeout(readTimeout, TimeUnit.SECONDS)
                .followRedirects(true)
                .proxySelector(new java.net.ProxySelector() {
                    @Override
                    public java.util.List<java.net.Proxy> select(java.net.URI uri) {
                        java.net.Proxy proxy =
                                at.greenchili.surfmate.mqtt.ProxyHelper.getHttpProxy();
                        if (proxy != null && proxy.type() != java.net.Proxy.Type.DIRECT) {
                            Log.d(TAG, "AppUpdater HTTP via proxy " + proxy.address()
                                    + " (direct fallback armed)");
                        }
                        return at.greenchili.surfmate.mqtt.ProxyHelper.proxyRouteChain(proxy);
                    }

                    @Override
                    public void connectFailed(
                            java.net.URI uri,
                            java.net.SocketAddress address,
                            java.io.IOException failure) {
                        // Proxy leg failed (OkHttp never reports DIRECT here):
                        // re-probe on the next call instead of pinning a dead
                        // route for the rest of the cache window.
                        at.greenchili.surfmate.mqtt.ProxyHelper.invalidateCache();
                    }
                })
                .build();
    }

    public interface UpdateCallback {
        void onUpdateAvailable(String currentVersion, String newVersion, String releaseNotes);
        void onNoUpdate(String currentVersion);
        void onError(String error);
    }

    public interface InstallCallback {
        void onProgress(String message);
        void onDownloadProgress(int percent);
        void onSuccess();
        void onError(String error);
    }

    public AppUpdater(Context context) {
        this.context = context;
        // Cleanup runs without ADB — just deletes from app's own external files dir
        cleanupLeftoverApk();
    }

    private AdbShellExecutor getAdb() {
        if (adb == null) {
            adb = new AdbShellExecutor(context);
        }
        return adb;
    }

    private at.greenchili.surfmate.launcher.AdbDaemonLauncher getAdbLauncher() {
        if (adbLauncher == null) {
            adbLauncher = new at.greenchili.surfmate.launcher.AdbDaemonLauncher(context);
        }
        return adbLauncher;
    }

    /**
     * Release per-instance ADB executor + tunnel-poll scheduler that this
     * AppUpdater lazily allocated. AppUpdater is NOT a process singleton
     * — it's `new`'d at every call site (MainActivity manual-check, the
     * 6h periodic check, UpdateApiHandler, SettingsAboutFragment,
     * SurveillanceIpcServer). Without this method, each instance that
     * touched the ADB-tunnel path stranded one non-daemon executor
     * thread + one tunnel-poll scheduler thread for the life of the
     * process. After a week of parking that's ~50+ zombie threads in
     * /proc/self/status.
     *
     * Callers SHOULD invoke close() in a finally block once they're done
     * with the AppUpdater instance. Idempotent.
     *
     * Uses releasePerInstanceResources (NOT closePersistentConnection)
     * so we don't null the process-wide shared Dadb that other launchers
     * are using.
     */
    public void close() {
        try {
            if (adbLauncher != null) {
                adbLauncher.releasePerInstanceResources();
                adbLauncher = null;
            }
        } catch (Exception ignored) {}
        try {
            if (adb != null) {
                adb.shutdown();
                adb = null;
            }
        } catch (Exception ignored) {}
    }

    /**
     * Run a shell command, picking the right executor for the current process.
     *
     * The app process (UID 10xxx) needs to elevate to UID 2000 to write
     * /data/user_de/0/com.android.shell/surfmate and call `pm install`, so it goes through the ADB-shell
     * tunnel ({@link at.greenchili.surfmate.launcher.AdbDaemonLauncher}).
     *
     * The daemon process is ALREADY UID 2000 (it was launched via app_process
     * by the same ADB tunnel at startup), so it can — and must — execute
     * shell commands directly. Routing daemon-side calls through the ADB
     * tunnel fails on every BYD head unit because dadb tries to read the
     * app's adbkey at /data/user/0/at.greenchili.surfmate/files/adbkey, and that
     * directory is mode 0700 owned by the app UID — UID 2000 can't open it
     * (EACCES). The user reported this as
     * "Install failed: Download failed: ERROR: Execution failed:
     *  /data/user/0/at.greenchili.surfmate/files/adbkey: open failed:
     *  EACCES (Permission denied)".
     *
     * Direct exec is also faster (no socket round-trip per command).
     *
     * Callback semantics match {@link at.greenchili.surfmate.launcher.AdbDaemonLauncher.LaunchCallback}:
     * onLog gets the combined stdout/stderr, then onLaunched fires on success
     * (exit 0) or onError on a non-zero exit / spawn failure.
     */
    // ── Bulk-lane deadlines and their caller-side wait ceilings ─────────
    // INVARIANT: every Java-side wait on a bulk command MUST outlast that
    // command's executor deadline. If the wait expires first, the updater
    // declares failure, deletes staged files and rolls back version
    // metadata while wget / pm install is STILL RUNNING on the dedicated
    // connection — the two halves then fight (a "failed" download completes
    // into a just-deleted path; a "failed" install lands anyway after the
    // rollback). The executor guarantees a callback within its deadline
    // (the watchdog closes the dedicated connection), so waiting
    // deadline+margin can never hang.
    private static final long DOWNLOAD_DEADLINE_MS = 640_000L; // cmd self-caps at 600s
    private static final long INSTALL_DEADLINE_MS = 300_000L;
    private static final long WAIT_MARGIN_MS = 20_000L;

    /**
     * Deadline-bounded wait on the boolean-array flag/monitor idiom used by
     * every shell callback in this class. Loops (spurious wakeups and stray
     * notifies must not be read as completion) and re-checks the flag under
     * the monitor.
     */
    private static void awaitFlag(boolean[] flag, long timeoutMs) throws InterruptedException {
        // Monotonic clock: currentTimeMillis() jumps with NTP/user clock
        // changes (this head unit picks up cell time mid-drive), which would
        // silently stretch or collapse the wait.
        final long deadlineNanos = System.nanoTime() + timeoutMs * 1_000_000L;
        synchronized (flag) {
            while (!flag[0]) {
                long remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L;
                if (remainingMs <= 0) return;
                flag.wait(remainingMs);
            }
        }
    }

    private void runShell(String command,
                          at.greenchili.surfmate.launcher.AdbDaemonLauncher.LaunchCallback callback) {
        // Tunnel through ADB if this process can't write /data/user_de/0/com.android.shell/surfmate
        // directly (the canonical app-process state). See canWriteLocalTmp
        // for why this beats a UID==2000 check.
        if (!canWriteLocalTmp()) {
            getAdbLauncher().executeShellCommand(command, callback);
            return;
        }
        runShellDirect(command, callback);
    }

    /**
     * Variant of {@link #runShell} for LONG-RUNNING commands — the APK
     * download ({@code timeout 600 wget -q …}: up to 600s with zero output)
     * and {@code pm install} (tens of seconds to minutes on slow flash).
     *
     * On the ADB-tunnel path these go over the BULK lane: a dedicated,
     * per-operation dadb connection bounded by {@code deadlineMs}. The shared
     * control connection has a per-command deadline and a socket read timeout
     * sized for control traffic; a long-quiet transfer there would either be
     * severed early or serialize every daemon health-check/stop command
     * behind it for minutes. The dedicated connection removes both failure
     * modes without loosening the control lane's bounds.
     *
     * The daemon-process path (direct exec) is unchanged — no dadb involved.
     */
    private void runShellLong(String command, long deadlineMs,
                              at.greenchili.surfmate.launcher.AdbDaemonLauncher.LaunchCallback callback) {
        if (!canWriteLocalTmp()) {
            getAdbLauncher().executeShellCommandBulk(command, deadlineMs, callback);
            return;
        }
        // Direct leg gets the SAME absolute deadline as the tunnel leg: a
        // wedged `pm install` would otherwise hold runShellLong (and the
        // install gate) forever, and the gate's age-based takeover would
        // eventually hand a second update the right to clobber this one.
        runShellDirect(command, deadlineMs, callback);
    }

    /** Daemon-process leg of {@link #runShell} (unbounded, short commands). */
    private void runShellDirect(String command,
                                at.greenchili.surfmate.launcher.AdbDaemonLauncher.LaunchCallback callback) {
        runShellDirect(command, 0, callback);
    }

    /**
     * Daemon-process leg shared by {@link #runShell} / {@link #runShellLong}.
     *
     * Runs SYNCHRONOUSLY on the caller's thread (don't bounce onto
     * AppUpdater.executor — downloadAndInstall already runs there and is
     * single-threaded, so queueing more work would deadlock against the
     * wait() that follows each call site). The synchronous callback fires
     * before return, so the caller's notify/wait pattern still works.
     *
     * With {@code deadlineMs > 0}, a reaper thread kills the ENTIRE process
     * tree at the deadline. destroyForcibly() alone is insufficient twice
     * over: it SIGKILLs only the outer `sh -c` wrapper, so its `pm install`
     * child keeps running (and can land an install AFTER the caller reported
     * failure and rolled back), and that surviving child also inherits the
     * wrapper's pipe fds, so the readLine loops below stay blocked despite
     * the wrapper being dead. The wrapper therefore records its own PID
     * ($$) to a nonce file at spawn, and the reaper kills the transitive
     * subtree rooted there (see {@link #buildTreeKillScript}) before the
     * destroyForcibly belt — killing every fd holder is also what actually
     * EOFs the pipes. deadlineMs == 0 preserves the historical unbounded
     * behavior for {@link #runShell}'s short commands.
     */
    private void runShellDirect(String command, long deadlineMs,
                                at.greenchili.surfmate.launcher.AdbDaemonLauncher.LaunchCallback callback) {
        String pidFile = null;
        try {
            String execCommand = command;
            if (deadlineMs > 0) {
                pidFile = at.greenchili.surfmate.util.DaemonStorage.rebase("/data/user_de/0/com.android.shell/surfmate/.appupdater_pid_") + System.nanoTime();
                execCommand = "echo $$ > " + pidFile + "; " + command;
            }
            final String pidFileForReaper = pidFile;
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", execCommand});
            Thread reaper = null;
            if (deadlineMs > 0) {
                reaper = new Thread(() -> {
                    try {
                        if (!p.waitFor(deadlineMs, TimeUnit.MILLISECONDS)) {
                            Log.w(TAG, "Direct command exceeded " + deadlineMs
                                    + "ms deadline — killing its process tree");
                            killProcessTree(pidFileForReaper);
                            p.destroyForcibly();   // belt for the wrapper itself
                        }
                    } catch (InterruptedException ignored) {
                        // Normal completion interrupts the reaper.
                    }
                }, "AppUpdater-cmdDeadline");
                reaper.setDaemon(true);
                reaper.start();
            }
            StringBuilder out = new StringBuilder();
            int exit;
            try {
                java.io.BufferedReader stdout = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream()));
                java.io.BufferedReader stderr = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getErrorStream()));
                String line;
                while ((line = stdout.readLine()) != null) {
                    out.append(line).append('\n');
                }
                while ((line = stderr.readLine()) != null) {
                    out.append(line).append('\n');
                }
                exit = p.waitFor();
            } finally {
                if (reaper != null) reaper.interrupt();
            }
            String combined = out.toString().trim();
            if (!combined.isEmpty()) callback.onLog(combined);
            if (exit == 0) {
                callback.onLaunched();
            } else {
                callback.onError("Exit " + exit + ": " + combined);
            }
        } catch (Exception e) {
            callback.onError("Execution failed: " + e.getMessage());
        } finally {
            // The kill script also rm's it on the timeout path; the success
            // path of a core install kills this process before reaching here
            // and leaks one ~8-byte file, cleaned by the next update's
            // leftover sweep.
            if (pidFile != null) {
                try { new java.io.File(pidFile).delete(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Kill the transitive process subtree rooted at the PID stored in
     * {@code pidFile}, then the root itself. One `ps -A -o PID,PPID`
     * snapshot; awk computes the descendant closure (a `pm`/`am` wrapper
     * exec()s into app_process at the same depth, but the closure walk makes
     * no depth assumption). Runs as a separate bounded shell so a wedged
     * `ps` can't strand the reaper thread.
     */
    private static void killProcessTree(String pidFile) {
        try {
            Process killer = Runtime.getRuntime().exec(
                    new String[]{"sh", "-c", buildTreeKillScript(pidFile)});
            if (!killer.waitFor(10, TimeUnit.SECONDS)) {
                killer.destroyForcibly();
            }
        } catch (Exception e) {
            Log.w(TAG, "process-tree kill failed: " + e.getMessage());
        }
    }

    /** See {@link #killProcessTree}. */
    private static String buildTreeKillScript(String pidFile) {
        return "P=$(cat " + pidFile + " 2>/dev/null); "
                + "if [ -n \"$P\" ]; then "
                +   "ps -A -o PID,PPID 2>/dev/null | awk -v root=\"$P\" '"
                +     "NR>1 { pid[NR]=$1; ppid[NR]=$2 } "
                +     "END { mark[root]=1; added=1; "
                +       "while (added) { added=0; "
                +         "for (i=2;i<=NR;i++) if (!mark[pid[i]] && mark[ppid[i]]) { mark[pid[i]]=1; added=1 } "
                +       "} "
                +       "for (i=2;i<=NR;i++) if (mark[pid[i]] && pid[i]!=root) print pid[i] "
                +     "}' | while read k; do kill -9 \"$k\" 2>/dev/null; done; "
                +   "kill -9 \"$P\" 2>/dev/null; "
                + "fi; "
                + "rm -f " + pidFile;
    }

    /**
     * Execute a multi-command shell payload via a temp script file. Use this
     * for any payload containing `pkill -f '<pattern>'` whose pattern also
     * appears as a literal substring in earlier commands of the same payload
     * — `sh -c "<payload>"` puts the whole payload in argv[2], and toybox
     * `pkill -f` would match the calling shell itself, SIGKILLing it before
     * later commands run.
     *
     * Routes through ADB or direct exec depending on the canonical
     * /data/user_de/0/com.android.shell/surfmate writability check used by runShell.
     */
    private void runShellScript(String scriptBody,
                                at.greenchili.surfmate.launcher.AdbDaemonLauncher.LaunchCallback callback) {
        if (!canWriteLocalTmp()) {
            getAdbLauncher().executeShellScript(scriptBody, callback);
            return;
        }
        // Direct path — write the script to a tmp file ourselves and exec it.
        // Same self-match defense as the ADB path: the running shell's argv
        // is just `sh <path>`, no daemon pattern visible to pkill.
        String scriptPath = at.greenchili.surfmate.util.DaemonStorage.rebase("/data/user_de/0/com.android.shell/surfmate/.appupdater_script_") + System.nanoTime() + ".sh";
        try {
            java.io.File scriptFile = new java.io.File(scriptPath);
            try (java.io.FileWriter fw = new java.io.FileWriter(scriptFile)) {
                fw.write(scriptBody);
            }
            scriptFile.setExecutable(true, false);

            Process p = Runtime.getRuntime().exec(new String[]{"sh", scriptPath});
            StringBuilder out = new StringBuilder();
            java.io.BufferedReader stdout = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            java.io.BufferedReader stderr = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getErrorStream()));
            String line;
            while ((line = stdout.readLine()) != null) out.append(line).append('\n');
            while ((line = stderr.readLine()) != null) out.append(line).append('\n');
            int exit = p.waitFor();
            String combined = out.toString().trim();
            if (!combined.isEmpty()) callback.onLog(combined);
            if (exit == 0) {
                callback.onLaunched();
            } else {
                callback.onError("Exit " + exit + ": " + combined);
            }
        } catch (Exception e) {
            callback.onError("Script execution failed: " + e.getMessage());
        } finally {
            try { new java.io.File(scriptPath).delete(); } catch (Exception ignored) {}
        }
    }

    /**
     * Currently-active OkHttp call, held so {@link #cancel()} can tear
     * down the socket immediately instead of waiting for the read loop
     * to observe the cancelled flag on its next iteration. Volatile so
     * the cancel thread sees the most recent reference set by the
     * download thread.
     */
    private volatile okhttp3.Call activeCall = null;

    /**
     * Cancel an in-progress download/install.
     */
    public void cancel() {
        cancelled = true;
        // Tear the socket down NOW so a stalled-mid-read transfer can't
        // hold for another 15 seconds before the read loop notices.
        okhttp3.Call call = activeCall;
        if (call != null) {
            try { call.cancel(); } catch (Exception ignored) {}
        }
    }

    private static final String APK_PATH = at.greenchili.surfmate.util.DaemonStorage.rebase("/data/user_de/0/com.android.shell/surfmate/surfmate_update.apk");
    private static final long MIB = 1024L * 1024L;
    private static final long MIN_UPDATE_FREE_BYTES = 350L * MIB;
    private static final long UPDATE_INSTALL_HEADROOM_BYTES = 128L * MIB;
    private static final String STORAGE_ERROR_MESSAGE =
            "Not enough storage to update Panoptes. Free space on the head unit and try again.";

    private String getApkPath() {
        return APK_PATH;
    }

    private void cleanupLeftoverApk() {
        try {
            // Also age out a stale Telegram post-update hint older than 24h —
            // if Telegram never came back online to consume it, the user has
            // already noticed the URL change through other means and a "you
            // were just updated" message would be confusing days later.
            String cmd = "rm -f " + APK_PATH + "; " +
                    "find " + UpdateLifecycle.TELEGRAM_POST_UPDATE_HINT_FILE +
                    " -mmin +1440 -delete 2>/dev/null; echo done";
            runShell(cmd, new at.greenchili.surfmate.launcher.AdbDaemonLauncher.LaunchCallback() {
                @Override public void onLog(String m) {}
                @Override public void onLaunched() { Log.i(TAG, "Cleaned up leftover APK"); }
                @Override public void onError(String e) {}
            });
        } catch (Exception ignored) {}
    }

    /**
     * Locate the first {@code .apk} asset in a release's {@code assets} array.
     * Returns {@code {browser_download_url, name, updated_at, size}} or {@code null}
     * when the release carries no APK (notes-only / draft). Shared by
     * {@link #checkForUpdate} (braveheart) and {@link #listVersions} (alpha)
     * so the single-asset find lives in exactly one place.
     */
    private static String[] firstApkAsset(JSONArray assets) {
        if (assets == null) return null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) continue;
            String name = asset.optString("name", "");
            if (name.endsWith(".apk")) {
                String url = asset.optString("browser_download_url", "");
                if (url.isEmpty()) continue;
                return new String[]{
                        url,
                        name,
                        asset.optString("updated_at", ""),
                        String.valueOf(Math.max(0L, asset.optLong("size", 0L)))
                };
            }
        }
        return null;
    }

    static long requiredUpdateFreeBytes(long apkSizeBytes) {
        return Math.max(MIN_UPDATE_FREE_BYTES,
                Math.max(0L, apkSizeBytes) * 3L + UPDATE_INSTALL_HEADROOM_BYTES);
    }

    /**
     * Returns a user-facing storage error, or {@code null} when there is enough
     * room to stage and install the resolved APK.
     */
    public String getUpdateStorageError() {
        try {
            // Reclaim a partial APK before measuring. This also leaves enough
            // room for callers to persist the error when a prior download filled
            // the partition.
            File stagedApk = new File(APK_PATH);
            if (stagedApk.exists() && !stagedApk.delete()) {
                Log.w(TAG, "Could not delete stale update APK before storage check");
            }

            long available = new StatFs(at.greenchili.surfmate.util.DaemonStorage.rebase("/data/user_de/0/com.android.shell/surfmate")).getAvailableBytes();
            long required = requiredUpdateFreeBytes(latestApkSizeBytes);
            if (available >= required) return null;

            long requiredMiB = (required + MIB - 1L) / MIB;
            long availableMiB = Math.max(0L, available) / MIB;
            return "Not enough storage to update Panoptes. Only " + availableMiB
                    + " MB is free; " + requiredMiB
                    + " MB is required. Free space on the head unit and try again.";
        } catch (Exception e) {
            // A failed stat must not block updates on vendor ROMs with unusual
            // /data/user_de/0/com.android.shell/surfmate permissions; the download/install errors remain
            // the fallback.
            Log.w(TAG, "Unable to check update storage", e);
            return null;
        }
    }



    // ==================== COMPANION APK INSTALL (SurfMate Launcher, WP-H) ====================
    //
    // Silent install of a SEPARATE companion package (com.surfmate.launcher)
    // from its OWN GitHub release track. This is fully ADDITIVE and REUSES the
    // download + `pm install` primitives of the core self-update path
    // ({@link #firstApkAsset}, {@link #buildClient}, {@link #downloadApkOkHttp} /
    // {@link #buildDownloadCommand}, {@link #runShell}), but deliberately does
    // NOT touch ANY of core's self-update bookkeeping:
    //   - no {@link #stopAllDaemons()} / kill cascade (we are NOT replacing
    //     OURSELVES, so no core process needs to die),
    //   - no per-channel baseline / VERSION_FILE / PREF_JUST_UPDATED writes
    //     (those track CORE's version, not the companion's),
    //   - no `am start` relaunch of MainActivity.
    // `pm install -r` of a DIFFERENT package never kills core's process, so the
    // simple synchronous flow the pre-daemon app-process path used is sufficient
    // and safe from either UID (runShell tunnels through the ADB daemon launcher
    // when the app UID can't write /data/user_de/0/com.android.shell/surfmate). The core self-update
    // methods ({@link #downloadAndInstall} / {@link #runDetachedInstall}) are
    // left BYTE-IDENTICAL by this addition.
    private static final String COMPANION_APK_PATH =
            at.greenchili.surfmate.util.DaemonStorage.rebase("/data/user_de/0/com.android.shell/surfmate/surfmate_companion.apk");

    /** No-op shell callback for fire-and-forget cleanup commands. */
    private static final at.greenchili.surfmate.launcher.AdbDaemonLauncher.LaunchCallback NOOP_SHELL =
            new at.greenchili.surfmate.launcher.AdbDaemonLauncher.LaunchCallback() {
                @Override public void onLog(String m) {}
                @Override public void onLaunched() {}
                @Override public void onError(String e) {}
            };





    static boolean isSuccessfulCameraPrepareStatus(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    /**
     * Ask CameraDaemon to durably checkpoint active-trip state and stop its
     * media pipeline before the updater launches any SIGKILL command.
     *
     * @return null on confirmed preparation, otherwise a user-facing reason
     */
    /** Attempts for a refusal the daemon reports as transient. */
    private static final int CAMERA_PREPARE_MAX_ATTEMPTS = 6;
    /** Matches the daemon's 1s pending-persistence drain cadence. */
    private static final long CAMERA_PREPARE_RETRY_DELAY_MS = 1000;


    /** Outcome of one prepare-restart round trip. */
    private static final class CameraPrepareAttempt {
        final boolean prepared;
        final boolean retryable;
        final String failure;

        CameraPrepareAttempt(boolean prepared, boolean retryable, String failure) {
            this.prepared = prepared;
            this.retryable = retryable;
            this.failure = failure;
        }
    }

    private CameraPrepareAttempt attemptCameraDaemonPrepare() {
        HttpURLConnection connection = null;
        try {
            connection = at.greenchili.surfmate.util.DaemonHttpClient.open(
                    "/api/surveillance/prepare-restart",
                    "POST",
                    3000,
                    10000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            try (java.io.OutputStream body = connection.getOutputStream()) {
                body.write(new JSONObject()
                        .put("reason", "app_update")
                        .toString()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            int statusCode = connection.getResponseCode();
            if (isSuccessfulCameraPrepareStatus(statusCode)) {
                return new CameraPrepareAttempt(true, false, null);
            }
            // The body names the actual precondition AND whether waiting can
            // clear it. Reporting only the status code made every distinct
            // cause look like the same failure.
            JSONObject error = readErrorBody(connection);
            String detail = error != null
                    ? error.optString("error", "").trim() : "";
            // A failed pipeline teardown is a 503 that retrying cannot fix, so
            // the daemon's own verdict decides — not the status code. An absent
            // or unparseable verdict defaults to retryable so a body we cannot
            // read never silently disables the retry.
            boolean retryable = statusCode == 503
                    && (error == null || error.optBoolean("retryable", true));
            String reason = !detail.isEmpty()
                    ? detail
                    : "camera daemon rejected restart (HTTP " + statusCode + ")";
            return new CameraPrepareAttempt(false, retryable, reason);
        } catch (Exception e) {
            String detail = e.getMessage();
            if (detail == null || detail.trim().isEmpty()) {
                detail = e.getClass().getSimpleName();
            }
            // A transport failure can mean the daemon prepared but the response
            // was lost, so this is not retried blindly.
            return new CameraPrepareAttempt(false, false,
                    "camera daemon preparation failed: " + detail);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Parse a failed daemon response body. Returns null when there is no
     * readable JSON object, so the caller can distinguish "no verdict" from
     * an explicit one.
     */
    private static JSONObject readErrorBody(HttpURLConnection connection) {
        InputStream stream = null;
        try {
            stream = connection.getErrorStream();
            if (stream == null) return null;
            java.io.ByteArrayOutputStream buffer =
                    new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[2048];
            int n;
            // Bounded: a daemon error body is a short JSON object. Guard the
            // WRITE, not the next read — testing after the read discarded the
            // chunk that crossed the cap and left truncated, unparseable JSON.
            while ((n = stream.read(chunk)) != -1) {
                if (buffer.size() + n > 8192) break;
                buffer.write(chunk, 0, n);
            }
            String body = buffer.toString("UTF-8").trim();
            if (body.isEmpty()) return null;
            return new JSONObject(body);
        } catch (Exception ignored) {
            return null;
        } finally {
            if (stream != null) {
                try { stream.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Set when abort-restart could not resume trip sampling, so the caller can
     * tell the user their trip is no longer recording. Null when the last abort
     * succeeded or none was attempted.
     */
    private volatile String lastAbortRestartFailure;

    /**
     * Suffix warning the user that trip recording did not resume, or "" when it
     * did. Appended to the safe-stop message so a frozen recorder is visible
     * instead of hidden behind "stopped safely".
     */
    private String abortRestartWarning() {
        String failure = lastAbortRestartFailure;
        return failure == null ? ""
                : " (warning: trip recording did not resume — " + failure
                        + "; restart the camera daemon to resume)";
    }




    private void cleanup(String path) {
        try {
            runShell("rm -f " + path, new at.greenchili.surfmate.launcher.AdbDaemonLauncher.LaunchCallback() {
                @Override public void onLog(String m) {}
                @Override public void onLaunched() {}
                @Override public void onError(String e) {}
            });
        } catch (Exception ignored) {}
    }


    /** Strict alpha tag allowlist: bare "alpha" or "alpha-v<semver>". */
    private static final java.util.regex.Pattern VALID_ALPHA_TAG =
            java.util.regex.Pattern.compile("^alpha(-v\\d+\\.\\d+(\\.\\d+)?)?$");

    public static boolean isValidAlphaTag(String tag) {
        return tag != null && VALID_ALPHA_TAG.matcher(tag).matches();
    }

    /**
     * Make a value safe to interpolate into a single-quoted shell string or a
     * printf format. Defense-in-depth: the tag is already strictly validated
     * and version labels come from extractVersion's dotted-numeric regex, but
     * any value that reaches `echo '<v>' > file` / printf in the detached
     * install script is scrubbed to [A-Za-z0-9._-] so a stray quote or '%'
     * can never break out. Drops everything else.
     */
    static String shellSafe(String s) {
        if (s == null) return "";
        return s.replaceAll("[^A-Za-z0-9._-]", "");
    }

    /** Strict ISO-8601 instant the GitHub asset updated_at uses. */
    private static final java.util.regex.Pattern ISO_INSTANT =
            java.util.regex.Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$");

    /**
     * Return an ISO-8601 timestamp VERBATIM iff it matches the exact GitHub
     * updated_at shape, else "". Used instead of shellSafe() for timestamps:
     * shellSafe strips the ':' (its charset is for version labels), which would
     * mangle 2026-05-29T10:00:00Z → 2026-05-29T100000Z and permanently break
     * baseline .equals() matching + the SimpleDateFormat parse. A string that
     * matches ISO_INSTANT has no quote/backslash/% so it's safe to interpolate
     * into a single-quoted echo or a printf format literal as-is; anything else
     * is dropped (caller treats "" as the no-prior-baseline / remove-file case).
     */
    static String isoSafe(String ts) {
        if (ts == null) return "";
        return ISO_INSTANT.matcher(ts).matches() ? ts : "";
    }

    /**
     * Extract version from APK filename including channel.
     * "surfmate-release-alpha-v6.1.apk" → "alpha-v6.1"
     * "surfmate-release-prod-v2.0.1.apk" → "prod-v2.0.1"
     */
    static String extractVersion(String apkName) {
        if (apkName != null) {
            // Try to match channel-version pattern: alpha-v6.1, prod-v2.0
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(alpha|debug|prod|beta|braveheart)-v?(\\d+\\.\\d+(?:\\.\\d+)?)")
                    .matcher(apkName);
            if (m.find()) return m.group(1) + "-v" + m.group(2);

            // Fallback: just version number
            m = java.util.regex.Pattern.compile("v?(\\d+\\.\\d+(?:\\.\\d+)?)").matcher(apkName);
            if (m.find()) return "v" + m.group(1);
        }
        return "unknown";
    }

    /**
     * Canonicalize an extractVersion() result to the "<channel>-v<semver>" form
     * the persisted-label shape guard (persistedGithubVersion) trusts. If the
     * extracted label already carries a channel prefix it's returned as-is;
     * if it's a bare "v<semver>" (APK filename lacked the channel) we prepend
     * the KNOWN install channel so the value we persist isn't silently rejected
     * on read (which would revert About/web to the BuildConfig identity). An
     * unparseable "unknown" stays "unknown".
     */
    static String canonicalVersionLabel(String extracted, String channel) {
        if (extracted == null || extracted.isEmpty() || "unknown".equals(extracted)) {
            return extracted;
        }
        if (channelOfLabel(extracted) != null) return extracted;          // already <channel>-v...
        if (channel == null || channel.isEmpty()) return extracted;       // no channel to add
        // bare "v26.1" (or "26.1") → "<channel>-v26.1"
        String num = numericVersion(extracted);
        return num.isEmpty() ? extracted : channel + "-v" + num;
    }

    /**
     * Resolve a canonical "<channel>-v<semver>" label for a release, trying
     * progressively weaker sources so a digit-less APK filename never freezes
     * the displayed version at "unknown" (which then can't be persisted, so the
     * baseline advances while About/toast show a stale older label forever).
     *
     * Order: APK asset filename → release {@code name} → release {@code tag_name}.
     * The first that yields a parseable dotted version wins. Falls through to
     * "unknown" only when NONE carry a version (truly unlabelled release) —
     * callers keep their existing unknown-sentinel handling for that genuine edge.
     *
     * The release {@code body} is deliberately NOT consulted: it's free-text
     * release notes, and extractVersion's bare-version regex would happily match
     * a number inside prose ("fixes regression from v25.1") and surface a WRONG
     * display label. The structured fields (filename / name / tag_name) cover
     * every well-formed release; a release missing a version in all three is
     * malformed and correctly stays "unknown".
     */
    static String resolveRemoteLabel(JSONObject release, String apkName, String channel) {
        String label = canonicalVersionLabel(extractVersion(apkName), channel);
        if (!"unknown".equals(label)) return label;
        if (release != null) {
            String[] fallbacks = {
                    release.optString("name", ""),
                    release.optString("tag_name", "")
            };
            for (String src : fallbacks) {
                if (src == null || src.isEmpty()) continue;
                String cand = canonicalVersionLabel(extractVersion(src), channel);
                if (!"unknown".equals(cand)) return cand;
            }
        }
        return "unknown";
    }

    /**
     * Channel a display label belongs to ("alpha-v25.4" → "alpha",
     * "braveheart-v26.0" → "braveheart"), or null when the label carries no
     * channel prefix (bare "v6.1", "unknown", or the "Manually Installed"
     * fallback). Used by checkForUpdate to tell a CHANNEL SWITCH apart from a
     * genuine first run — a null here means "can't tell", so we fall through to
     * the safe (suppress-and-seed) first-run path rather than risk a spurious
     * prompt.
     */
    public static String channelOfLabel(String label) {
        if (label == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(alpha|debug|prod|beta|braveheart)-v")
                .matcher(label);
        return m.find() ? m.group(1) : null;
    }

    static boolean isNewerVersion(String local, String remote) {
        try {
            String[] lp = local.split("\\.");
            String[] rp = remote.split("\\.");
            int len = Math.max(lp.length, rp.length);
            for (int i = 0; i < len; i++) {
                int l = i < lp.length ? Integer.parseInt(lp[i].replaceAll("[^0-9]", "")) : 0;
                int r = i < rp.length ? Integer.parseInt(rp[i].replaceAll("[^0-9]", "")) : 0;
                if (r > l) return true;
                if (r < l) return false;
            }
            return false;
        } catch (Exception e) {
            return !local.equals(remote);
        }
    }

    /**
     * Resolve the active update channel for THIS process.
     *
     * forceReload() before the read is mandatory: the web channel toggle is
     * written by the daemon (UID 2000) but checkForUpdate / listVersions may
     * run in the app process (UID 10xxx) and vice-versa, and the in-memory
     * config is per-UID. Falls back to the build-time seed
     * ({@link BuildConfig#UPDATE_CHANNEL}, "alpha") on any failure so a
     * config read error can never strand the updater with no channel.
     */
    private String resolveChannel() {
        try {
            at.greenchili.surfmate.config.UnifiedConfigManager.forceReload();
            String ch = at.greenchili.surfmate.config.UnifiedConfigManager.getUpdateChannel();
            if (ch != null && !ch.isEmpty()) return ch;
        } catch (Throwable t) {
            Log.w(TAG, "resolveChannel fell back to BuildConfig: " + t.getMessage());
        }
        return BuildConfig.UPDATE_CHANNEL;
    }

    /**
     * One-time, idempotent: seed the per-channel "alpha" baseline from the
     * legacy unsuffixed baseline so a current user who updated to this build
     * via the bare "alpha" tag keeps their detection baseline. No-op for any
     * channel other than alpha (braveheart has no legacy baseline — its first
     * check hits the empty-baseline seed branch and stores a fresh one).
     *
     * SharedPreferences half runs in-process (both UIDs). The filesystem half
     * routes through runShell (the app UID cannot write /data/user_de/0/com.android.shell/surfmate
     * directly) and only copies when the legacy file exists AND the
     * per-channel file does not, so a re-run never clobbers a live baseline.
     */
    private void migrateBaseline(String channel) {
        if (!CHANNEL_ALPHA.equals(channel)) return;
        try {
            SharedPreferences prefs =
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String perChannelKey = prefKeyForChannel(channel);
            if (!prefs.getString(perChannelKey, "").isEmpty()) return; // already migrated
            String legacy = prefs.getString(PREF_LAST_UPDATE_TIME, "");
            if (!legacy.isEmpty()) {
                prefs.edit().putString(perChannelKey, legacy).commit();
            }
            final String src = UPDATE_TIMESTAMP_FILE;
            final String dst = timestampFileForChannel(channel);
            runShell("[ -f " + src + " ] && [ ! -f " + dst + " ] && cp " + src + " " + dst
                            + " && chmod 644 " + dst + " 2>/dev/null; echo done",
                    new at.greenchili.surfmate.launcher.AdbDaemonLauncher.LaunchCallback() {
                        @Override public void onLog(String m) {}
                        @Override public void onLaunched() {}
                        @Override public void onError(String e) {}
                    });
        } catch (Exception ignored) {}
    }

    /**
     * Per-channel DEVICE-CLOCK install-time baseline key. Stores the app's
     * {@code PackageManager.lastUpdateTime} (a device-monotonic-ish ms value)
     * captured the last time we recorded an update baseline for this channel.
     * Used to detect an OUT-OF-BAND reinstall (Studio/manual sideload) WITHOUT
     * comparing the device clock against GitHub's server clock — see
     * checkForUpdate. SharedPreferences-only (per-UID): the app process gets the
     * full correct behaviour; a daemon-process check that never sees the stamp
     * simply degrades to "offer the sideloaded build once" — it can NEVER
     * falsely suppress a genuine update.
     */
    private static String installTimePrefKey(String channel) {
        return "install_time_baseline_" + channel;
    }

    /** Read the per-channel device-clock install-time baseline (0 if unset). */
    private static long getInstallTimeBaseline(Context ctx, String channel) {
        if (ctx == null) return 0L;
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(installTimePrefKey(channel), 0L);
    }

    /** Persist the per-channel device-clock install-time baseline. */
    private static void saveInstallTimeBaseline(Context ctx, String channel, long t) {
        if (ctx == null || t <= 0) return;
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putLong(installTimePrefKey(channel), t).commit();
    }

    private String getLastUpdateTimestamp(String channel) {
        String prefKey = prefKeyForChannel(channel);
        String tsFile = timestampFileForChannel(channel);

        // FILE-FIRST — the world-readable /data/user_de/0/com.android.shell/surfmate file is the single
        // cross-UID source of truth, exactly like persistedGithubVersion() does
        // for the display label. The daemon (UID 2000) owns the install and
        // advances this baseline; the app process (UID 10xxx) runs its OWN
        // MainActivity update check with its OWN per-UID SharedPreferences.
        //
        // The old order (SP-first, file only when SP empty) meant that after a
        // daemon-side install the app's stale-but-non-empty SP won, the freshly
        // advanced file was never consulted, apkUpdated stayed true, and the
        // popup re-offered the just-installed version on every check — forever.
        // Every path that writes SP also writes the file (first-run seed +
        // install both save both), so the file is always at least as current as
        // any per-UID SP. Read it first and reconcile SP from it.
        try {
            File f = new File(tsFile);
            if (f.exists()) {
                java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
                String ts = r.readLine();
                r.close();
                if (ts != null && !ts.isEmpty()) {
                    // Reconcile this process's SP cache so a same-process
                    // repeat read is fast and the two never diverge.
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit().putString(prefKey, ts).apply();
                    return ts;
                }
            }
        } catch (Exception ignored) {}

        // File absent/unreadable — fall back to this process's SP cache. This
        // covers the pre-first-install state and any ROM where the app UID
        // can't read the file; in both cases SP-only degrades to per-UID
        // detection, which is still correct within a single process.
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(prefKey, "");
    }


    private void saveLastUpdateTimestamp(String channel, String timestamp) {
        if (timestamp == null) return;
        String prefKey = prefKeyForChannel(channel);
        final String tsFile = timestampFileForChannel(channel);
        // Use commit() (synchronous) — process may be killed right after
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(prefKey, timestamp).commit();
        // Also save to filesystem via ADB shell (survives reinstall, app can't write /data/user_de/0/com.android.shell/surfmate directly).
        // isoSafe (NOT shellSafe) — this file is read back byte-for-byte and
        // compared via .equals() against the GitHub updated_at, so the colons
        // MUST survive. isoSafe emits a valid ISO instant verbatim (no shell
        // metachars) or "" if malformed. The SP value at L1645 is already raw,
        // so SP and file stay byte-identical for a valid timestamp.
        String safeTs = isoSafe(timestamp);
        if (safeTs.isEmpty()) return; // don't write a malformed/empty baseline file
        try {
            // chmod 644 so the OTHER UID can read it cross-process. The daemon
            // (UID 2000) owns the install and advances this baseline, but the
            // app process (UID 10xxx) runs its own MainActivity update check
            // and reads this file as the shared source of truth (see
            // getLastUpdateTimestamp). Without the chmod the daemon's write
            // lands mode 0600 (unreadable by the app UID), so the app keeps
            // reading its own stale per-UID SharedPreferences baseline and
            // re-offers the just-installed version forever. Mirrors the
            // VERSION_FILE writes, which chmod 644 for the same reason.
            runShell("echo '" + safeTs + "' > " + tsFile
                            + "; chmod 644 " + tsFile + " 2>/dev/null",
                    new at.greenchili.surfmate.launcher.AdbDaemonLauncher.LaunchCallback() {
                @Override public void onLog(String m) {}
                @Override public void onLaunched() {}
                @Override public void onError(String error) {
                    Log.w(TAG, "Failed to save timestamp to file: " + error);
                }
            });
        } catch (Exception ignored) {}
    }

    /**
     * Persist version string to filesystem so the daemon process can read it.
     * SharedPreferences are per-process and may not be accessible from the daemon.
     */
    private void persistVersionToFile(String version) {
        // Reject empty AND the "unknown" sentinel — this is the single
        // chokepoint enforcing the "only canonical <channel>-v<semver> labels
        // get persisted" invariant. Writing "unknown" would clobber a
        // previously-valid label with a value the read side rejects anyway
        // (channelOfLabel("unknown")==null), silently degrading same-tag-
        // republish tracking. Matches the IPC-hint + rollback write guards.
        if (version == null || version.isEmpty() || "unknown".equals(version)) return;
        try {
            // shellSafe: version flows into a single-quoted echo; scrub a stray
            // quote/metachar (extractVersion output is already constrained, but
            // this is the value-write twin of the detached-script path).
            // chmod 644: the file is read CROSS-UID — written by ONE UID (daemon
            // 2000 or app 10xxx) and read by the OTHER for the version display.
            // Without world-read the reader EACCEs, readVersionFile() returns
            // "", and getDisplayVersion falls back to the BuildConfig identity
            // (correct, but loses the persisted GitHub label for same-tag
            // republishes). Same chmod precedent as the .byd_device_id file.
            runShell("echo '" + shellSafe(version) + "' > " + VERSION_FILE
                            + "; chmod 644 " + VERSION_FILE + " 2>/dev/null",
                    new at.greenchili.surfmate.launcher.AdbDaemonLauncher.LaunchCallback() {
                @Override public void onLog(String m) {}
                @Override public void onLaunched() {}
                @Override public void onError(String error) {
                    Log.w(TAG, "Failed to save version to file: " + error);
                }
            });
        } catch (Exception ignored) {}
    }

    private void postError(UpdateCallback cb, String msg) {
        runCallback(() -> cb.onError(msg));
    }
    private void postInstallError(InstallCallback cb, String msg) {
        String error = userFacingInstallError(msg);
        runCallback(() -> cb.onError(error));
    }
    static String userFacingInstallError(String msg) {
        String error = msg == null || msg.trim().isEmpty() ? "Update failed" : msg.trim();
        if (error.startsWith("Not enough storage to update Panoptes.")) return error;
        String lower = error.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("no space left")
                || lower.contains("enospc")
                || lower.contains("install_failed_insufficient_storage")
                || lower.contains("insufficient storage")
                || lower.contains("not enough space")) {
            return STORAGE_ERROR_MESSAGE;
        }
        return error;
    }
    private void postProgress(InstallCallback cb, String msg) {
        runCallback(() -> cb.onProgress(msg));
    }

    /**
     * Check if app was just updated and return the version string.
     * Clears the flag after reading so it only shows once.
     *
     * Callers MUST call {@link #consumeFailedUpdateError(Context)} first.
     * That method clears PREF_JUST_UPDATED on a failed install (the flag is
     * set before `runDetachedInstall` so it's true on BOTH success and
     * failure paths; the progress JSON is the authoritative outcome
     * signal). After that, this method only returns a non-null version
     * when the install actually succeeded.
     */
    public static String consumeJustUpdatedVersion(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (prefs.getBoolean(PREF_JUST_UPDATED, false)) {
            String version = prefs.getString(PREF_UPDATED_VERSION, "");
            // Only clear the flag, keep the version for display
            prefs.edit()
                    .putBoolean(PREF_JUST_UPDATED, false)
                    .apply();
            return version;
        }
        return null;
    }

    /**
     * Read and clear a failed-install error written by the daemon-side
     * install script. Returns the error message (e.g. "Failure
     * [INSTALL_PARSE_FAILED_NO_CERTIFICATES]") when the script's
     * `pm install` exited non-zero, or null when there's no failure to
     * report. Called on app launch — together with
     * {@link #consumeJustUpdatedVersion(Context)} — so the user sees a
     * concrete error toast instead of a misleading success message or
     * silence.
     *
     * Also clears PREF_JUST_UPDATED. The flag is set BEFORE the install
     * script runs (at line 471 in downloadAndInstall) so it's "true" on
     * BOTH success and failure paths; we use the progress JSON as the
     * authoritative success/failure signal. If we left the flag set on
     * failure, the immediately-following consumeJustUpdatedVersion call
     * would see PREF_JUST_UPDATED=true with no failure marker (because we
     * deleted it here) and would fire the misleading "Updated to vX" toast
     * alongside the error toast.
     */
    public static String consumeFailedUpdateError(Context context) {
        if (!hasFailedUpdateMarker()) return null;
        String err = null;
        String priorUpdateTs = null;
        String priorUpdateChannel = null;
        String priorDisplayVersion = null;
        long recordTs = 0;
        try {
            java.io.File f = new java.io.File(at.greenchili.surfmate.util.DaemonStorage.rebase("/data/user_de/0/com.android.shell/surfmate/surfmate_update_progress.json"));
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f)))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            JSONObject j = new JSONObject(sb.toString());
            err = j.optString("error", null);
            if (err == null || err.isEmpty()) err = j.optString("message", "Install failed");
            priorUpdateTs = j.optString("priorUpdateTs", null);
            priorUpdateChannel = j.optString("priorUpdateChannel", null);
            priorDisplayVersion = j.optString("priorDisplayVersion", null);
            recordTs = j.optLong("ts", 0);
        } catch (Exception ignored) {}
        // One-shot guard. The phase=error record is deliberately LEFT in place
        // below (so the web's reconnect re-read can observe it after the daemon
        // restarts — see the no-delete note further down), which means a later
        // NORMAL launch's onCreate consume call would otherwise re-surface the
        // same stale failure on every launch until the next install overwrites
        // it. Gate on the record's `ts`: if we've already surfaced this exact
        // failure, return null (no re-toast). A retry that fails again writes a
        // fresh record with a new `ts`, so this re-arms naturally. The marker
        // lives in SharedPreferences (always app-writable), NOT the progress
        // JSON (a UID-2000 0644 file the app process can't truncate).
        SharedPreferences guardPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (recordTs > 0 && guardPrefs.getLong(PREF_LAST_CONSUMED_FAILURE_TS, 0) == recordTs) {
            // Already surfaced this failure. Still clear the just-updated flag so
            // a (now impossible, but defensive) success toast can't fire, then
            // bail without re-toasting.
            guardPrefs.edit().putBoolean(PREF_JUST_UPDATED, false).apply();
            return null;
        }
        if (recordTs > 0) {
            guardPrefs.edit().putLong(PREF_LAST_CONSUMED_FAILURE_TS, recordTs).apply();
        }
        // Clear the just-updated flag and the post-update sentinel so the next
        // launch is clean. The progress JSON itself is intentionally KEPT (see
        // the no-delete note at the end of this method); the next retry's
        // writeProgress("queued") overwrites it.
        //
        // Roll back the PER-CHANNEL baseline so the failed-install retry path
        // sees the correct baseline. Without this, the daemon-process
        // detached install advances the baseline before pm install runs;
        // on failure the baseline is still pointing at the version that
        // didn't land, and checkForUpdate reports "no update available"
        // — user is silently stuck. Channel comes from the progress JSON the
        // install script embedded; fall back to the build seed for records
        // written before this field existed.
        if (priorUpdateChannel == null || priorUpdateChannel.isEmpty()) {
            priorUpdateChannel = BuildConfig.UPDATE_CHANNEL;
        }
        try {
            android.content.SharedPreferences.Editor e =
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(PREF_JUST_UPDATED, false);
            // Restore PREF_UPDATED_VERSION (display label) to the prior build.
            // Only act when the progress record actually CARRIES prior-state
            // (priorDisplayVersion key present == the detached daemon path,
            // which advanced the label before pm install). A sync-path failure
            // record has NO prior* fields because the sync path already rolled
            // back PREF_UPDATED_VERSION + VERSION_FILE inline — so a null here
            // means "already handled", and we must NOT remove() (that would wipe
            // the label the sync path just restored). Within a carrying record:
            // a real prior label is restored; the fallback/empty sentinel means
            // "no prior real build" → remove.
            if (priorDisplayVersion != null) {
                if (!priorDisplayVersion.isEmpty()
                        && !DISPLAY_VERSION_FALLBACK.equals(priorDisplayVersion)) {
                    e.putString(PREF_UPDATED_VERSION, priorDisplayVersion);
                } else {
                    e.remove(PREF_UPDATED_VERSION);
                }
            }
            // Empty string is the legitimate "no prior install" sentinel
            // (saveLastUpdateTimestamp always writes a value after first
            // success). Restore even if empty so the field reverts to the
            // exact pre-attempt state. The SharedPreferences half is the
            // load-bearing rollback (getLastUpdateTimestamp reads it before
            // the /data/user_de/0/com.android.shell/surfmate file); the daemon re-writes the file on its
            // next successful check.
            if (priorUpdateTs != null) {
                e.putString(prefKeyForChannel(priorUpdateChannel), priorUpdateTs);
            }
            e.apply();
        } catch (Exception ignored) {}
        // Do NOT delete the phase=error progress record here. The web runs its
        // reconnect re-read of /api/update/progress only AFTER the camera daemon
        // is back online (it polls /status first), and on a post-kill failure
        // the relaunched MainActivity calls this method BEFORE it restarts the
        // daemon (showPostUpdateToasts runs before startDaemons.run()). Deleting
        // the record here would guarantee the web's re-read sees the idle
        // sentinel and falls through to a false "Updated to X". Leaving the
        // terminal phase=error record in place lets the web surface the real
        // failure (handleProgress treats phase=error as terminal and never ages
        // it out); the PREF_LAST_CONSUMED_FAILURE_TS guard above stops the app
        // from re-toasting it, and the next install's writeProgress("queued")
        // overwrites it. (This app-process unlink was also a no-op in practice
        // — the file is a UID-2000 0644 entry in a sticky shell-owned dir the
        // app UID can neither truncate nor unlink.)
        try { new java.io.File(POST_UPDATE_FILE).delete(); } catch (Exception ignored) {}
        return err;
    }

    /**
     * True when the progress JSON exists and reports phase=error. Used as a
     * gate so consumeJustUpdatedVersion doesn't toast "Updated to vX" for an
     * install that never landed.
     */
    private static boolean hasFailedUpdateMarker() {
        try {
            java.io.File f = new java.io.File(at.greenchili.surfmate.util.DaemonStorage.rebase("/data/user_de/0/com.android.shell/surfmate/surfmate_update_progress.json"));
            if (!f.exists()) return false;
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f)))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            return new JSONObject(sb.toString()).optString("phase", "").equals("error");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Fetch the latest published version label for the current channel from
     * GitHub Releases (best-effort, non-blocking). Returns the same label
     * format as the on-device "display version" (e.g. "alpha-v6.1") so the
     * UI can swap one for the other without further parsing.
     *
     * Used by the Settings → About surface so the user sees the truth-source
     * version (what's published) rather than what was last installed locally.
     * Falls back through {@link #onError} when offline / proxy down.
     */
    public interface RemoteVersionCallback {
        void onResult(String version);
        void onError(String error);
    }


    // ==================== Alpha catalog (pick-any) ====================

    /** One selectable version in the alpha archive. */
    public static class VersionEntry {
        public final String version;       // display label, e.g. "alpha-v25.4"
        public final String tag;           // GitHub tag_name, e.g. "alpha-v25.4" or "alpha"
        public final String downloadUrl;   // asset browser_download_url
        public final String updatedAt;     // asset updated_at (ISO)
        public final String publishedAt;   // release published_at (ISO)
        public final String releaseNotes;  // capped release body
        public final String relation;      // "current" | "newer" | "older" | "unknown"

        VersionEntry(String version, String tag, String downloadUrl, String updatedAt,
                     String publishedAt, String releaseNotes, String relation) {
            this.version = version;
            this.tag = tag;
            this.downloadUrl = downloadUrl;
            this.updatedAt = updatedAt;
            this.publishedAt = publishedAt;
            this.releaseNotes = releaseNotes;
            this.relation = relation;
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("version", version);
                o.put("tag", tag);
                o.put("downloadUrl", downloadUrl);
                o.put("updatedAt", updatedAt != null ? updatedAt : "");
                o.put("publishedAt", publishedAt != null ? publishedAt : "");
                o.put("releaseNotes", releaseNotes != null ? releaseNotes : "");
                o.put("relation", relation);
            } catch (Exception ignored) {}
            return o;
        }
    }

    public interface VersionListCallback {
        void onResult(java.util.List<VersionEntry> versions, String currentVersion);
        void onError(String error);
    }

    private static final int RELEASE_NOTES_CAP = 4096;


    /**
     * Strip a channel prefix to the numeric version: "alpha-v25.4" → "25.4",
     * "braveheart-v26" → "26". Accepts a single token OR dotted form so a
     * non-dotted versionName ("26") still parses (isNewerVersion splits on "."
     * and zero-pads, so "26" vs "26.1" compares correctly) — this avoids
     * silently neutralizing every alpha catalog chip if a future release bumps
     * versionName to a bare integer. "26.0-rc1" parses to "26.0"; a bare word
     * yields "". relationTo() treats "" as "unknown" (neutral chip, never a
     * wrong "current"/"newer").
     */
    private static String numericVersion(String label) {
        if (label == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("v?(\\d+(?:\\.\\d+)*)").matcher(label);
        return m.find() ? m.group(1) : "";
    }

    /**
     * Label-only relation of a catalog entry to the installed version.
     * Returns "unknown" when the installed version is unparseable (e.g. a
     * fresh sideload showing {@link #DISPLAY_VERSION_FALLBACK}) so the UI
     * shows a neutral chip rather than a misleading "older".
     */
    private static String relationTo(String installed, String label, String numeric) {
        if (installed == null || installed.isEmpty()
                || DISPLAY_VERSION_FALLBACK.equals(installed)) {
            return "unknown";
        }
        // listVersions only enumerates ALPHA entries. If the installed build is
        // NOT an alpha-channel label (e.g. "braveheart-v6.1" after a braveheart
        // install, then the user toggled to alpha and opened the catalog),
        // comparing only the dotted-numeric part would mislabel a same-number
        // alpha entry as "current"/"older". Channels are distinct builds, so
        // mark cross-channel as "unknown" (label-only; never gates anything).
        if (!installed.startsWith("alpha")) {
            return "unknown";
        }
        String instNum = numericVersion(installed);
        if (instNum.isEmpty()) return "unknown";
        if (instNum.equals(numeric)) return "current";
        return isNewerVersion(instNum, numeric) ? "newer" : "older";
    }


    /**
     * Last-resort sentinel for {@link #getInstalledVersion()} — only returned
     * if BuildConfig.VERSION_NAME is somehow empty (never in a normal build,
     * where versionName is a real per-release value bumped in build.gradle.kts).
     * Version identity is now BuildConfig-derived, so this fallback is
     * effectively unreachable for a built APK; it's kept as a defensive default.
     */
    public static final String DISPLAY_VERSION_FALLBACK = "Manually Installed";

    /**
     * The build's TRUE self-identity, derived from BuildConfig baked into the
     * running binary: "<channel>-v<versionName>" (e.g. "alpha-v26.0",
     * "braveheart-v26.0"). This is the only version string that is ALWAYS
     * correct for the build actually running — unlike the persisted
     * VERSION_FILE / PREF_UPDATED_VERSION, which are written only by the
     * in-app updater and survive reinstall/sideload/cross-channel flashes, so
     * they go STALE (the "About shows alpha-v26 on a braveheart build" +
     * "check says already-updated" bugs). versionName must be bumped per
     * release in build.gradle.kts.
     */
    public static String getInstalledVersion() {
        String channel = BuildConfig.UPDATE_CHANNEL;
        String ver = BuildConfig.VERSION_NAME;
        if (ver == null || ver.isEmpty()) return DISPLAY_VERSION_FALLBACK;
        if (channel == null || channel.isEmpty()) return "v" + ver;
        return channel + "-v" + ver;
    }

    /**
     * The GitHub release label that was actually downloaded+installed, as the
     * update flow recorded it (PREF_UPDATED_VERSION for the app process,
     * VERSION_FILE for the daemon — both written from {@link #extractVersion}
     * of the installed APK's filename, e.g. "alpha-v26.1"). This is the version
     * the USER cares about: when an APK is re-published on the same release tag
     * without bumping gradle's versionName, BuildConfig.VERSION_NAME goes stale
     * but this label tracks the real GitHub build. Returns "" when nothing has
     * been installed via the in-app updater yet (fresh sideload / Studio run).
     *
     * Staleness guard: VERSION_FILE / PREF_UPDATED_VERSION survive a
     * cross-channel flash, so we ONLY trust the persisted label when its channel
     * prefix matches the running build's channel (BuildConfig.UPDATE_CHANNEL).
     * On a mismatch (ran alpha, persisted label is braveheart, or vice-versa)
     * the persisted value is stale-for-this-build and we fall back to
     * BuildConfig — this is exactly the "About showed the wrong channel after a
     * cross-channel install" case the BuildConfig-only path was guarding.
     */
    private static String persistedGithubVersion(Context context) {
        // SINGLE cross-process source of truth: the world-readable FILE
        // /data/user_de/0/com.android.shell/surfmate/surfmate_version, written by EVERY install path
        // (chmod 644) regardless of which process ran it. We deliberately do
        // NOT consult per-UID SharedPreferences here: PREF_UPDATED_VERSION is
        // per-process, so a daemon-run install (web/Telegram) updates the
        // daemon's prefs + the file but NOT the app's prefs — reading the app's
        // prefs is exactly what made the About row show a stale label while the
        // webapp was correct. File-only ⇒ app, daemon, web, Telegram, IPC all
        // resolve identically. If the file is absent/unreadable we fall back to
        // the BuildConfig identity (caller), never to a per-UID pref.
        // (context is unused now; kept for call-site compatibility.)
        String label = readVersionFile();
        if (label == null || label.isEmpty()
                || DISPLAY_VERSION_FALLBACK.equals(label)) {
            return "";
        }
        // SHAPE GUARD: only trust a label in the canonical "<channel>-v<semver>"
        // form. A malformed value left by an OLD build — e.g. the historical
        // "v26.0alpha" (version-then-channel suffix) — would otherwise leak
        // straight to the About row, because channelOfLabel() returns null for
        // it so the cross-channel guard below never trips. Anything that
        // doesn't parse as channel-prefix + dotted version is rejected → caller
        // falls back to the always-correct BuildConfig identity.
        String labelChannel = channelOfLabel(label);   // matches ^<channel>-v
        if (labelChannel == null || numericVersion(label).isEmpty()) {
            return "";   // malformed/legacy label — fall back to BuildConfig
        }
        // Only trust it for the channel we're actually running (a stale
        // cross-channel label must not leak through).
        String runningChannel = BuildConfig.UPDATE_CHANNEL;
        if (runningChannel != null && !runningChannel.isEmpty()
                && !labelChannel.equals(runningChannel)) {
            return "";   // stale cross-channel label — let the caller fall back
        }
        return label;
    }

    /** Read VERSION_FILE (the daemon-readable persisted GitHub version). "" if absent. */
    private static String readVersionFile() {
        try {
            File f = new File(VERSION_FILE);
            if (!f.exists()) return "";
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
            String line = r.readLine();
            r.close();
            return line != null ? line.trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Get the display version string shown to the user (About screen, "up to
     * date" toast, post-update banner, web/Telegram status). Prefers the GitHub
     * release label actually installed ({@link #persistedGithubVersion}); falls
     * back to the BuildConfig identity ({@link #getInstalledVersion}) only when
     * nothing has been installed via the updater yet, or the persisted label is
     * a stale cross-channel value. This way the number the user sees always
     * matches the GitHub build they're on, even when versionName wasn't bumped.
     */
    public static String getDisplayVersion(Context context) {
        // SurfMate: Versionsangabe kommt nur aus dem eigenen Build. Die vom
        // Vorgaenger uebernommene Versionsdatei ("...-v56.0") wird nicht angezeigt.
        return getInstalledVersion();
    }

    /**
     * Context-free display version (daemon process). Same resolution as
     * {@link #getDisplayVersion}: prefer the persisted GitHub label (read from
     * VERSION_FILE since the daemon has no SharedPreferences), else BuildConfig.
     */
    public static String getDisplayVersionFromFile() {
        return getDisplayVersion(null);
    }
}
