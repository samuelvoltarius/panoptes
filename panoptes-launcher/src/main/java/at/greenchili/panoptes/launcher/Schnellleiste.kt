package at.greenchili.panoptes.launcher

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * 0.1.41 (LO, Punkt 2): schmale Leiste über allen Apps mit Haus und Lautstärke −/+, damit man aus jeder App sicher
 * zurückfindet. Baut auf der Overlay-Technik der 0.1.39-Statusleiste auf (TYPE_APPLICATION_OVERLAY).
 *
 * - Nur mit dem Recht „Über anderen Apps einblenden“, das der Nutzer selbst in den Android-Einstellungen erteilt
 *   (der Launcher öffnet nur die Seite). Nie über den Daemon oder einen Rechte-Befehl.
 * - Abschaltbar (Standard aus), Position rechts, links oder oben. 0.1.41 E2E (G6): Knöpfe mindestens 64 dp (Zielgruppe
 *   50+); seitlich steht sie zuerst zur Wahl, weil oben die Reiter vieler Apps liegen (oben bleibt wählbar, mit Hinweis).
 * - Nicht über dem Launcher selbst und nie im Rückwärtsgang (Rückfahrkamera bleibt frei).
 * - 0.1.44 (P1): „Zurück“ und „Letzte App“ über [Tasten] (Bedienungshilfe, die der Nutzer selbst einschaltet, sonst
 *   Kern-Taste auf Tipp); ohne beide Wege bleiben die zwei Knöpfe weg.
 */
class Schnellleiste(private val ctx: Context, private val ui: Ui) {

    companion object {
        const val PREFS = "launcher"
        const val KEY = "schnellleiste"
        const val AUS = "aus"
        const val OBEN = "oben"
        const val LINKS = "links"
        const val RECHTS = "rechts"
        /** Reihenfolge in der Auswahl: seitlich zuerst (G6), oben zuletzt, weil es App-Reiter verdecken kann. */
        val WAHL = listOf(AUS, RECHTS, LINKS, OBEN)

        /** G6: Knopfmaß für Fahrer 50+ (mindestens 64 dp hoch, etwas breiter als hoch). */
        const val KNOPF_DP = 64
        const val KNOPF_BREITE_DP = 72

        /** L1: eigene Lautstärke-Anzeige in der Leiste, so lange sichtbar (ms). */
        const val ANZEIGE_MS = 1_500L
        const val BALKEN_DP = 56

        /** Rein (L1): Lautstärke in Prozent 0..100; ohne gültiges Maximum 0. */
        fun prozent(wert: Int, max: Int): Int = if (max <= 0) 0 else (wert * 100 / max).coerceIn(0, 100)

        fun wahl(ctx: Context): String =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, AUS)?.takeIf { it in WAHL } ?: AUS

