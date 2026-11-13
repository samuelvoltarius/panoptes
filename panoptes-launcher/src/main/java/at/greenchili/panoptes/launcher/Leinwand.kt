package at.greenchili.panoptes.launcher

import java.util.Locale

/**
 * 0.1.44 (D1): eine kleine Zeichen-Schnittstelle für die Fahrzeug-Ansicht und die Szenen. Zwei Umsetzungen: Android
 * ([AndroidLeinwand], auf dem Gerät) und SVG ([SvgLeinwand], für Vorschaubilder und Tests ohne Emulator). So zeigt die
 * Vorschau genau die Zeichnung, die auch im Launcher läuft. Farben sind ARGB-Ints, gerechnet ohne Android-Klassen.
 */
sealed class Fuellung
data class Voll(val argb: Int) : Fuellung()
/** Linearer Verlauf von (x1,y1) nach (x2,y2). */
class Linear(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val farben: IntArray, val stops: FloatArray) : Fuellung()
/** Radialer Verlauf, elliptisch (rx, ry). */
class Radial(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val farben: IntArray, val stops: FloatArray) : Fuellung()

interface Leinwand {
    fun rechteck(x: Float, y: Float, b: Float, h: Float, r: Float, f: Fuellung)
    fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, f: Fuellung)
    fun vieleck(p: FloatArray, f: Fuellung)
    fun linie(x1: Float, y1: Float, x2: Float, y2: Float, breite: Float, argb: Int)
    fun streckenzug(p: FloatArray, breite: Float, argb: Int)
    fun rahmen(x: Float, y: Float, b: Float, h: Float, r: Float, breite: Float, argb: Int)
    fun ellipseRahmen(cx: Float, cy: Float, rx: Float, ry: Float, breite: Float, f: Fuellung)
}

