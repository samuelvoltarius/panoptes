package at.greenchili.panoptes.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.1.38.14 (P4, Seal U DM-i: „alles zu groß, Auto-Bild überdimensioniert“): Der Launcher passt sich dem Bildschirm an
 * (nur kleiner, nie still größer) und der Nutzer wählt Kleiner/Größer; Einstellungen zuerst das Alltägliche.
 */
class LauncherGroesseTest {

    @Test
    fun automatischNurKleinerNieGroesser() {
        assertEquals("gezeichnet für 1280×720 dp: unverändert", 1f, LauncherGroesse.autoFaktor(1280f, 720f), 1e-4f)
        assertEquals("größerer Bildschirm: nicht still vergrößert", 1f, LauncherGroesse.autoFaktor(1920f, 1080f), 1e-4f)
        assertEquals("hohe Dichte (960×540 dp): kleiner", 0.75f, LauncherGroesse.autoFaktor(960f, 540f), 1e-4f)
        assertEquals("Hochformat zählt kurze/lange Seite", 0.75f, LauncherGroesse.autoFaktor(540f, 960f), 1e-4f)
        assertEquals("nie unter MIN", LauncherGroesse.MIN, LauncherGroesse.autoFaktor(300f, 200f), 1e-4f)
        assertEquals("ungültig: unverändert", 1f, LauncherGroesse.autoFaktor(0f, 0f), 1e-4f)
    }

    @Test
    fun nutzerwahlWieBeimTacho() {
        assertEquals(1f, LauncherGroesse.faktor(LauncherGroesse.AUTO, 1f), 1e-4f)
        assertEquals(0.85f, LauncherGroesse.faktor(LauncherGroesse.KLEINER, 1f), 1e-4f)
        assertEquals(1.15f, LauncherGroesse.faktor(LauncherGroesse.GROESSER, 1f), 1e-4f)
        assertEquals("unbekannt = Automatisch", 0.75f, LauncherGroesse.faktor("xyz", 0.75f), 1e-4f)
        assertEquals("begrenzt", LauncherGroesse.MIN, LauncherGroesse.faktor(LauncherGroesse.KLEINER, 0.7f), 1e-4f)
        assertEquals(240, LauncherGroesse.dichteDpi(240, 1f))
        assertEquals(180, LauncherGroesse.dichteDpi(240, 0.75f))
        assertEquals(80, LauncherGroesse.dichteDpi(60, 0.7f))
    }

    private fun lies(rel: String): String {
        var f = File(rel); if (!f.exists()) f = File("panoptes-launcher/$rel")
        return f.readText().replace("\r\n", "\n")
    }

    @Test
    fun eineStelleUndEinstellungenMitErweitert() {
        val m = lies("src/main/java/at/greenchili/panoptes/launcher/MainActivity.kt")
        assertTrue(m.contains("super.attachBaseContext(LauncherGroesse.anwenden(newBase))"))
        assertFalse("Auto-Bild nicht mehr fest 360×144 dp", m.contains("FrameLayout.LayoutParams(ui.dp(360), ui.dp(144), Gravity.CENTER)"))
        val e = m.substring(m.indexOf("private fun einstellungen(erweitert: Boolean = false)"), m.indexOf("private fun groesseName("))
        val alltag = e.substring(0, e.indexOf("} else {"))
        assertTrue(alltag.contains("groesseWaehlen()") && alltag.contains("R.string.erweitert"))
        val selten = e.substring(e.indexOf("} else {"))
        for (k in listOf("exportieren()", "importieren()", "zuruecksetzenFragen()", "appInfo(packageName)", "zugriffErklaeren()",
                "zeigeStatus", "zeigeReiter", "nebeneinander")) {
            assertTrue("nichts fällt weg: $k unter Erweitert", selten.contains(k))
        }
        assertTrue(lies("src/main/java/at/greenchili/panoptes/launcher/Bausteine.kt").contains("ellipsize = android.text.TextUtils.TruncateAt.END"))
        for (d in listOf("values", "values-de", "values-fr", "values-it", "values-nl")) {
            val s = lies("src/main/res/$d/strings.xml")
            for (k in listOf("launcher_groesse", "groesse_auto", "groesse_kleiner", "groesse_groesser", "groesse_hinweis", "erweitert", "zurueck")) {
                assertTrue("$d $k", s.contains("name=\"$k\""))
            }
        }
    }
}
