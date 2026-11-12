package at.greenchili.panoptes.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.1.42 (L48, Projektinhaber „gleich alles einbauen“, LUECKEN_0142.md Punkte 4 und 8):
 * Selbststeuerung (D → Karte, P → Startseite, Laden → Lade-Kachel; nur Anzeige, ab Werk aus, Sperre 10 s,
 * Gang nur frisch), eigene Motive (per Code, keine Bilddatei) und Stand-Uhr (in P nach X min, Tipp beendet, ab Werk aus).
 */
class LauncherL48Test {
    private val alle = Selbststeuerung.Wahl(true, true, true)
    private val lange = 100_000L   // letzte Bedienung lange her

    @Test
    fun abWerkAusUndNieOhneWahl() {
        assertFalse(Selbststeuerung.Wahl().irgendeine)
        val z = Selbststeuerung.Zustand()
        assertNull(Selbststeuerung.schritt(z, Selbststeuerung.Wahl(), "P", false, lange, 0))
        assertNull(Selbststeuerung.schritt(z, Selbststeuerung.Wahl(), "D", true, lange, 0))
        assertEquals(0, StandUhr.MINUTEN.first())
        assertFalse(StandUhr.zeigen(0, "P", true, true, Long.MAX_VALUE, 0))
    }

    @Test
    fun wechselNachDundP() {
        val z = Selbststeuerung.Zustand()
        assertNull("erster Wert ist kein Wechsel", Selbststeuerung.schritt(z, alle, "D", null, lange, 0))
        assertEquals(Selbststeuerung.Ziel.START, Selbststeuerung.schritt(z, alle, "P", null, lange, 0))
        assertNull("kein Wechsel, nichts", Selbststeuerung.schritt(z, alle, "P", null, lange, 0))
        assertEquals(Selbststeuerung.Ziel.KARTE, Selbststeuerung.schritt(z, alle, "D", null, lange, 0))
        // null = nicht frisch (Zündung aus) zählt nie als Wechsel, letzter bekannter Wert bleibt
        assertNull(Selbststeuerung.schritt(z, alle, null, null, lange, 0))
        assertNull(Selbststeuerung.schritt(z, alle, "D", null, lange, 0))
        assertEquals(Selbststeuerung.Ziel.START, Selbststeuerung.schritt(z, alle, "P", null, lange, 0))
        // R/N lösen nichts aus
        assertNull(Selbststeuerung.schritt(z, alle, "R", null, lange, 0))
        // nur die gewählte Regel
        assertNull(Selbststeuerung.schritt(z, Selbststeuerung.Wahl(startBeiP = true), "D", null, lange, 0))
    }

    @Test
    fun ladenNachVorn() {
        val z = Selbststeuerung.Zustand()
        assertNull(Selbststeuerung.schritt(z, alle, null, Selbststeuerung.laedt("on", false), lange, 0))
        assertEquals(Selbststeuerung.Ziel.LADEN, Selbststeuerung.schritt(z, alle, null, Selbststeuerung.laedt("charging", null), lange, 0))
        assertNull("lädt weiter: nicht noch einmal", Selbststeuerung.schritt(z, alle, null, true, lange, 0))
        assertEquals(true, Selbststeuerung.laedt(null, true))
        assertEquals(false, Selbststeuerung.laedt("off", null))
        assertNull(Selbststeuerung.laedt(null, null))
        // Laden war beim Start schon an → kein Wechsel
        assertNull(Selbststeuerung.schritt(Selbststeuerung.Zustand(), alle, null, true, lange, 0))
    }

    @Test
    fun sperreWennFahrerBedient() {
        assertEquals(10_000L, Selbststeuerung.SPERRE_MS)
        val z = Selbststeuerung.Zustand()
        Selbststeuerung.schritt(z, alle, "P", null, 50_000, 0)
        // vor 3 s bedient → wartet
        assertNull(Selbststeuerung.schritt(z, alle, "D", null, 50_000, 47_000))
        assertNull(Selbststeuerung.schritt(z, alle, "D", null, 55_000, 47_000))
        // Sperre vorbei, Anlass gilt noch → jetzt
        assertEquals(Selbststeuerung.Ziel.KARTE, Selbststeuerung.schritt(z, alle, "D", null, 57_000, 47_000))
        // Anlass vorbei während der Sperre → verworfen (kein Karten-Sprung mehr in P)
        Selbststeuerung.schritt(z, alle, "P", null, 60_000, 59_000)
        Selbststeuerung.schritt(z, alle, "D", null, 61_000, 59_000)
        val z2 = Selbststeuerung.Zustand()
        Selbststeuerung.schritt(z2, Selbststeuerung.Wahl(karteBeiD = true), "P", null, 60_000, 59_000)
        assertNull(Selbststeuerung.schritt(z2, Selbststeuerung.Wahl(karteBeiD = true), "D", null, 61_000, 59_000))
        assertNull(Selbststeuerung.schritt(z2, Selbststeuerung.Wahl(karteBeiD = true), "P", null, 80_000, 59_000))
    }

