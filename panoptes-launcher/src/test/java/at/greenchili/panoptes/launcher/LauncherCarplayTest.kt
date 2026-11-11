package at.greenchili.panoptes.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.1.41 (CP, Alfreds Go 11.11.): Kachel „CarPlay / Android Auto“. Vertrag: nur bei installierter Werks-App, Start
 * immer im Vollbild (am Surf resizeableActivity=false, kein Split, keine Kartenquelle), höchstens EINMAL auf freiem
 * Platz ergänzt, nie verschoben, Dock unberührt, entfernt bleibt entfernt, ohne Werks-App kein leerer Platz.
 */
class LauncherCarplayTest {

    private fun kachel(l: StartLayout) = l.seiten.flatten().filter { it.kachel == Kachel.PROJEKTION }

    @Test
    fun nurInstallierteWerksApps() {
        assertEquals(listOf(Raster.CARPLAY, Raster.ANDROID_AUTO), Projektion.sichtbar { true })
        assertEquals(listOf(Raster.CARPLAY), Projektion.sichtbar { it == Raster.CARPLAY })
        assertTrue(Projektion.sichtbar { false }.isEmpty())
        // Rückfall-Activities wie am Surf belegt (CARPLAY_AA_SURF_0141.md)
        assertEquals("com.byd.carplay.ui.MainActivity", Projektion.RUECKFALL[Raster.CARPLAY])
        assertEquals("com.google.android.projection.sink.ui.AAPActivity", Projektion.RUECKFALL[Raster.ANDROID_AUTO])
    }

    @Test
    fun einmalAufFreienPlatzOhneVerschiebenUndOhneDock() {
        val l = Raster.standard(null).copy(dock = listOf(Raster.PANOPTES, Raster.CARPLAY))   // Alfreds Dock: ohne AA
        val a = Projektion.abgleichen(l, listOf(Raster.CARPLAY, Raster.ANDROID_AUTO), schonErgaenzt = false, appsBekannt = true)
        assertTrue(a.merken)
        val k = kachel(a.layout)
        assertEquals(1, k.size)
        assertEquals(2 to 2, k[0].b to k[0].h)   // groß, wenn Platz da ist
        // nichts verschoben, nichts entfernt, Dock gleich
        val vorher = l.seiten.flatten().associateBy { it.id }
        a.layout.seiten.flatten().filter { it.kachel != Kachel.PROJEKTION }.forEach { assertEquals(vorher[it.id], it) }
        assertEquals(vorher.size + 1, a.layout.seiten.flatten().size)
        assertEquals(l.dock, a.layout.dock)
        assertEquals(l.seiten.size, a.layout.seiten.size)
        // Seite 1 ist voll (Fahrzeug + Musik) → Seite 2
        assertTrue(a.layout.seiten[1].any { it.kachel == Kachel.PROJEKTION })
    }

    @Test
    fun entferntBleibtEntferntUndKeinZweitesMal() {
        val l = Raster.standard(null)
        val einmal = Projektion.abgleichen(l, listOf(Raster.CARPLAY), schonErgaenzt = false, appsBekannt = true).layout
        // zweiter Abgleich mit Merker: unverändert
        assertSame(einmal, Projektion.abgleichen(einmal, listOf(Raster.CARPLAY), schonErgaenzt = true, appsBekannt = true).layout)
        // Nutzer entfernt die Kachel → mit Merker kommt sie nicht wieder
        val id = kachel(einmal).single().id
        val ohne = Raster.entfernen(einmal, id)
        assertTrue(kachel(ohne).isEmpty())
        assertSame(ohne, Projektion.abgleichen(ohne, listOf(Raster.CARPLAY), schonErgaenzt = true, appsBekannt = true).layout)
        // schon vorhanden (z. B. Import) → nur Merker setzen, nichts doppelt
        val schon = Projektion.abgleichen(einmal, listOf(Raster.CARPLAY), schonErgaenzt = false, appsBekannt = true)
        assertSame(einmal, schon.layout); assertTrue(schon.merken)
    }

