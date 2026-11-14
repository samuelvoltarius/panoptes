package at.greenchili.surfmate.updater;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import at.greenchili.surfmate.launcher.AdbDaemonLauncher;

import java.io.File;

/**
 * Coordinates the post-update launch path so the new app process always wins
 * over zombie daemons / watchdogs left behind by the previous install.
 *
 * The contract:
 *   - Old process (AppUpdater.stopAllDaemons) writes UPDATE_IN_PROGRESS_FILE
 *     and POST_UPDATE_FILE to /data/user_de/0/com.android.shell/surfmate before pm install.
 *   - New process (MainActivity) detects either sentinel + the post_update
 *     intent extra + PREF_JUST_UPDATED. If any is set, it runs hardResetDaemons
 *     before DaemonStartupManager so old daemons can't outlive the install.
 *   - hardResetDaemons clears the sentinels on completion.
 */
public final class UpdateLifecycle {

    private static final String TAG = "UpdateLifecycle";

    /**
     * Build a ps+awk+kill snippet for {@code pattern}, excluding the calling
     * shell's own PID via {@code $$}. Replaces every {@code pkill -9 -f
     * '<pattern>'} site — pkill -f matches the calling shell's argv on the
     * literal pattern and SIGKILLs it before subsequent commands run.
     */
    /** Eigene Prozesse des Panoptes-Stapels (Skripte, Daemons), ein ps für alle; fremde mit gleichem Namen bleiben. */
    static final String EIGENE_PROZESSE_KILL = at.greenchili.surfmate.launcher.EigeneProzesse.beendenMehrere(
            java.util.Arrays.asList("start_cam_daemon", "start_acc_sentry", "start_telegram", "byd_cam_daemon",
                    "cam_daemon", "sentry_daemon", "acc_sentry_daemon", "telegram_bot_daemon", "sentry_proxy"));

    private static String psAwkKillLine(String pattern) {
        return "MY_PID=$$; ps -A -o PID,ARGS | grep -F '" + pattern + "' | grep -v grep "
            + "| awk '{print $1}' | while read pid; do "
            + "if [ \"$pid\" != \"$MY_PID\" ]; then kill -9 $pid 2>/dev/null; fi; done\n";
    }

    private static String diLink5CaptureKillScript() {
        try {
            at.greenchili.surfmate.camera.dilink5.DiLink5Platform
                    .refreshActiveMode();
            if (!at.greenchili.surfmate.camera.dilink5.DiLink5Platform
                    .isSelected()) {
                return "";
            }
        } catch (Throwable ignored) {
            return "";
        }
        return psAwkKillLine("fast_cam_capture")
                + psAwkKillLine("qcarcam_test")
                + "killall -9 fast_cam_capture 2>/dev/null\n"
                + "killall -9 qcarcam_test 2>/dev/null\n";
    }

    public static final String UPDATE_IN_PROGRESS_FILE = at.greenchili.surfmate.util.DaemonStorage.rebase("/data/user_de/0/com.android.shell/surfmate/surfmate_update_in_progress");
    public static final String POST_UPDATE_FILE = at.greenchili.surfmate.util.DaemonStorage.rebase("/data/user_de/0/com.android.shell/surfmate/surfmate_post_update");
    /**
     * One-shot marker read by TelegramBotDaemon's notifyTunnel handler so the
     * first post-update tunnel-URL message can include the new version (and a
     * "this is why your URL changed" hint) instead of the generic "URL changed"
     * copy. Contains the version string (e.g. "alpha-v11.4"). Deleted by the
     * daemon after consuming.
     */
    public static final String TELEGRAM_POST_UPDATE_HINT_FILE =
            at.greenchili.surfmate.util.DaemonStorage.rebase("/data/user_de/0/com.android.shell/surfmate/surfmate_post_update_pending_telegram");
    /**
     * Failure twin of {@link #TELEGRAM_POST_UPDATE_HINT_FILE}. Planted by the
     * detached install script's FAILURE branch (only for an IPC-triggered
     * install — i.e. when the success hint above existed) and contains the
     * pm-install error text. Consumed by the reborn TelegramBotDaemon's
     * notifyTunnel handler so a Telegram-initiated install that fails AFTER it
     * was scheduled surfaces the reason to the owner — symmetric with the
     * web/app failure surfaces — instead of going silent behind the generic
     * "Tunnel URL Changed" copy. Mutually exclusive with the success hint: the
     * failure branch deletes the success hint, the success branch never plants
     * this one, so the bot never sends both a success and a failure message.
     */
    public static final String TELEGRAM_INSTALL_FAILED_HINT_FILE =
            at.greenchili.surfmate.util.DaemonStorage.rebase("/data/user_de/0/com.android.shell/surfmate/surfmate_install_failed_pending_telegram");