    @Test
    fun motive() {
        assertEquals(10, Motive.WERTE.size)
        assertTrue(Motive.WERTE.size in 8..12)
        assertEquals(3, Motive.nummer("motiv-3"))
        assertNull(Motive.nummer("motiv-11"))
        assertNull(Motive.nummer("panoptes"))
        val l = Raster.standard(null)
        assertEquals("motiv-7", Raster.hintergrund(l, "motiv-7").hintergrund)
        assertEquals(l.hintergrund, Raster.hintergrund(l, "motiv-0").hintergrund)
        assertEquals("motiv-10", Raster.fromJson(Raster.toJson(Raster.hintergrund(l, "motiv-10")))!!.hintergrund)
        // Muster höchstens 22 % deckend: Kacheln bleiben lesbar
        assertTrue(Motive.deckung(true) <= 56 && Motive.deckung(false) <= 56)
    }

    @Test
    fun standUhr() {
        val m5 = 5 * 60_000L
        assertTrue(StandUhr.zeigen(5, "P", true, true, m5, 0))
        assertFalse(StandUhr.zeigen(5, "P", true, true, m5 - 1, 0))
        assertFalse("nur in P", StandUhr.zeigen(5, "D", true, true, m5, 0))
        assertFalse("Gang unbekannt", StandUhr.zeigen(5, null, true, true, m5, 0))
        assertFalse("Bild aus", StandUhr.zeigen(5, "P", false, true, m5, 0))
        assertFalse("Feld offen", StandUhr.zeigen(5, "P", true, false, m5, 0))
        assertTrue("nachts dunkler", StandUhr.deckung(true) > StandUhr.deckung(false))
    }

    private fun basis() = if (File("src/main").isDirectory) File(".") else File("panoptes-launcher")
    private fun quelle(n: String) = File(basis(), "src/main/java/at/greenchili/panoptes/launcher/$n").readText().replace("\r\n", "\n")

    @Test
    fun vertrag() {
        val m = quelle("MainActivity.kt")
        assertTrue(m.contains("selbstSteuern(); standUhrPruefen()"))
        assertTrue(m.contains("override fun onUserInteraction()"))
        assertTrue(m.contains("letzteBedienung = System.currentTimeMillis()"))
        val st = m.substringAfter("private fun selbstSteuern()").substringBefore("private fun nachtJetzt()")
        // nur Anzeige und Seitenwechsel: kein Kern-Aufruf, kein Fahrzeugweg
        for (verboten in listOf("client.", "befehl", "command", "klima", "kernSpeichern"))
            assertFalse(verboten, st.lowercase().contains(verboten.lowercase()))
        assertTrue(st.contains("karteMitStatus()"))
        assertTrue(st.contains("seite = 0; zeichneSeite()"))
        assertTrue(st.contains("Kachel.LADEN"))
        assertTrue(st.contains("if (bearbeiten || stopped) return"))
        assertTrue(m.contains("Motive.nummer(layout.hintergrund)?.let { MotivHintergrund(it, pal) } ?: SeitenHintergrund(pal, cockpit)"))
        assertTrue(m.contains("+ Motive.WERTE).map { it to hgName(it) }"))
        val sel = quelle("Selbststeuerung.kt")
        val uhr = quelle("StandUhr.kt")
        val mot = quelle("Motive.kt")
        assertTrue(sel.contains("getBoolean(KEY_D, false), it.getBoolean(KEY_P, false), it.getBoolean(KEY_LADEN, false)"))
        assertTrue(uhr.contains("getInt(KEY, 0)"))
        assertTrue(uhr.contains("setOnClickListener { beenden() }"))
        assertFalse("keine Bilddatei", mot.contains("R.drawable") || mot.contains("BitmapFactory") || mot.contains("assets"))
        for (o in listOf("values", "values-de", "values-fr", "values-it", "values-nl")) {
            val s = File(basis(), "src/main/res/$o/strings.xml").readText()
            for (k in listOf("hg_motiv", "motiv_1", "motiv_10", "selbst_titel", "selbst_hinweis", "selbst_d", "selbst_p", "selbst_laden",
                    "stand_uhr", "stand_uhr_hinweis", "stand_uhr_tipp", "stand_uhr_minuten"))
                assertTrue("$o: $k", s.contains("name=\"$k\""))
        }
    }
}
