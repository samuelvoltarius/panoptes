package at.greenchili.surfmate.onboarding.schritte

import at.greenchili.surfmate.BuildConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 0.1.15: Der Schein-§0 für Screenshots darf in der Auto-Build nie aktiv werden. Die Unit-Tests laufen gegen
 * dieselbe Variante wie die Auto-APK (debug, ENTWICKLUNG = true) – SCREENSHOT_MODUS muss dort false sein und
 * [ScheinEinrichtung.aktivieren] sofort zurückkehren, ohne den Transport zu ersetzen.
 */
class ScheinEinrichtungAusTest {

    @Test
    fun screenshotModusIstInDerAutoBuildAus() {
        assertFalse(BuildConfig.SCREENSHOT_MODUS)
    }

    @Test
    fun aktivierenKehrtSofortZurueck() {
        val vorher = EinrichtungApi.transport
        ScheinEinrichtung.aktivieren()
        assertSame(vorher, EinrichtungApi.transport)
    }
}
