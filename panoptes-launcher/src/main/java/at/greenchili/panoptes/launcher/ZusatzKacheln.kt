package at.greenchili.panoptes.launcher

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.NumberFormat

/**
 * 0.1.41 (LK): Kacheln „Reifen“, „Letzte Fahrt“ und „Tempo“. Alles nur gelesen aus der Kern-API (Summary `tyres`,
 * `state.speedKmh`; `/api/launcher/v1/history` letzte Fahrt). Fehlt ein Wert, steht „—“ – nie etwas Erfundenes.
 * Eine Tempolimit-Anzeige gibt es nicht: Der Kern liefert kein Limit (nur den ADAS-Schalter „Tempowarnung“).
 * Die Rechen- und Textregeln oben sind rein und im Unit-Test belegt; die Ansichten hängt MainActivity nur ein.
 */
class ZusatzKacheln(
    private val ctx: Context,
    private val ui: Ui,
    private val reg: (() -> Unit) -> Unit,
    private val summary: () -> LauncherSummary?,
    private val historie: () -> LauncherHistory?
) {
    companion object {
        /** Warnungen, die der Kern liefern darf (ReifenWarnung im Kern); alles andere wird verworfen. */
        val WARNUNGEN = setOf("leck_schnell", "leck_langsam", "unterdruck", "ueberdruck", "signal")
        const val STRICH = "—"

        /** Warnung je Rad [VL, VR, HL, HR]: Kern-Warnung zuerst, sonst Druck außerhalb der Kern-Grenzen; sonst null. */
        fun warnungen(r: LauncherReifen?): List<String?> {
            if (r == null) return listOf(null, null, null, null)
            return listOf(r.vl, r.vr, r.hl, r.hr).mapIndexed { i, bar ->
                r.warn.getOrNull(i) ?: when {
                    bar != null && r.unten != null && bar < r.unten -> "unterdruck"
                    bar != null && r.oben != null && bar > r.oben -> "ueberdruck"
                    else -> null
                }
            }
        }

        /** Gesamtzustand: true = alle vier Drücke bekannt und keine Warnung, false = Warnung, null = unbekannt. */
        fun reifenOk(r: LauncherReifen?): Boolean? {
            if (r == null) return null
            if (warnungen(r).any { it != null }) return false
            return if (listOf(r.vl, r.vr, r.hl, r.hr).all { it != null }) true else null
        }

        /** Verbrauch in kWh/100 km aus Wh/km (Kern `whPerKm`); null bei fehlendem oder unplausiblem Wert. */
        fun kwhPro100(whPerKm: Double?): Double? = whPerKm?.takeIf { it > 0 && it < 1000 }?.let { it / 10.0 }

        /** Dauer „42 min“ bzw. „1 h 05 min“; null bei fehlendem Wert. */
        fun dauer(min: Double?): String? {
            val m = min?.takeIf { it >= 0 }?.let { Math.round(it) } ?: return null
            return if (m < 60) "$m min" else String.format(java.util.Locale.ROOT, "%d h %02d min", m / 60, m % 60)
        }

        /** Tempo als ganze Zahl; null = unbekannt (dann „—“). */
        fun tempo(kmh: Double?): String? = kmh?.takeIf { it >= 0 && it < 400 }?.let { Math.round(it).toString() }
    }

    private val nf1 = NumberFormat.getNumberInstance().apply { minimumFractionDigits = 1; maximumFractionDigits = 1 }

    private fun spalte() = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL; setPadding(ui.dp(20), ui.dp(18), ui.dp(20), ui.dp(18))
    }

    private fun kopf(icon: Int, titel: Int) = LinearLayout(ctx).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(ui.icon(icon, ui.p.aqua, 20))
        addView(ui.titel(ctx.getString(titel)).apply { setPadding(ui.dp(8), 0, 0, 0) })
    }

    private fun warnText(w: String): String = ctx.getString(when (w) {
        "leck_schnell" -> R.string.warn_leck_schnell; "leck_langsam" -> R.string.warn_leck_langsam
        "unterdruck" -> R.string.warn_unterdruck; "ueberdruck" -> R.string.warn_ueberdruck; else -> R.string.warn_signal
    })

    /**
     * Reifen: vier Drücke (bar), Warnfarbe je Rad, darunter die erste Warnung oder „Alles in Ordnung“.
     * 0.1.41 G5: Bei einer Zeile Höhe (2×1) stehen die vier Räder in einer Reihe, sonst 2 × 2; [Einpassen] verkleinert
     * den Rest, falls es trotzdem eng wird – so fehlt nie eine Zeile.
     */
    fun reifen(gross: Boolean, h: Int = 2): View {
        val c = spalte()
        c.addView(kopf(R.drawable.ic_pn_car, R.string.widget_reifendruck))
        val raster = GridLayout(ctx).apply { columnCount = if (h <= 1) 4 else 2 }
        val radNamen = intArrayOf(R.string.rad_vl, R.string.rad_vr, R.string.rad_hl, R.string.rad_hr)
        val werte = radNamen.map { n ->
            val z = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, ui.dp(4), ui.dp(16), 0) }
            z.addView(ui.leise(ctx.getString(n), 13f).apply { setPadding(0, 0, ui.dp(6), 0) })
            val t = ui.zahl(STRICH, if (gross) 26f else 20f); z.addView(t)
            raster.addView(z); t
        }
        c.addView(raster, ui.lp(oben = if (h <= 1) 2 else 6))
        val zustand = ui.leise("", 14f)
        c.addView(zustand, ui.lp(oben = 6))
        reg {
            val r = summary()?.reifen
            val w = warnungen(r)
            listOf(r?.vl, r?.vr, r?.hl, r?.hr).forEachIndexed { i, bar ->
                werte[i].text = bar?.let { nf1.format(it) } ?: STRICH
                werte[i].setTextColor(if (w[i] != null) ui.p.warning else ui.p.onSurface)
            }
            val erste = w.indexOfFirst { it != null }
            zustand.text = when {
                erste >= 0 -> ctx.getString(radNamen[erste]) + ": " + warnText(w[erste]!!)
                reifenOk(r) == true -> ctx.getString(R.string.reifen_ok)
                else -> STRICH
            }
            zustand.setTextColor(if (erste >= 0) ui.p.warning else ui.p.onSurfaceVariant)
        }
        return Einpassen(ctx, c)
    }

    /** Letzte Fahrt: Strecke groß, darunter Dauer und Verbrauch (kWh/100 km). */
    fun fahrt(gross: Boolean): View {
        val c = spalte()
        c.addView(kopf(R.drawable.ic_pn_route, R.string.widget_fahrt))
        val strecke = ui.zahl(STRICH, if (gross) 34f else 24f); c.addView(strecke, ui.lp(oben = 8))
        val info = ui.leise("", 14f); c.addView(info, ui.lp(oben = 4))
        reg {
            val h = historie()
            strecke.text = h?.lastTripKm?.let { nf1.format(it) + " km" } ?: STRICH
            val verbrauch = kwhPro100(h?.lastTripWhPerKm)?.let { nf1.format(it) + " kWh/100 km" } ?: STRICH
            info.text = (dauer(h?.lastTripMin) ?: STRICH) + " · " + verbrauch
        }
        return Einpassen(ctx, c)
    }

    /**
     * Tempo groß in km/h. Ohne Kern-Limit keine Limit-Warnung.
     * 0.1.41 G5: Bei einer Zeile Höhe (2×1) steht „km/h“ neben der Zahl statt darunter; [Einpassen] verkleinert,
     * falls es trotzdem eng wird – „km/h“ wird nie abgeschnitten.
     */
    fun tempo(gross: Boolean, h: Int = 2): View {
        val c = spalte().apply { gravity = Gravity.CENTER_HORIZONTAL }
        c.addView(kopf(R.drawable.ic_pn_nav, R.string.widget_tempo), LinearLayout.LayoutParams(-1, -2))
        val zahl: TextView = ui.zahl(STRICH, if (gross) 88f else if (h <= 1) 44f else 56f).apply { gravity = Gravity.CENTER }
        val einheit = ui.leise("km/h", 15f).apply { gravity = Gravity.CENTER }
        if (h <= 1) {
            c.addView(LinearLayout(ctx).apply {
                gravity = Gravity.CENTER
                addView(zahl); addView(einheit.apply { setPadding(ui.dp(8), 0, 0, 0) })
            }, ui.lp(-1, -2, oben = 2))
        } else {
            c.addView(zahl, ui.lp(-1, -2, oben = 4))
            c.addView(einheit, ui.lp(-1, -2))
        }
        // 0.1.44 (P3): Tempolimit-Schild nur mit belegtem, frischem Wert; leise Warnung (Farbe) nur wenn eingeschaltet.
        // Bei Kern-Aus hat der alte Stand kein Limit (Frische.veraltet), darum hier online = true.
        val schild = Tempolimit.schild(ctx, ui, if (gross) 56 else 42)
        c.addView(schild, LinearLayout.LayoutParams(schild.layoutParams).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = ui.dp(4) })
        reg {
            val s = summary()
            zahl.text = tempo(s?.speedKmh) ?: STRICH
            Tempolimit.setze(schild, zahl, s, true, ui.p.onSurface, ui.p.warning)
        }
        return Einpassen(ctx, c)
    }
}
