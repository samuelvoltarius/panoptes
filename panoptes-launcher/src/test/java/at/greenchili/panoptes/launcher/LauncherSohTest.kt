package at.greenchili.panoptes.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * 0.1.38.8: Akku-Gesundheit im Launcher wie die Web-Akku-Karte – BYD-Wert und Panoptes-Schätzung nebeneinander,
 * ein fehlender Wert heißt „nicht verfügbar“, der alte Einzelwert nur als Rückfall für einen älteren Kern.
 */
class LauncherSohTest {
    private fun summary(battery: String) = LauncherContract.parseSummary("""{"battery":$battery}""")

    @Test fun beideWerte() {
        val s = summary("""{"healthPct":100,"sohAutoPct":100.0,"sohSchaetzungPct":95.4}""")
        assertEquals(100.0, s.sohAutoPct!!, 0.0)
        assertEquals(95.4, s.sohSchaetzungPct!!, 0.0)
        assertEquals(LauncherContract.Gesundheit.ZWEI, LauncherContract.gesundheitArt(s))
    }

    @Test fun einWertFehltUndAlterKern() {
        assertEquals("fehlender Wert = „nicht verfügbar“, trotzdem beide zeigen", LauncherContract.Gesundheit.ZWEI,
            LauncherContract.gesundheitArt(summary("""{"healthPct":98,"sohAutoPct":98.0,"sohSchaetzungPct":null}""")))
        assertEquals(LauncherContract.Gesundheit.ZWEI, LauncherContract.gesundheitArt(summary("""{"sohSchaetzungPct":96.1}""")))
        assertEquals("älterer Kern ohne die neuen Felder", LauncherContract.Gesundheit.EINZELWERT,
            LauncherContract.gesundheitArt(summary("""{"healthPct":97}""")))
        assertEquals(LauncherContract.Gesundheit.KEINE, LauncherContract.gesundheitArt(summary("""{}""")))
        assertEquals(LauncherContract.Gesundheit.KEINE, LauncherContract.gesundheitArt(null))
    }

    @Test fun unsinnigeWerteFallenWeg() {
        val s = summary("""{"sohAutoPct":-1,"sohSchaetzungPct":250}""")
        assertNull(s.sohAutoPct)
        assertNull(s.sohSchaetzungPct)
    }

    @Test fun kachelInfoUndStartseiteNutzenDieselbenTexte() {
        val main = String(Files.readAllBytes(Paths.get("src/main/java/at/greenchili/panoptes/launcher/MainActivity.kt")), Charsets.UTF_8)
        assertTrue("Kachel", main.contains("gesund.text = gesundheitWerte(s) ?: \"\""))
        assertTrue("Startseite neben der Akku-Zahl", main.contains("gesundStart.text = gesundheitWerte(s) ?: \"\""))
        assertTrue("Akku-Info mit Erklärung", main.contains("*gesundheitZeilen(s).toTypedArray())"))
        assertTrue(main.contains("getString(R.string.akku_gesundheit_erklaerung)"))
        assertTrue("fehlt = nicht verfügbar", main.contains("?: getString(R.string.nicht_verfuegbar)"))
        assertTrue("Regel 50+: große Werte, kleine Bezeichnung", main.contains("val gesund = ui.zahl(\"\", 20f)"))
        listOf("values", "values-de", "values-fr", "values-it", "values-nl").forEach { d ->
            val x = String(Files.readAllBytes(Paths.get("src/main/res/$d/strings.xml")), Charsets.UTF_8)
            listOf("akku_gesundheit_titel", "akku_gesundheit_werte", "akku_gesundheit_auto_zeile", "akku_gesundheit_schaetzung_zeile",
                "akku_gesundheit_erklaerung", "akku_prozent", "nicht_verfuegbar").forEach { n ->
                assertTrue("$d/$n", x.contains("name=\"$n\""))
            }
        }
    }
}
