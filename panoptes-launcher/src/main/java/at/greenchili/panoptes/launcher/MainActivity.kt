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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class MainActivity : AppCompatActivity() {
    private val client = PanoptesClient()
    // 0.1.41 (Nachtest F1): nach dem Beenden keine Arbeit mehr annehmen, Rückrufe nur in einer lebenden Activity
    private val io = SichererIo()
    private val main: Handler = LebensHandler { !isFinishing && !isDestroyed }
    private var stopped = false
    private var cachedHistory = LauncherHistory(null, null)
    private var lastHistoryFetch = 0L

    // A slow or half-available localhost endpoint must not queue another complete
    // refresh every five seconds. Otherwise the single IO worker can build an
    // unbounded backlog and paint minutes-old vehicle data after recovery.
    private val refreshInFlight = AtomicBoolean(false)

    // Klima läuft getrennt: „an“ wartet im Kern einige Sekunden auf das Echo und darf die Anzeige nicht aufhalten.
    private val klimaIo = SichererIo()
    private val klimaLaeuft = AtomicBoolean(false)

    // Update-Karte (Stufe 4): Stand höchstens alle 5 min lesen, „Später“ gilt je Version.
    private var updateStand: UpdateStand? = null
    private var lastUpdateFetch = 0L
    private lateinit var updateHost: FrameLayout

    // ---------- Zustand ----------
    private lateinit var ui: Ui
    private var dichteDpi = 0
    private lateinit var layout: StartLayout
    private var apps: List<InstallierteApp> = emptyList()
    private var appsNachPaket: Map<String, InstallierteApp> = emptyMap()
    private var summary: LauncherSummary? = null
    /** 0.1.44 (P3): letzter guter Kern-Stand (Kacheln bei Kern-Aus „zuletzt vor X min“) und Messpunkte ins Log. */
    private val frische = Frische.Merker()
    private val messUhr = Messpunkte.Uhr()
    private var appearance: LauncherAppearance? = null
    private var traffic = LauncherTraffic(0, 0)
    private var online = false
    private var bearbeiten = false
    private var seite = 0
    /** 0.1.42 (L48): letzte Berührung (Sperre der Selbststeuerung, Stand-Uhr), Zustand der Selbststeuerung, Stand-Uhr. */
    private var letzteBedienung = System.currentTimeMillis()
    private val selbst = Selbststeuerung.Zustand()
    private var standUhr: View? = null
    private var medien: Wiedergabe? = null
    /** 0.1.41 (E2E M1/G4): Android meldet Medienänderungen selbst, die Kachel zeichnet sofort neu. */
    private val medienBeobachter by lazy { MedienBeobachter(this, main) { medienNeu() } }
    // M1 (13.11.): Titel ohne Benachrichtigungszugriff über den Kern (KernMusik.kt); am BYD keine gesperrte Android-Seite
    private val kernMusik by lazy { KernMusik { f -> client.musik(f) } }
    private val byd by lazy { MusikAnzeige.BYD_PAKETE.any { p -> runCatching { packageManager.getPackageInfo(p, 0) }.isSuccess } }
    private var stilSchluessel = ""

    // ---------- Ansichten ----------
    private lateinit var root: FrameLayout
    private lateinit var bild: ImageView
    private lateinit var video: VideoView
    /** 0.1.40 (E2E M1): Schleier über dem eigenen Bild, damit Dock und Texte lesbar bleiben. */
    private lateinit var schleier: View
    private lateinit var seitenHost: Wischer
    private lateinit var punkte: LinearLayout
    private lateinit var hinweis: TextView
    private var raster: FreiLayout? = null
    /** 0.1.44 (E2): freier Editor – Hilfslinien, Rückgängig/Wiederholen, Ziehen/Größe/Aussehen (FreiEditor.kt). */
    private var linienAnsicht: HilfslinienAnsicht? = null
    private val verlauf = Verlauf()
    private val freiEditor by lazy {
        FreiEditor(this, ui, { layout }, { seite }, { raster }, { linienAnsicht }, { neu -> aendern(neu); main.post { zeichneSeite() } },
            { zeigeHinweis(it) }, { seitenHost.zieht = it })
    }
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
            val m = medienLesen()
            medien = m
            MusikStart.gesehen(this@MainActivity, m?.paket, m?.spielt == true)   // 0.1.41 (LM)
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

    // 0.1.38.14 (P4): Größe nach Bildschirm + Nutzerwahl, eine Stelle (LauncherGroesse)
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LauncherGroesse.anwenden(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        messUhr.start(System.currentTimeMillis())
        // 0.1.39 (L1-Nachbesserung): Karten-Split übersteht einen Neustart der Activity (Konfigwechsel)
        karteImSplit = savedInstanceState?.getString(ZUSTAND_KARTE_SPLIT)
        splitGewolltBeendet = savedInstanceState?.getBoolean(ZUSTAND_SPLIT_GEWOLLT) ?: false
        // E5 (13.11.): keine Layout-Frage mehr (Einfach.kt); erster Start = neu eingerichtet, Update zählt nicht
        val ersterStart = getSharedPreferences(PREFS, MODE_PRIVATE).let {
            Vorlagen.fragen(it.contains(KEY_LAYOUT), it.getBoolean(KEY_VORLAGE_GEFRAGT, false), launcherLief())
        }
        layout = ladeLayout()
        baueAlles()
        kernAbgleich()
        main.post { erstStart(ersterStart) }
        // 0.1.44 (P1): Haus-Taste startete den Launcher neu, während der Selbsttest darauf wartete
        if (intent?.hasCategory(Intent.CATEGORY_HOME) == true && Selbsttest.hausAngekommen(System.currentTimeMillis()))
            main.post { EinrichtungActivity.starten(this, Selbsttest.Schritt.ZURUECK) }
        displayAn = Ruhe.displayAn(displayZustand())
        runCatching { getSystemService(DisplayManager::class.java)?.registerDisplayListener(displayHoerer, main) }
        if (!isInMultiWindowMode) splitEndeGesehen()   // Split endete, während die Activity neu startete
        // 0.1.41 (LM): letzte Musik weiterspielen, nur wenn eingeschaltet (ab Werk aus), einmal je Start
        MusikStart.beimStart(this, main, { startbar(it) || rueckfallIntent(it) != null }) { starteVollbild(it) }
    }

    /** 0.1.44 (P1): Haus-Taste kam an; wartet der Selbsttest der Einrichtung darauf, geht es dort weiter. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.hasCategory(Intent.CATEGORY_HOME) == true && Selbsttest.hausAngekommen(System.currentTimeMillis()))
            main.post { EinrichtungActivity.starten(this, Selbsttest.Schritt.ZURUECK) }
    }

    private fun bedienhilfeErklaeren() {
        AlertDialog.Builder(this).setTitle(R.string.hilfe_einstellung).setMessage(R.string.hilfe_erklaerung)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                // 0.1.44 (V1 E1): fehlt die Android-Seite, sagt der Knopf es, statt still nichts zu tun
                if (!PanoptesBedienhilfe.einstellungOeffnen(this)) zeigeHinweis(getString(R.string.einstellung_fehlt))
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(ZUSTAND_KARTE_SPLIT, karteImSplit)
        outState.putBoolean(ZUSTAND_SPLIT_GEWOLLT, splitGewolltBeendet)
    }

    override fun onStart() {
        super.onStart()
        messUhr.haus(System.currentTimeMillis())
        Messpunkte.beiSichtbar(window.decorView) { Messpunkte.log(messUhr.sichtbar(System.currentTimeMillis())) }
        stopped = false
        geraeteWidgets.start()                  // 0.1.41 (LO)
        schnellleiste.launcherVorn(true)        // 0.1.41 (LO): nie über dem Launcher selbst
        leiste?.aus()   // 0.1.39: Launcher wieder vorn → keine Leiste über ihm
        zustandAnwenden()
        ladeApps()
    }

    override fun onResume() {
        super.onResume()
        // 0.1.41 (E2E G4): Zugriff bei jeder Rückkehr neu prüfen (z. B. von der Android-Seite „Benachrichtigungszugriff“)
        medienBeobachter.start()
        medienNeu()
    }

    /** M1: eigene Mediensitzung (mit Zugriff), sonst der letzte Stand vom Kern; stößt die Kern-Abfrage an. */
    private fun medienLesen(): Wiedergabe? {
        val letztes = MusikStart.letztes(this)
        if (MedienQuelle.zugriffErlaubt(this)) return MedienQuelle.aktuell(this, letztes)
        val jetzt = System.currentTimeMillis()
        kernMusik.anstossen(jetzt, letztes) { main.post { if (!stopped) medienNeu() } }
        return kernMusik.wiedergabe(jetzt)
    }

    /** Mediensitzung neu lesen und Musik-Kachel + Statusleiste zeichnen (Meldung von Android oder Rückkehr). */
    private fun medienNeu() {
        val m = medienLesen()
        medien = m
        MusikStart.gesehen(this, m?.paket, m?.spielt == true)
        aktualisierer.forEach { it() }
        statusAktualisierer.forEach { it() }
    }

    override fun onStop() {
        medienBeobachter.stop()
        stopped = true
        geraeteWidgets.stop()
        schnellleiste.launcherVorn(false)
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

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        if (!isInMultiWindowMode) splitEndeGesehen()
        baueAlles()
    }

    /** Karten-Split ist zu Ende: gewollt („Start“) still, sonst loggen (grund=system/system_r) und bei R auf den Rückfall. */
    private fun splitEndeGesehen() {
        val p = karteImSplit
        if (p != null) {
            karteImSplit = null
            if (splitGewolltBeendet) { splitGewolltBeendet = false }
            else {
                // vom System beendet (z. B. Rückwärtsgang/Rückfahrkamera): loggen; bei R sicher auf den Rückfall
                val grund = KartenStatus.abbruchGrund(null, summary?.gear)
                splitAbbruchLoggen(p, grund)
                if (grund == "system_r") karteVollbildMitLeiste(p, nachR = true)
            }
        }
    }

    private fun splitAbbruchLoggen(p: String, grund: String) =
        android.util.Log.w("PanoptesLauncher", "SPLIT_ABBRUCH paket=$p grund=$grund gang=${summary?.gear}")

    override fun onDestroy() {
        leiste?.aus()
        schnellleiste.aus()
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
        // 0.1.41 G5: Dichtewechsel zur Laufzeit (im Auto nicht vorgesehen) → Activity sauber neu, statt halb alter Ansichten
        if (dichteDpi > 0 && newConfig.densityDpi != dichteDpi) { recreate(); return }
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

    /** 0.1.40 (L2): eigenes Design + Akzent; „Wie Panoptes“ (ab Werk) = bisheriges Verhalten. Cockpit bleibt dunkel. */
    private fun stilPalette(cockpit: Boolean, modus: String?): Palette =
        if (cockpit) PanoptesStil.DUNKEL else PanoptesStil.gestaltet(designJetzt(), modus, systemHell(), layout.akzent)

    /** 0.1.41 (LA): Design, das gerade gilt; mit „Tag/Nacht automatisch“ nachts „Nacht“ ([TagNacht]). */
    private fun designJetzt(): String {
        val an = TagNacht.an(this)
        if (!an) return layout.design
        val c = java.util.Calendar.getInstance()
        val nacht = TagNacht.istNacht(summary?.istNacht, c.get(java.util.Calendar.MONTH) + 1,
            c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
        return TagNacht.design(layout.design, an, nacht)
    }

    private fun systemHell() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES

    private fun baueAlles() {
        val cockpit = layout.hintergrund == "cockpit"
        val pal = stilPalette(cockpit, appearance?.modus)
        ui = Ui(this, pal, cockpit, GrosseBedienung.faktor(this))   // 0.1.44 (P1): „Große Bedienung“
        dichteDpi = resources.configuration.densityDpi
        stilSchluessel = "${pal.hell}:$cockpit:${pal.aqua}:${pal.background}"
        aktualisierer.clear(); statusAktualisierer.clear()
        // 0.1.41 (Nachtest N2): der Takt vergleicht minutengenau; nach dem Neuaufbau beim nächsten Schlag sicher aktualisieren
        taktStand = ""
        overlay = null
        window.statusBarColor = if (cockpit) PanoptesStil.COCKPIT_SEITE[0] else pal.seiteVon
        window.navigationBarColor = window.statusBarColor
        if (pal.hell && !cockpit) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        else window.decorView.systemUiVisibility = 0

        standUhr = null
        // 0.1.42 (L48): eigene Motive (per Code gezeichnet) auf dem Seitenverlauf des Designs
        root = FrameLayout(this).apply { background = Motive.nummer(layout.hintergrund)?.let { MotivHintergrund(it, pal) } ?: SeitenHintergrund(pal, cockpit) }
        bild = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.GONE }
        root.addView(bild, FrameLayout.LayoutParams(-1, -1))
        schleier = View(this).apply { visibility = View.GONE }
        root.addView(schleier, FrameLayout.LayoutParams(-1, -1))
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
        if (layout.zeigeReiter && !bearbeiten && !Raster.hatWidget(layout, Kachel.REITERLEISTE)) spalte.addView(reiterZeile().also { if (szene()) FahrzeugKacheln.pillenLeiste(it, ui) }, ui.lp(-1, ui.dp(88), oben = 4))
        if (!Raster.hatWidget(layout, Kachel.DOCKLEISTE)) spalte.addView(dock(), ui.lp(-1, ui.bd(84), oben = 8))

        hinweis = ui.text("", 15f, 600, ui.p.onSurface).apply {
            visibility = View.GONE; gravity = Gravity.CENTER
            setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12))
            background = GradientDrawable().apply { cornerRadius = ui.dpf(16f); setColor(ui.p.surface); setStroke(ui.dp(1), ui.p.aqua) }
        }
        root.addView(hinweis, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = ui.dp(110) })
        // 0.1.39 (L1): neben der Karte (geteilter Bildschirm) statt der Startseite die schmale Statusspalte
        if (isInMultiWindowMode) {
            spalte.visibility = View.GONE
            root.addView(statusSpalte(), root.indexOfChild(hinweis), FrameLayout.LayoutParams(-1, -1))
        }
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
        // 0.1.41 (G5): Musik steht in einer dehnbaren Box. Ein langer Titel wird gekürzt, statt Uhr und Logo
        // aus der Zeile zu drücken (vorher blieb nach der Musik-Umstellung nur ♫ statt der Uhr).
        val musikBox = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END }
        val musikIcon = ui.icon(R.drawable.ic_pn_music, ui.p.onSurfaceVariant, 22)
        val musik = ui.text("", 16f, 600, ui.p.onSurfaceVariant).apply { maxLines = 1; maxWidth = ui.dp(380); ellipsize = android.text.TextUtils.TruncateAt.END }
        musikBox.addView(musikIcon, ui.lp(ui.dp(22), ui.dp(22), rechts = 8)); musikBox.addView(musik, ui.lp())
        z.addView(musikBox, ui.lp(0, -2, 1f, rechts = 22))
        // 0.1.44 (P3): Kern weg → die Kacheln zeigen alte Werte, hier steht wie alt (nie als frisch)
        val alt = ui.text("", 16f, 700, ui.p.warning).apply { maxLines = 1; visibility = View.GONE }
        z.addView(alt, ui.lp(rechts = 16))
        aktualisierer += {
            alt.text = frische.hinweis(this@MainActivity, online, System.currentTimeMillis())
            alt.visibility = if (alt.text.isEmpty()) View.GONE else View.VISIBLE
        }
        val uhr = ui.zahl("", 22f)
        z.addView(uhr, ui.lp(rechts = 16))
        z.addView(ImageView(this).apply {
            setImageDrawable(ContextCompat.getDrawable(this@MainActivity, R.drawable.logo_square))
            clipToOutline = true; outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            background = GradientDrawable().apply { cornerRadius = ui.dpf(10f); setColor(Color.TRANSPARENT) }
            contentDescription = getString(R.string.app_name)
        }, ui.lp(ui.dp(36), ui.dp(36)))
        // 0.1.41 (Nachtest N2): sofort einmal füllen. Vorher blieb die neu gebaute Leiste leer (keine Uhr, nur ♫),
        // bis sich Minute oder Musik änderten – bei pausierter Musik bis zu 60 s.
        val fuellen: () -> Unit = {
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
        statusAktualisierer += fuellen
        fuellen()
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
        // 0.1.44 (E2): Rückgängig/Wiederholen, Einrasten, Beschriftung, Notausgang „auf Vorlage zurücksetzen“
        k(R.drawable.ic_pn_prev, R.string.rueckgaengig) { verlauf.zurueck(layout)?.let { aendern(it, merken = false); baueAlles() } ?: zeigeHinweis(getString(R.string.nichts_rueckgaengig)) }
        k(R.drawable.ic_pn_next, R.string.wiederholen) { verlauf.vor(layout)?.let { aendern(it, merken = false); baueAlles() } }
        k(R.drawable.ic_pn_resize, if (freiEditor.einrasten) R.string.einrasten_an else R.string.einrasten_aus) { freiEditor.einrasten = !freiEditor.einrasten; baueAlles() }
        k(R.drawable.ic_pn_edit, R.string.text_hinzu) {
            nameFragen(getString(R.string.text_hinzu), "") { n ->
                val neu = Frei.hinzufuegen(layout, seite, ElementTyp.TEXT, 12, 3, text = n)
                if (neu === layout) zeigeHinweis(getString(R.string.kein_platz)); aendern(neu); zeichneSeite()
            }
        }
        k(R.drawable.ic_pn_home, R.string.vorlage_zuruecksetzen) { vorlageWaehlen() }
        // Fixrunde 0.1.43b (G3): Scrollposition bleibt nach jeder Aktion (Platzregeln.kt), Abstand und weicher Rand vor „Fertig“
        z.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; isHorizontalFadingEdgeEnabled = true; setFadingEdgeLength(ui.dp(36)); addView(knoepfe)
            Leistenlage.einhaengen(this)
        }, ui.lp(0, -2, 1f, rechts = 14))
        z.addView(ui.act(null, getString(R.string.fertig), primary = true) { bearbeiten = false; Leistenlage.zuruecksetzen(); verlauf.leeren(); baueAlles(); kernSpeichern() }, ui.lp(ui.dp(130), ui.dp(60)))
        return z
    }

    // ---------- Reiterzeile ----------

    /** 0.1.44 (D1): Hintergrund ist eine der eigenen Szenen (Motive 11–14)? */
    private fun szene() = Szenen.ist(Motive.nummer(layout.hintergrund))

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
        z.addView(reiter(R.drawable.ic_pn_map, R.string.reiter_karte, false) { karteMitStatus() }, ui.lp(ui.dp(104), -1, rechts = 6))
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
        // Fixrunde 0.1.43b (G4): mit „Große Bedienung“ wächst der Knopf mit, damit „Hinzufügen“ ganz lesbar bleibt
        z.addView(ui.act(R.drawable.ic_pn_plus, getString(R.string.hinzufuegen)) { if (darfBedienen()) schublade(Ziel.SCHNELL) }.apply { minimumWidth = ui.bd(104) }, ui.lp(-2, ui.bd(80)))
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
        z.addView(dockIcon(R.drawable.ic_pn_home, getString(R.string.byd_home), hervor = true) { zumBydStart() }, ui.lp(ui.bd(84), -1, rechts = 6))
        // 0.1.44 (P1): „Letzte App“ (Tasten.kt). „Zurück“ gehört in die Leiste über den Apps; über dem Launcher selbst gibt es nichts zurückzugehen.
        z.addView(dockIcon(R.drawable.ic_pn_wechsel, getString(R.string.taste_letzte)) { Tasten.druecken(this, Tasten.LETZTE, client) }, ui.lp(ui.bd(76), -1, rechts = 6))
        val favs = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        // Android Auto / CarPlay nur, wenn das BYD-Paket hier installiert und startbar ist (andere Autos: unsichtbar)
        layout.dock.filter { it !in Raster.PROJEKTION || startbar(it) }.forEach { p ->
            favs.addView(appKnopf(p, mitName = false, gross = Math.round(44 * ui.bedien)) { lange -> if (lange) leistenMenue(p, schnell = false) else starteApp(p) }, ui.lp(ui.bd(76), -1, rechts = 4))
        }
        favs.addView(dockIcon(R.drawable.ic_pn_plus, getString(R.string.hinzufuegen)) { if (darfBedienen()) schublade(Ziel.DOCK) }, ui.lp(ui.bd(76), -1))
        z.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(favs) }, ui.lp(0, -1, 1f))
        // Fixrunde 0.1.43b (G4): feste Breite (Platzregeln.kt DockPlatz), bei zu schmalem Dock die kompakte Form
        if (layout.zeigeKlima) {
            val kompakt = DockPlatz.kompakt(Math.round(resources.displayMetrics.widthPixels / resources.displayMetrics.density), ui.bedien)
            z.addView(klimaBedienung(kompakt), ui.lp(ui.dp(DockPlatz.klimaBreite(ui.bedien, kompakt)), ui.bd(64), rechts = 10))
        }
        z.addView(dockIcon(R.drawable.ic_pn_apps, getString(R.string.reiter_apps)) { schublade(null) }, ui.lp(ui.bd(76), -1, rechts = 4))
        z.addView(dockIcon(R.drawable.ic_pn_settings, getString(R.string.reiter_einstellungen)) { einstellungen() }, ui.lp(ui.bd(76), -1))
        return z
    }

    /**
     * Klima im Dock (Stufe 4): Temperatur −/+, Gebläse −/+, an/aus über den eng begrenzten Kern-Weg `klima`.
     * Der Kern prüft, dass die Anfrage von dieser APK kommt, und führt nur über die Panoptes-Klimabefehle mit allen
     * Sperren aus. Tipp auf die Werte öffnet das große Klima-Feld im Launcher (0.1.38.5), nicht Panoptes.
     */
    private fun klimaBedienung(kompakt: Boolean = false): View {
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
        val soll = ui.zahl("—", 21f).apply { minWidth = ui.dp(64); gravity = Gravity.CENTER; maxLines = 1 }
        val stufe = ui.zahl("—", 21f).apply { minWidth = ui.dp(20); gravity = Gravity.CENTER }
        val power = ui.icon(R.drawable.ic_pn_power, ui.p.onSurfaceVariant, 26)
        z.addView(knopf(R.drawable.ic_pn_minus, R.string.klima_temp_runter) { "temp_runter" }.also { tempKnoepfe += it }, ui.lp(ui.bd(DOCK_KNOPF), -1))
        z.addView(wert(R.drawable.ic_pn_thermo, soll), ui.lp(h = -1))
        z.addView(knopf(R.drawable.ic_pn_plus, R.string.klima_temp_hoch) { "temp_hoch" }.also { tempKnoepfe += it }, ui.lp(ui.bd(DOCK_KNOPF), -1, rechts = 6))
        if (!kompakt) {
            z.addView(knopf(R.drawable.ic_pn_minus, R.string.klima_luft_runter) { "luft_runter" }, ui.lp(ui.bd(DOCK_KNOPF), -1))
            z.addView(wert(R.drawable.ic_pn_fan, stufe), ui.lp(h = -1))
            z.addView(knopf(R.drawable.ic_pn_plus, R.string.klima_luft_hoch) { "luft_hoch" }, ui.lp(ui.bd(DOCK_KNOPF), -1, rechts = 6))
        }
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
            val i = Math.round(30 * ui.bedien)
            addView(ui.icon(res, if (hervor) ui.p.aqua else ui.p.onSurface, i), FrameLayout.LayoutParams(ui.dp(i), ui.dp(i), Gravity.CENTER))
        }
        addView(box, FrameLayout.LayoutParams(ui.bd(64), ui.bd(64), Gravity.CENTER))
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

    // ---------- 0.1.42 (L48): Selbststeuerung + Stand-Uhr (Logik in Selbststeuerung.kt / StandUhr.kt) ----------

    override fun onUserInteraction() {
        super.onUserInteraction()
        letzteBedienung = System.currentTimeMillis()
    }

    /** Nur Anzeige und Seitenwechsel, nie ein Fahrzeugbefehl. Gang nur frisch vom Kern (sonst null). */
    private fun selbstSteuern() {
        val s = summary
        val ziel = Selbststeuerung.schritt(selbst, Selbststeuerung.wahl(this), s?.gear,
            Selbststeuerung.laedt(s?.acc, s?.charging?.active), System.currentTimeMillis(), letzteBedienung) ?: return
        if (bearbeiten || stopped) return
        main.post {
            when (ziel) {
                Selbststeuerung.Ziel.KARTE -> if (karteImSplit == null) { standUhrWeg(); karteMitStatus() }
                // R1: im Karten-Split erst den geteilten Bildschirm verlassen (Navi läuft weiter), sonst bliebe die Statusspalte
                Selbststeuerung.Ziel.START -> { schliesseOverlay(); seite = 0; zeichneSeite()
                    if (Selbststeuerung.splitVerlassen(ziel, karteImSplit)) splitVerlassen() }
                Selbststeuerung.Ziel.LADEN -> {
                    val i = layout.seiten.indexOfFirst { p -> p.any { it.kachel == Kachel.LADEN } }
                        .takeIf { it >= 0 } ?: layout.seiten.indexOfFirst { p -> p.any { it.kachel == Kachel.AKKU } }
                    if (i >= 0) { standUhrWeg(); schliesseOverlay(); seite = i; zeichneSeite() }
                }
            }
        }
    }

    private fun nachtJetzt(): Boolean {
        val c = java.util.Calendar.getInstance()
        return TagNacht.istNacht(summary?.istNacht, c.get(java.util.Calendar.MONTH) + 1,
            c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
    }

    private fun standUhrPruefen() {
        val zeigen = StandUhr.zeigen(StandUhr.minuten(this), summary?.gear, bildAn && !stopped, overlay == null && !bearbeiten,
            System.currentTimeMillis(), letzteBedienung)
        if (zeigen && standUhr == null) {
            val v = StandUhr.ansicht(this, ui, nachtJetzt()) { standUhrWeg(); letzteBedienung = System.currentTimeMillis() }
            root.addView(v, FrameLayout.LayoutParams(-1, -1)); standUhr = v
        } else if (standUhr != null && summary?.gear != "P") standUhrWeg()
    }

    private fun standUhrWeg() { standUhr?.let { root.removeView(it) }; standUhr = null }

    private fun standUhrName(m: Int) = if (m == 0) getString(R.string.aus) else getString(R.string.stand_uhr_minuten, m)

    private fun formName(w: String) = getString(when (w) {
        "kompakt" -> R.string.form_kompakt; "suv" -> R.string.form_suv; "limousine" -> R.string.form_limousine; else -> R.string.form_auto
    })

    private fun formWaehlen() {
        auswahl(getString(R.string.fahrzeug_form), FahrzeugStart.FORMEN.map { w ->
            (if (w == FahrzeugStart.formWahl(this)) "✓ " else "") + formName(w) to { FahrzeugStart.formSetzen(this, w); zeichneSeite(); einstellungen(erweitert = true) }
        })
    }

    private fun standUhrWaehlen() {
        auswahl(getString(R.string.stand_uhr), StandUhr.MINUTEN.map { m ->
            (if (m == StandUhr.minuten(this)) "✓ " else "") + standUhrName(m) to { StandUhr.setzen(this, m); einstellungen(erweitert = true) }
        }, fuss = getString(R.string.stand_uhr_hinweis))
    }

    private fun selbstWaehlen() {
        val w = Selbststeuerung.wahl(this)
        val an = booleanArrayOf(w.karteBeiD, w.startBeiP, w.ladenVorn)
        AlertDialog.Builder(this).setCustomTitle(auswahlKopf(getString(R.string.selbst_titel), getString(R.string.selbst_hinweis)))
            .setMultiChoiceItems(arrayOf(getString(R.string.selbst_d), getString(R.string.selbst_p), getString(R.string.selbst_laden)), an) { _, i, x ->
                Selbststeuerung.setzen(this, Selbststeuerung.SCHLUESSEL[i], x)
            }
            .setPositiveButton(R.string.fertig) { _, _ -> einstellungen(erweitert = true) }
            .setOnCancelListener { einstellungen(erweitert = true) }
            .show()
    }

    private fun zeichneSeite(richtung: Int = 0) {
        aktualisierer.removeAll { it is KachelAktualisierer }
        seitenHost.removeAllViews()
        // 0.1.44 (E2): Feinraster 48 × 24 (FreiLayout); Elemente auf ganzen Zellen liegen wie bisher
        val r = FreiLayout(this, ui.dp(Raster.abstandDp(layout.abstand)))
        raster = r
        layout.seiten[seite].forEach { e -> val q = Frei.rect(e); r.addView(baueElement(e), FreiLayout.Fein(q[0], q[1], q[2], q[3])) }
        if (bearbeiten) {
            // freie Zellen sichtbar machen
            for (y in 0 until Raster.ZEILEN) for (x in 0 until Raster.SPALTEN) {
                val zelle = intArrayOf(x * Frei.FEIN, y * Frei.FEIN, Frei.FEIN, Frei.FEIN)
                if (Frei.passt(layout.seiten[seite], zelle)) r.addView(View(this).apply {
                    background = GradientDrawable().apply { cornerRadius = ui.dpf(16f); setStroke(ui.dp(1), ui.p.line, ui.dpf(8f), ui.dpf(6f)) }
                }, 0, FreiLayout.Fein(zelle[0], zelle[1], zelle[2], zelle[3]))
            }
        }
        seitenHost.addView(r, FrameLayout.LayoutParams(-1, -1))
        linienAnsicht = if (bearbeiten) HilfslinienAnsicht(this, ui).also { seitenHost.addView(it, FrameLayout.LayoutParams(-1, -1)) } else null
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
        // 0.1.44 (D1): auf einer Szene schwebende Glas-Karten, die Fahrzeug-Ansicht ohne Karte (FahrzeugKacheln.karte)
        box.background = if (e.typ == ElementTyp.WIDGET) FahrzeugKacheln.karte(e.kachel, szene(), ui) { ui.karte(hero) } else null
        if (e.typ == ElementTyp.WIDGET && FahrzeugKacheln.schwebt(e.kachel, szene())) box.elevation = ui.dpf(10f)
        // 0.1.44 (E2): eigener Stil je Element (Ecken, Deckung, Unschärfe-Ersatz, Akzent; Lesbarkeit geprüft)
        if (Stil.gesetzt(e) && e.typ != ElementTyp.TEXT && !FahrzeugKacheln.frei(e.kachel)) box.background = Stil.karte(e, ui)
        val inhalt: View = when (e.typ) {
            ElementTyp.WIDGET -> widget(e)
            ElementTyp.APP -> appKnopf(e.paket!!, mitName = true, gross = 64) { lange -> if (lange) langerDruck(e) else starteApp(e.paket) }.apply {
                background = ui.karte(); setPadding(ui.dp(6), ui.dp(8), ui.dp(6), ui.dp(8))
            }
            ElementTyp.ORDNER -> ordnerKachel(e)
            ElementTyp.TEXT -> textElement(e)
        }
        box.addView(inhalt, FrameLayout.LayoutParams(-1, -1))
        if (bearbeiten) bearbeitenSchicht(box, e)
        return box
    }

    private fun langerDruck(e: Element) {
        if (!darfBedienen()) return
        bearbeiten = true; verlauf.leeren(); baueAlles()
    }

    /** 0.1.44 (E2): Beschriftung – nur Text, Größe passt sich der Fläche an (mindestens 16 sp), Akzent nur wenn lesbar. */
    private fun textElement(e: Element): View = TextView(this).apply {
        text = e.name; typeface = ui.sora(600); gravity = Gravity.CENTER_VERTICAL; maxLines = 2
        setAutoSizeTextTypeUniformWithConfiguration(16, 96, 2, android.util.TypedValue.COMPLEX_UNIT_SP)
        val a = Farbe.hex(e.akzent)?.takeIf { Lesbarkeit.kontrast(it, ui.p.background) >= Lesbarkeit.TEXT }
        setTextColor(a ?: ui.p.onSurface)
        setPadding(ui.dp(6), 0, ui.dp(6), 0)
        setOnLongClickListener { langerDruck(e); true }
    }

    /** 0.1.44 (E2): Dock/Reiter als Element; ihre Aktualisierer werden wie die der Kacheln beim Neuzeichnen geräumt. */
    private fun leisteAlsKachel(f: () -> View): View {
        val vorher = aktualisierer.size
        val v = f()
        val neu = aktualisierer.subList(vorher, aktualisierer.size).toList()
        repeat(neu.size) { aktualisierer.removeAt(aktualisierer.size - 1) }
        neu.forEach { aktualisierer += KachelAktualisierer(it) }
        return v
    }

    // ---------- Bearbeiten-Modus: ziehen, Größe, entfernen ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun bearbeitenSchicht(box: FrameLayout, e: Element) {
        box.foreground = GradientDrawable().apply { cornerRadius = ui.dpf(20f); setStroke(ui.dp(2), ui.p.lime) }
        val fang = View(this)
        box.addView(fang, FrameLayout.LayoutParams(-1, -1))
        // 0.1.44 (E2): frei ziehen mit Einrasten und Hilfslinien (FreiEditor)
        fang.setOnTouchListener(freiEditor.ziehen(box, e) { kachelMenue(e) })
        fun abzeichen(res: Int, schwerkraft: Int, f: () -> Unit) = box.addView(FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ui.p.lime) }
            addView(ui.icon(res, ui.p.onPrimary, 22), FrameLayout.LayoutParams(ui.dp(22), ui.dp(22), Gravity.CENTER))
            isClickable = true; setOnClickListener { f() }
        }, FrameLayout.LayoutParams(ui.dp(48), ui.dp(48), schwerkraft).apply { setMargins(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)) })
        abzeichen(R.drawable.ic_pn_close, Gravity.TOP or Gravity.END) { aendern(Raster.entfernen(layout, e.id)); main.post { zeichneSeite() } }
        // 0.1.44 (E2): Größe am Griff frei ziehen; ein Tipp schaltet bei Kacheln wie bisher die Größen durch
        abzeichen(R.drawable.ic_pn_resize, Gravity.BOTTOM or Gravity.END) {}
        val griff = box.getChildAt(box.childCount - 1)
        griff.setOnTouchListener(freiEditor.groesseZiehen(box, griff, e) {
            if (e.typ == ElementTyp.WIDGET) {
                val neu = Raster.groesseWechseln(layout, e.id)
                if (neu === layout) zeigeHinweis(getString(R.string.kein_platz))
                aendern(neu); main.post { zeichneSeite() }
            }
        })
    }

    private fun kachelMenue(e: Element) {
        val punkte = mutableListOf<Pair<String, () -> Unit>>()
        if (e.typ == ElementTyp.WIDGET) {
            // 0.1.40 (L2): Größe je Kachel in drei Stufen statt Durchschalten
            val jetzt = Raster.stufeVon(e)
            Raster.STUFEN.keys.forEach { st ->
                punkte += ((if (st == jetzt) "✓ " else "") + getString(R.string.groesse) + ": " + stufeName(st)) to {
                    val neu = Raster.kachelStufe(layout, e.id, st)
                    if (neu === layout) zeigeHinweis(getString(R.string.kein_platz)); aendern(neu); zeichneSeite()
                }
            }
        }
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
        if (e.typ == ElementTyp.TEXT) punkte += getString(R.string.umbenennen) to { nameFragen(getString(R.string.umbenennen), e.name ?: "") { n -> aendern(Raster.umbenennen(layout, e.id, n)); zeichneSeite() } }
        // 0.1.44 (E2): Aussehen je Element (Ecken, Deckung, Unschärfe, Akzent)
        punkte += getString(R.string.stil_titel) to { auswahl(getString(R.string.stil_titel), freiEditor.stilPunkte(e) { t, p, fuss -> auswahl(t, p, fuss) }) }
        punkte += getString(R.string.entfernen) to {
            if (e.kachel == Kachel.GERAETEWIDGET) geraeteWidgets.freigeben(e.wid)   // 0.1.41 (LO): Bindung freigeben
            aendern(Raster.entfernen(layout, e.id)); zeichneSeite()
        }
        auswahl(titelVon(e), punkte)
    }

    // ---------- 0.1.41 (LO): Leiste über allen Apps und Android-Widgets (Logik in Schnellleiste.kt / GeraeteWidgets.kt)

    private val schnellleiste by lazy { Schnellleiste(applicationContext, ui) }
    private val geraeteWidgets by lazy { GeraeteWidgets(this) }

    private fun schnellName(w: String) = getString(when (w) {
        Schnellleiste.OBEN -> R.string.schnell_oben; Schnellleiste.LINKS -> R.string.schnell_links
        Schnellleiste.RECHTS -> R.string.schnell_rechts; else -> R.string.aus })

    private fun schnellleisteWaehlen() {
        auswahl(getString(R.string.schnell_titel), Schnellleiste.WAHL.map { w ->
            ((if (w == Schnellleiste.wahl(this)) "✓ " else "") + schnellName(w)) to {
                Schnellleiste.setzen(this, w)
                if (w != Schnellleiste.AUS && !Settings.canDrawOverlays(this)) schnellRechtFragen()
                schnellleiste.launcherVorn(true)
                einstellungen(erweitert = true)
            }
        }, getString(R.string.schnell_hinweis))
    }

    /** Recht nur über die Android-Einstellungsseite, vom Nutzer selbst erteilt (nie über den Daemon). */
    private fun schnellRechtFragen() {
        AlertDialog.Builder(this).setTitle(R.string.schnell_titel).setMessage(R.string.schnell_recht_text)
            .setPositiveButton(R.string.oeffnen) { _, _ ->
                try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                catch (_: Exception) { zeigeHinweis(getString(R.string.einstellung_fehlt)) }
            }
            .setNegativeButton(R.string.abbrechen, null).show()
    }

    private fun geraeteWidgetWaehlen() {
        val liste = geraeteWidgets.anbieter()
        if (liste.isEmpty()) { zeigeHinweis(getString(R.string.widget_geraet_keine)); return }
        auswahl(getString(R.string.widget_geraet_hinzu), liste.map { (name, info) ->
            name to {
                geraeteWidgets.hinzufuegen(info) { wid, i ->
                    val dm = resources.displayMetrics.density
                    val g = GeraeteWidgets.groesse((i.minWidth / dm).toInt(), (i.minHeight / dm).toInt())
                    val neu = Raster.geraeteWidgetHinzufuegen(layout, seite, wid, g)
                    if (neu === layout) { geraeteWidgets.freigeben(wid); zeigeHinweis(getString(R.string.kein_platz)) }
                    else { aendern(neu); zeichneSeite() }
                }
            }
        })
    }

    private fun geraeteWidgetKachel(e: Element): View {
        val v = e.wid?.let { geraeteWidgets.ansicht(this, it) }
        if (v != null) return v
        return spalte().apply { addView(ui.leise(getString(R.string.widget_geraet_fehlt), 14f)) }
    }

    private fun stufeName(st: String) = getString(when (st) { "klein" -> R.string.stufe_klein; "gross" -> R.string.stufe_gross; else -> R.string.stufe_mittel })

    private fun titelVon(e: Element) = when (e.typ) {
        ElementTyp.WIDGET -> kachelName(e.kachel!!)
        ElementTyp.APP -> appName(e.paket!!)
        ElementTyp.ORDNER -> e.name ?: ""
        ElementTyp.TEXT -> e.name ?: ""
    }

    private fun kachelName(k: Kachel) = getString(when (k) {
        Kachel.FAHRZEUG -> R.string.widget_fahrzeug; Kachel.AKKU -> R.string.widget_akku; Kachel.NAVI -> R.string.widget_navi
        Kachel.MUSIK -> R.string.widget_musik; Kachel.WAECHTER -> R.string.widget_waechter; Kachel.UHR -> R.string.widget_uhr
        Kachel.SCHLOSS -> R.string.widget_schloss; Kachel.LADEN -> R.string.widget_laden; Kachel.KAMERA -> R.string.widget_kamera
        Kachel.PROJEKTION -> R.string.widget_projektion
        Kachel.REIFEN -> R.string.widget_reifendruck; Kachel.FAHRT -> R.string.widget_fahrt; Kachel.TEMPO -> R.string.widget_tempo; Kachel.GERAETEWIDGET -> R.string.widget_geraet
        Kachel.FAHRSTATUS -> R.string.widget_fahrstatus; Kachel.AUTOANSICHT -> R.string.widget_autoansicht
        Kachel.DOCKLEISTE -> R.string.widget_dockleiste; Kachel.REITERLEISTE -> R.string.widget_reiterleiste
        Kachel.HAUS -> R.string.widget_haus; Kachel.PROFIL -> R.string.widget_profil
        Kachel.UMGEBUNG -> R.string.widget_umgebung
        Kachel.SPRACHE -> R.string.widget_sprache
    })

    private fun kachelHinzufuegen() {
        // 0.1.41 (CP): CarPlay / Android Auto nur anbieten, wenn eine Werks-App da ist
        val fehlend = Kachel.values().filter { it != Kachel.GERAETEWIDGET && !Raster.hatWidget(layout, it) && (it != Kachel.PROJEKTION || projektionSichtbar().isNotEmpty()) }
        // E5 (13.11.): zuerst die Grundauswahl (KachelKatalog.GRUND), alles andere unter „Weitere Kacheln …“
        fun eintrag(k: Kachel): Pair<String, () -> Unit> =
            kachelName(k) to {
                if (k == Kachel.DOCKLEISTE || k == Kachel.REITERLEISTE) {
                    // 0.1.44 (E2): Leiste als Element, ganze Breite, an den ersten freien Platz
                    val neu = Frei.hinzufuegen(layout, seite, ElementTyp.WIDGET, Frei.LEISTE.first, Frei.LEISTE.second, kachel = k)
                    if (neu === layout) zeigeHinweis(getString(R.string.kein_platz)) else { aendern(neu); baueAlles() }
                    return@to
                }
                val g = when (k) { Kachel.PROJEKTION -> Projektion.GROESSEN; Kachel.HAUS -> listOf(2 to 2, 2 to 1); Kachel.PROFIL -> listOf(1 to 1, 2 to 1); else -> listOf(2 to 1, 1 to 1) }
                val neu = Raster.widgetHinzufuegen(layout, seite, k, g)
                // 0.1.40 (E2E G2): Seite voll → Kachel auf einer neuen Seite anlegen und dorthin wechseln (statt still nichts)
                val mitSeite = if (neu === layout) Raster.seiteHinzufuegen(layout) else layout
                val aufNeuer = if (mitSeite !== layout) Raster.widgetHinzufuegen(mitSeite, mitSeite.seiten.size - 1, k, g) else neu
                if (neu !== layout) { aendern(neu); zeichneSeite() }
                else if (aufNeuer !== mitSeite) { aendern(aufNeuer); seite = aufNeuer.seiten.size - 1; baueAlles(); zeigeHinweis(getString(R.string.kachel_neue_seite)) }
                else zeigeHinweis(getString(R.string.kein_platz))
            }
        // 0.1.41 (LO): Android-Widgets immer anbietbar (mehrere erlaubt), Auswahl in eigener Liste
        val weitere = { auswahl(getString(R.string.kachel_weitere), KachelKatalog.weitere(fehlend).map { eintrag(it) } +
            listOf(getString(R.string.widget_geraet_hinzu) to { geraeteWidgetWaehlen() })) }
        if (KachelKatalog.direktWeitere(fehlend)) { weitere(); return }
        auswahl(getString(R.string.kachel_hinzu), KachelKatalog.grund(fehlend).map { eintrag(it) } + listOf(getString(R.string.kachel_weitere) to weitere))
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
            Kachel.SCHLOSS -> schlossKachel(gross)
            Kachel.LADEN -> ladenKachel(gross)
            Kachel.KAMERA -> kameraKachel(gross)
            Kachel.PROJEKTION -> projektionKachel(gross)
            Kachel.REIFEN -> zusatz.reifen(gross, e.h)
            Kachel.FAHRT -> zusatz.fahrt(gross)
            Kachel.TEMPO -> zusatz.tempo(gross, e.h)
            Kachel.GERAETEWIDGET -> geraeteWidgetKachel(e)
            Kachel.FAHRSTATUS -> fahrzeugStart.status(gross)
            Kachel.AUTOANSICHT -> fahrzeugStart.ansicht()
            Kachel.DOCKLEISTE -> leisteAlsKachel { dock() }
            Kachel.REITERLEISTE -> leisteAlsKachel { reiterZeile() }
            Kachel.HAUS -> haus.ansicht(gross, e.h)
            Kachel.PROFIL -> profilKachel.ansicht()
            Kachel.UMGEBUNG -> UmgebungKachel(this, ui, client)   // 0.1.44 (L1): Live-Umgebung, ab Werk aus
            Kachel.SPRACHE -> sprachKachel(gross)
        }
        // 0.1.38.5: direkt im Launcher; Panoptes nur über „Mehr in Panoptes“ im jeweiligen Feld
        when (k) {
            Kachel.FAHRZEUG -> v.setOnClickListener { fahrzeugInfo() }
            Kachel.AKKU -> v.setOnClickListener { akkuInfo() }
            Kachel.NAVI -> v.setOnClickListener { starteNavi() }
            Kachel.WAECHTER -> v.setOnClickListener { waechterInfo() }
            // 0.1.40: Befehle nie aus dem Launcher (Regel A+); nur auf Tipp, im Stand, mit Rückfrage nach Panoptes
            Kachel.SCHLOSS -> v.setOnClickListener { schlossFrage() }
            Kachel.LADEN -> v.setOnClickListener { akkuInfo() }
            Kachel.KAMERA -> v.setOnClickListener { liveAnsicht() }
            Kachel.FAHRSTATUS -> v.setOnClickListener { fahrzeugInfo() }
            // 0.1.44 (P4): Haus nur Anzeige (Tipp = Geräte wählen, nie schalten); Profil: Tipp wechselt
            Kachel.HAUS -> v.setOnClickListener { hausWaehlen() }
            Kachel.PROFIL -> v.setOnClickListener { profilTipp() }
            Kachel.SPRACHE -> v.setOnClickListener { sprechen() }
            else -> {}
        }
        v.setOnLongClickListener { langerDruck(e); true }
        return v
    }

    private fun spalte(pad: Int = 20) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(pad), ui.dp(pad - 2), ui.dp(pad), ui.dp(pad - 2)) }
    private fun reg(f: () -> Unit) { aktualisierer += KachelAktualisierer(f) }
    /** 0.1.41 (LK): Reifen, letzte Fahrt, Tempo – eigene Datei, hier nur eingehängt. */
    // ---------- 0.1.44 (P4): Haus-Kachel (nur lesen) und Fahrerprofile – Logik in HausKachel.kt / Fahrerprofile.kt ----------
    private var hausStand: HausStand? = null
    private var lastHausFetch = 0L
    private var lastSitzFetch = 0L
    private var letzteSitzId: String? = null
    private val haus get() = HausKachel(this, ui, { reg(it) }, { hausAuswahl() }, { hausStand })
    private val profilKachel get() = ProfilKachel(this, ui, { reg(it) }, { Fahrerprofile.stand(this) })

    private fun hausAuswahl() = Haus.auswahlLesen(getSharedPreferences(PREFS, MODE_PRIVATE).getString(Haus.KEY, null))

    /** Im Takt der Abfrage (io-Thread): Haus-Zustand und – nur wenn nötig – die erkannte Sitzposition holen. Nur lesen. */
    private fun hausUndSitzHolen(s: LauncherSummary?, jetzt: Long) {
        val wahl = hausAuswahl()
        if (wahl.isNotEmpty() && layout.seiten.any { p -> p.any { it.kachel == Kachel.HAUS } } && jetzt - lastHausFetch >= Haus.TAKT_MS) {
            lastHausFetch = jetzt
            hausStand = Haus.parse(client.haus(wahl))   // null = nicht lesbar: die Kachel zeigt „—“ und den Hinweis
        }
        val st = Fahrerprofile.stand(this)
        if (Fahrerprofile.sitzAbfragen(st, s?.gear, s?.speedKmh, jetzt, lastSitzFetch)) {
            lastSitzFetch = jetzt
            val info = Fahrerprofile.parseSitz(client.sitz()) ?: return
            val ziel = Fahrerprofile.ziel(st, info.aktuell, letzteSitzId, s?.gear, s?.speedKmh)
            if (info.aktuell != null) letzteSitzId = info.aktuell
            if (ziel != null) main.post { if (!bearbeiten && !stopped) profilWechseln(ziel) }
        }
    }

    private fun hausWaehlen() {
        if (!darfBedienen()) return
        io.execute {
            val alle = Haus.parse(client.haus(null))
            main.post {
                if (alle == null || alle.geraete.isEmpty()) {
                    zeigeHinweis(getString(if (alle == null) R.string.haus_nicht_lesbar else R.string.haus_keine)); return@post
                }
                var wahl = hausAuswahl()
                val namen = alle.geraete.map { it.titel + (it.raum?.let { r -> " ($r)" } ?: "") }.toTypedArray()
                val an = BooleanArray(alle.geraete.size) { alle.geraete[it].entity in wahl }
                AlertDialog.Builder(this).setCustomTitle(auswahlKopf(getString(R.string.haus_waehlen), getString(R.string.haus_hinweis)))
                    .setMultiChoiceItems(namen, an) { d, i, x ->
                        val neu = Haus.umschalten(wahl, alle.geraete[i].entity)
                        if (x && neu == wahl) { (d as AlertDialog).listView.setItemChecked(i, false); zeigeHinweis(getString(R.string.haus_max)) }
                        else wahl = neu
                    }
                    .setPositiveButton(R.string.fertig) { _, _ ->
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(Haus.KEY, Haus.auswahlSchreiben(wahl)).apply()
                        hausStand = null; lastHausFetch = 0L; zeichneSeite()
                    }
                    .setNegativeButton(R.string.abbrechen, null).show()
            }
        }
    }

    /** Wechsel zu Profil [nach]: das aktuelle Layout bleibt beim bisherigen Profil, Layout und Design des Ziels gelten. */
    private fun profilWechseln(nach: Int) {
        val neu = Fahrerprofile.wechseln(this, layout, nach) ?: return
        aendern(neu); seite = 0; schliesseOverlay(); baueAlles()
        zeigeHinweis(getString(R.string.profil_gewechselt, profilKachel.name(nach)))
    }

    private fun profilTipp() {
        if (!darfBedienen()) return
        val a = Fahrerprofile.anderes(Fahrerprofile.stand(this))
        if (a != null) profilWechseln(a) else profilDialog()
    }

    private fun profilDialog() {
        val st = Fahrerprofile.stand(this)
        val punkte = mutableListOf<Pair<String, () -> Unit>>()
        Fahrerprofile.anderes(st)?.let { a -> punkte += getString(R.string.profil_wechseln, profilKachel.name(a)) to { if (darfBedienen()) profilWechseln(a) } }
        if (!st.zweitesDa) {
            punkte += getString(R.string.profil_anlegen) to {
                if (darfBedienen()) Fahrerprofile.anlegen(this, layout)?.let { aendern(it); baueAlles(); profilDialog() }
            }
        } else {
            for (n in 1..Fahrerprofile.MAX) punkte += getString(R.string.profil_sitz, profilKachel.name(n),
                Fahrerprofile.sitzName(this, n) ?: getString(R.string.profil_sitz_keine)) to { sitzWaehlen(n) }
            punkte += getString(R.string.profil_auto) + ": " + getString(if (st.automatisch) R.string.an else R.string.aus) to
                { Fahrerprofile.autoSetzen(this, !st.automatisch); profilDialog() }
            punkte += getString(R.string.profil_entfernen) to {
                if (st.aktiv != 1) zeigeHinweis(getString(R.string.profil_entfernen_erst))
                else AlertDialog.Builder(this).setTitle(R.string.profil_entfernen)
                    .setPositiveButton(R.string.entfernen) { _, _ -> Fahrerprofile.entfernen(this); profilDialog() }
                    .setNegativeButton(R.string.abbrechen, null).show()
            }
        }
        auswahl(getString(R.string.profil_titel) + " – " + getString(R.string.profil_aktiv, profilKachel.name(st.aktiv)), punkte,
            fuss = getString(R.string.profil_hinweis))
    }

    private fun sitzWaehlen(n: Int) {
        io.execute {
            val info = Fahrerprofile.parseSitz(client.sitz())
            main.post {
                if (info == null || info.positionen.isEmpty()) { zeigeHinweis(getString(R.string.profil_sitz_leer)); return@post }
                val punkte = listOf(getString(R.string.profil_sitz_keine) to { Fahrerprofile.sitzSetzen(this, n, null, null); profilDialog() }) +
                    info.positionen.map { (id, name) -> name to { Fahrerprofile.sitzSetzen(this, n, id, name); profilDialog() } }
                auswahl(getString(R.string.profil_sitz_waehlen, profilKachel.name(n)), punkte, fuss = getString(R.string.profil_sitz_hinweis))
            }
        }
    }

    private val zusatz get() = ZusatzKacheln(this, ui, { reg(it) }, { summary }, { cachedHistory })
    /** 0.1.44 (D1): Fahrzeug-Startseite – eigene Datei, hier nur eingehängt. Fenster nur über den engen Kern-Weg. */
    private val fahrzeugStart get() = FahrzeugKacheln(this, ui, { reg(it) }, { summary }, { summary?.modelId }, { summary?.farbe },
        { f, ziel, fertig -> io.execute { val a = client.fenster(f, ziel); main.post { fertig(a) } } }, { zeigeHinweis(it) })
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
        mitte.addView(ersatz, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))   // 0.1.38.14: eingepasst statt fest 360×144 dp
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
        unten.addView(tempoBox, ui.lp(rechts = 16))
        val tempoSchild = Tempolimit.schild(this, ui, 52)   // 0.1.44 (P3): nur mit belegtem Limit sichtbar
        unten.addView(tempoSchild, ui.lp(ui.dp(52), ui.dp(52), rechts = 36))
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
            Tempolimit.setze(tempoSchild, tempo, s, online, ui.p.onSurface, ui.p.warning)
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
        // 0.1.41 (G1): nach einem Neuaufbau (Design-Wechsel) hängt das WebView in einer neuen Fläche → neu laden,
        // damit die Seite wieder „bereit“ meldet; bis dahin zeigt das Ersatzbild das gemerkte Standbild.
        if (w.parent !== mitte) { (w.parent as? ViewGroup)?.removeView(w); mitte.addView(w, FrameLayout.LayoutParams(-1, -1)); webAutoUrl = null }
        if (url != webAutoUrl) { webAutoUrl = url; autoGezeichnet = false; w.visibility = View.INVISIBLE; w.loadUrl(url) }
        ersatz.visibility = if (autoGezeichnet) View.GONE else View.VISIBLE
    }

    private var autoGezeichnet = false
    private var standbildFuer: String? = null

    /** Standbild aus Panoptes (`/api/models/art`) einmal je Modell holen; ohne Antwort bleibt die Silhouette. */
    private fun standbildLaden(bildView: ImageView?, modell: String?) {
        if (bildView == null || modell == null) return
        // 0.1.41 (G1): Das Standbild bleibt gemerkt. Nach einem Neuaufbau bekommt das NEUE Ersatzbild es sofort,
        // statt der flachen Silhouette (vorher wurde es je Modell nur einmal geholt und nur ins alte Bild gelegt).
        standbild?.takeIf { it.first == modell }?.let { (_, bmp) ->
            if (bildView.tag !== bmp) { bildView.setImageBitmap(bmp); bildView.alpha = 1f; bildView.tag = bmp }
            return
        }
        if (!online || modell == standbildFuer) return
        standbildFuer = modell
        io.execute {
            val bmp = runCatching { client.fahrzeugBild(modell) }.getOrNull()?.let { dekodieren(it) }
            main.post {
                if (bmp != null) { standbild = modell to bmp; bildView.setImageBitmap(bmp); bildView.alpha = 1f; bildView.tag = bmp } else standbildFuer = null
            }
        }
    }
    private var standbild: Pair<String, android.graphics.Bitmap>? = null
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

    /** 0.1.40 (L2) Schloss: Zustand aus dem Kern (state.locked, nur lesen). */
    private fun schlossKachel(gross: Boolean): View {
        val c = spalte()
        c.addView(LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; addView(ui.icon(R.drawable.ic_pn_lock, ui.p.aqua, 20)); addView(ui.titel(getString(R.string.widget_schloss)).apply { setPadding(ui.dp(8), 0, 0, 0) }) })
        val wert = ui.zahl("—", if (gross) 34f else 24f); c.addView(wert, ui.lp(oben = 8))
        reg {
            val zu = summary?.locked
            wert.text = when (zu) { true -> getString(R.string.schloss_zu); false -> getString(R.string.schloss_offen); null -> "—" }
            wert.setTextColor(when (zu) { true -> ui.p.lime; false -> ui.p.warning; null -> ui.p.onSurface })
        }
        return c
    }

    /** Ver-/Entriegeln nur in Panoptes (Launcher-Weg für Fahrzeugbefehle gesperrt): im Stand, nach Rückfrage. */
    private fun schlossFrage() {
        if (!darfBedienen()) return
        AlertDialog.Builder(this).setTitle(R.string.widget_schloss).setMessage(R.string.schloss_frage)
            .setPositiveButton(R.string.in_panoptes) { _, _ -> oeffnePanoptes("vehicle") }
            .setNegativeButton(R.string.abbrechen, null).show()
    }

    /** 0.1.40 (L2) Laden: Ladezustand aus dem Kern (charging, nur lesen). */
    private fun ladenKachel(gross: Boolean): View {
        val c = spalte()
        c.addView(LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; addView(ui.icon(R.drawable.ic_pn_plug, ui.p.aqua, 20)); addView(ui.titel(getString(R.string.widget_laden)).apply { setPadding(ui.dp(8), 0, 0, 0) }) })
        val wert = ui.zahl("—", if (gross) 34f else 24f); c.addView(wert, ui.lp(oben = 8))
        val info = ui.leise(""); c.addView(info, ui.lp(oben = 4))
        reg {
            val l = summary?.charging
            val soc = summary?.socPct?.let { "${Math.round(it)} %" }
            wert.text = if (l?.active == true) listOfNotNull(soc, l.kw?.let { String.format(Locale.getDefault(), "%.1f kW", it) }).joinToString(" · ").ifEmpty { getString(R.string.laden_aktiv) }
                else getString(R.string.laden_nicht)
            wert.setTextColor(if (l?.active == true) ui.p.lime else ui.p.onSurface)
            info.text = if (l?.active == true) listOfNotNull(l.targetPct?.let { getString(R.string.laden_ziel, Math.round(it).toInt()) },
                l.etaMin?.let { getString(R.string.laden_rest, Math.round(it).toInt()) }).joinToString(" · ") else soc ?: ""
        }
        return c
    }

    /** Live-Bild gibt es nur in Panoptes (Kamera-Daemon); dort gelten dessen Regeln. */
    private fun liveAnsicht() = oeffnePanoptes("live")

    /** 0.1.44 (P2) Sprechen: öffnet die Sprachbefehle von Panoptes (Regeln, Mikrofon-Erlaubnis und Rückfrage dort). */
    private fun sprachKachel(gross: Boolean): View {
        val c = spalte()
        c.addView(LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; addView(ui.icon(R.drawable.ic_pn_mikro, ui.p.aqua, if (gross) 30 else 22)); addView(ui.titel(getString(R.string.widget_sprache)).apply { setPadding(ui.dp(8), 0, 0, 0) }) })
        c.addView(ui.leise(getString(R.string.sprache_tipp), 15f), ui.lp(oben = 8))
        return c
    }

    /** Sprachbefehle von Panoptes (feste Komponente, exportiert); fehlt Panoptes, nur ein Hinweis. */
    private fun sprechen() {
        val i = Intent().setClassName(Raster.PANOPTES, Raster.PANOPTES + ".sprache.SprachStart").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(i) } catch (_: Exception) { zeigeHinweis(getString(R.string.launch_failed)) }
    }

    /** 0.1.40 (L2) Live-Kamera: öffnet die Panoptes-Liveansicht (dort gelten deren Regeln). */
    private fun kameraKachel(gross: Boolean): View {
        val c = spalte()   // 0.1.40 (E2E G3): Beschriftung oben wie bei den anderen Kacheln
        c.addView(LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; addView(ui.icon(R.drawable.ic_pn_camera, ui.p.aqua, if (gross) 30 else 22)); addView(ui.titel(getString(R.string.widget_kamera)).apply { setPadding(ui.dp(8), 0, 0, 0) }) })
        c.addView(ui.leise(getString(R.string.kamera_tipp), 15f), ui.lp(oben = 8))
        return c
    }

    /**
     * 0.1.41 (CP): CarPlay / Android Auto. Je Werks-App ein großer Knopf, Start im Vollbild (am Surf nicht teilbar).
     * Zustand („verbunden“) ist nicht ermittelbar und wird deshalb nicht gezeigt.
     */
    private fun projektionKachel(gross: Boolean): View {
        val c = spalte()
        c.addView(ui.titel(getString(R.string.widget_projektion)))
        val knoepfe = LinearLayout(this).apply { orientation = if (gross) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
        projektionSichtbar().forEach { p ->
            val name = getString(if (p == Raster.CARPLAY) R.string.projektion_carplay else R.string.projektion_android_auto)
            knoepfe.addView(ui.act(R.drawable.ic_pn_play, name, primary = true) { starteProjektion(p) },
                if (gross) ui.lp(-1, ui.dp(64), oben = 8) else ui.lp(0, ui.dp(64), 1f, rechts = 6))
        }
        c.addView(knoepfe, ui.lp(-1, -2, oben = 4))
        if (gross) c.addView(ui.leise(getString(R.string.projektion_tipp), 15f), ui.lp(oben = 8))
        return c
    }

    /** Startbare Werks-Apps für CarPlay / Android Auto (auch über die belegte Rückfall-Activity). */
    private fun projektionSichtbar(): List<String> = Projektion.sichtbar { startbar(it) || rueckfallIntent(it) != null }

    private fun rueckfallIntent(p: String): Intent? {
        val a = Projektion.RUECKFALL[p] ?: return null
        val i = Intent(Intent.ACTION_MAIN).setComponent(android.content.ComponentName(p, a))
        return if (runCatching { packageManager.resolveActivity(i, 0) != null }.getOrDefault(false)) i else null
    }

    /** Immer Vollbild, nie geteilt (resizeableActivity=false, kein force_resizable). */
    private fun starteProjektion(p: String) = starteVollbild(p)

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
        // Fixrunde 0.1.43b (G1): flache Karte (Vorlage Fahrzeug) mit schmalem Rand, Knopf direkt unter dem Titel (Platzregeln.kt)
        val flach = MusikKartePlatz.flach(e.h)
        val c = spalte(if (flach) MusikKartePlatz.RAND_FLACH else 20)
        val quelle = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val qIcon = ImageView(this); val qName = ui.text("", 18f, 700)
        quelle.addView(qIcon, ui.lp(ui.dp(30), ui.dp(30), rechts = 10)); quelle.addView(qName, ui.lp(0, -2, 1f))
        c.addView(ui.titel(getString(R.string.widget_musik)))
        c.addView(quelle, ui.lp(oben = 10))
        // 0.1.41 (LM, E2E M3): ohne Zugriff Hinweis + Knopf direkt unter dem Titel, damit er bei jeder Größe und
        // Dichte sichtbar bleibt (vorher ganz unten, bei „Größer“ abgeschnitten); auch der Hinweistext öffnet die Seite.
        // Erteilt wird der Zugriff nur dort vom Nutzer, nie per Befehl.
        qName.maxLines = 2
        val zugriff = ui.act(R.drawable.ic_pn_settings, getString(R.string.medien_zugriff_erlauben)) { zugriffSeite() }
        if (flach) {
            zugriff.orientation = LinearLayout.HORIZONTAL; zugriff.minimumHeight = ui.bd(MusikKartePlatz.KNOPF_FLACH)
            zugriff.setPadding(ui.dp(10), ui.dp(2), ui.dp(10), ui.dp(2))
            (zugriff.getChildAt(1) as? TextView)?.apply { setPadding(ui.dp(8), 0, 0, 0); maxLines = 1 }
            zugriff.contentDescription = getString(R.string.medien_zugriff_hinweis)
            c.addView(zugriff, c.childCount - 1, ui.lp(-1, -2, oben = 6))   // vor die Quellen-Zeile
        } else c.addView(zugriff, ui.lp(-1, -2, oben = 8))
        val cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
            background = GradientDrawable().apply { cornerRadius = ui.dpf(16f); setColor(ui.p.act) }
        }
        val coverGross = e.h >= 3
        val coverRahmen = FrameLayout(this).apply { addView(cover, FrameLayout.LayoutParams(ui.dp(if (coverGross) 170 else 96), ui.dp(if (coverGross) 170 else 96), Gravity.CENTER)) }
        if (e.h >= 2) c.addView(coverRahmen, ui.lp(-1, 0, 1f, oben = 10))
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
        reg {
            val m = medien
            val erlaubt = MedienQuelle.zugriffErlaubt(this)
            // M1: ohne Zugriff Titel vom Kern; Knopf zur Android-Seite nur außerhalb des BYD (dort gesperrt)
            val modus = MusikAnzeige.modus(erlaubt, kernMusik.liefert(System.currentTimeMillis()), byd)
            val knopf = modus == MusikAnzeige.Modus.ZUGRIFF
            val daten = modus == MusikAnzeige.Modus.LOKAL || modus == MusikAnzeige.Modus.KERN
            val ohne = if (daten) View.VISIBLE else View.GONE
            zugriff.visibility = if (knopf) View.VISIBLE else View.GONE
            coverRahmen.visibility = ohne; titel.visibility = ohne; kuenstler.visibility = ohne
            balken.visibility = if (modus == MusikAnzeige.Modus.LOKAL) View.VISIBLE else View.GONE
            val kompakt = MusikKartePlatz.kompakt(e.h, !knopf)
            quelle.visibility = if (kompakt) View.GONE else View.VISIBLE; tasten.visibility = if (kompakt) View.GONE else View.VISIBLE
            quelle.setOnClickListener(when (modus) {
                MusikAnzeige.Modus.ZUGRIFF -> View.OnClickListener { zugriffSeite() }
                MusikAnzeige.Modus.OHNE_TITEL -> View.OnClickListener { zugriffErklaeren() }
                else -> null
            })
            if (m == null) {
                qIcon.setImageDrawable(null)
                qName.text = getString(when (modus) {
                    MusikAnzeige.Modus.ZUGRIFF -> R.string.medien_zugriff_hinweis
                    MusikAnzeige.Modus.OHNE_TITEL -> R.string.medien_kern_fehlt
                    else -> R.string.medien_nichts
                })
                // 0.1.41 (Nachtest N1): der Hinweis ist ein ganzer Satz; bis 4 Zeilen (flache Karte 2) statt nach 2 abgeschnitten
                qName.maxLines = if (daten) 2 else if (MusikKartePlatz.flach(e.h)) 2 else 4; qName.textSize = if (daten) 18f else 16f
                titel.text = "—"; kuenstler.text = ""; balken.progress = 0; cover.setImageDrawable(null)
            } else {
                qIcon.setImageDrawable(appsNachPaket[m.paket]?.icon); qName.text = appName(m.paket)
                qName.maxLines = 2; qName.textSize = 18f
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

    private fun einstellungen(erweitert: Boolean = false) {
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(24), ui.dp(18), ui.dp(24), ui.dp(24)) }
        c.addView(kopf(getString(if (erweitert) R.string.erweitert else R.string.reiter_einstellungen)))
        val liste = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun zeile(res: Int, text: String, f: () -> Unit) = liste.addView(ui.act(res, text) { f() }.apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL or Gravity.START
            (getChildAt(1) as? TextView)?.apply { setPadding(ui.dp(14), 0, 0, 0); textSize = 16f * ui.bedien; gravity = Gravity.START or Gravity.CENTER_VERTICAL }
        }, ui.lp(-1, ui.bd(64), oben = 8))
        fun schalter(text: Int, an: Boolean) = getString(text) + ": " + getString(if (an) R.string.an else R.string.aus)
        // 0.1.38.14 (P4, Zielgruppe 50+): oben nur, was man im Alltag braucht; Seltenes unter „Mehr“ (nichts fällt weg)
        // E5 (13.11.): oben genau die 5 Zeilen aus Einstellungen.OBEN, in dieser Reihenfolge, dann „Mehr“
        if (!erweitert) {
            zeile(R.drawable.ic_pn_home, getString(R.string.einr_titel)) { if (darfBedienen()) EinrichtungActivity.starten(this) }
            zeile(R.drawable.ic_pn_apps, getString(R.string.launcher_groesse) + ": " + groesseName(LauncherGroesse.wahl(this))) { if (darfBedienen()) groesseWaehlen() }
            // 0.1.44 (P1): „Große Bedienung“ (GrosseBedienung.kt) und geführte Einrichtung (Einrichtung.kt)
            zeile(R.drawable.ic_pn_star, schalter(R.string.gross_titel, GrosseBedienung.an(this))) {
                val an = !GrosseBedienung.an(this); GrosseBedienung.setzen(this, an)
                // E5: ein noch unverändertes Werks-Layout wird mit „Großer Bedienung“ zu „Einfach“; eigene Layouts bleiben
                val v = ErstStart.vorlage(an, getSharedPreferences(PREFS, MODE_PRIVATE).contains(KEY_LAYOUT))
                if (v != null && darfBedienen(still = true)) { vorlageSetzen(v); return@zeile }
                baueAlles(); einstellungen(); zeigeHinweis(getString(R.string.gross_hinweis)) }
            zeile(R.drawable.ic_pn_nav, getString(R.string.navi_app) + ": " + (naviWahl()?.let { appName(it) } ?: getString(R.string.navi_app_auto))) { naviWaehlen() }
            zeile(R.drawable.ic_pn_map, getString(R.string.hintergrund)) { if (darfBedienen()) hintergrundWaehlen() }
            zeile(R.drawable.ic_pn_settings, getString(R.string.erweitert)) { einstellungen(erweitert = true) }
        } else {
            zeile(R.drawable.ic_pn_close, getString(R.string.zurueck)) { einstellungen() }
            // E5: aus der oberen Liste hierher: Startseiten bearbeiten, Layout-Vorlage, Klima im Dock, 3D-Fahrzeug
            zeile(R.drawable.ic_pn_edit, getString(R.string.bearbeiten)) { if (darfBedienen()) { bearbeiten = true; baueAlles() } }
            zeile(R.drawable.ic_pn_apps, getString(R.string.vorlage)) { if (darfBedienen()) vorlageWaehlen() }
            zeile(R.drawable.ic_pn_thermo, schalter(R.string.zeige_klima, layout.zeigeKlima)) { if (darfBedienen()) { aendern(layout.copy(zeigeKlima = !layout.zeigeKlima)); baueAlles(); einstellungen(erweitert = true) } }
            zeile(R.drawable.ic_pn_car, schalter(R.string.zeige_3d, layout.zeige3d)) { if (darfBedienen()) { aendern(layout.copy(zeige3d = !layout.zeige3d)); baueAlles(); einstellungen(erweitert = true) } }
            // 0.1.40 (L2) „Selbst gestalten“: alles hier unter „Erweitert“, oben bleibt es einfach
            // 0.1.40 (E2E M2): Beim Hintergrund „Cockpit“ gilt immer das dunkle Cockpit-Design → gesperrt mit Hinweis statt wirkungslos
            val cockpit = layout.hintergrund == "cockpit"
            zeile(R.drawable.ic_pn_star, getString(R.string.design) + ": " + (if (cockpit) getString(R.string.design_cockpit) else designName(layout.design))) {
                if (cockpit) zeigeHinweis(getString(R.string.design_cockpit_hinweis)) else if (darfBedienen()) designWaehlen() }
            zeile(R.drawable.ic_pn_star, getString(R.string.akzent) + ": " + (if (cockpit) getString(R.string.design_cockpit) else akzentName(layout.akzent))) {
                if (cockpit) zeigeHinweis(getString(R.string.design_cockpit_hinweis)) else if (darfBedienen()) akzentWaehlen() }
            // 0.1.41 (LA): Tag/Nacht automatisch (Standard aus) und Layout-Vorlagen
            zeile(R.drawable.ic_pn_star, if (cockpit) getString(R.string.tagnacht) + ": " + getString(R.string.design_cockpit) else schalter(R.string.tagnacht, TagNacht.an(this))) {
                if (cockpit) zeigeHinweis(getString(R.string.design_cockpit_hinweis)) else if (darfBedienen()) tagNachtUmschalten() }
            zeile(R.drawable.ic_pn_map, getString(R.string.bild_waehlen)) { if (darfBedienen()) bildWaehlen() }
            if (layout.hintergrund == "eigen")
                zeile(R.drawable.ic_pn_map, getString(R.string.abdunkelung) + ": " + schleierName(Schleier.wahl(this))) { if (darfBedienen()) schleierWaehlen() }
            zeile(R.drawable.ic_pn_apps, getString(R.string.abstand) + ": " + abstandName(layout.abstand)) { if (darfBedienen()) abstandWaehlen() }
            zeile(R.drawable.ic_pn_nav, getString(R.string.spalte_titel)) { if (darfBedienen()) spalteWaehlen() }
            zeile(R.drawable.ic_pn_status, schalter(R.string.zeige_status, layout.zeigeStatus)) { if (darfBedienen()) { aendern(layout.copy(zeigeStatus = !layout.zeigeStatus)); baueAlles(); einstellungen(erweitert = true) } }
            zeile(R.drawable.ic_pn_apps, schalter(R.string.zeige_reiter, layout.zeigeReiter)) { if (darfBedienen()) { aendern(layout.copy(zeigeReiter = !layout.zeigeReiter)); baueAlles(); einstellungen(erweitert = true) } }
            zeile(R.drawable.ic_pn_apps, schalter(R.string.neben_alle, layout.nebeneinander)) { if (darfBedienen()) { aendern(layout.copy(nebeneinander = !layout.nebeneinander)); baueAlles(); einstellungen(erweitert = true) } }
            // 0.1.41 (LC): Kombi-Knopf und Seitenblick (KombiSeitenblick.kt), beide ab Werk aus
            zeile(R.drawable.ic_pn_nav, schalter(R.string.kombi_einstellung, kombiKnopfAn())) { if (darfBedienen()) kombiEinstellung() }
            zeile(R.drawable.ic_pn_camera, getString(R.string.seitenblick_titel)) { if (darfBedienen()) seitenblickFragen() }
            zeile(R.drawable.ic_pn_music, schalter(R.string.medien_zugriff, MedienQuelle.zugriffErlaubt(this))) { zugriffErklaeren() }
            zeile(R.drawable.ic_pn_play, schalter(R.string.musik_weiterspielen, MusikStart.an(this))) { MusikStart.umschalten(this); einstellungen(erweitert = true) }
            // 0.1.44 (P4): Fahrerprofile (ab Werk eines), Sitzposition nur gelesen
            zeile(R.drawable.ic_pn_auto_seite, getString(R.string.profil_titel) + ": " + profilKachel.name(Fahrerprofile.stand(this).aktiv)) { profilDialog() }
            // 0.1.42 (L48): Selbststeuerung und Stand-Uhr, ab Werk aus
            zeile(R.drawable.ic_pn_auto_seite, getString(R.string.selbst_titel) + ": " + getString(if (Selbststeuerung.wahl(this).irgendeine) R.string.an else R.string.aus)) { selbstWaehlen() }
            zeile(R.drawable.ic_pn_clock, getString(R.string.stand_uhr) + ": " + standUhrName(StandUhr.minuten(this))) { standUhrWaehlen() }
            // 0.1.44 (D1): Grundform der eigenen Fahrzeug-Ansicht (Automatisch = aus dem Modell)
            zeile(R.drawable.ic_pn_car, getString(R.string.fahrzeug_form) + ": " + formName(FahrzeugStart.formWahl(this))) { formWaehlen() }
            // 0.1.41 (LO): Leiste über allen Apps (Haus, Lautstärke), Standard aus
            zeile(R.drawable.ic_pn_home, getString(R.string.schnell_titel) + ": " + schnellName(Schnellleiste.wahl(this))) { if (darfBedienen()) schnellleisteWaehlen() }
            // 0.1.44 (P1): Bedienungshilfe für Zurück / Letzte App – nur die Android-Seite öffnen, einschalten tut der Nutzer
            zeile(R.drawable.ic_pn_zurueck, schalter(R.string.hilfe_einstellung, PanoptesBedienhilfe.aktiv != null)) { bedienhilfeErklaeren() }
            zeile(R.drawable.ic_pn_route, getString(R.string.exportieren)) { exportieren() }
            zeile(R.drawable.ic_pn_plus, getString(R.string.importieren)) { if (darfBedienen()) importieren() }
            zeile(R.drawable.ic_pn_close, getString(R.string.zuruecksetzen)) { if (darfBedienen()) zuruecksetzenFragen() }
            zeile(R.drawable.ic_pn_info, getString(R.string.app_info)) { appInfo(packageName) }
        }
        liste.addView(ui.leise(getString(R.string.rueckweg_hinweis), 14f), ui.lp(oben = 14))
        c.addView(ScrollView(this).apply { addView(liste) }, ui.lp(-1, 0, 1f, oben = 8))
        zeigeOverlay(c, breit = false)
        (c.parent as? View)?.layoutParams = FrameLayout.LayoutParams(ui.dp(620), -1, Gravity.CENTER).apply { setMargins(0, ui.dp(30), 0, ui.dp(30)) }
    }

    private fun groesseName(w: String) = getString(when (w) {
        LauncherGroesse.KLEINER -> R.string.groesse_kleiner
        LauncherGroesse.GROESSER -> R.string.groesse_groesser
        else -> R.string.groesse_auto
    })

    /** 0.1.38.14: Größe wie beim Tacho; der Launcher baut sich danach neu auf (gleich sichtbar), nichts sonst ändert sich. */
    private fun groesseWaehlen() {
        val jetzt = LauncherGroesse.wahl(this)
        auswahl(getString(R.string.launcher_groesse), LauncherGroesse.WAHL.map { w ->
            (if (w == jetzt) "✓ " + groesseName(w) else groesseName(w)) to {
                schliesseOverlay()
                if (w != jetzt) { LauncherGroesse.speichern(this, w); recreate() }
            }
        }, fuss = getString(R.string.groesse_hinweis))
    }

    private fun hintergrundWaehlen() {
        val a = appearance
        val optionen = (Raster.hintergrundWahl(a?.hintergrundBild == true, a?.hintergrundVideo == true, EigenesBild.da(filesDir)) + Motive.WERTE).map { it to hgName(it) }
        auswahl(getString(R.string.hintergrund), optionen.map { (w, t) -> (if (w == layout.hintergrund) "✓ $t" else t) to { schliesseOverlay(); aendern(Raster.hintergrund(layout, w)); baueAlles() } },
            fuss = getString(R.string.hg_hinweis))
    }

    private val motivNamen = intArrayOf(R.string.motiv_1, R.string.motiv_2, R.string.motiv_3, R.string.motiv_4, R.string.motiv_5,
        R.string.motiv_6, R.string.motiv_7, R.string.motiv_8, R.string.motiv_9, R.string.motiv_10,
        R.string.motiv_11, R.string.motiv_12, R.string.motiv_13, R.string.motiv_14)   // 0.1.44 (D1): Szenen

    private fun hgName(w: String) = Motive.nummer(w)?.let { getString(R.string.hg_motiv) + ": " + getString(motivNamen[it - 1]) } ?: getString(when (w) {
        "cockpit" -> R.string.hg_cockpit; "bild" -> R.string.hg_bild; "video" -> R.string.hg_video; "eigen" -> R.string.hg_eigen
        else -> R.string.hg_panoptes
    })

    private fun designName(d: String) = getString(when (d) {
        "dunkel" -> R.string.design_dunkel; "hell" -> R.string.design_hell; "kontrast" -> R.string.design_kontrast
        "nacht" -> R.string.design_nacht; else -> R.string.design_auto
    })

    private val akzentNamen = intArrayOf(R.string.akzent_tuerkis, R.string.akzent_gruen, R.string.akzent_orange, R.string.akzent_koralle,
        R.string.akzent_violett, R.string.akzent_blau, R.string.akzent_gelb, R.string.akzent_weiss)

    private fun akzentName(a: String?): String {
        val i = Raster.AKZENTE.indexOf(a)
        return if (i >= 0) getString(akzentNamen[i]) else getString(R.string.akzent_standard)
    }

    private fun schleierName(w: String) = getString(when (w) {
        Schleier.SCHWACH -> R.string.abdunkelung_schwach; Schleier.STARK -> R.string.abdunkelung_stark; else -> R.string.abdunkelung_auto
    })

    /** 0.1.40 (E2E M1): Stärke des Schleiers über dem eigenen Bild; nur im Launcher gespeichert, nie exportiert. */
    private fun schleierWaehlen() = auswahl(getString(R.string.abdunkelung), Schleier.WAHL.map { w ->
        ((if (w == Schleier.wahl(this)) "✓ " else "") + schleierName(w)) to {
            Schleier.speichern(this, w); schliesseOverlay(); baueAlles(); einstellungen(erweitert = true)
        }
    }, fuss = getString(R.string.abdunkelung_hinweis))

    private fun abstandName(a: String) = getString(when (a) { "eng" -> R.string.abstand_eng; "weit" -> R.string.abstand_weit; else -> R.string.abstand_normal })

    /** Nach einer Design-Wahl: speichern, neu aufbauen, „Erweitert“ wieder zeigen (gleich sichtbar). */
    private fun gestaltet(neu: StartLayout) { schliesseOverlay(); aendern(neu); baueAlles(); einstellungen(erweitert = true) }

    private fun designWaehlen() = auswahl(getString(R.string.design), Raster.DESIGNS.map { d ->
        ((if (d == layout.design) "✓ " else "") + designName(d)) to { gestaltet(Raster.design(layout, d)) }
    })

    private fun akzentWaehlen() = auswahl(getString(R.string.akzent),
        listOf(((if (layout.akzent == null) "✓ " else "") + getString(R.string.akzent_standard)) to { gestaltet(Raster.akzent(layout, null)) }) +
            Raster.AKZENTE.map { h -> ((if (h == layout.akzent) "✓ " else "● ") + akzentName(h)) to { gestaltet(Raster.akzent(layout, h)) } })

    private fun abstandWaehlen() = auswahl(getString(R.string.abstand), Raster.ABSTAENDE.map { a ->
        ((if (a == layout.abstand) "✓ " else "") + abstandName(a)) to { gestaltet(Raster.abstand(layout, a)) }
    })

    private fun spalteWaehlen() {
        val teile = Raster.SPALTE_TEILE
        val namen = intArrayOf(R.string.spalte_akku, R.string.spalte_reichweite, R.string.spalte_gang, R.string.spalte_waechter,
            R.string.spalte_musik, R.string.spalte_uhr, R.string.spalte_kamera).map { getString(it) }.toTypedArray()
        val an = BooleanArray(teile.size) { teile[it] in layout.spalte }
        AlertDialog.Builder(this).setTitle(R.string.spalte_titel)
            .setMultiChoiceItems(namen, an) { _, i, ja -> an[i] = ja }
            .setPositiveButton(android.R.string.ok) { _, _ -> if (darfBedienen()) gestaltet(Raster.spalte(layout, teile.filterIndexed { i, _ -> an[i] })) }
            .setNegativeButton(R.string.abbrechen, null).show()
    }

    /** Eigenes Hintergrundbild: Datei oder USB-Stick über die Android-Dateiauswahl; wird verkleinert gespeichert. */
    private fun bildWaehlen() {
        val wege = listOf(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"),
            Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"))
        for (w in wege) {
            try { @Suppress("DEPRECATION") startActivityForResult(w, REQ_BILD); return } catch (_: Exception) {}
        }
        zeigeHinweis(getString(R.string.keine_dateiwahl))
    }

    private fun zuruecksetzenFragen() {
        AlertDialog.Builder(this).setTitle(R.string.zuruecksetzen).setMessage(R.string.zuruecksetzen_frage)
            .setPositiveButton(R.string.zuruecksetzen) { _, _ -> schliesseOverlay(); aendern(Raster.standard(appearance?.startAnsicht)); seite = 0; bearbeiten = false; baueAlles() }
            .setNegativeButton(R.string.abbrechen, null).show()
    }

    /**
     * Liste zum Antippen. 0.1.40 (L2-Fix): KEIN setMessage – bei AlertDialog verdrängt eine Nachricht die Liste aus
     * setItems (darum zeigten „Größe“ und „Hintergrund“ keine Auswahl). Der Hinweis steht jetzt im Kopf über der Liste.
     */
    private fun auswahl(titel: String, punkte: List<Pair<String, () -> Unit>>, fuss: String? = null) {
        val b = AlertDialog.Builder(this)
        if (fuss == null) b.setTitle(titel) else b.setCustomTitle(auswahlKopf(titel, fuss))
        b.setItems(Auswahl.eintraege(punkte)) { _, i -> punkte[i].second() }
            .setNegativeButton(R.string.abbrechen, null).show()
    }

    private fun auswahlKopf(titel: String, hinweis: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(ui.dp(24), ui.dp(20), ui.dp(24), ui.dp(4))
        addView(TextView(context).apply { setTextAppearance(android.R.style.TextAppearance_DeviceDefault_DialogWindowTitle); text = titel })
        addView(TextView(context).apply { setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Small); text = hinweis; setPadding(0, ui.dp(6), 0, 0) })
    }

    private fun nameFragen(titel: String, vorgabe: String, ok: (String) -> Unit) {
        if (!darfBedienen()) return
        val feld = EditText(this).apply { setText(vorgabe); setSingleLine(); hint = getString(R.string.ordner_name) }
        AlertDialog.Builder(this).setTitle(titel).setView(feld)
            .setPositiveButton(android.R.string.ok) { _, _ -> if (darfBedienen()) ok(feld.text.toString()) }
            .setNegativeButton(R.string.abbrechen, null).show()
    }

    // ==================== 0.1.41 (LA) Vorlagen, Tag/Nacht ====================

    private fun vorlageName(v: String) = getString(when (v) {
        Vorlagen.EINFACH -> R.string.vorlage_einfach; Vorlagen.COCKPIT -> R.string.vorlage_cockpit; Vorlagen.FAHRZEUG -> R.string.vorlage_fahrzeug; else -> R.string.vorlage_standard
    })

    private fun vorlageText(v: String) = getString(when (v) {
        Vorlagen.EINFACH -> R.string.vorlage_einfach_text; Vorlagen.COCKPIT -> R.string.vorlage_cockpit_text; Vorlagen.FAHRZEUG -> R.string.vorlage_fahrzeug_text; else -> R.string.vorlage_standard_text
    })

    /**
     * E5 (13.11.): keine Layout-Frage mehr. Neu eingerichtet: „Standard“ bleibt, mit „Großer Bedienung“ gilt „Einfach“
     * ([ErstStart.vorlage]); gewechselt wird unter Einstellungen → Mehr → Layout-Vorlage. Danach einmal das
     * Startbildschirm-Angebot ([startAngebot]).
     */
    private fun erstStart(neu: Boolean) {
        if (isFinishing) return
        if (neu) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_VORLAGE_GEFRAGT, true).apply()
            ErstStart.vorlage(GrosseBedienung.an(this))?.let { v ->
                val neustart = Vorlagen.groesse(v).let { g -> g != null && g != LauncherGroesse.wahl(this) }
                vorlageSetzen(v)
                if (neustart) return   // die Activity startet mit der neuen Größe neu; dort kommt das Angebot
            }
        }
        angebotPruefen = true
        startAngebot()
    }

    /** E5: Angebot noch offen? Wird bei jedem neuen Kern-Stand erneut geprüft, bis es gezeigt oder unnötig ist. */
    private var angebotPruefen = false

    /**
     * E5 (Alfreds Entscheidung): Nach dem ersten Start EINMAL „Als Startbildschirm einrichten?“ mit „Später“.
     * Nur im Stand und ohne offenes Fenster; sonst beim nächsten Kern-Stand. Danach nur noch in den Einstellungen.
     */
    private fun startAngebot() {
        if (!angebotPruefen || isFinishing || stopped) return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (ErstStart.erledigt(prefs.getBoolean(KEY_HOME_ANGEBOTEN, false), istStartbildschirm())) { angebotPruefen = false; return }
        if (overlay != null || !darfBedienen(still = true)) return   // später, beim nächsten Kern-Stand
        angebotPruefen = false
        prefs.edit().putBoolean(KEY_HOME_ANGEBOTEN, true).apply()
        AlertDialog.Builder(this).setTitle(R.string.einr_angebot_titel).setMessage(R.string.einr_angebot_text)
            .setPositiveButton(R.string.einr_angebot_ja) { _, _ -> if (darfBedienen()) EinrichtungActivity.starten(this) }
            .setNegativeButton(R.string.einr_angebot_spaeter, null).show()
    }

    /** Ist dieser Launcher schon der Startbildschirm des Systems? */
    private fun istStartbildschirm(): Boolean = runCatching {
        packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName == packageName
    }.getOrDefault(false)

    /** 0.1.41 (E2E M2): Update statt Neuinstallation? Dann keine Erst-Frage, auch ohne gespeichertes Layout. */
    private fun launcherLief(): Boolean {
        val pi = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        return Vorlagen.lief(pi?.firstInstallTime ?: 0L, pi?.lastUpdateTime ?: 0L, kernPrefs.getBoolean(KEY_KENNT_KERN, false))
    }

    /** 0.1.41 (E2E G2): Vorlage, die nach „Erst sichern“ gesetzt wird, sobald der Export geschrieben ist. */
    private var vorlageNachExport: String? = null

    /** Unter „Erweitert“: Vorlage wählen, vor dem Ersetzen fragen (mit Weg „erst als Datei sichern“). */
    private fun vorlageWaehlen() = auswahl(getString(R.string.vorlage), Vorlagen.WAHL.map { v ->
        (vorlageName(v) + " – " + vorlageText(v)) to {
            AlertDialog.Builder(this).setTitle(getString(R.string.vorlage) + ": " + vorlageName(v)).setMessage(R.string.vorlage_ersetzen_frage)
                .setPositiveButton(R.string.vorlage_ersetzen) { _, _ -> if (darfBedienen()) vorlageSetzen(v) }
                .setNeutralButton(R.string.vorlage_erst_sichern) { _, _ -> vorlageNachExport = v; exportieren() }
                .setNegativeButton(R.string.abbrechen, null).show()
        }
    }, fuss = getString(R.string.vorlage_hinweis))

    private fun vorlageSetzen(v: String) {
        val text = runCatching { assets.open(Vorlagen.datei(v)).use { String(it.readBytes(), Charsets.UTF_8) } }.getOrNull()
        val neu = Vorlagen.lesen(v, text)
        if (neu == null) { zeigeHinweis(getString(R.string.import_fehler)); return }
        schliesseOverlay(); bearbeiten = false
        aendern(Vorlagen.anwenden(layout, neu)); seite = 0
        val g = Vorlagen.groesse(v)
        if (g != null && g != LauncherGroesse.wahl(this)) { LauncherGroesse.speichern(this, g); recreate(); return }
        baueAlles()
        zeigeHinweis(getString(R.string.vorlage_ok, vorlageName(v)))
    }

    private fun tagNachtUmschalten() {
        val an = !TagNacht.an(this)
        TagNacht.speichern(this, an)
        schliesseOverlay(); baueAlles(); einstellungen(erweitert = true)
        if (an) zeigeHinweis(getString(R.string.tagnacht_hinweis))
    }

    // ==================== Layout speichern, Export/Import ====================

    private fun ladeLayout(): StartLayout {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.contains("ordnung")) prefs.edit().remove("ordnung").apply() // Vorstufe (nur Testbuilds)
        return Raster.fromJson(prefs.getString(KEY_LAYOUT, null)) ?: Raster.standard(null)
    }

    private fun aendern(neu: StartLayout, merken: Boolean = true) {
        if (neu === layout) return
        if (bearbeiten && merken) verlauf.merke(layout)   // 0.1.44 (E2): Rückgängig im Bearbeiten-Modus
        layout = neu
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_LAYOUT, Raster.toJson(neu))
            .putBoolean(KEY_OFFEN, true).putLong(KEY_LOKAL_ZEIT, System.currentTimeMillis()).apply()
        // Während Bearbeiten erst bei „Fertig“; Änderungen außerhalb (Leiste, Import, Einstellungen) gleich.
        if (!bearbeiten) kernSpeichern()
    }

    // ==================== 0.1.38 Layout im Panoptes-Kern ====================

    private val layoutIo = SichererIo()
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
        if (geraeteWidgets.ergebnis(requestCode, resultCode)) return   // 0.1.41 (LO): Android-Widget binden/einrichten
        // 0.1.41 (E2E G2): Export abgebrochen = Vorlage wird nicht gesetzt; nur nach geschriebener Datei weiter
        val nachExport = if (requestCode == REQ_EXPORT) vorlageNachExport.also { vorlageNachExport = null } else null
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        try {
            when (requestCode) {
                REQ_BILD -> {
                    zeigeHinweis(getString(R.string.bild_laeuft))
                    io.execute {
                        val ok = EigenesBild.uebernehmen(contentResolver, uri, filesDir)
                        main.post {
                            if (!ok) { zeigeHinweis(getString(R.string.bild_fehler)); return@post }
                            schliesseOverlay(); aendern(Raster.hintergrund(layout, "eigen")); bildGeladen = false; baueAlles()
                            zeigeHinweis(getString(R.string.bild_ok))
                        }
                    }
                }
                REQ_EXPORT -> {
                    val ok = contentResolver.openOutputStream(uri, "wt")?.use { it.write(Raster.toJson(layout).toByteArray(Charsets.UTF_8)); true } ?: false
                    if (ok) zeigeHinweis(getString(R.string.export_ok))
                    if (ok && nachExport != null && darfBedienen()) vorlageSetzen(nachExport)
                }
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
                projektionAbgleichen(liste.isNotEmpty())
                if (overlay == null) baueAlles()
            }
        }
    }

    /**
     * 0.1.41 (CP): Kachel CarPlay / Android Auto höchstens EINMAL auf freien Platz ergänzen (Merker; wer sie entfernt,
     * behält sie weg), nie verschieben, Dock unberührt. Ohne Werks-App fällt sie weg (kein leerer Platz).
     */
    private fun projektionAbgleichen(appsBekannt: Boolean) {
        if (bearbeiten) return
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        val a = Projektion.abgleichen(layout, projektionSichtbar(), p.getBoolean(KEY_PROJEKTION_ERGAENZT, false), appsBekannt)
        if (a.merken) p.edit().putBoolean(KEY_PROJEKTION_ERGAENZT, true).apply()
        if (a.layout !== layout) { aendern(a.layout); seite = seite.coerceIn(0, layout.seiten.size - 1) }
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
                runCatching { hausUndSitzHolen(s, now) }   // 0.1.44 (P4): nur lesen
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
        Tasten.kernDa = ok   // 0.1.44 (P1): Weg „Daemon“ für Zurück / Letzte App
        val warOnline = online
        if (ok && !warOnline && kernPrefs.getBoolean(KEY_OFFEN, false)) kernSpeichern()
        if (LayoutAbgleich.neuHolen(s?.layoutGeaendert, kernPrefs.getLong(KEY_BASIS, 0L), kernPrefs.getBoolean(KEY_OFFEN, false),
                bearbeiten || overlay != null)) kernAbgleich()
        val jetzt = System.currentTimeMillis()
        if (ok) frische.gut(s, jetzt)
        messUhr.daten(ok, jetzt).forEach { Messpunkte.log(it) }
        summary = if (ok) s else frische.alt(jetzt); traffic = t; online = ok
        selbstSteuern(); standUhrPruefen()   // 0.1.42 (L48)
        bearbeitenSperren()   // 0.1.44 (E2): Bearbeiten nur im Stand
        if (angebotPruefen) startAngebot()   // E5: Startbildschirm-Angebot, sobald der Stand bekannt ist
        if (!ok && warOnline) zeigeHinweis(getString(R.string.offline))
        val alt = appearance; appearance = a
        val cockpit = layout.hintergrund == "cockpit"
        val pal = stilPalette(cockpit, a?.modus)
        if ("${pal.hell}:$cockpit:${pal.aqua}:${pal.background}" != stilSchluessel && overlay == null) { baueAlles(); return }
        if (alt?.hintergrundBild != a?.hintergrundBild || alt?.hintergrundVideo != a?.hintergrundVideo) bildGeladen = false
        hintergrundAnwenden()
        // 0.1.41 (E2E M1): ruht der Sekundentakt, hält die Abfrage auch die Medien aktuell
        if (!Ruhe.taktLaeuft(stopped, bildAn, fokus)) medien = medienLesen()
        aktualisierer.forEach { it() }
        // ohne Sekundentakt (Ruhe, kein Fokus) hält die Abfrage Uhr und Statusleiste aktuell
        if (!Ruhe.taktLaeuft(stopped, bildAn, fokus)) statusAktualisierer.forEach { it() }
        klimaFeldZeichnen?.invoke()
        if (bildAn != warBildAn) zustandAnwenden()
    }

    // ---------- Hintergrund ----------

    private fun hintergrundAnwenden() {
        if (layout.hintergrund == "eigen") {
            // 0.1.40: eigenes Bild aus dem Launcher (fehlt es, bleibt der Panoptes-Look)
            videoStopp()
            if (!bildGeladen) {
                bildGeladen = true
                io.execute {
                    val bmp = EigenesBild.laden(filesDir)
                    val hell = bmp?.let { Schleier.bildHelligkeit(it) } ?: 0.5f
                    main.post {
                        if (bmp != null) { bild.setImageBitmap(bmp); bild.visibility = View.VISIBLE }
                        schleier.setBackgroundColor(Schleier.farbe(ui.p.seiteBis, Schleier.deckung(hell, ui.p.seiteBis, Schleier.wahl(this), ui.p.onSurfaceVariant)))
                        schleier.visibility = if (bmp != null) View.VISIBLE else View.GONE
                    }
                }
            }
            return
        }
        schleier.visibility = View.GONE
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
    /** 0.1.44 (E2): beim Losfahren den Bearbeiten-Modus beenden (Stand bleibt gespeichert), Bedienung normal. */
    private fun bearbeitenSperren() {
        val s = summary
        if (!bearbeiten || Fahrsicherheit.bearbeitenErlaubt(s?.gear, s?.acc, s?.speedKmh)) return
        main.post { if (bearbeiten) { bearbeiten = false; verlauf.leeren(); baueAlles(); kernSpeichern(); zeigeHinweis(getString(R.string.bearbeiten_gesperrt)) } }
    }

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

    private var leiste: StatusLeiste? = null
    /** Navi-App, die gerade geteilt neben dem Launcher läuft (null = kein Karten-Split). */
    private var karteImSplit: String? = null
    private var splitGewolltBeendet = false

    /**
     * 0.1.39 (L1) „Karte“: die gewählte Navi-App geteilt neben dem Launcher (der zeigt dann die Statusspalte). Geht das
     * nicht (nicht teilbar / Fehler), sicher auf den Rückfall: Navi im Vollbild + Statusleiste oben (mit Overlay-Recht).
     */
    private fun karteMitStatus() {
        val p = Fahrsicherheit.naviZiel(naviWahl(), summary?.naviGewaehlt, summary?.naviBekannt.orEmpty()) { startbar(it) }
        if (p == null) { zeigeHinweis(getString(R.string.navi_keine_app)); oeffnePanoptes("navi"); return }
        if (p == Raster.PANOPTES) { starteVollbild(p); return }
        io.execute {
            val r = client.nebenOeffnen(p)
            main.post {
                if (r == NebenErgebnis.OK) karteImSplit = p
                else { splitAbbruchLoggen(p, KartenStatus.abbruchGrund(r, summary?.gear)); karteVollbildMitLeiste(p) }
            }
        }
    }

    private fun karteVollbildMitLeiste(p: String, nachR: Boolean = false) {
        if (!Settings.canDrawOverlays(this)) { leisteErlaubenFragen(p); return }
        leiste?.aus()   // je Karten-Start eine neue Leiste (eigener Einmal-Versuch, richtiges Paket)
        val l = StatusLeiste(applicationContext, ui, object : StatusLeiste.Aktionen {
            override fun musik() = medienTaste(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            override fun kamera() = oeffnePanoptes("live")
            override fun start() { leiste?.aus(); startActivity(Intent(this@MainActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
            override fun splitNochmal() {
                io.execute {
                    val r = client.nebenOeffnen(p)
                    main.post {
                        if (r == NebenErgebnis.OK) { karteImSplit = p; leiste?.aus() }
                        else splitAbbruchLoggen(p, "nochmal_" + KartenStatus.abbruchGrund(r, summary?.gear))
                    }
                }
            }
        }, layout.spalte).also { leiste = it }
        l.abbruchBeiR = nachR
        if (!nachR) starteVollbild(p)   // nach R läuft die Navi schon (das System hat nur die Teilung beendet)
        l.an()
    }

    /** Einmalige, ehrliche Frage: ohne das Recht öffnet die Karte im Vollbild ohne Leiste. */
    private fun leisteErlaubenFragen(p: String) {
        AlertDialog.Builder(this).setTitle(R.string.leiste_recht_titel).setMessage(R.string.leiste_recht_text)
            .setPositiveButton(R.string.oeffnen) { _, _ ->
                try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                catch (_: Exception) { zeigeHinweis(getString(R.string.einstellung_fehlt)) }
            }
            .setNegativeButton(R.string.leiste_ohne) { _, _ -> starteVollbild(p) }.show()
            .getButton(AlertDialog.BUTTON_POSITIVE)?.apply { textSize = 20f; minHeight = ui.dp(64) }
    }

    /**
     * „Start“ neben der Karte: geteilten Bildschirm verlassen, Launcher im Vollbild (Kern verschiebt den Launcher-Task),
     * die Navi läuft dahinter weiter. Geht das nicht, wenigstens den Launcher nach vorn holen (bisheriges Verhalten).
     */
    private fun splitVerlassen() {
        splitGewolltBeendet = true
        io.execute {
            val ok = client.splitBeenden()
            main.post {
                if (!ok) {
                    android.util.Log.w("PanoptesLauncher", "SPLIT_ENDE fehlgeschlagen, nur nach vorn")
                    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                }
            }
        }
    }

    /** 0.1.39 (L1): schmale Statusspalte neben der Karte – große Werte, große Knöpfe, keine Texteingabe. */
    private fun statusSpalte(): View {
        // 0.1.40 (L2): Teile selbst zusammengestellt (Einstellungen → Erweitert → Statusspalte), „Start“ ist immer da
        val t = layout.spalte
        val c = spalte(18)
        val zeile = ui.zahl("—", 30f); if (t.any { it == "akku" || it == "reichweite" || it == "gang" }) c.addView(zeile)
        val uhr = ui.zahl("", 30f); if ("uhr" in t) c.addView(uhr, ui.lp(oben = 8))
        val waechter = ui.text("", 17f, 600, ui.p.onSurfaceVariant); if ("waechter" in t) c.addView(waechter, ui.lp(oben = 8))
        val titel = ui.text("", 16f, 600).apply { maxLines = 2 }; if ("musik" in t) c.addView(titel, ui.lp(oben = 18))
        val tasten = LinearLayout(this)
        listOf(R.drawable.ic_pn_music to KeyEvent.KEYCODE_MEDIA_PREVIOUS, R.drawable.ic_pn_music to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            R.drawable.ic_pn_music to KeyEvent.KEYCODE_MEDIA_NEXT).forEachIndexed { i, (res, code) ->
            tasten.addView(ui.act(res, getString(arrayOf(R.string.leiste_zurueck, R.string.leiste_musik, R.string.leiste_weiter)[i])) { medienTaste(code) },
                ui.lp(0, ui.dp(64), 1f, rechts = 6))
        }
        if ("musik" in t) c.addView(tasten, ui.lp(-1, -2, oben = 6))
        if ("kamera" in t) c.addView(ui.act(R.drawable.ic_pn_camera, getString(R.string.leiste_kamera)) { oeffnePanoptes("live") }, ui.lp(-1, ui.dp(64), oben = 18))
        // 0.1.41 (LC): „Navi aufs Kombi“ – nur mit Einstellung und solange der Kern nicht „geht nicht“ gesagt hat
        val kombi = ui.act(R.drawable.ic_pn_nav, getString(R.string.kombi_knopf)) { kombiTipp() }
        if (Kombi.knopfZeigen(kombiKnopfAn(), kombiAngeboten)) c.addView(kombi.also { kombiKnopf = it }, ui.lp(-1, ui.dp(64), oben = 10))
        c.addView(ui.act(R.drawable.ic_pn_status, getString(R.string.leiste_start)) { splitVerlassen() }, ui.lp(-1, ui.dp(64), oben = 10))
        // nicht reg{}: zeichneSeite() räumt KachelAktualisierer weg; die Spalte lebt bis zum nächsten baueAlles()
        aktualisierer += {
            val s = summary
            zeile.text = KartenStatus.zeile(if ("akku" in t) s?.socPct else null, if ("reichweite" in t) s?.rangeKm else null,
                if ("gang" in t) s?.gear else null)
            uhr.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            waechter.text = getString(if (s?.waechter?.an == true) R.string.leiste_waechter_an else R.string.leiste_waechter_aus)
            titel.text = medien?.titel ?: s?.media?.title ?: ""
            (kombi.getChildAt(1) as? TextView)?.text = getString(if (s?.tachoAktiv == true) R.string.kombi_normal else R.string.kombi_knopf)
        }
        return ScrollView(this).apply { addView(c) }
    }

    // ---------- 0.1.41 (LC): Navi aufs Kombi, Seitenblick beim Blinken (Regeln in KombiSeitenblick.kt) ----------
    /** Antwort des Kerns zum Kombi: null = unbekannt, false = dieses Auto kann es nicht (Knopf weg). */
    private var kombiAngeboten: Boolean? = null
    private var kombiKnopf: View? = null

    private fun kombiKnopfAn() = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(Kombi.KEY_KNOPF, false)

    private fun kombiZiel() = Kombi.ziel(karteImSplit,
        Fahrsicherheit.naviZiel(naviWahl(), summary?.naviGewaehlt, summary?.naviBekannt.orEmpty()) { startbar(it) })

    /** Einstellung: erst den Kern fragen (nur lesen); sagt er „nein“, bleibt der Knopf aus und der Grund wird genannt. */
    private fun kombiEinstellung() {
        val an = kombiKnopfAn()
        if (an) { kombiSetzen(false); return }
        val p = kombiZiel() ?: KartenStatus.GOOGLE_MAPS
        io.execute {
            val st = client.kombiStand(p)
            main.post {
                if (isFinishing) return@post
                kombiAngeboten = st?.angeboten
                if (st != null && !st.angeboten) { kombiDialog(R.string.kombi_geht_nicht, false); return@post }
                kombiSetzen(true)
                kombiDialog(R.string.kombi_info, false)
            }
        }
    }

    private fun kombiSetzen(an: Boolean) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(Kombi.KEY_KNOPF, an).apply()
        baueAlles(); einstellungen(erweitert = true)
    }

    private fun kombiDialog(text: Int, panoptesOeffnen: Boolean) {
        val d = AlertDialog.Builder(this).setTitle(R.string.kombi_einstellung).setMessage(text)
        if (panoptesOeffnen) d.setPositiveButton(R.string.oeffnen) { _, _ -> oeffnePanoptes("display") }.setNegativeButton(R.string.zurueck, null)
        else d.setPositiveButton(R.string.verstanden, null)
        d.show().getButton(AlertDialog.BUTTON_POSITIVE)?.apply { textSize = 20f; minHeight = ui.dp(64) }
    }

    /** Tipp auf den Knopf: läuft schon etwas am Kombi → zurück zum normalen Tacho, sonst die Navi aufs Kombi. */
    private fun kombiTipp() {
        if (summary?.tachoAktiv == true) {
            io.execute { val ok = client.tachoNormal(); main.post { zeigeHinweis(getString(if (ok) R.string.kombi_zurueck else R.string.launch_failed)) } }
            return
        }
        val p = kombiZiel() ?: run { zeigeHinweis(getString(R.string.navi_keine_app)); return }
        io.execute {
            val grund = client.kombiStarten(p)
            main.post { if (!isFinishing) kombiErgebnis(grund) }
        }
    }

    private fun kombiErgebnis(grund: String?) {
        if (grund == null) { zeigeHinweis(getString(R.string.kombi_laeuft)); return }
        android.util.Log.i("PanoptesLauncher", "KOMBI abgelehnt: $grund")
        when (Kombi.hinweis(grund)) {
            Kombi.Hinweis.GEHT_NICHT -> { kombiAngeboten = false; kombiKnopf?.visibility = View.GONE; kombiDialog(R.string.kombi_geht_nicht, false) }
            Kombi.Hinweis.FREIGEBEN -> kombiDialog(R.string.kombi_freigeben, true)
            Kombi.Hinweis.NUR_STAND -> zeigeHinweis(getString(R.string.kombi_nur_stand))
            Kombi.Hinweis.KEINE_NAVI -> zeigeHinweis(getString(R.string.navi_keine_app))
            Kombi.Hinweis.FEHLER -> zeigeHinweis(getString(R.string.launch_failed))
        }
    }

    /** Seitenblick: Stand vom Kern lesen, Regel erklären, dann ein- bzw. ausschalten (nur über den Kern). */
    private fun seitenblickFragen() {
        io.execute {
            val st = client.seitenblickStand()
            main.post {
                if (isFinishing) return@post
                if (st == null) { zeigeHinweis(getString(R.string.seitenblick_nicht_erreichbar)); return@post }   // G8 E2E 0.1.41
                val text = getString(R.string.seitenblick_regel) +
                    (if (st.blinker == "nicht_lesbar") "\n\n" + getString(R.string.seitenblick_blinker_fehlt) else "") +
                    "\n\n" + getString(R.string.seitenblick_ist) + " " + getString(if (st.an) R.string.an else R.string.aus)
                AlertDialog.Builder(this).setTitle(R.string.seitenblick_titel).setMessage(text)
                    .setPositiveButton(if (st.an) R.string.seitenblick_aus else R.string.seitenblick_an) { _, _ -> seitenblickSchalten(!st.an) }
                    .setNegativeButton(R.string.zurueck, null).show()
                    .getButton(AlertDialog.BUTTON_POSITIVE)?.apply { textSize = 20f; minHeight = ui.dp(64) }
            }
        }
    }

    private fun seitenblickSchalten(an: Boolean) {
        io.execute {
            val ok = client.seitenblickSchalten(an)
            main.post { zeigeHinweis(getString(if (!ok) R.string.seitenblick_fehler else if (an) R.string.seitenblick_ist_an else R.string.seitenblick_ist_aus)) }   // G8 E2E 0.1.41
        }
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
        // 0.1.41 (CP): CarPlay/AA ohne Start-Intent über die am Surf belegte Activity
        val i = packageManager.getLaunchIntentForPackage(p) ?: rueckfallIntent(p)
        if (i == null) { zeigeHinweis(getString(R.string.app_fehlt)); return }
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { zeigeHinweis(getString(R.string.launch_failed)) }
    }

    private fun appInfo(p: String) {
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$p")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: Exception) { zeigeHinweis(getString(R.string.launch_failed)) }
    }

    private fun zugriffErklaeren() {
        // M1: am BYD ist die Android-Seite gesperrt (Fehlermeldung des IVI) – ehrliche Erklärung ohne Knopf dorthin
        if (!MusikAnzeige.androidSeite(byd)) {
            AlertDialog.Builder(this).setTitle(R.string.medien_zugriff).setMessage(R.string.medien_zugriff_byd)
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        AlertDialog.Builder(this).setTitle(R.string.medien_zugriff).setMessage(R.string.medien_zugriff_text)
            .setPositiveButton(R.string.oeffnen) { _, _ -> zugriffSeite() }.setNegativeButton(R.string.abbrechen, null).show()
    }

    /** Android-Seite „Benachrichtigungszugriff“; erlauben muss der Nutzer dort selbst. Am BYD nie (gesperrt, M1). */
    private fun zugriffSeite() {
        if (!MusikAnzeige.androidSeite(byd)) { zugriffErklaeren(); return }
        try { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } catch (_: Exception) { zeigeHinweis(getString(R.string.einstellung_fehlt)) }
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
        const val REQ_BILD = 43
        const val KEY_SPAETER = "update_spaeter"
        const val KEY_NAVI = "navi_app"
        const val KEY_PROJEKTION_ERGAENZT = "projektion_ergaenzt"
        /** 0.1.41 (LA): Vorlagen-Frage beim ersten Einrichten schon gestellt. */
        const val KEY_VORLAGE_GEFRAGT = "vorlage_gefragt"
        /** E5: Startbildschirm-Angebot wurde gezeigt (nur einmal). */
        const val KEY_HOME_ANGEBOTEN = "startbildschirm_angeboten"
        const val ZUSTAND_KARTE_SPLIT = "karte_im_split"
        const val ZUSTAND_SPLIT_GEWOLLT = "split_gewollt_beendet"
        /** REGEL 50+: Klima-Knöpfe im Dock mindestens 64 dp, im Klima-Feld 96 dp. */
        const val DOCK_KNOPF = 64
        const val GROSS_KNOPF = 96
        const val UPDATE_TAKT_MS = 5 * 60_000L
    }
}
