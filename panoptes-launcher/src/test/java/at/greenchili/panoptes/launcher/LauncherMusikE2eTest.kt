package at.greenchili.panoptes.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.1.41 Nachbesserung aus dem E2E (E2E_0141.md):
 * - M1: Kachel fror ein → Android meldet Änderungen (OnActiveSessionsChangedListener + MediaController.Callback),
 *   die Kachel zeichnet sofort neu; ruht der Sekundentakt, liest die Abfrage die Medien mit.
 * - G4: Zugriff während des Laufs erteilt → bei jeder Rückkehr (onResume) neu prüfen und anmelden.
 * - M3: „Zugriff erlauben“ bei „Größer“ abgeschnitten → Knopf (Symbol + Text) direkt unter dem Titel, ohne feste
 *   Höhe; Cover/Titel/Balken ohne Zugriff ausgeblendet; auch der Hinweistext öffnet die Seite.
 */
class LauncherMusikE2eTest {
    private fun basis() = if (File("src/main").isDirectory) File(".") else File("panoptes-launcher")
    private fun quelle(name: String) =
        File(basis(), "src/main/java/at/greenchili/panoptes/launcher/$name").readText().replace("\r\n", "\n")

    @Test
    fun m1BeobachterMeldetAenderungen() {
        val b = quelle("MedienBeobachter.kt")
        assertTrue(b.contains("addOnActiveSessionsChangedListener(sitzungen, ich, main)"))
        assertTrue(b.contains("removeOnActiveSessionsChangedListener(sitzungen)"))
        assertTrue(b.contains("it.registerCallback(rueckruf, main)"))
        assertTrue(b.contains("it.unregisterCallback(rueckruf)"))
        for (f in listOf("onPlaybackStateChanged", "onMetadataChanged", "onSessionDestroyed")) assertTrue(f, b.contains("override fun $f"))
        assertTrue("Telecom nicht beobachten", b.contains("it.packageName !in MedienWahl.AUSGESCHLOSSEN"))
        val m = quelle("MainActivity.kt")
        assertTrue(m.contains("MedienBeobachter(this, main) { medienNeu() }"))
        val neu = m.substringAfter("private fun medienNeu()").substringBefore("override fun onStop()")
        assertTrue(neu.contains("medien = m") && neu.contains("aktualisierer.forEach { it() }") && neu.contains("statusAktualisierer.forEach { it() }"))
        assertTrue(m.substringAfter("override fun onStop()").substringBefore("}").contains("medienBeobachter.stop()"))
        assertTrue("Abfrage liest Medien, wenn der Takt ruht",
            m.contains("if (!Ruhe.taktLaeuft(stopped, bildAn, fokus)) medien = MedienQuelle.aktuell(this, MusikStart.letztes(this))"))
    }

    @Test
    fun g4ZugriffBeiRueckkehrNeuPruefen() {
        val m = quelle("MainActivity.kt")
        val r = m.substringAfter("override fun onResume()").substringBefore("private fun medienNeu()")
        assertTrue(r.contains("medienBeobachter.start()") && r.contains("medienNeu()"))
        val b = quelle("MedienBeobachter.kt")
        // wiederholbar: ohne Zugriff nicht angemeldet, nächster Versuch beim nächsten onResume
        assertTrue(b.contains("if (angemeldet) return true"))
        assertTrue(b.contains("if (!MedienQuelle.zugriffErlaubt(ctx)) return false"))
    }

    @Test
    fun m3KnopfImmerSichtbar() {
        val m = quelle("MainActivity.kt")
        val k = m.substringAfter("private fun medienKarte(").substringBefore("private fun ordnerKachel(")
        val knopf = k.indexOf("R.string.medien_zugriff_erlauben")
        assertTrue(knopf > 0)
        assertTrue("Knopf vor Cover und Tasten", knopf < k.indexOf("val cover") && knopf < k.indexOf("val tasten"))
        assertTrue("keine feste Höhe", k.contains("c.addView(zugriff, ui.lp(-1, -2, oben = 8))"))
        assertFalse(k.contains("c.addView(zugriff, ui.lp(-1, ui.dp(56)"))
        assertTrue(k.contains("coverRahmen.visibility = ohne; titel.visibility = ohne; kuenstler.visibility = ohne; balken.visibility = ohne"))
        assertTrue("Hinweistext tippbar", k.contains("quelle.setOnClickListener(if (erlaubt) null else View.OnClickListener { zugriffSeite() })"))
    }
}
