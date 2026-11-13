package at.greenchili.panoptes.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup

/**
 * 0.1.44 (E2): Seite im Feinraster (48 × 24, [Frei]). Eine Einheit ist eine Achtelzelle samt anteiliger Lücke; so
 * liegen Elemente auf ganzen Zellen genau wie im alten Raster, frei gesetzte dazwischen.
 */
class FreiLayout(ctx: Context, private val luecke: Int) : ViewGroup(ctx) {
    class Fein(val x: Int, val y: Int, val b: Int, val h: Int) : ViewGroup.LayoutParams(0, 0)

    /** Breite bzw. Höhe einer Einheit in Pixeln (Zelle + Lücke, durch 8). */
    val einheitX get() = ((width - paddingLeft - paddingRight + luecke).toFloat() / Raster.SPALTEN) / Frei.FEIN
    val einheitY get() = ((height - paddingTop - paddingBottom + luecke).toFloat() / Raster.ZEILEN) / Frei.FEIN

    private fun ex(w: Int) = ((w - paddingLeft - paddingRight + luecke).toFloat() / Raster.SPALTEN) / Frei.FEIN
    private fun ey(h: Int) = ((h - paddingTop - paddingBottom + luecke).toFloat() / Raster.ZEILEN) / Frei.FEIN

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec); val h = MeasureSpec.getSize(hSpec)
        setMeasuredDimension(w, h)
        val ux = ex(w); val uy = ey(h)
        for (i in 0 until childCount) {
            val k = getChildAt(i); val f = k.layoutParams as? Fein ?: continue
            k.measure(MeasureSpec.makeMeasureSpec(maxOf(1, (f.b * ux - luecke).toInt()), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(maxOf(1, (f.h * uy - luecke).toInt()), MeasureSpec.EXACTLY))
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val ux = einheitX; val uy = einheitY
        for (i in 0 until childCount) {
            val k = getChildAt(i); val f = k.layoutParams as? Fein ?: continue
            val x = paddingLeft + (f.x * ux).toInt(); val y = paddingTop + (f.y * uy).toInt()
            k.layout(x, y, x + k.measuredWidth, y + k.measuredHeight)
        }
    }

    /** Pixel → Einheiten (gerundet). */
    fun einheiten(px: Float, py: Float): Pair<Int, Int> =
        Math.round((px - paddingLeft) / einheitX) to Math.round((py - paddingTop) / einheitY)
}

/** Hilfslinien beim Ziehen (über der Seite, nicht berührbar). */
class HilfslinienAnsicht(ctx: Context, private val ui: Ui) : View(ctx) {
    private var linien = Frei.Linien(emptyList(), emptyList())
    private var raster: FreiLayout? = null
    private var feinZeigen = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setze(r: FreiLayout?, l: Frei.Linien, fein: Boolean) { raster = r; linien = l; feinZeigen = fein; invalidate() }

    override fun onDraw(c: Canvas) {
        val r = raster ?: return
        val ux = r.einheitX; val uy = r.einheitY
        if (feinZeigen) {
            p.color = Farbe.alpha(ui.p.onSurfaceVariant, 40); p.strokeWidth = 1f
            var x = 0; while (x <= Frei.BREITE) { val px = r.left + r.paddingLeft + x * ux; c.drawLine(px, r.top.toFloat(), px, r.bottom.toFloat(), p); x += Frei.RASTER_SCHRITT }
            var y = 0; while (y <= Frei.HOEHE) { val py = r.top + r.paddingTop + y * uy; c.drawLine(r.left.toFloat(), py, r.right.toFloat(), py, p); y += Frei.RASTER_SCHRITT }
        }
        p.color = ui.p.lime; p.strokeWidth = ui.dpf(2f)
        linien.senkrecht.forEach { x -> val px = r.left + r.paddingLeft + x * ux; c.drawLine(px, r.top.toFloat(), px, r.bottom.toFloat(), p) }
        linien.waagrecht.forEach { y -> val py = r.top + r.paddingTop + y * uy; c.drawLine(r.left.toFloat(), py, r.right.toFloat(), py, p) }
    }
}

/** Stil je Element anwenden (Ecken, Deckung, „Unschärfe“, Akzent) – nur wenn etwas gesetzt ist. */
object Stil {
    fun gesetzt(e: Element) = e.ecke != null || e.deckung != null || e.unschaerfe != null || e.akzent != null

    /**
     * Kartengrund mit eigener Deckung und Ecke, Rand in der Akzentfarbe. Unschärfe: Android 10 kann den Hintergrund
     * nicht live weichzeichnen (RenderEffect erst ab Android 12); jede Stufe macht die Karte darum etwas dichter und
     * legt einen weichen Schleier an, damit Unruhiges dahinter zurücktritt. Die Deckung wird für lesbaren Text
     * angehoben ([Lesbarkeit.deckung]).
     */
    fun karte(e: Element, ui: Ui): Drawable = GradientDrawable().apply {
        cornerRadius = ui.dpf((e.ecke ?: 26).toFloat())
        val basis = if (ui.p.hell) Farbe.rgb(0xFFFFFF) else Farbe.rgb(0x0A1824)
        val wunsch = ((e.deckung ?: 60) + (e.unschaerfe ?: 0) * 8).coerceAtMost(100)
        val d = Lesbarkeit.deckung(ui.p, wunsch, Lesbarkeit.gruende(ui.p))
        val oben = Farbe.alpha(Farbe.heller(basis, 0.04f * (e.unschaerfe ?: 0)), d * 255 / 100)
        orientation = GradientDrawable.Orientation.TOP_BOTTOM
        colors = intArrayOf(oben, Farbe.alpha(basis, d * 255 / 100))
        val akzent = Farbe.hex(e.akzent)?.takeIf { Lesbarkeit.akzentLesbar(ui.p, e.akzent, Lesbarkeit.karte(ui.p, ui.p.background, d)) }
        setStroke(ui.dp(if (akzent != null) 2 else 1), akzent ?: Farbe.alpha(if (ui.p.hell) ui.p.onSurface else Farbe.rgb(0xFFFFFF), 38))
    }
}
