package at.greenchili.panoptes.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * 0.1.43.4 (Rang 4 „Fahrzeug antippen“): Tippzonen auf dem Fahrzeugbild.
 *
 * Fenster und Glasdach führen in den vorhandenen Fenster-Weg (nur im Stand, immer mit Rückfrage, der Kern prüft noch
 * einmal). **Türen, Kofferraum und Haube zeigen nur ihren Zustand** – kein Knopf, kein Kern-Aufruf; Bedienen kommt
 * erst nach Alfreds Wort. Nie Hupe, Blinker, Licht oder Alarm (Regel A+).
 *
 * Zonen kommen aus zwei Quellen, beide in Pixeln der Ansicht:
 * - gezeichnete Seitenansicht ([FahrzeugStart.zeichne], linke Seite zum Betrachter, Bug rechts): aus dem [FahrzeugStart.Profil];
 * - Renderbild mit Ankerpunkten (Rang 1, `FahrzeugBild.punkte`): eine Zone um jeden **sichtbaren** Punkt.
 *   Die Verbindung setzt der Integrator über [ankerQuelle]; ohne Anker gibt es auf einem Renderbild keine Zonen
 *   (die Fenster-Pillen darunter bleiben immer).
 * Jede Zone ist mindestens [MIN_DP] dp und beim Renderbild mindestens [ANTEIL_MIN] des Bildrahmens groß; bei Überlappung
 * gewinnt die Zone, deren Mitte dem Finger am nächsten liegt.
 */
object FahrzeugTippen {

    const val MIN_DP = 48
    const val ANTEIL_MIN = 0.12f

    enum class Teil(val id: String, val anker: String) {
        FENSTER_VL("vl", "fenster_vl"), FENSTER_HL("hl", "fenster_hl"), FENSTER_VR("vr", "fenster_vr"), FENSTER_HR("hr", "fenster_hr"),
        DACH("dach", "dach"),
        TUER_VL("lf", "tuer_vl"), TUER_HL("lr", "tuer_hl"), TUER_VR("rf", "tuer_vr"), TUER_HR("rr", "tuer_hr"),
        HAUBE("hood", "haube"), KOFFERRAUM("trunk", "kofferraum");

        /** Fenster und Dach: Rückfrage über den Fenster-Weg. Alles andere: nur Zustand. */
        val istFenster: Boolean get() = id in LauncherContract.FENSTER
    }

    /** Neutraler Ankerpunkt (Anteile 0..1 des Bildrahmens), damit dieses Paket ohne Rang 1 baut. */
    data class Punkt(val x: Float, val y: Float, val sichtbar: Boolean)

    data class Zone(val teil: Teil, val l: Float, val o: Float, val r: Float, val u: Float) {
        fun enthaelt(x: Float, y: Float) = x in l..r && y in o..u
        val mx: Float get() = (l + r) / 2f
        val my: Float get() = (o + u) / 2f
    }

    /** Vom Integrator gesetzt (Rang 1): Modell → Ankerpunkte nach Name, null = kein Renderbild mit Punkten. */
    @Volatile var ankerQuelle: ((String) -> Map<String, Punkt>?)? = null

    /** Welche Fenster es gibt (wie die Pillen): nur Fenster mit Kernwert, Dach nur mit Glasdach. */
    fun fensterDa(k: LauncherKoerper?): Set<String> = FahrzeugStart.knoepfe(k).toSet()

    private fun erlaubt(t: Teil, fenster: Set<String>) = !t.istFenster || t.id in fenster

    /** Auf mindestens [minB] × [minH] um die Mitte aufziehen. */
    private fun mindestens(z: Zone, minB: Float, minH: Float): Zone {
        val b = maxOf(z.r - z.l, minB); val h = maxOf(z.u - z.o, minH)
        return Zone(z.teil, z.mx - b / 2f, z.my - h / 2f, z.mx + b / 2f, z.my + h / 2f)
    }

