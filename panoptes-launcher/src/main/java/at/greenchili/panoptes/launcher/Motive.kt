package at.greenchili.panoptes.launcher

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 0.1.42 (L48, LUECKEN_0142.md Punkt 8): 10 eigene, mitgelieferte Hintergrund-Motive. Alle werden zur Laufzeit per
 * Code gezeichnet (Verläufe und geometrische Muster) – keine Bilddatei, keine fremde Grafik, keine KI-Grafik.
 * Grundlage ist immer der Seitenverlauf des aktuellen Designs ([SeitenHintergrund]); das Muster liegt mit wenig
 * Deckung in der Akzentfarbe darüber. Damit folgen die Motive Design, Akzent und „Tag/Nacht automatisch“, und die
 * Kacheln bleiben lesbar. Im Layout heißen sie „motiv-1“ … „motiv-10“.
 */
object Motive {
    /** 0.1.44 (D1): 11–14 = eigene Szenen ([Szenen]: Nachtstraße, Showroom-Halle, Bergstraße, Garage), ebenfalls per Code. */
    const val ANZAHL = 14
    val WERTE = (1..ANZAHL).map { "motiv-$it" }

    fun ist(w: String?): Boolean = w != null && w in WERTE
    fun nummer(w: String?): Int? = if (ist(w)) w!!.removePrefix("motiv-").toInt() else null

    /** Deckung des Musters: hell etwas kräftiger, damit es auf hellem Grund sichtbar bleibt; nie über 0,22. */
    fun deckung(hell: Boolean): Int = if (hell) 46 else 40   // von 255

    /**
     * 0.1.42 (E2E G6, Ruhe-Regel): Waben sind ein dichtes Netz aus Linien und wirkten im Nacht-Design (Bernstein auf
     * Dunkel) zu kräftig über den Kacheln. Darum leiser und dünner als die übrigen Motive.
     */
    fun wabenDeckung(hell: Boolean): Int = if (hell) 34 else 22   // von 255
    fun wabenStrich(e: Float): Float = max(1f, e * 0.22f)
}

class MotivHintergrund(private val nr: Int, private val p: Palette) : Drawable() {
    private val basis = SeitenHintergrund(p, false)
    private val strich = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val flaeche = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private fun farbe(a: Int = Motive.deckung(p.hell)) = Color.argb(a, Color.red(p.aqua), Color.green(p.aqua), Color.blue(p.aqua))

    override fun draw(c: Canvas) {
        basis.bounds = bounds
        basis.draw(c)
        val w = bounds.width().toFloat(); val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val e = min(w, h) / 100f   // Einheit
        strich.color = farbe(); strich.strokeWidth = max(1.5f, e * 0.35f); strich.shader = null
        flaeche.color = farbe(); flaeche.shader = null
        when (nr) {
            1 -> wellen(c, w, h, e)
            2 -> punkte(c, w, h, e)
            3 -> kreise(c, w, h, e)
            4 -> streifen(c, w, h, e)
            5 -> waben(c, w, h, e)
            6 -> horizont(c, w, h, e)
            7 -> hoehenlinien(c, w, h, e)
            8 -> dreiecke(c, w, h, e)
            9 -> boegen(c, w, h, e)
            10 -> sterne(c, w, h, e)
            else -> Szenen.zeichne(nr, AndroidLeinwand(c), w, h, p)
        }
    }

    private fun wellen(c: Canvas, w: Float, h: Float, e: Float) {
        val pfad = Path()
        var y = h * 0.35f
        var i = 0
        while (y < h * 1.05f) {
            pfad.reset(); pfad.moveTo(0f, y)
            var x = 0f
            while (x <= w) { pfad.lineTo(x, y + sin((x / w * 6.28f * 1.5f) + i * 0.6f) * e * 4f); x += e * 2f }
            c.drawPath(pfad, strich); y += e * 5f; i++
        }
    }

    private fun punkte(c: Canvas, w: Float, h: Float, e: Float) {
        val a = e * 6f; var y = a / 2
        while (y < h) { var x = a / 2; while (x < w) { c.drawCircle(x, y, e * 0.55f, flaeche); x += a }; y += a }
    }

    private fun kreise(c: Canvas, w: Float, h: Float, e: Float) {
        var r = e * 8f
        while (r < max(w, h) * 1.3f) { c.drawCircle(w * 0.92f, h * 0.08f, r, strich); r += e * 7f }
    }