/** Reine Farbrechnung (ARGB), im Unit-Test nutzbar. */
object Farbe {
    fun a(c: Int) = (c ushr 24) and 0xFF
    fun r(c: Int) = (c shr 16) and 0xFF
    fun g(c: Int) = (c shr 8) and 0xFF
    fun b(c: Int) = c and 0xFF
    fun argb(a: Int, r: Int, g: Int, b: Int) = ((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
    fun rgb(v: Int) = (0xFF shl 24) or (v and 0xFFFFFF)
    fun alpha(c: Int, a: Int) = argb(a.coerceIn(0, 255), r(c), g(c), b(c))
    fun mix(c1: Int, c2: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        fun k(x: Int, y: Int) = Math.round(x + (y - x) * u)
        return argb(k(a(c1), a(c2)), k(r(c1), r(c2)), k(g(c1), g(c2)), k(b(c1), b(c2)))
    }
    fun dunkler(c: Int, t: Float) = mix(c, argb(a(c), 0, 0, 0), t)
    fun heller(c: Int, t: Float) = mix(c, argb(a(c), 255, 255, 255), t)
    fun css(c: Int): String = String.format(Locale.ROOT, "rgba(%d,%d,%d,%.3f)", r(c), g(c), b(c), a(c) / 255f)
    /** Lackfarbe aus dem Kern („#RRGGBB“), sonst null. */
    fun hex(s: String?): Int? = s?.takeIf { it.matches(Regex("^#[0-9A-Fa-f]{6}$")) }?.let { rgb(it.substring(1).toInt(16)) }
}

/** SVG-Umsetzung: sammelt Verläufe als <defs>. */
class SvgLeinwand(private val breite: Int, private val hoehe: Int) : Leinwand {
    private val defs = StringBuilder()
    private val body = StringBuilder()
    private var n = 0
    private fun z(v: Float) = String.format(Locale.ROOT, "%.1f", v)

    private fun fill(f: Fuellung): String = when (f) {
        is Voll -> Farbe.css(f.argb)
        is Linear -> {
            val id = "g" + (n++)
            defs.append("<linearGradient id=\"").append(id).append("\" gradientUnits=\"userSpaceOnUse\" x1=\"").append(z(f.x1))
                .append("\" y1=\"").append(z(f.y1)).append("\" x2=\"").append(z(f.x2)).append("\" y2=\"").append(z(f.y2)).append("\">")
            stops(f.farben, f.stops); defs.append("</linearGradient>")
            "url(#$id)"
        }
        is Radial -> {
            val id = "g" + (n++)
            val sy = if (f.rx > 0f) f.ry / f.rx else 1f
            defs.append("<radialGradient id=\"").append(id).append("\" gradientUnits=\"userSpaceOnUse\" cx=\"").append(z(f.cx))
                .append("\" cy=\"").append(z(f.cy)).append("\" r=\"").append(z(f.rx)).append("\" gradientTransform=\"translate(")
                .append(z(f.cx)).append(' ').append(z(f.cy)).append(") scale(1 ").append(String.format(Locale.ROOT, "%.4f", sy))
                .append(") translate(").append(z(-f.cx)).append(' ').append(z(-f.cy)).append(")\">")
            stops(f.farben, f.stops); defs.append("</radialGradient>")
            "url(#$id)"
        }
    }

    private fun stops(farben: IntArray, s: FloatArray) {
        for (i in farben.indices) defs.append("<stop offset=\"").append(String.format(Locale.ROOT, "%.3f", s[i]))
            .append("\" stop-color=\"").append(Farbe.css(Farbe.alpha(farben[i], 255))).append("\" stop-opacity=\"")
            .append(String.format(Locale.ROOT, "%.3f", Farbe.a(farben[i]) / 255f)).append("\"/>")
    }

    private fun punkte(p: FloatArray): String = buildString { var i = 0; while (i + 1 < p.size) { append(z(p[i])).append(',').append(z(p[i + 1])).append(' '); i += 2 } }

    override fun rechteck(x: Float, y: Float, b: Float, h: Float, r: Float, f: Fuellung) {
        val fl = fill(f)
        body.append("<rect x=\"").append(z(x)).append("\" y=\"").append(z(y)).append("\" width=\"").append(z(b)).append("\" height=\"")
            .append(z(h)).append("\" rx=\"").append(z(r)).append("\" fill=\"").append(fl).append("\"/>")
    }
    override fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, f: Fuellung) {
        val fl = fill(f)
        body.append("<ellipse cx=\"").append(z(cx)).append("\" cy=\"").append(z(cy)).append("\" rx=\"").append(z(rx)).append("\" ry=\"")
            .append(z(ry)).append("\" fill=\"").append(fl).append("\"/>")
    }
    override fun vieleck(p: FloatArray, f: Fuellung) {
        val fl = fill(f)
        body.append("<polygon points=\"").append(punkte(p)).append("\" fill=\"").append(fl).append("\"/>")
    }
    override fun linie(x1: Float, y1: Float, x2: Float, y2: Float, breite: Float, argb: Int) {
        body.append("<line x1=\"").append(z(x1)).append("\" y1=\"").append(z(y1)).append("\" x2=\"").append(z(x2)).append("\" y2=\"").append(z(y2))
            .append("\" stroke=\"").append(Farbe.css(argb)).append("\" stroke-width=\"").append(z(breite)).append("\" stroke-linecap=\"round\"/>")
    }
    override fun streckenzug(p: FloatArray, breite: Float, argb: Int) {
        body.append("<polyline points=\"").append(punkte(p)).append("\" fill=\"none\" stroke=\"").append(Farbe.css(argb))
            .append("\" stroke-width=\"").append(z(breite)).append("\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>")
    }
    override fun rahmen(x: Float, y: Float, b: Float, h: Float, r: Float, breite: Float, argb: Int) {
        body.append("<rect x=\"").append(z(x)).append("\" y=\"").append(z(y)).append("\" width=\"").append(z(b)).append("\" height=\"")
            .append(z(h)).append("\" rx=\"").append(z(r)).append("\" fill=\"none\" stroke=\"").append(Farbe.css(argb))
            .append("\" stroke-width=\"").append(z(breite)).append("\"/>")
    }
    override fun ellipseRahmen(cx: Float, cy: Float, rx: Float, ry: Float, breite: Float, f: Fuellung) {
        val fl = fill(f)
        body.append("<ellipse cx=\"").append(z(cx)).append("\" cy=\"").append(z(cy)).append("\" rx=\"").append(z(rx)).append("\" ry=\"")
            .append(z(ry)).append("\" fill=\"none\" stroke=\"").append(fl).append("\" stroke-width=\"").append(z(breite)).append("\"/>")
    }

    /** Roh-SVG einfügen (nur für Vorschau-Zusammenstellungen im Test). */
    fun roh(s: String) { body.append(s) }

    fun svg(): String = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"$breite\" height=\"$hoehe\" viewBox=\"0 0 $breite $hoehe\"><defs>" +
        defs + "</defs>" + body + "</svg>"
}

/** Android-Umsetzung auf einem Canvas. */
class AndroidLeinwand(private val c: android.graphics.Canvas) : Leinwand {
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val r = android.graphics.RectF()
    private val pfad = android.graphics.Path()

