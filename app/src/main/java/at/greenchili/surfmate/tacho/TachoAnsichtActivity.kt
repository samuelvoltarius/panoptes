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
import android.widget.VideoView
import at.greenchili.surfmate.client.CameraDaemonClient
import java.util.concurrent.Executors

/**
 * Panoptes-Tacho-Ansicht (0.1.37) auf dem Fahrerdisplay. Gestartet wird sie ausschliesslich von
 * `ClusterCast.start` nach `TachoNaviApp.fahrtSperre` (Appwahl, Probe in P, Freigabe).
 *
 * - Zeigt Tempo, Leistung, Akku, Reichweite, Gang, Aussentemperatur, km-Stand und das naechste
 *   Navi-Manoever mit Routenskizze. Keine Position.
 * - 0.1.42 (T3): Kamera ueber den Kern (`TachoKameraAnsicht`, native camview-Ebene, ab Werk aus) und Video
 *   einer gewaehlten Aufnahme NUR in P bei 0 km/h, ohne Ton; ausserhalb von P sofort weg (auch ohne Kern-Antwort).
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
    private lateinit var video: VideoView
    private var videoLaeuft = ""
    /** 0.1.42: freies Feld (Pixel des Fahrerdisplays) aus `tachoInstanz.ansichtBereich()` (T2); null = keins → nichts zeigen. */
    @Volatile private var feld: IntArray? = null
    private var ansichtAn = false

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
        video = VideoView(this).apply {
            visibility = android.view.View.GONE
            isFocusable = false
            // Ohne Ton und in Schleife; nie ueber einen anderen Kanal als die Medienwiedergabe des Kopfgeraets.
            setOnPreparedListener { mp -> try { mp.setVolume(0f, 0f); mp.isLooping = true } catch (_: Throwable) { } }
            setOnErrorListener { _, _, _ -> videoStopp(); true }
        }
        wurzel.addView(video, FrameLayout.LayoutParams(0, 0))
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

    /** 0.1.42: Video nur, wenn der Kern es erlaubt UND die Ansicht selbst P sieht; sonst sofort weg. */
    private fun videoFolgen(a: org.json.JSONObject) {
        val id = a.optString("videoId", "")
        val darf = a.optBoolean("video", false) && a.optString("gang", "") == "P" && id.matches(Regex("^[a-f0-9]{32}$"))
        if (!darf) { videoStopp(); return }
        val f = feld
        if (f == null) { videoStopp(); return }
        if (id == videoLaeuft) return
        videoLaeuft = id
        try {
            val ort = IntArray(2); wurzel.getLocationOnScreen(ort)
            video.layoutParams = FrameLayout.LayoutParams(f[2], f[3]).apply { leftMargin = f[0] - ort[0]; topMargin = f[1] - ort[1] }
            video.setVideoURI(android.net.Uri.parse(
                "http://127.0.0.1:" + at.greenchili.surfmate.daemon.CameraDaemon.HTTP_PORT + "/video/id/" + id))
            video.visibility = android.view.View.VISIBLE
            video.start()
        } catch (_: Throwable) { videoStopp() }
    }

    /** Design auf „ruhige Leiste unten“ (Tempo bleibt sichtbar), solange Kamera oder Video laeuft. */
    private fun ansichtFolgen(an: Boolean) {
        if (an == ansichtAn) return
        ansichtAn = an
        seite.evaluateJavascript("window.tachoInstanz && window.tachoInstanz.ansicht && window.tachoInstanz.ansicht($an)", null)
    }

    /** Freies Feld aus der Seite lesen (CSS-Pixel → Pixel des Displays). Ohne Einhaengestelle bleibt es null. */
    private fun feldLesen() {
        val js = "(function(){var c=document.getElementById('tacho');var i=window.tachoInstanz;" +
            "if(!c||!i||!i.ansichtBereich)return null;var b=i.ansichtBereich();if(!b)return null;" +
            "var r=c.getBoundingClientRect();return [r.left+b.x,r.top+b.y,b.w,b.h];})()"
        seite.evaluateJavascript(js) { s -> feld = feldAus(s) }
    }

    private fun feldAus(s: String?): IntArray? = try {
        val a = org.json.JSONArray(s ?: "null")
        val dichte = resources.displayMetrics.density
        val ort = IntArray(2); seite.getLocationOnScreen(ort)
        val f = IntArray(4) { i -> Math.round(a.getDouble(i).toFloat() * dichte) }
        f[0] += ort[0]; f[1] += ort[1]
        if (f[2] > 0 && f[3] > 0) f else null
    } catch (_: Throwable) { null }

    private fun videoStopp() {
        if (videoLaeuft.isEmpty() && video.visibility != android.view.View.VISIBLE) return
        videoLaeuft = ""
        try { video.stopPlayback() } catch (_: Throwable) { }
        video.visibility = android.view.View.GONE
    }

    /** Kern: eigenes Kamerabild im Tacho weg (Ansicht pausiert/beendet). */
    private fun kameraEnde() {
        try {
            hintergrund.execute {
                try { daemon.sendCommand(org.json.JSONObject().put("cmd", "tachoAnsichtEnde")) } catch (_: Throwable) { }
            }
        } catch (_: Throwable) { }
    }

    override fun onResume() {
        super.onResume()
        laeuft = true
        takt()
    }

    override fun onPause() {
        laeuft = false
        haupt.removeCallbacksAndMessages(null)
        videoStopp()
        kameraEnde()
        super.onPause()
    }

    override fun onDestroy() {
        laeuft = false
        haupt.removeCallbacksAndMessages(null)
        hintergrund.shutdownNow()
        try { seite.destroy() } catch (_: Throwable) { }
        super.onDestroy()
    }

    companion object {
        /**
         * 0.1.38: Takt fuer Tempo, Leistung und Navi (vorher 1 s). Laeuft nur zwischen onResume und onPause,
         * also nur solange die Ansicht sichtbar ist; der naechste Takt startet erst nach der Antwort.
         */
        const val TAKT_MS = 250L
        /** Ohne Daemon-Antwort nach so vielen Takten beenden (= 3 s wie bisher, fail-closed). */
        const val FEHLER_GRENZE = (3000L / TAKT_MS).toInt()
    }

    private fun aufDisplayNull(): Boolean = try {
        @Suppress("DEPRECATION")
        (windowManager.defaultDisplay.displayId == 0)
    } catch (_: Throwable) { false }

    private fun beenden() {
        laeuft = false
        videoStopp()
        kameraEnde()
        try { finishAndRemoveTask() } catch (_: Throwable) { finish() }
    }

    private fun takt() {
        if (!laeuft) return
        // Nie am Infotainment: dort hat die Ansicht nichts zu suchen.
        if (aufDisplayNull()) { beenden(); return }
        hintergrund.execute {
            val antwort = try {
                val c = org.json.JSONObject().put("cmd", "tachoAnsicht")
                feld?.let { f -> c.put("bereich", org.json.JSONArray().put(f[0]).put(f[1]).put(f[2]).put(f[3])) }
                // 0.1.44 (E1): eigenes Design nur holen, wenn es sich geaendert hat
                if (eigenStand.isNotEmpty()) c.put("eigenStand", eigenStand)
                daemon.sendCommand(c)
            } catch (_: Throwable) { null }
            haupt.post { zeige(antwort) }
        }
    }

    /** 0.1.44 (E1): Kennung des eigenen Designs, das die Seite schon hat. */
    @Volatile private var eigenStand = ""

    private fun zeige(a: org.json.JSONObject?) {
        if (!laeuft) return
        if (a == null || a.optString("status") != "ok") {
            videoStopp()
            if (++fehlversuche >= FEHLER_GRENZE) { beenden(); return }
        } else {
            fehlversuche = 0
            if (!a.optBoolean("aktiv", false)) { beenden(); return }
            // Nur bekannte Felder weiter an die Seite, dazu das Manoever aus der Navigation.
            val d = org.json.JSONObject()
            for (k in arrayOf("kmh", "kw", "soc", "reichweiteKm", "gang", "aussenC", "kmStand", "design", "eigen")) {
                if (a.has(k)) d.put(k, a.opt(k))
            }
            if (a.has("eigen")) eigenStand = a.optString("eigenStand", "")
            TachoAnsichtManoever.json(TachoAnsichtManoever.aktuell())?.let { d.put("navi", it) }
            videoFolgen(a)
            ansichtFolgen(a.optString("kamera", "").isNotEmpty() || videoLaeuft.isNotEmpty())
            feldLesen()
            seite.evaluateJavascript("window.tachoSetze && window.tachoSetze($d)", null)
        }
        haupt.postDelayed({ takt() }, TAKT_MS)
    }
}
