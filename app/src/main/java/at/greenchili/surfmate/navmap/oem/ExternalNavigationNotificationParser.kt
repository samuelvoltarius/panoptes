package at.greenchili.surfmate.navmap.oem

import java.util.Locale
import kotlin.math.roundToInt

/** Pure, conservative parser for allow-listed Google Maps/Waze turn notifications. */
object ExternalNavigationNotificationParser {
    private val packages = setOf("com.google.android.apps.maps", "com.waze")

    data class Frame(val maneuverType: Int, val distanceMeters: Int, val roundaboutExit: Int = 0)

    @JvmStatic
    fun parse(packageName: String?, parts: List<String?>): Frame? {
        if (packageName !in packages) return null
        val text = parts.filterNotNull().joinToString(" ").trim()
        if (text.isEmpty() || text.length > 2_000) return null
        val normalized = text.lowercase(Locale.ROOT).replace('’', '\'')
        val distance = distanceMeters(normalized) ?: return null
        val exit = roundaboutExit(normalized)
        val type = when {
            containsAny(normalized, "u-turn", "uturn", "wenden", "demi-tour", "inversione", "omkeren") -> 14
            containsAny(normalized, "roundabout", "kreisverkehr", "rond-point", "rotatoria", "rotonda", "rotonde") -> 26
            containsAny(normalized, "sharp right", "scharf rechts", "forte à droite", "net rechts", "destra stretta") -> 12
            containsAny(normalized, "sharp left", "scharf links", "forte à gauche", "scherp links", "sinistra stretta") -> 16
            containsAny(normalized, "slight right", "leicht rechts", "légèrement à droite", "flauw rechts", "leggermente a destra", "↗") -> 9
            containsAny(normalized, "slight left", "leicht links", "légèrement à gauche", "flauw links", "leggermente a sinistra", "↖") -> 10
            containsAny(normalized, "right", "rechts", "droite", "destra", "rechtsaf", "→", "↘") -> 11
            containsAny(normalized, "left", "links", "gauche", "sinistra", "linksaf", "←", "↙") -> 15
            containsAny(normalized, "continue", "straight", "geradeaus", "continuer", "tout droit", "prosegui", "dritto", "rechtdoor", "↑") -> 22
            else -> return null
        }
        return Frame(type, distance, if (type == 26) exit else 0)
    }

    private fun containsAny(text: String, vararg needles: String): Boolean =
        needles.any { text.contains(it) }

    private fun distanceMeters(text: String): Int? {
        // Word boundaries also accept compact notification forms such as "500m,"
        // without accidentally treating digits embedded in another token as distance.
        val match = Regex("\\b([0-9]{1,4}(?:[.,][0-9]{1,2})?)\\s*(km|mi|ft|yd|m)\\b")
            .find(text) ?: return null
        val value = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val meters = when (match.groupValues[2]) {
            "km" -> value * 1_000.0
            "mi" -> value * 1_609.344
            "ft" -> value * 0.3048
            "yd" -> value * 0.9144
            else -> value
        }
        return meters.roundToInt().takeIf { it in 0..2_000_000 }
    }

    private fun roundaboutExit(text: String): Int {
        val match = Regex("(?:exit|ausfahrt|sortie|uscita|afslag)\\s*(?:no\\.?\\s*)?([1-9]|10)|(?:([1-9]|10)(?:\\.|st|nd|rd|th|e|er|ème|ª|a)?\\s*(?:exit|ausfahrt|sortie|uscita|afslag))")
            .find(text) ?: return 0
        return (match.groupValues[1].ifEmpty { match.groupValues[2] }).toIntOrNull() ?: 0
    }
}
