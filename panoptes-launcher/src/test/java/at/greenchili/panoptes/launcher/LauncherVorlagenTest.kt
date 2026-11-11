package at.greenchili.panoptes.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 0.1.41 (LA): Layout-Vorlagen, Tag/Nacht automatisch, N1 Mindest-Abdunkelung, G1/G5 (G6 im App-Test DemoNaviAppTest). */
class LauncherVorlagenTest {

    private fun datei(rel: String): File { val f = File(rel); return if (f.exists()) f else File("panoptes-launcher/$rel") }
    private fun lies(rel: String): String = datei(rel).readText().replace("\r\n", "\n")

    private val quelle = "src/main/java/at/greenchili/panoptes/launcher/"
    private val m by lazy { lies(quelle + "MainActivity.kt") }
    private fun block(von: String, bis: String) = m.substring(m.indexOf(von), m.indexOf(bis, m.indexOf(von)))
    private fun vorlage(n: String) = Vorlagen.lesen(n, lies("src/main/" + "assets/" + Vorlagen.datei(n)))

    private val dunkel = 0xFF0A1622.toInt()

    // ---------- 1 Vorlagen ----------

    @Test
    fun alleVorlagenSindGueltigeLayoutdateien() {
        for (n in Vorlagen.WAHL) {
            val text = lies("src/main/assets/" + Vorlagen.datei(n))
            assertTrue("$n im Export-Format", text.contains("\"format\": \"panoptes-launcher-layout\""))
            val l = vorlage(n); assertNotNull(n, l)
            // nichts fällt bei der Prüfung weg: alle Kacheln der Datei kommen an
            assertEquals(n, Regex("\"typ\": \"widget\"").findAll(text).count(), l!!.seiten.flatten().size)
            assertTrue("$n hat das Fahrzeug", l.seiten.flatten().any { it.kachel == Kachel.FAHRZEUG })
            // keine Orte, Geheimnisse, kein eigenes Bild
            listOf("lat", "lon", "token", "passwort", "pin", "vin", "\"eigen\"").forEach { assertFalse("$n: $it", text.lowercase().contains(it)) }
        }
        assertNull("unbekannter Name", Vorlagen.lesen("fremd", lies("src/main/assets/vorlagen/standard.json")))
        assertNull("kaputte Datei", Vorlagen.lesen(Vorlagen.STANDARD, "{"))
    }

    @Test
    fun einfachHatWenigeGrosseKachelnUndGrosseSchrift() {
        val e = vorlage(Vorlagen.EINFACH)!!
        val alle = e.seiten.flatten()
        assertTrue("wenige Kacheln", alle.size <= 5)
        assertTrue("alle groß (mindestens 2×3)", alle.all { it.b >= 2 && it.h >= 3 })
        assertEquals("weit", e.abstand)
        assertEquals(LauncherGroesse.GROESSER, Vorlagen.groesse(Vorlagen.EINFACH))
        // 0.1.41 (E2E G3): Standard und Cockpit stellen die Größe zurück auf Automatisch
        assertEquals(LauncherGroesse.AUTO, Vorlagen.groesse(Vorlagen.STANDARD))
        assertEquals(LauncherGroesse.AUTO, Vorlagen.groesse(Vorlagen.COCKPIT))
    }

    @Test
    fun standardEntsprichtDemWerksLayoutUndCockpitIstDunkel() {
        val s = vorlage(Vorlagen.STANDARD)!!; val w = Raster.standard(null)
        assertEquals(w.seiten.map { p -> p.map { Triple(it.kachel, it.x to it.y, it.b to it.h) } },
            s.seiten.map { p -> p.map { Triple(it.kachel, it.x to it.y, it.b to it.h) } })
        assertEquals(w.dock, s.dock); assertEquals(w.hintergrund, s.hintergrund); assertEquals(w.spalte, s.spalte)
        assertEquals("cockpit", vorlage(Vorlagen.COCKPIT)!!.hintergrund)
    }

