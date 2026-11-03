package at.greenchili.surfmate.onboarding.schritte

import org.junit.Assert.assertEquals
import org.junit.Test

class AssistentWebPfadTest {
    @Test
    fun `relative Seite bekommt fuehrenden Slash`() {
        assertEquals("/system.html#kopplung", normalisiereAssistentWebPfad("system.html#kopplung"))
    }

    @Test
    fun `kanonischer Pfad bleibt unveraendert`() {
        assertEquals("/system#planerKarte", normalisiereAssistentWebPfad("/system#planerKarte"))
    }

    @Test
    fun `leerer Pfad zeigt Startseite`() {
        assertEquals("/", normalisiereAssistentWebPfad("  "))
    }
}
