package at.greenchili.panoptes.launcher

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class MainActivity : AppCompatActivity() {
    private val client = PanoptesClient()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var stopped = false
    private var cachedHistory = LauncherHistory(null, null)
    private var lastHistoryFetch = 0L

    // A slow or half-available localhost endpoint must not queue another complete
    // refresh every five seconds. Otherwise the single IO worker can build an
    // unbounded backlog and paint minutes-old vehicle data after recovery.
    private val refreshInFlight = AtomicBoolean(false)

    // Klima läuft getrennt: „an“ wartet im Kern einige Sekunden auf das Echo und darf die Anzeige nicht aufhalten.
    private val klimaIo = Executors.newSingleThreadExecutor()
    private val klimaLaeuft = AtomicBoolean(false)

    // Update-Karte (Stufe 4): Stand höchstens alle 5 min lesen, „Später“ gilt je Version.
    private var updateStand: UpdateStand? = null
    private var lastUpdateFetch = 0L
    private lateinit var updateHost: FrameLayout

    // ---------- Zustand ----------
    private lateinit var ui: Ui
    private lateinit var layout: StartLayout
    private var apps: List<InstallierteApp> = emptyList()
    private var appsNachPaket: Map<String, InstallierteApp> = emptyMap()
    private var summary: LauncherSummary? = null
    private var appearance: LauncherAppearance? = null
    private var traffic = LauncherTraffic(0, 0)
    private var online = false
    private var bearbeiten = false
    private var seite = 0
    private var medien: Wiedergabe? = null
    private var stilSchluessel = ""

    // ---------- Ansichten ----------
    private lateinit var root: FrameLayout
    private lateinit var bild: ImageView
    private lateinit var video: VideoView
    private lateinit var seitenHost: Wischer
    private lateinit var punkte: LinearLayout
    private lateinit var hinweis: TextView
    private var raster: RasterLayout? = null
    private var overlay: View? = null
    private var webAuto: WebView? = null
    private var webAutoUrl: String? = null
    private var bildGeladen = false
    private var videoLaeuft = false
    private val aktualisierer = mutableListOf<() -> Unit>()
    private val statusAktualisierer = mutableListOf<() -> Unit>()

    private val nf get() = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 0 }
    private val nf1 get() = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }

    // 0.1.38.8: Display oder Zündung aus → Launcher ruht, ohne Fokus kein Sekundentakt (Regeln in Ruhe)
    private var displayAn = true
    private val bildAn get() = Ruhe.bildAn(displayAn, summary?.acc)
    private var warBildAn = true
    private var fokus = true
    private var taktStand = ""
    private val displayHoerer = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val an = Ruhe.displayAn(displayZustand())
            if (an != displayAn) { displayAn = an; zustandAnwenden() }
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            refresh()
            if (Ruhe.abfrageLaeuft(stopped, displayAn)) main.postDelayed(this, Ruhe.abfrageMs(bildAn))
        }
    }

    /** Medien und Uhr laufen flüssiger als die Fahrzeugdaten, aber nur im Vordergrund und nur bei Änderung. */
    private val takt = object : Runnable {
        override fun run() {
            val m = MedienQuelle.aktuell(this@MainActivity)
            medien = m
            val stand = Ruhe.taktSchluessel(m?.paket, m?.titel, m?.kuenstler, m?.spielt, m?.positionMs, m?.dauerMs,
                m?.cover?.generationId, System.currentTimeMillis())
            if (stand != taktStand) {
                taktStand = stand
                aktualisierer.forEach { it() }
                statusAktualisierer.forEach { it() }
            }
            if (Ruhe.taktLaeuft(stopped, bildAn, fokus)) main.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        layout = ladeLayout()
        baueAlles()
        kernAbgleich()
        displayAn = Ruhe.displayAn(displayZustand())
        runCatching { getSystemService(DisplayManager::class.java)?.registerDisplayListener(displayHoerer, main) }
    }

    override fun onStart() {
        super.onStart()
        stopped = false
        zustandAnwenden()
        ladeApps()
    }

    override fun onStop() {
        stopped = true
        zustandAnwenden()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus != fokus) { fokus = hasFocus; zustandAnwenden() }
    }

    private fun displayZustand(): Int? =
        runCatching { getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.state }.getOrNull()

    /** Abfrage, Sekundentakt, 3D und Video passend zu Sichtbarkeit, Display und Fokus starten oder anhalten. */
    private fun zustandAnwenden() {
        main.removeCallbacks(poll)
        main.removeCallbacks(takt)
        warBildAn = bildAn
        if (Ruhe.abfrageLaeuft(stopped, displayAn)) main.post(poll)
        if (!stopped && bildAn) {
            webAuto?.onResume()
        } else {
            videoStopp()
            // 3D nur, wenn sichtbar: WebView anhalten (Renderer pausiert zusätzlich bei verdeckter Seite)
            webAuto?.onPause()
        }
        if (Ruhe.taktLaeuft(stopped, bildAn, fokus)) { taktStand = ""; main.post(takt) }
    }

    override fun onDestroy() {
        runCatching { getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayHoerer) }
        io.shutdownNow()
        klimaIo.shutdownNow()
        layoutIo.shutdown()
        webAuto?.destroy()
        super.onDestroy()
    }

    /** Hell/Dunkel des Systems (Modus „auto“ wie theme.js) ohne Neustart der Ansicht übernehmen. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (overlay == null) baueAlles()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            overlay != null -> schliesseOverlay()
            bearbeiten -> { bearbeiten = false; baueAlles(); kernSpeichern() }
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    // ==================== Aufbau ====================

    private fun systemHell() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES

    private fun baueAlles() {
        val cockpit = layout.hintergrund == "cockpit"
        val pal = if (cockpit) PanoptesStil.DUNKEL else PanoptesStil.palette(appearance?.modus, systemHell())
        ui = Ui(this, pal, cockpit)
        stilSchluessel = "${pal.hell}:$cockpit"
        aktualisierer.clear(); statusAktualisierer.clear()
        overlay = null
        window.statusBarColor = if (cockpit) PanoptesStil.COCKPIT_SEITE[0] else pal.seiteVon
        window.navigationBarColor = window.statusBarColor
        if (pal.hell && !cockpit) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        else window.decorView.systemUiVisibility = 0

        root = FrameLayout(this).apply { background = SeitenHintergrund(pal, cockpit) }
        bild = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.GONE }
        root.addView(bild, FrameLayout.LayoutParams(-1, -1))
        video = VideoView(this).apply { visibility = View.GONE }
        root.addView(video, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        bildGeladen = false; videoLaeuft = false

        val spalte = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(20), ui.dp(6), ui.dp(20), 0) }
        root.addView(spalte, FrameLayout.LayoutParams(-1, -1))
        if (bearbeiten) spalte.addView(bearbeitenLeiste(), ui.lp(-1, ui.dp(72)))
        else if (layout.zeigeStatus) spalte.addView(statusLeiste(), ui.lp(-1, ui.dp(52)))

        updateHost = FrameLayout(this)
        spalte.addView(updateHost, ui.lp(-1, -2, oben = 4))
        zeichneUpdateKarte()
        seitenHost = Wischer()
        spalte.addView(seitenHost, ui.lp(-1, 0, 1f, oben = 6))
        punkte = LinearLayout(this).apply { gravity = Gravity.CENTER }
        spalte.addView(punkte, ui.lp(-1, ui.dp(18)))
        if (layout.zeigeReiter && !bearbeiten) spalte.addView(reiterZeile(), ui.lp(-1, ui.dp(88), oben = 4))
        spalte.addView(dock(), ui.lp(-1, ui.dp(84), oben = 8))

        hinweis = ui.text("", 15f, 600, ui.p.onSurface).apply {
            visibility = View.GONE; gravity = Gravity.CENTER
            setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12))
            background = GradientDrawable().apply { cornerRadius = ui.dpf(16f); setColor(ui.p.surface); setStroke(ui.dp(1), ui.p.aqua) }
        }
        root.addView(hinweis, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = ui.dp(110) })
        setContentView(root)
        seite = seite.coerceIn(0, layout.seiten.size - 1)
        zeichneSeite()
        hintergrundAnwenden()
    }

    private fun zeigeHinweis(text: String) {
        hinweis.text = text; hinweis.visibility = View.VISIBLE
        hinweis.bringToFront()   // auch über Klima-/Info-Feld sichtbar (Befund Emulator 04.11.)
        main.removeCallbacks(hinweisWeg); main.postDelayed(hinweisWeg, 4_000)
    }
    private val hinweisWeg = Runnable { if (::hinweis.isInitialized) hinweis.visibility = View.GONE }

    // ---------- Statusleiste ----------

    private fun statusLeiste(): View {
        val z = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val glocke = statusIcon(R.drawable.ic_pn_bell)
        val wlan = statusIcon(R.drawable.ic_pn_wifi)
        val bt = statusIcon(R.drawable.ic_pn_bt)
        val ort = statusIcon(R.drawable.ic_pn_pin)
        val punkt = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ui.p.lime) }; visibility = View.GONE }
        val glockeBox = FrameLayout(this).apply {
            addView(glocke); addView(punkt, FrameLayout.LayoutParams(ui.dp(9), ui.dp(9), Gravity.TOP or Gravity.END))
            setOnClickListener { if (!MedienQuelle.zugriffErlaubt(this@MainActivity)) zugriffErklaeren() }
        }
        listOf(glockeBox, wlan, bt, ort).forEach { z.addView(it, ui.lp(ui.dp(44), ui.dp(44), rechts = 14)) }
        z.addView(View(this), ui.lp(0, 1, 1f))
        val musikIcon = ui.icon(R.drawable.ic_pn_music, ui.p.onSurfaceVariant, 22)
        val musik = ui.text("", 16f, 600, ui.p.onSurfaceVariant).apply { maxLines = 1; maxWidth = ui.dp(380) }
        z.addView(musikIcon, ui.lp(ui.dp(22), ui.dp(22), rechts = 8)); z.addView(musik, ui.lp(rechts = 22))
        val uhr = ui.zahl("", 22f)
        z.addView(uhr, ui.lp(rechts = 16))
        z.addView(ImageView(this).apply {
            setImageDrawable(ContextCompat.getDrawable(this@MainActivity, R.drawable.logo_square))
            clipToOutline = true; outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            background = GradientDrawable().apply { cornerRadius = ui.dpf(10f); setColor(Color.TRANSPARENT) }
            contentDescription = getString(R.string.app_name)
        }, ui.lp(ui.dp(36), ui.dp(36)))
        statusAktualisierer += {
            val an = ui.p.onSurface; val aus = Color.argb(110, Color.red(ui.p.outline), Color.green(ui.p.outline), Color.blue(ui.p.outline))
            val n = MedienDienst.hatBenachrichtigungen()
            glocke.setColorFilter(if (n == null) aus else an); punkt.visibility = if (n == true) View.VISIBLE else View.GONE
            wlan.setColorFilter(if (wlanAn()) an else aus)
            bt.setColorFilter(when (btAn()) { true -> an; else -> aus })
            ort.setColorFilter(if (ortAn()) an else aus)
            val m = medien
            val zeigen = m != null && (m.titel != null)
            musikIcon.visibility = if (zeigen) View.VISIBLE else View.GONE
            musik.text = if (zeigen) listOfNotNull(appName(m!!.paket), m.titel).joinToString(" · ") else ""
            uhr.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        }
        return z
    }

    private fun statusIcon(res: Int) = ui.icon(res, ui.p.onSurface, 26).apply {
        layoutParams = LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)); setPadding(ui.dp(9), ui.dp(9), ui.dp(9), ui.dp(9))
    }

    private fun wlanAn(): Boolean = try {
        val cm = getSystemService(ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    } catch (_: Throwable) { false }

    @SuppressLint("MissingPermission")
    private fun btAn(): Boolean? = try { BluetoothAdapter.getDefaultAdapter()?.isEnabled } catch (_: Throwable) { null }

    private fun ortAn(): Boolean = try { getSystemService(LocationManager::class.java).isLocationEnabled } catch (_: Throwable) { false }

    // ---------- Bearbeiten-Leiste ----------

    private fun bearbeitenLeiste(): View {
        val z = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        z.addView(ui.titel(getString(R.string.bearbeiten_titel)), ui.lp(rechts = 16))
        val knoepfe = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        fun k(res: Int, t: Int, f: () -> Unit) = knoepfe.addView(ui.act(res, getString(t)) { f() }.apply { orientation = LinearLayout.HORIZONTAL; (getChildAt(1) as? TextView)?.setPadding(ui.dp(8), 0, 0, 0) }, ui.lp(h = ui.dp(60), rechts = 10))
        k(R.drawable.ic_pn_plus, R.string.kachel_hinzu) { kachelHinzufuegen() }
        k(R.drawable.ic_pn_apps, R.string.app_hinzu) { schublade(Ziel.STARTSEITE) }
        k(R.drawable.ic_pn_plus, R.string.seite_neu) { aendern(Raster.seiteHinzufuegen(layout)); seite = layout.seiten.size - 1; zeichneSeite() }
        k(R.drawable.ic_pn_close, R.string.seite_weg) { seiteEntfernenFragen() }
        k(R.drawable.ic_pn_edit, R.string.hintergrund) { hintergrundWaehlen() }
        z.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(knoepfe) }, ui.lp(0, -2, 1f))
        z.addView(ui.act(null, getString(R.string.fertig), primary = true) { bearbeiten = false; baueAlles(); kernSpeichern() }, ui.lp(ui.dp(130), ui.dp(60)))
        return z
    }

    // ---------- Reiterzeile ----------

    private fun reiterZeile(): View {
        val z = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        fun reiter(res: Int, t: Int, aktiv: Boolean, f: () -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            addView(ui.icon(res, if (aktiv) ui.p.aqua else ui.p.onSurfaceVariant, 28))
            addView(ui.text(getString(t), 14f, if (aktiv) 700 else 600, if (aktiv) ui.p.onSurface else ui.p.onSurfaceVariant).apply { setPadding(0, ui.dp(4), 0, 0); gravity = Gravity.CENTER; maxLines = 1 })
            if (aktiv) background = GradientDrawable().apply { cornerRadius = ui.dpf(16f); setColor(ui.p.card); setStroke(ui.dp(2), ui.p.aqua) }
            isClickable = true; foreground = ui.welle(); setOnClickListener { f() }
        }
        z.addView(reiter(R.drawable.ic_pn_status, R.string.reiter_status, true) { seite = 0; zeichneSeite() }, ui.lp(ui.dp(104), -1, rechts = 6))
        z.addView(reiter(R.drawable.ic_pn_map, R.string.reiter_karte, false) { starteNavi() }, ui.lp(ui.dp(104), -1, rechts = 6))
        z.addView(reiter(R.drawable.ic_pn_car, R.string.reiter_steuerung, false) { klimaFeld() }, ui.lp(ui.dp(104), -1, rechts = 6))
        z.addView(reiter(R.drawable.ic_pn_apps, R.string.reiter_apps, false) { schublade(null) }, ui.lp(ui.dp(104), -1, rechts = 6))
        z.addView(reiter(R.drawable.ic_pn_settings, R.string.reiter_einstellungen, false) { einstellungen() }, ui.lp(ui.dp(120), -1, rechts = 6))
        // 0.1.37 „Tacho wieder normal“: groß und rot, nur wenn am Fahrerdisplay gerade etwas läuft; ohne Rückfrage.
        val tachoNormal = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            addView(ui.icon(R.drawable.ic_pn_status, ui.p.danger, 28))
            addView(ui.text(getString(R.string.tacho_normal), 14f, 700, ui.p.danger).apply { setPadding(0, ui.dp(4), 0, 0); gravity = Gravity.CENTER; maxLines = 2 })
            background = GradientDrawable().apply { cornerRadius = ui.dpf(16f); setStroke(ui.dp(2), ui.p.danger) }
            contentDescription = getString(R.string.tacho_normal)
            isClickable = true; foreground = ui.welle(); setOnClickListener { tachoNormal() }
            visibility = if (summary?.tachoAktiv == true) View.VISIBLE else View.GONE
        }
        z.addView(tachoNormal, ui.lp(ui.dp(150), -1))
        aktualisierer += { tachoNormal.visibility = if (summary?.tachoAktiv == true) View.VISIBLE else View.GONE }
        z.addView(View(this), ui.lp(0, 1, 1f))
        layout.schnell.forEach { p ->
            z.addView(appKnopf(p, mitName = true, gross = 44) { lange -> if (lange) leistenMenue(p, schnell = true) else starteApp(p) }, ui.lp(ui.dp(96), -1, rechts = 4))
        }
        z.addView(ui.act(R.drawable.ic_pn_plus, getString(R.string.hinzufuegen)) { if (darfBedienen()) schublade(Ziel.SCHNELL) }, ui.lp(ui.dp(104), ui.dp(80)))
        return z
    }

    // ---------- Dock ----------

    private fun dock(): View {
        val z = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(12), 0, ui.dp(12), 0)
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(ui.dpf(22f), ui.dpf(22f), ui.dpf(22f), ui.dpf(22f), 0f, 0f, 0f, 0f)
                setColor(if (ui.cockpit) PanoptesStil.COCKPIT_KARTE_BIS else ui.p.card); setStroke(ui.dp(1), if (ui.cockpit) PanoptesStil.COCKPIT_RAND else ui.p.line)
            }
        }
        // Fest als erstes Icon, nicht entfernbar: Rückweg zum BYD-Startbildschirm.
        z.addView(dockIcon(R.drawable.ic_pn_home, getString(R.string.byd_home), hervor = true) { zumBydStart() }, ui.lp(ui.dp(84), -1, rechts = 6))
        val favs = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        // Android Auto / CarPlay nur, wenn das BYD-Paket hier installiert und startbar ist (andere Autos: unsichtbar)
        layout.dock.filter { it !in Raster.PROJEKTION || startbar(it) }.forEach { p ->
            favs.addView(appKnopf(p, mitName = false, gross = 44) { lange -> if (lange) leistenMenue(p, schnell = false) else starteApp(p) }, ui.lp(ui.dp(76), -1, rechts = 4))
        }
        favs.addView(dockIcon(R.drawable.ic_pn_plus, getString(R.string.hinzufuegen)) { if (darfBedienen()) schublade(Ziel.DOCK) }, ui.lp(ui.dp(76), -1))
        z.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(favs) }, ui.lp(0, -1, 1f))
        if (layout.zeigeKlima) z.addView(klimaBedienung(), ui.lp(-2, ui.dp(64), rechts = 10))
        z.addView(dockIcon(R.drawable.ic_pn_apps, getString(R.string.reiter_apps)) { schublade(null) }, ui.lp(ui.dp(76), -1, rechts = 4))
        z.addView(dockIcon(R.drawable.ic_pn_settings, getString(R.string.reiter_einstellungen)) { einstellungen() }, ui.lp(ui.dp(76), -1))
        return z
    }

    /**
     * Klima im Dock (Stufe 4): Temperatur −/+, Gebläse −/+, an/aus über den eng begrenzten Kern-Weg `klima`.
     * Der Kern prüft, dass die Anfrage von dieser APK kommt, und führt nur über die Panoptes-Klimabefehle mit allen
     * Sperren aus. Tipp auf die Werte öffnet das große Klima-Feld im Launcher (0.1.38.5), nicht Panoptes.
     */
    private fun klimaBedienung(): View {
        val z = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(6), 0, ui.dp(6), 0)
            background = GradientDrawable().apply { cornerRadius = ui.dpf(16f); setColor(ui.p.act); setStroke(ui.dp(1), ui.p.line) }
            contentDescription = getString(R.string.klima_hinweis)
        }
        val tempKnoepfe = mutableListOf<View>()
        fun knopf(res: Int, beschreibung: Int, aktion: () -> String) = FrameLayout(this).apply {
            addView(ui.icon(res, ui.p.onSurface, 24), FrameLayout.LayoutParams(ui.dp(24), ui.dp(24), Gravity.CENTER))
            contentDescription = getString(beschreibung); isClickable = true; foreground = ui.welle()
            setOnClickListener { klimaSenden(aktion()) }
        }
        fun wert(icon: Int, text: TextView) = LinearLayout(this).apply {
            gravity = Gravity.CENTER; setPadding(ui.dp(4), 0, ui.dp(4), 0)
            addView(ui.icon(icon, ui.p.aqua, 22))
            addView(text, ui.lp().apply { marginStart = ui.dp(4) })
            isClickable = true; foreground = ui.welle(); setOnClickListener { klimaFeld() }
        }
        val soll = ui.zahl("—", 21f).apply { minWidth = ui.dp(52); gravity = Gravity.CENTER; maxLines = 1 }
        val stufe = ui.zahl("—", 21f).apply { minWidth = ui.dp(20); gravity = Gravity.CENTER }
        val power = ui.icon(R.drawable.ic_pn_power, ui.p.onSurfaceVariant, 26)
        z.addView(knopf(R.drawable.ic_pn_minus, R.string.klima_temp_runter) { "temp_runter" }.also { tempKnoepfe += it }, ui.lp(ui.dp(DOCK_KNOPF), -1))
        z.addView(wert(R.drawable.ic_pn_thermo, soll), ui.lp(h = -1))
        z.addView(knopf(R.drawable.ic_pn_plus, R.string.klima_temp_hoch) { "temp_hoch" }.also { tempKnoepfe += it }, ui.lp(ui.dp(DOCK_KNOPF), -1, rechts = 6))
        z.addView(knopf(R.drawable.ic_pn_minus, R.string.klima_luft_runter) { "luft_runter" }, ui.lp(ui.dp(DOCK_KNOPF), -1))
        z.addView(wert(R.drawable.ic_pn_fan, stufe), ui.lp(h = -1))
        z.addView(knopf(R.drawable.ic_pn_plus, R.string.klima_luft_hoch) { "luft_hoch" }, ui.lp(ui.dp(DOCK_KNOPF), -1, rechts = 6))
        z.addView(FrameLayout(this).apply {
            addView(power, FrameLayout.LayoutParams(ui.dp(26), ui.dp(26), Gravity.CENTER))
            contentDescription = getString(R.string.klima_an_aus); isClickable = true; foreground = ui.welle()
            // Unbekannter Zustand: „an“ (aus schalten geht über die volle Steuerung in Panoptes)
            setOnClickListener { klimaSenden(if (summary?.klima?.an == true) "aus" else "an") }
        }, ui.lp(ui.dp(52), -1))
        aktualisierer += {
            val k = summary?.klima
            soll.text = sollText(k) ?: "—"
            tempKnoepfe.forEach { it.alpha = if (k?.tempArt == "unbekannt") 0.35f else 1f }
            stufe.text = k?.stufe?.toString() ?: "—"
            power.setColorFilter(if (k?.an == true) ui.p.lime else ui.p.onSurfaceVariant)
        }
        return z
    }

    /** Eine Klimaaktion an den Kern; solange eine läuft, werden weitere Tipps verworfen (der Kern drosselt zusätzlich). */
    private fun klimaSenden(aktion: String) {
        // 0.1.38.6: Modell weder als Stufen- noch als Grad-Modell bestätigt: keine Temperatur-Bedienung, nie raten
        if ((aktion == "temp_hoch" || aktion == "temp_runter") && summary?.klima?.tempArt == "unbekannt") {
            zeigeHinweis(getString(R.string.klima_temp_unbekannt)); return
        }
        if (!klimaLaeuft.compareAndSet(false, true)) return
        klimaIo.execute {
            val antwort = try {
                client.klima(aktion)
            } catch (_: Exception) {
                null
            } finally {
                klimaLaeuft.set(false)
            }
            main.post {
                when {
                    antwort == null -> zeigeHinweis(getString(R.string.offline))
                    antwort.ok -> {
                        antwort.klima?.let { k -> summary = summary?.copy(klima = k); aktualisierer.forEach { it() } }
                        klimaFeldZeichnen?.invoke()
                        klimaRueckmeldung(aktion, antwort.klima)
                    }
                    else -> klimaGrund(antwort.grund)?.let { zeigeHinweis(it) }
                }
            }
        }
    }

    /** „Tacho wieder normal“ an den Kern; Ergebnis als kurzer Hinweis. */
    private fun tachoNormal() {
        klimaIo.execute {
            val ok = client.tachoNormal()
            main.post { zeigeHinweis(getString(if (ok) R.string.tacho_normal_ok else R.string.offline)) }
        }
    }

    /** Text zur Ablehnung; null = still (Ratenlimit, Grenze erreicht). */
    private fun klimaGrund(grund: String?): String? = when (grund) {
        "ratenlimit", "grenze" -> null
        "nicht_launcher", "signatur", "nicht_lokal" -> getString(R.string.klima_nicht_erlaubt)
        "stufe_unbekannt" -> getString(R.string.klima_stufe_unbekannt)
        "blocked_driving", "auth_required", "rate_limited" -> getString(R.string.klima_gesperrt)
        else -> getString(R.string.klima_keine_wirkung)
    }

    // ---------- Update-Karte (Stufe 4) ----------

    /** Panoptes und Launcher: Version, Größe, Installieren; „Später“ blendet diese Version aus. */
    private fun zeichneUpdateKarte() {
        if (!::updateHost.isInitialized) return
        updateHost.removeAllViews()
        val u = updateStand ?: return
        val spaeter = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_SPAETER, "").orEmpty()
        val eintraege = listOfNotNull(
            u.panoptes?.let { Triple("panoptes", it, getString(R.string.update_panoptes, it.version)) },
            u.launcher?.let { Triple("launcher", it, getString(R.string.update_launcher, it.version)) }
        ).filter { (paket, p, _) -> "$paket:${p.versionCode}" !in spaeter.split(',') }
        if (eintraege.isEmpty() || bearbeiten) return
        val karte = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = ui.karte(); setPadding(ui.dp(16), ui.dp(8), ui.dp(12), ui.dp(8))
        }
        karte.addView(ui.icon(R.drawable.ic_pn_download, ui.p.aqua, 28), ui.lp(rechts = 12))
        val texte = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texte.addView(ui.titel(getString(R.string.update_titel)))
        eintraege.forEach { (_, p, name) ->
            val zusatz = listOfNotNull(p.groesseBytes?.let { mb(it) }, updateGrund(p)).joinToString(" · ")
            texte.addView(ui.text(if (zusatz.isEmpty()) name else "$name · $zusatz", 15f, 600).apply { maxLines = 2 })
        }
        karte.addView(texte, ui.lp(0, -2, 1f))
        eintraege.forEach { (paket, p, _) ->
            if (p.phase == null) karte.addView(ui.act(null, getString(R.string.update_installieren) + if (eintraege.size > 1) " · " + if (paket == "panoptes") "Panoptes" else "Launcher" else "", primary = true) {
                updateInstallieren(paket, p)
            }, ui.lp(h = ui.dp(56), rechts = 8))
        }
        karte.addView(ui.act(null, getString(R.string.update_spaeter)) {
            val neu = (spaeter.split(',').filter { it.isNotBlank() } + eintraege.map { (paket, p, _) -> "$paket:${p.versionCode}" }).takeLast(6)
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_SPAETER, neu.joinToString(",")).apply()
            zeichneUpdateKarte()
        }, ui.lp(h = ui.dp(56)))
        updateHost.addView(karte, FrameLayout.LayoutParams(-1, -2))
    }

    private fun mb(b: Long) = "${nf1.format(b / 1_048_576.0)} MB"

    private fun updateGrund(p: UpdatePaket): String? = when {
        p.phase != null -> getString(R.string.update_laeuft)
        p.installierbar || p.mobilFrage -> null
        p.nichtWeil == "faehrt" || p.nichtWeil == "nicht_in_p" -> getString(R.string.update_nur_im_stand)
        p.nichtWeil == "zuendung_an" || p.nichtWeil == "zustand_unbekannt" -> getString(R.string.update_nach_parken)
        else -> null
    }

    /** Nicht während der Fahrt; ohne WLAN erst Rückfrage mit Größe (Einwilligung nur für diesen Download). */
    private fun updateInstallieren(paket: String, p: UpdatePaket) {
        if (!darfBedienen()) return
        if (p.mobilFrage) {
            AlertDialog.Builder(this).setTitle(R.string.update_titel)
                .setMessage(getString(R.string.update_mobil_frage, p.groesseBytes?.let { mb(it) } ?: getString(R.string.update_groesse_unbekannt)))
                .setPositiveButton(R.string.update_installieren) { _, _ -> updateSenden(paket, p, true) }
                .setNegativeButton(R.string.abbrechen, null).show()
        } else updateSenden(paket, p, false)
    }

    private fun updateSenden(paket: String, p: UpdatePaket, mobil: Boolean) {
        io.execute {
            val text = try {
                val r = org.json.JSONObject(client.installieren(paket, p, mobil))
                when {
                    r.optString("text").isNotBlank() -> r.optString("text")
                    r.optBoolean("ok", false) -> getString(R.string.update_gestartet)
                    else -> getString(R.string.update_fehler)
                }
            } catch (_: Exception) {
                getString(R.string.offline)
            }
            lastUpdateFetch = 0L
            main.post { zeigeHinweis(text) }
        }
    }

    private fun dockIcon(res: Int, label: String, hervor: Boolean = false, f: () -> Unit) = FrameLayout(this).apply {
        val box = FrameLayout(this@MainActivity).apply {
            background = GradientDrawable().apply { cornerRadius = ui.dpf(16f); setColor(if (hervor) ui.p.act else Color.TRANSPARENT); if (hervor) setStroke(ui.dp(1), ui.p.aqua) }
            addView(ui.icon(res, if (hervor) ui.p.aqua else ui.p.onSurface, 30), FrameLayout.LayoutParams(ui.dp(30), ui.dp(30), Gravity.CENTER))
        }
        addView(box, FrameLayout.LayoutParams(ui.dp(64), ui.dp(64), Gravity.CENTER))
        contentDescription = label; isClickable = true; foreground = ui.welle(); setOnClickListener { f() }
    }

    /** App-Icon (aus dem PackageManager) mit optionalem Namen; langer Druck meldet sich mit true. */
    private fun appKnopf(paket: String, mitName: Boolean, gross: Int, klick: (Boolean) -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        val a = appsNachPaket[paket]
        addView(ImageView(this@MainActivity).apply {
            if (a?.icon != null) setImageDrawable(a.icon) else setImageDrawable(ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_pn_apps)?.mutate()?.also { it.setTint(ui.p.outline) })
        }, LinearLayout.LayoutParams(ui.dp(gross), ui.dp(gross)))
        if (mitName) addView(ui.text(a?.name ?: paket, 13f, 600, ui.p.onSurfaceVariant).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER; setPadding(ui.dp(2), ui.dp(4), ui.dp(2), 0)
        }, LinearLayout.LayoutParams(-1, -2))
        contentDescription = a?.name ?: paket
        isClickable = true; foreground = ui.welle()
        setOnClickListener { klick(false) }
        setOnLongClickListener { klick(true); true }
    }

    // ==================== Seiten und Kacheln ====================

    /** Wischen zwischen den Seiten (nicht während eine Kachel gezogen wird). */
    private inner class Wischer : FrameLayout(this@MainActivity) {
        private var startX = 0f; private var startY = 0f
        private val schwelle = ViewConfiguration.get(context).scaledTouchSlop * 3
        var zieht = false
        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX = e.x; startY = e.y }
                MotionEvent.ACTION_MOVE -> if (!zieht && abs(e.x - startX) > schwelle && abs(e.x - startX) > abs(e.y - startY) * 1.5f) return true
            }
            return false
        }
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                val dx = e.x - startX
                if (dx < -ui.dp(80) && seite < layout.seiten.size - 1) { seite++; main.post { zeichneSeite(1) } }
                else if (dx > ui.dp(80) && seite > 0) { seite--; main.post { zeichneSeite(-1) } }
            }
            return true
        }
    }

    private fun zeichneSeite(richtung: Int = 0) {
        aktualisierer.removeAll { it is KachelAktualisierer }
        seitenHost.removeAllViews()
        val r = RasterLayout(this, Raster.SPALTEN, Raster.ZEILEN, ui.dp(14))
        raster = r
        layout.seiten[seite].forEach { e -> r.addView(baueElement(e), RasterLayout.Zelle(e.x, e.y, e.b, e.h)) }
        if (bearbeiten) {
            // freie Zellen sichtbar machen
            for (y in 0 until Raster.ZEILEN) for (x in 0 until Raster.SPALTEN) {
                if (Raster.passt(layout.seiten[seite], x, y, 1, 1)) r.addView(View(this).apply {
                    background = GradientDrawable().apply { cornerRadius = ui.dpf(16f); setStroke(ui.dp(1), ui.p.line, ui.dpf(8f), ui.dpf(6f)) }
                }, 0, RasterLayout.Zelle(x, y, 1, 1))
            }
        }
        seitenHost.addView(r, FrameLayout.LayoutParams(-1, -1))
        if (richtung != 0) { r.translationX = richtung * ui.dpf(120f); r.alpha = 0f; r.animate().translationX(0f).alpha(1f).setDuration(240).start() }
        punkte.removeAllViews()
        if (layout.seiten.size > 1) layout.seiten.indices.forEach { i ->
            punkte.addView(View(this).apply {
                background = GradientDrawable().apply { cornerRadius = ui.dpf(4f); setColor(if (i == seite) ui.p.aqua else ui.p.line) }
            }, ui.lp(ui.dp(if (i == seite) 22 else 8), ui.dp(8), rechts = 6))
        }
        aktualisierer.forEach { it() }
    }

    private class KachelAktualisierer(val f: () -> Unit) : () -> Unit { override fun invoke() = f() }

    private fun baueElement(e: Element): View {
        val box = FrameLayout(this)
        val hero = e.typ == ElementTyp.WIDGET && (e.kachel == Kachel.AKKU || e.kachel == Kachel.FAHRZEUG) && e.b >= 2 && e.h >= 2
        box.background = if (e.typ == ElementTyp.WIDGET) ui.karte(hero) else null
        val inhalt: View = when (e.typ) {
            ElementTyp.WIDGET -> widget(e)
            ElementTyp.APP -> appKnopf(e.paket!!, mitName = true, gross = 64) { lange -> if (lange) langerDruck(e) else starteApp(e.paket) }.apply {
                background = ui.karte(); setPadding(ui.dp(6), ui.dp(8), ui.dp(6), ui.dp(8))
            }
            ElementTyp.ORDNER -> ordnerKachel(e)
        }
        box.addView(inhalt, FrameLayout.LayoutParams(-1, -1))
        if (bearbeiten) bearbeitenSchicht(box, e)
        return box
    }

    private fun langerDruck(e: Element) {
        if (!darfBedienen()) return
        bearbeiten = true; baueAlles()
    }

    // ---------- Bearbeiten-Modus: ziehen, Größe, entfernen ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun bearbeitenSchicht(box: FrameLayout, e: Element) {
        box.foreground = GradientDrawable().apply { cornerRadius = ui.dpf(20f); setStroke(ui.dp(2), ui.p.lime) }
        val fang = View(this)
        box.addView(fang, FrameLayout.LayoutParams(-1, -1))
        var rx = 0f; var ry = 0f; var zieht = false
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        fang.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { rx = ev.rawX; ry = ev.rawY; zieht = false; box.parent.requestDisallowInterceptTouchEvent(true); seitenHost.zieht = true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - rx; val dy = ev.rawY - ry
                    if (!zieht && (abs(dx) > slop || abs(dy) > slop)) { zieht = true; box.elevation = ui.dpf(12f); box.alpha = 0.85f }
                    if (zieht) { box.translationX = dx; box.translationY = dy }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    seitenHost.zieht = false
                    if (zieht) {
                        val r = raster!!
                        val (x, y) = r.zelleBei(box.left + box.translationX + r.zellBreite / 2, box.top + box.translationY + r.zellHoehe / 2, e.b, e.h)
                        val neu = Raster.verschieben(layout, e.id, seite, x, y)
                        if (neu === layout) zeigeHinweis(getString(R.string.kein_platz))
                        aendern(neu); main.post { zeichneSeite() }
                    } else if (ev.actionMasked == MotionEvent.ACTION_UP) kachelMenue(e)
                }
            }
            true
        }
        fun abzeichen(res: Int, schwerkraft: Int, f: () -> Unit) = box.addView(FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ui.p.lime) }
            addView(ui.icon(res, ui.p.onPrimary, 22), FrameLayout.LayoutParams(ui.dp(22), ui.dp(22), Gravity.CENTER))
            isClickable = true; setOnClickListener { f() }
        }, FrameLayout.LayoutParams(ui.dp(48), ui.dp(48), schwerkraft).apply { setMargins(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)) })
        abzeichen(R.drawable.ic_pn_close, Gravity.TOP or Gravity.END) { aendern(Raster.entfernen(layout, e.id)); main.post { zeichneSeite() } }
        if (e.typ == ElementTyp.WIDGET) abzeichen(R.drawable.ic_pn_resize, Gravity.BOTTOM or Gravity.END) {
            val neu = Raster.groesseWechseln(layout, e.id)
            if (neu === layout) zeigeHinweis(getString(R.string.kein_platz))
            aendern(neu); main.post { zeichneSeite() }
        }
    }

    private fun kachelMenue(e: Element) {
        val punkte = mutableListOf<Pair<String, () -> Unit>>()
        if (e.typ == ElementTyp.WIDGET) punkte += getString(R.string.groesse) to { aendern(Raster.groesseWechseln(layout, e.id)); zeichneSeite() }
        if (e.typ == ElementTyp.APP) {
            layout.seiten.flatten().filter { it.typ == ElementTyp.ORDNER }.forEach { o ->
                punkte += getString(R.string.in_ordner, o.name) to { aendern(Raster.inOrdner(layout, e.id, o.id)); zeichneSeite() }
            }
            punkte += getString(R.string.neuer_ordner) to { nameFragen(getString(R.string.neuer_ordner), "") { n -> aendern(Raster.neuerOrdner(layout, e.id, n)); zeichneSeite() } }
            punkte += getString(R.string.app_info) to { appInfo(e.paket!!) }
        }
        if (e.typ == ElementTyp.ORDNER) punkte += getString(R.string.umbenennen) to { nameFragen(getString(R.string.umbenennen), e.name ?: "") { n -> aendern(Raster.umbenennen(layout, e.id, n)); zeichneSeite() } }
        layout.seiten.indices.filter { it != seite }.forEach { i ->
            punkte += getString(R.string.auf_seite, i + 1) to {
                val neu = Raster.aufSeite(layout, e.id, i)
                if (neu === layout) zeigeHinweis(getString(R.string.kein_platz)); aendern(neu); zeichneSeite()
            }
        }
        punkte += getString(R.string.entfernen) to { aendern(Raster.entfernen(layout, e.id)); zeichneSeite() }
        auswahl(titelVon(e), punkte)
    }

    private fun titelVon(e: Element) = when (e.typ) {
        ElementTyp.WIDGET -> kachelName(e.kachel!!)
        ElementTyp.APP -> appName(e.paket!!)
        ElementTyp.ORDNER -> e.name ?: ""
    }

    private fun kachelName(k: Kachel) = getString(when (k) {
        Kachel.FAHRZEUG -> R.string.widget_fahrzeug; Kachel.AKKU -> R.string.widget_akku; Kachel.NAVI -> R.string.widget_navi
        Kachel.MUSIK -> R.string.widget_musik; Kachel.WAECHTER -> R.string.widget_waechter; Kachel.UHR -> R.string.widget_uhr
    })

    private fun kachelHinzufuegen() {
        val fehlend = Kachel.values().filter { !Raster.hatWidget(layout, it) }
        if (fehlend.isEmpty()) { zeigeHinweis(getString(R.string.alle_kacheln_da)); return }
        auswahl(getString(R.string.kachel_hinzu), fehlend.map { k ->
            kachelName(k) to {
                val neu = Raster.widgetHinzufuegen(layout, seite, k)
                if (neu === layout) zeigeHinweis(getString(R.string.kein_platz)); aendern(neu); zeichneSeite()
            }
        })
    }

    private fun seiteEntfernenFragen() {
        if (layout.seiten.size <= 1) return
        AlertDialog.Builder(this).setTitle(R.string.seite_weg).setMessage(getString(R.string.seite_weg_frage, seite + 1))
            .setPositiveButton(R.string.entfernen) { _, _ -> aendern(Raster.seiteEntfernen(layout, seite)); seite = (seite - 1).coerceAtLeast(0); zeichneSeite() }
            .setNegativeButton(R.string.abbrechen, null).show()
    }

    // ---------- Widgets ----------

    private fun widget(e: Element): View {
        val k = e.kachel!!
        val gross = e.b >= 2 && e.h >= 2
        val v = when (k) {
            Kachel.FAHRZEUG -> if (e.b >= 3 && e.h >= 2) fahrzeugFeld() else fahrzeugKlein(gross)
            Kachel.AKKU -> akku(e)
            Kachel.NAVI -> navi(gross)
            Kachel.MUSIK -> medienKarte(e)
            Kachel.WAECHTER -> waechter(gross)
            Kachel.UHR -> uhrKachel(gross)
        }
        // 0.1.38.5: direkt im Launcher; Panoptes nur über „Mehr in Panoptes“ im jeweiligen Feld
        when (k) {
            Kachel.FAHRZEUG -> v.setOnClickListener { fahrzeugInfo() }
            Kachel.AKKU -> v.setOnClickListener { akkuInfo() }
            Kachel.NAVI -> v.setOnClickListener { starteNavi() }
            Kachel.WAECHTER -> v.setOnClickListener { waechterInfo() }
            else -> {}
        }
        v.setOnLongClickListener { langerDruck(e); true }
        return v
    }

    private fun spalte(pad: Int = 20) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(pad), ui.dp(pad - 2), ui.dp(pad), ui.dp(pad - 2)) }
    private fun reg(f: () -> Unit) { aktualisierer += KachelAktualisierer(f) }
    private val u get() = getString(R.string.unknown)

    /** Großes Fahrzeug-Feld: 3D-Modell aus dem Katalog, Datum/Uhr, Reifen, Gang, Tempo, Akku. */
    private fun fahrzeugFeld(): View {
        val f = FrameLayout(this)
        // 3D-Fahrzeug aus Panoptes (WebView, gleicher Katalog und Renderer), bis es gezeichnet ist ein ruhiges Standardbild
        val mitte = FrameLayout(this)
        f.addView(mitte, FrameLayout.LayoutParams(-1, -1).apply { setMargins(ui.dp(120), ui.dp(40), ui.dp(170), ui.dp(96)) })
        val ersatz = ImageView(this).apply {
            setImageDrawable(ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_pn_auto_seite)?.mutate()?.also { it.setTint(ui.p.outline) })
            scaleType = ImageView.ScaleType.FIT_CENTER; alpha = 0.8f
        }
        mitte.addView(ersatz, FrameLayout.LayoutParams(ui.dp(360), ui.dp(144), Gravity.CENTER))
        // oben links: Datum, Uhr, Luft (nur mit echtem Wert)
        val links = spalte(18)
        val datum = ui.text("", 17f, 600, ui.p.onSurfaceVariant); val uhr = ui.zahl("", 34f)
        val luft = ui.leise("", 14f)
        links.addView(datum); links.addView(uhr); links.addView(luft, ui.lp(oben = 10))
        f.addView(links, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        // oben rechts: Reifen rund ums Auto
        val reifen = GridLayout(this).apply { columnCount = 3; rowCount = 2; setPadding(ui.dp(12), ui.dp(16), ui.dp(18), ui.dp(12)) }
        val rz = Array(4) { ui.zahl("—", 20f).apply { gravity = Gravity.CENTER } }
        reifen.addView(rz[0]); reifen.addView(ui.icon(R.drawable.ic_pn_car, ui.p.onSurfaceVariant, 40).apply { rotation = 0f }, GridLayout.LayoutParams(GridLayout.spec(0, 2), GridLayout.spec(1)).apply { width = ui.dp(48); height = ui.dp(72); setGravity(Gravity.CENTER) })
        reifen.addView(rz[1]); reifen.addView(rz[2]); reifen.addView(rz[3])
        rz.forEach { (it.layoutParams as? GridLayout.LayoutParams)?.setMargins(ui.dp(6), ui.dp(4), ui.dp(6), ui.dp(4)) }
        val reifenBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        reifenBox.addView(ui.titel(getString(R.string.reifen)).apply { setPadding(0, ui.dp(16), ui.dp(18), 0) })
        reifenBox.addView(reifen)
        f.addView(reifenBox, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
        // unten: Gang, Tempo, Akku
        val unten = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL; setPadding(ui.dp(18), 0, ui.dp(18), ui.dp(14)) }
        val gaenge = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6))
            background = GradientDrawable().apply { cornerRadius = ui.dpf(999f); setColor(ui.p.act); setStroke(ui.dp(1), ui.p.line) }
        }
        val gang = listOf("P", "R", "N", "D").associateWith { g -> ui.zahl(g, 22f).apply { setPadding(ui.dp(10), 0, ui.dp(10), 0) }.also { gaenge.addView(it) } }
        unten.addView(gaenge, ui.lp(rechts = 36))
        val tempoBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val tempo = ui.zahl("—", 60f); tempoBox.addView(tempo, LinearLayout.LayoutParams(-2, -2)); tempoBox.addView(ui.leise("km/h", 15f))   // WRAP_CONTENT, sonst nur das erste Zeichen
        unten.addView(tempoBox, ui.lp(rechts = 36))
        val akkuBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        akkuBox.addView(ui.icon(R.drawable.ic_pn_battery, ui.p.aqua, 34))
        val soc = ui.zahl("—", 34f); akkuBox.addView(soc, LinearLayout.LayoutParams(-2, -2))   // WRAP_CONTENT, sonst zeigt die Zahl nur die erste Ziffer
        // 0.1.38.8: Akku-Gesundheit wie die Web-Akku-Karte, „Auto x · Schätzung y“
        val gesundStart = ui.text("", 16f, 600, ui.p.onSurfaceVariant).apply { maxLines = 1 }
        akkuBox.addView(gesundStart, LinearLayout.LayoutParams(-2, -2))
        unten.addView(akkuBox)
        f.addView(unten, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        reg {
            val s = summary
            val jetzt = Date()
            datum.text = SimpleDateFormat("EEE, d. MMM", Locale.getDefault()).format(jetzt)
            uhr.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(jetzt)
            luft.text = s?.luft?.let { l -> getString(R.string.luft_format, l.innen?.let { nf.format(it) } ?: u, l.aussen?.let { nf.format(it) } ?: u) } ?: ""
            luft.visibility = if (s?.luft != null) View.VISIBLE else View.GONE
            val r = s?.reifen
            listOf(r?.vl, r?.vr, r?.hl, r?.hr).forEachIndexed { i, bar ->
                rz[i].text = bar?.let { nf1.format(it) } ?: "—"
                val warn = bar != null && ((r?.unten != null && bar < r.unten) || (r?.oben != null && bar > r.oben))
                rz[i].setTextColor(if (warn) ui.p.warning else ui.p.onSurface)
            }
            gang.forEach { (g, tv) -> tv.setTextColor(if (s?.gear == g) ui.p.aqua else ui.p.outline) }
            tempo.text = s?.speedKmh?.let { nf.format(it) } ?: "—"
            soc.text = s?.socPct?.let { "${nf.format(it)}%" } ?: "—"
            gesundStart.text = gesundheitWerte(s) ?: ""
            gesundStart.visibility = if (gesundStart.text.isEmpty()) View.GONE else View.VISIBLE
            fahrzeug3d(mitte, ersatz, s)
        }
        return f
    }

    /** 3D-Modell: dieselbe Seite wie in Panoptes (Modellkatalog, Renderer ev-card-3d.js), nur bei Bedarf geladen. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun fahrzeug3d(mitte: FrameLayout, ersatz: View, s: LauncherSummary?) {
        val modell = s?.modelId
        standbildLaden(ersatz as? ImageView, modell)
        if (!layout.zeige3d || modell == null || !online) {
            ersatz.visibility = View.VISIBLE
            webAuto?.let { (it.parent as? ViewGroup)?.removeView(it) }
            return
        }
        val url = PanoptesClient.FAHRZEUG_SEITE + "?modell=" + Uri.encode(modell) + "&farbe=" + Uri.encode(s.farbe ?: "#E8E8EC") + "&ansicht=front"
        val neu = webAuto == null
        val w = webAuto ?: object : WebView(this) {
            // Berührungen gehen an die Kachel (Tipp öffnet Panoptes), nicht an die Seite.
            override fun dispatchTouchEvent(event: MotionEvent?) = false
        }.apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false
            isClickable = false; isFocusable = false
        }.also { webAuto = it }
        if (neu) {
            // Die Seite meldet über ihren Titel, ob das Modell wirklich gezeichnet ist („bereit“) oder nicht („fehler“).
            w.webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onReceivedTitle(view: WebView?, title: String?) {
                    autoGezeichnet = title == "bereit"
                    autoErsatz?.visibility = if (autoGezeichnet) View.GONE else View.VISIBLE
                    view?.visibility = if (autoGezeichnet) View.VISIBLE else View.INVISIBLE
                }
            }
        }
        autoErsatz = ersatz
        if (w.parent !== mitte) { (w.parent as? ViewGroup)?.removeView(w); mitte.addView(w, FrameLayout.LayoutParams(-1, -1)) }
        if (url != webAutoUrl) { webAutoUrl = url; autoGezeichnet = false; w.visibility = View.INVISIBLE; w.loadUrl(url) }
        ersatz.visibility = if (autoGezeichnet) View.GONE else View.VISIBLE
    }

    private var autoGezeichnet = false
    private var standbildFuer: String? = null

    /** Standbild aus Panoptes (`/api/models/art`) einmal je Modell holen; ohne Antwort bleibt die Silhouette. */
    private fun standbildLaden(bildView: ImageView?, modell: String?) {
        if (bildView == null || modell == null || !online || modell == standbildFuer) return
        standbildFuer = modell
        io.execute {
            val bmp = runCatching { client.fahrzeugBild(modell) }.getOrNull()?.let { dekodieren(it) }
            main.post {
                if (bmp != null) { bildView.setImageBitmap(bmp); bildView.alpha = 1f } else standbildFuer = null
            }
        }
    }
    private var autoErsatz: View? = null

    private fun fahrzeugKlein(gross: Boolean): View {
        val c = spalte()
        val titel = ui.titel(getString(R.string.widget_fahrzeug)); c.addView(titel)
        val reichweite = ui.zahl("—", if (gross) 44f else 30f); c.addView(reichweite, ui.lp(oben = 8))
        val zustand = ui.leise(""); c.addView(zustand, ui.lp(oben = 6))
        val verkehr = ui.leise(""); val verlauf = ui.leise("")
        if (gross) { c.addView(verkehr, ui.lp(oben = 10)); c.addView(verlauf, ui.lp(oben = 4)) }
        reg {
            val s = summary
            s?.vehicleName?.let { titel.text = it.uppercase() }
            reichweite.text = getString(R.string.range_format, s?.rangeKm?.let { nf.format(it) } ?: u)
            zustand.text = getString(R.string.state_format, s?.acc ?: u, s?.gear ?: u)
            verkehr.text = if (traffic.nearby == 0) getString(R.string.traffic_empty) else getString(R.string.traffic_format, traffic.nearby, traffic.approaching)
            val h = cachedHistory
            verlauf.text = if (h.lastTripKm == null && h.lastChargeKwh == null) getString(R.string.history_empty)
            else getString(R.string.history_format, h.lastTripKm?.let { nf.format(it) } ?: u, h.lastChargeKwh?.let { nf.format(it) } ?: u)
        }
        return c
    }

    /** Akku wie der Hero der Startseite: Pegel + Reichweite + Laden. */
    private fun akku(e: Element): View {
        val z = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(22), ui.dp(18), ui.dp(22), ui.dp(18)) }
        val pegel = Pegel(this, ui)
        val mitPegel = e.b >= 2 && e.h >= 2
        if (mitPegel) z.addView(pegel, ui.lp(ui.dp(120), ui.dp(150), rechts = 22))
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(ui.titel(getString(R.string.widget_akku)))
        val soc = ui.zahl("—", 30f); if (!mitPegel) c.addView(soc, ui.lp(oben = 6))
        val reichweite = ui.zahl("—", if (mitPegel) 48f else 22f); c.addView(reichweite, ui.lp(oben = 6))
        val laden = ui.text("", 14f, 600, ui.p.lime); c.addView(laden, ui.lp(oben = 8))
        // Akku-Gesundheit: kleine Bezeichnung, große Werte (Regel 50+)
        val gesundTitel = ui.leise(getString(R.string.akku_gesundheit_titel), 14f); c.addView(gesundTitel, ui.lp(oben = 6))
        val gesund = ui.zahl("", 20f); c.addView(gesund, ui.lp(oben = 2))
        z.addView(c, ui.lp(0, -2, 1f))
        reg {
            val s = summary
            pegel.soc = s?.socPct
            soc.text = s?.socPct?.let { "${nf.format(it)}%" } ?: "—"
            reichweite.text = s?.rangeKm?.let { "${nf.format(it)} km" } ?: "— km"
            val ch = s?.charging
            laden.text = when {
                ch == null -> ""
                ch.active -> listOfNotNull(getString(R.string.akku_laedt, ch.kw?.let { nf1.format(it) } ?: u),
                    ch.targetPct?.let { getString(R.string.akku_ziel, nf.format(it)) }, ch.etaMin?.let { getString(R.string.akku_eta, nf.format(it)) }).joinToString(" · ")
                else -> getString(R.string.akku_laedt_nicht)
            }
            laden.setTextColor(if (ch?.active == true) ui.p.lime else ui.p.onSurfaceVariant)
            gesund.text = gesundheitWerte(s) ?: ""
            gesund.visibility = if (gesund.text.isEmpty()) View.GONE else View.VISIBLE
            gesundTitel.visibility = gesund.visibility
        }
        return z
    }

    private fun navi(gross: Boolean): View {
        val c = spalte()
        c.addView(LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; addView(ui.icon(R.drawable.ic_pn_nav, ui.p.aqua, 20)); addView(ui.titel(getString(R.string.widget_navi)).apply { setPadding(ui.dp(8), 0, 0, 0) }) })
        val km = ui.zahl("—", if (gross) 40f else 26f); c.addView(km, ui.lp(oben = 8))
        val stopp = ui.text("", 15f, 600); c.addView(stopp, ui.lp(oben = 4))
        val prog = ui.leise(""); c.addView(prog, ui.lp(oben = 4))
        reg {
            val n = summary?.navi
            if (n != null && n.aktiv && n.naechsterStopp != null) {
                km.text = n.naechsterKm?.let { getString(R.string.navi_km, nf.format(it)) } ?: "—"
                stopp.text = getString(if (n.istZiel) R.string.navi_ziel else R.string.navi_stopp, n.naechsterStopp)
                prog.text = n.prognoseProzent?.let { getString(R.string.navi_prognose, nf.format(it)) } ?: ""
            } else { km.text = "—"; stopp.text = getString(R.string.navi_keine); prog.text = "" }
        }
        return c
    }

    private fun waechter(gross: Boolean): View {
        val c = spalte()
        c.addView(LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; addView(ui.icon(R.drawable.ic_pn_shield, ui.p.aqua, 20)); addView(ui.titel(getString(R.string.widget_waechter)).apply { setPadding(ui.dp(8), 0, 0, 0) }) })
        val an = ui.zahl("—", if (gross) 40f else 26f); c.addView(an, ui.lp(oben = 8))
        val info = ui.leise(""); c.addView(info, ui.lp(oben = 4))
        reg {
            val w = summary?.waechter
            an.text = when (w?.an) { true -> getString(R.string.waechter_an); false -> getString(R.string.waechter_aus); null -> "—" }
            an.setTextColor(if (w?.an == true) ui.p.lime else ui.p.onSurface)
            info.text = listOfNotNull(if (w?.scharf == true) getString(R.string.waechter_scharf) else null,
                if (w?.aufnahme == true) getString(R.string.waechter_aufnahme) else null).joinToString(" · ")
        }
        return c
    }

    private fun uhrKachel(gross: Boolean): View {
        val c = spalte().apply { gravity = Gravity.CENTER_VERTICAL }
        c.addView(ui.titel(getString(R.string.widget_uhr)))
        val zeit = ui.zahl("", if (gross) 64f else 44f); c.addView(zeit, ui.lp(oben = 4))
        val datum = ui.leise("", 15f); c.addView(datum)
        reg {
            val d = Date()
            zeit.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(d)
            datum.text = SimpleDateFormat("EEEE, d. MMMM", Locale.getDefault()).format(d)
        }
        return c
    }

    /** Medien-Karte: Quelle, Cover, Titel, Fortschritt, Knöpfe. Daten nur aus der Android-Mediensitzung (mit Zugriff). */
    private fun medienKarte(e: Element): View {
        val c = spalte(20)
        val quelle = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val qIcon = ImageView(this); val qName = ui.text("", 18f, 700)
        quelle.addView(qIcon, ui.lp(ui.dp(30), ui.dp(30), rechts = 10)); quelle.addView(qName)
        c.addView(ui.titel(getString(R.string.widget_musik)))
        c.addView(quelle, ui.lp(oben = 10))
        val cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
            background = GradientDrawable().apply { cornerRadius = ui.dpf(16f); setColor(ui.p.act) }
        }
        val coverGross = e.h >= 3
        if (e.h >= 2) c.addView(FrameLayout(this).apply { addView(cover, FrameLayout.LayoutParams(ui.dp(if (coverGross) 170 else 96), ui.dp(if (coverGross) 170 else 96), Gravity.CENTER)) }, ui.lp(-1, 0, 1f, oben = 10))
        val titel = ui.zahl("—", 22f).apply { ellipsize = android.text.TextUtils.TruncateAt.END }
        val kuenstler = ui.leise("", 15f).apply { maxLines = 1 }
        c.addView(titel, ui.lp(oben = 10)); c.addView(kuenstler, ui.lp(oben = 2))
        val balken = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progressTintList = android.content.res.ColorStateList.valueOf(ui.p.lime)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(ui.p.line)
        }
        c.addView(balken, ui.lp(-1, ui.dp(10), oben = 10))
        val tasten = LinearLayout(this).apply { gravity = Gravity.CENTER }
        val playPause = ui.act(R.drawable.ic_pn_play, null, primary = true) { medienTaste(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
        tasten.addView(ui.act(R.drawable.ic_pn_prev, null) { medienTaste(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }.apply { contentDescription = getString(R.string.musik_zurueck) }, ui.lp(ui.dp(72), ui.dp(64), rechts = 12))
        tasten.addView(playPause.apply { contentDescription = getString(R.string.musik_pause) }, ui.lp(ui.dp(84), ui.dp(72), rechts = 12))
        tasten.addView(ui.act(R.drawable.ic_pn_next, null) { medienTaste(KeyEvent.KEYCODE_MEDIA_NEXT) }.apply { contentDescription = getString(R.string.musik_weiter) }, ui.lp(ui.dp(72), ui.dp(64)))
        c.addView(tasten, ui.lp(-1, -2, oben = 12))
        val zugriff = ui.act(null, getString(R.string.medien_zugriff)) { zugriffErklaeren() }
        c.addView(zugriff, ui.lp(-1, ui.dp(56), oben = 8))
        reg {
            val m = medien
            val erlaubt = MedienQuelle.zugriffErlaubt(this)
            zugriff.visibility = if (erlaubt) View.GONE else View.VISIBLE
            if (m == null) {
                qIcon.setImageDrawable(null); qName.text = getString(if (erlaubt) R.string.medien_nichts else R.string.medien_ohne_zugriff)
                titel.text = "—"; kuenstler.text = ""; balken.progress = 0; cover.setImageDrawable(null)
            } else {
                qIcon.setImageDrawable(appsNachPaket[m.paket]?.icon); qName.text = appName(m.paket)
                titel.text = m.titel ?: "—"; kuenstler.text = m.kuenstler ?: ""
                balken.progress = MedienQuelle.fortschritt(m.positionMs, m.dauerMs) ?: 0
                if (m.cover != null) cover.setImageBitmap(m.cover) else cover.setImageDrawable(appsNachPaket[m.paket]?.icon)
            }
            ((playPause.getChildAt(0)) as? ImageView)?.setImageDrawable(ContextCompat.getDrawable(this, if (m?.spielt == true) R.drawable.ic_pn_pause else R.drawable.ic_pn_play)?.mutate()?.also { it.setTint(ui.p.onPrimary) })
        }
        return c
    }

    private fun ordnerKachel(e: Element): View {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = ui.karte(); setPadding(ui.dp(6), ui.dp(8), ui.dp(6), ui.dp(8))
            isClickable = true; foreground = ui.welle()
        }
        val g = GridLayout(this).apply { columnCount = 2 }
        e.apps.take(4).forEach { p ->
            g.addView(ImageView(this).apply { setImageDrawable(appsNachPaket[p]?.icon) }, GridLayout.LayoutParams().apply { width = ui.dp(30); height = ui.dp(30); setMargins(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3)) })
        }
        c.addView(g)
        c.addView(ui.text(e.name ?: "", 13f, 600, ui.p.onSurfaceVariant).apply { maxLines = 1; gravity = Gravity.CENTER; setPadding(0, ui.dp(4), 0, 0) })
        c.setOnClickListener { ordnerOeffnen(e) }
        c.setOnLongClickListener { langerDruck(e); true }
        return c
    }

    // ==================== Overlays: Schublade, Ordner, Einstellungen ====================

    private enum class Ziel { DOCK, SCHNELL, STARTSEITE }

    private fun zeigeOverlay(inhalt: View, breit: Boolean = true) {
        schliesseOverlay()
        val o = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(if (ui.p.hell) 90 else 170, 0, 0, 0)); isClickable = true
            setOnClickListener { schliesseOverlay() }
        }
        val karte = FrameLayout(this).apply {
            background = GradientDrawable().apply { cornerRadius = ui.dpf(24f); setColor(ui.p.surface); setStroke(ui.dp(1), ui.p.line) }
            isClickable = true
            addView(inhalt, FrameLayout.LayoutParams(-1, -1))
        }
        o.addView(karte, if (breit) FrameLayout.LayoutParams(-1, -1).apply { setMargins(ui.dp(40), ui.dp(30), ui.dp(40), ui.dp(30)) }
        else FrameLayout.LayoutParams(ui.dp(560), -2, Gravity.CENTER))
        root.addView(o, FrameLayout.LayoutParams(-1, -1))
        overlay = o
    }

    private fun schliesseOverlay() { overlay?.let { root.removeView(it) }; overlay = null; klimaFeldZeichnen = null }

    private fun kopf(titel: String, rechts: View? = null) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(ui.zahl(titel, 26f), ui.lp(0, -2, 1f))
        rechts?.let { addView(it, ui.lp(rechts = 12)) }
        addView(ui.act(R.drawable.ic_pn_close, null) { schliesseOverlay() }, ui.lp(ui.dp(64), ui.dp(64)))
    }

    /** App-Schublade: alle startbaren Apps mit Suche, Icons und Namen. [ziel] = Auswahl für Dock/Leiste/Startseite. */
    private fun schublade(ziel: Ziel?) {
        teilbarLaden(apps.map { it.paket })
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(24), ui.dp(18), ui.dp(24), ui.dp(18)) }
        val suche = EditText(this).apply {
            hint = getString(if (darfBedienen(still = true)) R.string.suche else R.string.suche_nur_stand)
            isEnabled = darfBedienen(still = true); setSingleLine(); textSize = 18f; typeface = ui.manrope(500)
            setTextColor(ui.p.onSurface); setHintTextColor(ui.p.onSurfaceVariant)
            setCompoundDrawablesRelativeWithIntrinsicBounds(ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_pn_search)?.mutate()?.also { it.setTint(ui.p.aqua) }, null, null, null)
            compoundDrawablePadding = ui.dp(10)
            background = GradientDrawable().apply { cornerRadius = ui.dpf(16f); setColor(ui.p.act); setStroke(ui.dp(1), ui.p.line) }
            setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12))
        }
        val titel = getString(when (ziel) { Ziel.DOCK -> R.string.waehle_dock; Ziel.SCHNELL -> R.string.waehle_schnell; Ziel.STARTSEITE -> R.string.app_hinzu; null -> R.string.alle_apps })
        c.addView(kopf(titel, suche.also { it.minWidth = ui.dp(420) }))
        val gitter = GridLayout(this).apply { columnCount = 7 }
        c.addView(ScrollView(this).apply { addView(gitter) }, ui.lp(-1, 0, 1f, oben = 14))
        fun fuellen(q: String) {
            gitter.removeAllViews()
            val treffer = AppSuche.filter(apps.map { it.paket to it.name }, q).map { it.first }
            if (treffer.isEmpty()) gitter.addView(ui.leise(getString(if (apps.isEmpty()) R.string.apps_laden else R.string.apps_empty), 16f))
            treffer.forEach { p ->
                gitter.addView(appKnopf(p, mitName = true, gross = 64) { lange ->
                    if (lange) schubladenMenue(p)
                    else when (ziel) {
                        Ziel.DOCK -> { aendern(Raster.dockHinzufuegen(layout, p)); schliesseOverlay(); baueAlles() }
                        Ziel.SCHNELL -> { aendern(Raster.schnellHinzufuegen(layout, p)); schliesseOverlay(); baueAlles() }
                        Ziel.STARTSEITE -> { val n = Raster.appHinzufuegen(layout, seite, p); if (n === layout) zeigeHinweis(getString(R.string.kein_platz)); aendern(n); schliesseOverlay(); zeichneSeite() }
                        null -> { schliesseOverlay(); starteApp(p) }
                    }
                }, GridLayout.LayoutParams().apply { width = ui.dp(140); height = ui.dp(124); setMargins(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)) })
            }
        }
        suche.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { fuellen(s?.toString().orEmpty()) }
        })
        fuellen("")
        zeigeOverlay(c)
    }

    private fun schubladenMenue(p: String) {
        val punkte = mutableListOf<Pair<String, () -> Unit>>(getString(R.string.app_info) to { appInfo(p) })
        if (p != Raster.PANOPTES) punkte += nebenPunkt(p)
        if (darfBedienen()) {
            punkte += (if (p in layout.dock) getString(R.string.aus_dock) else getString(R.string.zum_dock)) to {
                aendern(if (p in layout.dock) Raster.dockEntfernen(layout, p) else Raster.dockHinzufuegen(layout, p)); schliesseOverlay(); baueAlles()
            }
            punkte += (if (p in layout.schnell) getString(R.string.aus_schnell) else getString(R.string.zur_schnell)) to {
                aendern(if (p in layout.schnell) Raster.schnellEntfernen(layout, p) else Raster.schnellHinzufuegen(layout, p)); schliesseOverlay(); baueAlles()
            }
            punkte += nebenText(p) to { aendern(Raster.nebenUmschalten(layout, p)) }
            punkte += getString(R.string.auf_startseite) to {
                val n = Raster.appHinzufuegen(layout, seite, p); if (n === layout) zeigeHinweis(getString(R.string.kein_platz)); aendern(n); schliesseOverlay(); zeichneSeite()
            }
        }
        auswahl(appName(p), punkte)
    }

    /** Langer Druck in Dock oder Reiterleiste: App-Info, entfernen, sortieren. Der BYD-Rückweg ist nie dabei. */
    private fun leistenMenue(p: String, schnell: Boolean) {
        val punkte = mutableListOf<Pair<String, () -> Unit>>(getString(R.string.app_info) to { appInfo(p) })
        if (p != Raster.PANOPTES) punkte += nebenPunkt(p)
        if (darfBedienen()) {
            punkte += getString(if (schnell) R.string.aus_schnell else R.string.aus_dock) to {
                aendern(if (schnell) Raster.schnellEntfernen(layout, p) else Raster.dockEntfernen(layout, p)); baueAlles()
            }
            punkte += nebenText(p) to { aendern(Raster.nebenUmschalten(layout, p)) }
            punkte += getString(R.string.nach_links) to { aendern(if (schnell) Raster.schnellVerschieben(layout, p, -1) else Raster.dockVerschieben(layout, p, -1)); baueAlles() }
            punkte += getString(R.string.nach_rechts) to { aendern(if (schnell) Raster.schnellVerschieben(layout, p, 1) else Raster.dockVerschieben(layout, p, 1)); baueAlles() }
        }
        auswahl(appName(p), punkte)
    }

    /** Menüpunkt „Neben dem Launcher: an/aus“ für diese App (kehrt die Einstellung nur für sie um). */
    private fun nebenText(p: String) = getString(R.string.neben_app) + ": " +
        getString(if (Raster.oeffnetNeben(layout, p)) R.string.an else R.string.aus)

    private fun ordnerOeffnen(e: Element) {
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(24), ui.dp(18), ui.dp(24), ui.dp(24)) }
        c.addView(kopf(e.name ?: ""))
        val g = GridLayout(this).apply { columnCount = 4 }
        e.apps.forEach { p ->
            g.addView(appKnopf(p, mitName = true, gross = 64) { lange ->
                if (!lange) { schliesseOverlay(); starteApp(p) }
                else {
                    val punkte = mutableListOf<Pair<String, () -> Unit>>(getString(R.string.app_info) to { appInfo(p) })
                    if (darfBedienen()) punkte += getString(R.string.aus_ordner) to {
                        val n = Raster.ausOrdner(layout, e.id, p); if (n === layout) zeigeHinweis(getString(R.string.kein_platz)); aendern(n); schliesseOverlay(); zeichneSeite()
                    }
                    auswahl(appName(p), punkte)
                }
            }, GridLayout.LayoutParams().apply { width = ui.dp(120); height = ui.dp(124); setMargins(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)) })
        }
        c.addView(g, ui.lp(oben = 12))
        zeigeOverlay(c, breit = false)
    }

    private fun einstellungen() {
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(24), ui.dp(18), ui.dp(24), ui.dp(24)) }
        c.addView(kopf(getString(R.string.reiter_einstellungen)))
        val liste = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun zeile(res: Int, text: String, f: () -> Unit) = liste.addView(ui.act(res, text) { f() }.apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL or Gravity.START
            (getChildAt(1) as? TextView)?.apply { setPadding(ui.dp(14), 0, 0, 0); textSize = 16f; gravity = Gravity.START or Gravity.CENTER_VERTICAL }
        }, ui.lp(-1, ui.dp(64), oben = 8))
        fun schalter(text: Int, an: Boolean) = getString(text) + ": " + getString(if (an) R.string.an else R.string.aus)
        zeile(R.drawable.ic_pn_edit, getString(R.string.bearbeiten)) { if (darfBedienen()) { bearbeiten = true; baueAlles() } }
        zeile(R.drawable.ic_pn_map, getString(R.string.hintergrund)) { if (darfBedienen()) hintergrundWaehlen() }
        zeile(R.drawable.ic_pn_status, schalter(R.string.zeige_status, layout.zeigeStatus)) { if (darfBedienen()) { aendern(layout.copy(zeigeStatus = !layout.zeigeStatus)); baueAlles(); einstellungen() } }
        zeile(R.drawable.ic_pn_apps, schalter(R.string.zeige_reiter, layout.zeigeReiter)) { if (darfBedienen()) { aendern(layout.copy(zeigeReiter = !layout.zeigeReiter)); baueAlles(); einstellungen() } }
        zeile(R.drawable.ic_pn_thermo, schalter(R.string.zeige_klima, layout.zeigeKlima)) { if (darfBedienen()) { aendern(layout.copy(zeigeKlima = !layout.zeigeKlima)); baueAlles(); einstellungen() } }
        zeile(R.drawable.ic_pn_nav, getString(R.string.navi_app) + ": " + (naviWahl()?.let { appName(it) } ?: getString(R.string.navi_app_auto))) { naviWaehlen() }
        zeile(R.drawable.ic_pn_apps, schalter(R.string.neben_alle, layout.nebeneinander)) { if (darfBedienen()) { aendern(layout.copy(nebeneinander = !layout.nebeneinander)); baueAlles(); einstellungen() } }
        zeile(R.drawable.ic_pn_car, schalter(R.string.zeige_3d, layout.zeige3d)) { if (darfBedienen()) { aendern(layout.copy(zeige3d = !layout.zeige3d)); baueAlles(); einstellungen() } }
        zeile(R.drawable.ic_pn_music, getString(R.string.medien_zugriff)) { zugriffErklaeren() }
        zeile(R.drawable.ic_pn_route, getString(R.string.exportieren)) { exportieren() }
        zeile(R.drawable.ic_pn_plus, getString(R.string.importieren)) { if (darfBedienen()) importieren() }
        zeile(R.drawable.ic_pn_close, getString(R.string.zuruecksetzen)) { if (darfBedienen()) zuruecksetzenFragen() }
        zeile(R.drawable.ic_pn_info, getString(R.string.app_info)) { appInfo(packageName) }
        liste.addView(ui.leise(getString(R.string.rueckweg_hinweis), 14f), ui.lp(oben = 14))
        c.addView(ScrollView(this).apply { addView(liste) }, ui.lp(-1, 0, 1f, oben = 8))
        zeigeOverlay(c, breit = false)
        (c.parent as? View)?.layoutParams = FrameLayout.LayoutParams(ui.dp(620), -1, Gravity.CENTER).apply { setMargins(0, ui.dp(30), 0, ui.dp(30)) }
    }

    private fun hintergrundWaehlen() {
        val a = appearance
        val optionen = mutableListOf("panoptes" to getString(R.string.hg_panoptes), "cockpit" to getString(R.string.hg_cockpit))
        if (a?.hintergrundBild == true) optionen += "bild" to getString(R.string.hg_bild)
        if (a?.hintergrundVideo == true) optionen += "video" to getString(R.string.hg_video)
        auswahl(getString(R.string.hintergrund), optionen.map { (w, t) -> (if (w == layout.hintergrund) "✓ $t" else t) to { schliesseOverlay(); aendern(Raster.hintergrund(layout, w)); baueAlles() } },
            fuss = getString(R.string.hg_hinweis))
    }

    private fun zuruecksetzenFragen() {
        AlertDialog.Builder(this).setTitle(R.string.zuruecksetzen).setMessage(R.string.zuruecksetzen_frage)
            .setPositiveButton(R.string.zuruecksetzen) { _, _ -> schliesseOverlay(); aendern(Raster.standard(appearance?.startAnsicht)); seite = 0; bearbeiten = false; baueAlles() }
            .setNegativeButton(R.string.abbrechen, null).show()
    }

    private fun auswahl(titel: String, punkte: List<Pair<String, () -> Unit>>, fuss: String? = null) {
        AlertDialog.Builder(this).setTitle(titel).apply { fuss?.let { setMessage(it) } }
            .setItems(punkte.map { it.first }.toTypedArray()) { _, i -> punkte[i].second() }
            .setNegativeButton(R.string.abbrechen, null).show()
    }

    private fun nameFragen(titel: String, vorgabe: String, ok: (String) -> Unit) {
        if (!darfBedienen()) return
        val feld = EditText(this).apply { setText(vorgabe); setSingleLine(); hint = getString(R.string.ordner_name) }
        AlertDialog.Builder(this).setTitle(titel).setView(feld)
            .setPositiveButton(android.R.string.ok) { _, _ -> if (darfBedienen()) ok(feld.text.toString()) }
            .setNegativeButton(R.string.abbrechen, null).show()
    }

    // ==================== Layout speichern, Export/Import ====================

    private fun ladeLayout(): StartLayout {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.contains("ordnung")) prefs.edit().remove("ordnung").apply() // Vorstufe (nur Testbuilds)
        return Raster.fromJson(prefs.getString(KEY_LAYOUT, null)) ?: Raster.standard(null)
    }

    private fun aendern(neu: StartLayout) {
        if (neu === layout) return
        layout = neu
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_LAYOUT, Raster.toJson(neu))
            .putBoolean(KEY_OFFEN, true).putLong(KEY_LOKAL_ZEIT, System.currentTimeMillis()).apply()
        // Während Bearbeiten erst bei „Fertig“; Änderungen außerhalb (Leiste, Import, Einstellungen) gleich.
        if (!bearbeiten) kernSpeichern()
    }

    // ==================== 0.1.38 Layout im Panoptes-Kern ====================

    private val layoutIo = Executors.newSingleThreadExecutor()
    private val kernPrefs get() = getSharedPreferences(PREFS, MODE_PRIVATE)

    /** Kern-Stand holen und nach [LayoutAbgleich.beimHolen] übernehmen oder die lokale Änderung hochladen. */
    private fun kernAbgleich() {
        layoutIo.execute {
            val kern = client.layoutHolen() ?: return@execute
            main.post {
                val p = kernPrefs
                when (LayoutAbgleich.beimHolen(kern, p.getLong(KEY_BASIS, 0L), p.getBoolean(KEY_OFFEN, false),
                    p.contains(KEY_LAYOUT), p.getBoolean(KEY_KENNT_KERN, false))) {
                    LayoutAbgleich.Schritt.HOCHLADEN -> kernSpeichern()
                    LayoutAbgleich.Schritt.UEBERNEHMEN -> uebernimmKern(kern, false)
                    LayoutAbgleich.Schritt.NICHTS -> Unit
                }
            }
        }
    }

    /** Kern-Stand als neues Layout; neu gezeichnet nur, wenn nicht gerade bearbeitet oder ein Fenster offen ist. */
    private fun uebernimmKern(kern: KernLayout, hinweis: Boolean) {
        val anders = Raster.toJson(kern.layout) != Raster.toJson(layout)
        val e = kernPrefs.edit().putLong(KEY_BASIS, kern.geaendert).putBoolean(KEY_OFFEN, false).putBoolean(KEY_KENNT_KERN, true)
        if (anders) e.putString(KEY_LAYOUT, Raster.toJson(kern.layout))
        e.apply()
        if (!anders) return
        layout = kern.layout
        if (!bearbeiten && overlay == null) { seite = seite.coerceIn(0, layout.seiten.size - 1); baueAlles() }
        if (hinweis) zeigeHinweis(getString(R.string.layout_neuer_stand))
    }

    /** Lokales Layout in den Kern schreiben. 409: neueste Änderung gewinnt ([LayoutAbgleich.nochmalSenden]). */
    private fun kernSpeichern(versuch: Int = 0) {
        val l = layout
        val basis = kernPrefs.getLong(KEY_BASIS, 0L)
        layoutIo.execute {
            val a = client.layoutSpeichern(l, basis)
            main.post {
                when (a) {
                    is SpeicherAntwort.Ok -> {
                        val e = kernPrefs.edit().putLong(KEY_BASIS, a.geaendert).putBoolean(KEY_KENNT_KERN, true)
                        if (layout === l) e.putBoolean(KEY_OFFEN, false)
                        e.apply()
                        if (a.layout != null && layout === l && Raster.toJson(a.layout) != Raster.toJson(l)) uebernimmKern(KernLayout(a.layout, a.geaendert, false), false)
                    }
                    is SpeicherAntwort.Neuer -> {
                        val lokal = kernPrefs.getLong(KEY_LOKAL_ZEIT, 0L)
                        if (versuch == 0 && LayoutAbgleich.nochmalSenden(lokal, a.kern.geaendert)) {
                            kernPrefs.edit().putLong(KEY_BASIS, a.kern.geaendert).apply()
                            kernSpeichern(1)
                        } else uebernimmKern(a.kern, true)
                    }
                    is SpeicherAntwort.Fehler -> {
                        // 400/413: diese Form nimmt der Kern nie an, nicht immer wieder senden; sonst später erneut.
                        if (a.status == 400 || a.status == 413) kernPrefs.edit().putBoolean(KEY_OFFEN, false).apply()
                    }
                }
            }
        }
    }

    private fun exportieren() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json").putExtra(Intent.EXTRA_TITLE, "panoptes-launcher-layout.json"), REQ_EXPORT)
    }

    private fun importieren() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_IMPORT)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        try {
            when (requestCode) {
                REQ_EXPORT -> { contentResolver.openOutputStream(uri, "wt")?.use { it.write(Raster.toJson(layout).toByteArray(Charsets.UTF_8)) }; zeigeHinweis(getString(R.string.export_ok)) }
                REQ_IMPORT -> {
                    val text = contentResolver.openInputStream(uri)?.use { s -> String(s.readBytes().let { b -> if (b.size > 256 * 1024) ByteArray(0) else b }, Charsets.UTF_8) }
                    val neu = Raster.fromJson(text)
                    if (neu == null) zeigeHinweis(getString(R.string.import_fehler)) else { aendern(neu); seite = 0; schliesseOverlay(); baueAlles(); zeigeHinweis(getString(R.string.import_ok)) }
                }
            }
        } catch (_: Exception) {
            zeigeHinweis(getString(R.string.import_fehler))
        }
    }

    // ==================== Daten ====================

    private fun ladeApps() {
        io.execute {
            val liste = runCatching { AppQuelle.laden(this) }.getOrDefault(emptyList())
            main.post {
                apps = liste; appsNachPaket = liste.associateBy { it.paket }
                if (overlay == null) baueAlles()
            }
        }
    }

    private fun refresh() {
        if (!refreshInFlight.compareAndSet(false, true)) return
        io.execute {
            try {
                val s = client.summary()
                val trafficValue = runCatching { client.traffic() }.getOrDefault(LauncherTraffic(0, 0))
                val homeWunsch = runCatching { client.homeWunsch() }.getOrNull()
                val a = runCatching { client.appearance() }.getOrDefault(LauncherAppearance("auto", "crystal"))
                val now = System.currentTimeMillis()
                if (now - lastUpdateFetch >= UPDATE_TAKT_MS) {
                    runCatching { client.update() }.getOrNull()?.let { u ->
                        lastUpdateFetch = now
                        main.post { if (u != updateStand) { updateStand = u; zeichneUpdateKarte() } }
                    }
                }
                if (now - lastHistoryFetch >= 60_000) {
                    runCatching { client.history() }.getOrNull()?.let {
                        cachedHistory = it
                        lastHistoryFetch = now
                    }
                }
                main.post { neueDaten(s, trafficValue, a, true); homeAbgleichen(homeWunsch) }
            } catch (_: Exception) {
                main.post { neueDaten(null, LauncherTraffic(0, 0), appearance, false) }
            } finally {
                refreshInFlight.set(false)
            }
        }
    }

    private var homeFrageOffen = false

    /**
     * 0.1.38.7: Alias des Launchers dem Kern-Wunsch anpassen (nie bei unerreichbarem Kern) und, wenn der Schalter frisch an
     * ist, groß fragen. „Ja“: der Kern setzt uns als Standard; „Nein“: Rückweg zum BYD-Startbildschirm.
     */
    private fun homeAbgleichen(w: HomeWunsch?) {
        HomeAbgleich.aliasSoll(w, HomeSchalter.istAn(this))?.let { HomeSchalter.setze(this, it) }
        if (HomeAbgleich.fragen(w, HomeSchalter.istAn(this)) && !homeFrageOffen && !isFinishing) {
            homeFrageOffen = true
            val d = AlertDialog.Builder(this).setTitle(R.string.home_frage_titel).setMessage(R.string.home_frage_text)
                .setCancelable(false)
                .setPositiveButton(R.string.home_behalten) { _, _ ->
                    homeFrageOffen = false
                    io.execute {
                        val ok = client.homeBehalten()
                        main.post { zeigeHinweis(getString(if (ok) R.string.home_ok else R.string.home_fehler)) }
                    }
                }
                .setNegativeButton(R.string.home_nein) { _, _ ->
                    homeFrageOffen = false
                    io.execute { client.homeAus(); main.post { HomeSchalter.setze(this, false) } }
                }.create()
            d.setOnDismissListener { homeFrageOffen = false }
            d.show()
            d.getButton(AlertDialog.BUTTON_POSITIVE)?.apply { textSize = 22f; minHeight = ui.dp(72) }
            d.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply { textSize = 22f; minHeight = ui.dp(72) }
        }
    }

    private fun neueDaten(s: LauncherSummary?, t: LauncherTraffic, a: LauncherAppearance?, ok: Boolean) {
        val warOnline = online
        if (ok && !warOnline && kernPrefs.getBoolean(KEY_OFFEN, false)) kernSpeichern()
        if (LayoutAbgleich.neuHolen(s?.layoutGeaendert, kernPrefs.getLong(KEY_BASIS, 0L), kernPrefs.getBoolean(KEY_OFFEN, false),
                bearbeiten || overlay != null)) kernAbgleich()
        summary = s; traffic = t; online = ok
        if (!ok && warOnline) zeigeHinweis(getString(R.string.offline))
        val alt = appearance; appearance = a
        val cockpit = layout.hintergrund == "cockpit"
        val pal = if (cockpit) PanoptesStil.DUNKEL else PanoptesStil.palette(a?.modus, systemHell())
        if ("${pal.hell}:$cockpit" != stilSchluessel && overlay == null) { baueAlles(); return }
        if (alt?.hintergrundBild != a?.hintergrundBild || alt?.hintergrundVideo != a?.hintergrundVideo) bildGeladen = false
        hintergrundAnwenden()
        aktualisierer.forEach { it() }
        // ohne Sekundentakt (Ruhe, kein Fokus) hält die Abfrage Uhr und Statusleiste aktuell
        if (!Ruhe.taktLaeuft(stopped, bildAn, fokus)) statusAktualisierer.forEach { it() }
        klimaFeldZeichnen?.invoke()
        if (bildAn != warBildAn) zustandAnwenden()
    }

    // ---------- Hintergrund ----------

    private fun hintergrundAnwenden() {
        val a = appearance
        val willBild = (layout.hintergrund == "bild" || layout.hintergrund == "video") && a?.hintergrundBild == true
        if (willBild && !bildGeladen) {
            bildGeladen = true
            io.execute {
                val bytes = runCatching { client.hintergrundBild() }.getOrNull()
                val bmp = bytes?.let { dekodieren(it) }
                main.post { if (bmp != null) { bild.setImageBitmap(bmp); bild.visibility = View.VISIBLE } else bildGeladen = false }
            }
        } else if (!willBild) { bild.setImageDrawable(null); bild.visibility = View.GONE; bildGeladen = false }
        // Video nur mit Wahl „Video“, Kern-Schalter UND Gang P (Fahrsicherheit). Sonst sofort aus.
        if (bildAn && !stopped && layout.hintergrund == "video" && Fahrsicherheit.videoErlaubt(a?.hintergrundVideo == true, summary?.gear)) videoStart() else videoStopp()
    }

    private fun dekodieren(bytes: ByteArray) = try {
        val grenzen = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, grenzen)
        var teiler = 1
        while (grenzen.outWidth / teiler > 1920 || grenzen.outHeight / teiler > 1920) teiler *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = teiler })
    } catch (_: Throwable) { null }

    private fun videoStart() {
        if (videoLaeuft) return
        videoLaeuft = true
        video.visibility = View.VISIBLE
        video.setOnPreparedListener { mp -> mp.isLooping = true; mp.setVolume(0f, 0f); video.start() }
        video.setOnErrorListener { _, _, _ -> videoStopp(); true }
        video.setVideoURI(Uri.parse(client.hintergrundVideoUrl))
    }

    private fun videoStopp() {
        if (!videoLaeuft) return
        videoLaeuft = false
        video.stopPlayback()
        video.visibility = View.GONE
    }

    // ==================== Aktionen ====================

    /** Bedienen nur im Stand (Fahrsicherheit). Starten und Ansehen geht immer. */
    private fun darfBedienen(still: Boolean = false): Boolean {
        val s = summary
        if (Fahrsicherheit.bearbeitenErlaubt(s?.gear, s?.acc, s?.speedKmh)) return true
        if (!still) zeigeHinweis(getString(R.string.nur_im_stand))
        return false
    }

    private fun appName(p: String) = appsNachPaket[p]?.name ?: p

    // ---------- Direkt im Launcher (0.1.38.5): Klima-Feld, Info-Felder, Navi ----------

    /**
     * Klima-Feld im Launcher: große −/+ für Temperatur und Gebläse, An/Aus, aktueller Stand und Rückmeldung nach jedem
     * Tipp. Nur der Kern-Weg `klima` (dieselben sechs Aktionen wie im Dock). Panoptes nur über „Mehr in Panoptes“.
     */
    private fun klimaFeld() {
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(28), ui.dp(20), ui.dp(28), ui.dp(24)) }
        c.addView(kopf(getString(R.string.klima_feld_titel)))
        val tempKnoepfe = mutableListOf<View>()
        fun reihe(titel: Int, icon: Int, runter: String, hoch: String, wert: TextView) = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.icon(icon, ui.p.aqua, 32), ui.lp(rechts = 14))
            addView(ui.text(getString(titel), 20f, 600), ui.lp(0, -2, 1f))
            addView(ui.act(R.drawable.ic_pn_minus, null) { klimaSenden(runter) }.apply { contentDescription = getString(R.string.klima_runter); if (runter.startsWith("temp")) tempKnoepfe += this },
                ui.lp(ui.dp(GROSS_KNOPF), ui.dp(GROSS_KNOPF)))
            addView(wert.apply { gravity = Gravity.CENTER; minWidth = ui.dp(120) }, ui.lp())
            addView(ui.act(R.drawable.ic_pn_plus, null) { klimaSenden(hoch) }.apply { contentDescription = getString(R.string.klima_hoch); if (hoch.startsWith("temp")) tempKnoepfe += this },
                ui.lp(ui.dp(GROSS_KNOPF), ui.dp(GROSS_KNOPF)))
        }
        val soll = ui.zahl("—", 34f); val luft = ui.zahl("—", 34f)
        c.addView(reihe(R.string.klima_temp, R.drawable.ic_pn_thermo, "temp_runter", "temp_hoch", soll), ui.lp(-1, -2, oben = 18))
        c.addView(reihe(R.string.klima_luft, R.drawable.ic_pn_fan, "luft_runter", "luft_hoch", luft), ui.lp(-1, -2, oben = 14))
        val anAus = ui.act(R.drawable.ic_pn_power, getString(R.string.klima_einschalten), primary = true) {
            klimaSenden(if (summary?.klima?.an == true) "aus" else "an")
        }.apply { orientation = LinearLayout.HORIZONTAL; (getChildAt(1) as? TextView)?.apply { textSize = 18f; setPadding(ui.dp(12), 0, 0, 0) } }
        c.addView(anAus, ui.lp(-1, ui.dp(GROSS_KNOPF), oben = 20))
        val stand = ui.leise("", 16f); c.addView(stand, ui.lp(oben = 14))
        c.addView(ui.act(R.drawable.ic_pn_thermo, getString(R.string.komfort_oeffnen)) { komfortFeld() }
            .apply { orientation = LinearLayout.HORIZONTAL; (getChildAt(1) as? TextView)?.apply { textSize = 18f; setPadding(ui.dp(12), 0, 0, 0) } },
            ui.lp(-1, ui.dp(GROSS_KNOPF), oben = 14))
        c.addView(ui.act(R.drawable.ic_pn_car, getString(R.string.mehr_in_panoptes)) { schliesseOverlay(); oeffnePanoptes("vehicle") }
            .apply { orientation = LinearLayout.HORIZONTAL; (getChildAt(1) as? TextView)?.setPadding(ui.dp(12), 0, 0, 0) },
            ui.lp(-1, ui.dp(64), oben = 14))
        val zeichnen = {
            val k = summary?.klima
            soll.text = sollText(k) ?: "—"
            tempKnoepfe.forEach { it.alpha = if (k?.tempArt == "unbekannt") 0.35f else 1f }
            luft.text = k?.stufe?.toString() ?: "—"
            (anAus.getChildAt(1) as? TextView)?.text = getString(if (k?.an == true) R.string.klima_ausschalten else R.string.klima_einschalten)
            stand.text = listOfNotNull(k?.innenC?.let { getString(R.string.klima_innen, nf1.format(it)) },
                when (k?.an) { true -> getString(R.string.klima_an_ok); false -> getString(R.string.klima_aus_ok); null -> fernText(k) }).joinToString(" · ")
        }
        zeichnen(); klimaFeldZeichnen = zeichnen
        zeigeOverlay(c, breit = false)
        (c.parent as? View)?.layoutParams = FrameLayout.LayoutParams(ui.dp(640), -2, Gravity.CENTER)
    }

    private var klimaFeldZeichnen: (() -> Unit)? = null

    /**
     * 0.1.38.6 Komfort (04.11.: „Sitzheizung steuern und Klimaanlage und so weiter“): große Kacheln, ein Tipp = eine
     * Aktion über den engen Kern-Weg `klima` (feste Namen). Sitze, Lenkrad und Umluft zeigen den gelesenen Zustand und
     * erscheinen nur, wenn er lesbar ist (keine toten Knöpfe); Auto-Modus, Entfrosten und Nur-Gebläse haben keinen Lese-Wert
     * und kommen als An/Aus-Paar, die Rückmeldung sagt dann nur „gesendet“.
     */
    private fun komfortFeld() {
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(28), ui.dp(20), ui.dp(28), ui.dp(24)) }
        c.addView(kopf(getString(R.string.komfort_titel)))
        val kacheln = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val paare = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun paar(titel: Int, icon: Int, an: String, aus: String) = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.icon(icon, ui.p.aqua, 32), ui.lp(rechts = 14))
            addView(ui.text(getString(titel), 20f, 600), ui.lp(0, -2, 1f))
            addView(ui.act(null, getString(R.string.komfort_an)) { klimaSenden(an) }, ui.lp(ui.dp(GROSS_KNOPF), ui.dp(GROSS_KNOPF), rechts = 10))
            addView(ui.act(null, getString(R.string.komfort_aus)) { klimaSenden(aus) }, ui.lp(ui.dp(GROSS_KNOPF), ui.dp(GROSS_KNOPF)))
        }
        paare.addView(paar(R.string.komfort_auto, R.drawable.ic_pn_leaf, "auto_an", "auto_aus"), ui.lp(-1, -2, oben = 14))
        paare.addView(paar(R.string.komfort_entfrosten_vorn, R.drawable.ic_pn_fan, "entfrosten_vorn_an", "entfrosten_vorn_aus"), ui.lp(-1, -2, oben = 14))
        paare.addView(paar(R.string.komfort_entfrosten_hinten, R.drawable.ic_pn_fan, "entfrosten_hinten_an", "entfrosten_hinten_aus"), ui.lp(-1, -2, oben = 14))
        paare.addView(paar(R.string.komfort_nur_geblaese, R.drawable.ic_pn_fan, "nur_geblaese_an", "nur_geblaese_aus"), ui.lp(-1, -2, oben = 14))
        fun kachel(icon: Int, name: Int, zustand: String, aktion: String) = ui.act(icon, null) { klimaSenden(aktion) }.apply {
            minimumHeight = ui.dp(GROSS_KNOPF)
            addView(ui.text(getString(name), 17f, 600).apply { gravity = Gravity.CENTER; maxLines = 2 })
            addView(ui.text(zustand, 20f, 700, ui.p.aqua).apply { gravity = Gravity.CENTER; maxLines = 1 })
        }
        val zeichnen = {
            val k = summary?.klima
            val tiles = listOfNotNull(
                k?.sitzFahrer?.let { kachel(R.drawable.ic_pn_thermo, R.string.komfort_sitz_fahrer, stufeKlartext(it), "sitz_fahrer") },
                k?.sitzBeifahrer?.let { kachel(R.drawable.ic_pn_thermo, R.string.komfort_sitz_beifahrer, stufeKlartext(it), "sitz_beifahrer") },
                k?.lueftungFahrer?.let { kachel(R.drawable.ic_pn_fan, R.string.komfort_lueftung_fahrer, stufeKlartext(it), "lueftung_fahrer") },
                k?.lueftungBeifahrer?.let { kachel(R.drawable.ic_pn_fan, R.string.komfort_lueftung_beifahrer, stufeKlartext(it), "lueftung_beifahrer") },
                k?.lenkrad?.let { kachel(R.drawable.ic_pn_thermo, R.string.komfort_lenkrad,
                    getString(if (it) R.string.komfort_an else R.string.komfort_aus), "lenkrad") },
                k?.umluft?.let { kachel(R.drawable.ic_pn_fan, R.string.komfort_umluft,
                    getString(if (it) R.string.komfort_umluft_ein else R.string.komfort_frischluft), "umluft") })
            kacheln.removeAllViews()
            tiles.chunked(2).forEach { zeile ->
                kacheln.addView(LinearLayout(this).apply {
                    zeile.forEachIndexed { i, t -> addView(t, ui.lp(0, -2, 1f, rechts = if (i == 0) 12 else 0)) }
                }, ui.lp(-1, -2, oben = 12))
            }
            if (tiles.isEmpty()) kacheln.addView(ui.leise(getString(R.string.komfort_nichts_lesbar), 16f), ui.lp(oben = 12))
        }
        c.addView(kacheln, ui.lp(-1, -2))
        c.addView(paare, ui.lp(-1, -2))
        zeichnen(); klimaFeldZeichnen = zeichnen
        val scroll = ScrollView(this).apply { addView(c) }
        zeigeOverlay(scroll, breit = false)
        (scroll.parent as? View)?.layoutParams = FrameLayout.LayoutParams(ui.dp(640), -2, Gravity.CENTER)
    }

    private fun stufeKlartext(s: Int) = getString(when (s) { 1 -> R.string.komfort_stufe_1; 2 -> R.string.komfort_stufe_2; else -> R.string.komfort_aus })

    /**
     * Sollwert für die Anzeige: beim Surf die Stufe wie im Display („Heizen 2“, „Neutral“, „Kühlen 1“), sonst °C.
     * null = unbekannt (dann „—“). Nie eine erfundene Gradzahl.
     */
    private fun sollText(k: LauncherKlima?): String? {
        if (k == null) return null
        if (k.stufenModell) {
            val (art, zahl) = Fahrsicherheit.klimaStufe(k.tempStufe) ?: return null
            return when (art) { 1 -> getString(R.string.stufe_heizen, zahl); -1 -> getString(R.string.stufe_kuehlen, zahl); else -> getString(R.string.stufe_neutral) }
        }
        return k.sollC?.let { "${nf1.format(it)}°" }
    }

    /**
     * 0.1.38.7: Fernklima laut Befehl, nur wenn das Auto selbst nichts meldet (an == null). Nie „aus“, wenn wir es nicht wissen;
     * der Text nennt immer „laut Befehl“.
     */
    private fun fernText(k: LauncherKlima?): String? {
        if (k == null) return null
        val zeit = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        if (k.fernAktiv == true && k.fernGestartetMs != null && k.fernBisMs != null) {
            // fern.stufe = Balkenstufe 1..17 (Mitte 9); im Text steht der Abstand zur Mitte (Stufe 12 -> Heizen Stufe 3)
            val ks = Fahrsicherheit.klimaStufe(k.fernStufe)
            val art = when {
                ks == null -> ""
                ks.first == 1 -> getString(R.string.fern_art_heizen, ks.second)
                ks.first == -1 -> getString(R.string.fern_art_kuehlen, ks.second)
                else -> getString(R.string.fern_art_neutral)
            }
            return getString(R.string.fern_laeuft, zeit.format(java.util.Date(k.fernGestartetMs)), zeit.format(java.util.Date(k.fernBisMs)), art)
        }
        if (k.fernAktiv == false && k.fernBeendetUmMs != null) return getString(R.string.fern_beendet, zeit.format(java.util.Date(k.fernBeendetUmMs)))
        return null
    }

    /** Rückmeldung nach einem Klima-Tipp: neuer Wert in Klartext (REGEL 50+). */
    private fun klimaRueckmeldung(aktion: String, k: LauncherKlima?) {
        val text = when (aktion) {
            "temp_hoch", "temp_runter" -> sollText(k)?.let { getString(R.string.klima_neu_soll, it) }
            "luft_hoch", "luft_runter" -> k?.stufe?.let { getString(R.string.klima_neu_luft, it.toString()) }
            "an" -> getString(R.string.klima_an_ok)
            "aus" -> getString(R.string.klima_aus_ok)
            "sitz_fahrer" -> k?.sitzFahrer?.let { getString(R.string.komfort_neu, getString(R.string.komfort_sitz_fahrer), stufeKlartext(it)) }
            "sitz_beifahrer" -> k?.sitzBeifahrer?.let { getString(R.string.komfort_neu, getString(R.string.komfort_sitz_beifahrer), stufeKlartext(it)) }
            "lueftung_fahrer" -> k?.lueftungFahrer?.let { getString(R.string.komfort_neu, getString(R.string.komfort_lueftung_fahrer), stufeKlartext(it)) }
            "lueftung_beifahrer" -> k?.lueftungBeifahrer?.let { getString(R.string.komfort_neu, getString(R.string.komfort_lueftung_beifahrer), stufeKlartext(it)) }
            "lenkrad" -> k?.lenkrad?.let { getString(R.string.komfort_neu, getString(R.string.komfort_lenkrad), getString(if (it) R.string.komfort_an else R.string.komfort_aus)) }
            "umluft" -> k?.umluft?.let { getString(R.string.komfort_neu, getString(R.string.komfort_umluft), getString(if (it) R.string.komfort_umluft_ein else R.string.komfort_frischluft)) }
            else -> null   // Auto-Modus, Entfrosten, Nur-Gebläse: kein Lese-Wert, nur „gesendet“
        } ?: getString(R.string.klima_gesendet)
        zeigeHinweis(text)
    }

    /** Info-Feld im Launcher (Fahrzeug, Akku, Wächter): Zeilen in Klartext, Panoptes nur über „Mehr in Panoptes“. */
    private fun infoFeld(titel: String, ziel: String, zeilen: () -> List<String>) {
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(28), ui.dp(20), ui.dp(28), ui.dp(24)) }
        c.addView(kopf(titel))
        val liste = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(liste, ui.lp(-1, -2, oben = 12))
        c.addView(ui.act(R.drawable.ic_pn_car, getString(R.string.mehr_in_panoptes)) { schliesseOverlay(); oeffnePanoptes(ziel) }
            .apply { orientation = LinearLayout.HORIZONTAL; (getChildAt(1) as? TextView)?.setPadding(ui.dp(12), 0, 0, 0) },
            ui.lp(-1, ui.dp(64), oben = 18))
        val zeichnen = {
            liste.removeAllViews()
            zeilen().ifEmpty { listOf(getString(R.string.unknown)) }.forEach { liste.addView(ui.text(it, 20f, 600), ui.lp(oben = 10)) }
        }
        zeichnen(); klimaFeldZeichnen = zeichnen
        zeigeOverlay(c, breit = false)
        (c.parent as? View)?.layoutParams = FrameLayout.LayoutParams(ui.dp(640), -2, Gravity.CENTER)
    }

    private fun fahrzeugInfo() = infoFeld(summary?.vehicleName ?: getString(R.string.widget_fahrzeug), "vehicle") {
        val s = summary
        listOfNotNull(
            getString(R.string.range_format, s?.rangeKm?.let { nf.format(it) } ?: u),
            s?.socPct?.let { getString(R.string.info_akku, nf.format(it)) },
            s?.klima?.innenC?.let { getString(R.string.klima_innen, nf1.format(it)) },
            s?.reifen?.let { r -> listOf(r.vl, r.vr, r.hl, r.hr).takeIf { l -> l.any { it != null } }
                ?.joinToString(" · ") { it?.let { b -> nf1.format(b) } ?: "—" }?.let { getString(R.string.info_reifen, it) } },
            s?.gear?.let { getString(R.string.info_gang, it) })
    }

    private fun akkuInfo() = infoFeld(getString(R.string.widget_akku), "charging") {
        val s = summary; val ch = s?.charging
        listOfNotNull(
            s?.socPct?.let { getString(R.string.info_akku, nf.format(it)) },
            s?.rangeKm?.let { getString(R.string.range_format, nf.format(it)) },
            when {
                ch == null -> null
                ch.active -> listOfNotNull(getString(R.string.akku_laedt, ch.kw?.let { nf1.format(it) } ?: u),
                    ch.targetPct?.let { getString(R.string.akku_ziel, nf.format(it)) }, ch.etaMin?.let { getString(R.string.akku_eta, nf.format(it)) }).joinToString(" · ")
                else -> getString(R.string.akku_laedt_nicht)
            },
            *gesundheitZeilen(s).toTypedArray())
    }

    // ---------- 0.1.38.8: Akku-Gesundheit wie die Web-Akku-Karte – BYD-Wert und Panoptes-Schätzung nebeneinander ----------

    private fun sohProzent(v: Double?): String =
        v?.let { getString(R.string.akku_prozent, nf1.format(it)) } ?: getString(R.string.nicht_verfuegbar)

    /** Kurzform für Kachel und Startseite: „Auto 100 % · Schätzung 95,4 %“ (alter Kern: nur der Einzelwert). */
    private fun gesundheitWerte(s: LauncherSummary?): String? = when (LauncherContract.gesundheitArt(s)) {
        LauncherContract.Gesundheit.ZWEI -> getString(R.string.akku_gesundheit_werte, sohProzent(s!!.sohAutoPct), sohProzent(s.sohSchaetzungPct))
        LauncherContract.Gesundheit.EINZELWERT -> getString(R.string.akku_prozent, nf.format(s!!.healthPct!!))
        LauncherContract.Gesundheit.KEINE -> null
    }

    /** Akku-Info: beide Werte je eine Zeile und die Erklärung, welcher gilt. */
    private fun gesundheitZeilen(s: LauncherSummary?): List<String> = when (LauncherContract.gesundheitArt(s)) {
        LauncherContract.Gesundheit.ZWEI -> listOf(getString(R.string.akku_gesundheit_auto_zeile, sohProzent(s!!.sohAutoPct)),
            getString(R.string.akku_gesundheit_schaetzung_zeile, sohProzent(s.sohSchaetzungPct)), getString(R.string.akku_gesundheit_erklaerung))
        LauncherContract.Gesundheit.EINZELWERT -> listOf(getString(R.string.akku_gesundheit, nf.format(s!!.healthPct!!)))
        LauncherContract.Gesundheit.KEINE -> emptyList()
    }

    private fun waechterInfo() = infoFeld(getString(R.string.widget_waechter), "waechter") {
        val w = summary?.waechter
        listOfNotNull(
            when (w?.an) { true -> getString(R.string.waechter_an); false -> getString(R.string.waechter_aus); null -> null },
            if (w?.scharf == true) getString(R.string.waechter_scharf) else null,
            if (w?.aufnahme == true) getString(R.string.waechter_aufnahme) else null)
    }

    /**
     * Navi direkt starten: eigene Wahl im Launcher, sonst die am Tacho gewählte Navi-App, sonst die erste installierte
     * bekannte Navi-App. Erst wenn es keine gibt, öffnet sich die Karte in Panoptes (mit Hinweis).
     */
    private fun starteNavi() {
        val p = Fahrsicherheit.naviZiel(naviWahl(), summary?.naviGewaehlt, summary?.naviBekannt.orEmpty()) { startbar(it) }
        if (p != null) { starteApp(p); return }
        zeigeHinweis(getString(R.string.navi_keine_app)); oeffnePanoptes("navi")
    }

    private fun naviWahl(): String? = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_NAVI, null)

    /** Einstellung „Navi-App“: Automatisch (Tacho-Wahl) oder eine der installierten Navi-Apps. */
    private fun naviWaehlen() {
        val kandidaten = (listOfNotNull(summary?.naviGewaehlt) + summary?.naviBekannt.orEmpty()).distinct().filter { startbar(it) }
        val punkte = mutableListOf<Pair<String, () -> Unit>>(getString(R.string.navi_app_auto) to {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_NAVI).apply(); einstellungen()
        })
        kandidaten.forEach { p -> punkte += ((if (p == naviWahl()) "✓ " else "") + appName(p)) to {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_NAVI, p).apply(); einstellungen()
        } }
        auswahl(getString(R.string.navi_app), punkte, fuss = if (kandidaten.isEmpty()) getString(R.string.navi_keine_app) else null)
    }

    private fun startbar(p: String) = appsNachPaket.containsKey(p) ||
        runCatching { packageManager.getLaunchIntentForPackage(p) != null }.getOrDefault(false)

    private fun starteApp(p: String) {
        val a = appsNachPaket[p]
        if (a != null && !Fahrsicherheit.appStartErlaubt(a.video, summary?.gear, summary?.fahrtHinweis == true)) { zeigeHinweis(getString(R.string.video_nur_p)); return }
        if (Raster.oeffnetNeben(layout, p) && p != Raster.PANOPTES) { nebenStarten(p); return }
        starteVollbild(p)
    }

    /**
     * 0.1.38.5: neben dem Launcher öffnen. Kann die App das nicht (Kern prüft vorher) oder klappt es nicht, kommt eine
     * ehrliche Frage mit großem „Im Vollbild öffnen“ – nie mehr stilles Vollbild.
     */
    private fun nebenStarten(p: String) {
        if (teilbarCache[p] == "nein") { nichtTeilbarFragen(p, NebenErgebnis.NICHT_TEILBAR); return }
        io.execute {
            val r = client.nebenOeffnen(p)
            if (r == NebenErgebnis.NICHT_TEILBAR) teilbarCache[p] = "nein"
            main.post {
                when (r) {
                    NebenErgebnis.OK -> Unit
                    NebenErgebnis.VOLLBILD -> zeigeHinweis(getString(R.string.neben_geht_nicht))
                    else -> nichtTeilbarFragen(p, r)
                }
            }
        }
    }

    private val teilbarCache = java.util.concurrent.ConcurrentHashMap<String, String>()



    private fun nichtTeilbarFragen(p: String, r: NebenErgebnis) {
        AlertDialog.Builder(this).setTitle(appName(p))
            .setMessage(if (r == NebenErgebnis.NICHT_TEILBAR) R.string.neben_nicht_teilbar else R.string.neben_fehlgeschlagen)
            .setPositiveButton(R.string.im_vollbild_oeffnen) { _, _ -> starteVollbild(p) }
            .setNegativeButton(R.string.abbrechen, null).show()
            .getButton(AlertDialog.BUTTON_POSITIVE)?.apply { textSize = 20f; minHeight = ui.dp(64) }
    }

    /** Langdruck-Menüpunkt „Neben dem Launcher öffnen“, mit ehrlichem Vermerk, wenn die App es nicht kann. */
    private fun nebenPunkt(p: String): Pair<String, () -> Unit> =
        (getString(R.string.neben_jetzt) + if (teilbarCache[p] == "nein") " – " + getString(R.string.neben_kurz_nein) else "") to { nebenStarten(p) }

    private fun teilbarLaden(pakete: List<String>) {
        val offen = pakete.filter { !teilbarCache.containsKey(it) }
        if (offen.isEmpty()) return
        io.execute { client.teilbar(offen).forEach { (k, v) -> teilbarCache[k] = v } }
    }

    private fun starteVollbild(p: String) {
        val i = packageManager.getLaunchIntentForPackage(p)
        if (i == null) { zeigeHinweis(getString(R.string.app_fehlt)); return }
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { zeigeHinweis(getString(R.string.launch_failed)) }
    }

    private fun appInfo(p: String) {
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$p")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: Exception) { zeigeHinweis(getString(R.string.launch_failed)) }
    }

    private fun zugriffErklaeren() {
        AlertDialog.Builder(this).setTitle(R.string.medien_zugriff).setMessage(R.string.medien_zugriff_text)
            .setPositiveButton(R.string.oeffnen) { _, _ ->
                try { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } catch (_: Exception) { zeigeHinweis(getString(R.string.launch_failed)) }
            }.setNegativeButton(R.string.abbrechen, null).show()
    }

    /** Medientaste: an die Sitzung (mit Zugriff), sonst als Android-Medientaste. Kein Fahrzeugbefehl. */
    private fun medienTaste(code: Int) {
        val c = medien?.controller
        if (c != null) {
            val tc = c.transportControls
            when (code) {
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> tc.skipToPrevious()
                KeyEvent.KEYCODE_MEDIA_NEXT -> tc.skipToNext()
                else -> if (medien?.spielt == true) tc.pause() else tc.play()
            }
            return
        }
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }

    /** Panoptes öffnen, optional direkt auf einer Seite (Deep-Link navigate_to, NavigateZiel in Panoptes). */
    private fun oeffnePanoptes(ziel: String? = null) {
        val i = packageManager.getLaunchIntentForPackage(Raster.PANOPTES)
        if (i == null) { zeigeHinweis(getString(R.string.app_fehlt)); return }
        ziel?.let { i.putExtra("navigate_to", it) }
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { zeigeHinweis(getString(R.string.launch_failed)) }
    }

    /**
     * Rückweg zum BYD-Startbildschirm. Startet gezielt den vom System gemeldeten Home
     * (nie die eigene App, nie die Android-Auswahl, siehe [HomeRueckweg]). Ohne Ziel
     * schließt sich nur die eigene Ansicht; Android zeigt dann, was darunter liegt.
     */
    private fun zumBydStart() {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val standard = packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.let { HomeKandidat(it.packageName, it.name) }
        val kandidaten = packageManager.queryIntentActivities(home, 0)
            .map { HomeKandidat(it.activityInfo.packageName, it.activityInfo.name) }
        val ziel = HomeRueckweg.ziel(packageName, standard, kandidaten)
        if (ziel == null) { finish(); return }
        try {
            startActivity(Intent(home).setClassName(ziel.packageName, ziel.className).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            finish()
        }
    }

    private companion object {
        const val PREFS = "launcher"
        const val KEY_LAYOUT = "layout"
        /** 0.1.38 Kern-Abgleich: zuletzt gekanntes `geaendert`, lokale Änderung offen, Zeit der lokalen Änderung. */
        const val KEY_BASIS = "layout_basis"
        const val KEY_OFFEN = "layout_offen"
        const val KEY_LOKAL_ZEIT = "layout_lokal_zeit"
        const val KEY_KENNT_KERN = "layout_kennt_kern"
        const val REQ_EXPORT = 41
        const val REQ_IMPORT = 42
        const val KEY_SPAETER = "update_spaeter"
        const val KEY_NAVI = "navi_app"
        /** REGEL 50+: Klima-Knöpfe im Dock mindestens 64 dp, im Klima-Feld 96 dp. */
        const val DOCK_KNOPF = 64
        const val GROSS_KNOPF = 96
        const val UPDATE_TAKT_MS = 5 * 60_000L
    }
}