    @Test
    fun vorlageBehaeltDockUndAppsDesNutzers() {
        val alt = Raster.standard(null).copy(dock = listOf("com.beispiel.eins", "com.beispiel.zwei"), schnell = listOf("com.beispiel.drei"),
            nebeneinander = true, nebenAnders = listOf("com.beispiel.vier"), zaehler = 99)
        val n = Vorlagen.anwenden(alt, vorlage(Vorlagen.EINFACH)!!)
        assertEquals(alt.dock, n.dock); assertEquals(alt.schnell, n.schnell)
        assertTrue(n.nebeneinander); assertEquals(alt.nebenAnders, n.nebenAnders)
        assertEquals("Ids laufen weiter", 99, n.zaehler)
        assertEquals("Seiten aus der Vorlage", vorlage(Vorlagen.EINFACH)!!.seiten, n.seiten)
        assertEquals("ohne altes Layout = Vorlage", vorlage(Vorlagen.COCKPIT), Vorlagen.anwenden(null, vorlage(Vorlagen.COCKPIT)!!))
    }

    @Test
    fun ersteFrageNurBeiNeuerEinrichtungUndVorDemErsetzenFragen() {
        assertTrue(Vorlagen.fragen(layoutGespeichert = false, schonGefragt = false))
        assertFalse("Update mit Layout: nicht fragen", Vorlagen.fragen(layoutGespeichert = true, schonGefragt = false))
        assertFalse("nur einmal", Vorlagen.fragen(layoutGespeichert = false, schonGefragt = true))
        val c = block("override fun onCreate(", "override fun onSaveInstanceState(")
        assertTrue("vor ladeLayout geprüft", c.indexOf("Vorlagen.fragen(") < c.indexOf("layout = ladeLayout()"))
        assertTrue(c.contains("vorlageErstFrage()"))
        val w = block("private fun vorlageWaehlen()", "private fun vorlageSetzen(")
        assertTrue("Rückfrage vor dem Ersetzen", w.contains("R.string.vorlage_ersetzen_frage") && w.contains("vorlageSetzen(v)"))
        assertTrue("Weg: erst als Datei sichern", w.contains("exportieren()"))
        val e = block("private fun einstellungen(", "private fun groesseName(")
        val erw = e.substring(e.indexOf("} else {"))
        assertTrue("unter Erweitert", erw.contains("vorlageWaehlen()") && erw.contains("tagNachtUmschalten()"))
        assertTrue("nur im Stand", erw.contains("if (darfBedienen()) vorlageWaehlen()"))
        assertTrue("Kern-Layout schließt die Erst-Frage", block("private fun uebernimmKern(", "private fun kernSpeichern(").contains("vorlageDialog?.dismiss()"))
    }

    // ---------- 10 Tag/Nacht ----------

    @Test
    fun tagNachtNachSonnenstandSonstUhrzeit() {
        assertTrue("Januar 22 Uhr", TagNacht.nachtNachUhr(1, 22, 0))
        assertFalse("Januar Mittag", TagNacht.nachtNachUhr(1, 12, 0))
        assertFalse("Juni 20:30 noch hell", TagNacht.nachtNachUhr(6, 20, 30))
        assertTrue("Dezember 17 Uhr dunkel", TagNacht.nachtNachUhr(12, 17, 0))
        assertTrue("Juni 4 Uhr", TagNacht.nachtNachUhr(6, 4, 0))
        assertFalse("Kern-Wert geht vor", TagNacht.istNacht(false, 1, 23, 0))
        assertTrue(TagNacht.istNacht(true, 6, 12, 0))
        assertTrue("ohne Kern-Wert Uhrzeit", TagNacht.istNacht(null, 1, 23, 0))
    }

