package at.greenchili.surfmate.launcher

import android.content.Context
import android.provider.Settings
import at.greenchili.surfmate.logging.LogManager
import at.greenchili.surfmate.logging.LauncherEreignisse

/**
 * Launches daemon processes via ADB shell using app_process.
 * 
 * This class handles launching various daemon processes that run independently
 * of the app's lifecycle as shell user (UID 2000).
 * 
 * Note: This uses ADB shell (via AdbShellExecutor/Dadb) for launching daemons.
 * For system shell operations, see the shell/ package (PrivilegedShellSetup, etc.)
 * 
 * ProxyDaemon is launched via privileged shell (UID 1000) for elevated privileges.
 */
class DaemonLauncher(
    private val context: Context,
    private val adbShellExecutor: AdbShellExecutor,
    private val logManager: LogManager
) {
    companion object {
        private val TAG = "DaemonLauncher"
        
        // Log file paths for daemons
        private val CAMERA_DAEMON_LOG = at.greenchili.surfmate.util.DaemonStorage.rebase("/data/user_de/0/com.android.shell/surfmate/cam_daemon.log")
        private val SENTRY_DAEMON_LOG = at.greenchili.surfmate.util.DaemonStorage.rebase("/data/user_de/0/com.android.shell/surfmate/sentry_daemon.log")
        private val SENTRY_DAEMON_LOG_SYSTEM = "/data/data/com.android.providers.settings/sentry_daemon.log"
        private val ACC_SENTRY_DAEMON_LOG = at.greenchili.surfmate.util.DaemonStorage.rebase("/data/user_de/0/com.android.shell/surfmate/acc_sentry_daemon.log")
        private val TELEGRAM_DAEMON_LOG = at.greenchili.surfmate.util.DaemonStorage.rebase("/data/user_de/0/com.android.shell/surfmate/telegrambotdaemon.log")

        // ==================== LOG ROTATION ====================
        // Hard ceiling for a daemon's stdout-redirect log (the files the UI
        // surfaces under /data/user_de/0/com.android.shell/surfmate). Bounded by the in-run poller below,
        // NOT by DaemonLogger's Java rotation (compiled out in release via
        // DaemonLogConfig) — the shell poller is the only effective mechanism
        // on-device. Kept as a single coherent constant rather than threaded
        // from app config: daemon RELAUNCH paths (Telegram bot → cam/acc-sentry)
        // run under UID 2000 and can't read the app's SharedPreferences, so a
        // config-driven shell limit would be split-brain across launch sources.
        const val LOG_MAX_BYTES = 5_242_880L  // 5 MB
        // Housekeeping cadence while the daemon is alive. The poller already
        // ticks every 60 s (see logRotateCoprocessLines); this only decides how
        // often it stats the file. 0.1.36: 30 min instead of 4 h, because an
        // oversized log is now COPIED into the archive (LogArchiv) instead of
        // being thrown away, and a 4 h window let it grow far past the limit.
        const val LOG_POLL_INTERVAL_SEC = 1_800  // 30 minutes

        // Process names for daemon identification
        private const val CAMERA_DAEMON_PROCESS = "byd_cam_daemon"
        private const val SENTRY_DAEMON_PROCESS = "sentry_daemon"
        private const val ACC_SENTRY_DAEMON_PROCESS = "acc_sentry_daemon"
        private val TELEGRAM_DAEMON_PROCESS = "telegram_bot_daemon"
        private val ZROK_PROCESS = "zrok"
        private val CAMERA_WATCHDOG_SCRIPT =
            at.greenchili.surfmate.util.DaemonStorage.rebase("/data/user_de/0/com.android.shell/surfmate/start_cam_daemon.sh")
        private val CAMERA_WATCHDOG_PID_FILE =
            at.greenchili.surfmate.util.DaemonStorage.rebase("/data/user_de/0/com.android.shell/surfmate/cam_watchdog.pid")
        private val CAMERA_WATCHDOG_LOCK_PATH =
            at.greenchili.surfmate.util.DaemonStorage.rebase("/data/user_de/0/com.android.shell/surfmate/cam_watchdog.lock")
        private val ACC_SENTRY_WATCHDOG_SCRIPT =
            at.greenchili.surfmate.util.DaemonStorage.rebase("/data/user_de/0/com.android.shell/surfmate/start_acc_sentry.sh")
        private val ACC_SENTRY_WATCHDOG_PID_FILE =
            at.greenchili.surfmate.util.DaemonStorage.rebase("/data/user_de/0/com.android.shell/surfmate/acc_sentry_watchdog.pid")
        private val ACC_SENTRY_WATCHDOG_LOCK_PATH =
            at.greenchili.surfmate.util.DaemonStorage.rebase("/data/user_de/0/com.android.shell/surfmate/acc_sentry_watchdog.lock")
        // Telegram-Watchdog bekommt dieselbe Einzel-Sperre wie Kamera/Wächter: UI-Start
        // (DaemonStartupManager) und AccSentryDaemon starten ihn beim Booten fast
        // gleichzeitig; ohne Sperre blieben zwei Schleifen, die sich gegenseitig den
        // Bot abschießen (Neustart alle ~15 s, 20.10.2026).
        private val TELEGRAM_WATCHDOG_SCRIPT =
            at.greenchili.surfmate.util.DaemonStorage.rebase("/data/user_de/0/com.android.shell/surfmate/start_telegram.sh")
        private val TELEGRAM_WATCHDOG_PID_FILE =
            at.greenchili.surfmate.util.DaemonStorage.rebase("/data/user_de/0/com.android.shell/surfmate/telegram_watchdog.pid")
        private val TELEGRAM_WATCHDOG_LOCK_PATH =
            at.greenchili.surfmate.util.DaemonStorage.rebase("/data/user_de/0/com.android.shell/surfmate/telegram_watchdog.lock")

        /**
         * Test one `ps -A -o S,ARGS` snapshot for a live daemon process.
         * A SIGKILL can leave a short-lived zombie row; it is not a running daemon.
         */
        @JvmStatic
        fun processAliveInSnapshot(snapshot: String, processName: String): Boolean {
            return snapshot.lineSequence().any { rawLine ->
                val line = rawLine.trim()
                val separator = line.indexOfFirst { it == ' ' || it == '\t' }
                if (separator <= 0 || line.substring(0, separator).startsWith("Z")) {
                    return@any false
                }
                val args = line.substring(separator).trimStart()
                if (processName == SENTRY_DAEMON_PROCESS) {
                    args.contains(processName) && !args.contains("acc_")
                } else {
                    args.contains(processName)
                }
            }
        }
        
        // Use privileged shell for proxy daemon
        
        // DISABLED: Privileged shell for sentry daemon causes BYD default dashcam
        // to show "no signal". Running as UID 1000 elevates camera priority via
        // setPkg2AccWhiteList, stealing AVMCamera feed from the dashcam app.
        private const val USE_PRIVILEGED_SHELL_FOR_SENTRY = false
        
        // ACC Sentry daemon MUST run via ADB shell (UID 2000) for screen control
        private const val USE_ADB_SHELL_FOR_ACC_SENTRY = true
        
        // Guards to prevent concurrent launch attempts (shared across all instances).
        //
        // These are TIMESTAMPED, not plain booleans: a launch sets the guard to
        // the current time, and the guard is considered "held" only while it is
        // both non-zero AND younger than LAUNCH_GUARD_TIMEOUT_MS. This makes the
        // guard self-healing — if a launch path drops its async callback (e.g.
        // the ADB-shell executor never invokes onSuccess/onError, which wedged
        // CameraDaemon restarts), the stale guard expires instead of blocking
        // every future launch forever. 0 == not held.
        private const val LAUNCH_GUARD_TIMEOUT_MS = 60_000L
        @Volatile
        private var accSentryLaunchStartedAt = 0L
        @Volatile
        private var cameraLaunchStartedAt = 0L
        // Telegram needs the same guard: boot (+15s timer), the ACC-off edge,
        // the 30s health check and a user tap can all call launchTelegramDaemon
        // within the same window. Each deploys start_telegram.sh and nohup's it,
        // so without a guard several watchdogs end up alive; every daemon they
        // spawn SIGKILLs the others via killOldInstances, those watchdogs
        // respawn, and the loop re-announces "bot ready" on each cycle.
        @Volatile
        private var telegramLaunchStartedAt = 0L

        private val GUARD_SPERRE = Any()

        /**
         * 0.1.38.3 (fix-waechter-skript): Prüfen und Setzen in einem Schritt. Bis 0.1.38.3 getrennt; am Auto
         * (04.11. 12:15:35) liefen so zwei Wächter-Starts in derselben Sekunde und schrieben gleichzeitig.
         */
        @JvmStatic
        internal fun guardNehmen(lesen: () -> Long, setzen: (Long) -> Unit): Boolean = synchronized(GUARD_SPERRE) {
            if (guardHeld(lesen())) false else { setzen(System.currentTimeMillis()); true }
        }

        /**
         * 0.1.38.3 (fix-waechter-skript, Auto 04.11. 12:15:37): Ein hängender anderer adb-Befehl riss seine Deadline,
         * die gemeinsame Verbindung wurde geschlossen und der gerade laufende Wächter-Schreiber riss mit ab
         * („Execution failed: null“). Das Skript blieb alt, der Daemon lief ohne Watchdog. Das Schreiben ist atomar
         * (Temp-Datei, sh -n, mv), darum einmal wiederholen; nur ein echter Skriptfehler (SKRIPT_FEHLER) nicht.
         */
        @JvmStatic
        fun skriptSchreibenMitWiederholung(
            art: String,
            versuch: (AdbShellExecutor.ShellCallback) -> Unit,
            ziel: AdbShellExecutor.ShellCallback
        ) {
            versuch(object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) = ziel.onSuccess(output)
                override fun onError(error: String) {
                    if (error.contains("SKRIPT_FEHLER")) { ziel.onError(error); return }
                    LauncherEreignisse.merken("SKRIPT_WIEDERHOLT", "art=$art ${error.take(100)}")
                    versuch(ziel)
                }
            })
        }

        /** True if a launch guard set at [startedAt] is still active (held + unexpired). */
        private fun guardHeld(startedAt: Long): Boolean {
            if (startedAt == 0L) return false
            val age = System.currentTimeMillis() - startedAt
            // age < 0 (clock moved back) is treated as expired, not held.
            return age in 0 until LAUNCH_GUARD_TIMEOUT_MS
        }

        /**
         * Build a shell snippet that ps+greps for [pattern], filters out the
         * helper grep process AND the calling shell's PID, and SIGKILLs the
         * remaining matches. This replaces every `pkill -9 -f '<pattern>'`
         * that used to live in shell payloads — pkill -f matches against
         * /proc/<pid>/cmdline, which means the calling sh -c wrapper
         * (whose cmdline contains the literal pattern) self-matches and
         * gets SIGKILLed before any subsequent commands in the same
         * payload run. ps+awk+kill avoids that by keying on PID.
         *
         * Caller note: [pattern] is interpolated raw into a shell
         * argument to `grep -F`. Pass static patterns only; if a future
         * caller needs to pass user input, escape single quotes first.
         *
         * @return a multi-statement shell snippet, terminated with `; ` so
         *         it can concatenate cleanly into a one-line payload.
         */
        fun psAwkKill(pattern: String): String =
            // 0.1.38.9: die Muster des eigenen Daemon-Stapels beenden NUR eigene Prozesse (EigeneProzesse); fremde
            // Werkzeug-Apps mit gleichen Prozessnamen (Overdrive-artig) bleiben unberührt.
            if (pattern in EIGENE_MUSTER) EigeneProzesse.beenden(pattern) else
            psAwkKill("$pattern")

        /** Prozessnamen/-muster des Panoptes-Stapels (siehe psAwkKill). */
        private val EIGENE_MUSTER = setOf(
            "cam_daemon", "byd_cam_daemon", "start_cam_daemon", "sentry_daemon", "acc_sentry", "acc_sentry_daemon",
            "start_acc_sentry", "start_telegram", "start_telegram.sh", "telegram_bot_daemon", "sentry_proxy"
        )

        /** Newline-terminated form for use inside `buildString` script bodies. */
        fun psAwkKillLine(pattern: String): String = psAwkKill(pattern) + "\n"

        /** Mehrere (fremde/alte) Muster mit EINER ps-Abfrage, Zeilenumbruch am Ende. Kein Eigen-Test, nur für Altlasten. */
        @JvmStatic
        fun psAwkKillMehrereLine(patterns: List<String>): String {
            val e = patterns.joinToString(" ") { "-e '" + it.replace("'", "") + "'" }
            return "MY_PID=\$\$; ps -A -o PID,ARGS | grep -F $e | grep -v grep " +
                "| awk '{print \$1}' | while read pid; do " +
                "if [ \"\$pid\" != \"\$MY_PID\" ]; then kill -9 \$pid 2>/dev/null; fi; done\n"
        }

        /** 0.1.38.9: wie [psAwkKill], aber nur EIGENE Prozesse (siehe [EigeneProzesse]). */
        @JvmStatic
        fun psAwkKillEigene(pattern: String): String = EigeneProzesse.beenden(pattern)

        @JvmStatic
        fun psAwkKillEigeneLine(pattern: String): String = EigeneProzesse.beendenZeile(pattern)

        const val CLOUDFLARED_STOPPED = "CLOUDFLARED_STOPPED"
        const val CLOUDFLARED_STILL_RUNNING = "CLOUDFLARED_STILL_RUNNING"

        /**
         * One-line shell payload that SIGKILLs cloudflared and prints exactly one of
         * [CLOUDFLARED_STOPPED] / [CLOUDFLARED_STILL_RUNNING]. Callers must treat output with
         * neither marker as "not verified", never as stopped.
         *
         * - Kill by PID ([psAwkKill]): the binary runs as `${nativeLibraryDir}/libcloudflared.so`
         *   and toybox `killall` matches exact process names only, so `killall -9 cloudflared`
         *   matched nothing. The patterns are the binary itself (`libcloudflared.so`, or
         *   `/cloudflared ` for older installs that run `/data/user_de/0/com.android.shell/surfmate/cloudflared`) — NOT a
         *   bare "cloudflared", which would also SIGKILL concurrent shells that merely mention
         *   `cloudflared.disabled` / `cloudflared.log` (health-check sentinel probe, URL grep,
         *   Telegram log tail). Each pattern also catches the `sh -c` launch wrapper.
         * - Self-exclusion: the `while` subshell is a fork of this `sh -c` (same argv) but is
         *   dropped by `grep -v grep` because this payload's argv contains "grep".
         * - Verify by process NAME (this shell is `sh`, never a match) and skip zombie rows: a
         *   SIGKILLed process can be listed as `Z` until reaped (same rule as
         *   [processAliveInSnapshot]).
         */
        @JvmStatic
        fun killCloudflaredAndVerify(): String =
            psAwkKill("libcloudflared.so") + "; " +
            psAwkKill("/cloudflared ") + "; " +
            "killall -9 cloudflared libcloudflared.so 2>/dev/null; sleep 0.3; " +
            "if ps -A -o S,NAME | grep -v '^ *Z ' | grep -qF cloudflared; " +
            "then echo $CLOUDFLARED_STILL_RUNNING; else echo $CLOUDFLARED_STOPPED; fi"

        /**
         * Build the shell lines for a backgrounded log-size poller that bounds
         * the daemon's stdout-redirect log (`$LOG_FILE`) *while the daemon is
         * alive*, not merely at respawn.
         *
         * Why a co-process: in every watchdog the daemon is launched via a
         * BLOCKING call (`app_process ... >> "$LOG_FILE"`, or `wait $PID`) that
         * does not return until the daemon dies. A size-check at the loop top
         * therefore fires only once per crash/respawn — useless for a healthy
         * daemon that stays up for days. This poller runs in parallel for the
         * lifetime of [daemonPidVar] and truncates on a fixed cadence.
         *
         * Truncation uses `: > "$LOG_FILE"` (truncate-in-place), NOT rm+recreate:
         * the daemon holds an O_APPEND fd to this inode, so an in-place truncate
         * keeps its writes flowing to the same file. An rm would strand the fd
         * on the unlinked inode and the log would silently stop updating.
         *
         * Sleep cadence: the poller sleeps in 60s increments and only truncates
         * once the accumulated elapsed time crosses [LOG_POLL_INTERVAL_SEC],
         * rather than one big `sleep $LOG_POLL_INTERVAL_SEC`. This matters on a
         * crash-looping daemon: when the outer loop kills the poller subshell
         * mid-sleep, the in-flight `sleep` child is orphaned to init and runs to
         * completion — a 60s orphan drains harmlessly, a 4h orphan would pile up
         * (one per respawn) against the phantom-process cap. The short loop also
         * lets the poller self-exit within ~60s of the daemon dying (its
         * `kill -0` check fails on the next tick) instead of lingering up to 4h.
         * Same 60s-tick shape the zrok edge probe already uses.
         *
         * The caller MUST:
         *   1. background the daemon and capture its pid into [daemonPidVar],
         *   2. emit these lines (which start a background subshell),
         *   3. `wait` on the daemon pid,
         *   4. kill the poller pid (captured into [pollerPidVar]) afterward.
         *
         * @param daemonPidVar shell var name holding the daemon PID (no `$`).
         * @param pollerPidVar shell var name to receive the poller PID (no `$`).
         */
        fun logRotateCoprocessLines(daemonPidVar: String, pollerPidVar: String): List<String> = listOf(
            "  (",
            // Kill our own in-flight `sleep` child (and only it) when the outer
            // loop signals us via `kill $pollerPidVar`. Without this the
            // backgrounded `sleep` is orphaned to init and lives out its full
            // 60s on every respawn. NOT `kill 0` — that would signal the entire
            // process group, including the daemon and the watchdog itself.
            "    trap 'kill \$SLEEP_PID 2>/dev/null; exit 0' TERM",
            "    LOG_ELAPSED=0",
            "    while kill -0 \$$daemonPidVar 2>/dev/null; do",
            "      sleep 60 &",
            "      SLEEP_PID=\$!",
            "      wait \$SLEEP_PID",
            "      LOG_ELAPSED=\$((LOG_ELAPSED + 60))",
            "      if [ \$LOG_ELAPSED -ge $LOG_POLL_INTERVAL_SEC ]; then",
            "        LOG_ELAPSED=0",
            "        if [ -f \"\$LOG_FILE\" ]; then",
            "          LOG_SZ=\$(stat -c%s \"\$LOG_FILE\" 2>/dev/null || echo 0)",
            "          if [ \"\$LOG_SZ\" -gt $LOG_MAX_BYTES ]; then",
            // 0.1.36: copy into the archive, then truncate in place (the
            // daemon keeps its O_APPEND fd, see above). Never just drop it.
            "            log_kopieren_kuerzen \"\$LOG_FILE\"",
            "          fi",
            "        fi",
            "      fi",
            "    done",
            "  ) &",
            "  $pollerPidVar=\$!"
        )

        /**
         * 0.1.36 (logs must survive restarts and updates): shell
         * functions of [at.greenchili.surfmate.logging.LogArchiv] plus the
         * archive folder next to [logFile]. Emitted near the top of every
         * watchdog; definitions only, nothing is touched yet.
         */
        fun logArchivDefinitionLines(logFile: String): List<String> =
            listOf("LOGARCHIV=\"${at.greenchili.surfmate.logging.LogArchiv.ordnerFuer(logFile)}\"") +
                at.greenchili.surfmate.logging.LogArchiv.shellFunktionen()

        /**
         * Top of a watchdog loop: once a daemon has run in THIS watchdog
         * (`LA_GELAUFEN=1`, set after `wait`), move its log into the archive
         * before the next start, so every run keeps its own file. Replaces the
         * old size guard that truncated an oversized log to zero (one reason
         * cam_daemon.log "started over" after a restart). Moving is safe here:
         * the daemon is dead, nobody holds the file open.
         */
        fun logArchivLoopLines(): List<String> = listOf(
            "  if [ \"\$LA_GELAUFEN\" = \"1\" ]; then",
            "    log_archivieren \"\$LOG_FILE\"",
            "  fi"
        )

        /**
         * Start path without a watchdog script: archive the previous log, then
         * append (`>>`). Before 0.1.36 these paths used `>` and wiped the log
         * of the run that just ended.
         */
        @JvmStatic
        fun nohupMitArchiv(innerCmd: String, logFile: String): String =
            at.greenchili.surfmate.logging.LogArchiv.archivBefehl(logFile) +
                "nohup sh -c '$innerCmd' >> $logFile 2>&1 &"

        /**
         * Build the command used by the UI path to deploy a watchdog script.
         *
         * `echo` is not byte-preserving across Android shells: sequences such
         * as `\000` may be interpreted and written as a binary NUL. The
         * watchdog ownership probe contains exactly that sequence for
         * `/proc/<pid>/cmdline`, so an echo-written script can be corrupted
         * before it ever reaches its first log statement. `printf` with a
         * fixed `%s\n` format preserves the escaped argument literally.
         */
        @JvmStatic
        fun buildWatchdogScriptWriteCommand(
            scriptPath: String,
            scriptLines: List<String>
        ): String = buildString {
            // 0.1.36.1: nie mehr direkt in das Ziel schreiben. Eine eigene Temp-Datei je
            // Shell ($$), dann sh -n + Zeilenzahl, dann mv. So kann ein zweiter Schreiber
            // (AccSentry zeilenweise, UI in einem Zug) keine Zeilen mehr anhängen.
            val tmp = skriptTempPfad(scriptPath, "\$\$")
            // 0.1.38.1: liegengebliebene Temp-Dateien eines getöteten Schreibers aufräumen, nur
            // ältere als 5 min (nie die Temp eines gleichzeitigen Schreibers).
            append(tempAufraeumenCommand(scriptPath))
            append("rm -f $tmp 2>/dev/null; ")
            scriptLines.forEachIndexed { index, line ->
                append(buildScriptLineWriteCommand(line, index == 0, tmp))
                append("; ")
            }
            append(buildSkriptEinsetzenCommand(tmp, scriptPath, scriptLines.size, "755"))
        }

        /**
         * Temp-Datei neben dem Ziel (gleiches Dateisystem, damit `mv` ein atomares rename ist).
         * [tag] muss je Schreiber eindeutig sein (Shell-PID `$$` oder Java-Nonce).
         */
        @JvmStatic
        fun skriptTempPfad(scriptPath: String, tag: String): String {
            // 0.1.38.1: kein Daemon-Muster mehr im Namen (bis 0.1.38 "<ziel>.neu.<tag>"). Die
            // Kill-Muster (start_cam_daemon, cam_daemon, acc_sentry, telegram …) treffen per
            // Teilstring jede argv; am Auto (0.1.38, 04.11.) starb so ein Schreiber mitten im Skript.
            val schnitt = scriptPath.lastIndexOf('/')
            val ordner = scriptPath.substring(0, schnitt + 1)
            val name = scriptPath.substring(schnitt + 1)
            return ordner + TEMP_PRAEFIX + Integer.toHexString(name.hashCode()) + "." + tag
        }

        /** Anfang jeder Temp-Datei eines Skript-Schreibers ([skriptTempPfad]). */
        const val TEMP_PRAEFIX = ".pn_neu_"

        /**
         * 0.1.38.1: Temp-Dateien abgebrochener Schreiber im Ordner von [scriptPath] löschen, nur
         * ältere als 5 min, auch die alten Namen `start_*.sh.neu.*`. toybox find kann -mmin/-exec.
         */
        @JvmStatic
        fun tempAufraeumenCommand(scriptPath: String): String {
            val schnitt = scriptPath.lastIndexOf('/')
            val ordner = if (schnitt > 0) scriptPath.substring(0, schnitt) else "."
            return "find $ordner -maxdepth 1 \\( -name '$TEMP_PRAEFIX*' -o -name 'start_*.sh.neu.*' \\) " +
                "-mmin +5 -exec rm -f {} \\; 2>/dev/null; "
        }

        /**
         * 0.1.36.1: Fertige Temp-Datei prüfen und atomar einsetzen. Erst wenn `sh -n` ok ist
         * und die Zeilenzahl stimmt, ersetzt `mv` das Ziel. Sonst bleibt das alte Skript
         * unverändert, die Temp-Datei geht weg und der Befehl endet mit Exit 3 und der
         * Meldung `SKRIPT_FEHLER` (Aufrufer melden das als Fehler, Start unterbleibt).
         * Muss der letzte Teil des Befehls sein.
         */
        @JvmStatic
        fun buildSkriptEinsetzenCommand(
            tmpPath: String, scriptPath: String, erwarteteZeilen: Int, modus: String
        ): String =
            "SK_N=\$(wc -l < $tmpPath 2>/dev/null | tr -d ' \\t\\r'); " +
            "SK_F=\$(sh -n $tmpPath 2>&1); SK_RC=\$?; " +
            "if [ \"\$SK_RC\" = \"0\" ] && [ \"\$SK_N\" = \"$erwarteteZeilen\" ]; then " +
            "chmod $modus $tmpPath && mv -f $tmpPath $scriptPath && echo \"SKRIPT_OK $scriptPath\"; " +
            "else rm -f $tmpPath 2>/dev/null; " +
            "echo \"SKRIPT_FEHLER $scriptPath rc=\$SK_RC zeilen=\$SK_N/$erwarteteZeilen \$SK_F\"; " +
            "echo \"SKRIPT_FEHLER $scriptPath rc=\$SK_RC zeilen=\$SK_N/$erwarteteZeilen\" >&2; exit 3; fi"

        /**
         * Eine Skriptzeile byte-gleich schreiben (`printf '%s\n'`, nie `echo`).
         * Auch AccSentryDaemon schreibt start_telegram.sh so, Zeile für Zeile (0.1.36),
         * seit 0.1.36.1 nur noch in eine eigene Temp-Datei ([skriptTempPfad]).
         */
        @JvmStatic
        fun buildScriptLineWriteCommand(line: String, first: Boolean, scriptPath: String): String {
            val escapedLine = line
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\$", "\\$")
                .replace("`", "\\`")
            // 0.1.38.1: die erste Zeile legt die Datei mit umask 077 an (nie mehr 666 wie am Auto
            // 04.11.). In einer Unter-Shell, damit die umask nicht an später gestartete Watchdogs
            // und Daemons vererbt wird (deren Logs und Aufnahmen muss die App lesen können).
            return if (first) "(umask 077; printf '%s\\n' \"$escapedLine\" > $scriptPath)"
            else "printf '%s\\n' \"$escapedLine\" >> $scriptPath"
        }

        /**
         * Process-safe ownership for a detached shell watchdog.
         *
         * `mkdir` is the atomic acquisition primitive. Android's shell SELinux
         * policy can reject hard-link creation in `/data/user_de/0/com.android.shell/surfmate` even when
         * both paths are shell-owned, so `ln` cannot be used here. Directory
         * creation is supported on the affected DiLink generation and two
         * simultaneous launchers still cannot both win.
         *
         * A pre-existing owner is recognized only when its PID is numeric
         * and `/proc/<pid>/cmdline` still names this exact watchdog script.
         * An invalid lock is deliberately not stolen here: deleting a lock
         * after a stale read can race a newer owner. Relaunch/deployment
         * cleanup removes stale lock files only after killing old wrappers.
         *
         * Cleanup is owner-only. The EXIT trap compares the owner and legacy
         * PID files with `$$` before removing the lock directory.
         */
        private fun watchdogOwnershipLines(
            scriptPath: String,
            pidFile: String,
            lockPath: String,
            processName: String
        ): List<String> = listOf(
            "WATCHDOG_SCRIPT=\"$scriptPath\"",
            "WATCHDOG_PID_FILE=\"$pidFile\"",
            "WATCHDOG_LOCK_DIR=\"$lockPath\"",
            "WATCHDOG_LOCK_OWNER=\"\$WATCHDOG_LOCK_DIR/pid\"",
            "WATCHDOG_LOCK_OWNED=0",
            "",
            "watchdog_pid_matches() {",
            "  CANDIDATE_PID=\"\$1\"",
            "  [ -n \"\$CANDIDATE_PID\" ] || return 1",
            "  case \"\$CANDIDATE_PID\" in *[!0-9]*) return 1 ;; esac",
            "  [ -r \"/proc/\$CANDIDATE_PID/cmdline\" ] || return 1",
            "  tr '\\000' ' ' < \"/proc/\$CANDIDATE_PID/cmdline\" 2>/dev/null | grep -F \"\$WATCHDOG_SCRIPT\" >/dev/null 2>&1",
            "}",
            "",
            "acquire_watchdog_lock() {",
            "  if mkdir \"\$WATCHDOG_LOCK_DIR\" 2>/dev/null; then",
            "    if ! echo \$\$ > \"\$WATCHDOG_LOCK_OWNER\" 2>/dev/null; then",
            "      rmdir \"\$WATCHDOG_LOCK_DIR\" 2>/dev/null",
            "      return 1",
            "    fi",
            "    if ! echo \$\$ > \"\$WATCHDOG_PID_FILE\" 2>/dev/null; then",
            "      rm -f \"\$WATCHDOG_LOCK_OWNER\" 2>/dev/null",
            "      rmdir \"\$WATCHDOG_LOCK_DIR\" 2>/dev/null",
            "      return 1",
            "    fi",
            "    WATCHDOG_LOCK_OWNED=1",
            "    return 0",
            "  fi",
            "  OLD_WPID=\$(cat \"\$WATCHDOG_LOCK_OWNER\" 2>/dev/null)",
            "  if watchdog_pid_matches \"\$OLD_WPID\"; then",
            "    return 1",
            "  fi",
            "  # Stale lock: the owner PID is dead or not this watchdog (typically a",
            "  # lock dir left on persistent storage by a previous boot, which no",
            "  # launcher cleanup runs to clear after a cold start). Steal it: drop",
            "  # the stale lock and re-acquire so the watchdog self-heals instead of",
            "  # looping forever on a lock nobody owns.",
            "  rm -f \"\$WATCHDOG_LOCK_OWNER\" 2>/dev/null",
            "  rm -f \"\$WATCHDOG_PID_FILE\" 2>/dev/null",
            "  rmdir \"\$WATCHDOG_LOCK_DIR\" 2>/dev/null",
            "  if mkdir \"\$WATCHDOG_LOCK_DIR\" 2>/dev/null; then",
            "    if ! echo \$\$ > \"\$WATCHDOG_LOCK_OWNER\" 2>/dev/null; then",
            "      rmdir \"\$WATCHDOG_LOCK_DIR\" 2>/dev/null",
            "      return 1",
            "    fi",
            "    if ! echo \$\$ > \"\$WATCHDOG_PID_FILE\" 2>/dev/null; then",
            "      rm -f \"\$WATCHDOG_LOCK_OWNER\" 2>/dev/null",
            "      rmdir \"\$WATCHDOG_LOCK_DIR\" 2>/dev/null",
            "      return 1",
            "    fi",
            "    WATCHDOG_LOCK_OWNED=1",
            "    return 0",
            "  fi",
            "  # Another watchdog won the race to re-acquire; stand down.",
            "  return 1",
            "}",
            "",
            "cleanup_watchdog_lock() {",
            "  [ \"\$WATCHDOG_LOCK_OWNED\" = \"1\" ] || return",
            "  CURRENT_WPID=\$(cat \"\$WATCHDOG_LOCK_OWNER\" 2>/dev/null)",
            "  if [ \"\$CURRENT_WPID\" = \"\$\$\" ]; then",
            "    rm -f \"\$WATCHDOG_LOCK_OWNER\" 2>/dev/null",
            "    rmdir \"\$WATCHDOG_LOCK_DIR\" 2>/dev/null",
            "  fi",
            "  LEGACY_WPID=\$(cat \"\$WATCHDOG_PID_FILE\" 2>/dev/null)",
            "  if [ \"\$LEGACY_WPID\" = \"\$\$\" ]; then",
            "    rm -f \"\$WATCHDOG_PID_FILE\" 2>/dev/null",
            "  fi",
            "  WATCHDOG_LOCK_OWNED=0",
            "}",
            "",
            "trap 'cleanup_watchdog_lock' EXIT",
            "trap 'exit 0' HUP INT TERM",
            "if ! acquire_watchdog_lock; then",
            "  exit 0",
            "fi",
            *anhaengenLines(processName).toTypedArray()
        )

        /** Erkennungszeichen eines Skripts mit Anhänge-Modus (Selbstheilung prüft darauf, siehe [buildSelbstheilSkript]). */
        const val ANHAENGEN_MARKE = "# pn-anhaengen-v1"

        /**
         * 0.1.38.1 Anhänge-Modus (`sh <skript> anhaengen <pid>`): Läuft der Daemon schon, aber sein Watchdog
         * fehlt (z. B. weil das Skript beim Update-Start nicht geschrieben werden konnte), übernimmt ein neuer
         * Watchdog den laufenden Prozess, statt einen zweiten zu starten. Er wartet, bis `<pid>` endet, und geht
         * erst dann in seine normale Schleife (Sentinel und Park-Marker wie dort). Gehört `<pid>` nicht (mehr)
         * zum Daemon und läuft ein anderer Daemon gleichen Namens, endet er ohne Start: nie ein zweiter Daemon.
         * Erkannt wird der Daemon am argv[0] (`--nice-name`), exakt, nie per Teilstring.
         */
        private fun anhaengenLines(processName: String): List<String> = listOf(
            ANHAENGEN_MARKE,
            "pn_argv0() { tr '\\000' '\\n' 2>/dev/null < \"/proc/\$1/cmdline\" | head -n 1; }",
            "pn_daemon_laeuft() {",
            "  for PN_D in /proc/[0-9]*; do",
            "    [ \"\$(pn_argv0 \"\${PN_D#/proc/}\")\" = \"$processName\" ] && return 0",
            "  done",
            "  return 1",
            "}",
            "if [ \"\$1\" = \"anhaengen\" ]; then",
            "  PN_PID=\"\$2\"",
            "  case \"\$PN_PID\" in ''|*[!0-9]*) PN_PID=0 ;; esac",
            "  if [ \"\$PN_PID\" != \"0\" ] && [ \"\$(pn_argv0 \"\$PN_PID\")\" = \"$processName\" ]; then",
            "    echo \"[\$(date)] Watchdog angehaengt an laufenden Daemon (pid \$PN_PID)\" >> \"\$LOG_FILE\"",
            "    while kill -0 \"\$PN_PID\" 2>/dev/null; do sleep 5; done",
            "    echo \"[\$(date)] Angehaengter Daemon (pid \$PN_PID) beendet\" >> \"\$LOG_FILE\"",
            "  fi",
            "  if pn_daemon_laeuft; then",
            "    echo \"[\$(date)] Anhaengen: ein anderer Daemon laeuft schon, kein zweiter Start\" >> \"\$LOG_FILE\"",
            "    exit 0",
            "  fi",
            "fi"
        )

        /** Ein Watchdog, den Panoptes selbst prüft und heilt (0.1.38.1, PFLICHT Projektinhaber 04.11.). */
        private class WatchdogArt(
            val skript: String, val pidDatei: String, val sperre: String, val prozess: String,
            val sentinel: String, val parkSperrt: Boolean
        )

        private fun watchdogArt(art: String): WatchdogArt {
            val dir = "/data/user_de/0/com.android.shell/surfmate/"
            fun r(p: String) = at.greenchili.surfmate.util.DaemonStorage.rebase(p)
            return when (art) {
                ART_KAMERA -> WatchdogArt(CAMERA_WATCHDOG_SCRIPT, CAMERA_WATCHDOG_PID_FILE, CAMERA_WATCHDOG_LOCK_PATH,
                    CAMERA_DAEMON_PROCESS, r(dir + "camera_daemon.disabled"), true)
                // Der Wächter läuft auch geparkt (ACC-Richter), der Park-Marker sperrt ihn nicht.
                ART_WAECHTER -> WatchdogArt(ACC_SENTRY_WATCHDOG_SCRIPT, ACC_SENTRY_WATCHDOG_PID_FILE,
                    ACC_SENTRY_WATCHDOG_LOCK_PATH, ACC_SENTRY_DAEMON_PROCESS, r(dir + "acc_sentry_daemon.disabled"), false)
                ART_TELEGRAM -> WatchdogArt(TELEGRAM_WATCHDOG_SCRIPT, TELEGRAM_WATCHDOG_PID_FILE,
                    TELEGRAM_WATCHDOG_LOCK_PATH, TELEGRAM_DAEMON_PROCESS, r(dir + "telegram_bot_daemon.disabled"), true)
                else -> throw IllegalArgumentException("unbekannte Watchdog-Art: $art")
            }
        }

        const val ART_KAMERA = "kamera"
        const val ART_WAECHTER = "waechter"
        const val ART_TELEGRAM = "telegram"

        @JvmStatic fun watchdogSkriptPfad(art: String): String = watchdogArt(art).skript
        @JvmStatic fun watchdogPidDatei(art: String): String = watchdogArt(art).pidDatei
        @JvmStatic fun watchdogSperrPfad(art: String): String = watchdogArt(art).sperre
        @JvmStatic fun watchdogDaemonProzess(art: String): String = watchdogArt(art).prozess
        @JvmStatic fun watchdogSentinel(art: String): String = watchdogArt(art).sentinel
        @JvmStatic fun watchdogParkSperrt(art: String): Boolean = watchdogArt(art).parkSperrt

        /**
         * 0.1.38.1 Selbstprüfung und Selbstheilung eines Watchdogs (Shell-Skript, über eine Skriptdatei
         * ausführen, nie als `sh -c`: es enthält Daemon-Muster). Gibt genau eine Zeile `PNH <befund>` aus:
         * - `gruen`: genau ein Daemon, Skript da, `sh -n` ok, mit Anhänge-Modus, Watchdog lebt.
         * - `gesperrt` (Sentinel bzw. Park-Marker), `kein_daemon`, `mehrere <n>`, `fremd <pid>`: nichts getan.
         * - `skript_neu`: Watchdog lebt, Skript fehlte bzw. war kaputt und ist neu geschrieben.
         * - `angehaengt <pid>`: Skript (falls nötig) neu, Watchdog im Anhänge-Modus an den laufenden Daemon.
         * Schlägt das Schreiben fehl: `SKRIPT_FEHLER …`, Exit 3, alter Stand bleibt. Nie ein zweiter Daemon:
         * gestartet wird nur `sh <skript> anhaengen <pid>`, und die Watchdog-Sperre lässt höchstens einen zu.
         * [nurPid] > 0: nur heilen, wenn genau dieser Prozess der Daemon ist (ein Daemon heilt sich selbst).
         */
        /** Befund aus der Ausgabe von [buildSelbstheilSkript] (`gruen`, `angehaengt`, …), `skript_fehler` oder null. */
        @JvmStatic
        fun selbstheilBefund(ausgabe: String?): String? {
            if (ausgabe == null) return null
            if (ausgabe.contains("SKRIPT_FEHLER")) return "skript_fehler"
            val z = ausgabe.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("PNH ") } ?: return null
            return z.removePrefix("PNH ").substringBefore(' ')
        }

        /** Befund, nach dem nichts weiter zu tun ist: alles gut, geheilt oder bewusst gestoppt. */
        @JvmStatic
        fun selbstheilungOk(befund: String?): Boolean =
            befund == "gruen" || befund == "skript_neu" || befund == "angehaengt" || befund == "gesperrt"

        @JvmStatic
        @JvmOverloads
        fun buildSelbstheilSkript(art: String, scriptLines: List<String>, nurPid: Int = 0): String {
            val a = watchdogArt(art)
            val park = at.greenchili.surfmate.util.DaemonStorage.rebase(
                "/data/user_de/0/com.android.shell/surfmate/surfmate_parked_shutdown")
            return buildString {
                append("PN_S=\"${a.skript}\"\n")
                append("pn_argv0() { tr '\\000' '\\n' 2>/dev/null < \"/proc/\$1/cmdline\" | head -n 1; }\n")
                append("pn_wd() { case \"\$1\" in ''|*[!0-9]*) return 1 ;; esac; ")
                append("tr '\\000' ' ' 2>/dev/null < \"/proc/\$1/cmdline\" | grep -qF \"\$PN_S\"; }\n")
                append("if [ -f \"${a.sentinel}\" ]; then echo \"PNH gesperrt\"; exit 0; fi\n")
                if (a.parkSperrt) append("if [ -f \"$park\" ]; then echo \"PNH gesperrt\"; exit 0; fi\n")
                // toybox pidof erkennt den --nice-name; ohne pidof (Test-Shell) argv[0] exakt über /proc.
                append("if command -v pidof >/dev/null 2>&1; then PN_DPS=\$(pidof ${a.prozess}); else PN_DPS=\"\"; ")
                append("for PN_D in /proc/[0-9]*; do PN_I=\${PN_D#/proc/}; ")
                append("[ \"\$(pn_argv0 \$PN_I)\" = \"${a.prozess}\" ] && PN_DPS=\"\$PN_DPS \$PN_I\"; done; fi\n")
                append("set -- \$PN_DPS\n")
                append("if [ \$# -eq 0 ]; then echo \"PNH kein_daemon\"; exit 0; fi\n")
                append("if [ \$# -gt 1 ]; then echo \"PNH mehrere \$#\"; exit 0; fi\n")
                append("PN_DP=\$1\n")
                if (nurPid > 0) append("if [ \"\$PN_DP\" != \"$nurPid\" ]; then echo \"PNH fremd \$PN_DP\"; exit 0; fi\n")
                append("PN_SK=neu\n")
                append("if [ -f \"\$PN_S\" ] && sh -n \"\$PN_S\" 2>/dev/null && grep -qF '$ANHAENGEN_MARKE' \"\$PN_S\"; then PN_SK=ok; fi\n")
                append("PN_WD=0\n")
                append("if pn_wd \"\$(cat \"${a.pidDatei}\" 2>/dev/null)\" || pn_wd \"\$(cat \"${a.sperre}/pid\" 2>/dev/null)\"; then PN_WD=1; fi\n")
                append("if [ \$PN_SK = ok ] && [ \$PN_WD = 1 ]; then echo \"PNH gruen\"; exit 0; fi\n")
                append("if [ \$PN_SK = neu ]; then ")
                append(buildWatchdogScriptWriteCommand(a.skript, scriptLines))
                append("; fi\n")
                append("if [ \$PN_WD = 1 ]; then echo \"PNH skript_neu\"; exit 0; fi\n")
                append("nohup sh \"\$PN_S\" anhaengen \"\$PN_DP\" > /dev/null 2>&1 &\n")
                append("echo \"PNH angehaengt \$PN_DP\"\n")
            }
        }

        /**
         * Build the start_acc_sentry.sh watchdog script body. Static so the
         * Telegram bot daemon can emit the SAME watchdog the UI uses. The
         * acc-sentry watchdog is intentionally UNCAPPED (no MAX_RETRIES, no
         * backoff) — see [[feedback_acc_sentry_uncapped_immortal]].
         */
        fun buildAccSentryWatchdogScript(apkPath: String, proxyArgs: String): List<String> {
            val lockFile = at.greenchili.surfmate.util.DaemonStorage.rebase("/data/user_de/0/com.android.shell/surfmate/acc_sentry_daemon.lock")
            return listOf(
                "#!/system/bin/sh",
                "# AccSentryDaemon Watchdog Script",
                "APK_PATH=\"$apkPath\"",
                "CLS=\"at.greenchili.surfmate.daemon.AccSentryDaemon\"",
                "PROCESS_NAME=\"$ACC_SENTRY_DAEMON_PROCESS\"",
                "LOG_FILE=\"$ACC_SENTRY_DAEMON_LOG\"",
                "LOCK_FILE=\"$lockFile\"",
                "SENTINEL=\"/data/user_de/0/com.android.shell/surfmate/acc_sentry_daemon.disabled\"",
                // "Vehicle ON only" parked-shutdown marker (ParkedShutdown.MARKER_PATH).
                // Unlike every other watchdog, THIS one does NOT exit on it: acc_sentry_daemon
                // is the parked ACC judge — the park reaper deliberately spares it, it is the
                // only process with a hardware view of ACC while parked, it relights the panel
                // and erases the marker on the real ACC-on. A judge that dies mid-park and is
                // never respawned leaves the car with a dark centre screen and no recovery
                // except a BYD broadcast or a reboot. So the marker only slows the respawn
                // cadence (see the sleep at the bottom of the loop) so a crash-looping daemon
                // cannot keep a parked head unit busy. Never exists in onAndOff mode.
                "PARKED=\"/data/user_de/0/com.android.shell/surfmate/surfmate_parked_shutdown\"",
                "PARKED_RESPAWNS=0",
                "PROXY_ARGS=\"$proxyArgs\"",
                *logArchivDefinitionLines(ACC_SENTRY_DAEMON_LOG).toTypedArray(),
                "LA_GELAUFEN=0",
                *watchdogOwnershipLines(
                    ACC_SENTRY_WATCHDOG_SCRIPT,
                    ACC_SENTRY_WATCHDOG_PID_FILE,
                    ACC_SENTRY_WATCHDOG_LOCK_PATH,
                    ACC_SENTRY_DAEMON_PROCESS
                ).toTypedArray(),
                "",
                "/system/bin/device_config put activity_manager max_phantom_processes 2147483647 > /dev/null 2>&1",
                "",
                // 0.1.36: keep the previous run (was `>` = overwrite).
                "log_archivieren \"\$LOG_FILE\"",
                "echo \"=== WATCHDOG STARTED ===\" >> \$LOG_FILE",
                "",
                "echo \"[\$(date)] Waiting for system boot to complete...\" >> \$LOG_FILE",
                "BOOT_WAIT=0",
                "while [ \"\$(getprop sys.boot_completed)\" != \"1\" ] && [ \$BOOT_WAIT -lt 120 ]; do",
                "  if [ -f \"\$SENTINEL\" ]; then",
                "    echo \"[\$(date)] Daemon disabled by user during boot-wait. Exiting watchdog.\" >> \"\$LOG_FILE\"",
                "    exit 0",
                "  fi",
                "  sleep 2",
                "  BOOT_WAIT=\$((BOOT_WAIT + 2))",
                "done",
                "echo \"[\$(date)] Boot completed (waited \${BOOT_WAIT}s)\" >> \$LOG_FILE",
                "",
                // 0.1.38.8 Schnellstart: die 5 s Atempause nur kurz nach dem Boot, sonst gleich starten
                "UP=\$(cut -d. -f1 /proc/uptime 2>/dev/null)",
                "if [ -n \"\$UP\" ] && [ \"\$UP\" -ge 120 ] 2>/dev/null; then",
                "  echo \"[\$(date)] Geraet laeuft seit \${UP}s: kein Warten\" >> \$LOG_FILE",
                "else",
                "  sleep 5",
                "fi",
                "",
                "while true; do",
                // Archive the previous run's log before relaunch. Real-time
                // bounding during the daemon's life is done by the poller
                // co-process below (copy into the archive, then truncate).
                *logArchivLoopLines().toTypedArray(),
                "",
                "  if [ -f \"\$SENTINEL\" ]; then",
                "    echo \"[\$(date)] Daemon disabled by user (sentinel file exists). Exiting watchdog.\" >> \"\$LOG_FILE\"",
                "    exit 0",
                "  fi",
                "",
                "  RESOLVED_APK=\$(pm path at.greenchili.surfmate 2>/dev/null | grep '/base.apk\$' | head -n 1 | sed 's/^package://')",
                "  if [ -n \"\$RESOLVED_APK\" ] && [ -f \"\$RESOLVED_APK\" ]; then APK_PATH=\"\$RESOLVED_APK\"; fi",
                "  echo \"[\$(date)] Starting Daemon from \$APK_PATH...\" >> \"\$LOG_FILE\"",
                // Backgrounded so the log poller can supervise it while it runs.
                "  CLASSPATH=\"\$APK_PATH\" app_process \$PROXY_ARGS /system/bin --nice-name=\"\$PROCESS_NAME\" \"\$CLS\" >> \"\$LOG_FILE\" 2>&1 &",
                "  DAEMON_PID=\$!",
                *logRotateCoprocessLines("DAEMON_PID", "ROTATE_PID").toTypedArray(),
                "  wait \$DAEMON_PID",
                "  EXIT_CODE=\$?",
                "  LA_GELAUFEN=1",
                "  kill \$ROTATE_PID 2>/dev/null; wait \$ROTATE_PID 2>/dev/null",
                "  if [ -f \"\$SENTINEL\" ]; then",
                "    echo \"[\$(date)] Daemon disabled by user (sentinel written during shutdown). Exiting watchdog.\" >> \"\$LOG_FILE\"",
                "    exit 0",
                "  fi",
                "",
                "  echo \"[\$(date)] Daemon DIED (Code: \$EXIT_CODE). Respawning...\" >> \"\$LOG_FILE\"",
                // SIGKILL/SIGABRT (137/134) doesn't run shutdown hooks, so
                // the daemon's FileLock-backed lock file is left holding
                // the dead PID. Without the rm here, the next app_process
                // invocation hits acquireSingletonLock's PID-cmdline check
                // and refuses to start (returns false → System.exit(1)),
                // entering an infinite respawn loop because exit 1 isn't
                // a healthy-uptime exit either. Cam/Telegram watchdogs
                // already do this; AccSentry missed it.
                "  if [ \$EXIT_CODE -eq 137 ] || [ \$EXIT_CODE -eq 134 ]; then",
                "    rm -f \"\$LOCK_FILE\" 2>/dev/null",
                "  fi",
                // Parked (marker present): the judge must come back, but a crash loop
                // must not keep a sleeping head unit busy (each start-up probes the
                // hardware and may flash the panel). Slow cadence, then very slow.
                // Both back-offs poll the marker every 10 s so that the moment the
                // park ends (marker erased on ACC-on) the judge is back within 10 s
                // instead of at the end of a long sleep.
                "  if [ -f \"\$PARKED\" ]; then",
                "    PARKED_RESPAWNS=\$((PARKED_RESPAWNS + 1))",
                "    if [ \$PARKED_RESPAWNS -gt 5 ]; then",
                "      echo \"[\$(date)] Parked (marker present) — respawn #\$PARKED_RESPAWNS, backing off 5 min\" >> \"\$LOG_FILE\"",
                "      PARK_WAIT=30",
                "    else",
                "      echo \"[\$(date)] Parked (marker present) — respawn #\$PARKED_RESPAWNS in 30s\" >> \"\$LOG_FILE\"",
                "      PARK_WAIT=3",
                "    fi",
                "    while [ \$PARK_WAIT -gt 0 ] && [ -f \"\$PARKED\" ]; do",
                "      sleep 10",
                "      PARK_WAIT=\$((PARK_WAIT - 1))",
                "    done",
                "  else",
                "    PARKED_RESPAWNS=0",
                "    sleep 2",
                "  fi",
                "done"
            )
        }

        /**
         * Build the start_telegram.sh watchdog script body. Same retry
         * policy as cam_daemon (no cap, healthy-uptime reset, monotonic
         * uptime, 60s backoff cap). The Telegram bot daemon was previously
         * spawned bare via `nohup sh -c '<inner>' > log &`; if it crashed,
         * only the in-process 30s health-check covered it — and only when
         * MainActivity was alive. Worst-case downtime was ~12 minutes
         * (ProcessRevivalReceiver alarm → revive → next health-check tick).
         * With this watchdog, recovery is seconds.
         */
        fun buildTelegramWatchdogScript(apkPath: String, proxyArgs: String): List<String> {
            // Backgrounded (trailing &) so the log poller can supervise it
            // while it runs; $! is captured into DAEMON_PID below.
            val appProcessLine =
                "  CLASSPATH=$apkPath app_process " +
                "${proxyArgs}/system/bin " +
                "--nice-name=$TELEGRAM_DAEMON_PROCESS " +
                "at.greenchili.surfmate.daemon.TelegramBotDaemon >> \"\$LOG_FILE\" 2>&1 &"

            return listOf(
                "#!/system/bin/sh",
                "# TelegramBotDaemon Watchdog Script",
                "LOG_FILE=\"$TELEGRAM_DAEMON_LOG\"",
                "LOCK_FILE=\"/data/user_de/0/com.android.shell/surfmate/telegram_bot_daemon.lock\"",
                "SENTINEL=\"/data/user_de/0/com.android.shell/surfmate/telegram_bot_daemon.disabled\"",
                "PARKED=\"/data/user_de/0/com.android.shell/surfmate/surfmate_parked_shutdown\"",
                "RETRY_COUNT=0",
                "HEALTHY_UPTIME_SEC=300",
                *logArchivDefinitionLines(TELEGRAM_DAEMON_LOG).toTypedArray(),
                "LA_GELAUFEN=0",
                *watchdogOwnershipLines(
                    TELEGRAM_WATCHDOG_SCRIPT,
                    TELEGRAM_WATCHDOG_PID_FILE,
                    TELEGRAM_WATCHDOG_LOCK_PATH,
                    TELEGRAM_DAEMON_PROCESS
                ).toTypedArray(),
                "",
                // 0.1.36: the previous watchdog's log (restart, reboot, update)
                // goes into the archive (LogArchiv), never overwritten.
                "log_archivieren \"\$LOG_FILE\"",
                "while true; do",
                // Archive the previous run's log before relaunch;
                // real-time bounding is the poller co-process below.
                *logArchivLoopLines().toTypedArray(),
                "  if [ -f \"\$SENTINEL\" ] || [ -f \"\$PARKED\" ]; then",
                "    echo \"[\$(date)] Daemon disabled by user (sentinel file exists). Exiting watchdog.\" >> \"\$LOG_FILE\"",
                "    exit 0",
                "  fi",
                "  echo \"[\$(date)] Starting TelegramBotDaemon...\" >> \"\$LOG_FILE\"",
                "  START_EPOCH=\$(awk '{print int(\$1)}' /proc/uptime 2>/dev/null || date +%s)",
                "",
                appProcessLine,
                "  DAEMON_PID=\$!",
                *logRotateCoprocessLines("DAEMON_PID", "ROTATE_PID").toTypedArray(),
                "  wait \$DAEMON_PID",
                "  EXIT_CODE=\$?",
                "  LA_GELAUFEN=1",
                "  kill \$ROTATE_PID 2>/dev/null; wait \$ROTATE_PID 2>/dev/null",
                "  END_EPOCH=\$(awk '{print int(\$1)}' /proc/uptime 2>/dev/null || date +%s)",
                "  UPTIME_SEC=\$((END_EPOCH - START_EPOCH))",
                "  if [ \$UPTIME_SEC -lt 0 ]; then UPTIME_SEC=0; fi",
                "  if [ -f \"\$SENTINEL\" ] || [ -f \"\$PARKED\" ]; then",
                "    echo \"[\$(date)] Daemon disabled by user (sentinel written during shutdown). Exiting watchdog.\" >> \"\$LOG_FILE\"",
                "    exit 0",
                "  fi",
                "  if [ \$EXIT_CODE -eq 0 ]; then",
                "    echo \"[\$(date)] Daemon exited cleanly (code 0), restarting in 10s...\" >> \"\$LOG_FILE\"",
                "    RETRY_COUNT=0",
                "    sleep 10",
                "  else",
                "    if [ \$UPTIME_SEC -ge \$HEALTHY_UPTIME_SEC ] && [ \$RETRY_COUNT -gt 0 ]; then",
                "      echo \"[\$(date)] Daemon ran healthy for \${UPTIME_SEC}s before exit \$EXIT_CODE — resetting retry counter\" >> \"\$LOG_FILE\"",
                "      RETRY_COUNT=0",
                "    fi",
                "    RETRY_COUNT=\$((RETRY_COUNT + 1))",
                "    DELAY=\$((RETRY_COUNT * 3))",
                "    if [ \$DELAY -gt 60 ]; then DELAY=60; fi",
                "    echo \"[\$(date)] Daemon exited with code \$EXIT_CODE after \${UPTIME_SEC}s (attempt \$RETRY_COUNT), retrying in \${DELAY}s...\" >> \"\$LOG_FILE\"",
                "    if [ \$EXIT_CODE -eq 137 ] || [ \$EXIT_CODE -eq 134 ]; then",
                "      rm -f \"\$LOCK_FILE\" 2>/dev/null",
                "    fi",
                "    sleep \$DELAY",
                "  fi",
                "done"
            )
        }

        /**
         * Build the start_cam_daemon.sh watchdog script body. Static so the
         * Telegram bot daemon — which lives in a separate process and can't
         * easily own a DaemonLauncher instance — can emit the SAME watchdog
         * the UI uses, instead of an old retry-policy variant. See
         * [[feedback_watchdog_no_retry_cap]] for the no-cap rationale.
         */
        fun buildCamDaemonWatchdogScript(
                apkPath: String,
                nativeLibDir: String,
                outputDir: String,
                proxyArgs: String
        ): List<String> {
            // Backgrounded (trailing &) so the log poller can supervise it
            // while it runs; $! is captured into DAEMON_PID below. The cam
            // daemon is the highest-volume stdout logger, so real-time
            // bounding of cam_daemon.log (the UI-shown file) matters most here.
            val appProcessLine =
                "  CLASSPATH=/system/framework/bmmcamera.jar:\$APK_PATH app_process " +
                "-Djava.library.path=\$NATIVE_LIB_DIR:/system/lib64:/vendor/lib64:/product/lib64:/odm/lib64 " +
                "${proxyArgs}/system/bin " +
                "--nice-name=$CAMERA_DAEMON_PROCESS " +
                "at.greenchili.surfmate.daemon.CameraDaemon " +
                "$outputDir $nativeLibDir >> \"\$LOG_FILE\" 2>&1 &"

            return listOf(
                "#!/system/bin/sh",
                "# CameraDaemon Watchdog Script",
                "LOG_FILE=\"$CAMERA_DAEMON_LOG\"",
                "LOCK_FILE=\"/data/user_de/0/com.android.shell/surfmate/camera_daemon.lock\"",
                "SENTINEL=\"/data/user_de/0/com.android.shell/surfmate/camera_daemon.disabled\"",
                "PARKED=\"/data/user_de/0/com.android.shell/surfmate/surfmate_parked_shutdown\"",
                "RETRY_COUNT=0",
                "HEALTHY_UPTIME_SEC=300",
                *logArchivDefinitionLines(CAMERA_DAEMON_LOG).toTypedArray(),
                "LA_GELAUFEN=0",
                *watchdogOwnershipLines(
                    CAMERA_WATCHDOG_SCRIPT,
                    CAMERA_WATCHDOG_PID_FILE,
                    CAMERA_WATCHDOG_LOCK_PATH,
                    CAMERA_DAEMON_PROCESS
                ).toTypedArray(),
                "",
                // 0.1.36: the previous watchdog's log (restart, reboot, update)
                // goes into the archive (LogArchiv), never overwritten.
                "log_archivieren \"\$LOG_FILE\"",
                "while true; do",
                // Archive the previous run's log before relaunch;
                // real-time bounding is the poller co-process below.
                *logArchivLoopLines().toTypedArray(),
                "  if [ -f \"\$SENTINEL\" ] || [ -f \"\$PARKED\" ]; then",
                "    echo \"[\$(date)] Daemon disabled by user (sentinel file exists). Exiting watchdog.\" >> \"\$LOG_FILE\"",
                "    exit 0",
                "  fi",
                // An update replaces base.apk while this detached watchdog keeps
                // running. Resolve the installed package for every launch instead
                // of retaining the path embedded when the script was written.
                "  APK_PATH=\$(pm path at.greenchili.surfmate 2>/dev/null | grep '/base.apk\$' | head -n 1 | sed 's/^package://')",
                "  if [ -z \"\$APK_PATH\" ] || [ ! -f \"\$APK_PATH\" ]; then",
                "    echo \"[\$(date)] Installed SurfMate APK not found, retrying in 10s...\" >> \"\$LOG_FILE\"",
                "    sleep 10",
                "    continue",
                "  fi",
                "  NATIVE_LIB_DIR=\"\${APK_PATH%/base.apk}/lib/arm64\"",
                "  echo \"[\$(date)] Starting CameraDaemon...\" >> \"\$LOG_FILE\"",
                "  START_EPOCH=\$(awk '{print int(\$1)}' /proc/uptime 2>/dev/null || date +%s)",
                "",
                appProcessLine,
                "  DAEMON_PID=\$!",
                *logRotateCoprocessLines("DAEMON_PID", "ROTATE_PID").toTypedArray(),
                "  wait \$DAEMON_PID",
                "  EXIT_CODE=\$?",
                "  LA_GELAUFEN=1",
                "  kill \$ROTATE_PID 2>/dev/null; wait \$ROTATE_PID 2>/dev/null",
                "  END_EPOCH=\$(awk '{print int(\$1)}' /proc/uptime 2>/dev/null || date +%s)",
                "  UPTIME_SEC=\$((END_EPOCH - START_EPOCH))",
                "  if [ \$UPTIME_SEC -lt 0 ]; then UPTIME_SEC=0; fi",
                "  if [ -f \"\$SENTINEL\" ] || [ -f \"\$PARKED\" ]; then",
                "    echo \"[\$(date)] Daemon disabled by user (sentinel written during shutdown). Exiting watchdog.\" >> \"\$LOG_FILE\"",
                "    exit 0",
                "  fi",
                "  if [ \$EXIT_CODE -eq 0 ]; then",
                "    echo \"[\$(date)] Daemon exited cleanly (code 0), restarting in 10s...\" >> \"\$LOG_FILE\"",
                "    RETRY_COUNT=0",
                "    sleep 10",
                "  else",
                "    if [ \$UPTIME_SEC -ge \$HEALTHY_UPTIME_SEC ] && [ \$RETRY_COUNT -gt 0 ]; then",
                "      echo \"[\$(date)] Daemon ran healthy for \${UPTIME_SEC}s before exit \$EXIT_CODE — resetting retry counter\" >> \"\$LOG_FILE\"",
                "      RETRY_COUNT=0",
                "    fi",
                "    RETRY_COUNT=\$((RETRY_COUNT + 1))",
                "    DELAY=\$((RETRY_COUNT * 3))",
                "    if [ \$DELAY -gt 60 ]; then DELAY=60; fi",
                "    echo \"[\$(date)] Daemon exited with code \$EXIT_CODE after \${UPTIME_SEC}s (attempt \$RETRY_COUNT), retrying in \${DELAY}s...\" >> \"\$LOG_FILE\"",
                "    if [ \$EXIT_CODE -eq 137 ] || [ \$EXIT_CODE -eq 134 ]; then",
                "      rm -f \"\$LOCK_FILE\" 2>/dev/null",
                "    fi",
                "    sleep \$DELAY",
                "  fi",
                "done"
            )
        }
    }
    
    interface LaunchCallback {
        fun onLog(message: String)
        fun onLaunched()
        fun onError(error: String)
    }
    
    /**
     * Get JVM proxy arguments from system settings.
     * This allows app_process daemons to honor the Android system's Global Proxy settings.
     */
    private fun getProxyArgs(): String {
        val sb = StringBuilder()
        try {
            // Read Global HTTP Proxy (standard for WiFi/Ethernet)
            val globalProxy = Settings.Global.getString(context.contentResolver, Settings.Global.HTTP_PROXY)
            if (!globalProxy.isNullOrEmpty()) {
                val parts = globalProxy.split(":")
                if (parts.isNotEmpty()) {
                    val host = parts[0]
                    val port = if (parts.size > 1) parts[1] else "8180"
                    
                    // Add HTTP and HTTPS proxy flags
                    sb.append("-Dhttp.proxyHost=$host ")
                    sb.append("-Dhttp.proxyPort=$port ")
                    sb.append("-Dhttps.proxyHost=$host ")
                    sb.append("-Dhttps.proxyPort=$port ")
                    // Essential: Bypass proxy for localhost to avoid loopbacks
                    sb.append("-Dhttp.nonProxyHosts=\"localhost|127.*|[::1]\" ")
                    
                    logManager.debug(TAG, "Proxy args: host=$host, port=$port")
                }
            }
        } catch (e: Exception) {
            logManager.warn(TAG, "Failed to read proxy settings: ${e.message}")
        }
        return sb.toString()
    }
    
    /**
     * Launch the CameraDaemon via ADB shell.
     * The daemon will run independently of this app as shell user (UID 2000).
    */
    fun launchCameraDaemon(outputDir: String, nativeLibDir: String, callback: LaunchCallback) {
        // Prevent concurrent launch attempts (self-healing: a stale guard from a
        // dropped callback expires after LAUNCH_GUARD_TIMEOUT_MS instead of
        // wedging every future launch).
        if (!guardNehmen({ cameraLaunchStartedAt }, { cameraLaunchStartedAt = it })) {
            logManager.info(TAG, "CameraDaemon launch already in progress, skipping")
            callback.onLog("Launch already in progress")
            callback.onLaunched()
            return
        }
        
        logManager.info(TAG, "Launching CameraDaemon...")
        callback.onLog("Launching CameraDaemon...")
        
        // Check if already running using isDaemonRunning (handles zombies properly)
        isDaemonRunning(CAMERA_DAEMON_PROCESS) { isRunning ->
            if (isRunning) {
                logManager.info(TAG, "CameraDaemon already running")
                callback.onLog("CameraDaemon already running")
                // Clear the disable sentinel synchronously (in the rm's own
                // onSuccess callback) BEFORE reporting success. If we fired
                // onLaunched() before the rm landed, a daemon exit racing
                // the rm could let the watchdog gate-2 see the still-present
                // sentinel and exit 0 — silent permanent stop. With the rm
                // gated, the caller sees "running" only once the sentinel
                // is actually gone.
                adbShellExecutor.execute(
                    command = "rm -f /data/user_de/0/com.android.shell/surfmate/camera_daemon.disabled 2>/dev/null; echo done",
                    callback = object : AdbShellExecutor.ShellCallback {
                        override fun onSuccess(o: String) {
                            // 0.1.38.1: läuft der Daemon, fehlt aber Skript oder Watchdog, hier heilen
                            // (anhängen, kein zweiter Daemon). Bis 0.1.38 nahm dieser Kurzweg das nie wahr.
                            kameraWatchdogPruefen(outputDir, nativeLibDir, callback)
                        }
                        override fun onError(e: String) {
                            // rm failure is non-fatal but log it — better to
                            // proceed than wedge the launcher.
                            logManager.warn(TAG, "Sentinel rm failed on already-running short-circuit: $e")
                            callback.onLaunched()
                            cameraLaunchStartedAt = 0L
                        }
                    }
                )
            } else {
                launchCameraDaemonInternal(outputDir, nativeLibDir, object : LaunchCallback {
                    override fun onLog(message: String) = callback.onLog(message)
                    override fun onLaunched() {
                        cameraLaunchStartedAt = 0L
                        callback.onLaunched()
                    }
                    override fun onError(error: String) {
                        cameraLaunchStartedAt = 0L
                        callback.onError(error)
                    }
                })
            }
        }
    }
    
    /**
     * 0.1.38.1 Kurzweg „Daemon läuft schon“: Selbstprüfung ([buildSelbstheilSkript]). Grün, Skript neu
     * geschrieben oder Watchdog angehängt → fertig. Bleibt es rot (z. B. zwei Daemons, Schreiben scheitert),
     * startet die Kamera einmal ganz neu: Das ist der Weg, den die Meldung an den Nutzer verspricht
     * („Panoptes einmal öffnen“), kurzer Ausfall der Kamera inklusive.
     */
    private fun kameraWatchdogPruefen(outputDir: String, nativeLibDir: String, callback: LaunchCallback) {
        val zeilen = buildCamDaemonWatchdogScript(context.applicationInfo.sourceDir, nativeLibDir, outputDir, getProxyArgs())
        adbShellExecutor.executeScript(
            scriptBody = buildSelbstheilSkript(ART_KAMERA, zeilen),
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) = weiter(selbstheilBefund(output))
                override fun onError(error: String) = weiter(selbstheilBefund(error) ?: "fehler")
                private fun weiter(befund: String?) {
                    if (befund != "gruen") LauncherEreignisse.merken("SELBSTHEILUNG", "art=$ART_KAMERA weg=app befund=$befund")
                    if (selbstheilungOk(befund)) {
                        callback.onLaunched()
                        cameraLaunchStartedAt = 0L
                        return
                    }
                    logManager.warn(TAG, "CameraDaemon läuft, Watchdog nicht heilbar ($befund) — Neustart der Kamera")
                    launchCameraDaemonInternal(outputDir, nativeLibDir, object : LaunchCallback {
                        override fun onLog(message: String) = callback.onLog(message)
                        override fun onLaunched() {
                            cameraLaunchStartedAt = 0L
                            callback.onLaunched()
                        }
                        override fun onError(error: String) {
                            cameraLaunchStartedAt = 0L
                            callback.onError(error)
                        }
                    })
                }
            }
        )
    }

    private fun launchCameraDaemonInternal(outputDir: String, nativeLibDir: String, callback: LaunchCallback) {
        val apkPath = context.applicationInfo.sourceDir
        val proxyArgs = getProxyArgs()
        val scriptPath = at.greenchili.surfmate.util.DaemonStorage.rebase("/data/user_de/0/com.android.shell/surfmate/start_cam_daemon.sh")
        
        logManager.debug(TAG, "Deploying CameraDaemon watchdog script...")
        callback.onLog("Deploying watchdog script...")
        
        // Step 1: Kill old processes and clean up.
        // Use script-via-tmpfile so toybox `pkill -f 'cam_daemon'` can't
        // self-match the calling shell's argv. Order: clear sentinel,
        // kill the old owner (the script stays until the checked mv), settle, then
        // remove daemon + watchdog ownership artifacts. Ownership cleanup
        // stays AFTER the kill so no second watchdog can acquire while the
        // old shell is still alive.
        val cleanupScript = buildString {
            append("rm -f /data/user_de/0/com.android.shell/surfmate/camera_daemon.disabled 2>/dev/null\n")
            append(psAwkKillLine("cam_daemon"))
            append("sleep 1\n")
            append("rm -f /data/user_de/0/com.android.shell/surfmate/camera_daemon.lock $CAMERA_WATCHDOG_PID_FILE 2>/dev/null\n")
            append("rm -rf $CAMERA_WATCHDOG_LOCK_PATH 2>/dev/null\n")
            append("echo done\n")
        }

        adbShellExecutor.executeScript(
            scriptBody = cleanupScript,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    callback.onLog("Old processes cleaned up, writing script...")
                    writeCamDaemonScript(apkPath, proxyArgs, outputDir, nativeLibDir, scriptPath, callback)
                }
                
                override fun onError(error: String) {
                    callback.onLog("Writing script...")
                    writeCamDaemonScript(apkPath, proxyArgs, outputDir, nativeLibDir, scriptPath, callback)
                }
            }
        )
    }
    
    private fun writeCamDaemonScript(
        apkPath: String, proxyArgs: String, outputDir: String, nativeLibDir: String,
        scriptPath: String, callback: LaunchCallback
    ) {
        // Single source of truth for the watchdog body lives in the companion
        // (buildCamDaemonWatchdogScript) so the Telegram bot daemon can emit
        // the same script. See [[feedback_watchdog_no_retry_cap]].
        val scriptLines = buildCamDaemonWatchdogScript(apkPath, nativeLibDir, outputDir, proxyArgs)

        val writeCmd = buildWatchdogScriptWriteCommand(scriptPath, scriptLines)
        // 0.1.38.1: über eine Skriptdatei (argv `sh …/.adb_script_<id>.sh`), nicht `sh -c "<Befehl>"`.
        // So trifft kein Kill-Muster den Schreiber; stirbt er doch, bleibt das alte Skript stehen.
        LauncherEreignisse.merken("SKRIPT_SCHREIBEN", "art=$ART_KAMERA")
        // 0.1.38.3 (fix-waechter-skript): bei Abbruch einmal neu schreiben (atomar per mv, also sicher)
        skriptSchreibenMitWiederholung(ART_KAMERA, { cb -> adbShellExecutor.executeScript(scriptBody = writeCmd, callback = cb) },
            object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    LauncherEreignisse.merken("SKRIPT_OK", "art=$ART_KAMERA")
                    logManager.info(TAG, "CameraDaemon script written successfully")
                    callback.onLog("Script ready, launching...")
                    launchCamDaemonScript(scriptPath, callback)
                }

                override fun onError(error: String) {
                    // Rückfall startet den Daemon ohne Watchdog; der Daemon heilt das selbst
                    // (WatchdogSelbstpruefung) und hängt einen Watchdog an.
                    LauncherEreignisse.merken("SKRIPT_FEHLER", "art=$ART_KAMERA ${error.take(120)}")
                    logManager.error(TAG, "Failed to write daemon script: $error")
                    callback.onLog("Script write failed, using fallback...")
                    launchCamDaemonFallback(callback)
                }
            }
        )
    }
    
    private fun launchCamDaemonScript(scriptPath: String, callback: LaunchCallback) {
        val launchCmd = "nohup sh $scriptPath > /dev/null 2>&1 &"
        
        adbShellExecutor.execute(
            command = launchCmd,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "CameraDaemon watchdog launched")
                    callback.onLog("Watchdog active. Verifying daemon...")
                    
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        verifyDaemonRunning(CAMERA_DAEMON_PROCESS, "CameraDaemon", CAMERA_DAEMON_LOG, callback)
                    }, 1500)
                }
                
                override fun onError(error: String) {
                    logManager.error(TAG, "Failed to launch watchdog: $error")
                    callback.onLog("Watchdog launch failed, using fallback...")
                    launchCamDaemonFallback(callback)
                }
            }
        )
    }
    
    /**
     * Fallback: Launch CameraDaemon directly without watchdog (original simple method).
     */
    private fun launchCamDaemonFallback(callback: LaunchCallback) {
        val apkPath = context.applicationInfo.sourceDir
        val proxyArgs = getProxyArgs()
        val outputDir = context.getExternalFilesDir(null)?.absolutePath ?: context.filesDir.absolutePath
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        
        val innerCmd = buildString {
            append("CLASSPATH=\$([ -f /system/framework/bmmcamera.jar ] && echo /system/framework/bmmcamera.jar:)$apkPath ")
            append("app_process ")
            append("-Djava.library.path=$nativeLibDir:/system/lib64:/vendor/lib64:/product/lib64:/odm/lib64 ")
            append(proxyArgs)
            append("/system/bin ")
            append("--nice-name=$CAMERA_DAEMON_PROCESS ")
            append("at.greenchili.surfmate.daemon.CameraDaemon ")
            append("$outputDir ")
            append("$nativeLibDir")
        }
        
        val cmd = nohupMitArchiv(innerCmd, CAMERA_DAEMON_LOG)
        
        adbShellExecutor.execute(
            command = cmd,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "CameraDaemon launched (fallback, no watchdog)")
                    callback.onLog("Launch command sent, verifying...")
                    
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        verifyDaemonRunning(CAMERA_DAEMON_PROCESS, "CameraDaemon", CAMERA_DAEMON_LOG, callback)
                    }, 1500)
                }
                
                override fun onError(error: String) {
                    logManager.error(TAG, "Failed to launch CameraDaemon: $error")
                    callback.onError("Launch failed: $error")
                }
            }
        )
    }
    
    /**
     * Launch the SentryDaemon via ADB shell.
     * Monitors ACC state and manages recording/location services.
     */
    fun launchSentryDaemon(callback: LaunchCallback) {
        logManager.info(TAG, "Launching SentryDaemon...")
        callback.onLog("Launching SentryDaemon...")
        
        // Use word boundary to avoid matching acc_sentry_daemon
        adbShellExecutor.execute(
            command = "ps -A | grep -w $SENTRY_DAEMON_PROCESS | grep -v grep | grep -v acc_",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    if (output.trim().isNotEmpty()) {
                        logManager.info(TAG, "SentryDaemon already running: ${output.trim()}")
                        callback.onLog("SentryDaemon already running")
                        callback.onLaunched()
                        return
                    }
                    launchSentryDaemonInternal(callback)
                }
                
                override fun onError(error: String) {
                    launchSentryDaemonInternal(callback)
                }
            }
        )
    }
    
    private fun launchSentryDaemonInternal(callback: LaunchCallback) {
        callback.onLog("Granting bodywork permissions...")
        grantBodyworkPermissions(callback) {
            val apkPath = context.applicationInfo.sourceDir
            val proxyArgs = getProxyArgs()
            
            val innerCmd = buildString {
                append("CLASSPATH=$apkPath ")
                append("app_process ")
                append(proxyArgs)
                append("/system/bin ")
                append("--nice-name=$SENTRY_DAEMON_PROCESS ")
                append("at.greenchili.surfmate.daemon.SentryDaemon")
            }
            
            logManager.debug(TAG, "SentryDaemon command: $innerCmd")
            callback.onLog("Executing daemon launch command...")
            
            // Try privileged shell first (UID 1000) for better permissions
            if (USE_PRIVILEGED_SHELL_FOR_SENTRY) {
                callback.onLog("Checking privileged shell availability...")
                
                // Use ADB to check if port 1234 is open and running as UID 1000
                adbShellExecutor.execute(
                    command = "echo 'id' | nc localhost 1234 2>/dev/null | head -1",
                    callback = object : AdbShellExecutor.ShellCallback {
                        override fun onSuccess(output: String) {
                            if (output.contains("uid=1000")) {
                                logManager.info(TAG, "Privileged shell available (UID 1000 confirmed)")
                                callback.onLog("Using privileged shell (UID 1000)...")
                                // UID 1000 can write to /data/system, redirect logs there
                                val privCmd = "nohup sh -c '$innerCmd' > $SENTRY_DAEMON_LOG_SYSTEM 2>&1 &"
                                launchSentryDaemonViaPrivilegedShell(privCmd, innerCmd, callback)
                            } else if (output.contains("uid=")) {
                                logManager.warn(TAG, "Shell available but not UID 1000: $output")
                                callback.onLog("Shell not UID 1000, using ADB shell...")
                                val adbCmd = nohupMitArchiv(innerCmd, SENTRY_DAEMON_LOG)
                                launchSentryDaemonViaAdb(adbCmd, callback)
                            } else {
                                logManager.info(TAG, "Privileged shell not available, using ADB shell")
                                callback.onLog("Privileged shell not available, using ADB shell...")
                                val adbCmd = nohupMitArchiv(innerCmd, SENTRY_DAEMON_LOG)
                                launchSentryDaemonViaAdb(adbCmd, callback)
                            }
                        }
                        
                        override fun onError(error: String) {
                            logManager.info(TAG, "Privileged shell check failed: $error, using ADB shell")
                            callback.onLog("Using ADB shell...")
                            val adbCmd = nohupMitArchiv(innerCmd, SENTRY_DAEMON_LOG)
                            launchSentryDaemonViaAdb(adbCmd, callback)
                        }
                    }
                )
            } else {
                val adbCmd = nohupMitArchiv(innerCmd, SENTRY_DAEMON_LOG)
                launchSentryDaemonViaAdb(adbCmd, callback)
            }
        }
    }
    
    /**
     * Launch SentryDaemon via privileged shell (UID 1000).
     * This gives the daemon system-level privileges for better access to BYD services.
     */
    private fun launchSentryDaemonViaPrivilegedShell(cmd: String, innerCmd: String, callback: LaunchCallback) {
        // Escape single quotes for piping through nc
        // Replace ' with '\'' (end quote, escaped quote, start quote)
        val escapedCmd = cmd.replace("'", "'\\''")
        val ncCmd = "echo '$escapedCmd' | nc localhost 1234"
        
        logManager.debug(TAG, "Executing SentryDaemon via privileged shell: $ncCmd")
        
        adbShellExecutor.execute(
            command = ncCmd,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "SentryDaemon launch via privileged shell: $output")
                    callback.onLog("Launch command sent via privileged shell (UID 1000), verifying...")
                    
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        // Check both possible log locations
                        verifySentryDaemonRunning(callback)
                    }, 2000)
                }
                
                override fun onError(error: String) {
                    logManager.error(TAG, "Privileged shell launch failed: $error, falling back to ADB")
                    callback.onLog("Privileged shell failed, using ADB shell...")
                    val adbCmd = nohupMitArchiv(innerCmd, SENTRY_DAEMON_LOG)
                    launchSentryDaemonViaAdb(adbCmd, callback)
                }
            }
        )
    }
    
    /**
     * Launch SentryDaemon via ADB shell (UID 2000).
     * Fallback method when privileged shell is not available.
     */
    private fun launchSentryDaemonViaAdb(cmd: String, callback: LaunchCallback) {
        logManager.debug(TAG, "Executing SentryDaemon via ADB: $cmd")
        
        adbShellExecutor.execute(
            command = cmd,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "SentryDaemon launch command sent via ADB")
                    callback.onLog("Launch command sent via ADB shell (UID 2000), verifying...")
                    
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        verifySentryDaemonRunning(callback)
                    }, 2000)
                }
                
                override fun onError(error: String) {
                    logManager.error(TAG, "Failed to launch SentryDaemon: $error")
                    callback.onError("Launch failed: $error")
                }
            }
        )
    }
    
    /**
     * Verify SentryDaemon is running and report its UID.
     */
    private fun verifySentryDaemonRunning(callback: LaunchCallback) {
        // First check if process is running and get its UID
        // Use grep -v acc_ to exclude acc_sentry_daemon
        adbShellExecutor.execute(
            command = "ps -A -o PID,UID,ARGS | grep -w $SENTRY_DAEMON_PROCESS | grep -v grep | grep -v acc_ | head -1",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    if (output.trim().isNotEmpty()) {
                        // Parse PID and UID from output
                        val parts = output.trim().split(Regex("\\s+"))
                        val pid = parts.getOrNull(0) ?: "?"
                        val uid = parts.getOrNull(1) ?: "?"
                        
                        val uidName = when (uid) {
                            "1000" -> "system"
                            "2000" -> "shell"
                            "0" -> "root"
                            else -> "uid=$uid"
                        }
                        
                        logManager.info(TAG, "SentryDaemon running with PID: $pid, UID: $uid ($uidName)")
                        callback.onLog("SentryDaemon running with PID: $pid as $uidName")
                        callback.onLaunched()
                    } else {
                        // Process not found, check logs
                        checkSentryDaemonLogs(callback)
                    }
                }
                
                override fun onError(error: String) {
                    checkSentryDaemonLogs(callback)
                }
            }
        )
    }
    
    /**
     * Check SentryDaemon logs from both possible locations.
     */
    private fun checkSentryDaemonLogs(callback: LaunchCallback) {
        // Check both log locations:
        // - /data/data/com.android.providers.settings/sentry_daemon.log (UID 1000)
        // - /data/user_de/0/com.android.shell/surfmate/sentry_daemon.log (UID 2000)
        adbShellExecutor.execute(
            command = "cat $SENTRY_DAEMON_LOG_SYSTEM 2>/dev/null | tail -30; cat $SENTRY_DAEMON_LOG 2>/dev/null | tail -30",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(logContent: String) {
                    if (logContent.trim().isNotEmpty()) {
                        logManager.error(TAG, "SentryDaemon failed to start. Log: $logContent")
                        callback.onError("SentryDaemon failed to start. Log:\n$logContent")
                    } else {
                        logManager.error(TAG, "SentryDaemon process not found and no log output")
                        callback.onError("SentryDaemon process not found and no log output")
                    }
                }
                
                override fun onError(error: String) {
                    logManager.error(TAG, "SentryDaemon process not found and couldn't read log: $error")
                    callback.onError("SentryDaemon process not found and no log output")
                }
            }
        )
    }
    
    /**
     * Launch the AccSentryDaemon via ADB shell (UID 2000).
     * 
     * This daemon handles:
     * - ACC state monitoring
     * - Screen control (input keyevent) - MUST run as UID 2000
     * - Surveillance enable/disable
     * 
     * System whitelisting is handled by SentryDaemon (UID 1000) separately.
     */
    fun launchAccSentryDaemon(callback: LaunchCallback) {
        // Prevent concurrent launch attempts (self-healing timestamped guard;
        // see launchCameraDaemon for the dropped-callback rationale).
        if (!guardNehmen({ accSentryLaunchStartedAt }, { accSentryLaunchStartedAt = it })) {
            logManager.info(TAG, "AccSentryDaemon launch already in progress, skipping")
            callback.onLog("Launch already in progress")
            callback.onLaunched()
            return
        }
        
        logManager.info(TAG, "Launching AccSentryDaemon (UID 2000)...")
        callback.onLog("Launching AccSentryDaemon (UID 2000 for screen control)...")
        
        // Check if daemon or watchdog process is running
        adbShellExecutor.execute(
            command = "ps -A | grep -E '($ACC_SENTRY_DAEMON_PROCESS|start_acc_sentry)' | grep -v grep",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    val hasDaemon = output.contains(ACC_SENTRY_DAEMON_PROCESS)
                    val hasWatchdog = output.contains("start_acc_sentry")
                    
                    if (hasDaemon || hasWatchdog) {
                        // Clear the disable sentinel synchronously on the
                        // already-running path: report success only after
                        // the rm has landed, so a daemon exit racing the rm
                        // can't let the watchdog gate-2 see a still-present
                        // sentinel and exit 0 (silent permanent stop).
                        val statusMsg = if (hasDaemon) "AccSentryDaemon already running"
                                         else "Watchdog active, daemon will respawn"
                        if (hasDaemon) {
                            logManager.info(TAG, "AccSentryDaemon already running")
                        } else {
                            logManager.info(TAG, "Watchdog process running - daemon will spawn")
                        }
                        callback.onLog(statusMsg)
                        adbShellExecutor.execute(
                            command = "rm -f /data/user_de/0/com.android.shell/surfmate/acc_sentry_daemon.disabled 2>/dev/null; echo done",
                            callback = object : AdbShellExecutor.ShellCallback {
                                override fun onSuccess(o: String) {
                                    callback.onLaunched()
                                    accSentryLaunchStartedAt = 0L
                                }
                                override fun onError(e: String) {
                                    logManager.warn(TAG, "Sentinel rm failed on already-running short-circuit: $e")
                                    callback.onLaunched()
                                    accSentryLaunchStartedAt = 0L
                                }
                            }
                        )
                        return
                    }
                    
                    launchAccSentryDaemonInternal(callback)
                }
                
                override fun onError(error: String) {
                    launchAccSentryDaemonInternal(callback)
                }
            }
        )
    }
    
    private fun launchAccSentryDaemonInternal(callback: LaunchCallback) {
        val apkPath = context.applicationInfo.sourceDir
        val proxyArgs = getProxyArgs()
        val watchdogScriptPath = at.greenchili.surfmate.util.DaemonStorage.rebase("/data/user_de/0/com.android.shell/surfmate/start_acc_sentry.sh")
        val lockFilePath = at.greenchili.surfmate.util.DaemonStorage.rebase("/data/user_de/0/com.android.shell/surfmate/acc_sentry_daemon.lock")
        
        logManager.debug(TAG, "Deploying Immortal Watchdog Script for AccSentryDaemon...")
        callback.onLog("Deploying watchdog script via ADB (UID 2000)...")
        
        // Use script-via-tmpfile so toybox `pkill -f 'acc_sentry'` can't
        // self-match the calling shell's argv. Order: clear sentinel,
        // kill the old owner (the script stays until the checked mv), settle, then
        // remove daemon + watchdog ownership artifacts.
        val cleanupScript = buildString {
            append("rm -f /data/user_de/0/com.android.shell/surfmate/acc_sentry_daemon.disabled 2>/dev/null\n")
            append(psAwkKillLine("acc_sentry"))
            append("sleep 1\n")
            append("rm -f $lockFilePath $ACC_SENTRY_WATCHDOG_PID_FILE 2>/dev/null\n")
            append("rm -rf $ACC_SENTRY_WATCHDOG_LOCK_PATH 2>/dev/null\n")
            append("echo done\n")
        }

        adbShellExecutor.executeScript(
            scriptBody = cleanupScript,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    callback.onLog("Old processes cleaned up, writing watchdog script...")
                    writeWatchdogScript(apkPath, proxyArgs, watchdogScriptPath, callback)
                }
                
                override fun onError(error: String) {
                    // pkill returns error if no process found - that's OK
                    callback.onLog("Writing watchdog script...")
                    writeWatchdogScript(apkPath, proxyArgs, watchdogScriptPath, callback)
                }
            }
        )
    }
    
    /**
     * Write the watchdog script to /data/user_de/0/com.android.shell/surfmate/ using printf (more reliable than heredoc).
     */
    private fun writeWatchdogScript(apkPath: String, proxyArgs: String, scriptPath: String, callback: LaunchCallback) {
        // Single source of truth for the script body lives in the companion
        // (buildAccSentryWatchdogScript) so the Telegram path can emit the
        // same script. Sentinel-gated, uncapped — see
        // [[feedback_acc_sentry_uncapped_immortal]].
        val scriptLines = buildAccSentryWatchdogScript(apkPath, proxyArgs)
        
        val writeCmd = buildWatchdogScriptWriteCommand(scriptPath, scriptLines)
        // 0.1.38.1: über eine Skriptdatei wie bei der Kamera (writeCamDaemonScript).
        LauncherEreignisse.merken("SKRIPT_SCHREIBEN", "art=$ART_WAECHTER")
        // 0.1.38.3 (fix-waechter-skript): bei Abbruch einmal neu schreiben (atomar per mv, also sicher)
        skriptSchreibenMitWiederholung(ART_WAECHTER, { cb -> adbShellExecutor.executeScript(scriptBody = writeCmd, callback = cb) },
            object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    LauncherEreignisse.merken("SKRIPT_OK", "art=$ART_WAECHTER")
                    logManager.info(TAG, "Watchdog script written successfully")
                    callback.onLog("Watchdog script ready, launching...")
                    launchWatchdogScript(scriptPath, callback)
                }

                override fun onError(error: String) {
                    LauncherEreignisse.merken("SKRIPT_FEHLER", "art=$ART_WAECHTER ${error.take(120)}")
                    logManager.error(TAG, "Failed to write watchdog script: $error")
                    callback.onLog("Watchdog script failed, using fallback...")
                    launchAccSentryDaemonFallback(callback)
                }
            }
        )
    }
    
    /**
     * Launch the watchdog script in background using nohup.
     */
    private fun launchWatchdogScript(scriptPath: String, callback: LaunchCallback) {
        val launchCmd = "nohup sh $scriptPath > /dev/null 2>&1 &"
        
        adbShellExecutor.execute(
            command = launchCmd,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "Watchdog script launched successfully")
                    callback.onLog("Watchdog active. Verifying daemon...")
                    
                    // Watchdog has 5-second delay after boot wait before starting daemon
                    // Wait 8 seconds to ensure daemon has time to start
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        verifyAccSentryDaemonRunning(callback)
                    }, 8000)
                }
                
                override fun onError(error: String) {
                    logManager.error(TAG, "Failed to launch watchdog: $error")
                    callback.onLog("Watchdog launch failed, using fallback...")
                    launchAccSentryDaemonFallback(callback)
                }
            }
        )
    }
    
    /**
     * Fallback: Launch AccSentryDaemon directly without watchdog (original simple method).
     */
    private fun launchAccSentryDaemonFallback(callback: LaunchCallback) {
        val apkPath = context.applicationInfo.sourceDir
        val proxyArgs = getProxyArgs()
        
        val innerCmd = buildString {
            append("CLASSPATH=$apkPath ")
            append("app_process ")
            append(proxyArgs)
            append("/system/bin ")
            append("--nice-name=$ACC_SENTRY_DAEMON_PROCESS ")
            append("at.greenchili.surfmate.daemon.AccSentryDaemon")
        }
        
        val cmd = nohupMitArchiv(innerCmd, ACC_SENTRY_DAEMON_LOG)
        
        logManager.debug(TAG, "AccSentryDaemon fallback command: $cmd")
        callback.onLog("Launching via simple nohup (fallback)...")
        
        adbShellExecutor.execute(
            command = cmd,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "AccSentryDaemon fallback launch sent")
                    callback.onLog("Launch command sent, verifying...")
                    
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        verifyAccSentryDaemonRunning(callback)
                    }, 2000)
                }
                
                override fun onError(error: String) {
                    accSentryLaunchStartedAt = 0L  // Reset flag
                    logManager.error(TAG, "Failed to launch AccSentryDaemon (fallback): $error")
                    callback.onError("Launch failed: $error")
                }
            }
        )
    }
    
    private fun verifyAccSentryDaemonRunning(callback: LaunchCallback) {
        adbShellExecutor.execute(
            command = "ps -A -o PID,UID,ARGS | grep $ACC_SENTRY_DAEMON_PROCESS | grep -v grep | head -1",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    accSentryLaunchStartedAt = 0L  // Reset flag
                    if (output.trim().isNotEmpty()) {
                        val parts = output.trim().split(Regex("\\s+"))
                        val pid = parts.getOrNull(0) ?: "?"
                        val uid = parts.getOrNull(1) ?: "?"
                        
                        val uidName = when (uid) {
                            "2000" -> "shell (correct!)"
                            "1000" -> "system (WRONG - screen control won't work!)"
                            else -> "uid=$uid"
                        }
                        
                        logManager.info(TAG, "AccSentryDaemon running with PID: $pid, UID: $uid ($uidName)")
                        callback.onLog("AccSentryDaemon running with PID: $pid as $uidName")
                        callback.onLaunched()
                    } else {
                        // Check logs
                        adbShellExecutor.execute(
                            command = "cat $ACC_SENTRY_DAEMON_LOG 2>/dev/null | tail -30",
                            callback = object : AdbShellExecutor.ShellCallback {
                                override fun onSuccess(logContent: String) {
                                    if (logContent.trim().isNotEmpty()) {
                                        logManager.error(TAG, "AccSentryDaemon failed. Log: $logContent")
                                        callback.onError("AccSentryDaemon failed:\n$logContent")
                                    } else {
                                        callback.onError("AccSentryDaemon not found and no log")
                                    }
                                }
                                
                                override fun onError(error: String) {
                                    callback.onError("AccSentryDaemon not found")
                                }
                            }
                        )
                    }
                }
                
                override fun onError(error: String) {
                    accSentryLaunchStartedAt = 0L  // Reset flag
                    callback.onError("Failed to verify AccSentryDaemon: $error")
                }
            }
        )
    }
    
    /**
     * Stop the AccSentryDaemon and its watchdog script.
     * ps+awk+kill matches both daemon (--nice-name=acc_sentry_daemon) and
     * watchdog shell (sh /data/user_de/0/com.android.shell/surfmate/start_acc_sentry.sh) in one pass.
     */
    fun stopAccSentryDaemon(callback: LaunchCallback) {
        logManager.info(TAG, "Stopping AccSentryDaemon and watchdog...")
        callback.onLog("Stopping AccSentryDaemon...")

        // Use executeScript (tmpfile) so the calling shell's argv stays
        // free of any pattern. Sentinel + chmod first (defense for any
        // straggler watchdog that the kill misses), watchdog-script rm,
        // ps+awk+kill, settle + lock-rm.
        adbShellExecutor.executeScript(
            scriptBody = "echo \"disabled by ui at \$(date)\" > /data/user_de/0/com.android.shell/surfmate/acc_sentry_daemon.disabled\n" +
                "chmod 666 /data/user_de/0/com.android.shell/surfmate/acc_sentry_daemon.disabled 2>/dev/null\n" +
                "rm -f /data/user_de/0/com.android.shell/surfmate/start_acc_sentry.sh 2>/dev/null\n" +
                psAwkKillLine("acc_sentry") +
                "sleep 1\n" +
                "rm -f /data/user_de/0/com.android.shell/surfmate/acc_sentry_daemon.lock " +
                "$ACC_SENTRY_WATCHDOG_PID_FILE 2>/dev/null\n" +
                "rm -rf $ACC_SENTRY_WATCHDOG_LOCK_PATH 2>/dev/null\n" +
                "echo done\n",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "AccSentryDaemon stopped")
                    callback.onLog("AccSentryDaemon stopped")
                    callback.onLaunched()
                }
                
                override fun onError(error: String) {
                    // pkill returns error if no process found - that's fine
                    logManager.info(TAG, "AccSentryDaemon stopped (or was not running)")
                    callback.onLog("AccSentryDaemon stopped")
                    callback.onLaunched()
                }
            }
        )
    }
    
    // ==================== TELEGRAM BOT DAEMON ====================
    
    /**
     * Launch the Telegram Bot daemon via ADB shell.
     * Handles Telegram bot polling and notifications.
     */
    fun launchTelegramDaemon(callback: LaunchCallback) {
        // Single-flight, same self-healing timestamped guard the camera and
        // acc-sentry launches use. Boot, ACC-off, the 30s health check and a
        // user tap can otherwise each deploy their own watchdog concurrently
        // (see telegramLaunchStartedAt) and produce a respawn loop that
        // re-announces "bot ready" every cycle.
        if (!guardNehmen({ telegramLaunchStartedAt }, { telegramLaunchStartedAt = it })) {
            logManager.info(TAG, "TelegramBotDaemon launch already in progress, skipping")
            callback.onLog("Launch already in progress")
            callback.onLaunched()
            return
        }

        logManager.info(TAG, "Launching TelegramBotDaemon...")
        callback.onLog("Launching TelegramBotDaemon...")

        // Release the guard on every terminal outcome of the async chain.
        val guarded = object : LaunchCallback {
            override fun onLog(message: String) = callback.onLog(message)
            override fun onLaunched() {
                telegramLaunchStartedAt = 0L
                callback.onLaunched()
            }
            override fun onError(error: String) {
                telegramLaunchStartedAt = 0L
                callback.onError(error)
            }
        }

        // Check if already running
        adbShellExecutor.execute(
            command = "ps -A | grep $TELEGRAM_DAEMON_PROCESS | grep -v grep",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    if (output.trim().isNotEmpty()) {
                        logManager.info(TAG, "TelegramBotDaemon already running: ${output.trim()}")
                        callback.onLog("TelegramBotDaemon already running")
                        // Clear the disable sentinel before reporting success —
                        // same handshake the camera path does on its
                        // already-running short-circuit. We are being asked to
                        // start, so a leftover sentinel (e.g. the app-update
                        // sweep's, which is never cleared for optional daemons)
                        // must not survive: the supervising watchdog gate-exits
                        // on it at the daemon's next exit, turning a live daemon
                        // into a permanently-stopped one with no user action.
                        // Gated in the rm's own callback so onLaunched() only
                        // fires once the file is actually gone.
                        adbShellExecutor.execute(
                            command = "rm -f /data/user_de/0/com.android.shell/surfmate/telegram_bot_daemon.disabled "
                                + "2>/dev/null; echo done",
                            callback = object : AdbShellExecutor.ShellCallback {
                                override fun onSuccess(o: String) { guarded.onLaunched() }
                                override fun onError(e: String) {
                                    logManager.warn(TAG,
                                        "Sentinel rm failed on already-running short-circuit: $e")
                                    guarded.onLaunched()
                                }
                            }
                        )
                        return
                    }
                    launchTelegramDaemonInternal(guarded)
                }

                override fun onError(error: String) {
                    launchTelegramDaemonInternal(guarded)
                }
            }
        )
    }
    
    private fun launchTelegramDaemonInternal(callback: LaunchCallback) {
        val apkPath = context.applicationInfo.sourceDir
        val proxyArgs = getProxyArgs()
        val watchdogScriptPath = at.greenchili.surfmate.util.DaemonStorage.rebase("/data/user_de/0/com.android.shell/surfmate/start_telegram.sh")

        // Write output_dir to telegram config so daemon knows where events are stored
        writeOutputDirToTelegramConfig()

        // Step 1: Pre-launch sweep via executeScript (tmpfile path so toybox
        // `pkill -f 'telegram_bot_daemon'` can't self-match the calling
        // shell). Clear the disable sentinel — user is explicitly starting.
        // Lock-rm goes AFTER pkill with a settle to prevent the lockfile
        // resurrection race.
        //
        // CRITICAL: kill prior watchdog shells BEFORE killing the daemon.
        // The watchdog shell's argv (`sh /data/user_de/0/com.android.shell/surfmate/start_telegram.sh`)
        // does NOT contain "telegram_bot_daemon", so killing just the daemon
        // leaves the supervising shell alive — it respawns the daemon within
        // 10 s and the new watchdog we're about to deploy ends up racing
        // the old one. On reboot, multiple boot paths (DaemonStartupManager
        // 15 s timer + AccSentry ACC-off + 60 s periodic health check) each
        // call launchTelegramDaemon and stack watchdogs, producing the
        // restart-loop symptom (each daemon's killOldInstances kills the
        // others' daemons, those watchdogs respawn instantly, repeat).
        val cleanupScript =
            "rm -f /data/user_de/0/com.android.shell/surfmate/telegram_bot_daemon.disabled 2>/dev/null\n" +
            psAwkKillLine("start_telegram.sh") +
            psAwkKillLine("telegram_bot_daemon") +
            "sleep 1\n" +
            "rm -f /data/user_de/0/com.android.shell/surfmate/telegram_bot_daemon.lock 2>/dev/null\n" +
            "echo done\n"

        adbShellExecutor.executeScript(
            scriptBody = cleanupScript,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    callback.onLog("Old processes cleaned up, writing watchdog script...")
                    writeTelegramWatchdogScript(apkPath, proxyArgs, watchdogScriptPath, callback)
                }
                override fun onError(error: String) {
                    callback.onLog("Cleanup error (continuing): $error")
                    writeTelegramWatchdogScript(apkPath, proxyArgs, watchdogScriptPath, callback)
                }
            }
        )
    }

    private fun writeTelegramWatchdogScript(
            apkPath: String, proxyArgs: String, scriptPath: String, callback: LaunchCallback) {
        // Single source of truth for the script body lives in the companion
        // (buildTelegramWatchdogScript). Same retry policy as cam_daemon:
        // sentinel-gated, no retry cap, monotonic uptime via /proc/uptime,
        // healthy-uptime reset at 300s, backoff capped at 60s.
        val scriptLines = buildTelegramWatchdogScript(apkPath, proxyArgs)

        // 0.1.36.1: gleicher Weg wie Kamera/AccSentry (printf in eigene Temp-Datei, sh -n,
        // mv). Vorher echo direkt ins Ziel: mksh machte aus \000 ein NUL, und lief AccSentry
        // gleichzeitig, hängte es seine restlichen Zeilen hinter dieses Skript (Auto 0.1.36:
        // 334 statt 220 Zeilen, sh -n "unexpected 'fi'" in Zeile 221).
        val writeCmd = buildWatchdogScriptWriteCommand(scriptPath, scriptLines)
        // 0.1.38.1: über eine Skriptdatei wie bei der Kamera (writeCamDaemonScript).
        LauncherEreignisse.merken("SKRIPT_SCHREIBEN", "art=$ART_TELEGRAM")
        // 0.1.38.3 (fix-waechter-skript): bei Abbruch einmal neu schreiben (atomar per mv, also sicher)
        skriptSchreibenMitWiederholung(ART_TELEGRAM, { cb -> adbShellExecutor.executeScript(scriptBody = writeCmd, callback = cb) },
            object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    LauncherEreignisse.merken("SKRIPT_OK", "art=$ART_TELEGRAM")
                    logManager.info(TAG, "Telegram watchdog script written")
                    callback.onLog("Watchdog script ready, launching...")
                    launchTelegramWatchdogScript(scriptPath, callback)
                }
                override fun onError(error: String) {
                    LauncherEreignisse.merken("SKRIPT_FEHLER", "art=$ART_TELEGRAM ${error.take(120)}")
                    logManager.error(TAG, "Failed to write Telegram watchdog: $error")
                    callback.onError("Watchdog script write failed: $error")
                }
            }
        )
    }

    private fun launchTelegramWatchdogScript(scriptPath: String, callback: LaunchCallback) {
        val launchCmd = "nohup sh $scriptPath > /dev/null 2>&1 &"

        adbShellExecutor.execute(
            command = launchCmd,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "TelegramBotDaemon watchdog launched")
                    callback.onLog("Watchdog active, verifying daemon...")

                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        verifyTelegramDaemonRunning(callback)
                    }, 2000)
                }
                
                override fun onError(error: String) {
                    logManager.error(TAG, "Failed to launch TelegramBotDaemon: $error")
                    callback.onError("Launch failed: $error")
                }
            }
        )
    }
    
    /**
     * Write output_dir and apk_path to the unified telegram config so the
     * daemon can find event recordings and AccSentryDaemon can launch the
     * telegram daemon with the correct classpath. Cross-UID safe — the
     * unified config file is world-RW.
     */
    private fun writeOutputDirToTelegramConfig() {
        try {
            val outputDir = context.getExternalFilesDir(null)?.absolutePath
                ?: "/sdcard/DCIM/BYDCam"
            val apkPath = context.applicationInfo.sourceDir
            at.greenchili.surfmate.telegram.config.UnifiedTelegramConfig
                .setLaunchPaths(outputDir, apkPath)
            logManager.debug(TAG, "Wrote outputDir/apkPath to unified telegram config")
        } catch (e: Exception) {
            logManager.warn(TAG, "Error writing telegram config: ${e.message}")
        }
    }
    
    private fun verifyTelegramDaemonRunning(callback: LaunchCallback) {
        adbShellExecutor.execute(
            command = "ps -A -o PID,UID,ARGS | grep $TELEGRAM_DAEMON_PROCESS | grep -v grep | head -1",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    if (output.trim().isNotEmpty()) {
                        val parts = output.trim().split(Regex("\\s+"))
                        val pid = parts.getOrNull(0) ?: "?"
                        val uid = parts.getOrNull(1) ?: "?"
                        
                        logManager.info(TAG, "TelegramBotDaemon running with PID: $pid, UID: $uid")
                        callback.onLog("TelegramBotDaemon running with PID: $pid")
                        callback.onLaunched()
                    } else {
                        // Check logs
                        adbShellExecutor.execute(
                            command = "cat $TELEGRAM_DAEMON_LOG 2>/dev/null | tail -30",
                            callback = object : AdbShellExecutor.ShellCallback {
                                override fun onSuccess(logContent: String) {
                                    if (logContent.trim().isNotEmpty()) {
                                        logManager.error(TAG, "TelegramBotDaemon failed. Log: $logContent")
                                        callback.onError("TelegramBotDaemon failed:\n$logContent")
                                    } else {
                                        callback.onError("TelegramBotDaemon not found and no log")
                                    }
                                }
                                
                                override fun onError(error: String) {
                                    callback.onError("TelegramBotDaemon not found")
                                }
                            }
                        )
                    }
                }
                
                override fun onError(error: String) {
                    callback.onError("Failed to verify TelegramBotDaemon: $error")
                }
            }
        )
    }
    
    /**
     * Stop the Telegram Bot daemon.
     */
    fun stopTelegramDaemon(callback: LaunchCallback) {
        logManager.info(TAG, "Stopping TelegramBotDaemon...")
        callback.onLog("Stopping TelegramBotDaemon...")

        // Plant disable sentinel + chmod 666 + rm watchdog script BEFORE
        // pkill, so any orphan watchdog gates out on its next iteration.
        // Lock-rm AFTER pkill with settle to avoid lockfile resurrection.
        // Routed through executeScript (tmpfile) so toybox `pkill -f
        // 'telegram_bot_daemon'` can't self-match the calling shell.
        // Also rm the greeting throttle stamp — when user toggles the
        // daemon back ON, the next start should greet immediately rather
        // than gate on the prior session's stamp. Throttle is for
        // crash-loop spam, not user intent.
        adbShellExecutor.executeScript(
            scriptBody =
                "echo \"disabled by ui at \$(date)\" > /data/user_de/0/com.android.shell/surfmate/telegram_bot_daemon.disabled\n" +
                "chmod 666 /data/user_de/0/com.android.shell/surfmate/telegram_bot_daemon.disabled 2>/dev/null\n" +
                "rm -f /data/user_de/0/com.android.shell/surfmate/start_telegram.sh 2>/dev/null\n" +
                // Kill watchdog shells too. The sentinel-gate on next loop
                // would also stop them, but an explicit kill ensures the
                // daemon doesn't get respawned in the ~5–10 s window
                // between this stop and the watchdog's next iteration.
                psAwkKillLine("start_telegram.sh") +
                psAwkKillLine("telegram_bot_daemon") +
                "sleep 1\n" +
                "rm -f /data/user_de/0/com.android.shell/surfmate/telegram_bot_daemon.lock 2>/dev/null\n" +
                "rm -f /data/user_de/0/com.android.shell/surfmate/.tg_last_greeted 2>/dev/null\n" +
                "echo done\n",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "TelegramBotDaemon stopped")
                    callback.onLog("TelegramBotDaemon stopped")
                    callback.onLaunched()
                }

                override fun onError(error: String) {
                    // pkill returns error if process not found, which is fine
                    callback.onLaunched()
                }
            }
        )
    }
    
    /**
     * Kill a daemon process by name.
     * Detects the UID of the running process and uses the appropriate shell:
     * - UID 1000 processes need to be killed via privileged shell
     * - UID 2000 processes can be killed via ADB shell
     */
    fun killDaemon(processName: String, callback: LaunchCallback) {
        logManager.info(TAG, "Killing daemon: $processName")
        callback.onLog("Checking $processName UID...")
        
        // First, detect the UID of the running process
        adbShellExecutor.execute(
            command = "ps -A -o UID,PID,ARGS | grep '$processName' | grep -v grep | head -1",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    if (output.trim().isEmpty()) {
                        logManager.info(TAG, "$processName not running")
                        callback.onLog("$processName not running")
                        callback.onLaunched()
                        return
                    }
                    
                    // Parse UID from output (format: "UID PID ARGS...")
                    val parts = output.trim().split(Regex("\\s+"))
                    val uid = parts.getOrNull(0)?.toIntOrNull() ?: 2000
                    val pid = parts.getOrNull(1) ?: "?"
                    
                    logManager.info(TAG, "$processName running as UID $uid (PID $pid)")
                    callback.onLog("$processName running as UID $uid (PID $pid)")
                    
                    if (uid == 1000) {
                        // Process running as system - need privileged shell to kill
                        callback.onLog("Using privileged shell to kill UID 1000 process...")
                        killDaemonViaPrivilegedShell(processName, callback)
                    } else {
                        // Process running as shell or other - ADB can kill it
                        callback.onLog("Using ADB shell to kill process...")
                        killDaemonViaAdb(processName, callback)
                    }
                }
                
                override fun onError(error: String) {
                    // Can't detect UID, try both methods
                    logManager.warn(TAG, "Could not detect UID, trying both kill methods")
                    callback.onLog("Could not detect UID, trying both methods...")
                    killDaemonViaBothShells(processName, callback)
                }
            }
        )
    }
    
    /**
     * Kill daemon via privileged shell (for UID 1000 processes).
     */
    private fun killDaemonViaPrivilegedShell(processName: String, callback: LaunchCallback) {
        val killCmd = if (processName == CAMERA_DAEMON_PROCESS) {
            // Hard kill the whole cam_daemon family FIRST — single pkill on the
            // Privileged-shell path runs as UID 1000 via nc localhost:1234.
            // The privileged-shell daemon does its own `sh -c` of whatever
            // we send, so the toybox `pkill -f 'cam_daemon'` self-match
            // priv-shell wraps the received string in its own `sh -c`,
            // so a literal pkill -f pattern would self-match on that
            // side too. Use ps+awk+kill: the priv-shell's argv will
            // contain the variable assignment text but the kill
            // operates on a PID list, so $$ filtering correctly
            // excludes the priv-shell's PID.
            "rm -f /data/user_de/0/com.android.shell/surfmate/start_cam_daemon.sh 2>/dev/null; " +
            psAwkKill("cam_daemon") + "; " +
            "sleep 1; rm -f /data/user_de/0/com.android.shell/surfmate/camera_daemon.lock " +
            "$CAMERA_WATCHDOG_PID_FILE 2>/dev/null; " +
            "rm -rf $CAMERA_WATCHDOG_LOCK_PATH 2>/dev/null"
        } else {
            psAwkKill(processName)
        }
        val escapedCmd = killCmd.replace("'", "'\\''")
        val ncCmd = "echo '$escapedCmd' | nc localhost 1234"

        // Route via executeScript: the ncCmd string contains the daemon
        // pattern literally. If we used a plain `sh -c "<ncCmd>"`, the
        // calling ADB shell would self-match its own pkill before the
        // pipe to nc completes. Tmpfile form keeps the calling shell's
        // argv free of the pattern.
        adbShellExecutor.executeScript(
            scriptBody = "$ncCmd\n",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "$processName killed via privileged shell")
                    callback.onLog("$processName stopped (via privileged shell)")
                    callback.onLaunched()
                }

                override fun onError(error: String) {
                    logManager.warn(TAG, "Privileged shell kill failed: $error, trying ADB")
                    killDaemonViaAdb(processName, callback)
                }
            }
        )
    }
    
    /**
     * Kill daemon via ADB shell (for UID 2000 processes).
     */
    private fun killDaemonViaAdb(processName: String, callback: LaunchCallback) {
        // For cam/acc-sentry/zrok we route through executeScript so toybox
        // `pkill -f '<pattern>'` can't self-match the calling shell's argv
        // (which would otherwise contain the literal pattern). Order:
        //   1. Sentinel + chmod (defense; only on user-initiated kills).
        //   2. rm watchdog script.
        //   3. pkill cascade.
        //   4. settle + rm lock file (post-pkill so the daemon can't
        //      resurrect the lock between rm and kill).
        val isCamOrAccOrZrok =
            processName == ACC_SENTRY_DAEMON_PROCESS ||
            processName == CAMERA_DAEMON_PROCESS ||
            processName == ZROK_PROCESS

        if (!isCamOrAccOrZrok) {
            // Other daemons (sentry_daemon, telegram_bot_daemon,
            // sentry_proxy, cloudflared, sing-box, tailscaled). Was
            // previously a `sh -c "pkill -9 -f '$processName'; killall -9
            // $processName; echo done"`, but the wrapper's argv contained
            // the literal `processName` → toybox pkill -f SIGKILLed the
            // calling shell, dropping `killall` and `echo done`. The
            // callback then only fired via `onError` after the
            // AdbShellExecutor read-side timed out (~5 s per daemon).
            // AppUpdater.stopAllDaemons step-2 iterated over 7 daemons
            // → up to 35 s wasted on every app-process update.
            //
            // Routed through executeScript (tmpfile) → calling shell argv
            // is `sh /tmp/<script>`, no self-match. ps+awk+kill belt-and-
            // braces in case the daemon was launched without --nice-name
            // and only matches `killall` by comm.
            val script = buildString {
                append(psAwkKillLine(processName))
                if (processName !in EIGENE_MUSTER) append("killall -9 $processName 2>/dev/null\n")
                append("echo done\n")
            }
            adbShellExecutor.executeScript(
                scriptBody = script,
                callback = object : AdbShellExecutor.ShellCallback {
                    override fun onSuccess(output: String) {
                        logManager.info(TAG, "$processName stopped via ADB")
                        callback.onLog("$processName stopped"); callback.onLaunched()
                    }
                    override fun onError(error: String) {
                        logManager.info(TAG, "$processName stopped (or was not running)")
                        callback.onLog("$processName stopped"); callback.onLaunched()
                    }
                }
            )
            return
        }

        val killScript = when (processName) {
            ACC_SENTRY_DAEMON_PROCESS ->
                "[ -f /data/user_de/0/com.android.shell/surfmate/acc_sentry_daemon.disabled ] || " +
                "echo \"disabled by killDaemon at \$(date)\" > /data/user_de/0/com.android.shell/surfmate/acc_sentry_daemon.disabled\n" +
                "chmod 666 /data/user_de/0/com.android.shell/surfmate/acc_sentry_daemon.disabled 2>/dev/null\n" +
                "rm -f /data/user_de/0/com.android.shell/surfmate/start_acc_sentry.sh 2>/dev/null\n" +
                psAwkKillLine("acc_sentry") +
                "sleep 1\n" +
                "rm -f /data/user_de/0/com.android.shell/surfmate/acc_sentry_daemon.lock " +
                "$ACC_SENTRY_WATCHDOG_PID_FILE 2>/dev/null\n" +
                "rm -rf $ACC_SENTRY_WATCHDOG_LOCK_PATH 2>/dev/null\n" +
                "echo done\n"
            CAMERA_DAEMON_PROCESS ->
                // No sentinel here — this generic kill path is non-user-initiated
                // (e.g. mutual exclusion). cam_daemon's user stops live in
                // CameraDaemonController which DOES plant the sentinel.
                "rm -f /data/user_de/0/com.android.shell/surfmate/start_cam_daemon.sh 2>/dev/null\n" +
                psAwkKillLine("cam_daemon") +
                "sleep 1\n" +
                "rm -f /data/user_de/0/com.android.shell/surfmate/camera_daemon.lock " +
                "$CAMERA_WATCHDOG_PID_FILE 2>/dev/null\n" +
                "rm -rf $CAMERA_WATCHDOG_LOCK_PATH 2>/dev/null\n" +
                "echo done\n"
            else -> // ZROK_PROCESS
                "[ -f /data/user_de/0/com.android.shell/surfmate/zrok.disabled ] || " +
                "echo \"disabled by killDaemon at \$(date)\" > /data/user_de/0/com.android.shell/surfmate/zrok.disabled\n" +
                "chmod 666 /data/user_de/0/com.android.shell/surfmate/zrok.disabled 2>/dev/null\n" +
                "rm -f /data/user_de/0/com.android.shell/surfmate/start_zrok.sh 2>/dev/null\n" +
                psAwkKillLine("zrok") +
                "killall -9 $processName 2>/dev/null\n" +
                "echo done\n"
        }

        adbShellExecutor.executeScript(
            scriptBody = killScript,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "$processName stopped via ADB script")
                    callback.onLog("$processName stopped")
                    callback.onLaunched()
                }
                override fun onError(error: String) {
                    logManager.info(TAG, "$processName stopped (or was not running): $error")
                    callback.onLog("$processName stopped")
                    callback.onLaunched()
                }
            }
        )
    }
    
    /**
     * Kill daemon via both shells (when UID is unknown).
     */
    private fun killDaemonViaBothShells(processName: String, callback: LaunchCallback) {
        // Privileged-shell path — priv daemon wraps the received string in
        // its own `sh -c`. ps+awk+kill keeps the priv-shell alive (PID
        // exclusion via $$) so the trailing lock-rm runs.
        val privKillCmd = if (processName == CAMERA_DAEMON_PROCESS) {
            "rm -f /data/user_de/0/com.android.shell/surfmate/start_cam_daemon.sh 2>/dev/null; " +
            psAwkKill("cam_daemon") + "; " +
            "sleep 1; rm -f /data/user_de/0/com.android.shell/surfmate/camera_daemon.lock " +
            "$CAMERA_WATCHDOG_PID_FILE 2>/dev/null; " +
            "rm -rf $CAMERA_WATCHDOG_LOCK_PATH 2>/dev/null"
        } else {
            psAwkKill(processName)
        }
        val escapedCmd = privKillCmd.replace("'", "'\\''")
        val ncCmd = "echo '$escapedCmd' | nc localhost 1234 2>/dev/null"

        // ADB path — runs through the outer executeScript at the bottom,
        // so the tmpfile wrapper insulates the calling shell from
        // self-match. Within the script body, ps+awk+kill is still
        // safer (avoids an unnecessary 137 exit on the inner sh that
        // runs the script body).
        val adbKillCmd = if (processName == ACC_SENTRY_DAEMON_PROCESS) {
            "[ -f /data/user_de/0/com.android.shell/surfmate/acc_sentry_daemon.disabled ] || " +
            "echo \"disabled by killDaemon at \$(date)\" > /data/user_de/0/com.android.shell/surfmate/acc_sentry_daemon.disabled; " +
            "chmod 666 /data/user_de/0/com.android.shell/surfmate/acc_sentry_daemon.disabled 2>/dev/null; " +
            "rm -f /data/user_de/0/com.android.shell/surfmate/start_acc_sentry.sh 2>/dev/null; " +
            psAwkKill("acc_sentry") + "; " +
            "sleep 1; " +
            "rm -f /data/user_de/0/com.android.shell/surfmate/acc_sentry_daemon.lock " +
            "$ACC_SENTRY_WATCHDOG_PID_FILE 2>/dev/null; " +
            "rm -rf $ACC_SENTRY_WATCHDOG_LOCK_PATH 2>/dev/null"
        } else if (processName == CAMERA_DAEMON_PROCESS) {
            "rm -f /data/user_de/0/com.android.shell/surfmate/start_cam_daemon.sh 2>/dev/null; " +
            psAwkKill("cam_daemon") + "; " +
            "sleep 1; " +
            "rm -f /data/user_de/0/com.android.shell/surfmate/camera_daemon.lock " +
            "$CAMERA_WATCHDOG_PID_FILE 2>/dev/null; " +
            "rm -rf $CAMERA_WATCHDOG_LOCK_PATH 2>/dev/null"
        } else if (processName == ZROK_PROCESS) {
            "[ -f /data/user_de/0/com.android.shell/surfmate/zrok.disabled ] || " +
            "echo \"disabled by killDaemon at \$(date)\" > /data/user_de/0/com.android.shell/surfmate/zrok.disabled; " +
            "chmod 666 /data/user_de/0/com.android.shell/surfmate/zrok.disabled 2>/dev/null; " +
            "rm -f /data/user_de/0/com.android.shell/surfmate/start_zrok.sh 2>/dev/null; " +
            "MY_PID=\$\$; ps -A -o PID,ARGS | grep -F zrok | grep -v grep " +
            "| awk '{print \$1}' | while read pid; do " +
            "if [ \"\$pid\" != \"\$MY_PID\" ]; then kill -9 \$pid 2>/dev/null; fi; done; " +
            "killall -9 $processName 2>/dev/null"
        } else {
            psAwkKill(processName) +
            (if (processName in EIGENE_MUSTER) "" else "; killall -9 $processName 2>/dev/null")
        }
        
        // Both the nc command (which contains the priv kill payload) and
        // the adbKillCmd contain the daemon pattern literally. Sending them
        // as a `sh -c "ncCmd; adbKillCmd"` payload would let the calling
        // shell self-match its own pkill. Route through executeScript
        // (tmpfile + sh path) so the calling shell's argv is just `sh
        // <tmpPath>` and pkill cannot self-match.
        adbShellExecutor.executeScript(
            scriptBody = "$ncCmd\n$adbKillCmd\necho done\n",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "$processName stopped (tried both shells)")
                    callback.onLog("$processName stopped")
                    callback.onLaunched()
                }

                override fun onError(error: String) {
                    logManager.info(TAG, "$processName stopped (or was not running)")
                    callback.onLog("$processName stopped")
                    callback.onLaunched()
                }
            }
        )
    }
    
    /**
     * ONE `ps -A` snapshot for the whole daemon set, matched in-process.
     *
     * The per-daemon [isDaemonRunning] probe costs a full `/proc` walk and an adb
     * shell session, all serialized on a process-wide lock (historically doubled
     * by AdbShellExecutor's per-command `echo ok` liveness probe, since removed
     * entirely after the vendor-adbd abort incident). Probing N daemons
     * individually therefore costs N adb sessions + N `/proc` walks per tick. `adbd` is a shared SYSTEM service,
     * so that load is stolen from the whole head unit, not just from us.
     *
     * This takes a single snapshot and lets the caller test every daemon against
     * it, cutting the health check to 2 adb sessions + 1 `/proc` walk per tick.
     *
     * `-o S,ARGS` is explicit rather than strictly required. The state column lets
     * [processAliveIn] reject zombies left briefly behind by SIGKILL. Bare `ps -A` on toybox
     * prints the NAME column, which is argv[0] (basename, width 27 — and unbounded
     * when stdout is not a tty), NOT the 15-char kernel `comm` (that is the separate
     * CMD field). Verified on-device: `ps -A -o ...,NAME` printed
     * "acc_sentry_daemon" (17 chars) and "at.greenchili.surfmate" (17 chars) in full, so
     * long names are not truncated and bare `ps -A` would in fact have worked.
     * We pass `-o S,ARGS` anyway so the column set is pinned by us and cannot drift
     * with a vendor's default-field list.
     *
     * @param callback receives the raw `ps -A -o S,ARGS` output, or null if the probe
     *   failed (callers must treat null as "unknown", never as "dead").
     */
    fun snapshotProcessTable(callback: (String?) -> Unit) {
        adbShellExecutor.execute(
            command = "ps -A -o S,ARGS",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) { callback(output) }
                override fun onError(error: String) { callback(null) }
            }
        )
    }

    /**
     * Tests one daemon against a [snapshotProcessTable] (`ps -A -o S,ARGS`) result.
     * Semantics match the old per-daemon `ps -A | grep <name> | grep -v grep`.
     *
     * Deliberately NO watchdog-script exclusion. It looks like the supervising
     * shells (`sh /data/user_de/0/com.android.shell/surfmate/start_cam_daemon.sh`) could false-positive a dead
     * daemon as alive, but no watchdog FILENAME actually contains a full
     * DaemonType.processName: start_cam_daemon.sh lacks the `byd_` of
     * "byd_cam_daemon"; start_acc_sentry.sh lacks `_daemon`; start_telegram.sh is not
     * "telegram_bot_daemon"; start_singbox.sh is not "sing-box" (hyphen). The one
     * that would match — start_zrok.sh vs "zrok" — is moot because ZROK is routed off
     * this snapshot path entirely (see DaemonStartupManager.runHealthCheck, which
     * checks the actual share and watchdog together via ZrokLauncher.isTunnelManaged).
     * A `.sh` filter therefore excludes nothing real while being the only
     * false-NEGATIVE vector here: any daemon whose argv ever contained ".sh" would
     * read DEAD forever and be relaunched every tick.
     *
     * SENTRY needs the bespoke rule the launch path documents (`grep -w` +
     * `grep -v acc_`): "sentry_daemon" is a substring of "acc_sentry_daemon", so
     * without the exclusion a running ACC-Sentry would mask a dead Sentry daemon.
     */
    fun processAliveIn(snapshot: String, processName: String): Boolean {
        return processAliveInSnapshot(snapshot, processName)
    }

    /**
     * Check if a daemon is running.
     * Uses the same state-aware process snapshot as the periodic health check.
     */
    fun isDaemonRunning(processName: String, callback: (Boolean) -> Unit) {
        adbShellExecutor.execute(
            command = "ps -A -o S,ARGS",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    callback(processAliveInSnapshot(output, processName))
                }
                
                override fun onError(error: String) {
                    callback(false)
                }
            }
        )
    }
    
    /**
     * Get process uptime in human-readable format.
     * Returns null if process is not running.
     * 
     * Uses ps with grep to find the actual daemon process,
     * sorting by uptime to get the longest-running match (the actual daemon).
     */
    fun getProcessUptime(processName: String, callback: (String?) -> Unit) {
        // Use ps to find process and get its etime
        // Sort by etime descending to get the longest-running process (the actual daemon, not shell commands)
        adbShellExecutor.execute(
            command = "ps -eo etime,args 2>/dev/null | grep '$processName' | grep -v grep | grep -v pgrep | sort -t: -k1 -rn | head -1 | awk '{print \$1}' | tr -d ' '",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    val etime = output.trim()
                    if (etime.isNotEmpty() && etime.contains(":")) {
                        callback(formatUptime(etime))
                    } else {
                        callback(null)
                    }
                }
                
                override fun onError(error: String) {
                    callback(null)
                }
            }
        )
    }
    
    /**
     * Format elapsed time from ps output (e.g., "01:23:45" or "1-02:03:04") to human readable.
     * Input formats:
     * - MM:SS (e.g., "00:30", "16:49")
     * - HH:MM:SS (e.g., "01:23:45")
     * - DD-HH:MM:SS (e.g., "1-02:03:04")
     */
    /** Short minute mark. English stays glued ("15m"); Hebrew is "דק׳". */
    private fun minuteMark(): String = compactMark(at.greenchili.surfmate.R.string.unit_minute_compact, "m")

    /** Short hour mark. English stays glued ("2h"); Hebrew is "שע׳". */
    private fun hourMark(): String = compactMark(at.greenchili.surfmate.R.string.unit_hour_compact, "h")

    /** Short second mark. English stays glued ("30s"); Hebrew is "שנ׳". */
    private fun secondMark(): String = compactMark(at.greenchili.surfmate.R.string.unit_second_compact, "s")

    private fun compactMark(resId: Int, ascii: String): String {
        val unit = context.getString(resId)
        return if (unit == ascii) ascii else " $unit"
    }

    private fun formatUptime(etime: String): String {
        return try {
            // Check if it contains a day separator
            if (etime.contains("-")) {
                // DD-HH:MM:SS format
                val dayParts = etime.split("-")
                val days = dayParts[0].toInt()
                val timeParts = dayParts[1].split(":")
                val hours = timeParts[0].toInt()
                val mins = timeParts[1].toInt()
                
                return when {
                    days > 0 -> "${days}d ${hours}${hourMark()}"
                    hours > 0 -> "${hours}${hourMark()} ${mins}${minuteMark()}"
                    else -> "${mins}${minuteMark()}"
                }
            }
            
            // Time only format
            val parts = etime.split(":")
            when (parts.size) {
                2 -> { // MM:SS
                    val mins = parts[0].toInt()
                    val secs = parts[1].toInt()
                    when {
                        mins > 0 -> "${mins}${minuteMark()} ${secs}${secondMark()}"
                        secs > 0 -> "${secs}${secondMark()}"
                        else -> "just started"
                    }
                }
                3 -> { // HH:MM:SS
                    val hours = parts[0].toInt()
                    val mins = parts[1].toInt()
                    val secs = parts[2].toInt()
                    when {
                        hours > 0 -> "${hours}${hourMark()} ${mins}${minuteMark()}"
                        mins > 0 -> "${mins}${minuteMark()} ${secs}${secondMark()}"
                        else -> "${secs}${secondMark()}"
                    }
                }
                else -> etime
            }
        } catch (e: Exception) {
            etime
        }
    }
    
    /**
     * Data class for subprocess info.
     */
    data class ProcessInfo(
        val name: String,
        val pid: Int,
        val uptime: String
    )
    
    /**
     * Get list of subprocesses for a daemon with their PIDs and uptimes.
     */
    fun getSubprocesses(processPatterns: List<String>, callback: (List<ProcessInfo>) -> Unit) {
        if (processPatterns.isEmpty()) {
            callback(emptyList())
            return
        }
        
        // Build grep pattern for all processes
        val grepPattern = processPatterns.joinToString("\\|")
        
        // Get PID, elapsed time, and command for matching processes
        adbShellExecutor.execute(
            command = "ps -eo pid,etime,args 2>/dev/null | grep -E '${processPatterns.joinToString("|")}' | grep -v grep",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    val processes = mutableListOf<ProcessInfo>()
                    output.lines().forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.isNotEmpty()) {
                            // Parse: PID ETIME COMMAND...
                            val parts = trimmed.split(Regex("\\s+"), limit = 3)
                            if (parts.size >= 3) {
                                try {
                                    val pid = parts[0].toInt()
                                    val etime = formatUptime(parts[1])
                                    val cmd = parts[2]
                                    // Extract process name from command
                                    val name = extractProcessName(cmd, processPatterns)
                                    processes.add(ProcessInfo(name, pid, etime))
                                } catch (e: Exception) {
                                    // Skip malformed lines
                                }
                            }
                        }
                    }
                    callback(processes)
                }
                
                override fun onError(error: String) {
                    callback(emptyList())
                }
            }
        )
    }
    
    private fun extractProcessName(cmd: String, patterns: List<String>): String {
        // Try to match against known patterns and return friendly name
        for (pattern in patterns) {
            if (cmd.contains(pattern)) {
                return when {
                    pattern.contains("byd_cam_daemon") -> "Camera Daemon"
                    pattern.contains("sentry_daemon") -> "Sentry Daemon"
                    pattern.contains("sing-box") -> "Sing-box"
                    pattern.contains("cloudflared") -> "Cloudflared"
                    pattern.contains("ffmpeg") -> "FFmpeg"
                    pattern.contains("mediamtx") -> "MediaMTX"
                    else -> pattern.take(20)
                }
            }
        }
        // Fallback: extract binary name from path
        return cmd.split("/").lastOrNull()?.split(" ")?.firstOrNull() ?: cmd.take(20)
    }
    
    // ==================== HELPER METHODS ====================
    
    private fun verifyDaemonRunning(
        processName: String,
        daemonName: String,
        logPath: String,
        callback: LaunchCallback
    ) {
        isDaemonRunning(processName) { running ->
            if (running) {
                logManager.info(TAG, "$daemonName running")
                callback.onLog("$daemonName running")
                callback.onLaunched()
            } else {
                checkDaemonLog(logPath, daemonName, callback)
            }
        }
    }
    
    private fun checkDaemonLog(logPath: String, daemonName: String, callback: LaunchCallback) {
        adbShellExecutor.execute(
            command = "cat $logPath 2>/dev/null | tail -30",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(logContent: String) {
                    if (logContent.trim().isNotEmpty()) {
                        logManager.error(TAG, "$daemonName failed to start. Log: $logContent")
                        callback.onError("$daemonName failed to start. Log:\n$logContent")
                    } else {
                        // Check if log file exists at all
                        adbShellExecutor.execute(
                            command = "ls -la $logPath 2>&1; echo '---'; dmesg | tail -10 2>/dev/null",
                            callback = object : AdbShellExecutor.ShellCallback {
                                override fun onSuccess(debugOutput: String) {
                                    logManager.error(TAG, "$daemonName process not found. Debug: $debugOutput")
                                    callback.onError("$daemonName process not found and no log output.\nDebug: $debugOutput")
                                }
                                
                                override fun onError(error: String) {
                                    logManager.error(TAG, "$daemonName process not found and no log output")
                                    callback.onError("$daemonName process not found and no log output")
                                }
                            }
                        )
                    }
                }
                
                override fun onError(error: String) {
                    logManager.error(TAG, "$daemonName process not found and couldn't read log: $error")
                    callback.onError("$daemonName process not found and no log output")
                }
            }
        )
    }
    
    private fun grantBodyworkPermissions(callback: LaunchCallback, onComplete: () -> Unit) {
        val packageName = "at.greenchili.surfmate"
        val permissions = listOf(
            "android.permission.BYDAUTO_BODYWORK_COMMON",
            "android.permission.BYDAUTO_BODYWORK_GET",
            "android.permission.BYDAUTO_BODYWORK_SET"
        )
        
        logManager.debug(TAG, "Granting bodywork permissions...")
        
        val commands = permissions.map { "pm grant $packageName $it 2>/dev/null || true" }
        
        executeCommandSequence(commands, 0) {
            logManager.info(TAG, "Bodywork permissions granted")
            onComplete()
        }
    }
    
    private fun executeCommandSequence(commands: List<String>, index: Int, onComplete: () -> Unit) {
        if (index >= commands.size) {
            onComplete()
            return
        }
        
        adbShellExecutor.execute(
            command = commands[index],
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    executeCommandSequence(commands, index + 1, onComplete)
                }
                
                override fun onError(error: String) {
                    executeCommandSequence(commands, index + 1, onComplete)
                }
            }
        )
    }
}
