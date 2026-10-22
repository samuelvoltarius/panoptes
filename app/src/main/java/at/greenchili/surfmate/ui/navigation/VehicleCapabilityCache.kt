package at.greenchili.surfmate.ui.navigation

import at.greenchili.surfmate.util.DaemonHttpClient
import org.json.JSONObject
import java.net.HttpURLConnection

/**
 * App-seitige Kopie von `GET /api/system/capabilities` (Daemon). Tacho- und HUD-Funktionen
 * werden nur angeboten, wenn das Auto sie nachweislich hat (21.10.2026; Surf: ausgeblendet).
 * Unbekannt (Daemon noch nicht erreichbar) gilt als „nicht vorhanden“; der nächste Aufruf
 * versucht es erneut, eine bekannte Antwort bleibt für die Laufzeit des Prozesses.
 */
object VehicleCapabilityCache {

    @Volatile var cluster: Boolean? = null
        private set
    @Volatile var hud: Boolean? = null
        private set
    @Volatile private var inFlight = false

    /**
     * 0.1.10 (Teil E/H3): bleibt ein Panoptes-Hinweis über CarPlay bzw. Android Auto sichtbar?
     * "ja" | "nein" | "unbekannt"; null = noch nicht geholt. Nur "ja" schaltet Hinweise über der Projektion frei.
     */
    @Volatile var ueberCarPlay: String? = null
        private set
    @Volatile var ueberAndroidAuto: String? = null
        private set
    /** Gemeldete Kamera-Anzahl; null = unbekannt (dann entfällt die Zuordnung nicht). */
    @Volatile var kameraAnzahl: Int? = null
        private set

    /** Darf der Menüpunkt mit diesem Schlüssel gezeigt werden? */
    fun allowsRailKey(key: String): Boolean = when (key) {
        NavigationRailCatalog.PROJECTION -> cluster == true
        else -> true
    }

    /** Im Hintergrund holen, falls noch unbekannt; [onKnown] läuft auf dem Hintergrund-Thread. */
    fun refreshIfUnknown(onKnown: () -> Unit) {
        if (cluster != null || inFlight) return
        inFlight = true
        Thread({
            try {
                val json = fetch()
                if (json != null && json.optBoolean("success", false)) {
                    apply(json)
                    onKnown()
                }
            } finally {
                inFlight = false
            }
        }, "VehicleCapabilityCache").apply { isDaemon = true }.start()
    }

    internal fun apply(json: JSONObject) {
        cluster = json.optBoolean("cluster", false)
        hud = json.optBoolean("hud", false)
        val ueber = json.optJSONObject("ueberProjektion")
        ueberCarPlay = ueber?.optString("carplay", "unbekannt") ?: "unbekannt"
        ueberAndroidAuto = ueber?.optString("androidauto", "unbekannt") ?: "unbekannt"
        val kam = json.optJSONObject("kameras")
        kameraAnzahl = if (kam != null && kam.has("anzahl")) kam.optInt("anzahl") else null
    }

    private fun fetch(): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = DaemonHttpClient.open("/api/system/capabilities", "GET", 2000, 10000)
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            if (body.isEmpty()) null else JSONObject(body)
        } catch (_: Throwable) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }
}
