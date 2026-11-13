package at.greenchili.panoptes.launcher

import android.content.Context
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * 0.1.44 (L1): Live-Umgebung – Skizze von oben um das eigene Auto aus der Kern-Erkennung (YOLO auf den vorhandenen
 * Kamerabildern). Kein Kamerabild, keine Warnung, kein Ton, nichts gespeichert. Ab Werk aus.
 *
 * Kern: `GET /api/launcher/v1/umgebung` (jede Abfrage = „Kachel sichtbar“), `POST … {an}` (Schalter).
 * Ohne frisches Ergebnis zeigt die Skizze KEINE Objekte und sagt es im Text – Leere sieht nie wie „frei“ aus,
 * weil das Auto dann gedimmt und der Status sichtbar ist.
 */
object Umgebung {
    data class Objekt(val seite: String, val quer: Int, val zone: String, val typ: String)
    data class Stand(val an: Boolean, val status: String, val hz: Double, val objekte: List<Objekt>)

    val SEITEN = setOf("front", "right", "rear", "left")
    val ZONEN = setOf("nah", "mittel", "fern")
    val TYPEN = setOf("auto", "lkw", "person", "rad")
    val STATUS = setOf("aus", "startet", "live", "verzoegert", "keine_erkennung", "leistung", "warm", "modell")
    const val TAKT_AN_MS = 250L
    const val TAKT_AUS_MS = 3_000L

    /** Kern-Antwort lesen; Unbekanntes fällt weg. Objekte nur bei live/verzoegert. */
    fun parse(json: String): Stand? = try {
        val o = JSONObject(json)
        val status = o.optString("status", "").takeIf { it in STATUS } ?: "keine_erkennung"
        val liste = ArrayList<Objekt>()
        if (status == "live" || status == "verzoegert") {
            val a = o.optJSONArray("objekte")
            if (a != null) for (i in 0 until minOf(a.length(), 24)) {
                val j = a.optJSONObject(i) ?: continue
                val s = j.optString("seite"); val z = j.optString("zone"); val t = j.optString("typ")
                val q = j.optInt("quer", 0).coerceIn(-1, 1)
                if (s in SEITEN && z in ZONEN && t in TYPEN) liste.add(Objekt(s, q, z, t))
            }
        }
        Stand(o.optBoolean("an", false), status, o.optDouble("hz", 0.0).takeIf { !it.isNaN() } ?: 0.0, liste)
    } catch (e: Exception) { null }

    fun schaltenBody(an: Boolean): String = JSONObject().put("an", an).toString()

    /** Zeigt die Skizze Objekte? Nur bei frischem Ergebnis. */
    fun zeigtObjekte(s: Stand?) = s != null && s.an && (s.status == "live" || s.status == "verzoegert")

    /**
     * Lage eines Objekts relativ zur Automitte in Einheiten u (Auto = 1,8u breit, 4u lang; y nach unten = hinten).
     * Kamera-Sicht → Fahrzeug-Sicht: vorne: Bild-links = Auto-links; rechts: Bild-links = vorne; hinten (Bild nicht
     * gespiegelt angenommen): Bild-links = Auto-rechts; links: Bild-links = hinten. Am Auto NICHT abgenommen.
     */
    fun lage(o: Objekt): FloatArray {
        val abstand = when (o.zone) { "nah" -> 1.2f; "mittel" -> 2.6f; else -> 4.0f }
        val quer = o.quer * 1.4f
        return when (o.seite) {
            "front" -> floatArrayOf(quer, -2f - abstand)
            "rear" -> floatArrayOf(-quer, 2f + abstand)
            "right" -> floatArrayOf(0.9f + abstand, quer)
            else -> floatArrayOf(-0.9f - abstand, -quer)
        }
    }

