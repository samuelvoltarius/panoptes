package at.greenchili.surfmate.launcher

import at.greenchili.surfmate.logging.LauncherEreignisse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 0.1.38.3 (fix-waechter-skript), Auto 04.11.2026 beim Einspielen von 0.1.38.3:
 * ```
 * 12:15:35 SKRIPT_SCHREIBEN art=waechter   (zweimal)
 * 12:15:37 ADB_ABBRUCH gen=1 alter=62197ms deadline 60000ms exceeded by adb#3
 * 12:15:37 SKRIPT_FEHLER art=waechter Execution failed: null
 * ```
 * Ein anderer, hängender adb-Befehl riss seine Deadline; die gemeinsame Verbindung wurde geschlossen und der
 * laufende Wächter-Schreiber riss ohne Text ab. start_acc_sentry.sh blieb auf dem Stand vor dem Update, der Daemon
 * lief über den Rückfall ohne Watchdog, bis die Selbstheilung einen anhängte.
 */
class SkriptSchreibenWiederholungTest {

    private class Ziel : AdbShellExecutor.ShellCallback {
        val ok = mutableListOf<String>()
        val fehler = mutableListOf<String>()
        override fun onSuccess(output: String) { ok.add(output) }
        override fun onError(error: String) { fehler.add(error) }
    }

    /** Antworten der Reihe nach: null = Erfolg, sonst dieser Fehlertext. */
    private fun versuche(vararg antworten: String?): Pair<AtomicInteger, (AdbShellExecutor.ShellCallback) -> Unit> {
        val n = AtomicInteger()
        return n to { cb ->
            val a = antworten[n.getAndIncrement()]
            if (a == null) cb.onSuccess("SKRIPT_OK /data/user_de/0/com.android.shell/surfmate/start_acc_sentry.sh")
            else cb.onError(a)
        }
    }

    @Test
    fun abgerisseneVerbindungBeimSchreibenWirdEinmalWiederholt() {
        LauncherEreignisse.entnehmen()
        val abbruch = "Execution failed: " + AdbShellExecutor.fehlerText(EOFException())
        val (n, versuch) = versuche(abbruch, null)
        val ziel = Ziel()
        DaemonLauncher.skriptSchreibenMitWiederholung("waechter", versuch, ziel)
        assertEquals(2, n.get())
        assertEquals("Skript ist neu geschrieben, kein Rückfall ohne Watchdog", 1, ziel.ok.size)
        assertTrue(ziel.fehler.isEmpty())
        val ereignisse = LauncherEreignisse.entnehmen().joinToString("\n")
        assertTrue(ereignisse, ereignisse.contains("SKRIPT_WIEDERHOLT"))
        assertTrue(ereignisse, ereignisse.contains("art=waechter Execution failed: EOFException: ohne Text"))
    }

    @Test
    fun getoeteteSchreibShellWirdAuchWiederholt() {
        val (n, versuch) = versuche("Exit code 137: ", null)
        val ziel = Ziel()
        DaemonLauncher.skriptSchreibenMitWiederholung("waechter", versuch, ziel)
        assertEquals(2, n.get())
        assertEquals(1, ziel.ok.size)
    }

    @Test
    fun echterSkriptfehlerWirdNichtWiederholt() {
        val (n, versuch) = versuche("Exit code 3: SKRIPT_FEHLER /x/start_acc_sentry.sh rc=2 zeilen=100/265", null)
        val ziel = Ziel()
        DaemonLauncher.skriptSchreibenMitWiederholung("waechter", versuch, ziel)
        assertEquals(1, n.get())
        assertEquals(1, ziel.fehler.size)
        assertTrue(ziel.ok.isEmpty())
    }

    @Test
    fun hoechstensEineWiederholung() {
        val (n, versuch) = versuche("Execution failed: EOFException: ohne Text", "Execution failed: zweiter Abbruch")
        val ziel = Ziel()
        DaemonLauncher.skriptSchreibenMitWiederholung("kamera", versuch, ziel)
        assertEquals(2, n.get())
        assertEquals(listOf("Execution failed: zweiter Abbruch"), ziel.fehler)
    }

    @Test
    fun fehlerTextNieNull() {
        val t = AdbShellExecutor.fehlerText(EOFException())
        assertEquals("EOFException: ohne Text", t)
        assertFalse(t.contains("null"))
        assertEquals("IOException: Stream closed ← SocketException: Connection reset",
            AdbShellExecutor.fehlerText(IOException("Stream closed", SocketException("Connection reset"))))
    }

    /** Zwei Starts in derselben Sekunde (12:15:35): genau einer bekommt den Guard. */
    @Test
    fun startGuardIstAtomar() {
        val feld = AtomicLong()
        val gewonnen = AtomicInteger()
        val los = CountDownLatch(1)
        val fertig = CountDownLatch(8)
        repeat(8) {
            Thread {
                los.await()
                if (DaemonLauncher.guardNehmen({ feld.get() }, { feld.set(it) })) gewonnen.incrementAndGet()
                fertig.countDown()
            }.start()
        }
        los.countDown()
        fertig.await()
        assertEquals(1, gewonnen.get())
        assertTrue(feld.get() > 0)
    }
}
