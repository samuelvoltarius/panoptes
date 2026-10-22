package at.greenchili.surfmate.ui.util

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.UserManager
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import at.greenchili.surfmate.ui.model.DaemonType

/**
 * Manages SharedPreferences for app settings persistence.
 * Uses device-encrypted storage to support Direct Boot (before user unlock).
 */
object PreferencesManager {
    
    private const val TAG = "PreferencesManager"
    private const val PREFS_NAME = "surfmate_prefs"
    private const val KEY_ENABLED_DAEMONS = "enabled_daemons"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_SELECTED_CAMERAS = "selected_cameras"
    private const val KEY_LAST_TUNNEL_URL = "last_tunnel_url"
    private const val KEY_LOGS_EXPANDED = "logs_expanded"
    private const val KEY_NAVIGATION_VISIBLE_V1 = "navigation_visible_v1"
    /** Rail keys the user has already been offered, so a NEW one can default to
     *  visible without un-hiding something they deliberately switched off. */
    private const val KEY_NAVIGATION_SEEN_V1 = "navigation_seen_v1"

    private var prefs: SharedPreferences? = null
    // Held so theme-mode changes can poke the floating overlay service —
    // a Service doesn't see AppCompatDelegate.setDefaultNightMode() the way
    // an Activity does, so we ask it to rebuild itself explicitly.
    private var appContextRef: Context? = null

    /**
     * Initialize with application context. Call once in Application.onCreate().
     * Uses device-encrypted storage to support Direct Boot scenarios.
     * Migrates existing prefs from credential-encrypted storage on first run.
     */
    fun init(context: Context) {
        val appContext = context.applicationContext
        appContextRef = appContext
        
        // Use device-encrypted storage for Direct Boot support
        val storageContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val deviceContext = appContext.createDeviceProtectedStorageContext()
            
            // Migrate from credential-encrypted to device-encrypted storage (one-time)
            // This preserves existing preferences when upgrading
            if (isUserUnlocked(appContext)) {
                try {
                    deviceContext.moveSharedPreferencesFrom(appContext, PREFS_NAME)
                    Log.d(TAG, "Migrated prefs from credential to device storage")
                } catch (e: Exception) {
                    // Already migrated or nothing to migrate
                    Log.d(TAG, "Prefs migration skipped: ${e.message}")
                }
            }
            deviceContext
        } else {
            appContext
        }
        
