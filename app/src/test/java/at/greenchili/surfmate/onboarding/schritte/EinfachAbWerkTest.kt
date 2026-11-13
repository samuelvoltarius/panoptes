package at.greenchili.surfmate.onboarding.schritte

import at.greenchili.surfmate.server.EinrichtungApiHandler
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.1.44 V1 „Einfach ab Werk“ (13.11.: „E1–E5 jetzt“), Pakete E1–E3:
 * E1 Sofort-Fixes (Kameras-Knopf, Gewohnheiten nicht vorgewählt, Benachrichtigungszugriff ehrlich, Launcher-Texte),
 * E2 Selbsttest ehrlich (nicht Gefragtes ist „freiwillig“, kein Fehler), E3 drei Empfehlungskarten statt langer Liste.
 */
class EinfachAbWerkTest {

    private fun datei(rel: String): String {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null) {
            for (k in listOf(File(d, rel), File(d, "app/$rel"))) if (k.isFile) return k.readText()
            d = d.parentFile
        }
        throw AssertionError(rel)
    }

    private fun stand(json: String) = EinrichtungStand.ausJson(JSONObject(json))

    private fun s(id: String, st: String) = """{"id":"$id","pflicht":false,"status":"$st"}"""

    // ------------------------------------------------------------------ E3 Empfehlungen

    @Test fun empfehlungenGleichImKernUndAmDisplay() {
        assertEquals(listOf("waechter", "extra_handy", "extra_launcher"), EinrichtungApiHandler.EMPFOHLEN)
        assertEquals(EinrichtungApiHandler.EMPFOHLEN, Einrichtung.EMPFOHLEN)
        for (id in Einrichtung.EMPFOHLEN) assertTrue(id, id in EinrichtungApiHandler.SCHRITTE)
        val web = datei("src/main/assets/web/shared/einrichtung-web.js")
        assertTrue("Web-Rückfall gleich", web.contains("var EMPFOHLEN = ['waechter', 'extra_handy', 'extra_launcher'];"))
    }

    @Test fun hoechstensDreiKartenRestEingeklappt() {
        val st = stand("""{"schritte":[${s("sprache", "erledigt")},${s("auto", "spaeter")},${s("kameras", "spaeter")},
            ${s("waechter", "spaeter")},${s("betrieb", "spaeter")},${s("karte", "spaeter")},${s("strompreis", "spaeter")},
            ${s("extra_telegram", "spaeter")},${s("extra_handy", "spaeter")},${s("extra_launcher", "spaeter")},
            ${s("extra_fernzugang", "spaeter")},${s("fertig", "offen")}],"offen_spaeter":[]}""")
        assertEquals(listOf("waechter", "extra_handy", "extra_launcher"), st.empfehlungen())
        val weitere = st.weitere()
        assertEquals(listOf("auto", "kameras", "betrieb", "karte", "strompreis", "extra_telegram", "extra_fernzugang"), weitere)
        assertTrue("nichts geht verloren", (st.empfehlungen() + weitere).toSet() == st.nochOffen().toSet())
    }

    @Test fun erledigteEmpfehlungVerschwindet() {
        val st = stand("""{"schritte":[${s("waechter", "erledigt")},${s("extra_handy", "spaeter")},
            ${s("extra_launcher", "entfaellt")},${s("karte", "spaeter")},${s("fertig", "offen")}],"offen_spaeter":[]}""")
        assertEquals(listOf("extra_handy"), st.empfehlungen())
        assertEquals(listOf("karte"), st.weitere())
    }

    @Test fun kernLiefertEmpfohlenNurOffene() {
        val k = JSONObject().put("waechter", "erledigt").put("extra_handy", "spaeter").put("extra_launcher", "offen")
        val r = EinrichtungApiHandler::class.java.getDeclaredMethod("empfohlen", JSONObject::class.java)
            .apply { isAccessible = true }.invoke(null, k) as org.json.JSONArray
        assertEquals("[\"extra_handy\",\"extra_launcher\"]", r.toString())
        val h = datei("src/main/java/at/greenchili/surfmate/server/EinrichtungApiHandler.java")
        assertTrue(h.contains("r.put(\"empfohlen\", empfohlen(s));"))
        assertTrue(h.contains("if (EMPFOHLEN.contains(id)) e.put(\"empfohlen\", true);"))
    }

    @Test fun displayZeigtKartenUndEingeklappteWeitere() {
        val w = datei("src/main/java/at/greenchili/surfmate/onboarding/schritte/WeitereSchritte.kt")
        assertTrue(w.contains("val empfohlen = stand.empfehlungen()") && w.contains("val weitere = stand.weitere()"))
        assertTrue("weitere eingeklappt", w.contains("val box = spalte().apply { visibility = View.GONE }"))
        assertTrue(w.contains("R.string.einr_weitere_zeigen, weitere.size"))
        val web = datei("src/main/assets/web/shared/einrichtung-web.js")
        assertTrue(web.contains("box.style.display = 'none';") && web.contains("empfehlungsKarte(inhalt, o)"))
    }

    // ------------------------------------------------------------------ E2 Selbsttest ehrlich, E1 Knopf

    @Test fun selbsttestFreiwilligRuhigMitPassendemKnopf() {
        val w = datei("src/main/java/at/greenchili/surfmate/onboarding/schritte/WeitereSchritte.kt")
        assertTrue(w.contains("\"freiwillig\" -> t.res(R.string.einr_st_freiwillig)"))
        assertTrue("nur „fehlt“ ist orange", w.contains("if (status == \"fehlt\") setTextColor(a.farbeWarnung)"))
        assertFalse("Fehler: Knopf hing an der ID des Abschluss-Bildschirms", w.contains("if (id == \"kameras\")"))
        assertTrue(w.contains("knopfText(p.optString(\"id\"), status)"))
        assertTrue(w.contains("punkt == \"kameras\" -> t.res(R.string.einr_kameras_knopf)"))
        val st = datei("src/main/java/at/greenchili/surfmate/einrichtung/Selbsttest.java")
        assertTrue(st.contains("w = punkt(\"waechter\", FREIWILLIG, \"waechter\");"))
        assertTrue(st.contains("k = punkt(\"kameras\", FREIWILLIG, \"kameras\");"))
    }

    // ------------------------------------------------------------------ E1 Gewohnheiten, Benachrichtigungszugriff

    @Test fun gewohnheitenNichtVorgewaehlt() {
        val g = datei("src/main/java/at/greenchili/surfmate/onboarding/schritte/GewohnheitenSchritt.kt")
        assertFalse(g.contains("wahl.waehle(\"ja\")"))
        assertTrue(g.contains("override fun weiterMoeglich(): Boolean = wahl.wert != null"))
        val k = datei("src/main/java/at/greenchili/surfmate/einstellungen/Katalog.java")
        assertTrue("ab Werk aus", k.contains("gew.standard = false;"))
    }

    @Test fun benachrichtigungszugriffEhrlich() {
        val f = datei("src/main/java/at/greenchili/surfmate/ui/fragment/WebViewFragment.kt")
        val open = f.substring(f.indexOf("fun openNotificationAccessSettings()"), f.indexOf("fun notificationAccessStatus()"))
        assertTrue("fehlende Seite heißt „unavailable“, nicht „ok“", open.contains("resolveActivity(act.packageManager) == null) return \"unavailable\""))
        val stat = f.substring(f.indexOf("fun notificationAccessStatus()"))
        assertTrue(stat.contains("getEnabledListenerPackages(ctx).contains(ctx.packageName)) return \"granted\""))
        assertTrue("nur lesen", !stat.substring(0, stat.indexOf("\"error\"")).contains("startActivity"))
        val js = datei("src/main/assets/web/shared/road-sense.js")
        assertTrue(js.contains("refreshNotificationAccess(") && js.contains("if (r === 'unavailable') this.refreshNotificationAccess('unavailable');"))
        assertTrue(datei("src/main/assets/web/local/road-sense.html").contains("id=\"rsNotifAccessStatus\""))
    }

    @Test fun launcherTextSagtDieWahrheit() {
        for (l in listOf("de", "en", "fr", "it", "nl")) {
            val j = JSONObject(datei("src/main/assets/web/i18n/$l.json"))
            val sys = j.getJSONObject("system")
            assertFalse(l, sys.getString("lnc_home_hint").contains("nie selbst") || sys.getString("lnc_home_hint").contains("never"))
        }
        val de = JSONObject(datei("src/main/assets/web/i18n/de.json")).getJSONObject("system").getString("lnc_home_hint")
        assertTrue(de.contains("Schalter") && de.contains("Alles zurück auf BYD"))
    }

    // ------------------------------------------------------------------ Texte in 5 Sprachen, ES5

    @Test fun texteIn5Sprachen() {
        for (d in listOf("values", "values-de", "values-fr", "values-it", "values-nl")) {
            val x = datei("src/main/res/$d/strings_einrichtung.xml")
            for (k in listOf("einr_st_freiwillig", "einr_empf_titel", "einr_empf_waechter", "einr_empf_handy",
                    "einr_empf_launcher", "einr_weitere_zeigen", "einr_weitere_verbergen"))
                assertTrue("$d/$k", Regex("<string name=\"$k\">[^<]+</string>").containsMatchIn(x))
        }
        for (l in listOf("de", "en", "fr", "it", "nl")) {
            val j = JSONObject(datei("src/main/assets/web/i18n/$l.json"))
            val e = j.getJSONObject("einr")
            for (k in listOf("einrichten_knopf", "nur_display", "weitere_zeigen", "weitere_verbergen"))
                assertTrue("$l/einr.$k", e.optString(k).isNotBlank())
            for (k in Einrichtung.EMPFOHLEN) assertTrue("$l/einr.empf.$k", e.getJSONObject("empf").optString(k).isNotBlank())
            assertTrue(e.getString("weitere_zeigen").contains("{anzahl}"))
            val r = j.getJSONObject("road_sense")
            for (k in listOf("notif_access_granted", "notif_access_missing", "notif_access_unavailable", "notif_access_nobridge"))
                assertTrue("$l/road_sense.$k", r.optString(k).isNotBlank())
        }
    }

    @Test fun chromium74() {
        val web = datei("src/main/assets/web/shared/einrichtung-web.js")
        for (verboten in listOf("?.", "??", "gap:", "inset:", "=>", "let ", "const "))
            assertFalse("einrichtung-web.js: $verboten", web.contains(verboten))
        val js = datei("src/main/assets/web/shared/road-sense.js")
        val teil = js.substring(js.indexOf("_notifAccessState()"), js.indexOf("// ==================== Destructive actions"))
        for (verboten in listOf("?.", "??", "=>")) assertFalse("road-sense.js: $verboten", teil.contains(verboten))
    }
}