    /** Skizze zeichnen (Android und SVG). Ruhige Farben, keine Warnfarben. */
    fun zeichne(l: Leinwand, s: Stand?, p: Palette, b: Float, h: Float) {
        val cx = b / 2f; val cy = h / 2f
        val u = minOf(b / 11f, h / 13f)
        val linie = Farbe.alpha(p.onSurfaceVariant, 60)
        // Zonen-Ringe (nah / mittel), dezent
        l.rahmen(cx - 0.9f * u - 1.2f * u, cy - 2f * u - 1.2f * u, (1.8f + 2.4f) * u, (4f + 2.4f) * u, 1.2f * u, 2f, linie)
        l.rahmen(cx - 0.9f * u - 2.6f * u, cy - 2f * u - 2.6f * u, (1.8f + 5.2f) * u, (4f + 5.2f) * u, 2.2f * u, 1.5f, Farbe.alpha(linie, 35))
        // eigenes Auto
        val aktiv = zeigtObjekte(s)
        val auto = if (aktiv) p.aqua else Farbe.alpha(p.onSurfaceVariant, 110)
        l.rechteck(cx - 0.9f * u, cy - 2f * u, 1.8f * u, 4f * u, 0.5f * u, Voll(auto))
        l.rechteck(cx - 0.7f * u, cy - 1.1f * u, 1.4f * u, 0.8f * u, 0.2f * u, Voll(Farbe.alpha(p.background, 160)))
        if (!aktiv) return
        val farbeObj = p.onSurface
        for (o in s!!.objekte) {
            val xy = lage(o)
            val x = cx + xy[0] * u; val y = cy + xy[1] * u
            val deck = when (o.zone) { "nah" -> 235; "mittel" -> 185; else -> 130 }
            val f = Voll(Farbe.alpha(farbeObj, deck))
            when (o.typ) {
                "person" -> l.ellipse(x, y, 0.32f * u, 0.32f * u, f)
                "rad" -> l.ellipse(x, y, 0.25f * u, 0.5f * u, f)
                "lkw" -> l.rechteck(x - 0.6f * u, y - 1.2f * u, 1.2f * u, 2.4f * u, 0.2f * u, f)
                else -> l.rechteck(x - 0.55f * u, y - 0.95f * u, 1.1f * u, 1.9f * u, 0.35f * u, f)
            }
        }
    }

    /** Statuszeile (Text-Ressource). */
    fun statusText(s: Stand?): Int = when (s?.status) {
        null, "keine_erkennung" -> R.string.umgebung_keine
        "aus" -> R.string.umgebung_aus
        "startet" -> R.string.umgebung_startet
        "live" -> R.string.umgebung_live
        "verzoegert" -> R.string.umgebung_verzoegert
        "leistung" -> R.string.umgebung_leistung
        "warm" -> R.string.umgebung_warm
        else -> R.string.umgebung_modell
    }
}

/** Kachel „Umgebung“: Skizze, Statuszeile, Schalter. Fragt den Kern nur, solange die Kachel sichtbar ist. */
class UmgebungKachel(ctx: Context, private val ui: Ui, private val client: PanoptesClient) : FrameLayout(ctx) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var laeuft = false
    private var stand: Umgebung.Stand? = null
    private val skizze = object : View(ctx) {
        override fun onDraw(c: Canvas) {
            if (width > 0 && height > 0) Umgebung.zeichne(AndroidLeinwand(c), stand, ui.p, width.toFloat(), height.toFloat())
        }
    }
    private val text = TextView(ctx).apply {
        textSize = 14f; setTextColor(ui.p.onSurfaceVariant); gravity = Gravity.CENTER
    }
    private val knopf = TextView(ctx).apply {
        textSize = 15f; setTextColor(ui.p.onPrimary); gravity = Gravity.CENTER
        setPadding(28, 14, 28, 14)
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 40f; setColor(ui.p.lime) }
        setOnClickListener { schalten(stand?.an != true) }
    }

    init {
        addView(skizze, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val unten = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(text)
            addView(knopf, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 8 })
        }
        addView(unten, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = 12 })
        zeige(null)
        contentDescription = ctx.getString(R.string.widget_umgebung)
    }

    private fun zeige(s: Umgebung.Stand?) {
        stand = s
        val t = context.getString(Umgebung.statusText(s))
        text.text = if (s?.status == "verzoegert") String.format(t, String.format(java.util.Locale.ROOT, "%.1f", s.hz)) else t
        knopf.text = context.getString(if (s?.an == true) R.string.umgebung_ausschalten else R.string.umgebung_einschalten)
        knopf.visibility = if (s == null) View.GONE else View.VISIBLE
        skizze.invalidate()
    }

    private fun schalten(an: Boolean) {
        Thread {
            runCatching { client.umgebungSchalten(an) }
            val s = runCatching { client.umgebung() }.getOrNull()
            main.post { zeige(s) }
        }.apply { isDaemon = true }.start()
    }

    private fun starten() {
        if (laeuft) return
        laeuft = true
        Thread {
            while (laeuft) {
                val s = runCatching { client.umgebung() }.getOrNull()
                main.post { if (laeuft) zeige(s) }
                try { Thread.sleep(if (s?.an == true) Umgebung.TAKT_AN_MS else Umgebung.TAKT_AUS_MS) } catch (e: InterruptedException) { break }
            }
        }.apply { isDaemon = true; name = "UmgebungAbfrage" }.start()
    }

    private fun stoppen() { laeuft = false }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (windowVisibility == View.VISIBLE) starten() }
    override fun onDetachedFromWindow() { stoppen(); super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(v: Int) { super.onWindowVisibilityChanged(v); if (v == View.VISIBLE && isAttachedToWindow) starten() else stoppen() }
}
