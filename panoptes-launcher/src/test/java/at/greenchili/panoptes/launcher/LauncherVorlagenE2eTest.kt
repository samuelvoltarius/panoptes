package at.greenchili.panoptes.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 0.1.41 Nachbesserung aus dem E2E: M2 (keine Frage beim Update), M4 (Kontrast nach Design), G1, G2, G3. */
class LauncherVorlagenE2eTest {

    private fun datei(rel: String): File { val f = File(rel); return if (f.exists()) f else File("panoptes-launcher/$rel") }
    private fun lies(rel: String): String = datei(rel).readText().replace("\r\n", "\n")
    private val m by lazy { lies("src/main/java/at/greenchili/panoptes/launcher/MainActivity.kt") }
    private fun block(von: String, bis: String) = m.substring(m.indexOf(von), m.indexOf(bis, m.indexOf(von)))

    // ---------- M2 ----------

    @Test
    fun m2UpdateFragtNichtAuchOhneGespeichertesLayout() {
        assertTrue("Neuinstallation", Vorlagen.lief(1000L, 1000L, kenntKern = false).not())
        assertTrue("Update: lastUpdateTime > firstInstallTime", Vorlagen.lief(1000L, 2000L, kenntKern = false))
        assertTrue("Kern-Stand schon bekannt", Vorlagen.lief(1000L, 1000L, kenntKern = true))
        assertFalse("unbekannte Zeiten = keine Annahme", Vorlagen.lief(0L, 0L, kenntKern = false))
        // 0.1.40 hat nie ein Layout gespeichert → trotzdem nicht fragen
        assertFalse(Vorlagen.fragen(layoutGespeichert = false, schonGefragt = false, lief = true))
        assertTrue(Vorlagen.fragen(layoutGespeichert = false, schonGefragt = false, lief = false))
        val c = block("override fun onCreate(", "override fun onSaveInstanceState(")
        assertTrue("onCreate nutzt die Update-Erkennung", c.contains("KEY_VORLAGE_GEFRAGT, false), launcherLief())"))
        val l = block("private fun launcherLief()", "private var vorlageNachExport")
        assertTrue(l.contains("firstInstallTime") && l.contains("lastUpdateTime") && l.contains("KEY_KENNT_KERN"))
    }

    // ---------- M4 ----------

    @Test
    fun m4TextErreichtBeiJedemDesignUndHellemBild45zu1() {
        for (p in listOf(PanoptesStil.NACHT, PanoptesStil.DUNKEL, PanoptesStil.HELL, PanoptesStil.KONTRAST)) {
            for (hell in listOf(0f, 0.5f, 1f)) for (wahl in Schleier.WAHL) {
                val d = Schleier.deckung(hell, p.seiteBis, wahl, p.onSurfaceVariant)
                val grund = Schleier.mischen(hell, p.seiteBis, d)
                assertTrue("seite=${Integer.toHexString(p.seiteBis)} hell=$hell $wahl d=$d",
                    Schleier.kontrast(p.onSurfaceVariant, grund) >= Schleier.ZIEL_KONTRAST)
                assertTrue("nie schwächer als bisher", d >= Schleier.deckung(hell, p.seiteBis, wahl) - 0.0001f)
                assertTrue("Rest Bild bleibt", d <= Schleier.TEXT_MAX + 0.0001f)
            }
        }
        // Befund: Nacht + weißes Bild + Schwach deckte 60 % (≈ 1,4:1); jetzt deutlich dichter
        val nacht = Schleier.deckung(1f, PanoptesStil.NACHT.seiteBis, Schleier.SCHWACH, PanoptesStil.NACHT.onSurfaceVariant)
        assertTrue("Nacht + helles Bild ≥ 0,9", nacht >= 0.9f)
        // passendes dunkles Bild bei dunklem Design bleibt „Schwach“ (35 %)
        assertEquals(Schleier.MIN, Schleier.deckung(0.05f, PanoptesStil.DUNKEL.seiteBis, Schleier.SCHWACH, PanoptesStil.DUNKEL.onSurfaceVariant), 0.02f)
        // WCAG-Rechnung stimmt an bekannten Punkten
        assertEquals(21f, Schleier.kontrast(0xFFFFFFFF.toInt(), 0xFF000000.toInt()), 0.01f)
        assertEquals(1f, Schleier.kontrast(0xFF777777.toInt(), 0xFF777777.toInt()), 0.001f)
        // Aufruf mit der Textfarbe der jeweils gültigen Palette (auch bei Tag/Nacht automatisch)
        val h = block("private fun hintergrundAnwenden()", "private fun dekodieren(")
        assertTrue(h.contains("Schleier.wahl(this), ui.p.onSurfaceVariant)"))
    }

    // ---------- G1, G2, G3 ----------

    @Test
    fun g1ErstFrageEinheitlichGross() {
        val e = block("private fun vorlageErstFrage()", "private fun launcherLief()")
        assertTrue("Adapter stellt jede Zeile ein", e.contains("override fun getView(") && e.contains("textSize = 20f") && e.contains("setAdapter(adapter)"))
        assertFalse("kein nachträgliches Umstellen nur sichtbarer Zeilen", e.contains("lv.post"))
    }

    @Test
    fun g2NachErstSichernGehtEsMitDerVorlageWeiter() {
        val w = block("private fun vorlageWaehlen()", "private fun vorlageSetzen(")
        assertTrue(w.contains("vorlageNachExport = v; exportieren()"))
        val r = block("override fun onActivityResult(", "REQ_IMPORT ->")
        assertTrue("gemerkte Vorlage wird vor jedem Abbruch-Rücksprung abgeholt",
            r.indexOf("vorlageNachExport.also { vorlageNachExport = null }") in 0 until r.indexOf("val uri = data?.data ?: return"))
        assertTrue("erst nach geschriebener Datei", r.contains("if (ok && nachExport != null && darfBedienen()) vorlageSetzen(nachExport)"))
    }

    @Test
    fun g3StandardUndCockpitStellenDieGroesseZurueck() {
        assertEquals(LauncherGroesse.GROESSER, Vorlagen.groesse(Vorlagen.EINFACH))
        assertEquals(LauncherGroesse.AUTO, Vorlagen.groesse(Vorlagen.STANDARD))
        assertEquals(LauncherGroesse.AUTO, Vorlagen.groesse(Vorlagen.COCKPIT))
        assertTrue(block("private fun vorlageSetzen(", "private fun tagNachtUmschalten(").contains("LauncherGroesse.speichern(this, g); recreate()"))
        // der Hinweis unter Erweitert sagt es (nichts still umstellen), in 5 Sprachen
        for ((d, wort) in listOf("values" to "Automatic", "values-de" to "Automatisch", "values-fr" to "Automatique",
                "values-it" to "Automatica", "values-nl" to "Automatisch")) {
            val x = lies("src/main/res/$d/strings.xml")
            val hinweis = Regex("name=\"vorlage_hinweis\">([^<]*)<").find(x)!!.groupValues[1]
            assertTrue("$d: $hinweis", hinweis.contains(wort))
        }
    }
}
