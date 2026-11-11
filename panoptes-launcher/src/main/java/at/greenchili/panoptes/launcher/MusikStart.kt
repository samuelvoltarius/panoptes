package at.greenchili.panoptes.launcher

import android.content.Context
import android.os.Handler

/**
 * 0.1.41 (LM): „Musik beim Start weiterspielen“, ab Werk AUS, lokal im Launcher gespeichert.
 * Merkt sich das zuletzt spielende Paket und ruft beim Launcher-Start höchstens einmal je Prozess
 * [Weiterspielen.entscheiden] auf: play() auf dessen Sitzung, sonst nur die App öffnen. Kein Fahrzeugbefehl.
 */
object MusikStart {
    private const val PREFS = "launcher"
    const val KEY_AN = "musik_weiterspielen"
    const val KEY_LETZTES = "musik_letztes_paket"
    @Volatile private var geprueft = false

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun an(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_AN, false)
    fun umschalten(ctx: Context) { prefs(ctx).edit().putBoolean(KEY_AN, !an(ctx)).apply() }
    fun letztes(ctx: Context): String? = prefs(ctx).getString(KEY_LETZTES, null)

    /** Aus dem Medientakt: nur bei Wechsel schreiben. */
    fun gesehen(ctx: Context, paket: String?, spielt: Boolean) {
        val p = Weiterspielen.merken(paket, spielt) ?: return
        if (p != letztes(ctx)) prefs(ctx).edit().putString(KEY_LETZTES, p).apply()
    }

    fun beimStart(ctx: Context, main: Handler, appDa: (String) -> Boolean, oeffnen: (String) -> Unit) {
        if (geprueft) return
        geprueft = true
        if (!an(ctx)) return
        val app = ctx.applicationContext
        fun versuch(n: Int) {
            val letztes = letztes(app)
            when (val a = Weiterspielen.entscheiden(an(app), letztes, MedienQuelle.ueberblick(app) ?: emptyMap(), n,
                letztes != null && appDa(letztes))) {
                is Weiterspielen.Aktion.Spielen -> MedienQuelle.spielen(app, a.paket)
                is Weiterspielen.Aktion.Oeffnen -> oeffnen(a.paket)
                Weiterspielen.Aktion.Warten -> main.postDelayed({ versuch(n + 1) }, Weiterspielen.ABSTAND_MS)
                Weiterspielen.Aktion.Nichts -> Unit
            }
        }
        main.postDelayed({ versuch(1) }, Weiterspielen.ABSTAND_MS)
    }
}
