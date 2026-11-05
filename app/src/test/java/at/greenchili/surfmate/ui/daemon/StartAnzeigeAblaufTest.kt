package at.greenchili.surfmate.ui.daemon

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.1.38.10: Der echte [StartAblauf] meldet an [StartStatus], die Anzeige liest daraus. Belegt: Zugang ohne Tor
 * (läuft während des Hard-Resets an und ist bereit, die anderen Stufen warten), danach Häkchen der Reihe nach.
 */
class StartAnzeigeAblaufTest {

    private var jetzt = 0L
    private val aufgaben = ArrayList<Pair<Long, () -> Unit>>()

    private fun laufen(bis: Long) {
        while (true) {
            val n = aufgaben.filter { it.first <= bis }.minByOrNull { it.first } ?: break
            aufgaben.remove(n)
            jetzt = n.first
            n.second()
        }
        jetzt = bis
    }

    @After
    fun weg() = StartStatus.zuruecksetzen()

    private fun zustand(s: String): String {
        val st = StartStatus.json(jetzt).getJSONArray("stufen")
        for (i in 0 until st.length()) if (st.getJSONObject(i).getString("stufe") == s) return st.getJSONObject(i).getString("zustand")
        return "fehlt"
    }

    @Test
    fun zugangBereitWaehrendDasTorZuIstDannDerRestDerReiheNach() {
        var torOffen = false
        val stufen = listOf(StartStufe.ZUGANG, StartStufe.OBERFLAECHE, StartStufe.WAECHTER)
        StartStatus.beginne(stufen, jetzt)
        StartAblauf(
            stufen = stufen, bootSchutzMs = 0L,
            plane = { ms, r -> aufgaben.add(Pair(jetzt + ms, r)) },
            jetzt = { jetzt },
            toroffen = { torOffen },
            aktion = { },
            pruefen = { s, rueck -> rueck(s == StartStufe.ZUGANG || jetzt >= 15_000L) },
            melde = { s, z -> StartStatus.melde(s, z, jetzt) }
        ).starten()

        laufen(8_000L)                       // Hard-Reset läuft noch
        assertEquals("bereit", zustand("zugang"))
        assertEquals("startet", zustand("oberflaeche"))
        assertEquals("startet", zustand("waechter"))
        assertTrue(StartStatus.laeuft(jetzt))

        torOffen = true
        laufen(60_000L)
        assertEquals("bereit", zustand("oberflaeche"))
        assertEquals("bereit", zustand("waechter"))
        assertFalse(StartStatus.laeuft(jetzt))
        assertTrue(StartStatus.json(jetzt).getBoolean("fertig"))
    }
}
