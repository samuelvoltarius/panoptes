package at.greenchili.surfmate.launcher

import android.content.Context
import at.greenchili.surfmate.logging.LogManager

/**
 * Facade for launching daemons and services via ADB shell.
 * 
 * This class delegates to specialized launchers:
 * - AdbShellExecutor: ADB connection and shell command execution
 * - DaemonLauncher: Daemon process launching (CameraDaemon, SentryDaemon, etc.)
 * - ServiceLauncher: Android service launching and permission configuration
 * 
 * Maintains backward compatibility with the original AdbDaemonLauncher API.
 */
class AdbDaemonLauncher(private val context: Context) {
    
    companion object {
        private const val TAG = "AdbDaemonLauncher"
    }
    
    // Shared LogManager instance
    private val logManager = LogManager.getInstance()
    
    // Specialized launchers. adbShellExecutor is exposed (not private) so
    // DaemonStartupManager and other callers can pass the SAME executor
    // to ad-hoc launcher classes (TailscaleLauncher, ServiceLauncher) on
    // boot/init paths instead of allocating fresh executors that never get
    // shutdown — historically those were thread-leak hotspots.
    //
    // CONTRACT: external callers may call `.execute()` / `.executeScript()`
    // on this field. They MUST NOT call `.shutdown()` or `.closeConnection()`
    // — both are owned by this AdbDaemonLauncher and routed via
    // closePersistentConnection / releasePerInstanceResources. Calling
    // shutdown() externally torpedoes the parent launcher invisibly:
    // every subsequent execute() throws RejectedExecutionException.
    val adbShellExecutor = AdbShellExecutor(context)
    private val daemonLauncher = DaemonLauncher(context, adbShellExecutor, logManager)
    private val serviceLauncher = ServiceLauncher(context, adbShellExecutor, logManager)
    
    // ==================== CALLBACK INTERFACES ====================
    
    interface LaunchCallback {
        fun onLog(message: String)
        fun onLaunched()
        fun onError(error: String)
    }
    
    // ==================== CONNECTION MANAGEMENT ====================
    
    /**
     * Close the persistent ADB connection AND shut down the executor thread.
     * Call this when the service is destroyed.
     *
     * IMPORTANT: closeConnection() closes the **process-wide shared Dadb**
     * (it lives in `AdbShellExecutor`'s companion). Calling this from a
     * transient AdbDaemonLauncher (e.g. UpdateLifecycle.hardResetDaemons)
     * will close the connection that the long-lived
     * `daemonStartupManager.adbLauncher` is concurrently using, surfacing
     * spurious onError on its in-flight tasks. For transient launchers
     * use {@link #releasePerInstanceResources()} instead.
     */
    fun closePersistentConnection() {
        adbShellExecutor.shutdown()
        adbShellExecutor.closeConnection()
    }

    /**
     * Release resources owned by THIS launcher only — its single-thread
     * AdbShellExecutor — without
     * touching the process-wide shared Dadb connection. Use this on
     * short-lived launchers (e.g. UpdateLifecycle.hardResetDaemons)
     * whose work is one-shot and that don't own the Dadb.
     */
    fun releasePerInstanceResources() {
        adbShellExecutor.shutdown()
    }
    
    // NOTE: the old `checkAdbAvailable` helper (a bare `echo ok` round-trip)
    // was deleted: it had no callers, and its command was byte-identical to
    // the 21-byte OPEN implicated in the vendor adbd abort incident.

    // ==================== DAEMON LAUNCHING ====================
    
    /**
     * Launch the CameraDaemon via ADB loopback.
     */
    fun launchDaemon(outputDir: String, nativeLibDir: String, callback: LaunchCallback) {
        daemonLauncher.launchCameraDaemon(
            outputDir = outputDir,
            nativeLibDir = nativeLibDir,
            callback = callback.toDaemonCallback()
        )
    }
    
    /**
     * Launch the SentryDaemon via ADB shell.
     */
    fun launchSentryDaemon(callback: LaunchCallback) {
        daemonLauncher.launchSentryDaemon(callback.toDaemonCallback())
    }
    
    /**
     * Launch the AccSentryDaemon via ADB shell (UID 2000).
     * This daemon handles ACC monitoring and screen control.
     */
    fun launchAccSentryDaemon(onSuccess: () -> Unit, onError: (String) -> Unit) {
        daemonLauncher.launchAccSentryDaemon(object : DaemonLauncher.LaunchCallback {
            override fun onLog(message: String) {
                logManager.debug(TAG, message)
            }
            override fun onLaunched() {
                onSuccess()
            }
            override fun onError(error: String) {
                onError(error)
            }
        })
    }
    
    /**
     * Launch the TelegramBotDaemon via ADB shell.
     * This daemon handles Telegram bot polling and notifications.
     */
    fun launchTelegramDaemon(callback: LaunchCallback) {
        daemonLauncher.launchTelegramDaemon(callback.toDaemonCallback())
    }
    
    /**
     * Stop the TelegramBotDaemon.
     */
    fun stopTelegramDaemon(callback: LaunchCallback) {
        daemonLauncher.stopTelegramDaemon(callback.toDaemonCallback())
    }
    
