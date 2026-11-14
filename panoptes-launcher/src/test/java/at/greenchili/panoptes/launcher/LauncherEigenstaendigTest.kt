package at.greenchili.panoptes.launcher

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.ServerSocket

/**
 * 0.1.43.4 Paket S „Launcher eigenständig“: läuft ohne Panoptes als vollwertiger Startbildschirm, Kern-Erreichbarkeit
 * als EIN gedrosselter Baustein (KernVerbindung), Platzhalter „Mit Panoptes: …“, Rückweg zu BYD ohne Kern, Update-Hinweis
 * aus dem öffentlichen GitHub-Release ohne Selbst-Installation.
 */
class LauncherEigenstaendigTest {

    private fun basis() = if (File("src/main").isDirectory) File(".") else File("panoptes-launcher")
    private fun quelle(n: String) = File(basis(), "src/main/java/at/greenchili/panoptes/launcher/$n").readText().replace("\r\n", "\n")
    private val main by lazy { quelle("MainActivity.kt") }

    @Before fun vorher() = KernVerbindung.zuruecksetzen()
    @After fun nachher() = KernVerbindung.zuruecksetzen()

    /** Ein Port, auf dem sicher niemand lauscht (Kern nicht erreichbar). */
    private fun toterPort(): Int = ServerSocket(0).use { it.localPort }

    // ---------- Pflicht: Kern nicht erreichbar ----------

    @Test
    fun kernNichtErreichbar() {
        val client = PanoptesClient("http://127.0.0.1:${toterPort()}")
        var netz = 0
        var t = 1_000_000L
        // so wie MainActivity.refresh(): fragen nur, wenn KernVerbindung es erlaubt
        fun abfrage() {
            if (!KernVerbindung.sollPruefen(t) { true }) return
            netz++
            val ok = runCatching { client.summary() }.isSuccess
            KernVerbindung.melde(ok, t)
        }
        repeat(KernVerbindung.SCHNELL_VERSUCHE) { abfrage(); t += 3_000 }
        assertEquals("erst im normalen Takt (Kern startet evtl. gerade)", KernVerbindung.SCHNELL_VERSUCHE, netz)
        assertFalse(KernVerbindung.verfuegbar())
        assertTrue("nie erreicht → „Mit Panoptes: …“ statt leerer Kacheln", KernVerbindung.ohnePanoptes())
        // dann gedrosselt: im Abfragetakt von 3 s keine Versuche, bis 30 s um sind
        repeat(9) { abfrage(); t += 3_000 }
        assertEquals(KernVerbindung.SCHNELL_VERSUCHE, netz)
        t += 3_000; abfrage()
        assertEquals(KernVerbindung.SCHNELL_VERSUCHE + 1, netz)
        // eine Stunde lang: höchstens alle 30 s, ab 10 Fehlversuchen alle 2 min (keine Dauerversuche)
        netz = 0
        repeat(1200) { abfrage(); t += 3_000 }
        assertTrue("Versuche in einer Stunde: $netz", netz in 20..40)
        // Kacheln, die Panoptes brauchen, werden Platzhalter; Musik/Uhr/Navi/Profil bleiben echte Kacheln
        for (k in listOf(Kachel.FAHRZEUG, Kachel.AKKU, Kachel.KAMERA, Kachel.SPRACHE, Kachel.HAUS, Kachel.WAECHTER)) assertTrue(k.name, KernVerbindung.platzhalter(k))
        for (k in listOf(Kachel.MUSIK, Kachel.UHR, Kachel.NAVI, Kachel.PROFIL, Kachel.PROJEKTION, Kachel.GERAETEWIDGET, Kachel.DOCKLEISTE, Kachel.REITERLEISTE))
            assertFalse(k.name, KernVerbindung.platzhalter(k))
    }

    @Test
    fun panoptesNichtInstalliertKeinNetz() {
        var pm = 0
        var t = 0L
        assertFalse(KernVerbindung.sollPruefen(t) { pm++; false })
        assertEquals(KernVerbindung.Stand.FEHLT, KernVerbindung.stand)
        assertTrue(KernVerbindung.ohnePanoptes())
        // 10 min lang nicht einmal der PackageManager
        while (t < KernVerbindung.FEHLT_MS - 1) { assertFalse(KernVerbindung.sollPruefen(t) { pm++; false }); t += 3_000 }
        assertEquals(1, pm)
        // installiert → sofort fragen
        t = KernVerbindung.FEHLT_MS
        assertTrue(KernVerbindung.sollPruefen(t) { pm++; true })
        assertEquals(KernVerbindung.Stand.UNBEKANNT, KernVerbindung.stand)
    }

