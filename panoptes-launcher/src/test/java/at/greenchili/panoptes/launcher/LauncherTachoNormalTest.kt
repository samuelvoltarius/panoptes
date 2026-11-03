package at.greenchili.panoptes.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 0.1.37: Knopf „Tacho wieder normal“ im Launcher, nur sichtbar, wenn am Fahrerdisplay etwas läuft. */
class LauncherTachoNormalTest {

    private fun summary(tacho: String) = """{"vehicle":{"name":"x"},$tacho"klima":null}"""

    @Test
    fun zusammenfassungMeldetObAmFahrerdisplayEtwasLaeuft() {
        assertTrue(LauncherContract.parseSummary(summary(""""tacho":{"aktiv":true},""")).tachoAktiv)
        assertFalse(LauncherContract.parseSummary(summary(""""tacho":{"aktiv":false},""")).tachoAktiv)
        assertFalse("ohne Angabe (alter Kern) kein Knopf", LauncherContract.parseSummary(summary("")).tachoAktiv)
    }

    /** Die Release-Pipeline verteilt den Debug-Build: der darf wie Panoptes nie debuggable sein. */
    @Test
    fun ausgelieferterBuildIstNichtDebuggable() {
        val gradle = File("build.gradle.kts").readText().replace("\r\n", "\n")
        val debug = gradle.substringAfter("        debug {").substringBefore("        }")
        assertTrue(debug.contains("isDebuggable = false"))
    }

    @Test
    fun knopfRuftNurDenKernWegAuf() {
        val client = File("src/main/java/at/greenchili/panoptes/launcher/PanoptesClient.kt").readText().replace("\r\n", "\n")
        assertTrue(client.contains("\"/api/launcher/v1/tacho/normal\""))
        val main = File("src/main/java/at/greenchili/panoptes/launcher/MainActivity.kt").readText().replace("\r\n", "\n")
        assertTrue(main.contains("setOnClickListener { tachoNormal() }"))
        assertTrue(main.contains("summary?.tachoAktiv == true"))
        for (sprache in listOf("", "-de", "-fr", "-it", "-nl")) {
            val s = File("src/main/res/values$sprache/strings.xml").readText()
            assertTrue(sprache, s.contains("name=\"tacho_normal\"") && s.contains("name=\"tacho_normal_ok\""))
        }
    }
}
