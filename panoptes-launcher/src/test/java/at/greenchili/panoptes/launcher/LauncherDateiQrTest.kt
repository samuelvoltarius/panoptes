package at.greenchili.panoptes.launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 0.1.43.4 (F7): Fenster „Datei vom Handy“ im Launcher: reine Regeln und Verdrahtung. */
class LauncherDateiQrTest {

    private fun quelle(rel: String): String {
        val p = "src/main/$rel"
        return listOf(File(p), File("panoptes-launcher/$p")).first { it.isFile }.readText().replace("\r\n", "\n")
    }

    @Test
    fun restzeitText() {
        assertEquals("2:00", DateiQr.restText(120))
        assertEquals("1:57", DateiQr.restText(117))
        assertEquals("0:05", DateiQr.restText(5))
        assertEquals("nie negativ", "0:00", DateiQr.restText(-4))
        assertEquals("nie über 59 Minuten", "59:59", DateiQr.restText(999_999))
    }

    @Test
    fun anzeigeAusDemKernZustand() {
        fun a(s: String) = DateiQr.anzeige(JSONObject(s))
        assertEquals(DateiQr.Anzeige.WARTE, a("""{"ok":true,"aktiv":true,"zustand":"offen"}"""))
        assertEquals(DateiQr.Anzeige.WARTE, a("""{"ok":true,"url":"x","zustand":"offen"}"""))
        assertEquals(DateiQr.Anzeige.UEBERTRAGUNG, a("""{"ok":true,"aktiv":true,"zustand":"laeuft"}"""))
        assertEquals(DateiQr.Anzeige.FERTIG, a("""{"ok":true,"aktiv":true,"zustand":"fertig","name":"foto.jpg"}"""))
        assertEquals(DateiQr.Anzeige.ABGELAUFEN, a("""{"ok":true,"aktiv":true,"zustand":"abgelaufen"}"""))
        assertEquals("keine Sitzung = abgelaufen", DateiQr.Anzeige.ABGELAUFEN, a("""{"ok":true,"aktiv":false}"""))
        assertEquals(DateiQr.Anzeige.KEIN_NETZ, a("""{"ok":false,"grund":"kein_lan"}"""))
        assertEquals(DateiQr.Anzeige.FEHLER, a("""{"ok":false,"grund":"nur_am_auto"}"""))
        assertEquals("keine Antwort", DateiQr.Anzeige.FEHLER, DateiQr.anzeige(null))
    }

    @Test
    fun erneuernNurNichtMitteninDerUebertragung() {
        for (a in DateiQr.Anzeige.values()) assertEquals(a.name, a != DateiQr.Anzeige.UEBERTRAGUNG, DateiQr.erneuernErlaubt(a))
    }

    @Test
    fun nurLokaleAdressenWerdenAlsQrGezeigt() {
        assertTrue(DateiQr.lokaleAdresse("http://192.168.43.1:8180/shared/datei-handy.html#k=ab"))
        assertTrue(DateiQr.lokaleAdresse("http://10.0.0.7:8180/shared/datei-handy.html#k=ab"))
        assertTrue(DateiQr.lokaleAdresse("http://172.20.1.2:8180/shared/datei-handy.html#k=ab"))
        for (nein in listOf("http://100.64.12.34:8180/x", "http://8.8.8.8:8180/x", "https://192.168.0.1:8180/x", "http://127.0.0.1:8180/x",
            "http://172.32.0.1:8180/x", "http://auto.example.org:8180/x", "", "javascript:alert(1)"))
            assertFalse(nein, DateiQr.lokaleAdresse(nein))
        assertFalse(DateiQr.lokaleAdresse(null))
    }

    @Test
    fun verdrahtungUndTexte() {
        val akt = quelle("java/at/greenchili/panoptes/launcher/MainActivity.kt")
        assertTrue("eine Zeile unter „Erweitert“, nicht in der oberen Liste",
            akt.indexOf("DateiQrFenster(this, ui).zeigen()") > akt.indexOf("""} else {
            zeile(R.drawable.ic_pn_close, getString(R.string.zurueck))"""))
        val fenster = quelle("java/at/greenchili/panoptes/launcher/DateiQrFenster.kt")
        assertTrue("nur der eigene Kern, nur Loopback", fenster.contains("http://127.0.0.1:8180/api/dateien/qr"))
        assertTrue("beim Schließen endet die Sitzung", fenster.contains("setOnDismissListener { zu = true; io.execute { aufruf(\"POST\", \"\$kern/ende\") } }"))
        assertFalse("keine Kennung im Log", fenster.contains("android.util.Log") || Regex("""\bLog\.[a-z]\(""").containsMatchIn(fenster))
        val gradle = listOf(File("build.gradle.kts"), File("panoptes-launcher/build.gradle.kts")).first { it.isFile }.readText()
        assertTrue(gradle.contains("implementation(libs.zxing.core)"))
        val keys = listOf("datei_qr_titel", "datei_qr_warte", "datei_qr_adresse", "datei_qr_ordner", "datei_qr_laeuft_ab", "datei_qr_erneuern",
            "datei_qr_kopieren", "datei_qr_schliessen", "datei_qr_empfangen", "datei_qr_laeuft_ue", "datei_qr_abgelaufen", "datei_qr_kein_lan",
            "datei_qr_fehler", "datei_qr_hinweis")
        for (d in listOf("values", "values-de", "values-fr", "values-it", "values-nl")) {
            val s = quelle("res/$d/strings.xml")
            for (k in keys) assertTrue("$d $k", s.contains("name=\"$k\">"))
        }
    }

    @Test
    fun ohnePanoptesRuhigerHinweisUndKeineDauerversuche() {
        val q = quelle("java/at/greenchili/panoptes/launcher/DateiQrFenster.kt")
        val k = quelle("java/at/greenchili/panoptes/launcher/Kamera.kt")
        assertTrue("gemeinsamer Hinweis liest nur KernVerbindung", q.contains("fun ohnePanoptesHinweis(") && q.contains("if (!KernVerbindung.ohnePanoptes()) return false"))
        assertTrue("Link nur über den Baustein", q.contains("KernVerbindung.panoptesOeffnen(activity)") && q.contains("R.string.mit_panoptes, titel"))
        assertTrue("Datei-Fenster fragt den Hinweis vor jedem Netzaufruf", q.indexOf("ohnePanoptesHinweis(activity, ui, activity.getString(R.string.datei_qr_titel))") in 1 until q.indexOf("neu(erstes = true)"))
        assertTrue("Aufnahme-Fenster ebenso", k.indexOf("QrAnsicht.ohnePanoptesHinweis(activity, ui, activity.getString(R.string.aufn_qr_titel))") in 1 until k.indexOf("neu(bild, stand, adresse, rest, d)"))
        assertTrue("Takt stoppt ohne Kern (Datei)", q.contains("if (zu || KernVerbindung.ohnePanoptes()) return"))
        assertTrue("Takt stoppt ohne Kern (Aufnahme)", k.contains("if (zu || KernVerbindung.ohnePanoptes()) return"))
        for (f in listOf(q, k)) assertFalse("keine eigene Erreichbarkeitsmessung", f.contains("KernVerbindung.melde(") || f.contains("KernVerbindung.sollPruefen("))
    }
}
