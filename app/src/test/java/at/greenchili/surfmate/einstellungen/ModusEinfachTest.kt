package at.greenchili.surfmate.einstellungen

import at.greenchili.surfmate.testutil.Quelltext
import at.greenchili.surfmate.ui.util.PreferencesManager
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * E4 (0.1.44, 13.11.): Modus „Einfach / Alle Funktionen“.
 * Neuinstallation „einfach“, Bestand „alle“ (nie still umschalten), ohne Wert „alle“. Einfach zeigt nur stufe 'a' und
 * keine erweiterten Felder; Suche und Direktlinks finden alles; Notzugang, Fernzugang, Rechtshinweise bleiben erreichbar.
 */
class ModusEinfachTest {

    private val wurzel: File = run {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "app/src/main").isDirectory) d = d.parentFile
        requireNotNull(d) { "Repo-Wurzel nicht gefunden" }
    }

    private fun lies(rel: String): String = Quelltext.lies(File(wurzel, rel).toPath())
    private fun felder() = Katalog.felder(Katalog.Kontext())
    private fun feld(key: String): Feld = requireNotNull(Katalog.finde(felder(), key)) { key }

    @Test
    fun neuEinfachBestandAlleOhneWertAlle() {
        assertEquals("einfach", Katalog.umfangFuerErstenStart(false))
        assertEquals("alle", Katalog.umfangFuerErstenStart(true))
        assertEquals("ohne Config: alle", "alle", Katalog.umfang(null))
        assertEquals("alle", Katalog.umfang(JSONObject()))
        assertEquals("alle", Katalog.umfang(JSONObject().put("anzeige", JSONObject().put("umfang", "quatsch"))))
        assertEquals("einfach", Katalog.umfang(JSONObject().put("anzeige", JSONObject().put("umfang", "einfach"))))

        val f = feld(Katalog.UMFANG)
        assertEquals(Feld.Typ.AUSWAHL, f.typ)
        assertEquals("Standard alle: nichts verschwindet still", "alle", f.standard)
        assertEquals(listOf("einfach", "alle"), f.optionen.map { it.wert })
        assertFalse("der Schalter selbst ist nie versteckt", f.erweitert || f.verborgen)

        // Festschreiben nur, wenn es noch keinen Eintrag gibt
        assertTrue(PreferencesManager.umfangFehlt(null))
        assertTrue(PreferencesManager.umfangFehlt(JSONObject()))
        assertTrue(PreferencesManager.umfangFehlt(JSONObject().put("umfang", JSONObject.NULL)))
        assertFalse(PreferencesManager.umfangFehlt(JSONObject().put("umfang", "alle")))
        assertFalse(PreferencesManager.umfangFehlt(JSONObject().put("umfang", "einfach")))
    }

    @Test
    fun festschreibenEinmalUndNieStillUmschalten() {
        val pm = lies("app/src/main/java/at/greenchili/surfmate/ui/util/PreferencesManager.kt")
        val init = pm.indexOf("        istBestand()\n        merkeUmfangErststart()")
        assertTrue("Entscheidung beim allerersten Start, vor allem, was die Config anlegt", init > 0)
        val f = pm.substring(pm.indexOf("private fun festschreibenUmfang()"), pm.indexOf("internal fun umfangFehlt("))
        assertTrue("nie überschreiben", f.contains("if (!umfangFehlt(vorher)) {"))
        assertTrue("zurücklesen (Fixrunde 0.1.43.2 M1)", f.contains("if (!umfangFehlt(nachher)) {"))
        assertTrue(f.contains("prefs?.getString(KEY_UMFANG_ERSTSTART, null)"))
        val m = pm.substring(pm.indexOf("private fun merkeUmfangErststart()"), pm.indexOf("/** Nur festschreiben, wenn"))
        assertTrue("einmal gemerkt, nie überschrieben", m.contains("if (p.contains(KEY_UMFANG_ERSTSTART)) return"))
        // Profil liefert den Umfang für die Leiste
        assertTrue(lies("app/src/main/java/at/greenchili/surfmate/server/SystemApiHandler.java")
            .contains("res.put(\"umfang\", Erststart.umfang(cfg));"))
    }

    @Test
    fun zusaetzlichErweitertAberSicherheitNie() {
        val neu = listOf("surveillance.clipRetentionHours", "surveillance.keepDays", "surveillance.deterrentQuietSeconds",
            "guardStop.windows", "planner.autoId", "planner.memberToken", "anzeige.befehleAnsicht",
            "anzeige.fahrzeugAnsicht", "anzeige.startAnsicht", "anzeige.bandGroesse", "anzeige.bandPosition",
            "energie.profil", "update.hinweis", "zaehlen.kennung", "zaehlen.kennungAktion", "ladekurve.kennung",
            "laden.netzeModus")
        for (k in neu) assertTrue(k, feld(k).erweitert)
        assertTrue("mindestens 40 erweitert", felder().count { it.erweitert } >= 40)
        // Einwilligungen, Wächter an/aus und Rechtshinweise bleiben im Modus „Einfach“ sichtbar
        val sicher = listOf("surveillance.surveillanceEnabled", "surveillance.triggerMode", "zaehlen.an",
            "ladekurve.teilen", "karte.quelle", "planner.zustandSenden", "planner.positionSenden",
            "planner.vorliebenSenden", "community.enabled", "wetter.online", "speedcam.enabled", "update.pruefen",
            "update.mobilfunk", "anzeige.darstellung", "anzeige.thema")
        for (k in sicher) assertFalse("nie erweitert: $k", feld(k).erweitert)
    }

    @Test
    fun seitenStufenUndSicherheitErreichbar() {
        val js = lies("app/src/main/assets/web/shared/app-shell.js")
        val liste = js.substring(js.indexOf("    var SECTIONS = ["), js.indexOf("    // Reiter, die mehrere Seiten zusammenfassen."))
        val seiten = Regex("""\{ href: '([\w.-]+)',[^\n]*stufe: '([abc])' \}""").findAll(liste)
            .associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals("jede Seite hat eine Stufe", Regex("""\{ href: '""").findAll(liste).count(), seiten.size)
        val einfach = seiten.filterValues { it == "a" }.keys
        assertEquals(setOf("index.html", "navi.html", "charging.html", "vehicle-control.html", "live-view.html",
            "events.html", "waechter.html", "launcher.html", "system.html", "fehler-melden.html", "about.html"), einfach)
        // Notzugang und Fernzugang liegen auf system.html, „Alles zurück auf BYD“ beim Launcher, Rechtshinweise unter Über
        val sys = lies("app/src/main/assets/web/local/system.html")
        assertTrue(sys.contains("id=\"notzugang\"") || sys.contains("id='notzugang'") || sys.contains("notz.titel"))
        assertTrue(sys.contains("tailscaleKarte"))
        assertTrue("Modus-Karte immer sichtbar (in keiner Gruppe)", sys.contains("<div class=\"card pn-umfang-karte\" id=\"umfang\">"))
        val so = lies("app/src/main/assets/web/shared/system-overview.js")
        val gruppen = so.substring(so.indexOf("var GROUPS = {"), so.indexOf("var STORAGE_KEY"))
        assertFalse(gruppen.contains("'umfang'"))
        assertTrue("Notzugang-Gruppe bleibt", gruppen.contains("'notzugang'"))
        for (c in listOf("remote-dev-view.html", "abrp.html", "mqtt.html", "wireguard.html", "performance.html"))
            assertEquals(c, "c", seiten[c])
    }

    @Test
    fun shellFiltertNurDasMenueUndBleibtEs5() {
        val js = lies("app/src/main/assets/web/shared/app-shell.js")
        val e4 = js.substring(js.indexOf("// ---------------------------------------------------------------- E4: Modus"), js.indexOf("    function svgIcon(name) {"))
        assertTrue("offene Seite immer, sonst nur stufe a", e4.contains("return p.href === base || !istEinfach() || p.stufe === 'a';"))
        assertTrue("ohne Wert alle", e4.contains("=== 'einfach' ? 'einfach' : 'alle'"))
        assertTrue(e4.contains("fetch('/api/einstellungen', {"))
        assertTrue(e4.contains("werte: { 'anzeige.umfang': wert }"))
        for (v in listOf("=>", "let ", "const ", "`", "?.", "??")) assertFalse("ES5: $v", e4.contains(v))
        val nav = js.substring(js.indexOf("function buildRailNav(active, base)"), js.indexOf("// Unsichtbare Status-Felder"))
        assertTrue("Alle Funktionen gut sichtbar in der Leiste", nav.contains("data-umfang=\"alle\""))
        assertTrue(nav.contains("if (istEinfach())"))
        assertTrue(js.contains("if (!zeigeSeite(p, base)) continue;   // E4: Modus „Einfach“"))
        assertTrue(js.contains("umfangAbgleichen(p.umfang);"))
        // Seitensuche nimmt weiter ALLE Seiten (kein Modus-Filter), Direktlinks: findSection kennt alle Seiten
        val so = lies("app/src/main/assets/web/shared/system-overview.js")
        val seitenListe = so.substring(so.indexOf("function seitenListe()"), so.indexOf("function kartenListe()"))
        assertFalse(seitenListe.contains("stufe") || seitenListe.contains("umfang"))
        val fs = js.substring(js.indexOf("function findSection(base)"), js.indexOf("function svgIcon(name)"))
        assertFalse(fs.substring(0, fs.indexOf("return { section: null")).contains("stufe"))
        // Feld-Treffer zeigen sich auch im Modus „Einfach“
        assertTrue(so.contains("if (e.feld) sessionStorage.setItem('pn.sprungFeld', e.feld);"))
        val es = lies("app/src/main/assets/web/shared/einstellungen.js")
        assertTrue(es.contains("if (einfach && f.erweitert && f.key !== sprung) { hatVersteckt = true; return; }"))
        assertTrue(es.contains("window.PanoptesShell.setzeUmfang('alle')"))
        for (v in listOf("=>", "let ", "const ", "`")) {
            val teil = es.substring(es.indexOf("// E4 (0.1.44): Modus „Einfach / Alle Funktionen“ kommt"), es.indexOf("/** Lädt Schema und Werte."))
            assertFalse("ES5: $v", teil.contains(v))
        }
    }

    @Test
    fun texteInFuenfSprachen() {
        for (l in listOf("de", "en", "fr", "it", "nl")) {
            val w = JSONObject(lies("app/src/main/assets/web/i18n/$l.json"))
            val sh = w.getJSONObject("shell")
            for (k in listOf("alle_funktionen", "kurz_alle", "umfang_einfach", "umfang_titel", "umfang_hilfe", "umfang_fehler"))
                assertTrue("$l shell.$k", sh.getString(k).isNotBlank())
            val ui = w.getJSONObject("settings").getJSONObject("ui")
            assertTrue(ui.getString("alle_hinweis").isNotBlank() && ui.getString("alle_zeigen").isNotBlank())
            val s = JSONObject(lies("app/src/main/assets/server-i18n/$l.json")).getJSONObject("settings")
                .getJSONObject("anzeige").getJSONObject("umfang")
            for (k in listOf("title", "help", "einfach", "alle")) assertNotNull(s.optString(k).takeIf { it.isNotBlank() })
        }
    }
}
