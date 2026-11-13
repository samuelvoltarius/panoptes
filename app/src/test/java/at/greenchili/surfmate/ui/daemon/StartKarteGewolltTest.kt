package at.greenchili.surfmate.ui.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.1.43 S2 (Foto Projektinhaber, Surf: Chip „Wächter aus“, darunter „Wächter ✗ startet nicht“): Die Startkarte führt nur, was
 * gewollt laufen soll. Ein gewollt ausgeschalteter oder vom Nutzer angehaltener Dienst ist keine Zeile und kein ✗;
 * der Ablauf ([DaemonStartPlan.stufen]) bleibt unverändert. Chip und Karte lesen dieselbe Einstellung.
 * S3: Reiter „Bildschirm im Auto“ heißt, was die Seite zeigt, und fehlt am Display.
 */
class StartKarteGewolltTest {

    private val alle = DaemonStartPlan.stufen(DaemonStartPlan.Einstellungen(tailscaleAn = true, restAn = true))

    @Test
    fun waechterAusIstKeineStufeUndKeinFehler() {
        val g = DaemonStartPlan.Gewollt(waechterAn = false, telegramAn = true)
        assertEquals(listOf(StartStufe.ZUGANG, StartStufe.OBERFLAECHE, StartStufe.REST), DaemonStartPlan.anzeige(alle, g))
        // Ablauf unverändert: der Wächter-Stapel wird weiter ausgelöst (ACC-Richter), nur nicht angezeigt
        assertTrue(StartStufe.WAECHTER in alle)

        // Karte: ohne Stufe kein ✗, kein „Nicht alles ist gestartet“
        StartStatus.beginne(DaemonStartPlan.anzeige(alle, g), 0L)
        StartStatus.melde(StartStufe.WAECHTER, StufeZustand.STARTET_NICHT, 20_000L)
        assertFalse(StartStatus.stand(21_000L).any { it.first == StartStufe.WAECHTER })
        assertFalse(StartStatus.stand(21_000L).any { it.second == StufeZustand.STARTET_NICHT })
    }

    @Test
    fun waechterAnUndNichtDaBleibtEchterFehler() {
        val g = DaemonStartPlan.Gewollt(waechterAn = true, telegramAn = false)
        val a = DaemonStartPlan.anzeige(alle, g)
        assertEquals(listOf(StartStufe.ZUGANG, StartStufe.OBERFLAECHE, StartStufe.WAECHTER), a)
        StartStatus.beginne(a, 0L)
        StartStatus.melde(StartStufe.WAECHTER, StufeZustand.STARTET_NICHT, 20_000L)
        assertTrue(StartStatus.stand(21_000L).contains(StartStufe.WAECHTER to StufeZustand.STARTET_NICHT))
    }

    @Test
    fun angehalteneDiensteSindKeinFehler() {
        val g = DaemonStartPlan.Gewollt(waechterAn = true, telegramAn = true,
            gestoppt = setOf(StartStufe.ZUGANG, StartStufe.WAECHTER, StartStufe.REST))
        assertEquals(listOf(StartStufe.OBERFLAECHE), DaemonStartPlan.anzeige(alle, g))
    }

    @Test
    fun stoppMarkeWieIfNotUserStopped() {
        assertFalse("keine Marke", DaemonStartPlan.nutzerGestoppt(null))
        assertTrue(DaemonStartPlan.nutzerGestoppt("disabled by ui at Fri Nov 13"))
        assertTrue(DaemonStartPlan.nutzerGestoppt("disabled by telegram"))
        assertTrue(DaemonStartPlan.nutzerGestoppt("disabled by user"))
        assertTrue(DaemonStartPlan.nutzerGestoppt("disabled by 12-V-Schlaf"))
        assertTrue("leer = angehalten (wie die Shell-Prüfung)", DaemonStartPlan.nutzerGestoppt(""))
        assertFalse("Maschinen-Marke räumt der Start weg", DaemonStartPlan.nutzerGestoppt("disabled by update"))
    }

    private fun lies(p: String): String {
        val f = listOf(File(p), File("app/$p")).first { it.exists() }
        return f.readText().replace("\r\n", "\n")
    }

    @Test
    fun eingehaengtEineQuelle() {
        val m = lies("src/main/java/at/greenchili/surfmate/ui/daemon/DaemonStartupManager.kt")
        assertTrue(m.contains("val anzeige = DaemonStartPlan.anzeige(stufen, startGewollt())"))
        assertTrue(m.contains("UnifiedConfigManager.isSurveillanceEnabled()"))
        assertTrue(m.contains("StartStatus.beginne(anzeige,"))
        // Chip, Wächter-Zeile und Kern: dieselbe Einstellung (gewollt)
        assertTrue(lies("src/main/java/at/greenchili/surfmate/daemon/CameraDaemon.java")
            .contains("status.put(\"gewollt\", at.greenchili.surfmate.config.UnifiedConfigManager.isSurveillanceEnabled());"))
        val shell = lies("src/main/assets/web/shared/app-shell.js")
        assertTrue(shell.contains("else if (st.enabled || st.gewollt) setPill('pnPillGuard'"))
        assertTrue(lies("src/main/assets/web/shared/waechter-zustand.js").contains("(s.enabled || s.gewollt)"))
    }

    @Test
    fun fernansichtReiter() {
        val shell = lies("src/main/assets/web/shared/app-shell.js")
        assertTrue(shell.contains("{ href: 'remote-dev-view.html', label: 'Bildschirm im Auto', i18n: 'shell.page_bildschirm', fern: true, stufe: 'c' }"))   // stufe: E4 (Modus Einfach)
        assertTrue(shell.contains("return !!(p.hidden || (p.fern && dstDisplay()));"))
        assertFalse(shell.contains("Bildschirm vom Handy"))
        assertTrue(lies("src/main/assets/web/shared/system-overview.js").contains("shell.seiteAus ? shell.seiteAus(p) : p.hidden"))
        // Alltagssprache (REGEL DAU, MenueEineQuelleTest: kein „Fernansicht“ im Menü): was man sieht
        val erwartet = mapOf("de" to "Bildschirm im Auto", "en" to "Car screen", "fr" to "Écran de la voiture",
            "it" to "Schermo dell'auto", "nl" to "Scherm in de auto")
        for ((l, v) in erwartet) {
            val j = org.json.JSONObject(lies("src/main/assets/web/i18n/$l.json"))
            assertEquals(l, v, j.getJSONObject("shell").getString("page_bildschirm"))
            assertFalse("nicht mehr „vom Handy“ $l", j.getJSONObject("shell").getString("page_bildschirm").contains("Handy"))
        }
        // ES5 in app-shell.js
        val code = shell.replace(Regex("(?s)/\\*.*?\\*/"), "").replace(Regex("//[^\n]*"), "")
        for (v in listOf("?.", "??", "=>", "`", "const ", "let ")) assertFalse("ES5: $v", code.contains(v))
    }
}
