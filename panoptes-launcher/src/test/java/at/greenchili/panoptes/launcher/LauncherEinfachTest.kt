package at.greenchili.panoptes.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.1.44 V1 E1 (Sofort-Fixes im Launcher): Beschriftung passt zum Ziel, Du-Form, Einstellungsknöpfe nie still.
 * E5 (13.11.2026, Projektinhaber zugestimmt): Launcher einfacher – keine Layout-Frage, Startbildschirm einmal anbieten,
 * Einstellungen oben 5 Zeilen + „Mehr“, Kachel-Katalog Grundauswahl + „Weitere“, Du-Form, Web-Hinweis richtig.
 * (Beide Zweige legten diese Datei an; in integ-0144 zusammengeführt, alle Tests beider Seiten bleiben.)
 */
class LauncherEinfachTest {

    private fun datei(rel: String): String =
        listOf(File(rel), File("panoptes-launcher/$rel")).first { it.isFile }.readText()

    private fun quelle(name: String) = datei("src/main/java/at/greenchili/panoptes/launcher/$name")

    private fun wert(d: String, k: String): String =
        Regex("<string name=\"$k\">([^<]*)</string>").find(datei("src/main/res/$d/strings.xml"))?.groupValues?.get(1)
            ?: throw AssertionError("$d/$k")

    @Test
    fun beschriftungPasstZumZiel() {
        assertTrue("Reiter öffnet nur das Klima-Feld", quelle("MainActivity.kt").contains("R.string.reiter_steuerung, false) { klimaFeld() }"))
        assertTrue(wert("values-de", "reiter_steuerung") == "Klima")
        assertTrue(wert("values-de", "vorlage_zuruecksetzen") == "Andere Vorlage wählen")
        assertTrue(wert("values-de", "hilfe_einstellung").contains("Letzte App"))
        for (k in listOf("kombi_einstellung", "kombi_knopf", "kombi_normal", "kombi_info", "kombi_geht_nicht", "kombi_freigeben",
                "kombi_laeuft", "kombi_zurueck"))
            assertFalse("Fachwort Kombi: $k", wert("values-de", k).contains("Kombi"))
    }

    @Test
    fun duFormDurchgaengig() {
        val de = datei("src/main/res/values-de/strings.xml")
        for (z in de.lines().filter { it.contains("<string name=") })
            assertFalse("Sie-Form: $z", Regex("\\b(Sie|Ihnen|Ihre?)\\b").containsMatchIn(z.substringAfter(">")))
        val it = datei("src/main/res/values-it/strings.xml")
        for (z in it.lines().filter { it.contains("name=\"einr_") || it.contains("name=\"hilfe_") })
            assertFalse("Lei-Form: $z", Regex("\\b(Prema|Tocchi|Riprovi|Attivi|Apra|controlli)\\b|\\blei stesso\\b").containsMatchIn(z))
    }

    @Test
    fun einstellungsknoepfeNieStill() {
        val m = quelle("MainActivity.kt")
        assertTrue(m.contains("if (!PanoptesBedienhilfe.einstellungOeffnen(this)) zeigeHinweis(getString(R.string.einstellung_fehlt))"))
        assertFalse("Einstellungsseiten melden nicht „App konnte nicht gestartet werden“",
            Regex("ACTION_(MANAGE_OVERLAY_PERMISSION|NOTIFICATION_LISTENER_SETTINGS)[^\\n]*\\n?[^\\n]*launch_failed").containsMatchIn(m))
        assertTrue("Zeile zeigt den Zugriff ehrlich", m.contains("schalter(R.string.medien_zugriff, MedienQuelle.zugriffErlaubt(this))"))
        assertTrue(quelle("Einrichtung.kt").contains("if (!PanoptesBedienhilfe.einstellungOeffnen(this)) hinweis(R.string.einstellung_fehlt)"))
        for (d in listOf("values", "values-de", "values-fr", "values-it", "values-nl"))
            assertTrue(d, wert(d, "einstellung_fehlt").isNotBlank())
    }

    // ---- E5 ----
    private fun basis() = if (File("src/main").isDirectory) File(".") else File("panoptes-launcher")
    private fun lies(rel: String) = File(basis(), rel).readText().replace("\r\n", "\n")
    private val m by lazy { lies("src/main/java/at/greenchili/panoptes/launcher/MainActivity.kt") }
    private fun block(von: String, bis: String) = m.substring(m.indexOf(von), m.indexOf(bis, m.indexOf(von)))
    private val sprachen = listOf("values", "values-de", "values-fr", "values-it", "values-nl")

