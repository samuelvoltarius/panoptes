package at.greenchili.panoptes.launcher

import android.content.Context
import android.graphics.Bitmap

/**
 * 0.1.40 E2E-Befund M1: Über einem eigenen Hintergrundbild liegt immer ein Schleier in der Seitenfarbe des Designs,
 * damit Dock, Karten und Texte bei jedem Bild lesbar bleiben. „Automatisch“ richtet die Stärke nach der Bildhelligkeit:
 * Je weiter das Bild von der Seitenfarbe entfernt ist (helles Bild bei dunklem Design oder umgekehrt), desto dichter
 * wird der Schleier. Er ist nie schwächer als [MIN]. Die Wahl liegt nur im Launcher (wie das Bild selbst) und wird nie
 * exportiert. Die Rechenregeln sind rein und im Unit-Test belegt.
 */
object Schleier {
    const val AUTO = "auto"
    const val SCHWACH = "schwach"
    const val STARK = "stark"
    val WAHL = listOf(AUTO, SCHWACH, STARK)

    /** Untergrenze in jedem Fall: auch ein „passendes“ Bild bekommt eine feste Abdunkelung. */
    const val MIN = 0.35f
    const val MAX = 0.80f
    const val STARK_DECKUNG = 0.85f
    /** 0.1.41 (N1): Mindestdeckung für „Schwach“, wenn das Bild weit von der Seitenfarbe weg ist (helles Bild bei
     *  dunklem Design). Zwischen Abstand [N1_VON] und [N1_BIS] steigt sie von [MIN] auf [SCHWACH_MIN_HELL]. */
    const val SCHWACH_MIN_HELL = 0.60f
    const val N1_VON = 0.20f
    const val N1_BIS = 0.40f

    private const val PREFS = "launcher_schleier"
    private const val KEY = "wahl"

    /** Relative Helligkeit 0..1 einer Farbe (Rec. 709, ohne Gamma-Rechnung; reicht für die Stärke). */
    fun helligkeit(farbe: Int): Float =
        (0.2126f * ((farbe shr 16) and 0xFF) + 0.7152f * ((farbe shr 8) and 0xFF) + 0.0722f * (farbe and 0xFF)) / 255f

    /** Mittlere Helligkeit 0..1 einer Pixel-Liste; leer = 0,5 (unbekannt, mittlerer Schleier). */
    fun mittel(pixel: IntArray): Float = if (pixel.isEmpty()) 0.5f else pixel.fold(0f) { s, p -> s + helligkeit(p) } / pixel.size

    /** Deckung 0..1 des Schleiers für Bildhelligkeit, Seitenfarbe und Wahl. */
    fun deckung(bildHell: Float, seite: Int, wahl: String?): Float {
        val abstand = Math.abs(bildHell.coerceIn(0f, 1f) - helligkeit(seite))
        val auto = MIN + (MAX - MIN) * abstand
        return when (wahl) {
            SCHWACH -> schwach(abstand)
            STARK -> maxOf(auto, STARK_DECKUNG)
            else -> auto
        }
    }

    /**
     * 0.1.41 (E2E M4): Deckung nach Design. Wie [deckung], aber nie so schwach, dass die Textfarbe [text] des Designs
     * (die schwächere Schrift, `onSurfaceVariant`) auf Bild + Schleier unter [ZIEL_KONTRAST] fällt. Nacht-Design mit
     * hellem Bild deckt so etwa 93 % statt 60 % (vorher etwa 1,4:1). Gilt für jede Wahl, auch „Schwach“.
     */
    fun deckung(bildHell: Float, seite: Int, wahl: String?, text: Int): Float =
        maxOf(deckung(bildHell, seite, wahl), textDeckung(bildHell, seite, text))

    /** Mindestkontrast für Schrift direkt auf dem Hintergrund (WCAG AA, normaler Text). */
    const val ZIEL_KONTRAST = 4.5f
    /** Mehr deckt der Schleier nie; ein Rest Bild bleibt sichtbar. */
    const val TEXT_MAX = 0.95f

    /** Kleinste Deckung (in 1-%-Schritten bis [TEXT_MAX]), bei der [text] auf dem gemischten Grund [ZIEL_KONTRAST] erreicht. */
    fun textDeckung(bildHell: Float, seite: Int, text: Int): Float {
        var i = 0
        while (i < 95) {
            val d = i / 100f
            if (kontrast(text, mischen(bildHell, seite, d)) >= ZIEL_KONTRAST) return d
            i++
        }
        return TEXT_MAX
    }

    /** Grund aus Bild (als Grau mit der Bildhelligkeit) und Schleier in der Seitenfarbe, gemischt wie Android-Alpha. */
    fun mischen(bildHell: Float, seite: Int, deckung: Float): Int {
        val g = bildHell.coerceIn(0f, 1f) * 255f
        val d = deckung.coerceIn(0f, 1f)
        fun k(c: Int) = Math.round(g * (1 - d) + c * d).coerceIn(0, 255)
        return (0xFF shl 24) or (k((seite shr 16) and 0xFF) shl 16) or (k((seite shr 8) and 0xFF) shl 8) or k(seite and 0xFF)
    }

    /** Kontrastverhältnis nach WCAG (mit Gamma), 1..21. */
    fun kontrast(a: Int, b: Int): Float {
        val x = leucht(a)
        val y = leucht(b)
        return ((maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)).toFloat()
    }

    private fun lin(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
    }

    private fun leucht(f: Int): Double =
        0.2126 * lin((f shr 16) and 0xFF) + 0.7152 * lin((f shr 8) and 0xFF) + 0.0722 * lin(f and 0xFF)

    /** 0.1.41 (N1): „Schwach“ bleibt 35 %, solange Bild und Design ähnlich hell sind; bei hellem Bild auf dunklem Design
     *  (oder umgekehrt) nie unter [SCHWACH_MIN_HELL], damit Reiter, Gänge und Dock lesbar bleiben. */
    fun schwach(abstand: Float): Float {
        val t = ((abstand - N1_VON) / (N1_BIS - N1_VON)).coerceIn(0f, 1f)
        return MIN + (SCHWACH_MIN_HELL - MIN) * t
    }

    /** Schleierfarbe: Seitenfarbe des Designs mit der Deckung als Alpha. */
    fun farbe(seite: Int, deckung: Float): Int =
        (Math.round(deckung.coerceIn(0f, 1f) * 255) shl 24) or (seite and 0xFFFFFF)

    /** Bildhelligkeit über ein kleines Vorschaubild (32 × 18), damit es am Auto nichts kostet. */
    fun bildHelligkeit(bmp: Bitmap): Float = try {
        val klein = Bitmap.createScaledBitmap(bmp, 32, 18, true)
        val px = IntArray(32 * 18); klein.getPixels(px, 0, 32, 0, 0, 32, 18)
        if (klein !== bmp) klein.recycle()
        mittel(px)
    } catch (_: Throwable) { 0.5f }

    fun wahl(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, AUTO)?.takeIf { it in WAHL } ?: AUTO

    fun speichern(ctx: Context, wahl: String) {
        if (wahl in WAHL) ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, wahl).apply()
    }
}
