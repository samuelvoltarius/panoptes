package at.greenchili.panoptes.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * 0.1.44 (E2): Bedienung des freien Editors – Ziehen mit Einrasten und Hilfslinien, Größe am Griff ziehen, Aussehen
 * je Element. MainActivity hängt das nur ein; die Regeln stehen in [Frei], die Anzeige in [FreiLayout].
 * Bearbeiten gibt es nur im Stand (MainActivity prüft [Fahrsicherheit.bearbeitenErlaubt] und beendet den Modus beim
 * Losfahren).
 */
class FreiEditor(
    private val ctx: Context,
    private val ui: Ui,
    private val layout: () -> StartLayout,
    private val seite: () -> Int,
    private val raster: () -> FreiLayout?,
    private val linien: () -> HilfslinienAnsicht?,
    private val setze: (StartLayout) -> Unit,
    private val hinweis: (String) -> Unit,
    private val wischerHalten: (Boolean) -> Unit
) {
    /** Einrasten auf Viertelzellen und Nachbarkanten (abschaltbar). */
    var einrasten = true

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    /** Zielrechteck beim Ziehen: Lage aus der Pixelposition, gerastet und an Nachbarn ausgerichtet. */
    private fun ziel(e: Element, px: Float, py: Float, b: Int, h: Int): Pair<IntArray, Frei.Linien> {
        val r = raster() ?: return Frei.rect(e) to Frei.Linien(emptyList(), emptyList())
        val (gx, gy) = r.einheiten(px, py)
        val roh = intArrayOf(Frei.rasten(gx, einrasten), Frei.rasten(gy, einrasten), b, h)
        return Frei.hilfslinien(layout().seiten[seite()], roh, e.id, einrasten)
    }

    @SuppressLint("ClickableViewAccessibility")
    fun ziehen(box: View, e: Element, tipp: () -> Unit) = View.OnTouchListener { _, ev ->
        val r0 = Frei.rect(e)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { box.tag = floatArrayOf(ev.rawX, ev.rawY, 0f); box.parent.requestDisallowInterceptTouchEvent(true); wischerHalten(true) }
            MotionEvent.ACTION_MOVE -> {
                val st = box.tag as FloatArray
                val dx = ev.rawX - st[0]; val dy = ev.rawY - st[1]
                if (st[2] == 0f && (abs(dx) > slop || abs(dy) > slop)) { st[2] = 1f; box.elevation = ui.dpf(14f); box.alpha = 0.88f }
                if (st[2] == 1f) {
                    box.translationX = dx; box.translationY = dy
                    linien()?.setze(raster(), ziel(e, box.left + dx, box.top + dy, r0[2], r0[3]).second, einrasten)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                wischerHalten(false)
                val st = box.tag as? FloatArray
                linien()?.setze(raster(), Frei.Linien(emptyList(), emptyList()), false)
                if (st != null && st[2] == 1f) {
                    val (z, _) = ziel(e, box.left + box.translationX, box.top + box.translationY, r0[2], r0[3])
                    val neu = Frei.setze(layout(), e.id, z[0], z[1], z[2], z[3])
                    if (neu === layout()) hinweis(ctx.getString(R.string.kein_platz))
                    setze(neu)
                } else if (ev.actionMasked == MotionEvent.ACTION_UP) tipp()
            }
        }
        true
    }

    /** Größe am Griff unten rechts ziehen (Mindestgröße und Rand begrenzt); ein Tipp ruft [tipp]. */
    @SuppressLint("ClickableViewAccessibility")
    fun groesseZiehen(box: View, griff: View, e: Element, tipp: () -> Unit) = View.OnTouchListener { _, ev ->
        val r0 = Frei.rect(e)
        val ra = raster()
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { griff.tag = floatArrayOf(ev.rawX, ev.rawY, 0f); box.parent.requestDisallowInterceptTouchEvent(true); wischerHalten(true) }
            MotionEvent.ACTION_MOVE -> {
                val st = griff.tag as FloatArray
                val dx = ev.rawX - st[0]; val dy = ev.rawY - st[1]
                if (st[2] == 0f && (abs(dx) > slop || abs(dy) > slop)) st[2] = 1f
                if (st[2] == 1f && ra != null) {
                    box.scaleX = ((box.width + dx) / box.width).coerceAtLeast(0.2f); box.scaleY = ((box.height + dy) / box.height).coerceAtLeast(0.2f)
                    box.pivotX = 0f; box.pivotY = 0f
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                wischerHalten(false)
                val st = griff.tag as? FloatArray
                if (st != null && st[2] == 1f && ra != null) {
                    val dx = ev.rawX - st[0]; val dy = ev.rawY - st[1]
                    val b = Frei.rasten(r0[2] + Math.round(dx / ra.einheitX), einrasten)
                    val h = Frei.rasten(r0[3] + Math.round(dy / ra.einheitY), einrasten)
                    val neu = Frei.setze(layout(), e.id, r0[0], r0[1], b, h)
                    if (neu === layout()) hinweis(ctx.getString(R.string.kein_platz))
                    box.scaleX = 1f; box.scaleY = 1f
                    setze(neu)
                } else if (ev.actionMasked == MotionEvent.ACTION_UP) tipp()
            }
        }
        true
    }

    /** Menüpunkte „Aussehen“: Ecken, Deckung, Unschärfe, Akzent, zurück auf das Design. */
    fun stilPunkte(e: Element, auswahl: (String, List<Pair<String, () -> Unit>>, String?) -> Unit): List<Pair<String, () -> Unit>> {
        fun neu(ecke: Int? = e.ecke, deckung: Int? = e.deckung, unschaerfe: Int? = e.unschaerfe, akzent: String? = e.akzent) {
            setze(Frei.stil(layout(), e.id, ecke, deckung, unschaerfe, akzent))
            val d = Lesbarkeit.deckung(ui.p, (deckung ?: 60) + (unschaerfe ?: 0) * 8, Lesbarkeit.gruende(ui.p))
            if (deckung != null && d > deckung) hinweis(ctx.getString(R.string.stil_lesbar, d))
            if (akzent != null && !Lesbarkeit.akzentLesbar(ui.p, akzent, Lesbarkeit.karte(ui.p, ui.p.background, d)))
                hinweis(ctx.getString(R.string.stil_akzent_schwach))
        }
        fun haken(an: Boolean) = if (an) "✓ " else ""
        return listOf(
            ctx.getString(R.string.stil_ecken) to {
                auswahl(ctx.getString(R.string.stil_ecken), listOf(0, 12, 26, 40).map { v -> haken(e.ecke == v) + ctx.getString(R.string.stil_dp, v) to { neu(ecke = v) } }, null)
            },
            ctx.getString(R.string.stil_deckung) to {
                auswahl(ctx.getString(R.string.stil_deckung), listOf(100, 80, 60, 40).map { v -> haken(e.deckung == v) + "$v %" to { neu(deckung = v) } }, null)
            },
            ctx.getString(R.string.stil_unschaerfe) to {
                auswahl(ctx.getString(R.string.stil_unschaerfe), (0..Frei.UNSCHAERFE_MAX).map { v ->
                    haken((e.unschaerfe ?: 0) == v) + (if (v == 0) ctx.getString(R.string.aus) else ctx.getString(R.string.stil_stufe, v)) to { neu(unschaerfe = v) }
                }, ctx.getString(R.string.stil_unschaerfe_hinweis))
            },
            ctx.getString(R.string.stil_akzent) to {
                auswahl(ctx.getString(R.string.stil_akzent), Raster.AKZENTE.map { a -> haken(e.akzent == a) + a to { neu(akzent = a) } }, null)
            },
            ctx.getString(R.string.stil_wie_design) to { setze(Frei.stil(layout(), e.id, null, null, null, null)) }
        )
    }
}
