package at.greenchili.surfmate.tacho

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import at.greenchili.surfmate.client.CameraDaemonClient
import java.util.concurrent.Executors

/**
 * Panoptes-Tacho-Ansicht (0.1.37) auf dem Fahrerdisplay. Gestartet wird sie ausschliesslich von
 * `ClusterCast.start` nach `TachoNaviApp.fahrtSperre` (Appwahl, Probe in P, Freigabe).
 *
 * - Zeigt Tempo, Leistung, Akku, Reichweite, Gang, Aussentemperatur, km-Stand und das naechste
 *   Navi-Manoever mit Routenskizze. Kein Video, keine Kamera, keine Position.
 * - Gezeichnet wird von `web/shared/tacho-designs.js` (gleicher Renderer wie die Web-Vorschau) in
 *   einer WebView aus den App-Assets; Netzladen ist gesperrt.
 * - Zeichnet nur im verkleinerten Mittelbereich ([TachoAnsicht.inhalt]), der Rest bleibt schwarz.
 * - Beendet sich selbst, wenn sie auf Display 0 (Infotainment) landet, wenn der Daemon sie nicht
 *   mehr als aktive Projektion meldet, wenn der Daemon nicht antwortet oder wenn die Seite
 *   abstuerzt (fail-closed).
 */
class TachoAnsichtActivity : Activity() {

    private val haupt = Handler(Looper.getMainLooper())
    private val hintergrund = Executors.newSingleThreadExecutor { r ->
        Thread(r, "TachoAnsicht").apply { isDaemon = true }
    }
    private val daemon = CameraDaemonClient()
    @Volatile private var laeuft = false
    private var fehlversuche = 0

    private lateinit var wurzel: FrameLayout
    private lateinit var seite: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        wurzel = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        seite = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.blockNetworkLoads = true
            settings.allowFileAccess = true
            settings.allowContentAccess = false
            settings.setGeolocationEnabled(false)
            isFocusable = false
            webViewClient = object : WebViewClient() {
                // Nur die eigene Seite aus den Assets, keine Navigation weg.
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = true
                override fun onRenderProcessGone(v: WebView, d: RenderProcessGoneDetail): Boolean {
                    beenden()
                    return true
                }
            }
        }
        wurzel.addView(seite, FrameLayout.LayoutParams(0, 0))
        wurzel.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> platziere(v.width, v.height) }
        setContentView(wurzel)
        seite.loadUrl(TachoAnsicht.SEITE)
    }

    /** Inhalt nur im verkleinerten Mittelbereich. */
    private fun platziere(w: Int, h: Int) {
        val r = TachoAnsicht.inhalt(w, h)
        val lp = seite.layoutParams as FrameLayout.LayoutParams
        if (lp.leftMargin == r[0] && lp.topMargin == r[1] && lp.width == r[2] && lp.height == r[3]) return
        lp.leftMargin = r[0]; lp.topMargin = r[1]; lp.width = r[2]; lp.height = r[3]
        seite.layoutParams = lp
    }

    override fun onResume() {
        super.onResume()
        laeuft = true
        takt()
    }

    override fun onPause() {
        laeuft = false
        haupt.removeCallbacksAndMessages(null)
        super.onPause()
    }

    override fun onDestroy() {
        laeuft = false
        haupt.removeCallbacksAndMessages(null)
        hintergrund.shutdownNow()
        try { seite.destroy() } catch (_: Throwable) { }
        super.onDestroy()
    }

    private fun aufDisplayNull(): Boolean = try {
        @Suppress("DEPRECATION")
        (windowManager.defaultDisplay.displayId == 0)
    } catch (_: Throwable) { false }

    private fun beenden() {
        laeuft = false
        try { finishAndRemoveTask() } catch (_: Throwable) { finish() }
    }

    private fun takt() {
        if (!laeuft) return
        // Nie am Infotainment: dort hat die Ansicht nichts zu suchen.
        if (aufDisplayNull()) { beenden(); return }
        hintergrund.execute {
            val antwort = try {
                daemon.sendCommand(org.json.JSONObject().put("cmd", "tachoAnsicht"))
            } catch (_: Throwable) { null }
            haupt.post { zeige(antwort) }
        }
    }

    private fun zeige(a: org.json.JSONObject?) {
        if (!laeuft) return
        if (a == null || a.optString("status") != "ok") {
            if (++fehlversuche >= 3) { beenden(); return }
        } else {
            fehlversuche = 0
            if (!a.optBoolean("aktiv", false)) { beenden(); return }
            // Nur bekannte Felder weiter an die Seite, dazu das Manoever aus der Navigation.
            val d = org.json.JSONObject()
            for (k in arrayOf("kmh", "kw", "soc", "reichweiteKm", "gang", "aussenC", "kmStand", "design")) {
                if (a.has(k)) d.put(k, a.opt(k))
            }
            TachoAnsichtManoever.json(TachoAnsichtManoever.aktuell())?.let { d.put("navi", it) }
            seite.evaluateJavascript("window.tachoSetze && window.tachoSetze($d)", null)
        }
        haupt.postDelayed({ takt() }, 1000L)
    }
}
