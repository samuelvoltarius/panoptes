package at.greenchili.surfmate.onboarding.schritte

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Regel B (nie aussperren): Der Daemon-Start darf NUR bei einer echten Neuinstallation aufgeschoben werden.
 * Bestand mit laufendem oder autorisiertem Daemon (z. B. Alfreds Auto beim Update) startet SOFORT.
 */
class DaemonStartRegelTest {

    @Test fun nurNeuinstallationOhneDaemonWirdAufgeschoben() {
        assertTrue(DaemonStartRegel.aufschieben(daemonAutorisiert = false, einrichtungFertig = false, daemonAntwortet = false, nachUpdate = false))
    }

    @Test fun bestandMitAutorisiertemDaemonStartetSofort() {
        assertFalse(DaemonStartRegel.mussPruefen(daemonAutorisiert = true, einrichtungFertig = false, nachUpdate = false))
        assertFalse(DaemonStartRegel.aufschieben(true, false, false, false))
    }

    @Test fun bestandMitLaufendemDaemonStartetSofort() {
        assertFalse(DaemonStartRegel.aufschieben(daemonAutorisiert = false, einrichtungFertig = false, daemonAntwortet = true, nachUpdate = false))
    }

    @Test fun abgeschlosseneEinrichtungStartetSofort() {
        assertFalse(DaemonStartRegel.mussPruefen(false, einrichtungFertig = true, nachUpdate = false))
        assertFalse(DaemonStartRegel.aufschieben(false, true, false, false))
    }

    @Test fun startNachUpdateStartetSofort() {
        assertFalse(DaemonStartRegel.mussPruefen(false, false, nachUpdate = true))
        assertFalse(DaemonStartRegel.aufschieben(false, false, false, true))
    }

    @Test fun alleKombinationenAusserEinerStartenSofort() {
        var aufgeschoben = 0
        for (a in listOf(true, false)) for (f in listOf(true, false)) for (d in listOf(true, false)) for (u in listOf(true, false)) {
            if (DaemonStartRegel.aufschieben(a, f, d, u)) aufgeschoben++
        }
        assertEquals(1, aufgeschoben)
    }

    /** Quelltext-Vertrag: MainActivity schiebt nur über die Regel auf, Startweg unverändert, Ende des Assistenten startet. */
    @Test fun mainActivityNutztDieRegelUndStartetSpaetestensAmEnde() {
        val ma = String(Files.readAllBytes(Paths.get("src/main/java/at/greenchili/surfmate/ui/MainActivity.kt")), Charsets.UTF_8)
        val start = ma.indexOf("private fun runDaemonStartup(intent: android.content.Intent?, fromOnCreate: Boolean)")
        val jetzt = ma.indexOf("private fun runDaemonStartupJetzt(intent: android.content.Intent?, fromOnCreate: Boolean)")
        assertTrue(start > 0 && jetzt > start)
        val entscheidung = ma.substring(start, jetzt)
        // Schnellweg ohne HTTP für Bestand/Update/onNewIntent
        assertTrue(entscheidung.contains("if (!fromOnCreate || !regel.mussPruefen(st.daemonAuthorized, st.onboardingComplete, nachUpdate))"))
        // Aufschieben nur, wenn die Regel es sagt; sonst sofort
        assertTrue(entscheidung.contains("if (regel.aufschieben(st.daemonAuthorized, st.onboardingComplete, antwortet, nachUpdate))"))
        assertTrue(entscheidung.contains("runDaemonStartupJetzt(intent, fromOnCreate)"))
        // Kein ADB in der Entscheidung (ADB würde das Popup auslösen)
        assertFalse(entscheidung.contains("adbLauncher") || entscheidung.contains("clearStaleSentinels"))
        // Der eigentliche Startweg enthält weiterhin initializeOnAppLaunch und clearStaleSentinels
        val rest = ma.substring(jetzt)
        assertTrue(rest.contains("daemonStartupManager.clearStaleSentinels()"))
        assertTrue(rest.contains("daemonStartupManager.initializeOnAppLaunch()"))
        // Nachholen: onboardingDaemonStarten ruft den unveränderten Startweg
        val nach = ma.substring(ma.indexOf("fun onboardingDaemonStarten()"))
        assertTrue(nach.substring(0, 700).contains("runDaemonStartupJetzt(p.first, p.second)"))
        // Ende des Assistenten (auch ACC-ON) startet spätestens dann
        val host = String(Files.readAllBytes(Paths.get("src/main/java/at/greenchili/surfmate/onboarding/OnboardingHost.kt")), Charsets.UTF_8)
        val dismiss = host.substring(host.indexOf("fun dismiss()"))
        assertTrue(dismiss.substring(0, 500).contains("onboardingDaemonStarten()"))
    }
}
