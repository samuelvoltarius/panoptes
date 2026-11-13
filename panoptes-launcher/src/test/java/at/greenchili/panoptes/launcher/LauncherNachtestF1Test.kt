package at.greenchili.panoptes.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** 0.1.41 Nachtest: F1 (Absturz RejectedExecutionException nach Activity-Neustart), N1 (Musik-Hinweis), N2 (Uhr fehlt). */
class LauncherNachtestF1Test {

    private fun datei(rel: String): File { val f = File(rel); return if (f.exists()) f else File("panoptes-launcher/$rel") }
    private fun lies(rel: String): String = datei(rel).readText().replace("\r\n", "\n")
    private val quelle = "src/main/java/at/greenchili/panoptes/launcher/"
    private val m by lazy { lies(quelle + "MainActivity.kt") }
    private fun block(von: String, bis: String) = m.substring(m.indexOf(von), m.indexOf(bis, m.indexOf(von)))

    // ---------- F1 ----------

    @Test
    fun f1BeendeterIoNimmtNichtsMehrAnStattAbzustuerzen() {
        val io = SichererIo()
        val n = AtomicInteger()
        val fertig = java.util.concurrent.CountDownLatch(1)
        assertTrue(io.execute(Runnable { n.incrementAndGet(); fertig.countDown() }))
        fertig.await(2, java.util.concurrent.TimeUnit.SECONDS)
        io.shutdownNow()
        assertTrue(io.isShutdown)
        // vorher: RejectedExecutionException auf dem Hauptthread → Launcher-Absturz
        assertFalse("nach dem Beenden verworfen, kein Absturz", io.execute(Runnable { n.incrementAndGet() }))
        assertEquals(1, n.get())
        val tot = SichererIo { false }
        assertFalse("tote Activity: nichts annehmen", tot.execute(Runnable { n.incrementAndGet() }))
        tot.shutdown()
    }

    @Test
    fun f1AlleExecutorUndRueckrufeDerActivityAbgesichert() {
        // kein ungeschützter Executor und kein roher Main-Handler mehr in der Activity
        assertFalse(m.contains("Executors.newSingleThreadExecutor()"))
        assertTrue(m.contains("private val io = SichererIo()"))
        assertTrue(m.contains("private val klimaIo = SichererIo()"))
        assertTrue(m.contains("private val layoutIo = SichererIo()"))
        // Rückrufe (ladeApps → baueAlles usw.) nur in einer lebenden Activity
        assertTrue(m.contains("private val main: Handler = LebensHandler { !isFinishing && !isDestroyed }"))
        assertFalse(m.contains("Handler(Looper.getMainLooper())"))
        val l = lies(quelle + "Lebenszeit.kt")
        assertTrue(l.contains("override fun dispatchMessage(msg: Message)") && l.contains("if (lebt()) super.dispatchMessage(msg)"))
        assertTrue(l.contains("catch (_: RejectedExecutionException)") && l.contains("ex.isShutdown"))
        // onDestroy beendet weiter alle drei; das Speichern des Layouts läuft noch zu Ende
        val d = block("override fun onDestroy()", "super.onDestroy()")
        assertTrue(d.contains("io.shutdownNow()") && d.contains("klimaIo.shutdownNow()") && d.contains("layoutIo.shutdown()"))
        // Schnellleiste/StatusLeiste beenden ihren Executor nie und prüfen ihren Merker im Rückruf (gleiches Muster ohne Absturz)
        for (f in listOf("Schnellleiste.kt", "StatusLeiste.kt")) assertFalse(f, lies(quelle + f).contains("shutdown"))
    }

    // ---------- N1, N2 ----------

    @Test
    fun n1MusikHinweisWirdNichtAbgeschnitten() {
        val k = block("private fun medienKarte(", "private fun ordnerKachel(")
        assertTrue(k.contains("quelle.addView(qName, ui.lp(0, -2, 1f))"))
        assertTrue(k.contains("qName.maxLines = if (daten) 2 else if (MusikKartePlatz.flach(e.h)) 2 else 4"))
        for (d in listOf("values", "values-de", "values-fr", "values-it", "values-nl")) {
            val x = lies("src/main/res/$d/strings.xml")
            val t = Regex("name=\"medien_zugriff_hinweis\">([^<]*)<").find(x)!!.groupValues[1]
            assertTrue("$d: kurz genug für 4 Zeilen", t.length in 20..110)
        }
    }

    @Test
    fun n2UhrIstNachDemNeuaufbauSofortDa() {
        val s = block("private fun statusLeiste()", "private fun statusIcon(")
        assertTrue("sofort gefüllt", s.contains("statusAktualisierer += fuellen\n        fuellen()"))
        val b = block("private fun baueAlles()", "private fun zeigeHinweis(")
        assertTrue("nächster Takt aktualisiert sicher", b.indexOf("taktStand = \"\"") > b.indexOf("statusAktualisierer.clear()"))
    }
}
