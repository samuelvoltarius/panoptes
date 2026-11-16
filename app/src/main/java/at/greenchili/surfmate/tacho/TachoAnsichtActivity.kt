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
 * - 0.1.43.9 (T2): App-Fenster. Erlaubt der Kern eine App im Kasten des Designs (`appFenster.flaeche`), legt die
 *   Ansicht dort eine SurfaceView mit einem PRIVATEN VirtualDisplay an und meldet dessen Kennung (`appDisplay`); der
 *   Daemon startet die App darauf. Sie zeichnet nur in diesen Kasten. Faellt die Erlaubnis weg, kommt die Flaeche
 *   1,5 s spaeter weg (Zeit fuer den Kern, die App vorher im Hintergrund aufs Infotainment zu legen).
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

    /**
     * 0.1.43.9 (F, B3): Takt an die Sichtbarkeit gebunden (onStart/onStop), nicht an den Fokus (onResume/onPause).
     * Ein Tipp aufs Infotainment pausiert die Ansicht auf dem Fahrerdisplay; Tempo und Werte laufen trotzdem weiter,
     * und das App-Fenster bleibt. Die Regeln (beim Losfahren nur Navi-Apps) gelten unveraendert im Kern.
     */
    override fun onStart() {
        super.onStart()
        if (laeuft) return
        laeuft = true
        takt()
    }

    override fun onStop() {
        laeuft = false
        haupt.removeCallbacksAndMessages(null)
        karteStopp()
        // 0.1.43.9 (T2): kameraEnde (tachoAnsichtEnde) laesst den Kern auch die App wegnehmen; die Flaeche danach
        haupt.postDelayed({ if (!laeuft) appFlaecheWeg() }, APP_WEG_MS)
        videoStopp()
        kameraEnde()
        super.onStop()
    }

    override fun onDestroy() {
        laeuft = false
        haupt.removeCallbacksAndMessages(null)
        karteStopp()
        appFlaecheWeg()
        hintergrund.shutdownNow()
        try { seite.destroy() } catch (_: Throwable) { }
        super.onDestroy()
    }

    companion object {
        /**
         * 0.1.38: Takt fuer Tempo, Leistung und Navi (vorher 1 s). Laeuft nur zwischen onStart und onStop
         * (0.1.43.9 F, vorher onResume/onPause), also solange die Ansicht sichtbar ist, auch ohne Fokus; der naechste
         * Takt startet erst nach der Antwort.
         */
        const val TAKT_MS = 250L
        /** 0.1.43.9 (T1): Kartenquelle hoechstens so oft neu lesen. */
        const val QUELLE_MS = 5000L
        /** Ohne Daemon-Antwort nach so vielen Takten beenden (= 3 s wie bisher, fail-closed). */
        const val FEHLER_GRENZE = (3000L / TAKT_MS).toInt()
        /** 0.1.43.9 (T2): so lange bleibt die App-Flaeche nach dem Wegfall, damit der Kern die App vorher wegnimmt. */
        const val APP_WEG_MS = 1500L
    }

    private fun aufDisplayNull(): Boolean = try {
        @Suppress("DEPRECATION")
        (windowManager.defaultDisplay.displayId == 0)
    } catch (_: Throwable) { false }

    private fun beenden() {
        laeuft = false
        karteStopp()
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
                // 0.1.43.9 (T2): Lage der Tacho-Seite (Pixel des Fahrerdisplays) fuer das App-Fenster; der Kern rechnet
                // das Rechteck selbst aus dem gespeicherten Design und prueft, dass es das Tempo nicht ueberdeckt.
                // 0.1.43.9 (F, B2): nur, wenn die Seite gerade das eigene Design MIT App-Kasten zeigt (bestaetigt)
                if (seiteApp) seiteLage?.let { f -> c.put("flaeche", org.json.JSONArray().put(f[0]).put(f[1]).put(f[2]).put(f[3])) }
                if (appDisplayId > 0) c.put("appDisplay", appDisplayId)
                // 0.1.44 (E1): eigenes Design nur holen, wenn es sich geaendert hat; 0.1.43.9 (F, B2): die Kennung
                // stammt nur aus der Bestaetigung der Seite, geht sie dort verloren, schickt der Kern das Design erneut
                if (eigenStand.isNotEmpty()) c.put("eigenStand", eigenStand)
                // 0.1.43.9 (T3): Position nur, wenn das Design eine Karte hat und eine Quelle gewaehlt ist
                if (karteKasten != null && karteQuelle != at.greenchili.surfmate.einstellungen.Kartenquelle.KEINE) c.put("karte", true)
                daemon.sendCommand(c)
            } catch (_: Throwable) { null }
            haupt.post { zeige(antwort) }
        }
    }

    /** 0.1.43.9 (T1): Kartenquelle fuer das Element Karte, hoechstens alle [QUELLE_MS] neu gelesen. */
    @Volatile private var karteQuelle = at.greenchili.surfmate.einstellungen.Kartenquelle.KEINE
    private var karteQuelleZeit = 0L

    private fun karteQuelle(): String {
        val jetzt = android.os.SystemClock.elapsedRealtime()
        if (karteQuelleZeit == 0L || jetzt - karteQuelleZeit > QUELLE_MS) {
            val q = at.greenchili.surfmate.einstellungen.Kartenquelle.aktuell()
            // 0.1.43.9 (F, B1): ohne passende Grafik bzw. nach einem Snapshot-Fehler keine Kacheln (gedaempftes Zeichen)
            karteQuelle = if (q == at.greenchili.surfmate.einstellungen.Kartenquelle.KEINE) q
                else TachoKachelKarte.quelleFuerSeite(q, TachoKarteBild.kachelnMoeglich(this))
            karteQuelleZeit = jetzt
        }
        return karteQuelle
    }

    // ---- 0.1.43.9 (T2) App-Fenster: eigene Flaeche im Kasten
    private var appFlaeche: android.view.SurfaceView? = null
    private var appVd: android.hardware.display.VirtualDisplay? = null
    private var appRechteck: IntArray? = null
    @Volatile private var appDisplayId = -1
    private var appWegSeit = 0L

    private fun appFolgen(a: org.json.JSONObject?) {
        val f = a?.optJSONObject("appFenster")
        val r = f?.optJSONArray("rechteck")
        // 0.1.43.9 (F, B2): nie ueber einem anderen Design als dem eigenen mit App-Kasten (Bestaetigung der Seite)
        val soll = if (seiteApp && f != null && f.optBoolean("flaeche", false) && r != null && r.length() == 4)
            IntArray(4) { r.optInt(it) } else null
        if (soll == null || soll[2] <= soll[0] || soll[3] <= soll[1]) {
            val alt = appFlaeche ?: return
            // Sofort unsichtbar (die App darf nichts verdecken); das VirtualDisplay bleibt ohne Bild, bis der Kern
            // die App weggenommen hat, damit sie nicht vorne aufs Infotainment springt.
            if (alt.visibility != android.view.View.INVISIBLE) alt.visibility = android.view.View.INVISIBLE
            val jetzt = android.os.SystemClock.uptimeMillis()
            if (appWegSeit == 0L) appWegSeit = jetzt
            if (jetzt - appWegSeit >= APP_WEG_MS) appFlaecheWeg()
            return
        }
        appWegSeit = 0L
        val ort = IntArray(2); wurzel.getLocationOnScreen(ort)
        val lp = FrameLayout.LayoutParams(soll[2] - soll[0], soll[3] - soll[1]).apply {
            leftMargin = soll[0] - ort[0]; topMargin = soll[1] - ort[1]
        }
        val v = appFlaeche
        if (v != null) {
            if (!soll.contentEquals(appRechteck)) { v.layoutParams = lp; appRechteck = soll }
            if (v.visibility != android.view.View.VISIBLE) v.visibility = android.view.View.VISIBLE
            return
        }
        val neu = android.view.SurfaceView(this)
        neu.setZOrderMediaOverlay(true)   // ueber der Tacho-Seite, nur im Kasten
        neu.isFocusable = false
        neu.holder.addCallback(object : android.view.SurfaceHolder.Callback {
            override fun surfaceCreated(h: android.view.SurfaceHolder) {
                val vd = appVd
                if (vd == null) appVdAnlegen(h.surface, neu.width, neu.height)
                else try { vd.surface = h.surface } catch (_: Throwable) { }
            }
            override fun surfaceChanged(h: android.view.SurfaceHolder, format: Int, breite: Int, hoehe: Int) {
                val vd = appVd
                if (vd == null) appVdAnlegen(h.surface, breite, hoehe)
                else try { vd.resize(breite, hoehe, resources.displayMetrics.densityDpi) } catch (_: Throwable) { }
            }
            // 0.1.43.9 (F): Flaeche unsichtbar/Ansicht verdeckt = nur kein Bild; freigegeben wird erst in appFlaecheWeg
            override fun surfaceDestroyed(h: android.view.SurfaceHolder) {
                try { appVd?.surface = null } catch (_: Throwable) { }
            }
        })
        appFlaeche = neu
        appRechteck = soll
        wurzel.addView(neu, lp)
    }

    /** Privates VirtualDisplay nur fuer diese Ansicht (wie RemoteDevVirtualDisplay); keine Spiegelung, keine Rechte. */
    private fun appVdAnlegen(s: android.view.Surface, breite: Int, hoehe: Int) {
        if (appVd != null || breite <= 0 || hoehe <= 0) return
        try {
            val dm = getSystemService(android.content.Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
            val vd = dm.createVirtualDisplay("Panoptes-Tacho-App", breite, hoehe, resources.displayMetrics.densityDpi, s,
                android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
            val id = vd?.display?.displayId ?: -1
            if (vd == null || id <= 0) { vd?.release(); return }
            appVd = vd
            appDisplayId = id
        } catch (_: Throwable) {
            appVd = null; appDisplayId = -1
        }
    }

    private fun appVdWeg() {
        appDisplayId = -1
        try { appVd?.release() } catch (_: Throwable) { }
        appVd = null
    }

    private fun appFlaecheWeg() {
        appWegSeit = 0L
        appVdWeg()
        appFlaeche?.let { try { wurzel.removeView(it) } catch (_: Throwable) { } }
        appFlaeche = null
        appRechteck = null
    }

    /** 0.1.43.9 (T2): Lage der Tacho-Seite auf dem Fahrerdisplay [x, y, w, h] in Pixeln. */
    @Volatile private var seiteLage: IntArray? = null

    private fun seiteLageLesen() {
        try {
            val ort = IntArray(2); seite.getLocationOnScreen(ort)
            seiteLage = if (seite.width > 0 && seite.height > 0) intArrayOf(ort[0], ort[1], seite.width, seite.height) else null
        } catch (_: Throwable) { seiteLage = null }
    }

    /** 0.1.44 (E1): Kennung des eigenen Designs, das die Seite schon hat (0.1.43.9 F: nur aus ihrer Bestaetigung). */
    @Volatile private var eigenStand = ""
    /** 0.1.43.9 (F, B2): Die Seite zeigt gerade das eigene Design mit App-Kasten (bestaetigt). */
    @Volatile private var seiteApp = false

    /**
     * 0.1.43.9 (F, B2): Rueckgabe von `tachoSetze` = was die Seite jetzt zeigt. Ist die Seite noch nicht bereit oder hat
     * sie das Design verloren, kommt null bzw. ein leerer Stand: Dann schickt der Kern das Design beim naechsten Takt
     * erneut, und eine App-Flaeche gibt es nicht (sie laege sonst ueber dem Standard-Design und dessen Tempo).
     */
    private fun seiteBestaetigt(s: String?) {
        val st = TachoAnsicht.seitenStand(s)
        eigenStand = st[0]
        seiteApp = st[1] == "1"
        if (!seiteApp) appFolgen(null)
    }

    // 0.1.43.9 (T3): Kartenbild fuer das Element Karte (nur mit Karte im Design und gewaehlter Quelle).
    @Volatile private var karteKasten: org.json.JSONObject? = null
    private var eigenLetzt: org.json.JSONObject? = null
    private var karteBild: TachoKarteBild? = null
    private var karteLetzt: org.json.JSONObject? = null
    private var karteNeu = false

    private fun karteFolgen(a: org.json.JSONObject, d: org.json.JSONObject, quelle: String) {
        if (a.has("eigen")) eigenLetzt = a.optJSONObject("eigen")
        val kasten = TachoKachelKarte.kasten(a.optString("design"), eigenLetzt)
        karteKasten = kasten
        if (kasten == null || quelle == at.greenchili.surfmate.einstellungen.Kartenquelle.KEINE) {
            karteStopp()
            return
        }
        val kb = karteBild ?: TachoKarteBild(this) { j -> karteLetzt = j; karteNeu = true }.also { karteBild = it }
        kb.schritt(kasten, quelle, a.optJSONObject("kartePos"), true)
        val letzt = karteLetzt ?: return
        // Das Bild selbst nur bei Aenderung; die Seite behaelt das letzte.
        val k = org.json.JSONObject(letzt.toString())
        if (!karteNeu) k.remove("src")
        karteNeu = false
        d.put("karteBild", k)
    }

    private fun karteStopp() {
        karteBild?.stopp()
        karteBild = null
        karteLetzt = null
        karteNeu = false
    }

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
            // 0.1.43.9 (F, B2): Kennung mit dem Design an die Seite; uebernommen wird sie erst aus deren Bestaetigung.
            if (a.has("eigen")) d.put("eigenStand", a.optString("eigenStand", ""))
            TachoAnsichtManoever.json(TachoAnsichtManoever.aktuell())?.let { d.put("navi", it) }
            // Nur die gewaehlte Quelle (community/osm/keine); die Seite laedt selbst nichts aus dem Netz.
            val quelle = karteQuelle()
            d.put("karteQuelle", quelle)
            try { karteFolgen(a, d, quelle) } catch (_: Throwable) { karteStopp() }
            videoFolgen(a)
            ansichtFolgen(a.optString("kamera", "").isNotEmpty() || videoLaeuft.isNotEmpty())
            feldLesen()
            seiteLageLesen()
            appFolgen(a)
            seite.evaluateJavascript("window.tachoSetze && window.tachoSetze($d)") { s -> seiteBestaetigt(s) }
        }
        haupt.postDelayed({ takt() }, TAKT_MS)
    }
}
