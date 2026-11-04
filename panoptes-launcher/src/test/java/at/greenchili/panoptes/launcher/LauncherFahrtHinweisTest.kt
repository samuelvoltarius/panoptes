package at.greenchili.panoptes.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.1.38.1 („ja“): Video-Apps starten aus dem Launcher auch beim Fahren, wenn in Panoptes der EINE
 * Haftungshinweis „Verstanden, ich entscheide selbst“ bestätigt ist – derselbe wie am Tacho, nie zweimal gefragt.
 * Der Launcher liest ihn nur aus dem Summary; setzen kann er ihn nicht. Video-Hintergrund und „Bearbeiten nur im
 * Stand“ bleiben unverändert.
 */
class LauncherFahrtHinweisTest {

    private fun summary(tacho: String) = """{"vehicle":{"name":"x"},$tacho"klima":null}"""

    @Test
    fun videoAppsNachDemEinenHinweisAuchBeimFahren() {
        assertFalse(Fahrsicherheit.appStartErlaubt(true, "D", false))
        assertTrue(Fahrsicherheit.appStartErlaubt(true, "D", true))
        assertTrue(Fahrsicherheit.appStartErlaubt(true, null, true))
        assertFalse("ohne Angabe wie bisher", Fahrsicherheit.appStartErlaubt(true, "D"))
        assertTrue(Fahrsicherheit.appStartErlaubt(false, "D", false))
    }

    @Test
    fun hinweisKommtNurAusDemKernUndFehltAlsNein() {
        assertTrue(LauncherContract.parseSummary(summary(""""tacho":{"aktiv":false,"fahrtHinweis":true},""")).fahrtHinweis)
        assertFalse(LauncherContract.parseSummary(summary(""""tacho":{"aktiv":false,"fahrtHinweis":false},""")).fahrtHinweis)
        assertFalse("alter Kern ohne Feld", LauncherContract.parseSummary(summary(""""tacho":{"aktiv":true},""")).fahrtHinweis)
        assertFalse("ohne Summary-Teil", LauncherContract.parseSummary(summary("")).fahrtHinweis)
    }

    @Test
    fun launcherSetztDenHinweisNieUndRestBleibt() {
        val main = File("src/main/java/at/greenchili/panoptes/launcher/MainActivity.kt").readText().replace("\r\n", "\n")
        val client = File("src/main/java/at/greenchili/panoptes/launcher/PanoptesClient.kt").readText()
        for (q in listOf(main, client)) {
            assertFalse("kein Weg zum Setzen", q.contains("/api/tacho/navi/fahrt"))
        }
        val regeln = File("src/main/java/at/greenchili/panoptes/launcher/LauncherLayout.kt").readText()
        assertTrue("Video-Hintergrund unverändert", regeln.contains("fun videoErlaubt(schalter: Boolean, gear: String?): Boolean = schalter && gear == \"P\""))
        assertTrue("Bearbeiten nur im Stand unverändert", regeln.contains("return gear == \"P\" || (gear == null && acc == \"off\")"))
    }
}
