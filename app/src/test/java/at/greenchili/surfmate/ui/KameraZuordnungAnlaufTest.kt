package at.greenchili.surfmate.ui

import at.greenchili.surfmate.ui.KameraZuordnungAnlauf.Abruf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.1.43.18 (F4a/F5): „Kamera-Zuordnung“ fragt in der Anlaufzeit nach, erst dann der Hinweis.
 * 0.1.43.18b (E2E_014318 A2): letzter Abruf läuft zu Ende, einmal +10 s, wenn der Kern schon antwortet, hart ≤ 35 s.
 */
class KameraZuordnungAnlaufTest {

    /** Ergebnis einer nachgespielten Warteschleife wie in `MainActivity.onReconfigureCameraClicked`. */
    private data class Lauf(val hinweis: Boolean, val endeMs: Long, val versuche: Int, val verlaengert: Boolean)

    /**
     * Spielt die Schleife mit falscher Uhr nach. [kern] liefert zu einem Abruf-Beginn (relativ zum Tipp) und dessen
     * Zeitgrenzen den Ausgang und die Dauer. Abrufe laufen streng nacheinander (Beginn ≥ Ende des vorigen).
     */
    private fun spiele(mitCoach: Boolean = false, kern: (beginn: Long, verbinden: Int, lesen: Int) -> Pair<Abruf, Long>): Lauf {
        val start = 1_000_000L
        val f = KameraZuordnungAnlauf.Fenster(start, mitCoach)
        var jetzt = start
        var versuche = 0
        var letztesEnde = start
        while (true) {
            assertTrue("nie parallel", jetzt >= letztesEnde)
            val beginn = jetzt
            val (v, l) = f.zeitgrenzen(beginn)
            assertTrue("Verbinden nie 0 (= unendlich)", v >= KameraZuordnungAnlauf.MIN_VERBINDEN_MS)
            val (ausgang, dauer) = kern(beginn - start, v, l)
            assertTrue("Abruf hält seine Zeitgrenzen", dauer <= v + l)
            jetzt += dauer
            letztesEnde = jetzt
            versuche++
            assertTrue("harte Grenze 35 s", jetzt - start <= KameraZuordnungAnlauf.GRENZE_MS)
            if (ausgang == Abruf.OK) return Lauf(false, jetzt - start, versuche, f.verlaengert)
            val warte = f.nachAbruf(ausgang, beginn, jetzt)
            if (warte == KameraZuordnungAnlauf.SCHLUSS) return Lauf(true, jetzt - start, versuche, f.verlaengert)
            assertTrue(warte >= 0)
            jetzt += warte
        }
    }

    /**
     * Kern ab [erreichbarMs] per HTTP erreichbar, initialisiert dann noch 4 s: so lange läuft jeder Abruf in die
     * Lesegrenze (wie A2, 08:49:54 erreichbar, `initSurveillance` 08:49:59). Vorher sofort „Verbindung abgelehnt“.
     */
    private fun kernAb(erreichbarMs: Long): (Long, Int, Int) -> Pair<Abruf, Long> = { beginn, _, lesen ->
        when {
            beginn < erreichbarMs -> Pair(Abruf.NICHT_DA, 5L)
            beginn < erreichbarMs + 4_000L -> Pair(Abruf.KERN_ANTWORTET, lesen.toLong())
            else -> Pair(Abruf.OK, 80L)
        }
    }

    @Test
    fun kernBei16Bis24SekundenMitLesezeitEndeKeinHinweis() {
        for (r in listOf(16_000L, 18_000L, 20_000L, 22_000L, 24_000L, 25_000L)) {
            val lauf = spiele(kern = kernAb(r))
            assertFalse("Kern ab $r ms: kein Hinweis (${lauf})", lauf.hinweis)
            assertTrue("Kern ab $r ms: verlängert", lauf.verlaengert)
        }
        // A2 genau: erreichbar nach 18 s → Dialog nach ~24 s statt Hinweis
        val a2 = spiele(kern = kernAb(18_000L))
        assertFalse(a2.hinweis)
        assertTrue(a2.endeMs in 22_000L..26_000L)
    }