    /**
     * Kill the daemon process via ADB.
     */
    fun killDaemon(callback: LaunchCallback) {
        daemonLauncher.killDaemon("byd_cam_daemon", callback.toDaemonCallback())
    }
    
    /**
     * Kill a specific daemon process by name via ADB.
     */
    fun killDaemon(processName: String, callback: LaunchCallback) {
        daemonLauncher.killDaemon(processName, callback.toDaemonCallback())
    }
    
    /**
     * Check if daemon process is running via ADB.
     */
    fun isDaemonRunning(callback: (Boolean) -> Unit) {
        daemonLauncher.isDaemonRunning("byd_cam_daemon", callback)
    }
    
    /**
     * Check if a specific daemon is running by process name.
     */
    fun isDaemonRunning(processName: String, callback: (Boolean) -> Unit) {
        daemonLauncher.isDaemonRunning(processName, callback)
    }

    /**
     * One `ps -A` snapshot for the whole daemon set — see
     * [DaemonLauncher.snapshotProcessTable]. Lets a caller that needs to test
     * several daemons at once pay one `/proc` walk instead of one per daemon.
     * Callback receives null when the probe failed (treat as "unknown", never
     * as "dead").
     */
    fun snapshotProcessTable(callback: (String?) -> Unit) {
        daemonLauncher.snapshotProcessTable(callback)
    }

    /** Test one process name against a [snapshotProcessTable] result. */
    fun processAliveIn(snapshot: String, processName: String): Boolean =
        daemonLauncher.processAliveIn(snapshot, processName)
    
    /**
     * Get process uptime for a daemon.
     */
    fun getProcessUptime(processName: String, callback: (String?) -> Unit) {
        daemonLauncher.getProcessUptime(processName, callback)
    }
    
    /**
     * Get list of subprocesses for given patterns.
     */
    fun getSubprocesses(processPatterns: List<String>, callback: (List<DaemonLauncher.ProcessInfo>) -> Unit) {
        daemonLauncher.getSubprocesses(processPatterns, callback)
    }
    
    // ==================== SERVICE LAUNCHING ====================
    
    /**
     * Start daemons via ADB shell.
     */
    fun startDaemonsViaShell(callback: LaunchCallback) {
        serviceLauncher.startDaemonsViaShell(callback.toServiceCallback())
    }
    
    /**
     * Configure and start the Location Sidecar Service via ADB shell.
     */
    fun startLocationSidecar(callback: LaunchCallback) {
        serviceLauncher.grantLocationPermissions(callback.toServiceCallback())
    }
    
    /**
     * Apply power settings to keep WiFi and system active.
     */
    fun applyPowerSettings() {
        serviceLauncher.applyPowerSettings(object : ServiceLauncher.LaunchCallback {
            override fun onLog(message: String) {}
            override fun onLaunched() {}
            override fun onError(error: String) {
                logManager.error(TAG, "Failed to apply power settings: $error")
            }
        })
    }
    
    // 0.1.13: pokeMcuViaShell/turnScreenOff entfernt (ohne Aufrufer; KEYCODE_WAKEUP/POWER wären sichtbar, A+).

    // ==================== ACC/WIFI MANAGEMENT ====================
    
    /**
     * Inject app into BYD ACC mode whitelist via ADB shell.
     */
    fun injectAccWhitelist(packageName: String, callback: LaunchCallback) {
        serviceLauncher.injectAccWhitelist(packageName, callback.toServiceCallback())
    }
    
    /**
     * Enable WiFi and prevent it from sleeping via shell commands.
     */
    fun ensureWifiEnabled(callback: LaunchCallback) {
        serviceLauncher.ensureWifiEnabled(callback.toServiceCallback())
    }
    
    // ==================== SENTRY DAEMON ====================
    
    /**
     * Check if Sentry Daemon is running.
     */
    fun isSentryDaemonRunning(callback: (Boolean) -> Unit) {
        daemonLauncher.isDaemonRunning("sentry_daemon", callback)
    }
    
    /**
     * Stop the Sentry Daemon.
     */
    fun stopSentryDaemon(callback: LaunchCallback) {
        daemonLauncher.killDaemon("sentry_daemon", callback.toDaemonCallback())
    }
    
    // ==================== LOCATION SIDECAR MANAGEMENT ====================
    
    /**
     * Stop the Location Sidecar Service.
     */
    fun stopLocationSidecar(callback: LaunchCallback) {
        serviceLauncher.stopLocationSidecarService(callback.toServiceCallback())
    }
    
    /**
     * Check if Location Sidecar is running.
     */
    fun isLocationSidecarRunning(callback: (Boolean) -> Unit) {
        serviceLauncher.isLocationSidecarRunning(callback)
    }
    
    // ==================== LOCATION SIDECAR SERVICE ====================
    
