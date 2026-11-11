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
            SCHWACH -> MIN
            STARK -> maxOf(auto, STARK_DECKUNG)
            else -> auto
        }
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
