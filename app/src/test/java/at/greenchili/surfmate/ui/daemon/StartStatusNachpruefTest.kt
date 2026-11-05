package at.greenchili.surfmate.ui.daemon

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 0.1.38.11 (Startkarte, Befunde E2E/Code-Analyse): „✓ Oberfläche“ nur aus der echten Prüfung, nicht aus dem Abruf der
 * Karte; ein Dienst, den ein anderer Weg gestartet hat oder dessen Ablauf abgebrochen wurde, wird beim Abruf
 * nachgeprüft und bekommt ✓ statt nach 300 s „startet nicht“; „startet nicht“ steht einmal im Start-Protokoll.
 */
class StartStatusNachpruefTest {

    private val log = ArrayList<String>()
    private lateinit var altesProtokoll: (String, String) -> Unit

    @Before
    fun an() {
        altesProtokoll = StartStatus.protokoll
        StartStatus.protokoll = { e, w -> log.add("$e $w") }
    }

    @After
    fun weg() {
        StartStatus.zuruecksetzen()
        StartStatus.protokoll = altesProtokoll
    }

    private fun z(s: StartStufe, jetzt: Long) = StartStatus.stand(jetzt).first { it.first == s }.second

    @Test
    fun fremdGestartetWirdBeimAbrufBereitStattNach300sStartetNicht() {
        StartStatus.beginne(listOf(StartStufe.OBERFLAECHE, StartStufe.WAECHTER), 0)
        StartStatus.nachprueferSetzen(this) { s, rueck -> rueck(s == StartStufe.WAECHTER) }
        StartStatus.nachpruefen(1_000)
        assertEquals(StufeZustand.BEREIT, z(StartStufe.WAECHTER, 1_000))
        assertEquals("läuft nicht → bleibt startet", StufeZustand.STARTET, z(StartStufe.OBERFLAECHE, 1_000))
        assertEquals(StufeZustand.BEREIT, z(StartStufe.WAECHTER, StartStatus.ZEITLIMIT_MS + 1))
        assertEquals(StufeZustand.STARTET_NICHT, z(StartStufe.OBERFLAECHE, StartStatus.ZEITLIMIT_MS + 1))
    }

    @Test
    fun startetNichtWirdBereitSobaldDerDienstLaeuft() {
        StartStatus.beginne(listOf(StartStufe.WAECHTER), 0)
        StartStatus.melde(StartStufe.WAECHTER, StufeZustand.STARTET_NICHT, 20_000)   // Zeitlimit des Ablaufs
        var laeuft = false
        StartStatus.nachprueferSetzen(this) { _, rueck -> rueck(laeuft) }
        StartStatus.nachpruefen(30_000)
        assertEquals(StufeZustand.STARTET_NICHT, z(StartStufe.WAECHTER, 30_000))
        laeuft = true   // z. B. der 30-s-Health-Check hat ihn gestartet
        StartStatus.nachpruefen(30_000 + StartStatus.NACHPRUEF_MS)
        assertEquals(StufeZustand.BEREIT, z(StartStufe.WAECHTER, 40_000))
        assertTrue(StartStatus.json(40_000).getBoolean("fertig"))
    }

    @Test
    fun hoechstensAlleFuenfSekundenUndNieFuerBereite() {
        StartStatus.beginne(listOf(StartStufe.OBERFLAECHE, StartStufe.WAECHTER), 0)
        StartStatus.melde(StartStufe.OBERFLAECHE, StufeZustand.BEREIT, 10)
        val gefragt = ArrayList<StartStufe>()
        StartStatus.nachprueferSetzen(this) { s, rueck -> gefragt.add(s); rueck(false) }
        StartStatus.nachpruefen(1_000)
        StartStatus.nachpruefen(3_000)   // Karte fragt alle 2 s
        assertEquals(listOf(StartStufe.WAECHTER), gefragt)
        StartStatus.nachpruefen(1_000 + StartStatus.NACHPRUEF_MS)
        assertEquals(2, gefragt.size)
    }

