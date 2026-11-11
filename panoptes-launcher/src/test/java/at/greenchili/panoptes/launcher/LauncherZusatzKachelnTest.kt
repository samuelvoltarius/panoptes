package at.greenchili.panoptes.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 0.1.41 (LK): Kacheln Reifen, letzte Fahrt, Tempo – nur Kern-Daten, fehlend = „—“, nie erfunden. */
class LauncherZusatzKachelnTest {

    private fun lies(rel: String): String {
        var f = File(rel); if (!f.exists()) f = File("panoptes-launcher/$rel")
        return f.readText().replace("\r\n", "\n")
    }

    @Test
    fun reifenAusDemSummary() {
        val s = LauncherContract.parseSummary("""{"tyres":{"flBar":2.4,"frBar":2.1,"rlBar":null,"rrBar":2.5,"loBar":2.3,"hiBar":3.0,
            "warn":{"fl":null,"fr":"unterdruck","rl":"erfunden","rr":"leck_langsam"}}}""")
        val r = s.reifen!!
        assertEquals(listOf(null, "unterdruck", null, "leck_langsam"), r.warn)
        assertEquals(listOf(null, "unterdruck", null, "leck_langsam"), ZusatzKacheln.warnungen(r))
        assertEquals(false, ZusatzKacheln.reifenOk(r))
        // ältere Kerne ohne warn: Warnung nur aus den Kern-Grenzen
        val alt = LauncherContract.parseSummary("""{"tyres":{"flBar":2.2,"frBar":2.4,"rlBar":3.2,"rrBar":2.4,"loBar":2.3,"hiBar":3.0}}""").reifen!!
        assertEquals(listOf("unterdruck", null, "ueberdruck", null), ZusatzKacheln.warnungen(alt))
        val gut = LauncherContract.parseSummary("""{"tyres":{"flBar":2.4,"frBar":2.4,"rlBar":2.4,"rrBar":2.4,"loBar":2.3,"hiBar":3.0}}""").reifen
        assertEquals(true, ZusatzKacheln.reifenOk(gut))
        assertNull("ein Druck fehlt → nicht „alles ok“", ZusatzKacheln.reifenOk(r.copy(vr = 2.4, warn = listOf(null, null, null, null))))
        assertNull(ZusatzKacheln.reifenOk(null))
        assertNull(LauncherContract.parseSummary("{}").reifen)
    }

    @Test
    fun letzteFahrtAusHistory() {
        val h = LauncherContract.parseHistory("""{"trips":[{"distanceKm":12.3,"durationMin":25,"whPerKm":158}],"charging":[]}""")
        assertEquals(12.3, h.lastTripKm!!, 0.0)
        assertEquals(15.8, ZusatzKacheln.kwhPro100(h.lastTripWhPerKm)!!, 0.001)
        assertEquals("25 min", ZusatzKacheln.dauer(h.lastTripMin))
        assertEquals("1 h 05 min", ZusatzKacheln.dauer(65.0))
        val leer = LauncherContract.parseHistory("""{"trips":[{"distanceKm":3.1,"durationMin":null,"whPerKm":null}]}""")
        assertNull(leer.lastTripMin); assertNull(ZusatzKacheln.kwhPro100(leer.lastTripWhPerKm)); assertNull(ZusatzKacheln.dauer(null))
        assertNull("unplausibel", ZusatzKacheln.kwhPro100(0.0))
        assertNull(LauncherContract.parseHistory("{}").lastTripKm)
    }

    @Test
    fun tempoOhneErfundenesLimit() {
        assertEquals("88", ZusatzKacheln.tempo(87.6))
        assertNull(ZusatzKacheln.tempo(null))
        assertNull(ZusatzKacheln.tempo(-1.0))
        val z = lies("src/main/java/at/greenchili/panoptes/launcher/ZusatzKacheln.kt")
        assertFalse("kein Limit ohne Kern-Wert", z.contains("limit", ignoreCase = false) && z.contains("speedLimit"))
        assertFalse("keine Befehle, nur lesen", z.contains("client.") || z.contains("POST"))
    }

    @Test
    fun eingehaengtUndGespeichert() {
        assertEquals(listOf("reifen", "fahrt", "tempo"), listOf(Kachel.REIFEN, Kachel.FAHRT, Kachel.TEMPO).map { it.name.lowercase() })
        val m = lies("src/main/java/at/greenchili/panoptes/launcher/MainActivity.kt")
        assertTrue(m.contains("Kachel.REIFEN -> zusatz.reifen(gross, e.h)"))
        assertTrue(m.contains("Kachel.FAHRT -> zusatz.fahrt(gross)"))
        assertTrue(m.contains("Kachel.TEMPO -> zusatz.tempo(gross, e.h)"))
        // Layout hin und zurück behält die Kacheln
        val l = Raster.standard(null)
        var neu = l
        for (k in listOf(Kachel.REIFEN, Kachel.FAHRT, Kachel.TEMPO)) neu = Raster.widgetHinzufuegen(Raster.seiteHinzufuegen(neu), neu.seiten.size, k)
        val zurueck = Raster.fromJson(Raster.toJson(neu))
        for (k in listOf(Kachel.REIFEN, Kachel.FAHRT, Kachel.TEMPO)) assertTrue(k.name, Raster.hatWidget(zurueck!!, k))
    }

    /** 0.1.41 E2E G5: nichts abschneiden – kompakt bei einer Zeile Höhe, sonst als Ganzes verkleinern. */
    @Test
    fun inhaltPasstSichDerKachelAn() {
        assertEquals(1f, Einpassen.massstab(100, 120), 0f)
        assertEquals(1f, Einpassen.massstab(0, 120), 0f)
        assertEquals(0.75f, Einpassen.massstab(160, 120), 0.001f)
        assertEquals("nie kleiner als MIN", Einpassen.MIN, Einpassen.massstab(1000, 100), 0f)
        val z = lies("src/main/java/at/greenchili/panoptes/launcher/ZusatzKacheln.kt")
        assertEquals("Reifen, Fahrt, Tempo eingepasst", 3, z.split("return Einpassen(ctx, c)").size - 1)
        assertFalse("keine abgeschnittene Zustandszeile", z.contains("maxLines = 2"))
        assertTrue("2×1: vier Räder in einer Reihe", z.contains("columnCount = if (h <= 1) 4 else 2"))
        val t = z.substring(z.indexOf("fun tempo(gross"))
        assertTrue("2×1: km/h neben der Zahl", t.contains("if (h <= 1)") && t.contains("addView(zahl); addView(einheit"))
        val m = lies("src/main/java/at/greenchili/panoptes/launcher/MainActivity.kt")
        assertTrue("Dichtewechsel → sauber neu", m.contains("newConfig.densityDpi != dichteDpi) { recreate(); return }"))
        assertTrue(m.contains("dichteDpi = resources.configuration.densityDpi"))
    }

    @Test
    fun texteInFuenfSprachen() {
        val namen = listOf("widget_reifendruck", "widget_fahrt", "widget_tempo", "reifen_ok", "warn_leck_schnell", "warn_leck_langsam",
            "warn_unterdruck", "warn_ueberdruck", "warn_signal", "rad_vl", "rad_vr", "rad_hl", "rad_hr")
        for (d in listOf("values", "values-de", "values-fr", "values-it", "values-nl")) {
            val s = lies("src/main/res/$d/strings.xml")
            for (n in namen) assertTrue("$d/$n", s.contains("<string name=\"$n\">"))
        }
    }
}
