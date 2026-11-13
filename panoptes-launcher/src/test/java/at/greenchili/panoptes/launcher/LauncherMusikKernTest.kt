package at.greenchili.panoptes.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * M1 (13.11.): Musik-Karte am Surf ohne Titel, „Zugriff erlauben“ führte am BYD in die IVI-Fehlermeldung.
 * Jetzt Titel/Interpret über den Kern (KernMusik), und am BYD nie die gesperrte Android-Seite.
 */
class LauncherMusikKernTest {
    private fun basis() = if (File("src/main").isDirectory) File(".") else File("panoptes-launcher")
    private fun quelle(n: String) = File(basis(), "src/main/java/at/greenchili/panoptes/launcher/$n").readText().replace("\r\n", "\n")
    private val main get() = quelle("MainActivity.kt")

    @Test
    fun antwortDesKerns() {
        val s = KernMusik.parse("""{"ok":true,"lesbar":true,"paket":"com.byd.carplay.ui","titel":"Bohemian Rhapsody","kuenstler":"Queen","spielt":true}""", 1000)!!
        assertTrue(s.lesbar); assertEquals("com.byd.carplay.ui", s.paket); assertEquals("Bohemian Rhapsody", s.titel)
        assertEquals("Queen", s.kuenstler); assertTrue(s.spielt)
        val ohne = KernMusik.parse("""{"ok":true,"lesbar":true,"paket":null}""", 1000)!!
        assertNull(ohne.paket)
        val k = KernMusik.parse("""{"ok":true,"lesbar":true,"paket":"x","titel":null,"kuenstler":"  "}""", 1000)!!
        assertNull(k.titel); assertNull("leer = nichts", k.kuenstler)
        assertFalse(KernMusik.parse("""{"ok":true,"lesbar":false}""", 1000)!!.lesbar)
        assertNull(KernMusik.parse("""{"ok":false,"grund":"nicht_launcher"}""", 1000))
        assertNull(KernMusik.parse(null, 1000)); assertNull(KernMusik.parse("kaputt", 1000))
    }

    @Test
    fun frischUndAlsWiedergabe() {
        val s = KernMusik.Stand(true, "com.byd.carplay.ui", "T", "A", true, 1000)
        assertTrue(KernMusik.frisch(s, 1000 + KernMusik.FRISCH_MS))
        assertFalse("zu alt", KernMusik.frisch(s, 1001 + KernMusik.FRISCH_MS))
        assertFalse("nicht lesbar", KernMusik.frisch(s.copy(lesbar = false), 1000))
        assertFalse(KernMusik.frisch(null, 1000))
        val k = KernMusik { """{"ok":true,"lesbar":true,"paket":"com.byd.carplay.ui","titel":"T","kuenstler":"A","spielt":true}""" }
        val fertig = CountDownLatch(1)
        k.anstossen(System.currentTimeMillis(), null) { fertig.countDown() }
        assertTrue(fertig.await(5, TimeUnit.SECONDS))
        val w = k.wiedergabe(System.currentTimeMillis())
        assertNotNull(w); assertEquals("T", w!!.titel); assertEquals("A", w.kuenstler); assertTrue(w.spielt)
        assertNull("Tasten gehen als Medientasten", w.controller); assertNull(w.cover)
        assertTrue(k.liefert(System.currentTimeMillis()))
    }

    @Test
    fun abfrageGedrosselt() {
        var n = 0
        val k = KernMusik { n++; """{"ok":true,"lesbar":true,"paket":null}""" }
        val l = CountDownLatch(1)
        k.anstossen(10_000, null) { l.countDown() }
        assertTrue(l.await(5, TimeUnit.SECONDS))
        k.anstossen(10_000 + KernMusik.ABSTAND_MS - 1, null)
        Thread.sleep(200)
        assertEquals("innerhalb des Abstands keine zweite Abfrage", 1, n)
    }

    @Test
    fun modusUndBydSperre() {
        assertEquals(MusikAnzeige.Modus.LOKAL, MusikAnzeige.modus(erlaubt = true, kernLiefert = true, byd = true))
        assertEquals(MusikAnzeige.Modus.KERN, MusikAnzeige.modus(false, true, true))
        assertEquals(MusikAnzeige.Modus.KERN, MusikAnzeige.modus(false, true, false))
        assertEquals("BYD ohne Kern: kein Knopf", MusikAnzeige.Modus.OHNE_TITEL, MusikAnzeige.modus(false, false, true))
        assertEquals(MusikAnzeige.Modus.ZUGRIFF, MusikAnzeige.modus(false, false, false))
        assertFalse(MusikAnzeige.androidSeite(byd = true)); assertTrue(MusikAnzeige.androidSeite(byd = false))
        assertTrue(MusikAnzeige.BYD_PAKETE.containsAll(listOf("com.byd.carplay.ui", "com.byd.mediacenter")))
    }

    @Test
    fun keinKnopfInDieFehlermeldung() {
        val seite = main.substringAfter("private fun zugriffSeite() {").substringBefore("\n    }\n")
        assertTrue("Sperre vor dem Intent", seite.indexOf("if (!MusikAnzeige.androidSeite(byd))") in 0 until seite.indexOf("ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        // die Android-Seite wird nur in zugriffSeite geöffnet
        assertEquals(1, Regex("ACTION_NOTIFICATION_LISTENER_SETTINGS").findAll(main).count())
        val erkl = main.substringAfter("private fun zugriffErklaeren() {").substringBefore("private fun zugriffSeite()")
        assertTrue(erkl.contains("R.string.medien_zugriff_byd"))
        val karte = main.substringAfter("private fun medienKarte(").substringBefore("private fun ordnerKachel(")
        assertTrue(karte.contains("val modus = MusikAnzeige.modus(erlaubt, kernMusik.liefert(System.currentTimeMillis()), byd)"))
        assertTrue("Knopf nur im Modus ZUGRIFF", karte.contains("zugriff.visibility = if (knopf) View.VISIBLE else View.GONE"))
        assertTrue(karte.contains("MusikAnzeige.Modus.OHNE_TITEL -> R.string.medien_kern_fehlt"))
        // alle Lesestellen gehen über medienLesen (Kern als Ersatz)
        assertFalse(main.contains("medien = MedienQuelle.aktuell("))
        assertTrue(main.contains("kernMusik.anstossen(jetzt, letztes)"))
        assertTrue(quelle("PanoptesClient.kt").contains("\"/api/launcher/v1/musik\""))
        // nie per Befehl erteilen
        for (f in listOf("allow_listener", "appops", "settings put")) assertFalse(f, main.contains(f) || quelle("KernMusik.kt").contains(f))
    }

    @Test
    fun texteInFuenfSprachen() {
        for (ordner in listOf("values", "values-de", "values-fr", "values-it", "values-nl")) {
            val x = File(basis(), "src/main/res/$ordner/strings.xml").readText()
            for (n in listOf("medien_kern_fehlt", "medien_zugriff_byd")) {
                val t = Regex("name=\"$n\">([^<]*)<").find(x)?.groupValues?.get(1)
                assertNotNull("$ordner: $n", t)
                assertTrue("$ordner: $n", t!!.contains("Panoptes"))
                assertFalse("$ordner: Apostroph escaped", Regex("(^|[^\\\\])'").containsMatchIn(t))
            }
        }
    }
}