    private fun streifen(c: Canvas, w: Float, h: Float, e: Float) {
        strich.strokeWidth = e * 2.2f
        var x = -h
        while (x < w) { c.drawLine(x, h, x + h, 0f, strich); x += e * 11f }
    }

    private fun waben(c: Canvas, w: Float, h: Float, e: Float) {
        val r = e * 7f; val dx = r * sqrt(3f); val dy = r * 1.5f
        strich.color = farbe(Motive.wabenDeckung(p.hell)); strich.strokeWidth = Motive.wabenStrich(e)
        val pfad = Path(); var zeile = 0; var y = 0f
        while (y < h + r) {
            var x = if (zeile % 2 == 0) 0f else dx / 2
            while (x < w + r) {
                pfad.reset()
                for (k in 0..6) {
                    val a = Math.toRadians((60.0 * k) - 30.0)
                    val px = x + (r * cos(a)).toFloat(); val py = y + (r * sin(a)).toFloat()
                    if (k == 0) pfad.moveTo(px, py) else pfad.lineTo(px, py)
                }
                c.drawPath(pfad, strich); x += dx
            }
            y += dy; zeile++
        }
    }

    private fun horizont(c: Canvas, w: Float, h: Float, e: Float) {
        flaeche.shader = RadialGradient(w * 0.5f, h * 0.78f, max(w, h) * 0.55f, farbe(70), 0, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, flaeche); flaeche.shader = null
        c.drawCircle(w * 0.5f, h * 0.78f, e * 14f, strich)
        var y = h * 0.78f; var abstand = e * 1.5f
        while (y < h) { c.drawLine(0f, y, w, y, strich); y += abstand; abstand *= 1.35f }
    }

    private fun hoehenlinien(c: Canvas, w: Float, h: Float, e: Float) {
        for (k in 1..14) {
            val rx = e * 7f * k; val ry = e * 4.5f * k
            val pfad = Path()
            var t = 0.0
            while (t <= 6.3) {
                val wob = 1f + 0.08f * sin(t * 3 + k).toFloat()
                val px = w * 0.25f + (rx * wob * cos(t)).toFloat(); val py = h * 0.7f + (ry * wob * sin(t)).toFloat()
                if (t == 0.0) pfad.moveTo(px, py) else pfad.lineTo(px, py)
                t += 0.1
            }
            pfad.close(); c.drawPath(pfad, strich)
        }
    }

    private fun dreiecke(c: Canvas, w: Float, h: Float, e: Float) {
        val s = e * 14f; val hh = s * 0.866f; var zeile = 0; var y = 0f
        while (y < h) {
            var x = if (zeile % 2 == 0) 0f else -s / 2
            var k = 0
            while (x < w + s) {
                // jedes zweite Dreieck gefüllt, mit wechselnder Deckung (eigenes „Low-Poly“)
                if ((k + zeile) % 3 == 0) {
                    flaeche.color = farbe(18 + ((k * 7 + zeile * 5) % 4) * 8)
                    val pfad = Path().apply { moveTo(x, y + hh); lineTo(x + s / 2, y); lineTo(x + s, y + hh); close() }
                    c.drawPath(pfad, flaeche)
                }
                x += s / 2; k++
            }
            y += hh; zeile++
        }
        flaeche.color = farbe()
    }

    private fun boegen(c: Canvas, w: Float, h: Float, e: Float) {
        strich.strokeWidth = e * 1.2f
        for (k in 0..9) {
            val r = e * (20f + k * 9f)
            c.drawArc(-r + w * 0.05f, h - r, r + w * 0.05f, h + r, 270f, 90f, false, strich)
        }
    }

    private fun sterne(c: Canvas, w: Float, h: Float, e: Float) {
        // feste Zahlenfolge (kein Zufall zur Laufzeit): immer das gleiche Bild
        var s = 1234567L
        repeat(180) {
            s = (s * 1103515245L + 12345L) and 0x7fffffffL; val x = (s % 10000) / 10000f * w
            s = (s * 1103515245L + 12345L) and 0x7fffffffL; val y = (s % 10000) / 10000f * h
            s = (s * 1103515245L + 12345L) and 0x7fffffffL; val r = e * (0.25f + (s % 100) / 100f * 0.6f)
            c.drawCircle(x, y, r, flaeche)
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}
