package at.greenchili.surfmate.ui.util

import at.greenchili.surfmate.testutil.Quelltext
import at.greenchili.surfmate.ui.model.DaemonType
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Paths

/**
 * Koordination 0.1.9 (#12): Tailscale bei NEUinstallation aus, Bestand unverändert (Regel B, nie aussperren).
 */
class TailscaleStandardTest {

    private val ts = DaemonType.TAILSCALE_TUNNEL

    /** Alfreds Auto: daemons.TAILSCALE_TUNNEL=true gewinnt immer, egal wie die Installation erkannt wird. */
    @Test
    fun bestandMitEintragBleibtAn() {
        val shared = JSONObject().put("TAILSCALE_TUNNEL", true)
        assertTrue(PreferencesManager.aktiv(ts, shared, false, emptySet(), bestand = false))
        assertTrue(PreferencesManager.aktiv(ts, shared, true, emptySet(), bestand = false))
        assertTrue(PreferencesManager.aktiv(ts, shared, false, emptySet(), bestand = true))
    }

    @Test
    fun ausgeschaltetBleibtAus() {
        val shared = JSONObject().put("TAILSCALE_TUNNEL", false)
        assertFalse(PreferencesManager.aktiv(ts, shared, true, setOf(ts), bestand = true))
    }

    @Test
    fun ohneEintragNurBestandAn() {
        assertFalse(PreferencesManager.aktiv(ts, null, false, emptySet(), bestand = false))
        assertFalse(PreferencesManager.aktiv(ts, JSONObject(), false, emptySet(), bestand = false))
        assertTrue(PreferencesManager.aktiv(ts, null, false, emptySet(), bestand = true))
        // lokale Liste (alte App-Einstellung) gilt vor dem Standard
        assertTrue(PreferencesManager.aktiv(ts, null, true, setOf(ts), bestand = false))
        assertFalse(PreferencesManager.aktiv(ts, null, true, emptySet(), bestand = true))
        // andere Typen nie von selbst an
        assertFalse(PreferencesManager.aktiv(DaemonType.WIREGUARD_TUNNEL, null, false, emptySet(), bestand = true))
        assertFalse(PreferencesManager.aktiv(DaemonType.TELEGRAM_DAEMON, null, false, emptySet(), bestand = true))
    }

    @Test
    fun bestandErkennung() {
        assertFalse(PreferencesManager.istBestand(1_000L, 1_000L, false))   // frisch installiert
        assertTrue(PreferencesManager.istBestand(1_000L, 2_000L, false))    // aktualisiert
        assertTrue(PreferencesManager.istBestand(1_000L, 1_000L, true))     // alte Daemon-Config vorhanden
    }

    /** Verdrahtung: Festschreiben beim Start, Fernzugang schaltet dauerhaft ein, Server ohne Tailscale-Standard. */
    @Test
    fun verdrahtung() {
        val pm = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/ui/util/PreferencesManager.kt"))
        assertTrue(pm.contains("Thread({ festschreibenTailscale() }"))
        assertFalse(pm.contains("else -> type in DEFAULT_ON"))
        val dlg = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/ui/setup/RemoteAccessDialog.kt"))
        assertTrue(dlg.contains("DaemonType.TAILSCALE_TUNNEL, true)"))
        val sys = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/SystemApiHandler.java"))
        assertFalse(sys.contains("DEFAULT_ON.add(DaemonType.TAILSCALE_TUNNEL)"))
    }

    /** Datenschutz-Audit Nr. 8: keine Avatare mehr von github.com. */
    @Test
    fun keinGithubAvatar() {
        val about = Quelltext.lies(
            Paths.get("src/main/java/at/greenchili/surfmate/ui/fragment/settings/SettingsAboutFragment.kt"))
        assertFalse(about.contains(".png?size="))
        assertFalse(about.contains("fetchAvatarBitmap"))
    }
}
