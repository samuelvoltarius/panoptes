package at.greenchili.panoptes.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.1.38.6 (Emulator 1280×720, 160 dpi): Auf der Startseite zeigten Akku und Tempo nur das erste Zeichen (100 % → „1“,
 * 86 % → „8“). Ursache: `addView(zahl)` ohne LayoutParams in einer senkrechten Box nimmt die schmale Breite der Box
 * (MATCH_PARENT) und schneidet die einzeilige Zahl ab. Die Zahlen-Views brauchen WRAP_CONTENT.
 */
class LauncherZahlenBreiteTest {

    private fun main() = File("src/main/java/at/greenchili/panoptes/launcher/MainActivity.kt").readText().replace("\r\n", "\n")

    @Test
    fun akkuUndTempoBekommenWrapContent() {
        val m = main()
        assertTrue(m.contains("akkuBox.addView(soc, LinearLayout.LayoutParams(-2, -2))"))
        assertTrue(m.contains("tempoBox.addView(tempo, LinearLayout.LayoutParams(-2, -2))"))
        assertFalse("Akku-Zahl ohne LayoutParams", m.contains("akkuBox.addView(soc)"))
        assertFalse("Tempo-Zahl ohne LayoutParams", m.contains("tempoBox.addView(tempo)"))
    }

    @Test
    fun zahlenViewsEinerSenkrechtenBoxHabenImmerLayoutParams() {
        // Jede mit ui.zahl(...) erzeugte einzeilige Zahl, die in eine Box mit "…Box" im Namen gelegt wird, kommt mit Maßen
        val m = main()
        val zahlen = Regex("""val (\w+) = ui\.zahl\(""").findAll(m).map { it.groupValues[1] }.toSet()
        assertTrue(zahlen.contains("soc") && zahlen.contains("tempo"))
        for (z in zahlen) {
            assertFalse("$z ohne LayoutParams in einer Box", Regex("""\b\w+Box\.addView\($z\)""").containsMatchIn(m))
        }
    }
}
