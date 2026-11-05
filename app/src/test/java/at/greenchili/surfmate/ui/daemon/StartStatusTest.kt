package at.greenchili.surfmate.ui.daemon

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.1.38.10: eine Stelle für den Start-Zustand je Stufe (Anzeige im Ladebildschirm und auf der Startseite). */
class StartStatusTest {

    @After
    fun weg() = StartStatus.zuruecksetzen()

    @Test
    fun ohneStartLeer() {
        assertTrue(StartStatus.stand(0).isEmpty())
        assertFalse(StartStatus.laeuft(0))
        val j = StartStatus.json(0)
        assertFalse(j.getBoolean("aktiv"))
        assertFalse(j.getBoolean("fertig"))
        assertEquals(0, j.getJSONArray("stufen").length())
    }

    @Test
    fun reihenfolgeFestUndNurGemeldeteStufen() {
        StartStatus.beginne(listOf(StartStufe.WAECHTER, StartStufe.OBERFLAECHE), 1000)
        assertEquals(listOf(StartStufe.OBERFLAECHE, StartStufe.WAECHTER), StartStatus.stand(1000).map { it.first })
        StartStatus.melde(StartStufe.ZUGANG, StufeZustand.BEREIT, 100)   // gibt es ohne Tailscale nicht
        assertEquals(2, StartStatus.stand(1000).size)
    }

    @Test
    fun bereitUndFertig() {
        StartStatus.beginne(listOf(StartStufe.ZUGANG, StartStufe.OBERFLAECHE, StartStufe.WAECHTER, StartStufe.REST), 0)
        assertTrue(StartStatus.laeuft(10))
        StartStatus.melde(StartStufe.ZUGANG, StufeZustand.BEREIT, 100)
        StartStatus.melde(StartStufe.OBERFLAECHE, StufeZustand.BEREIT, 100)
        StartStatus.melde(StartStufe.WAECHTER, StufeZustand.BEREIT, 100)
        StartStatus.melde(StartStufe.REST, StufeZustand.STARTET_NICHT, 100)
        assertFalse(StartStatus.laeuft(10))
        val j = StartStatus.json(500)
        assertTrue(j.getBoolean("aktiv"))
        assertTrue(j.getBoolean("fertig"))
        assertEquals(500L, j.getLong("seitMs"))
        val st = j.getJSONArray("stufen")
        assertEquals("zugang", st.getJSONObject(0).getString("stufe"))
        assertEquals("bereit", st.getJSONObject(0).getString("zustand"))
        assertEquals("startet_nicht", st.getJSONObject(3).getString("zustand"))
        assertEquals(100L - 0L, st.getJSONObject(0).getLong("abMs"))
    }

    @Test
    fun nachZeitlimitStartetNicht() {
        StartStatus.beginne(listOf(StartStufe.OBERFLAECHE, StartStufe.WAECHTER), 0)
        StartStatus.melde(StartStufe.OBERFLAECHE, StufeZustand.BEREIT, 100)
        assertEquals(StufeZustand.STARTET, StartStatus.stand(StartStatus.ZEITLIMIT_MS - 1)[1].second)
        assertEquals(StufeZustand.STARTET_NICHT, StartStatus.stand(StartStatus.ZEITLIMIT_MS)[1].second)
        assertEquals(StufeZustand.BEREIT, StartStatus.stand(StartStatus.ZEITLIMIT_MS)[0].second)
        assertFalse(StartStatus.laeuft(StartStatus.ZEITLIMIT_MS))
    }

    /** Der Anzeige-Rückfall darf nie vor den Zeitlimits des gestaffelten Starts greifen (die meldet der Ablauf selbst). */
    @Test
    fun rueckfallLaengerAlsDerLaengsteAblauf() {
        val ablauf = DaemonStartPlan.BOOT_STABIL_MS + StartStufe.values().sumOf { DaemonStartPlan.zeitlimitMs(it) } +
            StartStufe.values().maxOf { DaemonStartPlan.beobachtungMs(it) }
        assertTrue("$ablauf < ${StartStatus.ZEITLIMIT_MS}", ablauf < StartStatus.ZEITLIMIT_MS)
    }

    @Test
    fun zeitJeStufe() {
        StartStatus.beginne(listOf(StartStufe.OBERFLAECHE, StartStufe.WAECHTER), 1_000)
        StartStatus.melde(StartStufe.OBERFLAECHE, StufeZustand.BEREIT, 9_000)
        StartStatus.melde(StartStufe.OBERFLAECHE, StufeZustand.BEREIT, 12_000)   // Wiederholung: Zeit bleibt
        val st = StartStatus.json(20_000).getJSONArray("stufen")
        assertEquals(8_000L, st.getJSONObject(0).getLong("abMs"))
        assertEquals(0L, st.getJSONObject(1).getLong("abMs"))
    }

    @Test
    fun neuerStartSetztZurueck() {
        StartStatus.beginne(listOf(StartStufe.OBERFLAECHE), 0)
        StartStatus.melde(StartStufe.OBERFLAECHE, StufeZustand.BEREIT, 100)
        StartStatus.beginne(listOf(StartStufe.OBERFLAECHE, StartStufe.WAECHTER), 5000)
        assertTrue(StartStatus.stand(5000).all { it.second == StufeZustand.STARTET })
    }
}