    private fun setze(f: Fuellung) {
        p.shader = null
        when (f) {
            is Voll -> p.color = f.argb
            is Linear -> { p.color = -0x1; p.shader = android.graphics.LinearGradient(f.x1, f.y1, f.x2, f.y2, f.farben, f.stops, android.graphics.Shader.TileMode.CLAMP) }
            is Radial -> {
                p.color = -0x1
                val s = android.graphics.RadialGradient(f.cx, f.cy, maxOf(f.rx, 0.01f), f.farben, f.stops, android.graphics.Shader.TileMode.CLAMP)
                if (f.rx > 0f && f.ry != f.rx) s.setLocalMatrix(android.graphics.Matrix().apply { setScale(1f, f.ry / f.rx, f.cx, f.cy) })
                p.shader = s
            }
        }
    }

    private fun fuell(f: Fuellung) { p.style = android.graphics.Paint.Style.FILL; setze(f) }
    private fun strich(breite: Float, argb: Int) {
        p.style = android.graphics.Paint.Style.STROKE; p.shader = null; p.color = argb; p.strokeWidth = breite
        p.strokeCap = android.graphics.Paint.Cap.ROUND; p.strokeJoin = android.graphics.Paint.Join.ROUND
    }
    private fun pfadAus(pt: FloatArray, zu: Boolean) {
        pfad.reset(); var i = 0
        while (i + 1 < pt.size) { if (i == 0) pfad.moveTo(pt[0], pt[1]) else pfad.lineTo(pt[i], pt[i + 1]); i += 2 }
        if (zu) pfad.close()
    }

    override fun rechteck(x: Float, y: Float, b: Float, h: Float, r: Float, f: Fuellung) { fuell(f); this.r.set(x, y, x + b, y + h); c.drawRoundRect(this.r, r, r, p) }
    override fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, f: Fuellung) { fuell(f); r.set(cx - rx, cy - ry, cx + rx, cy + ry); c.drawOval(r, p) }
    override fun vieleck(p: FloatArray, f: Fuellung) { fuell(f); pfadAus(p, true); c.drawPath(pfad, this.p) }
    override fun linie(x1: Float, y1: Float, x2: Float, y2: Float, breite: Float, argb: Int) { strich(breite, argb); c.drawLine(x1, y1, x2, y2, p) }
    override fun streckenzug(p: FloatArray, breite: Float, argb: Int) { strich(breite, argb); pfadAus(p, false); c.drawPath(pfad, this.p) }
    override fun rahmen(x: Float, y: Float, b: Float, h: Float, r: Float, breite: Float, argb: Int) { strich(breite, argb); this.r.set(x, y, x + b, y + h); c.drawRoundRect(this.r, r, r, p) }
    override fun ellipseRahmen(cx: Float, cy: Float, rx: Float, ry: Float, breite: Float, f: Fuellung) {
        p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = breite; setze(f)
        r.set(cx - rx, cy - ry, cx + rx, cy + ry); c.drawOval(r, p)
    }
}