    @Test
    fun mitKernUndFreischaltenVonSelbst() {
        // kleiner Kern-Ersatz: beantwortet jede Anfrage mit {} (Summary ohne Werte)
        val server = ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1"))
        Thread {
            runCatching {
                while (!server.isClosed) server.accept().use { so ->
                    val ein = so.getInputStream().bufferedReader()
                    while (ein.readLine().orEmpty().isNotEmpty()) Unit
                    so.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray())
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            var frei = 0
            KernVerbindung.beimFreischalten { frei++ }
            // erst ohne Kern: Platzhalter
            repeat(KernVerbindung.SCHNELL_VERSUCHE) { KernVerbindung.melde(false, it * 3_000L) }
            assertTrue(KernVerbindung.ohnePanoptes())
            // Kern kommt dazu
            val t = 60_000L
            assertTrue(KernVerbindung.sollPruefen(t) { true })
            val ok = runCatching { PanoptesClient("http://127.0.0.1:${server.localPort}").summary() }.isSuccess
            assertTrue(ok)
            KernVerbindung.melde(ok, t)
            assertTrue(KernVerbindung.verfuegbar())
            assertFalse(KernVerbindung.ohnePanoptes())
            assertEquals("Freischalten von selbst", 1, frei)
            assertFalse(KernVerbindung.platzhalter(Kachel.FAHRZEUG))
            // mit Kern: jede Abfrage darf fragen
            assertTrue(KernVerbindung.sollPruefen(t + 3_000) { error("kein PackageManager nötig") })
            // kurz weg nach erstem Kontakt: alte Werte (Frische) statt Platzhalter
            repeat(5) { KernVerbindung.melde(false, t + it) }
            assertFalse(KernVerbindung.ohnePanoptes())
        } finally {
            server.close()
        }
    }

    @Test
    fun abstandRegeln() {
        assertEquals(0L, KernVerbindung.abstandMs(KernVerbindung.Stand.DA, 0))
        assertEquals(0L, KernVerbindung.abstandMs(KernVerbindung.Stand.WEG, 1))
        assertEquals(KernVerbindung.MITTEL_MS, KernVerbindung.abstandMs(KernVerbindung.Stand.WEG, KernVerbindung.SCHNELL_VERSUCHE))
        assertEquals(KernVerbindung.LANGSAM_MS, KernVerbindung.abstandMs(KernVerbindung.Stand.WEG, 50))
        assertEquals(KernVerbindung.FEHLT_MS, KernVerbindung.abstandMs(KernVerbindung.Stand.FEHLT, 0))
        assertFalse(KernVerbindung.ohnePanoptes(KernVerbindung.Stand.UNBEKANNT, 0, false))
        assertFalse("beim Start nicht gleich Platzhalter", KernVerbindung.ohnePanoptes(KernVerbindung.Stand.WEG, 1, false))
        // Rückkehr in den Launcher: höchstens alle 15 s anstoßen
        repeat(KernVerbindung.LANGSAM_AB) { KernVerbindung.melde(false, 0L) }
        KernVerbindung.anstossen(1_000L)
        assertFalse(KernVerbindung.sollPruefen(1_000L) { true })
        assertTrue(KernVerbindung.sollPruefen(KernVerbindung.ANSTOSS_MS) { true })
    }

    // ---------- Stand ohne Kern ----------

    @Test
    fun mitKernZaehltNurDerKern() {
        val j = 1_000_000L
        val steht = StandOhneKern.Gps(0.0, j, true)
        assertTrue(StandOhneKern.erlaubt(true, false, false, null, 0L, j))
        assertFalse("Kern sagt Fahrt: GPS und Bestätigung helfen nicht", StandOhneKern.erlaubt(false, false, true, steht, j + 60_000, j))
        // Video unverändert: nur P
        assertFalse(Fahrsicherheit.appStartErlaubt(true, null))
    }

    @Test
    fun gpsUnterDreiKmhFrei() {
        val j = 1_000_000L
        for (kmh in listOf(0.0, 1.0, 2.9)) {
            val g = StandOhneKern.Gps(kmh, j - 5_000, true)
            assertEquals(StandOhneKern.Urteil.FREI, StandOhneKern.urteil(true, g, j))
            assertTrue("$kmh", StandOhneKern.erlaubt(false, true, true, g, 0L, j))
        }
    }

    @Test
    fun gpsAbDreiKmhGesperrtUndBestaetigungVerfaellt() {
        val j = 1_000_000L
        val faehrt = StandOhneKern.Gps(3.0, j - 1_000, true)
        assertEquals(StandOhneKern.Urteil.GESPERRT, StandOhneKern.urteil(true, faehrt, j))
        assertFalse("auch mit Bestätigung", StandOhneKern.erlaubt(false, true, true, faehrt, j + 60_000, j))
        assertTrue("„Ich stehe“ verfällt sofort", StandOhneKern.bestaetigungVerfaellt(faehrt, j))
        assertFalse(StandOhneKern.bestaetigungVerfaellt(StandOhneKern.Gps(2.0, j, true), j))
        assertFalse("unsicherer Wert lässt nichts verfallen", StandOhneKern.bestaetigungVerfaellt(StandOhneKern.Gps(50.0, j, false), j))
        val m = main
        assertTrue(m.contains("if (StandOhneKern.bestaetigungVerfaellt(g, System.currentTimeMillis())) standBestaetigtBis = 0L"))
    }

    @Test
    fun gpsVeraltetOderUnsicherGesperrt() {
        val j = 1_000_000L
        val alt = StandOhneKern.Gps(0.0, j - StandOhneKern.FRISCH_MS - 1, true)
        assertEquals(StandOhneKern.Urteil.GESPERRT, StandOhneKern.urteil(true, alt, j))
        assertFalse("abgelaufen: auch die Bestätigung hilft nicht", StandOhneKern.erlaubt(false, true, true, alt, j + 60_000, j))
        assertEquals(StandOhneKern.Urteil.GESPERRT, StandOhneKern.urteil(true, StandOhneKern.Gps(0.0, j, false), j))
        assertEquals("ohne Tempo", StandOhneKern.Urteil.GESPERRT, StandOhneKern.urteil(true, StandOhneKern.Gps(null, j, true), j))
        assertEquals(StandOhneKern.Urteil.FREI, StandOhneKern.urteil(true, StandOhneKern.Gps(0.0, j - StandOhneKern.FRISCH_MS, true), j))
        // lange gar kein Wert (Garage, Tunnel): wie „kein GPS“ → fragen statt für immer gesperrt
        assertEquals(StandOhneKern.Urteil.FRAGEN, StandOhneKern.urteil(true, StandOhneKern.Gps(0.0, j - StandOhneKern.KEIN_GPS_MS - 1, true), j))
    }

    @Test
    fun keinRechtOderKeinGpsFragen() {
        val j = 1_000_000L
        assertEquals(StandOhneKern.Urteil.FRAGEN, StandOhneKern.urteil(false, StandOhneKern.Gps(0.0, j, true), j))
        assertEquals(StandOhneKern.Urteil.FRAGEN, StandOhneKern.urteil(true, null, j))
        assertFalse("ohne Bestätigung gesperrt", StandOhneKern.erlaubt(false, true, false, null, 0L, j))
        assertTrue(StandOhneKern.erlaubt(false, true, false, null, j + StandOhneKern.BESTAETIGT_MS, j))
        assertFalse("nach 10 min abgelaufen", StandOhneKern.erlaubt(false, true, false, null, j, j))
        assertFalse("nie länger als 10 min", StandOhneKern.erlaubt(false, true, false, null, j + StandOhneKern.BESTAETIGT_MS + 1, j))
        // Standortrecht nur auf Tipp mit Erklärung, nie beim Start
        assertTrue(main.contains("if (!StandOhneKern.recht(this)) b.setNeutralButton(R.string.stand_gps_knopf) { _, _ -> standortErklaeren() }"))
        assertEquals(1, main.split("requestPermissions(").size - 1)
        assertTrue(main.substringAfter("private fun standortErklaeren()").substringBefore("override fun onRequestPermissionsResult").contains("requestPermissions("))
        assertFalse(main.substringAfter("override fun onCreate(").substringBefore("override fun onStart()").contains("requestPermissions"))
        // GPS nur ohne Panoptes und nur vorn
        assertTrue(main.contains("private fun gpsAnwenden() { if (!stopped && KernVerbindung.ohnePanoptes()) gps.start() else gps.stop() }"))
        assertTrue(main.contains("gps.stop()   // 0.1.43.4 (Paket S): GPS nur, solange der Launcher vorn ist"))
        assertTrue(main.contains("if (!bearbeiten || standOk()) return"))
        val m = File(basis(), "src/main/AndroidManifest.xml").readText()
        assertTrue(m.contains("android.permission.ACCESS_FINE_LOCATION"))
        assertFalse(m.contains("ACCESS_BACKGROUND_LOCATION"))
    }

    // ---------- Sicherheitsnetz: Startbildschirm ohne Panoptes ----------

    @Test
    fun startbildschirmOhnePanoptesUndRueckweg() {
        val e = quelle("Einrichtung.kt")
        assertTrue(e.contains("if (!KernVerbindung.verfuegbar()) { lokalEinschalten(); return }"))
        val lokal = e.substringAfter("private fun lokalEinschalten()").substringBefore("private fun hinweisDann(")
        assertTrue(lokal.contains("HomeSchalter.setze(this, true)"))
        assertTrue("Wahl trifft der Nutzer auf der Android-Seite", lokal.contains("HomeSchalter.androidWahlOeffnen(this)"))
        // Alles zurück: zuerst lokal (Alias aus), dann Kern nur, wenn Panoptes da ist
        val zurueck = e.substringAfter("private fun allesZurueck()").substringBefore("@Deprecated")
        assertTrue(zurueck.indexOf("HomeSchalter.setze(this, false)") in 0 until zurueck.indexOf("io.execute"))
        assertTrue(zurueck.contains("HomeSchalter.lokalMerken(this, false)"))
        assertTrue(zurueck.contains("val kern = ohne || client.homeAus()"))
        assertTrue(quelle("HomeSchalter.kt").contains("Settings.ACTION_HOME_SETTINGS"))
        // Panoptes kommt später dazu: lokale Wahl wird übernommen, nicht abgeschaltet
        val aus = HomeWunsch(gewuenscht = false, behalten = false, frage = false)
        assertTrue(HomeAbgleich.lokalUebernehmen(aus, aliasAn = true, lokal = true))
        assertFalse(HomeAbgleich.lokalUebernehmen(aus, aliasAn = true, lokal = false))
        assertFalse(HomeAbgleich.lokalUebernehmen(null, aliasAn = true, lokal = true))
        assertFalse(HomeAbgleich.lokalUebernehmen(HomeWunsch(true, true, false), aliasAn = true, lokal = true))
        val ab = main.substringAfter("private fun homeAbgleichen(").substringBefore("HomeAbgleich.aliasSoll(")
        assertTrue("Übernahme vor dem Alias-Abgleich", ab.contains("HomeAbgleich.lokalUebernehmen("))
    }

    // ---------- Updates ohne Panoptes ----------

    private val sha = "a".repeat(64)
    private fun vj(code: Int, apk: String = "panoptes-launcher-0.1.44-abc1234.apk", tag: String = "v0.1.44", s: String = sha) =
        """{"versionCode":200,"versionName":"0.1.44","tag":"$tag","apk":"panoptes-0.1.44.apk","sha256":"${"b".repeat(64)}",
            "launcher":{"versionCode":$code,"versionName":"0.1.44","apk":"$apk","sha256":"$s"}}"""

    @Test
    fun updateAusOeffentlichemRelease() {
        val f = EigenUpdate.parse(vj(21), 20)
        assertNotNull(f)
        assertEquals("https://github.com/samuelvoltarius/panoptes/releases/download/v0.1.44/panoptes-launcher-0.1.44-abc1234.apk", f!!.adresse)
        assertNull("nicht neuer", EigenUpdate.parse(vj(20), 20))
        assertNull(EigenUpdate.parse(vj(21, apk = "../boese.apk"), 20))
        assertNull(EigenUpdate.parse(vj(21, apk = "x.exe"), 20))
        assertNull(EigenUpdate.parse(vj(21, tag = "main"), 20))
        assertNull(EigenUpdate.parse(vj(21, s = "123"), 20))
        assertNull(EigenUpdate.parse("""{"versionCode":200,"tag":"v1"}""", 20))
        assertNull(EigenUpdate.parse("kein json", 20))
        assertTrue(EigenUpdate.VERSION_JSON.startsWith("https://github.com/samuelvoltarius/panoptes/releases/latest/download/"))
        // Takt: mit Kern nie, sonst höchstens einmal am Tag
        val t = 10L * EigenUpdate.TAKT_MS
        assertFalse(EigenUpdate.faellig(true, 0L, false, t))
        assertTrue(EigenUpdate.faellig(false, 0L, false, t))
        assertFalse(EigenUpdate.faellig(false, t - 60_000, false, t))
        assertTrue(EigenUpdate.faellig(false, t - EigenUpdate.TAKT_MS, false, t))
        assertTrue(EigenUpdate.faellig(false, t - EigenUpdate.FEHL_TAKT_MS, true, t))
        // installiert nie selbst: nur Android-Installer per Intent, kein PackageInstaller, keine Shell
        val q = quelle("EigenUpdate.kt")
        assertTrue(q.contains("application/vnd.android.package-archive"))
        assertFalse(q.contains("PackageInstaller")); assertFalse(q.contains("Runtime.getRuntime")); assertFalse(q.contains("pm install"))
        assertTrue("sha256 vor dem Installer", q.contains("if (hex(md.digest()) != f.sha256)"))
        val m = File(basis(), "src/main/AndroidManifest.xml").readText()
        assertTrue(m.contains("android.permission.REQUEST_INSTALL_PACKAGES"))
        assertFalse(m.contains("android.permission.INSTALL_PACKAGES\""))
        assertTrue(m.contains("androidx.core.content.FileProvider") && m.contains("@xml/update_pfade"))
        assertTrue(File(basis(), "src/main/res/xml/update_pfade.xml").readText().contains("path=\"update/\""))
        // ohne Kern-Stand zeigt die Update-Karte den eigenen Fund
        assertTrue(main.contains("val u = updateStand ?: return eigenUpdateKarte()"))
    }

    // ---------- Einbau in MainActivity ----------

    @Test
    fun einbau() {
        val r = main.substringAfter("private fun refresh()").substringBefore("private var homeFrageOffen")
        assertTrue("gedrosselt", r.contains("KernVerbindung.sollPruefen(jetzt0) { KernVerbindung.installiert(this) }"))
        assertTrue(r.indexOf("KernVerbindung.sollPruefen") < r.indexOf("client.summary()"))
        assertTrue(r.contains("KernVerbindung.melde(true"))
        assertTrue(r.contains("KernVerbindung.melde(false"))
        val w = main.substringAfter("private fun widget(e: Element): View {").substringBefore("val v = when (k)")
        assertTrue(w.contains("if (KernVerbindung.platzhalter(k)) return mitPanoptesKachel(e, k, gross)"))
        val p = main.substringAfter("private fun mitPanoptesKachel(").substringBefore("private fun spalte(")
        assertTrue(p.contains("KernVerbindung.panoptesOeffnen(this@MainActivity)"))
        assertTrue("bleibt bearbeitbar", p.contains("langerDruck(e)"))
        assertTrue("Musik-Titel über den Kern nur, wenn er da ist", main.contains("KernMusik { f -> if (KernVerbindung.verfuegbar()) client.musik(f) else null }"))
        assertTrue("keine leere Klima-Leiste", main.contains("if (layout.zeigeKlima && !KernVerbindung.ohnePanoptes())"))
        assertTrue("Freischalten baut neu", main.contains("if (ohne != ohneGezeigt && overlay == null && !bearbeiten)"))
        assertTrue(main.contains("KernVerbindung.anstossen(System.currentTimeMillis())"))
        // Medientasten gehen ohne Sonderrechte
        assertTrue(main.contains("am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))"))
    }

    @Test
    fun texteInFuenfSprachen() {
        val res = File(basis(), "src/main/res")
        val k = listOf("mit_panoptes", "mit_panoptes_link", "mit_panoptes_seite", "stand_frage_titel", "stand_frage_text", "stand_frage_ja",
            "stand_frage_ok", "eigen_update_text", "eigen_update_laedt", "eigen_update_laden", "eigen_update_erlaubnis",
            "eigen_update_fehler", "einr_lokal_text", "einr_lokal_keine_seite", "stand_gps_knopf", "stand_gps_erklaerung", "stand_gps_erlauben")
        for (d in listOf("values", "values-de", "values-fr", "values-it", "values-nl")) {
            val x = File(res, "$d/strings.xml").readText()
            for (n in k) assertTrue("$d $n", x.contains("name=\"$n\""))
            assertTrue("$d mit_panoptes mit Platzhalter", Regex("name=\"mit_panoptes\">[^<]*%1\\\$s<").containsMatchIn(x))
        }
        val fr = File(res, "values-fr/strings.xml").readText()
        assertTrue("FR in vous-Form", fr.contains("Êtes-vous à l\\'arrêt"))
    }
}
