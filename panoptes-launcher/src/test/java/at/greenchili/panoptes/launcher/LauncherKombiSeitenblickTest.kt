package at.greenchili.panoptes.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.1.41 (LC): Punkt 3 „Navi aufs Kombi“ und Punkt 8 „Seitenblick beim Blinken“. Ab Werk aus, nur Kern-Wege,
 * Blinker nur lesen (REGEL A+), ehrlich „geht nicht“, wenn das Auto es nicht kann.
 */
class LauncherKombiSeitenblickTest {

    private fun quelle(name: String): String {
        val kandidaten = listOf("src/main/java/at/greenchili/panoptes/launcher/$name",
            "panoptes-launcher/src/main/java/at/greenchili/panoptes/launcher/$name")
        return kandidaten.map { File(it) }.first { it.isFile }.readText().replace("\r\n", "\n")   // Windows-Checkout (autocrlf) liefert CRLF
    }

    private fun strings(ordner: String): String {
        val kandidaten = listOf("src/main/res/$ordner/strings.xml", "panoptes-launcher/src/main/res/$ordner/strings.xml")
        return kandidaten.map { File(it) }.first { it.isFile }.readText()
    }

    @Test
    fun knopfNurMitEinstellungUndNichtWennDerKernNeinSagt() {
        assertFalse(Kombi.knopfZeigen(false, true))
        assertFalse(Kombi.knopfZeigen(false, null))
        assertFalse("Surf: Plattformurteil nein → kein Knopf", Kombi.knopfZeigen(true, false))
        assertTrue(Kombi.knopfZeigen(true, null))
        assertTrue(Kombi.knopfZeigen(true, true))
    }

    @Test
    fun abWerkAus() {
        val src = quelle("MainActivity.kt")
        assertTrue(src.contains("getBoolean(Kombi.KEY_KNOPF, false)"))
        assertFalse(src.contains("getBoolean(Kombi.KEY_KNOPF, true)"))
        // Seitenblick hat keinen Launcher-Merker: der Kern-Schalter blindspot.enabled ist ab Werk aus
        assertFalse(src.contains("seitenblick_an\", true"))
    }

    @Test
    fun zielIstDieGeteilteNaviSonstDasNaviZielNiePanoptes() {
        assertEquals("com.waze", Kombi.ziel("com.waze", "com.google.android.apps.maps"))
        assertEquals("com.google.android.apps.maps", Kombi.ziel(null, "com.google.android.apps.maps"))
        assertEquals("com.google.android.apps.maps", Kombi.ziel(Raster.PANOPTES, "com.google.android.apps.maps"))
        assertNull(Kombi.ziel(null, Raster.PANOPTES))
        assertNull(Kombi.ziel("", null))
    }

    @Test
    fun gruendeDesKernsWerdenEhrlichUebersetzt() {
        for (g in listOf("single_os", "kein_projektionsdienst", "keine_shell_rechte", "auf_diesem_auto_nicht_angeboten",
                "faehigkeit_unbekannt", "warnleuchten_verdeckt", "dilink5_totwinkel_nicht_belegt"))
            assertEquals(g, Kombi.Hinweis.GEHT_NICHT, Kombi.hinweis(g))
        assertEquals(Kombi.Hinweis.FREIGEBEN, Kombi.hinweis("nicht_freigegeben"))
        assertEquals(Kombi.Hinweis.FREIGEBEN, Kombi.hinweis("hinweis_noetig"))
        assertEquals(Kombi.Hinweis.NUR_STAND, Kombi.hinweis("nur_im_stand"))
        assertEquals(Kombi.Hinweis.KEINE_NAVI, Kombi.hinweis("keine_navi"))
        assertEquals(Kombi.Hinweis.FEHLER, Kombi.hinweis("fehler"))
        assertEquals(Kombi.Hinweis.FEHLER, Kombi.hinweis(null))
        assertNull(Kombi.startGrund("{\"ok\":true,\"gestartet\":true}"))
        assertEquals("nur_im_stand", Kombi.startGrund("{\"ok\":false,\"grund\":\"nur_im_stand\"}"))
        assertEquals("fehler", Kombi.startGrund("{\"ok\":false,\"gestartet\":false}"))
        assertEquals("fehler", Kombi.startGrund("kaputt"))
    }

