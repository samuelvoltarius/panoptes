package at.greenchili.surfmate

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import at.greenchili.surfmate.config.ConfigChangeListener
import at.greenchili.surfmate.config.ConfigManager
import at.greenchili.surfmate.logging.LogCleaner
import at.greenchili.surfmate.logging.LogConfig
import at.greenchili.surfmate.logging.LogManager
import at.greenchili.surfmate.server.LocaleManager
import at.greenchili.surfmate.services.DaemonKeepaliveService
// import at.greenchili.surfmate.shell.PrivilegedShellSetup
import at.greenchili.surfmate.ui.util.PreferencesManager

/**
 * Application class for SurfMate.
 * Initializes global singletons before any Activity is created.
 */
class SurfMateApplication : Application() {
    
    override fun onCreate() {
        super.onCreate()

        try {
            startService(Intent(this, at.greenchili.surfmate.remote.RemoteDevViewBridgeService::class.java))
        } catch (error: Throwable) {
            Log.w("SurfMateApplication", "Remote dev-view bridge unavailable: ${error.message}")
        }

        // Apply the user-picked locale before any Activity/Fragment is created.
        // Auto-mode (or unset) writes an empty list so AppCompat falls back to
        // Locale.getDefault() — i.e. the BYD head unit's system language.
        LocaleManager.attach(this)
        applyPersistedLocale()

        // Initialize LogConfig with app's cache directory for file logging
        LogConfig.init(this)

        // Initialize LogManager seeded from the PERSISTED logging config, and
        // wire live updates + the cleanup worker. Previously LogManager was
        // built with LogConfig.default() and the user's size/retention values
        // never reached it (dead config), and LogCleaner was never scheduled
        // (dead retention). setupLogging() closes both gaps.
        setupLogging()

        // Initialize PreferencesManager before any ViewModel is created
        PreferencesManager.init(this)

        // Apply persisted theme mode (Auto / Light / Dark) before any Activity
        // is created so the first paint matches the user's choice.
        AppCompatDelegate.setDefaultNightMode(PreferencesManager.getThemeMode())
        
        // Privileged shell (UID 1000) DISABLED — causes BYD default dashcam
        // to show "no signal" by elevating app's camera priority via accmodemanager.
        // All daemons now run via ADB shell (UID 2000) which is sufficient.
        // PrivilegedShellSetup.init(this)
        // PrivilegedShellSetup.setup(...)

        // Start DaemonKeepaliveService - handles:
        // - Foreground service with START_STICKY
        // - PARTIAL_WAKE_LOCK to prevent CPU sleep
        // - SCREEN_OFF receiver registration
        // - Daemon startup
        DaemonKeepaliveService.start(this)
        // Non-blocking and a no-op in steady state. If a language pick was
        // saved app-private while daemon IPC was unavailable, replay it once
        // the daemon comes back instead of letting an old server locale win.
        if (packageName == android.app.Application.getProcessName()) {
            LocaleManager.replayPendingWriteAsync()
        }

        // App-process listener that binds Telenav's OEM AIDL for the daemon's
        // HTTP endpoint (the daemon can't bindService itself). Idempotent.
        at.greenchili.surfmate.telenav.TelenavIpcServer.start(this)

        // Signal relays for the daemon (it cannot read call/Bluetooth state from
        // UID 2000). Started HERE, on plain process start, and not only from
        // KeepAliveAccessibilityService.onServiceConnected as before.
        //
        // Neither monitor needs accessibility for anything — they want a Context and
        // nothing more. The a11y service was simply a convenient always-alive host,
        // and that convenience quietly made them share its failure mode: when AMS
        // leaves the service stuck in "Binding" (field-observed, recurring on this
        // firmware), onServiceConnected never runs, so no receiver is registered and
        // even the 60s re-assert never ticks. Bluetooth automations then see nothing
        // at all — measured: 11+ minutes with the phone connected the whole time, and
        // 92s even once the keymap watchdog learned to detect and recover the wedge.
        //
        // SurfMateApplication.onCreate runs on EVERY process start, so this brings
        // the relays up within seconds of power-on regardless of whether the
        // accessibility service ever binds. Both start() methods are synchronized and
        // idempotent (they no-op while an instance is registered), so the a11y hook
        // calling them again later is free, and neither call can throw into onCreate.
        //
        // MAIN PROCESS ONLY. onCreate also runs in the isolated `com.byd.warning`
        // process that hosts EnergyModeActuatorService, and the singletons are
        // per-process — so without this guard that process registers a second pair of
        // receivers and a second 60s re-assert timer, doubling the relay POSTs for
        // values the daemon already has. Same idiom as BydDataCollector.
        if (packageName == android.app.Application.getProcessName()) {
            try {
                at.greenchili.surfmate.services.CallStateMonitor.start(this)
            } catch (ignored: Throwable) {
                // Guard only: the a11y hook calls start() again if it ever binds.
            }
            try {
                at.greenchili.surfmate.services.BluetoothStateMonitor.start(this)
            } catch (ignored: Throwable) {
                // Guard only: the a11y hook calls start() again if it ever binds.
            }
        }
    }

