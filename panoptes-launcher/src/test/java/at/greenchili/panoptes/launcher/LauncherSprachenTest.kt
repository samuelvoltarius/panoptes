package at.greenchili.panoptes.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.1.43.4 (Kleinkram, F8): Spanisch, Portugiesisch und Türkisch im Launcher. „Sauber“ heißt hier: nur Schlüssel, die es im
 * englischen Original gibt, mit genau denselben Platzhaltern (%1$s, %1$d, %%), alle drei Sprachen mit gleichem Schlüsselsatz,
 * gültiges XML-Escaping. Fehlt einer Sprache ein später neu hinzugekommener Schlüssel, greift Android auf Englisch zurück
 * (kein Absturz, keine falsche Zahl); das Nachziehen sagt die Liste in der Rückmeldung der Pakete.
 */
class LauncherSprachenTest {

    private fun res(d: String): String {
        val p = "src/main/res/$d/strings.xml"
        return listOf(File(p), File("panoptes-launcher/$p")).first { it.isFile }.readText().replace("\r\n", "\n")
    }

    private fun eintraege(xml: String): Map<String, String> =
        Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }

    private fun platzhalter(s: String) = Regex("""%(?:\d\$)?[sd]|%%""").findAll(s).map { it.value }.sorted().toList()

    private val neu = listOf("values-es", "values-pt", "values-tr")

    @Test
    fun nurEnglischeSchluesselMitGleichenPlatzhaltern() {
        val basis = eintraege(res("values"))
        for (d in neu) {
            val e = eintraege(res(d))
            assertTrue("$d hat Texte", e.size >= 500)
            for ((k, v) in e) {
                assertTrue("$d: $k gibt es im Original nicht", basis.containsKey(k))
                assertEquals("$d: Platzhalter von $k", platzhalter(basis.getValue(k)), platzhalter(v))
                assertTrue("$d: $k nicht leer", v.isNotBlank())
            }
        }
    }

    @Test
    fun alleDreiSprachenHabenDenSelbenSchluesselsatz() {
        val es = eintraege(res("values-es")).keys
        assertEquals(es, eintraege(res("values-pt")).keys)
        assertEquals(es, eintraege(res("values-tr")).keys)
    }

    @Test
    fun alleDreiSprachenSindVollstaendig() {
        // 0.1.43.4 (Nacharbeit): nichts fehlt mehr; jeder neue Original-Schlüssel muss in ES/PT/TR nachgezogen werden
        val basis = eintraege(res("values")).keys
        for (d in neu) assertEquals("$d: fehlende Schlüssel", emptySet<String>(), basis - eintraege(res(d)).keys)
    }

    @Test
    fun xmlEscapingIstGueltig() {
        for (d in neu) {
            val s = res(d)
            // ein nacktes ' oder " im Text würde aapt ablehnen; erlaubt ist nur \' und \"
            for ((k, v) in eintraege(s)) {
                assertTrue("$d $k: ' muss als \\' stehen", !Regex("""(?<!\\)'""").containsMatchIn(v))
                assertTrue("$d $k: \" muss als \\\" stehen", !Regex("""(?<!\\)"""").containsMatchIn(v))
                assertTrue("$d $k: < und & maskiert", !v.contains("<") && !Regex("&(?!amp;|lt;)").containsMatchIn(v))
            }
        }
    }

    @Test
    fun dateiQrTexteSindInAllenAchtSprachen() {
        for (d in listOf("values", "values-de", "values-fr", "values-it", "values-nl") + neu) {
            val e = eintraege(res(d))
            for (k in listOf("datei_qr_titel", "datei_qr_warte", "datei_qr_erneuern", "datei_qr_kein_lan"))
                assertTrue("$d $k", e.containsKey(k))
        }
    }
}
