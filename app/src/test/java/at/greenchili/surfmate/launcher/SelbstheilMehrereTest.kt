package at.greenchili.surfmate.launcher

import at.greenchili.surfmate.launcher.DaemonLauncher.Companion.SelbstheilSchritt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 0.1.43.18 (F3, Befund 19.11. 11:55): „mehrere“ erst nach 10 s noch einmal prüfen, statt sofort neu zu starten. */
class SelbstheilMehrereTest {

    @Test
    fun mehrereBeimErstenBlickHeisstNochmalPruefen() {
        assertEquals(SelbstheilSchritt.NOCHMAL_PRUEFEN, DaemonLauncher.selbstheilSchritt("mehrere", false))
        assertEquals(10_000L, DaemonLauncher.MEHRERE_NACHPRUEF_MS)
    }

    @Test
    fun mehrereUndDannGruenStartetNichtNeu() {
        assertEquals(SelbstheilSchritt.FERTIG, DaemonLauncher.selbstheilSchritt("gruen", true))
        assertEquals(SelbstheilSchritt.FERTIG, DaemonLauncher.selbstheilSchritt("angehaengt", true))
    }

    @Test
    fun zweimalMehrereStartetEinmalNeu() {
        assertEquals(SelbstheilSchritt.NEUSTART, DaemonLauncher.selbstheilSchritt("mehrere", true))
    }

    @Test
    fun andereRoteBefundeWieBisherSofort() {
        for (b in listOf("kein_daemon", "fremd", "skript_fehler", "fehler", null)) {
            assertEquals(b.toString(), SelbstheilSchritt.NEUSTART, DaemonLauncher.selbstheilSchritt(b, false))
        }
        for (b in listOf("gruen", "skript_neu", "angehaengt", "gesperrt")) {
            assertEquals(b, SelbstheilSchritt.FERTIG, DaemonLauncher.selbstheilSchritt(b, false))
        }
    }

    @Test
    fun zweiterBlickIstVerdrahtet() {
        val f = File("src/main/java/at/greenchili/surfmate/launcher/DaemonLauncher.kt").takeIf { it.exists() }
            ?: File("app/src/main/java/at/greenchili/surfmate/launcher/DaemonLauncher.kt")
        val s = f.readText()
        assertTrue(s.contains("Thread.sleep(MEHRERE_NACHPRUEF_MS)"))
        assertTrue(s.contains("kameraWatchdogPruefen(outputDir, nativeLibDir, callback, zweiterBlick = true)"))
    }
}
