package at.greenchili.panoptes.launcher

import android.content.Context
import android.view.View
import android.widget.FrameLayout

/**
 * 0.1.41 E2E-Befund G5: Hülle für Kachel-Inhalte, die nie etwas abschneidet. Passt der Inhalt bei voller Breite
 * nicht in die Kachelhöhe (kleine Kachel, hohe Dichte, lange Übersetzung), wird er als Ganzes verkleinert – Schrift
 * und Abstände gleichmäßig – und auf der verkleinerten Breite neu umbrochen, damit er die Kachel wieder ganz füllt.
 * Nie kleiner als [MIN]; die Rechenregel ist rein und im Unit-Test belegt.
 */
class Einpassen(ctx: Context, inhalt: View) : FrameLayout(ctx) {
    companion object {
        const val MIN = 0.55f

        /** Maßstab 0..1: 1 = passt; sonst verfügbar / benötigt, nie unter [MIN]. Unbekannte Größen = 1. */
        fun massstab(benoetigt: Int, verfuegbar: Int): Float =
            if (benoetigt <= 0 || verfuegbar <= 0 || benoetigt <= verfuegbar) 1f
            else maxOf(MIN, verfuegbar.toFloat() / benoetigt)
    }

    private var s = 1f

    init { addView(inhalt) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val kind = getChildAt(0)
        val frei = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        kind.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), frei)
        s = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) 1f else massstab(kind.measuredHeight, h)
        if (s < 1f) {
            kind.measure(MeasureSpec.makeMeasureSpec(Math.round(w / s), MeasureSpec.EXACTLY), frei)
            s = minOf(s, massstab(kind.measuredHeight, h))
        }
        val hoehe = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) kind.measuredHeight else h
        setMeasuredDimension(w, hoehe)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val kind = getChildAt(0)
        kind.layout(0, 0, kind.measuredWidth, kind.measuredHeight)
        kind.pivotX = 0f; kind.pivotY = 0f
        kind.scaleX = s; kind.scaleY = s
    }
}
