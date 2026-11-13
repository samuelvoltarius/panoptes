package at.greenchili.panoptes.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.1.43.3 (Rang 7) Sprache im Launcher sichtbar: Assistent-Knopf nur mit eingeschalteten Sprachbefehlen,
 * „Was kann ich sagen?“ nur mit Befehlen aus Panoptes, ehrliche Plaketten, der Launcher hört nie selbst zu.
 */
class LauncherSpracheTest {
    private fun basis() = if (File("src/main").isDirectory) File(".") else File("panoptes-launcher")
    private fun quelle(n: String) = File(basis(), "src/main/java/at/greenchili/panoptes/launcher/$n").readText().replace("\r\n", "\n")

    private val json = """{"sprache":"de","erkennbar":true,"an":true,"weckwort":true,"weckwortSatz":"hallo auto","modellDa":true,
        "befehle":[
          {"befehl":"klima_an","kategorie":"klima","art":"rueckfrage","saetze":["klima an","klima einschalten"]},
          {"befehl":"waermer","kategorie":"klima","art":"rueckfrage","saetze":["wärmer"]},
          {"befehl":"navi_heim","kategorie":"navigation","art":"vorschau","saetze":["nach hause"]},
          {"befehl":"musik_weiter","kategorie":"medien","art":"medien","saetze":["nächstes lied"]},
          {"befehl":"reichweite","kategorie":"allgemein","art":"lesen","saetze":["reichweite"]},
          {"befehl":"hupe","kategorie":"fahrzeug","art":"steuert","saetze":["hupe"]},
          {"befehl":"fenster","kategorie":"klima","art":"fremd","saetze":["fenster auf"]}],
        "nie":["hupe","licht an"]}"""

    @Test
    fun nurBekannteGruppenUndArten() {
        val k = Sprache.parse(json)
        assertNotNull(k)
        k!!
        assertEquals("unbekannte Kategorie/Art fällt weg", listOf("klima_an", "waermer", "navi_heim", "musik_weiter", "reichweite"), k.befehle.map { it.befehl })
        assertEquals(listOf("klima", "navigation", "medien", "allgemein"), k.kategorien())
        assertEquals(listOf("hupe", "licht an"), k.nie)
        assertNull(Sprache.parse("kaputt"))
        assertNull(Sprache.parse(null))
        // leere Gruppen gibt es nicht als Chip
        val nurKlima = Sprache.parse("""{"an":true,"befehle":[{"befehl":"klima_an","kategorie":"klima","art":"rueckfrage","saetze":["klima an"]}]}""")!!
        assertEquals(listOf("klima"), nurKlima.kategorien())
    }

    @Test
    fun sucheChipsUndAusprobieren() {
        val k = Sprache.parse(json)!!
        val titel = mapOf("klima_an" to "Klima an", "waermer" to "Wärmer", "navi_heim" to "Nach Hause", "musik_weiter" to "Nächstes Lied", "reichweite" to "Reichweite")
        assertEquals(listOf("klima_an", "waermer"), k.filter("", "klima") { titel[it] ?: it }.map { it.befehl })
        assertEquals(listOf("klima_an"), k.filter("EINSCHALTEN", null) { titel[it] ?: it }.map { it.befehl })
        assertEquals(listOf("navi_heim"), k.filter("hause", null) { titel[it] ?: it }.map { it.befehl })
        assertTrue(k.filter("xyz", null) { it }.isEmpty())
        val p = k.probieren()
        assertTrue(p.size in 1..6)
        assertEquals("klima_an" to "klima an", p.first())
        assertTrue("jede Gruppe vertreten", p.map { it.first }.containsAll(listOf("navi_heim", "musik_weiter", "reichweite")))
    }

    @Test
    fun zustandUndKnopfNurMitZustimmung() {
        val k = Sprache.parse(json)!!
        assertEquals("bereit", k.zustand())
        assertTrue(k.knopfSichtbar(true))
        assertFalse("Launcher-Schalter aus", k.knopfSichtbar(false))
        val aus = k.copy(an = false)
        assertEquals("aus", aus.zustand())
        assertFalse("ab Werk (Sprachbefehle aus) kein Knopf", aus.knopfSichtbar(true))
        assertEquals("kein_modell", k.copy(modellDa = false).zustand())
        assertEquals("noch_nicht", k.copy(erkennbar = false).zustand())
    }