    @Test
    fun cleanupNimmtNurDenEigenenNachprueferHeraus() {
        StartStatus.beginne(listOf(StartStufe.WAECHTER), 0)
        val boot = Any()
        val main = Any()
        var vonMain = 0
        StartStatus.nachprueferSetzen(boot) { _, rueck -> rueck(false) }
        StartStatus.nachprueferSetzen(main) { _, rueck -> vonMain++; rueck(false) }
        StartStatus.nachprueferEntfernen(boot)   // Boot-Manager räumt nach der Übergabe auf
        StartStatus.nachpruefen(1_000)
        assertEquals("der neue Manager prüft weiter", 1, vonMain)
        StartStatus.nachprueferEntfernen(main)
        StartStatus.nachpruefen(1_000 + StartStatus.NACHPRUEF_MS)
        assertEquals(1, vonMain)
    }

    @Test
    fun startetNichtStehtEinmalImProtokoll() {
        StartStatus.beginne(listOf(StartStufe.OBERFLAECHE, StartStufe.WAECHTER), 0)
        StartStatus.melde(StartStufe.WAECHTER, StufeZustand.STARTET_NICHT, 20_000)
        StartStatus.melde(StartStufe.WAECHTER, StufeZustand.STARTET_NICHT, 21_000)
        StartStatus.stand(StartStatus.ZEITLIMIT_MS + 1)
        StartStatus.stand(StartStatus.ZEITLIMIT_MS + 2)
        StartStatus.json(StartStatus.ZEITLIMIT_MS + 3)
        assertEquals(listOf("STUFE_STARTET_NICHT stufe=waechter grund=zeitlimit_ablauf",
            "STUFE_STARTET_NICHT stufe=oberflaeche grund=keine_meldung_300s"), log)
        // LauncherEreignisse.sauber ersetzt „start_“ (Kill-Muster) – der Ereignisname darf es nicht enthalten
        assertFalse(log.any { it.contains("start_", ignoreCase = true) })
        StartStatus.beginne(listOf(StartStufe.WAECHTER), 400_000)
        StartStatus.melde(StartStufe.WAECHTER, StufeZustand.STARTET_NICHT, 420_000)
        assertEquals("neuer Start, neue Zeile", 3, log.size)
    }

    private fun q(rel: String): String {
        var f = File(rel); if (!f.exists()) f = File("app/$rel")
        return f.readText().replace("\r\n", "\n")
    }

    @Test
    fun abrufSetztOberflaecheNichtMehrSelbstAufBereit() {
        val wv = q("src/main/java/at/greenchili/surfmate/ui/fragment/WebViewFragment.kt")
        val a = wv.indexOf("private fun interceptStartStatus(url: String)")
        val f = wv.substring(a, wv.indexOf("private fun startDiensteZeigen()", a))
        assertFalse(f.contains("StufeZustand.BEREIT"))
        assertTrue(f.contains("StartStatus.nachpruefen(jetzt)"))
        val m = q("src/main/java/at/greenchili/surfmate/ui/daemon/DaemonStartupManager.kt")
        assertTrue("eine Quelle: dieselben Prüfungen wie der Ablauf",
            m.contains("StartStatus.nachprueferSetzen(this) { s, rueck -> stufeBereit(s, rueck) }"))
        assertTrue(m.contains("StartStatus.nachprueferEntfernen(this)"))
        assertTrue("REST nur mit Prüfung (Telegram)",
            m.contains("stufen.filter { it != StartStufe.REST || PreferencesManager.isDaemonEnabled(DaemonType.TELEGRAM_DAEMON) }"))
    }

    @Test
    fun zeileHeisstWieDasGepruefteUndKarteLiestNach() {
        val js = q("src/main/assets/web/shared/start-karte.js")
        assertTrue(js.contains("rest: 'Telegram',"))
        assertFalse(js.contains("Brücke"))
        assertTrue("„startet nicht“: langsam weiter nachlesen", js.contains("timer = setTimeout(lesen, d.fertig ? NACH_MS : TAKT_MS);"))
        for (l in listOf("de", "en", "fr", "it", "nl")) {
            assertEquals(l, "Telegram", JSONObject(q("src/main/assets/web/i18n/$l.json")).getJSONObject("home").getString("start_rest"))
        }
        for (d in listOf("values", "values-de", "values-fr", "values-it", "values-nl")) {
            assertTrue(d, q("src/main/res/$d/strings.xml").contains("<string name=\"start_dienst_rest\">Telegram</string>"))
        }
    }
}