        fun setzen(ctx: Context, w: String) {
            if (w in WAHL) ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, w).apply()
        }

        /** Rein: Leiste sichtbar? Schalter an, Recht erteilt, Launcher nicht vorn, nicht im Rückwärtsgang. */
        fun zeigen(wahl: String, recht: Boolean, launcherVorn: Boolean, gear: String?): Boolean =
            wahl != AUS && wahl in WAHL && recht && !launcherVorn && gear != "R"
    }

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val client = PanoptesClient()
    private var ansicht: View? = null
    private var gebautFuer: String? = null
    @Volatile private var launcherVorn = true
    @Volatile private var laeuft = false
    private var anzeige: View? = null
    private var anzeigeText: TextView? = null
    private var anzeigeFuellung: View? = null
    private val anzeigeWeg = Runnable { anzeige?.visibility = View.INVISIBLE }

    // Gang alle 2 s (wie die Statusleiste): Im Rückwärtsgang verschwindet die Leiste sofort.
    private val takt = object : Runnable {
        override fun run() {
            if (!laeuft) return
            io.execute {
                val s = runCatching { client.summary() }.getOrNull()
                Tasten.kernDa = s != null   // 0.1.44 (P1): Weg „Daemon“ für Zurück/Letzte App
                main.post { if (laeuft) anwenden(s?.gear) }
            }
            main.postDelayed(this, 2_000)
        }
    }

    /** Launcher kommt nach vorn (onStart) bzw. geht weg (onStop). */
    fun launcherVorn(vorn: Boolean) {
        launcherVorn = vorn
        if (wahl(ctx) == AUS) { aus(); return }
        if (!laeuft) { laeuft = true; main.post(takt) } else anwenden(null)
    }

    fun aus() {
        laeuft = false
        main.removeCallbacks(takt)
        entfernen()
    }

    private fun anwenden(gear: String?) {
        val w = wahl(ctx)
        if (!zeigen(w, Settings.canDrawOverlays(ctx), launcherVorn, gear)) { entfernen(); return }
        // 0.1.44 (P1): neu bauen, wenn Position, „Große Bedienung“ oder der Weg für Zurück/Letzte App wechselt
        val schluessel = "$w:${GrosseBedienung.faktor(ctx)}:${Tasten.wegJetzt() != Tasten.Weg.KEINER}"
        if (ansicht != null && gebautFuer == schluessel) return
        entfernen()
        bauen(w)
        if (ansicht != null) gebautFuer = schluessel
    }

    private fun bauen(w: String) {
        val quer = w == OBEN
        // 0.1.44 (P1): „Große Bedienung“ vergrößert die Leiste um den gleichen Faktor wie Dock und Knöpfe
        val f = GrosseBedienung.faktor(ctx)
        fun m(v: Int) = ui.dp(Math.round(v * f))
        val z = LinearLayout(ctx).apply {
            orientation = if (quer) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
            background = GradientDrawable().apply {
                setColor(ui.p.surface); setStroke(ui.dp(1), ui.p.line); cornerRadius = ui.radius(Mass.RADIUS_MITTEL)
            }
        }
        fun knopf(res: Int, label: Int, f: () -> Unit) = z.addView(ui.act(res, null) { f() }.apply {
            minimumHeight = m(KNOPF_DP); minimumWidth = m(KNOPF_DP); contentDescription = ctx.getString(label)
        }, LinearLayout.LayoutParams(m(if (quer) KNOPF_BREITE_DP else KNOPF_DP), m(if (quer) KNOPF_DP else KNOPF_BREITE_DP)).apply {
            val a = ui.dp(3); setMargins(a, a, a, a)
        })
        knopf(R.drawable.ic_pn_home, R.string.schnell_haus) { haus() }
        // 0.1.44 (P1): Zurück und Letzte App, nur wenn ein Weg da ist (Bedienungshilfe oder Kern), sonst ohne Knöpfe
        if (Tasten.wegJetzt() != Tasten.Weg.KEINER) {
            knopf(R.drawable.ic_pn_zurueck, R.string.taste_zurueck) { Tasten.druecken(ctx, Tasten.ZURUECK, client) }
            knopf(R.drawable.ic_pn_wechsel, R.string.taste_letzte) { Tasten.druecken(ctx, Tasten.LETZTE, client) }
        }
        knopf(R.drawable.ic_pn_minus, R.string.schnell_leiser) { lautstaerke(AudioManager.ADJUST_LOWER) }
        knopf(R.drawable.ic_pn_plus, R.string.schnell_lauter) { lautstaerke(AudioManager.ADJUST_RAISE) }
        // L1: eigene Anzeige (Wert + Balken) am Ende der Leiste. Der Platz ist immer reserviert (unsichtbar statt weg),
        // damit die Knöpfe nie springen und nichts über ihnen liegt. Androids eigenes Lautstärkefenster wird nicht gezeigt,
        // weil es am rechten Rand genau über der Leiste aufgeht und der nächste Tipp sonst dort landet.
        val text = ui.text("", 16f, 700).apply { gravity = Gravity.CENTER }
        val spur = FrameLayout(ctx).apply { background = GradientDrawable().apply { setColor(ui.p.line); cornerRadius = ui.dpf(3f) } }
        val fuellung = View(ctx).apply { background = GradientDrawable().apply { setColor(ui.p.aqua); cornerRadius = ui.dpf(3f) } }
        spur.addView(fuellung, FrameLayout.LayoutParams(0, -1))
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; visibility = View.INVISIBLE
            addView(text, LinearLayout.LayoutParams(-2, -2))
            addView(spur, LinearLayout.LayoutParams(ui.dp(BALKEN_DP), ui.dp(6)).apply { topMargin = ui.dp(4) })
        }
        z.addView(box, LinearLayout.LayoutParams(m(KNOPF_BREITE_DP), m(if (quer) KNOPF_DP else 44)))
        anzeige = box; anzeigeText = text; anzeigeFuellung = fuellung
        val lp = WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity = when (w) { LINKS -> Gravity.START or Gravity.CENTER_VERTICAL
                RECHTS -> Gravity.END or Gravity.CENTER_VERTICAL
                else -> Gravity.TOP or Gravity.CENTER_HORIZONTAL }
            if (w == OBEN) y = statusLeistenHoehe()
        }
        if (runCatching { wm.addView(z, lp) }.isSuccess) { ansicht = z; gebautFuer = w }
    }

    /** Haus: der Standard-Startbildschirm des Systems (BYD-Start oder, wenn so eingestellt, der Panoptes Launcher). */
    private fun haus() {
        runCatching {
            ctx.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun lautstaerke(richtung: Int) {
        runCatching {
            ctx.getSystemService(AudioManager::class.java)
                ?.let { a ->
                    // L1: ohne Androids Lautstärkefenster (Flags 0), stattdessen die eigene Anzeige in der Leiste
                    a.adjustStreamVolume(AudioManager.STREAM_MUSIC, richtung, 0)
                    zeigeLautstaerke(a.getStreamVolume(AudioManager.STREAM_MUSIC), a.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                }
        }
    }

    private fun zeigeLautstaerke(wert: Int, max: Int) {
        val box = anzeige ?: return
        val p = prozent(wert, max)
        anzeigeText?.text = "$p %"
        box.contentDescription = "$p %"
        anzeigeFuellung?.let { f -> f.layoutParams = f.layoutParams.apply { width = ui.dp(BALKEN_DP) * p / 100 } }
        box.visibility = View.VISIBLE
        main.removeCallbacks(anzeigeWeg)
        main.postDelayed(anzeigeWeg, ANZEIGE_MS)
    }

    private fun statusLeistenHoehe(): Int {
        val id = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) ctx.resources.getDimensionPixelSize(id) else ui.dp(24)
    }

    private fun entfernen() {
        ansicht?.let { runCatching { wm.removeView(it) } }
        main.removeCallbacks(anzeigeWeg)
        ansicht = null; gebautFuer = null; anzeige = null; anzeigeText = null; anzeigeFuellung = null
    }
}