        prefs = storageContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        Log.d(TAG, "Initialized with device-encrypted storage")
        // Bestand sofort bestimmen (vor allem, was die Config anlegen könnte), festschreiben im Hintergrund
        istBestand()
        Thread({ festschreibenTailscale() }, "TailscaleStandard").start()
    }
    
    /**
     * Check if the user is unlocked (credential-encrypted storage available).
     */
    fun isUserUnlocked(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
            userManager?.isUserUnlocked ?: true
        } else {
            true
        }
    }
    
    /**
     * Check if PreferencesManager has been initialized.
     */
    fun isInitialized(): Boolean = prefs != null
    
    private fun requirePrefs(): SharedPreferences {
        return prefs ?: throw IllegalStateException("PreferencesManager not initialized. Call init() first.")
    }
    
    /**
     * Theme mode (Auto/Light/Dark) — persisted, read at app start.
     * Stored as the AppCompatDelegate.MODE_NIGHT_* int so the value can be
     * passed straight to setDefaultNightMode().
     * Default: MODE_NIGHT_FOLLOW_SYSTEM (auto, follows the head-unit theme).
     */
    fun getThemeMode(): Int {
        return requirePrefs().getInt(
            KEY_THEME_MODE,
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        )
    }

    fun setThemeMode(mode: Int) {
        requirePrefs().edit().putInt(KEY_THEME_MODE, mode).apply()
        AppCompatDelegate.setDefaultNightMode(mode)
        // Nudge the floating overlay so the recording/trip pill repaints
        // against the newly-chosen palette. AppCompat only refires
        // onConfigurationChanged for foreground Activities; a plain Service
        // wouldn't notice the flip otherwise.
        appContextRef?.let { ctx ->
            try {
                at.greenchili.surfmate.overlay.StatusOverlayService.refreshTheme(ctx)
            } catch (e: Throwable) {
                Log.w(TAG, "overlay theme refresh failed: ${e.message}")
            }
        }
    }

    // Enabled Daemons
    /**
     * Which optional daemons/tunnels should run.
     *
     * Source of truth is the shared unified-config section [DAEMONS_SECTION]
     * (`{"TAILSCALE_TUNNEL": true, ...}`), because the web UI runs in the
     * daemon process and can only write there. The app-private set is the
     * fallback for types the section does not mention yet, and [DEFAULT_ON]
     * applies on a fresh install where neither exists.
     */
    fun getEnabledDaemons(): Set<DaemonType> {
        val prefs = requirePrefs()
        val hasLocal = prefs.contains(KEY_ENABLED_DAEMONS)
        val local = (prefs.getStringSet(KEY_ENABLED_DAEMONS, emptySet()) ?: emptySet()).mapNotNull { name ->
            try { DaemonType.valueOf(name) } catch (e: Exception) { null }
        }.toSet()
        val shared = sharedDaemonSection()
        val bestand = istBestand()
        return DaemonType.values().filter { type ->
            aktiv(type, shared, hasLocal, local, bestand)
        }.toSet()
    }

    /**
     * Regel für einen Daemon-Typ (rein, testbar). Ein Eintrag im gemeinsamen Abschnitt gewinnt immer
     * (Alfreds Auto: daemons.TAILSCALE_TUNNEL=true), dann die lokale Liste. Ohne beides: Tailscale nur bei
     * einer Bestandsinstallation an (Koordination 0.1.9, Regel B), bei einer NEUinstallation aus.
     */
    internal fun aktiv(
        type: DaemonType,
        shared: org.json.JSONObject?,
        hasLocal: Boolean,
        local: Set<DaemonType>,
        bestand: Boolean,
    ): Boolean = when {
        shared != null && shared.has(type.name) -> shared.optBoolean(type.name, false)
        hasLocal -> type in local
        else -> type in BESTAND_ON && bestand
    }

    /**
     * Bestand = die App wurde aktualisiert (erste Installation älter als das letzte Update) oder die
     * Daemon-Config hat schon Inhalt (Neuinstallation über eine alte Installation). Im Zweifel Bestand,
     * denn ein fälschlich ausgeschaltetes Tailscale sperrt aus (Regel B), ein fälschlich eingeschaltetes nicht.
     */
    private fun istBestand(): Boolean {
        cachedBestand?.let { return it }
        val ctx = appContextRef ?: return true
        val b = try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            istBestand(pi.firstInstallTime, pi.lastUpdateTime, configHatInhalt())
        } catch (t: Throwable) {
            true
        }
        cachedBestand = b
        return b
    }

    internal fun istBestand(firstInstall: Long, lastUpdate: Long, configHatInhalt: Boolean): Boolean =
        lastUpdate > firstInstall || configHatInhalt

    /** Die Daemon-Config gibt es schon (nur Datei-Existenz, keine Standardwerte mitzählen). */
    private fun configHatInhalt(): Boolean = try {
        at.greenchili.surfmate.config.UnifiedConfigManager.configExists()
    } catch (t: Throwable) {
        false
    }

    @Volatile
    private var cachedBestand: Boolean? = null

    /**
     * Beim ersten Start mit 0.1.9 den heute wirksamen Tailscale-Zustand festschreiben, wenn es noch
     * keinen Eintrag gibt: Bestand bleibt an, eine Neuinstallation bleibt aus – auch nach späteren
     * Updates (sonst würde sie mit dem ersten Update zum „Bestand“ und Tailscale ginge von selbst an).
     */
    private fun festschreibenTailscale() {
        try {
            val p = prefs ?: return
            val shared = sharedDaemonSection()
            if (shared != null && shared.has(DaemonType.TAILSCALE_TUNNEL.name)) return
            if (p.contains(KEY_ENABLED_DAEMONS)) return
            val an = istBestand()
            setDaemonEnabled(DaemonType.TAILSCALE_TUNNEL, an)
            Log.i(TAG, "Tailscale-Standard festgeschrieben: ${if (an) "an (Bestand)" else "aus (Neuinstallation)"}")
        } catch (t: Throwable) {
            Log.w(TAG, "festschreibenTailscale: ${t.message}")
        }
    }

    /** Re-reads the shared section from disk; the health check calls this once per tick. */
    fun refreshSharedDaemonState() {
        cachedDaemonSection = try {
            at.greenchili.surfmate.config.UnifiedConfigManager.forceReload().optJSONObject(DAEMONS_SECTION)
        } catch (t: Throwable) {
            cachedDaemonSection
        }
    }

    private fun sharedDaemonSection(): org.json.JSONObject? {
        cachedDaemonSection?.let { return it }
        refreshSharedDaemonState()
        return cachedDaemonSection
    }

    @Volatile
    private var cachedDaemonSection: org.json.JSONObject? = null

    /** Unified-config section that holds the desired on/off state per [DaemonType] name. */
    const val DAEMONS_SECTION = "daemons"

    /**
     * Ohne jeden Eintrag nur bei Bestandsinstallationen an (Koordination 0.1.9): eine neue Installation
     * startet ohne Tailscale; der Fernzugangs-Schritt der Einrichtung schaltet es dauerhaft ein.
     */
    private val BESTAND_ON = setOf(DaemonType.TAILSCALE_TUNNEL)
    
    fun setDaemonEnabled(type: DaemonType, enabled: Boolean) {
        val current = getEnabledDaemons().map { it.name }.toMutableSet()
        if (enabled) {
            current.add(type.name)
        } else {
            current.remove(type.name)
        }
        requirePrefs().edit().putStringSet(KEY_ENABLED_DAEMONS, current).commit()
        try {
            at.greenchili.surfmate.config.UnifiedConfigManager.updateSection(
                DAEMONS_SECTION, org.json.JSONObject().put(type.name, enabled))
            cachedDaemonSection = (cachedDaemonSection?.let { org.json.JSONObject(it.toString()) }
                ?: org.json.JSONObject()).put(type.name, enabled)
        } catch (t: Throwable) {
            Log.w("PreferencesManager", "could not mirror ${type.name}=$enabled to unified config", t)
        }
    }
    
    fun isDaemonEnabled(type: DaemonType): Boolean {
        return type in getEnabledDaemons()
    }
    
    // Selected Cameras
    fun getSelectedCameras(): Set<Int> {
        val strings = requirePrefs().getStringSet(KEY_SELECTED_CAMERAS, setOf("1")) ?: setOf("1")
        return strings.mapNotNull { it.toIntOrNull() }.toSet()
    }
    
    fun setSelectedCameras(cameras: Set<Int>) {
        val strings = cameras.map { it.toString() }.toSet()
        requirePrefs().edit().putStringSet(KEY_SELECTED_CAMERAS, strings).apply()
    }
    
    fun toggleCamera(cameraId: Int): Set<Int> {
        val current = getSelectedCameras().toMutableSet()
        if (cameraId in current) {
            current.remove(cameraId)
        } else {
            current.add(cameraId)
        }
        setSelectedCameras(current)
        return current
    }
    
    // Tunnel URL
    fun getLastTunnelUrl(): String? {
        return requirePrefs().getString(KEY_LAST_TUNNEL_URL, null)
    }
    
    fun setLastTunnelUrl(url: String?) {
        requirePrefs().edit().putString(KEY_LAST_TUNNEL_URL, url).apply()
    }
    
    // Logs Panel State
    fun isLogsExpanded(): Boolean {
        return requirePrefs().getBoolean(KEY_LOGS_EXPANDED, false)
    }
    
    fun setLogsExpanded(expanded: Boolean) {
        requirePrefs().edit().putBoolean(KEY_LOGS_EXPANDED, expanded).apply()
    }

    /**
     * Missing preference means the complete current catalog, preserving the
     * navigation users had before customization existed. Once customized, new
     * optional destinations remain available in Settings but are not pinned
     * automatically.
     */
    fun getVisibleNavigationKeys(knownKeys: Set<String>): Set<String> {
        val p = requirePrefs()
        val stored = if (p.contains(KEY_NAVIGATION_VISIBLE_V1)) {
            p.getStringSet(KEY_NAVIGATION_VISIBLE_V1, emptySet())?.toSet()
        } else {
            null
        }
        // A key the user has never been offered is NEW: show it rather than leaving
        // a shipped feature invisible on every existing install. Record the offer so
        // this only ever happens once per key.
        val seen = p.getStringSet(KEY_NAVIGATION_SEEN_V1, emptySet())?.toSet() ?: emptySet()
        val resolved = NavigationVisibilityPolicy.resolveWithNewDefaults(stored, knownKeys, seen)
        if (!seen.containsAll(knownKeys)) {
            p.edit().putStringSet(KEY_NAVIGATION_SEEN_V1, seen + knownKeys).apply()
        }
        return resolved
    }

    fun setNavigationItemVisible(key: String, visible: Boolean, knownKeys: Set<String>) {
        val updated = NavigationVisibilityPolicy.setVisible(
            getVisibleNavigationKeys(knownKeys),
            key,
            visible,
            knownKeys,
        )
        requirePrefs().edit()
            .putStringSet(KEY_NAVIGATION_VISIBLE_V1, updated)
            .apply()
    }

    fun resetNavigationVisibility() {
        requirePrefs().edit()
            .remove(KEY_NAVIGATION_VISIBLE_V1)
            .remove(KEY_NAVIGATION_SEEN_V1)
            .apply()
    }

    /** Current access URL — always the last tunnel URL we saw. */
    fun getCurrentUrl(): String? = getLastTunnelUrl()
}