    /**
     * Build the live LogConfig by merging the user's persisted logging policy
     * (size cap / retention / cleanup interval / rotation count) onto the
     * app-context file-logging settings (log dir + enable flags), seed the
     * LogManager singleton with it, push later changes live, and schedule the
     * periodic cleanup worker. Without this the persisted config never reached
     * any rotation/retention mechanism.
     */
    private fun setupLogging() {
        val base = LogConfig.default()  // carries the resolved app log dir + enable flags
        val persisted = ConfigManager.getInstance(this).getLoggingConfig()

        // Every user-owned field must be listed here. base is LogConfig.default(), so
        // anything omitted silently reverts to the compiled-in default and the persisted
        // value is inert — which is what this merge exists to prevent.
        fun merged(policy: LogConfig): LogConfig = base.copy(
            retentionHours = policy.retentionHours,
            cleanupIntervalHours = policy.cleanupIntervalHours,
            maxFileSizeMB = policy.maxFileSizeMB,
            rotationCount = policy.rotationCount,
            minLevel = policy.minLevel
        )

        // Seed the singleton with the persisted policy.
        LogManager.getInstance(merged(persisted))

        // Push live changes into the running LogManager and re-schedule the
        // cleaner so a settings change takes effect without an app restart.
        ConfigManager.getInstance(this).addConfigChangeListener(object : ConfigChangeListener {
            override fun onConfigChanged(key: String, oldValue: Any?, newValue: Any?) {
                if (key == "loggingConfig" && newValue is LogConfig) {
                    val live = merged(newValue)
                    LogManager.getInstance().updateConfig(live)
                    try {
                        LogCleaner.schedule(this@SurfMateApplication, live.cleanupIntervalHours.toLong())
                    } catch (e: Exception) {
                        Log.w("SurfMateApplication", "LogCleaner re-schedule failed: ${e.message}")
                    }
                }
            }
        })

        // Schedule the periodic app-log cleanup worker (was never enqueued, so
        // retentionHours was inert). WorkManager enforces a 15-min floor; our
        // 4h cadence is well above it.
        try {
            LogCleaner.schedule(this, persisted.cleanupIntervalHours.toLong())
        } catch (e: Exception) {
            Log.w("SurfMateApplication", "LogCleaner schedule failed: ${e.message}")
        }
    }

    private fun applyPersistedLocale() {
        try {
            val raw = LocaleManager.getRaw()
            val locales = if (raw == null) {
                // Noch nie gewählt: Deutsch als Panoptes-Standard.
                LocaleListCompat.forLanguageTags(LocaleManager.PRODUCT_DEFAULT_LANG)
            } else if (raw == LocaleManager.AUTO_TAG) {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(raw)
            }
            AppCompatDelegate.setApplicationLocales(locales)
        } catch (e: Exception) {
            Log.w("SurfMateApplication", "applyPersistedLocale failed: ${e.message}")
        }
    }
}