    @Test
    fun ersterStartOhneFrage() {
        assertNull("Standard bleibt Standard", ErstStart.vorlage(grosseBedienung = false))
        assertEquals("Große Bedienung = Einfach", Vorlagen.EINFACH, ErstStart.vorlage(grosseBedienung = true))
        assertNull("eigenes Layout wird nie still ersetzt", ErstStart.vorlage(true, layoutGespeichert = true))
        assertFalse(m.contains("vorlageErstFrage"))
        val e = block("private fun erstStart(", "private fun launcherLief()")
        assertTrue("nur einmal markiert", e.contains("putBoolean(KEY_VORLAGE_GEFRAGT, true)"))
        assertTrue(e.contains("ErstStart.vorlage(GrosseBedienung.an(this))"))
        // Große Bedienung einschalten: Werks-Layout → Einfach, nur im Stand
        val e2 = block("private fun einstellungen(erweitert: Boolean = false)", "private fun groesseName(")
        assertTrue(e2.contains("ErstStart.vorlage(an, getSharedPreferences(PREFS, MODE_PRIVATE).contains(KEY_LAYOUT))"))
        assertTrue(e2.contains("if (v != null && darfBedienen(still = true)) { vorlageSetzen(v); return@zeile }"))
    }

    @Test
    fun startbildschirmEinmalAnbieten() {
        assertTrue(ErstStart.anbieten(schonAngeboten = false, istStartbildschirm = false, imStand = true))
        assertFalse("nur einmal", ErstStart.anbieten(true, false, true))
        assertFalse("schon Startbildschirm", ErstStart.anbieten(false, true, true))
        assertFalse("nicht während der Fahrt", ErstStart.anbieten(false, false, false))
        assertTrue(ErstStart.erledigt(true, false)); assertTrue(ErstStart.erledigt(false, true)); assertFalse(ErstStart.erledigt(false, false))
        val a = block("private fun startAngebot()", "private fun istStartbildschirm()")
        assertTrue("als gezeigt erst gemerkt, wenn es gezeigt wird",
            a.indexOf("putBoolean(KEY_HOME_ANGEBOTEN, true)") in a.indexOf("darfBedienen(still = true)") until a.indexOf(".show()"))
        assertTrue("„Später“ = nichts tun", a.contains(".setNegativeButton(R.string.einr_angebot_spaeter, null)"))
        assertTrue("„Einrichten“ = geführte Einrichtung", a.contains("EinrichtungActivity.starten(this)"))
        assertTrue("nicht über ein offenes Fenster", a.contains("overlay != null"))
        assertTrue("erneut beim Kern-Stand", block("private fun neueDaten(", "klimaFeldZeichnen?.invoke()").contains("if (angebotPruefen) startAngebot()"))
        assertTrue(m.contains("const val KEY_HOME_ANGEBOTEN = \"startbildschirm_angeboten\""))
        // nie selbst zum Startbildschirm machen: nur die geführte Einrichtung öffnen
        assertFalse(a.contains("set-home-activity") || a.contains("startbildschirm(true"))
    }

    @Test
    fun einstellungenObenFuenfZeilen() {
        assertEquals(5, Einstellungen.OBEN.size)
        val e = block("private fun einstellungen(erweitert: Boolean = false)", "private fun groesseName(")
        val oben = e.substring(e.indexOf("if (!erweitert) {"), e.indexOf("} else {"))
        val mehr = e.substring(e.indexOf("} else {"))
        val zeilen = Regex("\\bzeile\\(").findAll(oben).count()
        assertEquals("5 Zeilen + „Mehr“", 6, zeilen)
        val reihenfolge = listOf("R.string.einr_titel", "R.string.launcher_groesse", "R.string.gross_titel", "R.string.navi_app", "R.string.hintergrund", "R.string.erweitert")
        val pos = reihenfolge.map { oben.indexOf(it) }
        assertTrue("Reihenfolge $pos", pos.all { it >= 0 } && pos == pos.sorted())
        for (k in listOf("bearbeiten = true", "vorlageWaehlen()", "zeigeKlima", "zeige3d")) {
            assertFalse("nicht mehr oben: $k", oben.contains(k)); assertTrue("unter Mehr: $k", mehr.contains(k))
        }
        for (d in sprachen) {
            val x = lies("src/main/res/$d/strings.xml")
            val t = Regex("name=\"erweitert\">([^<]*)<").find(x)!!.groupValues[1]
            assertTrue("$d: „Mehr“ kurz", t.length <= 6)
        }
    }

