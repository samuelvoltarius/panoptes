package at.greenchili.panoptes.launcher

import android.content.Context

/**
 * 0.1.44 (P1 „Bedienung 50+“, Punkt 2): Schalter „Große Bedienung“.
 *
 * Vergrößert Schrift (Text, Titel, Plaketten), Knöpfe, Dock und Leiste um [FAKTOR] und macht leise Beschriftungen
 * kontrastreich ([Ui.kontrast]). Getrennt von der Kachelgröße: Raster und Kachelmaße bleiben, ebenso große Zahlen in
 * den Kacheln ([Ui.zahl]), die schon groß sind und sonst aus der Kachel liefen. Auch getrennt von der
 * Launcher-Größe ([LauncherGroesse], skaliert alles über die Dichte). Ab Werk aus.
 */
object GrosseBedienung {
    const val FAKTOR = 1.25f
    private const val PREFS = "launcher"
    private const val KEY = "grosse_bedienung"

    fun an(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setzen(ctx: Context, an: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, an).apply()
    }

    /** Rein: Faktor für Bedienelemente. */
    fun faktor(an: Boolean): Float = if (an) FAKTOR else 1f

    fun faktor(ctx: Context): Float = faktor(an(ctx))
}
