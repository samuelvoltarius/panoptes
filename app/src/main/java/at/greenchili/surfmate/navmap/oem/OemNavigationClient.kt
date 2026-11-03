package at.greenchili.surfmate.navmap.oem

import at.greenchili.surfmate.client.CameraDaemonClient
import at.greenchili.surfmate.config.UnifiedConfigManager
import at.greenchili.surfmate.navmap.nav.NavGuidanceEngine
import java.util.concurrent.Executors

/** Non-blocking app-process client for the daemon-owned OEM arrow publisher. */
object OemNavigationClient {
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "OemNavigationClient").apply { isDaemon = true }
    }
    private val daemon = CameraDaemonClient()
    @Volatile private var lastKey = ""

    @JvmStatic
    fun publish(state: NavGuidanceEngine.GuidanceState) {
        val maneuver = state.currentManeuver ?: return
        if (!enabled()) return
        val distance = state.distanceToManeuverM.toInt().coerceAtLeast(0)
        val remaining = state.remainingDistanceM.toInt().coerceAtLeast(0)
        val seconds = state.etaSeconds.toInt().coerceAtLeast(0)
        val key = "${maneuver.type}:$distance:$remaining:$seconds:${maneuver.roundaboutExitCount}"
        if (key == lastKey) return
        lastKey = key
        executor.execute {
            try {
                val data = org.json.JSONObject()
                    .put("op", "frame")
                    .put("maneuverType", maneuver.type)
                    .put("distanceMeters", distance)
                    .put("remainingMeters", remaining)
                    .put("remainingSeconds", seconds)
                    .put("roundaboutExit", maneuver.roundaboutExitCount)
                daemon.sendCommand(org.json.JSONObject().put("cmd", "oemNavigation").put("data", data))
            } catch (_: Throwable) { }
        }
    }

    @JvmStatic
    fun stop() {
        lastKey = ""
        // Always forward stop: the driver may have switched the option off while a
        // route was active. The daemon accepts stop independently of the opt-in so a
        // latched OEM arrow disappears immediately instead of waiting for the watchdog.
        executor.execute {
            try {
                val data = org.json.JSONObject().put("op", "stop")
                daemon.sendCommand(org.json.JSONObject().put("cmd", "oemNavigation").put("data", data))
            } catch (_: Throwable) { }
        }
    }

    /**
     * Publish a deliberately minimal frame parsed from an allow-listed navigation
     * notification. Street names and coordinates never leave the app process.
     */
    @JvmStatic
    fun publishExternal(maneuverType: Int, distanceMeters: Int, roundaboutExit: Int = 0) {
        if (!externalEnabled()) return
        val distance = distanceMeters.coerceIn(0, 2_000_000)
        val key = "external:$maneuverType:$distance:$roundaboutExit"
        if (key == lastKey) return
        lastKey = key
        executor.execute {
            try {
                val data = org.json.JSONObject()
                    .put("op", "frame")
                    .put("maneuverType", maneuverType)
                    .put("distanceMeters", distance)
                    // Notifications do not provide a trustworthy trip remainder/ETA.
                    // The daemon contract requires non-negative values, so publish only
                    // the known next-maneuver distance and neutral ETA.
                    .put("remainingMeters", distance)
                    .put("remainingSeconds", 0)
                    .put("roundaboutExit", roundaboutExit.coerceIn(0, 10))
                daemon.sendCommand(org.json.JSONObject().put("cmd", "oemNavigation").put("data", data))
            } catch (_: Throwable) { }
        }
    }

    private fun enabled(): Boolean = try {
        // loadConfig() has an mtime/CRC freshness gate. Avoid forceReload() here:
        // publish() runs from the one-second guidance tick on the main thread.
        UnifiedConfigManager.loadConfig()
            .optJSONObject("navMap")?.optBoolean("oemTurnGuidance", false) == true
    } catch (_: Throwable) { false }

    private fun externalEnabled(): Boolean = try {
        val nav = UnifiedConfigManager.loadConfig().optJSONObject("navMap")
        nav?.optBoolean("oemTurnGuidance", false) == true &&
            nav.optBoolean("externalNotificationGuidance", false)
    } catch (_: Throwable) { false }
}