    @Test
    fun kachelKatalogGrundUndWeitere() {
        assertEquals(listOf(Kachel.FAHRZEUG, Kachel.AKKU, Kachel.NAVI, Kachel.MUSIK, Kachel.UHR), KachelKatalog.GRUND)
        val fehlend = listOf(Kachel.WAECHTER, Kachel.UHR, Kachel.SCHLOSS, Kachel.FAHRZEUG)
        assertEquals(listOf(Kachel.FAHRZEUG, Kachel.UHR), KachelKatalog.grund(fehlend))
        assertEquals(listOf(Kachel.WAECHTER, Kachel.SCHLOSS), KachelKatalog.weitere(fehlend))
        assertTrue("alle Grundkacheln da: gleich die weitere Liste", KachelKatalog.direktWeitere(listOf(Kachel.WAECHTER)))
        assertFalse(KachelKatalog.direktWeitere(fehlend))
        // nichts fällt weg: jede Kachel ist in genau einer der beiden Listen
        val alle = Kachel.values().toList()
        assertEquals(alle.toSet(), (KachelKatalog.grund(alle) + KachelKatalog.weitere(alle)).toSet())
        val k = block("private fun kachelHinzufuegen()", "private fun seiteEntfernenFragen()")
        assertTrue(k.contains("KachelKatalog.grund(fehlend).map { eintrag(it) } + listOf(getString(R.string.kachel_weitere) to weitere)"))
        assertTrue("Android-Widgets unter Weitere", k.substring(k.indexOf("val weitere")).contains("geraeteWidgetWaehlen()"))
        assertTrue(k.contains("if (KachelKatalog.direktWeitere(fehlend)) { weitere(); return }"))
    }

    @Test
    fun texteInFuenfSprachenUndDuForm() {
        val neu = listOf("einr_angebot_titel", "einr_angebot_text", "einr_angebot_ja", "einr_angebot_spaeter", "kachel_weitere")
        for (d in sprachen) {
            val x = lies("src/main/res/$d/strings.xml")
            for (n in neu) {
                val t = Regex("name=\"$n\">([^<]*)<").find(x)?.groupValues?.get(1)
                assertNotNull("$d: $n", t)
                assertFalse("$d: $n Apostroph", Regex("(^|[^\\\\])'").containsMatchIn(t!!))
            }
            assertFalse("$d: Layout-Frage weg", x.contains("name=\"vorlage_frage\"") || x.contains("name=\"vorlage_spaeter\""))
        }
        // Launcher durchgängig Du: kein „Sie“ als Anrede, kein „Ihr/Ihnen“
        val de = lies("src/main/res/values-de/strings.xml")
        for (z in de.lines().filter { it.contains("<string") }) {
            val text = z.substringAfter(">").substringBefore("</string>")
            assertFalse("Sie-Form: $z", Regex("\\b(Ihnen|Ihr|Ihre|Ihren|Ihrem)\\b").containsMatchIn(text))
            assertFalse("Sie-Form: $z", Regex("\\b(schalten|stellen|tippen|drücken|versuchen|testen) Sie\\b|\\bSie (jetzt|jederzeit|selbst)\\b|^(Drücken|Tippen|Schalten|Versuchen) Sie\\b").containsMatchIn(text))
        }
        val it = lies("src/main/res/values-it/strings.xml")
        for (f in listOf("Prema ", "Tocchi ", "Riprovi", "Apra ", "attivi lei", "controlli più")) assertFalse("IT Lei-Form: $f", it.contains(f))
    }

    @Test
    fun webHinweisStimmt() {
        val web = File(basis(), "../app/src/main/assets/web")
        for (l in listOf("de", "en", "fr", "it", "nl")) {
            val j = File(web, "i18n/$l.json").readText()
            val hint = Regex("\"lnc_home_hint\": \"([^\"]*)\"").find(j)!!.groupValues[1]
            assertFalse("$l: nicht mehr „nie Standard“", hint.contains("nie selbst") || hint.contains("never makes") ||
                hint.contains("ne définit jamais") || hint.contains("non imposta mai") || hint.contains("nooit zelf"))
        }
        val de = File(web, "i18n/de.json").readText()
        assertTrue(de.contains("Startbildschirm wird der Launcher nur, wenn du es selbst einschaltest"))
        assertFalse(File(web, "local/display.html").readText().contains("nie selbst zum Standard-Startbildschirm"))
        assertFalse(File(web, "shared/launcher-karte.js").readText().contains("Panoptes setzt sie nie als Standard-Startbildschirm"))
    }
}