    public static final String EXTRA_POST_UPDATE = "post_update";

    private UpdateLifecycle() {}

    /** Detects whether this launch came right after a package install. */
    public static boolean isPostUpdateLaunch(Context ctx, Intent intent) {
        if (intent != null && intent.getBooleanExtra(EXTRA_POST_UPDATE, false)) return true;
        if (new File(POST_UPDATE_FILE).exists()) return true;
        if (new File(UPDATE_IN_PROGRESS_FILE).exists()) return true;
        try {
            return ctx.getSharedPreferences("app_updater", Context.MODE_PRIVATE)
                    .getBoolean("just_updated", false);
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * 0.1.38.10: Zeitmarke je Schritt des Hard-Resets. Das Skript gibt {@code HR_T <Schritt> <Sekunden seit Beginn>} aus,
     * der Rückruf schreibt sie als {@code HARDRESET_SCHRITTE} ins daemon_launcher.log (so zeigt das nächste Update, wo
     * die Zeit hängt).
     */
    private static String hrMarke(String name) {
        return "echo \"HR_T " + name + " $(( $(date +%s) - HR_T0 ))\"\n";
    }

    /**
     * Läuft der tailscaled von Weg 1 nach dem Hard-Reset weiter (er wird nicht mehr beendet)? Aus den Zeilen
     * {@code HR_TS laeuft <pid> <ja|nein>} wird {@code anzahl=N;exe_geloescht=ja|nein}; leer = läuft keiner.
     * Eine mit dem Update gelieferte neue Version wird dann erst nach dem nächsten Gerätestart wirksam.
     */
    static String tailscaledAusAusgabe(String ausgabe) {
        if (ausgabe == null) return "";
        int n = 0;
        boolean geloescht = false;
        for (String z : ausgabe.split("\n")) {
            String[] t = z.trim().split(" ");
            if (t.length == 4 && t[0].equals("HR_TS") && t[1].equals("laeuft") && t[2].matches("[0-9]{1,9}")) {
                n++;
                if (t[3].equals("ja")) geloescht = true;
            }
        }
        return n == 0 ? "" : "anzahl=" + n + ";exe_geloescht=" + (geloescht ? "ja" : "nein");
    }

    /** Aus der Ausgabe des Hard-Reset-Skripts die Schritt-Zeiten als {@code name=Sekunden;…} (leer = keine Marken). */
    static String schritteAusAusgabe(String ausgabe, long javaStartSek) {
        if (ausgabe == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String z : ausgabe.split("\n")) {
            z = z.trim();
            if (z.startsWith("HR_BEGIN ")) {
                try {
                    long skript = Long.parseLong(z.substring(9).trim());
                    sb.append("vorlauf=").append(Math.max(0, skript - javaStartSek)).append(';');
                } catch (NumberFormatException ignored) { }
            } else if (z.startsWith("HR_T ")) {
                String[] t = z.split(" ");
                if (t.length == 3 && t[1].matches("[a-z_]{1,20}") && t[2].matches("-?[0-9]{1,6}")) {
                    sb.append(t[1]).append('=').append(t[2]).append(';');
                }
            }
        }
        return sb.toString();
    }

    /**
     * Hard-kill every known daemon + watchdog, wipe lock/sentinel files, then
     * invoke onComplete on the same thread that the underlying launcher uses.
     * Safe to call when no update is in progress — it's just a sweep.
     */
    public static void hardResetDaemons(Context ctx, Runnable onComplete) {
        Log.i(TAG, "post-update detected — hard-resetting daemons");
        at.greenchili.surfmate.logging.LauncherEreignisse.merken("HARDRESET_BEGINN", "");
        long start = System.currentTimeMillis();
        // 0.1.38.10 Stufe −1: ab hier startet kein Daemon, bis der Hard-Reset durch ist (HardResetTor, ZEITLIMIT_MS).
        HardResetTor.erwarten(android.os.SystemClock.elapsedRealtime());
        String diLink5CaptureCleanup = diLink5CaptureKillScript();
        // We allocate a fresh AdbDaemonLauncher here because hardResetDaemons
        // is static and runs early in MainActivity.runDaemonStartup, before
        // the per-Activity DaemonStartupManager's launcher is necessarily
        // initialized. To avoid leaking the launcher's executor + nested
        // tunnel-poll scheduler thread, we explicitly call
        // closePersistentConnection() inside the completion paths.
        AdbDaemonLauncher launcher = new AdbDaemonLauncher(ctx);

        // Single script-via-tmp-file invocation — `executeShellScript` writes
        // the body to /data/user_de/0/com.android.shell/surfmate/<id>.sh and runs it. The running shell's
        // argv is `sh /data/user_de/0/com.android.shell/surfmate/<id>.sh`, so toybox `pkill -f` cannot
        // match the calling shell on a daemon pattern. This replaces the
        // earlier 3-phase split that was needed to defend against pkill
        // self-suicide on a `sh -c "..."` payload.
        //
        // Order within the script:
        //   1. Plant per-daemon disable sentinels (defense for any watchdog
        //      we might miss with the pkill — gate-1/gate-2 in the watchdog
        //      loop catches them on the next iteration).
        //   2. (bis 0.1.38: Watchdog-Skripte löschen; seit 0.1.38.1 bleiben sie stehen, siehe unten.)
        //   3. pkill / killall cascade — daemon binaries first, then
        //      ancillary binaries (cloudflared/zrok/sing-box/wgproxy; tailscaled von Weg 1 bleibt).
        //      No self-match risk now.
        //   4. Wait briefly for processes to settle, THEN remove lock files.
        //      Doing this AFTER the kills (rather than before) prevents the
        //      "phase-2 lockfile resurrection" race: previously phase 1 rm'd
        //      *_daemon.lock, then between phases the still-alive daemon
        //      wrote its PID back into the lock, then phase 2 pkilled it,
        //      leaving an orphaned lock that the new daemon refused to
        //      overwrite.
        //   5. Clear per-daemon disable sentinels so the new MainActivity
        //      doesn't see them and refuse to start. Post-update sentinels
        //      (UPDATE_IN_PROGRESS_FILE, POST_UPDATE_FILE) are KEPT; the
        //      new process consumes them via isPostUpdateLaunch.
        // Plant disable sentinels ONLY for CORE daemons (camera + sentry +
        // acc-sentry). Machine-written markers are wiped again at the end —
        // the transient sentinel just keeps any watchdog we miss with the pkill
        // from re-exec'ing the daemon between our kill and the new process'
        // launch. OPTIONAL daemons (telegram / zrok / tailscale / wireguard / singbox) are
        // deliberately NOT touched here: their disable sentinel is a DURABLE
        // user-stop signal that must PERSIST across an app update. Planting
        // (and then wiping) the telegram/zrok sentinels here would resurrect a
        // user-stopped tunnel/bot — and would also destroy the only cross-UID
        // record of a UI stop that AccSentryDaemon's ACC-on auto-start gate
        // relies on. Write-if-absent also preserves a manual CORE-daemon stop.
        String script =
                "echo \"HR_BEGIN $(date +%s)\"\nHR_T0=$(date +%s)\n" +
                "[ -f /data/user_de/0/com.android.shell/surfmate/camera_daemon.disabled ] || " +
                "echo \"disabled by post-update reset at $(date)\" > /data/user_de/0/com.android.shell/surfmate/camera_daemon.disabled\n" +
                "chmod 666 /data/user_de/0/com.android.shell/surfmate/camera_daemon.disabled 2>/dev/null\n" +
                "[ -f /data/user_de/0/com.android.shell/surfmate/sentry_daemon.disabled ] || " +
                "echo \"disabled by post-update reset at $(date)\" > /data/user_de/0/com.android.shell/surfmate/sentry_daemon.disabled\n" +
                "chmod 666 /data/user_de/0/com.android.shell/surfmate/sentry_daemon.disabled 2>/dev/null\n" +
                "[ -f /data/user_de/0/com.android.shell/surfmate/acc_sentry_daemon.disabled ] || " +
                "echo \"disabled by post-update reset at $(date)\" > /data/user_de/0/com.android.shell/surfmate/acc_sentry_daemon.disabled\n" +
                "chmod 666 /data/user_de/0/com.android.shell/surfmate/acc_sentry_daemon.disabled 2>/dev/null\n" +
                "rm -f /data/user_de/0/com.android.shell/surfmate/cam_watchdog.pid 2>/dev/null\n" +
                // 0.1.38.1: start_cam_daemon.sh, start_acc_sentry.sh und start_telegram.sh bleiben stehen.
                // Bis 0.1.38 löschte dieser Schritt sie; scheiterte danach das Neuschreiben (Auto 04.11.,
                // 01:04: Schreiber getötet), fehlte das Skript, bis jemand von Hand eingriff. Die Sentinels
                // oben und die Kill-Kaskade halten alte Watchdogs auf, das Neuschreiben ersetzt die Skripte
                // atomar per mv. start_zrok.sh ist eine Altlast und darf weiter weg.
                "rm -f /data/user_de/0/com.android.shell/surfmate/start_zrok.sh 2>/dev/null\n" +
                hrMarke("sentinels") +
                // ps+awk+kill cascade — single-source-of-truth process
                // names below. Each pattern walks /proc and SIGKILLs the
                // matching PIDs except the calling shell's own PID.
                // 0.1.38.9 Schnellstart 2: EINE ps-Abfrage für alle eigenen Prozesse statt 13 nacheinander (auf dem Auto
                // je ~1 s, der Hard-Reset dauerte 15-30 s) und nur noch EIGENE Prozesse (Klassenpfad/Ordner), nie die
                // einer anderen Werkzeug-App mit gleichen Prozessnamen. Zweiter Durchgang nach dem Settle unten.
                EIGENE_PROZESSE_KILL + "\n" +
                diLink5CaptureCleanup +
                at.greenchili.surfmate.launcher.DaemonLauncher.Companion.psAwkKillMehrereLine(
                        java.util.Arrays.asList("cloudflared", "zrok", "sing-box", "wgproxy")) +
                // 0.1.38.10 (der Zugang darf nie vom Update abhängen): der Hard-Reset beendet den tailscaled
                // von Weg 1 NICHT mehr (bis 0.1.38.9 mit -9, 0.1.14: nur unser --statedir, nie der Notzugang). Er läuft über
                // das Update weiter (deploy/update-on-car.sh: "tailscaled bleibt"); der Start prüft isRunning und startet nur,
                // wenn er fehlt. Preis: eine mit dem Update gelieferte neue tailscaled-Version wird erst nach dem nächsten
                // Gerätestart wirksam. Nur lesen und protokollieren (TAILSCALED_ALT_LAEUFT):
                at.greenchili.surfmate.launcher.TailscaledProzesse.laeuftSkript(
                        at.greenchili.surfmate.launcher.TailscaledProzesse.PANOPTES_STATEDIR) + "\n" +
                "killall -9 cloudflared 2>/dev/null\n" +
                "killall -9 zrok 2>/dev/null\n" +
                "killall -9 wgproxy 2>/dev/null\n" +
                "killall -9 sing-box 2>/dev/null\n" +
                hrMarke("kill") +
                // Brief settle so SIGKILL'd daemons release their lockfiles
                // before we rm the lock files. Without this delay, a daemon
                // mid-shutdown could still rewrite the lock between our
                // pkill and our rm.
                "sleep 1\n" +
                EIGENE_PROZESSE_KILL + "\n" +
                hrMarke("settle") +
                // Watchdog ownership locks intentionally do not match
                // *_daemon.lock. SIGKILL bypasses the watchdog EXIT trap, so
                // these must be removed explicitly after the old wrappers are
                // dead or the replacement watchdog will fail closed.
                "rm -rf /data/user_de/0/com.android.shell/surfmate/cam_watchdog.lock /data/user_de/0/com.android.shell/surfmate/acc_sentry_watchdog.lock 2>/dev/null\n" +
                "rm -f /data/user_de/0/com.android.shell/surfmate/*_daemon.lock 2>/dev/null\n" +
                // The detached install script has finished executing by the
                // time the new MainActivity runs this reset — remove it so it
                // doesn't linger in /data/user_de/0/com.android.shell/surfmate (hygiene; it's rewritten via
                // atomic tmp+rename each install so it never blocks recovery).
                "rm -f /data/user_de/0/com.android.shell/surfmate/surfmate_install.sh 2>/dev/null\n" +
                hrMarke("sperren") +
                // Clear only known machine-written CORE markers. A pre-existing
                // "disabled by ui/telegram" sentinel is durable manual intent
                // and must survive the update.
                // The post-update markers (UPDATE_IN_PROGRESS_FILE /
                // POST_UPDATE_FILE) survive — new process owns them.
                "for S in /data/user_de/0/com.android.shell/surfmate/camera_daemon.disabled " +
                at.greenchili.surfmate.util.DaemonStorage.rebase("/data/user_de/0/com.android.shell/surfmate/sentry_daemon.disabled ") +
                "/data/user_de/0/com.android.shell/surfmate/acc_sentry_daemon.disabled; do\n" +
                "  R=$(head -1 \"$S\" 2>/dev/null)\n" +
                "  case \"$R\" in " +
                "'disabled by post-update reset'*|'disabled for update'*|" +
                "'disabled by stopAllDaemons sweep'*|'disabled by killDaemon'*) " +
                "rm -f \"$S\" 2>/dev/null;; esac\n" +
                "done\n" +
                hrMarke("marker") +
                // 0.1.38.10: kein Log-Archiv-Schritt mehr im Hard-Reset (am Auto 14 s und mehr, Deploy 3, 87 Archivdateien).
                // Logs gehen auch ohne ihn nie verloren (0.1.36, 03.11.): nichts wird gelöscht, jeder Wächter
                // archiviert sein Log beim eigenen Start (log_archivieren im Start-Skript) und räumt dabei EINMAL auf,
                // im Betrieb kürzt der Beobachter die Logs mit Archivkopie (log_kopieren_kuerzen).
                "rm -f " + UPDATE_IN_PROGRESS_FILE + " " + POST_UPDATE_FILE + " 2>/dev/null\n" +
                hrMarke("gesamt") +
                "echo done\n";

        launcher.executeShellScript(script, new AdbDaemonLauncher.LaunchCallback() {
            @Override public void onLog(String m) {
                // 0.1.38.10: Zeit je Schritt (vorlauf = ADB-Verbindung + Skript schreiben, dann die Schritte des Skripts)
                String schritte = schritteAusAusgabe(m, start / 1000L);
                if (!schritte.isEmpty()) {
                    at.greenchili.surfmate.logging.LauncherEreignisse.merken("HARDRESET_SCHRITTE", schritte);
                }
                String ts = tailscaledAusAusgabe(m);
                if (!ts.isEmpty()) {
                    at.greenchili.surfmate.logging.LauncherEreignisse.merken("TAILSCALED_ALT_LAEUFT", ts);
                }
            }
            @Override public void onLaunched() {
                // releasePerInstanceResources() — NOT closePersistentConnection.
                // The latter closes the process-wide shared Dadb that the
                // long-lived daemonStartupManager.adbLauncher is concurrently
                // using; that would surface as spurious onError on its
                // in-flight tasks. We only own this launcher's executor
                // and tunnel-poll scheduler — release just those.
                try { launcher.releasePerInstanceResources(); } catch (Exception ignored) {}
                finishHardReset(onComplete, start, false);
            }
            @Override public void onError(String e) {
                Log.w(TAG, "hard reset error (continuing): " + e);
                try { launcher.releasePerInstanceResources(); } catch (Exception ignored) {}
                finishHardReset(onComplete, start, true);
            }
        });
    }

    private static void finishHardReset(Runnable onComplete, long start, boolean withError) {
        long ms = System.currentTimeMillis() - start;
        Log.i(TAG, "hard reset complete in " + ms + "ms" + (withError ? " (with warning)" : ""));
        at.greenchili.surfmate.logging.LauncherEreignisse.merken(withError ? "HARDRESET_FEHLER" : "HARDRESET_ENDE",
                "ms=" + ms);
        // 0.1.38.10: kein fester Settle mehr (vorher 1 s); das Skript wartet selbst auf das Ende der Prozesse
        // ("sleep 1" + zweiter Durchgang), und das Tor gibt die Stufen frei.
        HardResetTor.beenden();
        if (onComplete != null) onComplete.run();
    }
}