    @Test
    fun kernFrueherDaOhneVerlaengerung() {
        val lauf = spiele(kern = kernAb(2_000L).let { k -> { b, v, l -> if (b >= 2_000L) Pair(Abruf.OK, 80L) else k(b, v, l) } })
        assertFalse(lauf.hinweis)
        assertFalse(lauf.verlaengert)
    }

    @Test
    fun kernNieDaHinweisNachFensterendeUndSpaetestens35s() {
        // sofort abgelehnt (Loopback ohne Kern)
        val schnell = spiele { _, _, _ -> Pair(Abruf.NICHT_DA, 5L) }
        assertTrue(schnell.hinweis)
        assertFalse("ohne Kern-Antwort keine Verlängerung", schnell.verlaengert)
        assertTrue("erst nach Fensterende: ${schnell.endeMs}", schnell.endeMs >= KameraZuordnungAnlauf.ANLAUF_MS)
        assertTrue(schnell.endeMs <= KameraZuordnungAnlauf.GRENZE_MS)
        // Verbindungsaufbau läuft jedes Mal in die Grenze
        val langsam = spiele { _, v, _ -> Pair(Abruf.NICHT_DA, v.toLong()) }
        assertTrue(langsam.hinweis)
        assertTrue(langsam.endeMs <= KameraZuordnungAnlauf.GRENZE_MS)
    }

    @Test
    fun kernHaengtImmerHinweisSpaetestens35s() {
        val lauf = spiele { _, _, l -> Pair(Abruf.KERN_ANTWORTET, l.toLong()) }
        assertTrue(lauf.hinweis)
        assertTrue("einmal verlängert", lauf.verlaengert)
        assertTrue(lauf.endeMs > KameraZuordnungAnlauf.ANLAUF_MS)
        assertTrue(lauf.endeMs <= KameraZuordnungAnlauf.GRENZE_MS)
        // auch der ungünstigste Fall: Verbinden UND Lesen laufen jedes Mal voll aus
        val voll = spiele { _, v, l -> Pair(Abruf.KERN_ANTWORTET, (v + l).toLong()) }
        assertTrue(voll.hinweis)
        assertTrue(voll.endeMs <= KameraZuordnungAnlauf.GRENZE_MS)
    }

    @Test
    fun verlaengerungNurEinmalUndGedeckelt() {
        val start = 0L
        val f = KameraZuordnungAnlauf.Fenster(start, false)
        f.nachAbruf(Abruf.KERN_ANTWORTET, 1_000L, 5_000L)
        assertEquals(KameraZuordnungAnlauf.ANLAUF_MS + KameraZuordnungAnlauf.VERLAENGERUNG_MS, f.endeMs)
        f.nachAbruf(Abruf.KERN_ANTWORTET, 6_000L, 10_000L)
        assertEquals("nur einmal", KameraZuordnungAnlauf.ANLAUF_MS + KameraZuordnungAnlauf.VERLAENGERUNG_MS, f.endeMs)
        assertTrue(f.spaetesterBeginnMs() + KameraZuordnungAnlauf.MIN_ABRUF_MS <= KameraZuordnungAnlauf.GRENZE_MS)
        assertEquals(35_000L, KameraZuordnungAnlauf.GRENZE_MS)
        assertEquals(10_000L, KameraZuordnungAnlauf.VERLAENGERUNG_MS)
    }

    @Test
    fun echterFehlerSofort() {
        val lauf = spiele { _, _, _ -> Pair(Abruf.FEHLER, 30L) }
        assertTrue(lauf.hinweis)
        assertEquals(1, lauf.versuche)
        assertEquals(30L, lauf.endeMs)
        assertEquals(Abruf.FEHLER, KameraZuordnungAnlauf.ausgangFuerStatus(404))
        assertEquals(Abruf.FEHLER, KameraZuordnungAnlauf.ausgangFuerStatus(400))
        assertEquals(Abruf.KERN_ANTWORTET, KameraZuordnungAnlauf.ausgangFuerStatus(503))
        assertEquals(Abruf.KERN_ANTWORTET, KameraZuordnungAnlauf.ausgangFuerStatus(500))
        assertEquals(Abruf.KERN_ANTWORTET, KameraZuordnungAnlauf.ausgangFuerStatus(401))
        assertEquals(Abruf.OK, KameraZuordnungAnlauf.ausgangFuerStatus(200))
    }

