package at.greenchili.panoptes.launcher

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * 0.1.39 (L1) Rückfall: Lässt sich die Navi-App nicht teilen, läuft sie im Vollbild und der Launcher legt oben eine
 * schmale Statusleiste darüber (TYPE_APPLICATION_OVERLAY, nur mit dem Recht „Über anderen Apps einblenden“).
 * Inhalt: Akku · Reichweite · Gang, Musik ⏯, Kamera, Start. Keine Texteingabe (Fahrsperre). Im Rückwärtsgang
 * (Rückfahrkamera) verschwindet sie sofort und kommt danach wieder. Aktualisiert sich selbst alle 2 s, weil die
 * Launcher-Seite im Hintergrund nicht tickt.
 */
class StatusLeiste(private val ctx: Context, private val ui: Ui, private val aktionen: Aktionen,
                   private val spalte: List<String> = Raster.SPALTE_STANDARD) {

    interface Aktionen { fun musik(); fun kamera(); fun start(); fun splitNochmal() }

    /** Split wurde im Rückwärtsgang vom System beendet → nach R + Ruhe genau ein neuer Versuch (KartenStatus.nochmalVersuchen). */
    @Volatile var abbruchBeiR = false
    private var versucht = false
    private var ruheSeit: Long? = null

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val client = PanoptesClient()
    private var ansicht: View? = null
    private var text: TextView? = null
    private val beschriftungen = mutableListOf<TextView>()
    @Volatile private var aktiv = false

    private val takt = object : Runnable {
        override fun run() {
            if (!aktiv) return
            io.execute {
                val s = runCatching { client.summary() }.getOrNull()
                main.post { if (aktiv) zeigen(s) }
            }
            main.postDelayed(this, 2_000)
        }
    }

    fun an() {
        if (aktiv) return
        aktiv = true
        main.post(takt)
    }

    fun aus() {
        aktiv = false
        main.removeCallbacks(takt)
        entfernen()
    }

    private fun zeigen(s: LauncherSummary?) {
        val jetzt = android.os.SystemClock.elapsedRealtime()
        if (s?.gear == "R") ruheSeit = null else if (ruheSeit == null) ruheSeit = jetzt
        if (KartenStatus.nochmalVersuchen(abbruchBeiR, versucht, s?.gear, ruheSeit, jetzt)) { versucht = true; aktionen.splitNochmal() }
        val darf = KartenStatus.leisteZeigen(aktiv, Settings.canDrawOverlays(ctx), s?.gear)
        if (!darf) { entfernen(); return }
        if (ansicht == null) bauen()
        text?.text = KartenStatus.leistenZeile(spalte, s?.socPct, s?.rangeKm, s?.gear,
            android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date()))
    }

    private fun bauen() {
        val z = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(16), 0, ui.dp(8), 0)
            background = GradientDrawable().apply { setColor(ui.p.surface); setStroke(ui.dp(1), ui.p.line) }
        }
        val t = ui.text("—", 18f, 700)
        z.addView(t, ui.lp(0, -2, 1f))
        // 0.1.40: waagrechter Knopf – Text neben dem Symbol ohne Abstand oben. E2E 240 dpi: „Kamera“ und
        // „Pause/Weiter“ waren trotzdem angeschnitten → Beschriftung mit eigener wrap-Breite (vorher match_parent aus
        // dem senkrechten Aufbau), und passt sie nach dem Layout nicht, zeigen alle Knöpfe nur das Symbol.
        fun knopf(res: Int, label: Int, f: () -> Unit) =
            z.addView(ui.act(res, ctx.getString(label)) { f() }.apply {
                orientation = LinearLayout.HORIZONTAL; minimumHeight = ui.dp(48); minimumWidth = ui.dp(48)
                setPadding(ui.dp(10), 0, ui.dp(14), 0); contentDescription = ctx.getString(label)
                (getChildAt(1) as? TextView)?.apply {
                    layoutParams = LinearLayout.LayoutParams(-2, -2); setPadding(ui.dp(6), 0, 0, 0); setSingleLine(); ellipsize = null
                    beschriftungen += this
                }
            }, ui.lp(-2, ui.dp(48), rechts = 8))
        // 0.1.40 (E2E G4): Knöpfe nach der Auswahl der Statusspalte; „Start“ ist immer da
        if ("musik" in spalte) knopf(R.drawable.ic_pn_music, R.string.leiste_musik) { aktionen.musik() }
        if ("kamera" in spalte) knopf(R.drawable.ic_pn_camera, R.string.leiste_kamera) { aktionen.kamera() }
        knopf(R.drawable.ic_pn_status, R.string.leiste_start) { aktionen.start() }
        z.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val zuEng = beschriftungen.any { it.visibility == View.VISIBLE &&
                KartenStatus.nurSymbole(it.paint.measureText(it.text.toString()), it.width - it.paddingLeft - it.paddingRight) } ||
                (z.childCount > 0 && z.getChildAt(z.childCount - 1).right > z.width - z.paddingRight && beschriftungen.any { it.visibility == View.VISIBLE })
            if (zuEng) z.post { beschriftungen.forEach { it.visibility = View.GONE } }
        }
        val lp = WindowManager.LayoutParams(-1, ui.dp(56), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP; y = statusLeistenHoehe() }   // unter Uhr/Akku der Android-Leiste
        if (runCatching { wm.addView(z, lp) }.isSuccess) { ansicht = z; text = t }
    }

    /** Höhe der Android-Statusleiste (Uhr, Akku), damit die Leiste nicht darunter liegt. */
    private fun statusLeistenHoehe(): Int {
        val id = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) ctx.resources.getDimensionPixelSize(id) else ui.dp(24)
    }

    private fun entfernen() {
        ansicht?.let { runCatching { wm.removeView(it) } }
        ansicht = null; text = null; beschriftungen.clear()
    }
}
