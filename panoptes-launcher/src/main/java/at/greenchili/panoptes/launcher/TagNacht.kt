package at.greenchili.panoptes.launcher

import android.content.Context

/**
 * 0.1.41 (LA) Design Tag/Nacht automatisch. Ist es an, gilt tagsüber das gewählte Design und nachts „Nacht“.
 * Standard: aus (ab Werk wird nichts still umgestellt). Die Wahl liegt nur im Launcher und wird nicht exportiert.
 *
 * Woher „Nacht“ kommt, in dieser Reihenfolge:
 *  1. Sonnenstand vom Kern (`env.isNight`, SolarCalculator mit dem GPS-Ort des Autos), wenn er einen Wert liefert;
 *  2. sonst die Uhrzeit mit festen Sonnenzeiten je Monat (Mitteleuropa, Ortszeit).
 * Einen Lichtsensor-Wert liefert der Kern heute nicht; darum wird keiner benutzt (kein Sensor-Zugriff im Launcher).
 * Reine Regeln, im Unit-Test belegt.
 */
object TagNacht {
    const val NACHT_DESIGN = "nacht"

    private const val PREFS = "launcher_tagnacht"
    private const val KEY = "an"

    /** Ungefähre Sonnenauf- und -untergänge (Minuten nach Mitternacht, Ortszeit mit Sommerzeit) je Monat 1..12, ~48° N. */
    private val AUF = intArrayOf(465, 435, 390, 390, 340, 310, 320, 360, 405, 450, 430, 465)
    private val UNTER = intArrayOf(1005, 1050, 1090, 1190, 1230, 1260, 1255, 1215, 1155, 1095, 1000, 980)

    /** Nacht nach Uhrzeit: vor Sonnenaufgang oder ab Sonnenuntergang des Monats. */
    fun nachtNachUhr(monat: Int, stunde: Int, minute: Int): Boolean {
        val m = (monat.coerceIn(1, 12)) - 1
        val jetzt = stunde.coerceIn(0, 23) * 60 + minute.coerceIn(0, 59)
        return jetzt < AUF[m] || jetzt >= UNTER[m]
    }

    /** Kern-Wert (Sonnenstand) geht vor; ohne ihn die Uhrzeit. */
    fun istNacht(kernNacht: Boolean?, monat: Int, stunde: Int, minute: Int): Boolean =
        kernNacht ?: nachtNachUhr(monat, stunde, minute)

    /** Design, das gerade gilt. Aus = immer das gewählte. An + Nacht = „Nacht“. An + Tag: gewähltes Design,
     *  außer es ist selbst „Nacht“ – dann tagsüber „Wie Panoptes“, sonst gäbe es keinen Wechsel. */
    fun design(gewaehlt: String, an: Boolean, nacht: Boolean): String = when {
        !an -> gewaehlt
        nacht -> NACHT_DESIGN
        gewaehlt == NACHT_DESIGN -> "auto"
        else -> gewaehlt
    }

    fun an(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun speichern(ctx: Context, an: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, an).apply()
    }
}
