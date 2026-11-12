package at.greenchili.panoptes.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.1.42 (R1 aus E2E_0142): „Bei P: Startseite zeigen“ muss auch aus dem Karten-Split heraus wirken. D → Karte
 * startet den Split; der folgende Wechsel nach P verlässt ihn erst (wie Knopf „Start“), die Navi läuft weiter.
 */
class LauncherR1Test {
    private val alle = Selbststeuerung.Wahl(true, true, true)
    private val lange = 100_000L

    @Test
    fun dNachPausDemKartenZustand() {
        val z = Selbststeuerung.Zustand()
        Selbststeuerung.schritt(z, alle, "P", null, lange, 0)
        val d = Selbststeuerung.schritt(z, alle, "D", null, lange, 0)
        assertEquals(Selbststeuerung.Ziel.KARTE, d)
        // Karten-Split läuft jetzt (karteImSplit = Navi-Paket)
        val p = Selbststeuerung.schritt(z, alle, "P", null, lange, 0)
        assertEquals(Selbststeuerung.Ziel.START, p)
        assertTrue("im Split: erst verlassen", Selbststeuerung.splitVerlassen(p!!, "com.example.navi"))
        assertFalse("ohne Split: nichts zu verlassen", Selbststeuerung.splitVerlassen(p, null))
        assertFalse("Karte verlässt den Split nie", Selbststeuerung.splitVerlassen(Selbststeuerung.Ziel.KARTE, "com.example.navi"))
        assertFalse("Laden verlässt den Split nicht", Selbststeuerung.splitVerlassen(Selbststeuerung.Ziel.LADEN, "com.example.navi"))
    }

    private fun basis() = if (File("src/main").isDirectory) File(".") else File("panoptes-launcher")
    private fun quelle(n: String) = File(basis(), "src/main/java/at/greenchili/panoptes/launcher/$n").readText().replace("\r\n", "\n")

    @Test
    fun vertragStartVerlaesstSplit() {
        val m = quelle("MainActivity.kt")
        val st = m.substringAfter("private fun selbstSteuern()").substringBefore("private fun nachtJetzt()")
        val start = st.substringAfter("Selbststeuerung.Ziel.START ->").substringBefore("Selbststeuerung.Ziel.LADEN ->")
        assertTrue(start.contains("seite = 0; zeichneSeite()"))
        assertTrue(start.contains("if (Selbststeuerung.splitVerlassen(ziel, karteImSplit)) splitVerlassen()"))
        // die Navi-App wird nicht beendet: kein Beenden/Schließen eines Pakets im START-Weg
        for (verboten in listOf("killBackgroundProcesses", "forceStop", "finishAndRemoveTask", "karteImSplit = null"))
            assertFalse(verboten, start.contains(verboten))
        // splitVerlassen() ist der gleiche Weg wie Knopf „Start“: Kern verschiebt nur den Launcher-Task
        val sv = m.substringAfter("private fun splitVerlassen()").substringBefore("private fun statusSpalte()")
        assertTrue(sv.contains("client.splitBeenden()"))
        assertTrue(sv.contains("splitGewolltBeendet = true"))
    }
}