    @Test
    fun einrichtungsCoachWartetSelbst() {
        val lauf = spiele(mitCoach = true) { _, _, _ -> Pair(Abruf.NICHT_DA, 5L) }
        assertTrue(lauf.hinweis)
        assertEquals(1, lauf.versuche)
    }

    @Test
    fun uhrRueckwaertsKeinEndlosWarten() {
        val f = KameraZuordnungAnlauf.Fenster(10_000L, false)
        assertEquals(KameraZuordnungAnlauf.SCHLUSS, f.nachAbruf(Abruf.NICHT_DA, 10_000L, 5_000L))
    }

    @Test
    fun zeitgrenzenNormalUndNaheDerGrenze() {
        val f = KameraZuordnungAnlauf.Fenster(0L, false)
        assertEquals(Pair(3_000, 4_000), f.zeitgrenzen(0L))
        assertEquals(Pair(3_000, 4_000), f.zeitgrenzen(28_000L))
        assertEquals(Pair(1_000, 4_000), f.zeitgrenzen(30_000L))
    }

    @Test
    fun diagnoseOhneOrt() {
        assertEquals("quelle=zuordnung gewartet_ms=24500 versuche=12", KameraZuordnungAnlauf.diagnose(24_500L, 12))
        assertEquals("quelle=zuordnung gewartet_ms=31000 versuche=14 letzter=kern_antwortet verlaengert=1",
            KameraZuordnungAnlauf.diagnose(31_000L, 14, Abruf.KERN_ANTWORTET, true))
    }

    @Test
    fun hinweisErstNachDerAnlaufzeitUndMitProtokoll() {
        val f = File("src/main/java/at/greenchili/surfmate/ui/MainActivity.kt").takeIf { it.exists() }
            ?: File("app/src/main/java/at/greenchili/surfmate/ui/MainActivity.kt")
        val s = f.readText()
        val a = s.indexOf("private fun onReconfigureCameraClicked()")
        val b = s.indexOf("showCameraMappingDialog(state)", a)
        assertTrue(a > 0 && b > a)
        val block = s.substring(a, b)
        assertTrue(block.contains("KameraZuordnungAnlauf.Fenster(start, mitCoach)"))
        assertTrue(block.contains("fenster.nachAbruf(ausgang, beginn,"))
        assertTrue(block.contains("fetchCameraMappingState(fenster.zeitgrenzen(beginn))"))
        assertTrue(block.contains("R.string.camera_mapping_starting"))
        assertTrue(block.contains("\"KAMERA_MELDUNG\""))
        assertTrue(block.indexOf("fenster.nachAbruf(") < block.indexOf("R.string.camera_mapping_unreachable"))
        // Abruf meldet seinen Ausgang: Verbindung steht → Kern antwortet
        val g = s.indexOf("private fun fetchCameraMappingState(")
        val fetch = s.substring(g, s.indexOf("private fun showCameraMappingDialog", g))
        assertTrue(fetch.contains("conn.connect()"))
        assertTrue(fetch.contains("KameraZuordnungAnlauf.ausgangFuerStatus(code)"))
        assertTrue(fetch.contains("if (verbunden) KameraZuordnungAnlauf.Abruf.KERN_ANTWORTET"))
    }

    @Test
    fun neuerTextInFuenfSprachenOhneVerboteneWoerter() {
        for (d in listOf("values", "values-de", "values-fr", "values-it", "values-nl")) {
            val f = File("src/main/res/$d/strings.xml").takeIf { it.exists() } ?: File("app/src/main/res/$d/strings.xml")
            val s = f.readText()
            val i = s.indexOf("name=\"camera_mapping_starting\"")
            assertTrue(d, i > 0)
            val zeile = s.substring(i, s.indexOf("</string>", i))
            assertFalse(d, zeile.contains("Erprobung") || zeile.contains("Testversion"))
        }
    }
}
