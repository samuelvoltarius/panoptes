package at.greenchili.panoptes.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/** 0.1.43.4: Kamera im Launcher – Sperre (nur P bei 0 km/h), Liste (parsen, Größe), Verträge (nur lesen), Texte in fünf Sprachen. */
class LauncherKameraTest {
    private fun datei(rel: String): File {
        val a = File(rel)
        return if (a.isFile) a else File("panoptes-launcher/$rel")
    }
    private fun quelle(name: String) = datei("src/main/java/at/greenchili/panoptes/launcher/$name").readText()

    @Test
    fun videoNurInPBeiStillstand() {
        assertTrue(Kamera.erlaubt("P", 0.0))
        assertTrue(Kamera.erlaubt("P", null))
        assertFalse(Kamera.erlaubt("P", 3.0))
        assertFalse(Kamera.erlaubt("D", 0.0))
        assertFalse(Kamera.erlaubt("R", 0.0))
        assertFalse(Kamera.erlaubt(null, 0.0))
    }

    @Test
    fun kameraKennungen() {
        assertEquals(listOf(0, 1, 3, 4, 2), Kamera.ANORDNUNG)
        assertTrue(Kamera.gueltig(0) && Kamera.gueltig(4))
        assertFalse(Kamera.gueltig(5)); assertFalse(Kamera.gueltig(-1))
    }

    @Test
    fun idPruefung() {
        assertTrue(Kamera.idGueltig("rec_1-2.mp4"))
        assertFalse(Kamera.idGueltig("../x")); assertFalse(Kamera.idGueltig("a b"))
        assertFalse(Kamera.idGueltig(null)); assertFalse(Kamera.idGueltig(""))
    }

    @Test
    fun groesseInKlartext() {
        assertEquals("512 B", Kamera.groesse(512, Locale.US))
        assertEquals("2 KB", Kamera.groesse(2048, Locale.US))
        assertEquals("1.5 MB", Kamera.groesse(1572864, Locale.US))
        assertEquals("2.00 GB", Kamera.groesse(2L * 1073741824, Locale.US))
    }

    @Test
    fun parseListe() {
        val s = Kamera.parse("""{"ok":true,"gespeichert":5,"angezeigt":2,"aufnahmen":[
            {"id":"a.mp4","kamera":3,"quelle":"allein","ausloeser":"auto","zeit":10,"groesse":99,"da":true},
            {"id":"../boese","kamera":1,"quelle":"fahrt","zeit":5,"groesse":1,"da":true},
            {"id":"b.mp4","kamera":0,"quelle":"fahrt","zeit":4,"groesse":2,"da":false}]}""")!!
        assertEquals(5, s.gespeichert)
        assertEquals(2, s.zeilen.size)
        assertTrue(s.zeilen[0].quelleAllein); assertEquals("auto", s.zeilen[0].ausloeser)
        assertNull(s.zeilen[1].ausloeser); assertFalse(s.zeilen[1].da)
        assertNull(Kamera.parse("kaputt")); assertNull(Kamera.parse("""{"ok":false}"""))
    }

    @Test
    fun launcherSchicktNurLesenUndNurFesteWerte() {
        val c = quelle("PanoptesClient.kt")
        val i = c.indexOf("fun aufnahmen")
        assertTrue(i > 0)
        val block = c.substring(i, i + 400)
        assertTrue(block.contains("\"GET\""))
        assertFalse(block.contains("POST")); assertFalse(block.contains("DELETE"))
        val k = quelle("Kamera.kt").substringBefore("class AufnahmeQrFenster")
        assertFalse(k.contains("setRequestProperty")); assertFalse(k.contains("HttpURLConnection"))
    }

    @Test
    fun spielenUndListeNurImStandUndMitGeprueftenWerten() {
        val m = quelle("MainActivity.kt")
        val i = m.indexOf("private fun aufnahmenFenster")
        assertTrue(i > 0)
        val block = m.substring(i, i + 1300)
        assertTrue(block.contains("Kamera.erlaubt"))
        assertTrue(block.contains("Kamera.idGueltig"))
    }

    @Test
    fun qrFensterSchicktNurDieAufnahmeKennungMitGeprueftemWert() {
        val k = quelle("Kamera.kt")
        val i = k.indexOf("class AufnahmeQrFenster")
        assertTrue(i > 0)
        val block = k.substring(i)
        assertTrue(block.contains("Kamera.idGueltig(id)"))
        assertTrue(block.contains("\"holen\"") && block.contains("\"aufnahme\""))
        assertFalse(block.contains("/api/recordings")); assertFalse(block.contains("DELETE"))
        assertTrue(quelle("MainActivity.kt").contains("AufnahmeQrFenster(this, ui, id)"))
    }

    @Test
    fun texteInFuenfSprachen() {
        for (d in listOf("values", "values-de", "values-fr", "values-it", "values-nl")) {
            val t = datei("src/main/res/$d/strings.xml").readText()
            assertTrue("$d aufn_qr_titel", t.contains("name=\"aufn_qr_titel\""))
            assertTrue("$d aufn_qr", t.contains("name=\"aufn_qr\""))
        }
        val schluessel = listOf("kamera_360", "kamera_vorne", "kamera_hinten", "kamera_links", "kamera_rechts", "kamera_aufnahmen", "kamera_nur_stand",
            "aufn_titel", "aufn_zeit_alle", "aufn_zeit_heute", "aufn_zeit_7", "aufn_zeit_30", "aufn_sort_neu", "aufn_sort_alt", "aufn_zaehler",
            "aufn_quelle_fahrt", "aufn_quelle_allein", "aufn_ausl_manuell", "aufn_ausl_auto", "aufn_da", "aufn_fehlt", "aufn_abspielen",
            "aufn_leer", "aufn_nicht_lesbar", "aufn_laedt")
        for (d in listOf("values", "values-de", "values-fr", "values-it", "values-nl")) {
            val t = datei("src/main/res/$d/strings.xml").readText()
            for (k in schluessel) assertTrue("$d fehlt $k", t.contains("name=\"$k\""))
        }
    }
}
