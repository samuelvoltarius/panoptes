package at.greenchili.panoptes.launcher

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.TextView
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.max

/** 0.1.44 (P3): belegtes Tempolimit aus dem Kern (`summary.tempolimit`). */
data class LauncherTempolimit(val kmh: Int, val quelle: String, val alterMs: Long)

/**
 * 0.1.44 (P3, LUECKEN_0142 Punkt 5): Tempolimit im Launcher (Tempo-Kachel, Statuskarte). Reine Regeln + ein kleines
 * Schild. Gezeigt wird nur ein frischer, belegter Wert aus dem Kern (Quelle OpenStreetMap `maxspeed` entlang der
 * Panoptes-Route); ohne Wert, mit fremder Quelle, zu alt oder bei Kern-Aus: nichts. Die Warnung ist leise (nur Farbe,
 * kein Ton) und ab Werk aus (Einstellung `launcher.tempoWarnung`).
 */
object Tempolimit {
    const val QUELLE_OSM = "osm"
    /** Gleich wie TempolimitStand.FRISCH_MS im Kern. */
    const val FRISCH_MS = 15_000L

    fun parse(o: JSONObject?): LauncherTempolimit? {
        if (o == null) return null
        val kmh = o.optInt("kmh", -1)
        val quelle = o.optString("quelle", "")
        val alter = o.optLong("alterMs", Long.MAX_VALUE)
        if (kmh !in 5..200 || quelle != QUELLE_OSM) return null
        return LauncherTempolimit(kmh, quelle, alter)
    }

    /** Anzuzeigendes Limit in km/h oder null: nur belegt, frisch und bei erreichbarem Kern. */
    fun anzeige(t: LauncherTempolimit?, online: Boolean): Int? =
        t?.takeIf { online && it.quelle == QUELLE_OSM && it.alterMs in 0..FRISCH_MS }?.kmh

    /** Spielraum, bevor gewarnt wird: 3 km/h, über 60 km/h 5 % (aufgerundet). */
    fun toleranz(limit: Int): Int = max(3, ceil(limit * 0.05).toInt())

    /** Zu schnell? Nur mit Tempo UND Limit; sonst nie. */
    fun ueber(tempo: Double?, limit: Int?): Boolean =
        tempo != null && limit != null && tempo >= limit + toleranz(limit)

    /** Rundes Schild (weiß mit rotem Rand, Zahl schwarz), anfangs unsichtbar. */
    fun schild(ctx: Context, ui: Ui, groesse: Int = 46): TextView = TextView(ctx).apply {
        gravity = Gravity.CENTER
        textSize = groesse / 2.6f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(Color.BLACK)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setStroke(ui.dp(4), Color.rgb(0xD0, 0x1F, 0x1F)) }
        layoutParams = android.widget.LinearLayout.LayoutParams(ui.dp(groesse), ui.dp(groesse))
        visibility = View.GONE
    }

    /** Schild und Tempofarbe setzen (eine Stelle für Kachel und Statuskarte). */
    fun setze(schild: TextView, tempo: TextView, s: LauncherSummary?, online: Boolean, normal: Int, warnung: Int) {
        val limit = anzeige(s?.tempolimit, online)
        schild.visibility = if (limit != null) View.VISIBLE else View.GONE
        if (limit != null) {
            schild.text = limit.toString()
            schild.contentDescription = schild.context.getString(R.string.tempolimit_beschreibung, limit)
        }
        val warnen = s?.tempoWarnung == true && ueber(s.speedKmh, limit)
        tempo.setTextColor(if (warnen) warnung else normal)
    }
}
