package at.greenchili.surfmate.navmap.nav

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round

/**
 * 0.1.32.1 Regel Nr. 2: Was die Karte fuer die Suche hinausschickt. Seit G3 (05.11.2026) geht die Ortssuche nur an den
 * eigenen Planer (ueber den Kern, nur der Suchtext); fremd bleibt nur Overpass (Punkte an der Route) mit grobem
 * Ausschnitt. Position nur auf 2 Nachkommastellen, keine Kennung, Schalter {@code karte.sucheInternet} (Standard an).
 */
object KartenSuchePrivat {

    private const val STELLEN = 100.0

    /** Grobe Koordinate, etwa 1 km. */
    fun rund(wert: Double): Double = round(wert * STELLEN) / STELLEN

    /** Ausschnitt {süd, west, nord, ost} nach außen gerundet, damit kein Treffer am Rand verloren geht. */
    fun ausschnitt(sued: Double, west: Double, nord: Double, ost: Double): DoubleArray = doubleArrayOf(
        floor(sued * STELLEN) / STELLEN, floor(west * STELLEN) / STELLEN,
        ceil(nord * STELLEN) / STELLEN, ceil(ost * STELLEN) / STELLEN
    )

    fun erlaubt(): Boolean = try {
        at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig().optJSONObject("karte")
            ?.optBoolean("sucheInternet", true) ?: true
    } catch (_: Throwable) {
        true
    }
}
