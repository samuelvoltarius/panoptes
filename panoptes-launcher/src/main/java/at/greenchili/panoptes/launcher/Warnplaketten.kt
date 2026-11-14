package at.greenchili.panoptes.launcher

import android.content.Context

/**
 * 0.1.43.4 (Rang 4 „Warnplaketten“): zusätzliche Plaketten in der Plaketten-Reihe der Fahrzeug-Ansicht (L3,
 * [HauptPlaketten]). Rang 4 liefert nur, was die Hauptreihe nicht zeigt: Gurt Fahrer/Beifahrer, Reifendruck,
 * Kofferraum, Haube. Tür, Schloss, Fenster und Laden zeigt allein [HauptPlaketten] (Koordination 13.11.).
 *
 * Nur aus echten Kernwerten – unbekannt erzeugt keine Plakette, es wird nie geraten. Gezeichnet über [Ui.plakette].
 * Reine Anzeige: kein Ton, keine Animation, kein Fahrzeugbefehl.
 */
object Warnplaketten {
    /** Höchstens so viele Plaketten in der gemeinsamen Reihe (Hauptreihe + Rang 4), wichtigste zuerst. */
    const val MAX = 4
    /** Ab dieser Geschwindigkeit gilt „fährt“ (Gurt-Plakette); darunter ist ein offener Gurt normal. */
    const val FAHRT_AB_KMH = 5.0

    enum class Art { GURT_FAHRER, GURT_BEIFAHRER, KOFFERRAUM, HAUBE, REIFEN }

    data class Plakette(val art: Art, val ton: Signal.Ton, val teile: List<String> = emptyList())

    /** Fährt: bekanntes Tempo über [FAHRT_AB_KMH] (unbekannt = nicht in Fahrt, also keine Gurt-Plakette). */
    fun faehrt(s: LauncherSummary): Boolean = (s.speedKmh ?: 0.0) > FAHRT_AB_KMH

    fun liste(s: LauncherSummary?): List<Plakette> {
        if (s == null) return emptyList()
        val o = ArrayList<Plakette>()
        if (s.gurt?.fahrer == false && faehrt(s)) o += Plakette(Art.GURT_FAHRER, Signal.Ton.GEFAHR)
        if (s.gurt?.beifahrer == false && faehrt(s)) o += Plakette(Art.GURT_BEIFAHRER, Signal.Ton.GEFAHR)
        val k = s.koerper
        if (k?.tueren?.get("trunk") == true) o += Plakette(Art.KOFFERRAUM, Signal.Ton.WARNUNG)
        if (k?.tueren?.get("hood") == true) o += Plakette(Art.HAUBE, Signal.Ton.WARNUNG)
        val reifen = s.reifen?.warn?.withIndex()?.filter { it.value != null }?.map { listOf("vl", "vr", "hl", "hr")[it.index] }.orEmpty()
        if (reifen.isNotEmpty()) o += Plakette(Art.REIFEN, Signal.Ton.WARNUNG, reifen)
        return o
    }

    fun text(ctx: Context, p: Plakette): String = ctx.getString(when (p.art) {
        Art.GURT_FAHRER -> R.string.plakette_gurt_fahrer
        Art.GURT_BEIFAHRER -> R.string.plakette_gurt_beifahrer
        Art.KOFFERRAUM -> R.string.plakette_kofferraum
        Art.HAUBE -> R.string.plakette_haube
        Art.REIFEN -> R.string.plakette_reifen
    })

    fun symbol(p: Plakette): Int = when (p.art) {
        Art.GURT_FAHRER, Art.GURT_BEIFAHRER -> R.drawable.ic_pn_sitz
        Art.KOFFERRAUM, Art.HAUBE -> R.drawable.ic_pn_car
        Art.REIFEN -> R.drawable.ic_pn_info
    }

    /** Ein Eintrag der gemeinsamen Reihe: entweder aus der Hauptreihe oder aus Rang 4. */
    data class Eintrag(val ton: Signal.Ton, val haupt: HauptPlaketten.Teil? = null, val eigen: Plakette? = null)

    private fun rang(t: Signal.Ton) = when (t) {
        Signal.Ton.GEFAHR -> 0; Signal.Ton.WARNUNG -> 1; Signal.Ton.INFO -> 2; Signal.Ton.OK -> 3; Signal.Ton.NEUTRAL -> 4
    }

    /**
     * Gemeinsame Reihe: nach Dringlichkeit (Gefahr, Warnung, Info, OK), bei Gleichstand erst Rang 4 (Gurt, Klappen,
     * Reifen), dann die Hauptreihe in ihrer Reihenfolge. Höchstens [MAX]; wegfallen zuerst die ruhigen.
     */
    fun reihe(haupt: List<HauptPlaketten.Teil>, eigene: List<Plakette>): List<Eintrag> =
        (eigene.map { Eintrag(it.ton, eigen = it) } + haupt.map { Eintrag(it.ton, haupt = it) })
            .sortedBy { rang(it.ton) }.take(MAX)
}