    @Test
    fun keinPlatzKeineErgaenzungAberMerker() {
        val voll = StartLayout(listOf(listOf(Element("w1", ElementTyp.WIDGET, 0, 0, 6, 3, kachel = Kachel.FAHRZEUG))),
            listOf(Raster.PANOPTES), "panoptes", 1)
        val a = Projektion.abgleichen(voll, listOf(Raster.CARPLAY), schonErgaenzt = false, appsBekannt = true)
        assertSame(voll, a.layout)          // keine neue Seite, nichts verschoben
        assertTrue(a.merken)                // höchstens einmal versucht
        // nur 2×1 frei → 2×1
        val fast = StartLayout(listOf(listOf(Element("w1", ElementTyp.WIDGET, 0, 0, 4, 3, kachel = Kachel.FAHRZEUG),
            Element("w2", ElementTyp.WIDGET, 4, 0, 2, 2, kachel = Kachel.MUSIK))), listOf(Raster.PANOPTES), "panoptes", 2)
        val k = kachel(Projektion.ergaenzen(fast)).single()
        assertEquals(listOf(4, 2, 2, 1), listOf(k.x, k.y, k.b, k.h))
    }

    @Test
    fun ohneWerksAppKeinLeererPlatz() {
        val mit = Projektion.ergaenzen(Raster.standard(null))
        assertEquals(1, kachel(mit).size)
        val a = Projektion.abgleichen(mit, emptyList(), schonErgaenzt = true, appsBekannt = true)
        assertTrue(kachel(a.layout).isEmpty())
        assertFalse(a.merken)
        // App-Liste unbekannt (Fehler im PackageManager) → nichts löschen
        assertSame(mit, Projektion.abgleichen(mit, emptyList(), schonErgaenzt = true, appsBekannt = false).layout)
        // ohne Kachel und ohne App: dasselbe Objekt (kein Speichern)
        val ohne = Raster.standard(null)
        assertSame(ohne, Projektion.abgleichen(ohne, emptyList(), schonErgaenzt = false, appsBekannt = true).layout)
    }

    @Test
    fun speichernUndLaden() {
        val mit = Projektion.ergaenzen(Raster.standard(null))
        val zurueck = Raster.fromJson(Raster.toJson(mit))
        assertNotNull(zurueck)
        assertEquals(kachel(mit), kachel(zurueck!!))
        assertTrue(Regex("\"kachel\":\\s*\"projektion\"").containsMatchIn(Raster.toJson(mit)))
    }

    private fun quelle(name: String): String {
        val b = if (File("src/main").isDirectory) File(".") else File("panoptes-launcher")
        return File(b, "src/main/java/at/greenchili/panoptes/launcher/$name").readText().replace("\r\n", "\n")
    }

    private fun res(ordner: String): String {
        val b = if (File("src/main").isDirectory) File(".") else File("panoptes-launcher")
        return File(b, "src/main/res/$ordner/strings.xml").readText()
    }

    /** Quelltext-Vertrag: Vollbild-Start, nie Split; Merker; Rückfall-Activity; Texte in 5 Sprachen. */
    @Test
    fun vertrag() {
        val m = quelle("MainActivity.kt")
        assertTrue(m.contains("private fun starteProjektion(p: String) = starteVollbild(p)"))
        assertTrue(m.contains("Kachel.PROJEKTION -> projektionKachel(gross)"))
        assertTrue(m.contains("?: rueckfallIntent(p)"))
        assertTrue(m.contains("KEY_PROJEKTION_ERGAENZT = \"projektion_ergaenzt\""))
        assertTrue(m.contains("projektionAbgleichen(liste.isNotEmpty())"))
        assertTrue("nur anbieten, wenn Werks-App da",
            m.contains("it != Kachel.PROJEKTION || projektionSichtbar().isNotEmpty()"))
        val kachelCode = m.substringAfter("private fun projektionKachel(").substringBefore("private fun uhrKachel(")
        assertFalse("kein Split für CarPlay/AA", kachelCode.contains("nebenOeffnen") || kachelCode.contains("nebenStarten"))
        assertFalse(m.contains("force_resizable_activities 1"))
        for (o in listOf("values", "values-de", "values-fr", "values-it", "values-nl")) {
            val s = res(o)
            for (k in listOf("widget_projektion", "projektion_carplay", "projektion_android_auto", "projektion_tipp"))
                assertTrue("$o: $k", s.contains("name=\"$k\""))
        }
    }
}
