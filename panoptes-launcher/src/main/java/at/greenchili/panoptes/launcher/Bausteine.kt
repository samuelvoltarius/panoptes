package at.greenchili.panoptes.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Bausteine im Panoptes-Stil (index.html Startseite: `.hc`, `.hc h2`, `.big`, `.muted`, `.act`, `.pn-pill`),
 * Schriften Sora (Überschriften/Zahlen) und Manrope (Text) wie die Weboberfläche.
 */
class Ui(val ctx: Context, val p: Palette, val cockpit: Boolean) {
    private val dichte = ctx.resources.displayMetrics.density
    fun dp(v: Int) = (v * dichte).toInt()
    fun dpf(v: Float) = v * dichte

    val titelFarbe get() = if (cockpit) PanoptesStil.COCKPIT_TITEL else p.outline

    fun sora(gewicht: Int) = schrift("fonts/sora.ttf", gewicht)
    fun manrope(gewicht: Int) = schrift("fonts/manrope.ttf", gewicht)

    private fun schrift(datei: String, gewicht: Int): Typeface = cache.getOrPut("$datei:$gewicht") {
        try {
            Typeface.Builder(ctx.assets, datei).setFontVariationSettings("'wght' $gewicht").build()
        } catch (_: Throwable) {
            Typeface.create(Typeface.SANS_SERIF, if (gewicht >= 600) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    /** `.hc`: Kartenfläche, Rand `--sm-line`, Radius 20 (Cockpit: eigene dichte Optik, Radius 14). */
    fun karte(hero: Boolean = false): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        if (cockpit) {
            cornerRadius = dpf(PanoptesStil.RADIUS_KARTE_COCKPIT.toFloat())
            orientation = GradientDrawable.Orientation.TL_BR
            colors = intArrayOf(PanoptesStil.COCKPIT_KARTE_VON, PanoptesStil.COCKPIT_KARTE_BIS)
            setStroke(dp(1), PanoptesStil.COCKPIT_RAND)
        } else {
            cornerRadius = dpf(PanoptesStil.RADIUS_KARTE.toFloat())
            if (hero) {
                orientation = GradientDrawable.Orientation.TL_BR
                colors = intArrayOf(p.heroVon, p.heroBis)
            } else setColor(p.card)
            setStroke(dp(1), p.line)
        }
    }

    /** `.hc h2`: Sora 13, 600, Großbuchstaben, Sperrung .08em, Farbe outline. */
    fun titel(text: String) = TextView(ctx).apply {
        this.text = text.uppercase(); textSize = 13f; typeface = sora(600); letterSpacing = 0.08f
        setTextColor(titelFarbe); maxLines = 1
    }

    /** Große Zahl wie `.big`/`.h-range` (Sora 700). */
    fun zahl(text: String, sp: Float) = TextView(ctx).apply {
        this.text = text; textSize = sp; typeface = sora(700); letterSpacing = -0.02f
        setTextColor(p.onSurface); includeFontPadding = false; maxLines = 1
    }

    fun text(text: String, sp: Float = 15f, gewicht: Int = 500, farbe: Int = p.onSurface) = TextView(ctx).apply {
        this.text = text; textSize = sp; typeface = manrope(gewicht); setTextColor(farbe)
    }

    /** `.muted`: on-surface-variant, 13. */
    fun leise(text: String, sp: Float = 13f) = text(text, sp, 500, p.onSurfaceVariant)

    fun icon(res: Int, farbe: Int = p.aqua, groesse: Int = 24) = ImageView(ctx).apply {
        setImageDrawable(ContextCompat.getDrawable(ctx, res)?.mutate()?.also { it.setTint(farbe) })
        layoutParams = LinearLayout.LayoutParams(dp(groesse), dp(groesse))
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    /** `.act`: Kachelknopf mit Icon (aqua) und Text, Radius 16; primary = Limette. Mindestens 64 dp hoch. */
    fun act(res: Int?, label: String?, primary: Boolean = false, klick: () -> Unit) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        minimumHeight = dp(64); minimumWidth = dp(64)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = GradientDrawable().apply {
            cornerRadius = dpf(PanoptesStil.RADIUS_ACT.toFloat())
            setColor(if (primary) p.lime else p.act)
            if (!primary) setStroke(dp(1), p.line)
        }
        res?.let { addView(icon(it, if (primary) p.onPrimary else p.aqua, 26)) }
        label?.let {
            addView(text(it, 13f, 600, if (primary) p.onPrimary else p.onSurface).apply {
                gravity = Gravity.CENTER; maxLines = 1
                if (res != null) setPadding(0, dp(4), 0, 0)
            })
        }
        isClickable = true; isFocusable = true
        setOnClickListener { klick() }
        foreground = welle()
    }

    /** `.pn-pill`: runde Plakette. */
    fun pill(text: String, aktiv: Boolean = false) = TextView(ctx).apply {
        this.text = text; textSize = 13f; typeface = sora(600); gravity = Gravity.CENTER
        setTextColor(if (aktiv) p.onPrimary else p.onSurfaceVariant)
        setPadding(dp(14), dp(6), dp(14), dp(6))
        background = GradientDrawable().apply {
            cornerRadius = dpf(999f); setColor(if (aktiv) p.lime else p.act); if (!aktiv) setStroke(dp(1), p.line)
        }
    }

    fun welle(): Drawable? {
        val tv = TypedValue()
        return if (ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) ContextCompat.getDrawable(ctx, tv.resourceId) else null
    }

    fun lp(b: Int = ViewGroup.LayoutParams.WRAP_CONTENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, gewicht: Float = 0f,
           oben: Int = 0, rechts: Int = 0) = LinearLayout.LayoutParams(b, h, gewicht).apply { topMargin = dp(oben); marginEnd = dp(rechts) }

    companion object { private val cache = HashMap<String, Typeface>() }
}

/** Seitenhintergrund wie `--sm-page-bg` (bzw. Cockpit-Hintergrund der Startansicht „Cockpit“). */
class SeitenHintergrund(private val p: Palette, private val cockpit: Boolean) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(c: Canvas) {
        val r = bounds; val w = r.width().toFloat(); val h = r.height().toFloat()
        if (cockpit) {
            // linear-gradient(145deg, …): von links oben nach rechts unten
            paint.shader = LinearGradient(0f, 0f, w, h, PanoptesStil.COCKPIT_SEITE, floatArrayOf(0f, 0.48f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(r, paint)
            paint.shader = RadialGradient(w * 0.48f, h * 0.34f, max(w, h) * 0.34f, PanoptesStil.COCKPIT_GLOW, 0, Shader.TileMode.CLAMP)
            c.drawRect(r, paint)
            return
        }
        // linear-gradient(160deg, von, bis 70%)
        val a = Math.toRadians(160.0 - 90.0)
        val dx = (cos(a) * w / 2).toFloat(); val dy = (sin(a) * h / 2).toFloat()
        paint.shader = LinearGradient(w / 2 - dx, h / 2 - dy, w / 2 + dx, h / 2 + dy, intArrayOf(p.seiteVon, p.seiteBis, p.seiteBis), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(r, paint)
        if (Color.alpha(p.glowA) > 0) {
            paint.shader = RadialGradient(w * 0.85f, -h * 0.10f, max(w, h) * 0.6f, p.glowA, 0, Shader.TileMode.CLAMP)
            c.drawRect(r, paint)
        }
        if (Color.alpha(p.glowB) > 0) {
            paint.shader = RadialGradient(-w * 0.10f, h * 1.10f, max(w, h) * 0.45f, p.glowB, 0, Shader.TileMode.CLAMP)
            c.drawRect(r, paint)
        }
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.OPAQUE
}

/** Ladestand als Pegel wie `.gauge` der Startseite (Form 90/90/34/34, Wasser mit Verlauf, Farbe nach Stand). */
class Pegel(ctx: Context, private val ui: Ui) : View(ctx) {
    var soc: Double? = null; set(v) { field = v; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = ui.sora(700) }
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val oben = w / 2; val unten = w * 0.19f
        val form = Path().apply {
            addRoundRect(RectF(0f, 0f, w, h), floatArrayOf(oben, oben, oben, oben, unten, unten, unten, unten), Path.Direction.CW)
        }
        c.save(); c.clipPath(form)
        paint.shader = null; paint.color = Color.argb(140, 4, 16, 27); c.drawRect(0f, 0f, w, h, paint)
        val stand = ((soc ?: 0.0).coerceIn(0.0, 100.0) / 100.0).toFloat()
        val farben = PanoptesStil.pegel(soc)
        val wasser = h * (1 - stand)
        paint.shader = LinearGradient(0f, wasser, 0f, h, farben[0], farben[1], Shader.TileMode.CLAMP)
        c.drawRect(0f, wasser, w, h, paint)
        c.restore()
        paint.shader = null; paint.style = Paint.Style.STROKE; paint.strokeWidth = ui.dpf(1f); paint.color = Color.argb(64, 95, 214, 230)
        c.drawPath(form, paint); paint.style = Paint.Style.FILL
        text.textSize = w * 0.27f
        c.drawText(soc?.let { "${Math.round(it)}%" } ?: "—", w / 2, h / 2 + text.textSize / 3, text)
    }
}

/** Raster für eine Startseite: Kinder bekommen [Zelle] als LayoutParams (x, y, Breite, Höhe in Zellen). */
class RasterLayout(ctx: Context, private val spalten: Int, private val zeilen: Int, private val luecke: Int) : ViewGroup(ctx) {
    class Zelle(val x: Int, val y: Int, val b: Int, val h: Int) : ViewGroup.LayoutParams(0, 0)
    val zellBreite get() = (width - paddingLeft - paddingRight - luecke * (spalten - 1)).toFloat() / spalten
    val zellHoehe get() = (height - paddingTop - paddingBottom - luecke * (zeilen - 1)).toFloat() / zeilen

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec); val h = MeasureSpec.getSize(hSpec)
        setMeasuredDimension(w, h)
        val zb = (w - paddingLeft - paddingRight - luecke * (spalten - 1)).toFloat() / spalten
        val zh = (h - paddingTop - paddingBottom - luecke * (zeilen - 1)).toFloat() / zeilen
        for (i in 0 until childCount) {
            val k = getChildAt(i); val z = k.layoutParams as? Zelle ?: continue
            k.measure(MeasureSpec.makeMeasureSpec((zb * z.b + luecke * (z.b - 1)).toInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((zh * z.h + luecke * (z.h - 1)).toInt(), MeasureSpec.EXACTLY))
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val zb = zellBreite; val zh = zellHoehe
        for (i in 0 until childCount) {
            val k = getChildAt(i); val z = k.layoutParams as? Zelle ?: continue
            val x = paddingLeft + (z.x * (zb + luecke)).toInt(); val y = paddingTop + (z.y * (zh + luecke)).toInt()
            k.layout(x, y, x + k.measuredWidth, y + k.measuredHeight)
        }
    }

    /** Zelle unter einem Punkt (für Ziehen im Bearbeiten-Modus), auf das Raster begrenzt. */
    fun zelleBei(px: Float, py: Float, b: Int, h: Int): Pair<Int, Int> {
        val x = ((px - paddingLeft) / (zellBreite + luecke)).toInt().coerceIn(0, spalten - b)
        val y = ((py - paddingTop) / (zellHoehe + luecke)).toInt().coerceIn(0, zeilen - h)
        return x to y
    }
}