    /**
     * Zonen der gezeichneten Seitenansicht in einer Fläche [w] × [h] (gleiche Lage wie [AutoAnsicht] ohne Bild:
     * Mitte w/2, h·0,5, Länge [FahrzeugStart.laenge]). Sichtbar ist die linke Seite: Fenster/Türen vorn und hinten
     * links, Haube, Kofferraum (Heck), Dach.
     */
    fun zonenSeite(f: FahrzeugStart.Form, w: Float, h: Float, fenster: Set<String>, minPx: Float): List<Zone> {
        val s = FahrzeugStart.laenge(w, h)
        if (s <= 0f) return emptyList()
        val pr = FahrzeugStart.profil(f)
        val cx = w / 2f; val boden = h * 0.50f + s * 0.17f
        fun sx(x: Float) = cx + (x - 0.5f) * s
        fun sy(y: Float) = boden - y * s
        val fy = pr.fenster.filterIndexed { i, _ -> i % 2 == 1 }
        val fUnten = fy.minOrNull() ?: 0.26f; val fOben = fy.maxOrNull() ?: 0.36f
        val dachY = pr.umriss.filterIndexed { i, _ -> i % 2 == 1 }.maxOrNull() ?: 0.4f
        fun z(t: Teil, x0: Float, x1: Float, y0: Float, y1: Float) = Zone(t, sx(x0), sy(y1), sx(x1), sy(y0))
        val roh = listOf(
            z(Teil.FENSTER_HL, pr.tuerHinten, pr.bSaeule, fUnten, fOben),
            z(Teil.FENSTER_VL, pr.bSaeule, pr.tuerVorn, fUnten, fOben),
            z(Teil.TUER_HL, pr.tuerHinten, pr.bSaeule, pr.radR, fUnten),
            z(Teil.TUER_VL, pr.bSaeule, pr.tuerVorn, pr.radR, fUnten),
            z(Teil.HAUBE, pr.haubeVon, 1.0f, pr.radR, fUnten),
            z(Teil.KOFFERRAUM, 0.0f, maxOf(pr.heckBis, pr.tuerHinten), pr.radR, dachY),
            z(Teil.DACH, pr.dachVon, pr.dachBis, fOben, dachY + 0.02f)
        )
        return roh.filter { erlaubt(it.teil, fenster) }.map { mindestens(it, minPx, minPx) }
    }

    /** Zonen um die sichtbaren Ankerpunkte eines Renderbilds; [rahmen] = [FahrzeugStart.bildRahmen] (x, y, b, h). */
    fun zonenAnker(anker: Map<String, Punkt>, rahmen: FloatArray, fenster: Set<String>, minPx: Float): List<Zone> {
        val groesse = maxOf(rahmen[2] * ANTEIL_MIN, minPx)
        return Teil.values().mapNotNull { t ->
            val p = anker[t.anker] ?: return@mapNotNull null
            if (!p.sichtbar || p.x !in 0f..1f || p.y !in 0f..1f || !erlaubt(t, fenster)) return@mapNotNull null
            val x = rahmen[0] + p.x * rahmen[2]; val y = rahmen[1] + p.y * rahmen[3]
            Zone(t, x - groesse / 2f, y - groesse / 2f, x + groesse / 2f, y + groesse / 2f)
        }
    }

    /** Getroffenes Teil: unter allen Zonen, die den Punkt enthalten, die mit der nächsten Mitte; sonst null. */
    fun treffer(zonen: List<Zone>, x: Float, y: Float): Teil? =
        zonen.filter { it.enthaelt(x, y) }.minByOrNull { (it.mx - x) * (it.mx - x) + (it.my - y) * (it.my - y) }?.teil

    /** Zustand eines Nicht-Fensters: true offen, false geschlossen, null unbekannt (nie geraten). */
    fun offen(t: Teil, k: LauncherKoerper?): Boolean? = k?.tueren?.get(t.id)
}

/**
 * Durchsichtige Schicht über [AutoAnsicht]: nimmt nur Tipps an, leuchtet die getroffene Zone kurz auf (keine
 * Daueranimation) und meldet das Teil. Die Zonen werden erst beim Tippen aus der aktuellen Größe berechnet.
 */
class TippSchicht(ctx: Context, private val ui: Ui) : View(ctx) {
    var zonen: (Float, Float) -> List<FahrzeugTippen.Zone> = { _, _ -> emptyList() }
    var getippt: (FahrzeugTippen.Teil) -> Unit = {}
    private var gedrueckt: FahrzeugTippen.Zone? = null
    private val pinsel = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rechteck = RectF()

    init { isClickable = true; contentDescription = ctx.getString(R.string.tippen_hinweis) }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val l = zonen(width.toFloat(), height.toFloat())
                val t = FahrzeugTippen.treffer(l, e.x, e.y) ?: return false   // daneben: nichts annehmen
                gedrueckt = l.firstOrNull { it.teil == t }; invalidate(); return true
            }
            MotionEvent.ACTION_UP -> {
                val z = gedrueckt; gedrueckt = null; invalidate()
                if (z != null && z.enthaelt(e.x, e.y)) { performClick(); getippt(z.teil) }
                return true
            }
            MotionEvent.ACTION_CANCEL -> { gedrueckt = null; invalidate(); return true }
        }
        return gedrueckt != null
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onDraw(c: Canvas) {
        val z = gedrueckt ?: return
        pinsel.style = Paint.Style.FILL; pinsel.color = Farbe.alpha(ui.p.aqua, 60)
        rechteck.set(z.l, z.o, z.r, z.u)
        c.drawRoundRect(rechteck, ui.dpf(12f), ui.dpf(12f), pinsel)
    }
}
