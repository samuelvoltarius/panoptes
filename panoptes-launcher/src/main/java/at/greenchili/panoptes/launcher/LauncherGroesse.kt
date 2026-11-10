package at.greenchili.panoptes.launcher

import android.content.Context
import android.content.res.Configuration

/**
 * 0.1.38.14 (P4, Seal U DM-i: „im Launcher ist alles zu groß, das Auto-Bild überdimensioniert“): Größe des Launchers.
 *
 * Gezeichnet ist der Launcher für 1280 × 720 dp (1920 × 1080 bei Dichte 1,5). Automatisch wird nur VERKLEINERT, wenn
 * der Bildschirm in dp kleiner ist (hohe Dichte, kleiner Bildschirm) – nie vergrößert, damit sich auf Autos, für die er
 * gebaut ist, nichts still ändert. Dazu wählt der Nutzer wie beim Tacho „Kleiner“ oder „Größer“. Umgesetzt über die
 * Dichte des Activity-Kontexts: alle dp/sp-Maße, Bilder und Dialoge skalieren gleich (eine Stelle).
 */
object LauncherGroesse {
    const val AUTO = "auto"
    const val KLEINER = "kleiner"
    const val GROESSER = "groesser"
    val WAHL = listOf(AUTO, KLEINER, GROESSER)

    const val BEZUG_KURZ_DP = 720f
    const val BEZUG_LANG_DP = 1280f
    const val MIN = 0.7f
    const val MAX = 1.3f

    private const val PREFS = "launcher_groesse"
    private const val KEY = "wahl"

    /** Automatischer Faktor aus der Bildschirmgröße in dp: höchstens 1 (nie größer als gezeichnet), mindestens [MIN]. */
    fun autoFaktor(breiteDp: Float, hoeheDp: Float): Float {
        if (breiteDp <= 0f || hoeheDp <= 0f) return 1f
        val kurz = minOf(breiteDp, hoeheDp)
        val lang = maxOf(breiteDp, hoeheDp)
        return minOf(kurz / BEZUG_KURZ_DP, lang / BEZUG_LANG_DP, 1f).coerceAtLeast(MIN)
    }

    /** Gesamtfaktor: Automatisch × Nutzerwahl (Kleiner 0,85 / Größer 1,15), begrenzt auf [MIN]..[MAX]. */
    fun faktor(wahl: String?, auto: Float): Float {
        val nutzer = when (wahl) { KLEINER -> 0.85f; GROESSER -> 1.15f; else -> 1f }
        return (auto * nutzer).coerceIn(MIN, MAX)
    }

    /** Neue Dichte (densityDpi) für den Faktor; nie unter 80 dpi. */
    fun dichteDpi(basisDpi: Int, faktor: Float): Int = maxOf(80, Math.round(basisDpi * faktor))

    fun wahl(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, AUTO)?.takeIf { it in WAHL } ?: AUTO

    fun speichern(ctx: Context, wahl: String) {
        if (wahl in WAHL) ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, wahl).apply()
    }

    /** Kontext mit angepasster Dichte (für attachBaseContext). Bei Faktor 1 unverändert. */
    fun anwenden(basis: Context): Context {
        return try {
            val dm = basis.resources.displayMetrics
            val auto = autoFaktor(dm.widthPixels / dm.density, dm.heightPixels / dm.density)
            val f = faktor(wahl(basis), auto)
            if (Math.abs(f - 1f) < 0.01f) return basis
            val conf = Configuration(basis.resources.configuration)
            conf.densityDpi = dichteDpi(conf.densityDpi, f)
            basis.createConfigurationContext(conf)
        } catch (_: Throwable) {
            basis
        }
    }
}