    /**
     * Configure Location permissions via ADB shell.
     * Grants location permissions, applies power settings, and injects ACC whitelist.
     */
    fun startLocationSidecarService(callback: LaunchCallback) {
        logManager.info(TAG, "Configuring LocationSidecarService permissions via ADB shell...")
        callback.onLog("Granting Location permissions...")
        
        // Step 1: Grant Location permissions
        serviceLauncher.grantLocationPermissions(object : ServiceLauncher.LaunchCallback {
            override fun onLog(message: String) = callback.onLog(message)
            
            override fun onLaunched() {
                callback.onLog("Applying power settings...")
                
                // Step 2: Apply power settings
                serviceLauncher.applyPowerSettings(object : ServiceLauncher.LaunchCallback {
                    override fun onLog(message: String) = callback.onLog(message)
                    
                    override fun onLaunched() {
                        callback.onLog("Injecting ACC whitelist...")
                        
                        // Step 3: Inject ACC whitelist
                        serviceLauncher.injectAccWhitelist(context.packageName, object : ServiceLauncher.LaunchCallback {
                            override fun onLog(message: String) = callback.onLog(message)
                            
                            override fun onLaunched() {
                                callback.onLog("Starting LocationSidecarService...")
                                
                                // Step 4: Start the service
                                serviceLauncher.startLocationSidecarService(object : ServiceLauncher.LaunchCallback {
                                    override fun onLog(message: String) = callback.onLog(message)
                                    
                                    override fun onLaunched() {
                                        logManager.info(TAG, "LocationSidecarService started successfully")
                                        callback.onLog("Location service started")
                                        callback.onLaunched()
                                    }
                                    
                                    override fun onError(error: String) {
                                        logManager.error(TAG, "LocationSidecarService start failed: $error")
                                        callback.onError("Location service start failed: $error")
                                    }
                                })
                            }
                            
                            override fun onError(error: String) {
                                // Continue even if ACC whitelist fails
                                logManager.warn(TAG, "ACC whitelist failed (continuing): $error")
                                serviceLauncher.startLocationSidecarService(callback.toServiceCallback())
                            }
                        })
                    }
                    
                    override fun onError(error: String) {
                        // Continue even if power settings fail
                        logManager.warn(TAG, "Power settings failed (continuing): $error")
                        serviceLauncher.startLocationSidecarService(callback.toServiceCallback())
                    }
                })
            }
            
            override fun onError(error: String) {
                callback.onError("Failed to grant Location permissions: $error")
            }
        })
    }
    
    /**
     * Stop LocationSidecarService.
     */
    fun stopLocationSidecarService(callback: LaunchCallback) {
        serviceLauncher.stopLocationSidecarService(callback.toServiceCallback())
    }
    
// ==================== SHELL EXECUTION ====================
    
    /**
     * Execute a shell command via ADB.
     */
    fun executeShellCommand(command: String, callback: LaunchCallback) {
        adbShellExecutor.execute(
            command = command,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    callback.onLog(output)
                    callback.onLaunched()
                }

                override fun onError(error: String) {
                    callback.onError(error)
                }
            }
        )
    }

    /**
     * Execute a LONG-RUNNING shell command on the BULK lane: a dedicated,
     * per-operation Dadb connection bounded by [deadlineMs], separate from the
     * shared control connection. Use for anything that can stay output-silent
     * longer than the control lane tolerates (updater download, `pm install`)
     * so it can neither be severed by the shared lane's socket timeout nor
     * monopolize/tear down the connection every other subsystem depends on.
     * See AdbShellExecutor.executeBulk.
     */
    fun executeShellCommandBulk(command: String, deadlineMs: Long, callback: LaunchCallback) {
        adbShellExecutor.executeBulk(
            command = command,
            deadlineMs = deadlineMs,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    callback.onLog(output)
                    callback.onLaunched()
                }

                override fun onError(error: String) {
                    callback.onError(error)
                }
            }
        )
    }

    /**
     * Execute a multi-command shell payload via a temp script file. Use this
     * for any payload containing `pkill -f '<pattern>'` whose pattern also
     * appears as a literal substring elsewhere in the same payload —
     * `sh -c "<payload>"` puts the whole payload in argv[2], and toybox
     * `pkill -f` matches the calling shell itself, SIGKILLing it before
     * later commands run. The script-file form puts only the path in argv,
     * so pkill cannot self-match. See AdbShellExecutor.executeScript.
     */
    fun executeShellScript(scriptBody: String, callback: LaunchCallback) {
        adbShellExecutor.executeScript(
            scriptBody = scriptBody,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    callback.onLog(output)
                    callback.onLaunched()
                }

                override fun onError(error: String) {
                    callback.onError(error)
                }
            }
        )
    }
    
    // ==================== CALLBACK ADAPTERS ====================
    
    private fun LaunchCallback.toDaemonCallback() = object : DaemonLauncher.LaunchCallback {
        override fun onLog(message: String) = this@toDaemonCallback.onLog(message)
        override fun onLaunched() = this@toDaemonCallback.onLaunched()
        override fun onError(error: String) = this@toDaemonCallback.onError(error)
    }
    
    private fun LaunchCallback.toServiceCallback() = object : ServiceLauncher.LaunchCallback {
        override fun onLog(message: String) = this@toServiceCallback.onLog(message)
        override fun onLaunched() = this@toServiceCallback.onLaunched()
        override fun onError(error: String) = this@toServiceCallback.onError(error)
    }
}