    @Test
    fun tagNachtDesignStandardAusUndAbschaltbar() {
        assertEquals("aus = gewählt", "hell", TagNacht.design("hell", an = false, nacht = true))
        assertEquals("nacht", TagNacht.design("hell", an = true, nacht = true))
        assertEquals("hell", TagNacht.design("hell", an = true, nacht = false))
        assertEquals("Nacht gewählt → tagsüber Wie Panoptes", "auto", TagNacht.design("nacht", an = true, nacht = false))
        val t = lies(quelle + "TagNacht.kt")
        assertTrue("Standard aus", t.contains("getBoolean(KEY, false)"))
        assertFalse("kein eigener Lichtsensor", t.contains("SensorManager"))
        assertTrue("Palette nutzt Tag/Nacht", block("private fun stilPalette(", "private fun systemHell()").contains("designJetzt()"))
        assertTrue("Kern-Wert aus env.isNight", block("private fun designJetzt()", "private fun systemHell()").contains("summary?.istNacht"))
        assertFalse("nie exportiert", lies(quelle + "StartLayout.kt").lowercase().contains("tagnacht"))
        val s = LauncherContract.parseSummary("""{"env":{"isNight":true}}""")
        assertEquals(true, s.istNacht)
        assertNull(LauncherContract.parseSummary("""{"env":{"isNight":null}}""").istNacht)
        assertNull(LauncherContract.parseSummary("""{}""").istNacht)
    }

    // ---------- N1 ----------

    @Test
    fun schwachHatBeiHellemBildEineMindestdeckung() {
        assertEquals("passendes Bild bleibt schwach", Schleier.MIN, Schleier.deckung(0.05f, dunkel, Schleier.SCHWACH), 0.02f)
        assertTrue("helles Bild auf dunklem Design", Schleier.deckung(0.9f, dunkel, Schleier.SCHWACH) >= Schleier.SCHWACH_MIN_HELL - 0.001f)
        assertTrue("Schwach bleibt schwächer als Stark", Schleier.deckung(0.9f, dunkel, Schleier.SCHWACH) < Schleier.deckung(0.9f, dunkel, Schleier.STARK))
        assertTrue("stetig", Schleier.schwach(0.3f) > Schleier.MIN && Schleier.schwach(0.3f) < Schleier.SCHWACH_MIN_HELL)
    }

    // ---------- G1, G5 ----------

    @Test
    fun g1StandbildBleibtNachNeuaufbau() {
        val s = block("private fun standbildLaden(", "private var standbild:")
        assertTrue("gemerktes Bild zuerst", s.indexOf("standbild?.takeIf") < s.indexOf("if (!online || modell == standbildFuer) return"))
        assertTrue(s.contains("standbild = modell to bmp"))
        assertTrue("WebView nach Umhängen neu laden", block("private fun fahrzeug3d(", "private var autoGezeichnet").contains("mitte.addView(w, FrameLayout.LayoutParams(-1, -1)); webAutoUrl = null"))
    }

    @Test
    fun g5UhrBleibtNebenLangemMusiktitel() {
        val s = block("private fun statusLeiste()", "private fun statusIcon(")
        assertTrue("Musik in dehnbarer Box", s.contains("z.addView(musikBox, ui.lp(0, -2, 1f"))
        assertTrue("Titel wird gekürzt", s.contains("TruncateAt.END"))
        assertTrue("Uhr nach der Box", s.indexOf("z.addView(uhr") > s.indexOf("z.addView(musikBox"))
        assertFalse("kein fester Leerraum mehr vor der Musik", s.contains("z.addView(View(this), ui.lp(0, 1, 1f))"))
    }

    // ---------- 5 Sprachen ----------

    @Test
    fun texteInFuenfSprachen() {
        val keys = listOf("tagnacht", "tagnacht_hinweis", "vorlage", "vorlage_hinweis", "vorlage_frage", "vorlage_spaeter",
            "vorlage_einfach", "vorlage_einfach_text", "vorlage_standard", "vorlage_standard_text", "vorlage_cockpit",
            "vorlage_cockpit_text", "vorlage_ersetzen_frage", "vorlage_ersetzen", "vorlage_erst_sichern", "vorlage_ok")
        for (d in listOf("values", "values-de", "values-fr", "values-it", "values-nl")) {
            val x = lies("src/main/res/$d/strings.xml")
            keys.forEach { assertTrue("$d: $it", x.contains("name=\"$it\"")) }
            assertTrue("$d: Platzhalter", Regex("name=\"vorlage_ok\">[^<]*%1\\\$s").containsMatchIn(x))
        }
    }
}
