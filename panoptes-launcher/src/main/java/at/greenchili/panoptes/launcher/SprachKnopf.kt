package at.greenchili.panoptes.launcher

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import java.util.concurrent.Executors

/**
 * 0.1.43.3 (Rang 7) Assistent-Knopf. Seit L3 (Design-Runde 0.1.43.3) eine Pille „Sprechen“ oben rechts in der
 * Statusleiste (vorher runder Knopf rechts mittig, der den Rand der Musik-Karte überdeckte, E2E H2). Ohne Statusleiste
 * steht dieselbe Pille oben rechts über der Seite.
 *
 * - Sichtbar **nur**, wenn der Fahrer die Sprachbefehle in Panoptes eingeschaltet hat (Kern `an`) und der Launcher-Schalter
 *   „Sprech-Knopf zeigen“ an ist. Ohne diese Zustimmung ändert sich am Startbildschirm nichts (ab Werk nichts umgestellt).
 * - Tipp: Sprechen (Panoptes `SprachStart`). Langes Drücken: „Was kann ich sagen?“ ([SprachHilfeActivity]).
 * - Der Launcher hört selbst nie zu und schickt keinen Fahrzeugbefehl.
 */
class SprachKnopf(private val activity: Activity, private val client: PanoptesClient) {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var knopf: View? = null
    @Volatile private var katalog: SprachKatalog? = null

    /** In die Wurzel der Startseite einhängen (unsichtbar, bis der Stand da ist). */
    fun einhaengen(root: FrameLayout, ui: Ui, platz: FrameLayout? = null) {
        // L3: Pille mit Mikrofon und „Sprechen“, Glas mit Akzent-Symbol; mindestens 44 dp hoch, mit „Große Bedienung“ mehr
        val k = android.widget.LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ui.bd(44)
            setPadding(ui.dp(16), ui.dp(6), ui.dp(20), ui.dp(6))
            background = ui.glas(Mass.RADIUS_RUND)
            addView(ui.icon(R.drawable.ic_pn_mikro, ui.p.aqua, Math.round(22 * ui.bedien)), ui.lp(rechts = 8))
            addView(ui.stufe(Stufe.PLAKETTE, activity.getString(R.string.widget_sprache), ui.p.onSurface).apply { textSize = 16f * ui.bedien; maxLines = 1 })
            contentDescription = activity.getString(R.string.sh_knopf_beschr)
            isClickable = true; isLongClickable = true; foreground = ui.welle()
            setOnClickListener { runCatching { activity.startActivity(Sprache.intent()) } }
            setOnLongClickListener { SprachHilfeActivity.starten(activity); true }
            visibility = View.GONE
        }
        if (platz != null) platz.addView(k, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL))
        else root.addView(k, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.TOP).apply { marginEnd = ui.dp(20); topMargin = ui.dp(8) })
        knopf = k
        anwenden()
    }

    /** Stand neu holen (bei Rückkehr in den Launcher). Nur lesen. */
    fun aktualisieren() {
        io.execute {
            val k = runCatching { client.sprache() }.getOrNull()
            katalog = k
            main.post { anwenden() }
        }
    }

    private fun anwenden() {
        val sichtbar = katalog?.knopfSichtbar(Sprache.knopfSchalter(activity)) == true
        knopf?.visibility = if (sichtbar) View.VISIBLE else View.GONE
    }

    fun ende() { io.shutdownNow() }
}
