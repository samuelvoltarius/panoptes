package at.greenchili.panoptes.launcher

import android.content.Context
import java.util.Locale

/** 0.1.43.4 (E2E G5): Großschreibung nach Sprache – Türkisch i → İ (MENZİL, MÜZİK), nie Locale.ROOT. */
object Gross {
    fun text(s: String, sprache: Locale): String = s.uppercase(sprache)
}

/** 0.1.43.4 (E2E G3): Zündungswert aus dem Kern → Text; unbekannt = null (dann „—“). */
object Zuendung {
    fun text(acc: String?): Int? = when (acc) {
        "on" -> R.string.acc_an
        "off" -> R.string.acc_aus
        "charging" -> R.string.ansicht_laedt
        else -> null
    }
}

/**
 * 0.1.43.4 (E2E G4): Namensnennungen stehen im Katalog auf Deutsch („· verändert“, „· bearbeitet“). Name, Lizenz und
 * Quelle bleiben, wie sie sind; nur der Zusatz wird übersetzt.
 */
object Namensnennung {
    const val VERAENDERT = " · verändert"
    const val BEARBEITET = " · bearbeitet"

    fun lokal(roh: String, veraendert: String, bearbeitet: String): String = when {
        roh.endsWith(VERAENDERT) -> roh.dropLast(VERAENDERT.length) + " · " + veraendert
        roh.endsWith(BEARBEITET) -> roh.dropLast(BEARBEITET.length) + " · " + bearbeitet
        else -> roh
    }

    fun lokal(ctx: Context, roh: String): String =
        lokal(roh, ctx.getString(R.string.nennung_veraendert), ctx.getString(R.string.nennung_bearbeitet))
}
