package at.greenchili.panoptes.launcher

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 0.1.42 E2E G6 (Ruhe-Regel): Waben-Motiv leiser und dünner als die übrigen Motive, vor allem im Nacht-Design. */
class LauncherWabenRuheTest {
    private fun basis() = if (File("src/main").isDirectory) File(".") else File("panoptes-launcher")

    @Test
    fun wabenLeiserAlsAndereMotive() {
        assertTrue(Motive.wabenDeckung(false) < Motive.deckung(false))
        assertTrue(Motive.wabenDeckung(true) < Motive.deckung(true))
        assertTrue("dunkel höchstens ~9 %", Motive.wabenDeckung(false) <= 24)
        assertTrue(Motive.wabenStrich(10f) < 10f * 0.35f)
        assertTrue(Motive.wabenStrich(1f) >= 1f)
        val m = File(basis(), "src/main/java/at/greenchili/panoptes/launcher/Motive.kt").readText().replace("\r\n", "\n")
        assertTrue(m.contains("strich.color = farbe(Motive.wabenDeckung(p.hell)); strich.strokeWidth = Motive.wabenStrich(e)"))
    }
}