    @Test
    fun antwortenDesKernsLesen() {
        val k = Kombi.parse("{\"angeboten\":false,\"grund\":\"single_os\",\"freigegeben\":false,\"aktiv\":null}")
        assertNotNull(k); assertFalse(k!!.angeboten); assertEquals("single_os", k.grund); assertNull(k.aktiv)
        assertNull(Kombi.parse("{\"ok\":false,\"grund\":\"launcher_signatur\"}"))
        assertNull(Kombi.parse("kein json"))
        val s = Seitenblick.parse("{\"an\":false,\"rueckwaertsVorrang\":false,\"blinker\":\"lesbar\"}")
        assertNotNull(s); assertFalse(s!!.an); assertEquals("lesbar", s.blinker)
        assertNull(Seitenblick.parse("{\"ok\":false,\"grund\":\"launcher_signatur\"}"))
        assertTrue(Seitenblick.geschaltet("{\"ok\":true,\"an\":true}"))
        assertFalse(Seitenblick.geschaltet("{\"ok\":false,\"grund\":\"kern_fehler\"}"))
        assertFalse(Seitenblick.geschaltet(null))
    }

    /** Nur die Kern-Wege; kein Fahrzeugbefehl, kein eigener Kamera- oder Projektionsweg, Blinker nie bedient. */
    @Test
    fun nurKernWegeKeinBlinkerSchreiben() {
        val client = quelle("PanoptesClient.kt")
        assertTrue(client.contains("\"/api/launcher/v1/tacho/kombi"))
        assertTrue(client.contains("\"/api/launcher/v1/seitenblick\""))
        val alles = quelle("KombiSeitenblick.kt") + quelle("MainActivity.kt") + client
        for (verboten in listOf("/api/bs/", "/api/vehicle/", "cluster-cast", "/api/navmap/cluster", "setTurnLight",
                "hazard", "/api/tacho/navi/probe", "/api/tacho/navi/hinweis", "/api/tacho/navi/fahrt", "/api/tacho/navi/warnleuchten"))
            assertFalse(verboten, alles.contains(verboten))
    }

    /** E2E 0.1.41 G8: Scheitert der Seitenblick, eigener Text statt „Die App konnte nicht gestartet werden“. */
    @Test
    fun seitenblickFehlerHatEigenenText() {
        val src = quelle("MainActivity.kt")
        val a = src.indexOf("private fun seitenblickFragen()")
        val e = src.indexOf("\n    }\n", src.indexOf("private fun seitenblickSchalten(", a))
        val block = src.substring(a, e)
        assertFalse("kein launch_failed im Seitenblick", block.contains("R.string.launch_failed"))
        assertTrue(block.contains("R.string.seitenblick_fehler") && block.contains("R.string.seitenblick_nicht_erreichbar"))
    }

    @Test
    fun texteInAllenFuenfSprachen() {
        val schluessel = listOf("kombi_einstellung", "kombi_knopf", "kombi_normal", "kombi_info", "kombi_geht_nicht",
            "kombi_freigeben", "kombi_nur_stand", "kombi_laeuft", "kombi_zurueck", "verstanden", "seitenblick_titel",
            "seitenblick_regel", "seitenblick_blinker_fehlt", "seitenblick_ist", "seitenblick_an", "seitenblick_aus",
            "seitenblick_ist_an", "seitenblick_ist_aus", "seitenblick_fehler", "seitenblick_nicht_erreichbar")
        for (o in listOf("values", "values-de", "values-fr", "values-it", "values-nl")) {
            val s = strings(o)
            for (k in schluessel) assertTrue("$o/$k", s.contains("name=\"$k\""))
        }
        // Die Regel nennt in jeder Sprache: auch beim Fahren, Rückfahrkamera hat Vorrang, Blinker nur gelesen
        val de = strings("values-de")
        assertTrue(de.contains("auch während der Fahrt") && de.contains("Rückfahrkamera") && de.contains("liest den Blinker nur"))
    }
}
