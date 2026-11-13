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
 * 0.1.43.3 (Rang 7) Assistent-Knopf: runder Mikrofon-Knopf am rechten Rand des Startbildschirms.
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
    fun einhaengen(root: FrameLayout, ui: Ui) {
        val groesse = ui.bd(64)
        val k = FrameLayout(activity).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ui.p.lime); setStroke(ui.dp(2), ui.p.line) }
            addView(ui.icon(R.drawable.ic_pn_mikro, ui.p.onPrimary, Math.round(30 * ui.bedien)),
                FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            contentDescription = activity.getString(R.string.sh_knopf_beschr)
            isClickable = true; isLongClickable = true; foreground = ui.welle()
            elevation = ui.dpf(6f)
            setOnClickListener { runCatching { activity.startActivity(Sprache.intent()) } }
            setOnLongClickListener { SprachHilfeActivity.starten(activity); true }
            visibility = View.GONE
        }
        root.addView(k, FrameLayout.LayoutParams(groesse, groesse, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = ui.dp(12) })
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