    @Test
    fun ausfuehrenNurUeberPanoptesUndKeinMikrofonImLauncher() {
        val sp = quelle("Sprache.kt")
        assertTrue(sp.contains("Intent().setClassName(Raster.PANOPTES, Raster.PANOPTES + ZIEL)"))
        assertTrue(sp.contains("if (befehl != null) i.putExtra(EXTRA_BEFEHL, befehl)"))
        for (f in listOf("Sprache.kt", "SprachHilfe.kt", "SprachKnopf.kt")) {
            val q = quelle(f)
            for (v in listOf("AudioRecord", "RECORD_AUDIO", "SpeechRecognizer", "klima(", "fenster(", "/api/launcher/v1/klima", "hupe(", "requestMitFehler(\"POST\""))
                assertFalse("$f: $v", q.contains(v))
        }
        val man = File(basis(), "src/main/AndroidManifest.xml").readText()
        assertFalse("Launcher fragt nie nach dem Mikrofon", man.contains("RECORD_AUDIO"))
        val e = man.substring(man.indexOf(".SprachHilfeActivity")).substringBefore("/>")
        assertTrue(e.contains("android:exported=\"false\""))
        val m = quelle("MainActivity.kt")
        assertTrue(m.contains("if (!bearbeiten && !isInMultiWindowMode) sprachKnopf.einhaengen(root, ui)"))
        assertTrue(m.contains("sprachKnopf.aktualisieren()"))
        val kn = quelle("SprachKnopf.kt")
        assertTrue(kn.contains("val sichtbar = katalog?.knopfSichtbar(Sprache.knopfSchalter(activity)) == true"))
        assertTrue("Standard: unsichtbar bis der Stand da ist", kn.contains("visibility = View.GONE"))
        assertTrue(quelle("PanoptesClient.kt").contains("fun sprache(): SprachKatalog? = Sprache.parse(get(\"/api/launcher/v1/sprache\"))"))
    }

    @Test
    fun ehrlichePlakettenUndTexteInFuenfSprachen() {
        val befehle = listOf("klima_an", "klima_aus", "waermer", "kaelter", "navi_heim", "musik_weiter", "musik_zurueck", "musik_pause", "wo_bin_ich", "reichweite")
        val hilfe = quelle("SprachHilfe.kt") + quelle("SprachKnopf.kt") + quelle("MainActivity.kt")
        val genutzt = Regex("R\\.string\\.(sh_[a-z_]+)").findAll(hilfe).map { it.groupValues[1] }.toSet()
        assertTrue(genutzt.size > 20)
        for (d in listOf("values", "values-de", "values-fr", "values-it", "values-nl")) {
            val x = File(basis(), "src/main/res/$d/strings_sprache.xml").readText()
            for (k in genutzt) assertTrue("$d: $k", x.contains("name=\"$k\""))
            for (b in befehle) {
                assertTrue("$d: sh_t_$b", x.contains("name=\"sh_t_$b\""))
                assertTrue("$d: sh_e_$b", x.contains("name=\"sh_e_$b\""))
            }
            for (a in Sprache.ARTEN) assertTrue("$d: sh_b_$a", x.contains("name=\"sh_b_$a\""))
            for (k in Sprache.KATEGORIEN) assertTrue("$d: sh_k_$k", x.contains("name=\"sh_k_$k\""))
        }
        val de = File(basis(), "src/main/res/values-de/strings_sprache.xml").readText()
        assertTrue(de.contains(">Mit Rückfrage<") && de.contains(">Nur lesen<"))
        assertTrue("Klima: Stufe, nie Gradzahl", de.contains("nie eine erfundene Gradzahl"))
        assertTrue("A+", de.contains("Nie per Sprache: Hupe, Blinker, Licht und Alarm."))
        val fr = File(basis(), "src/main/res/values-fr/strings_sprache.xml").readText()
        assertTrue("FR mit vous", fr.contains("Touchez") && !fr.contains(" tu "))
    }
}
