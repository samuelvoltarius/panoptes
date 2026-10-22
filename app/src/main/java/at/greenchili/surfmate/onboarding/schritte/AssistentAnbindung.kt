package at.greenchili.surfmate.onboarding.schritte

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import at.greenchili.surfmate.R
import at.greenchili.surfmate.launcher.AdbDaemonLauncher
import at.greenchili.surfmate.onboarding.CameraWizardCoach
import at.greenchili.surfmate.onboarding.OnboardingOverlayView
import at.greenchili.surfmate.onboarding.OnboardingState
import at.greenchili.surfmate.ui.fragment.WebViewFragment
import com.google.android.material.button.MaterialButton

/**
 * Verbindet den Einrichtungsassistenten 0.1.10 ([Assistent]) mit den vorhandenen Abläufen der App.
 * Wichtig: „Läuft Panoptes?“ wird hier NUR über HTTP geprüft, nie über ADB – jede ADB-Abfrage löst das
 * Android-Popup „USB-Debugging zulassen?“ aus, und das darf erst im Schritt usb_freigabe nach der Erklärung kommen.
 */
internal class AssistentAnbindung(
    private val activity: Activity,
    private val wurzel: ViewGroup,
    private val adbLauncher: AdbDaemonLauncher,
    private val state: OnboardingState,
    /** Assistent beendet (fertig = Abschluss erreicht). */
    private val beendet: (fertig: Boolean) -> Unit,
    /** Kurze Führung durch Panoptes (DashboardTourCoach) im Host starten. */
    private val rundgangStarten: () -> Unit,
) : AssistentHost {

    private val haupt = Handler(Looper.getMainLooper())
    var assistent: Assistent? = null; private set
    private var webEbene: View? = null

    fun starten() {
        val a = Assistent(activity, wurzel, this) { fertig ->
            assistent = null
            schliesseWeb()
            if (fertig) state.onboardingComplete = true
            beendet(fertig)
        }
        assistent = a
        a.starten()
    }

    fun beenden() { assistent?.beenden(false); assistent = null; schliesseWeb() }

    // ---------------------------------------------------------------- AssistentHost

    override fun usbAnfragen() {
        // Erst hier (nach der Erklärung) den Start auslösen; das Android-Popup erscheint dabei.
        val ausgeloest = (activity as? at.greenchili.surfmate.ui.MainActivity)?.onboardingDaemonStarten() ?: false
        // Erneut tippen (Popup weggedrückt bzw. Start schon gelaufen): eine ADB-Verbindung bringt das Popup wieder;
        // nach „Zulassen“ übernimmt der vorhandene onAuthGranted-Weg in MainActivity.
        if (!ausgeloest) adbLauncher.isDaemonRunning { }
    }

    override fun daemonLaeuft(ergebnis: (Boolean) -> Unit) {
        Thread({
            val ok = EinrichtungApi.get("/status").let { it.status in 200..499 }   // erreichbar = läuft
            ergebnis(ok)
        }, "EinrichtungDaemonCheck").start()
    }

    override fun fahrzeugWaehlen(fertig: (Boolean) -> Unit) {
        val ma = activity as? at.greenchili.surfmate.ui.MainActivity
        val geoeffnet = ma?.openVehicleProfileForOnboarding { haupt.post { fertig(true) } } ?: false
        if (!geoeffnet) fertig(false)
    }

    override fun kamerasZuordnen(fertig: (Boolean) -> Unit) {
        // Vorhandener Kamera-Coach (Spotlights im echten Zuordnungs-Dialog) auf eigener Overlay-Ebene
        val ov = OnboardingOverlayView(activity, encoderBusy = { false })
        ov.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        wurzel.addView(ov)
        CameraWizardCoach(activity, ov, state, adbLauncher) { outcome ->
            (ov.parent as? ViewGroup)?.removeView(ov)
            haupt.post { fertig(outcome == CameraWizardCoach.Outcome.SAVED) }
        }.begin()
    }

    override fun fernzugang(fertig: (Boolean) -> Unit) {
        at.greenchili.surfmate.ui.setup.RemoteAccessDialog(activity) { verbunden ->
            if (verbunden) state.remoteAccessDone = true
            haupt.post { fertig(verbunden) }
        }.show()
    }

    /** Web-Seite der App über dem Assistenten zeigen, mit Knopf „Zurück zur Einrichtung“. */
    override fun seiteOeffnen(pfad: String) {
        val fa = activity as? FragmentActivity ?: return
        schliesseWeb()
        val ebene = FrameLayout(activity).apply {
            id = WEB_EBENE_ID
            setBackgroundColor(0xFF0B1A26.toInt())
            isClickable = true
            elevation = 64f
        }
        val inhalt = FrameLayout(activity).apply { id = WEB_INHALT_ID }
        ebene.addView(inhalt, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val zurueck = MaterialButton(activity).apply {
            text = activity.getString(R.string.einr_zurueck_zur_einrichtung)
            isAllCaps = false
            minHeight = 64
            setOnClickListener { schliesseWeb() }
        }
        ebene.addView(zurueck, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END).apply { setMargins(32, 32, 32, 32) })
        wurzel.addView(ebene, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        webEbene = ebene
        val web = WebViewFragment().apply { arguments = Bundle().apply { putString(WebViewFragment.ARG_PAGE_PATH, pfad) } }
        fa.supportFragmentManager.beginTransaction().replace(WEB_INHALT_ID, web, TAG_WEB).commitAllowingStateLoss()
    }

    private fun schliesseWeb() {
        val fa = activity as? FragmentActivity
        fa?.supportFragmentManager?.findFragmentByTag(TAG_WEB)?.let { fa.supportFragmentManager.beginTransaction().remove(it).commitAllowingStateLoss() }
        webEbene?.let { (it.parent as? ViewGroup)?.removeView(it) }
        webEbene = null
    }

    override fun rundgang() { rundgangStarten() }

    companion object {
        private const val WEB_EBENE_ID = 0x0F1E0001
        private const val WEB_INHALT_ID = 0x0F1E0002
        private const val TAG_WEB = "einrichtung_web"
    }
}
