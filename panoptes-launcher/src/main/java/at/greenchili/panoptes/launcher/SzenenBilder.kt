package at.greenchili.panoptes.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * 0.1.43.4 (Rang 1): eigene gerenderte Szenen für die Fahrzeug-Startseite – Motive 15–20, je Tag und Nacht.
 * Selbst gebaut in Blender aus Grundkörpern, Rauschen und Himmelsmodell (`tools/render_launcher_szenen.py`):
 * keine Fremdbilder, keine KI-Bilder, keine fremden Texturen, kein Nachbau fremder Launcher-Szenen.
 * Tag/Nacht folgt dem Design (hell → Tag, sonst Nacht), damit „Tag/Nacht automatisch“ greift.
 * Unten bleibt die Bühne ruhig (dort steht das Auto), oben dunkelt ein leiser Verlauf ab, damit Karten lesbar bleiben.
 * Speicher: ein Bild gleichzeitig, RGB 565 (1600 × 1000 ≈ 3,2 MB), Laden im Hintergrund.
 */
object SzenenBilder {
    const val ERSTE = 15
    const val LETZTE = 20
    val NAMEN = listOf("studio", "stadt", "berge", "kueste", "tiefgarage", "ladepark")
    const val ORDNER = "szenen"

    fun ist(nr: Int?): Boolean = nr != null && nr in ERSTE..LETZTE
    fun datei(nr: Int, hell: Boolean): String = "$ORDNER/${NAMEN[nr - ERSTE]}_${if (hell) "tag" else "nacht"}.webp"

    /** Ausschnitt „cover“ [links, oben, rechts, unten]: Bild füllt die Fläche, mittig, unten bevorzugt (Bühne bleibt). */
    fun ausschnitt(bw: Int, bh: Int, w: Float, h: Float): IntArray {
        if (bw <= 0 || bh <= 0 || w <= 0f || h <= 0f) return intArrayOf(0, 0, bw, bh)
        val ziel = w / h
        val quelle = bw.toFloat() / bh
        return if (quelle > ziel) {
            val nb = (bh * ziel).toInt().coerceIn(1, bw)
            val x0 = (bw - nb) / 2
            intArrayOf(x0, 0, x0 + nb, bh)
        } else {
            val nh = (bw / ziel).toInt().coerceIn(1, bh)
            val y0 = ((bh - nh) * 0.7f).toInt()   // eher unten: Boden und Bühne bleiben im Bild
            intArrayOf(0, y0, bw, y0 + nh)
        }
    }

    @Volatile private var app: Context? = null

    /** Einmal beim Start (MainActivity), damit die Hintergründe ohne eigenen Kontext Bilder laden können. */
    fun anmelden(ctx: Context) { app = ctx.applicationContext }

    private val io by lazy { Executors.newSingleThreadExecutor() }
    private val haupt by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var aktuellName: String? = null
    @Volatile private var aktuell: Bitmap? = null
    @Volatile private var laedt: String? = null
    private val warten = mutableListOf<() -> Unit>()

    /** Fertiges Bild oder null (dann wird geladen und [neu] danach im Haupt-Thread gerufen). */
    fun bild(nr: Int, hell: Boolean, neu: () -> Unit): Bitmap? {
        val name = datei(nr, hell)
        if (name == aktuellName) aktuell?.let { return it }
        val ctx = app ?: return null
        synchronized(warten) { warten.add(neu) }
        if (laedt == name) return null
        laedt = name
        io.execute {
            val b = try {
                val opt = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                ctx.assets.open(name).use { BitmapFactory.decodeStream(it, null, opt) }
            } catch (_: Throwable) { null }
            haupt.post {
                if (b != null) { aktuell = b; aktuellName = name }   // altes Bild gibt der GC frei (kein recycle, könnte noch gezeichnet werden)
                if (laedt == name) laedt = null
                val liste = synchronized(warten) { val l = warten.toList(); warten.clear(); l }
                if (b != null) liste.forEach { it() }
            }
        }
        return null
    }

    private val pinsel by lazy { Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG) }
    private val schleier by lazy { Paint() }
    private val ziel by lazy { RectF() }

    /** Zeichnet die Szene; false, solange das Bild noch lädt (dann bleibt der Grundverlauf darunter sichtbar). */
    fun zeichne(c: Canvas, nr: Int, w: Float, h: Float, p: Palette, neu: () -> Unit): Boolean {
        val b = bild(nr, p.hell, neu) ?: return false
        ziel.set(0f, 0f, w, h)
        val a = ausschnitt(b.width, b.height, w, h)
        c.drawBitmap(b, Rect(a[0], a[1], a[2], a[3]), ziel, pinsel)
        // Oben leise abdunkeln (Karten, Uhr, Reiter), unten nichts: dort steht das Auto
        schleier.shader = LinearGradient(0f, 0f, 0f, h * 0.45f,
            intArrayOf(Farbe.argb(if (p.hell) 70 else 120, 0, 0, 0), Farbe.argb(0, 0, 0, 0)), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h * 0.45f, schleier)
        return true
    }
}
