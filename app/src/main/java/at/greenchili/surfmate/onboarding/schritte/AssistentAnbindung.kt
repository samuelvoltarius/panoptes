package at.greenchili.surfmate.onboarding.schritte

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
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
) : AssistentHost {

    private val haupt = Handler(Looper.getMainLooper())
    var assistent: Assistent? = null; private set
    private var webEbene: View? = null
    /** 0.1.38.11 (M1): Zurück-Taste schließt die Seiten-Ebene (nur solange sie offen ist). */
    private var zurueckRueckruf: androidx.activity.OnBackPressedCallback? = null

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
        val ausgeloest = (activity as? at.greenchili.surfmate.ui.MainActivity)
            ?.onboardingDaemonStarten(erneut = true) ?: false
        // Nur bei einem anderen Host direkt prüfen. MainActivity startet bei „erneut“ den echten Daemon-Weg neu;
        // ein bloßer Prozess-Check würde nach einem fehlgeschlagenen ersten Versuch nichts reparieren.
        if (!ausgeloest) adbLauncher.isDaemonRunning { }
    }

    override fun istBydInfotainment(): Boolean {
        val berechtigung = try {
            activity.packageManager.getPermissionInfo("android.permission.BYDAUTO_BODYWORK_GET", 0)
            true
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            false
        }
        return UsbFreigabePlattform.istBydInfotainment(
            berechtigung,
            android.os.Build.MANUFACTURER,
            android.os.Build.MODEL,
        )
    }

    override fun daemonLaeuft(ergebnis: (Boolean) -> Unit) {
        Thread({
            val ok = EinrichtungApi.get("/status").let { it.status in 200..499 }   // erreichbar = läuft
            ergebnis(ok)
        }, "EinrichtungDaemonCheck").start()
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

    /**
     * Web-Seite der App über dem Assistenten zeigen. Die Seite läuft eingebettet (ohne Web-Menüleiste), darum
     * 0.1.38.11 (M1, Nutzer „die Menüleiste fehlt“): Kopfzeile „Einrichtung · <Schritt>“ mit großem „← Zurück zur
     * Einrichtung“, einem Satz, wann das Menü wiederkommt, und „Einrichtung unterbrechen“; die Zurück-Taste schließt die
     * Ebene. Der Knopf unten rechts bleibt (so kennen ihn die Nutzer).
     */
    override fun seiteOeffnen(pfad: String, titel: CharSequence?) {
        val fa = activity as? FragmentActivity ?: return
        schliesseWeb()
        val dp = activity.resources.displayMetrics.density
        fun px(v: Float) = (v * dp).toInt()
        val ebene = FrameLayout(activity).apply {
            id = WEB_EBENE_ID
            setBackgroundColor(0xFF0B1A26.toInt())
            isClickable = true
            elevation = 64f
        }
        val spalte = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val kopf = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(16f), px(8f), px(16f), px(8f))
            setBackgroundColor(0xFF06121C.toInt())
        }
        kopf.addView(MaterialButton(activity).apply {
            text = "\u2190 " + activity.getString(R.string.einr_zurueck_zur_einrichtung)
            isAllCaps = false
            minHeight = maxOf(64, px(56f))
            textSize = 20f
            setOnClickListener { schliesseWeb() }
        })
        val texte = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20f), 0, px(20f), 0)
        }
        texte.addView(TextView(activity).apply {
            text = if (titel.isNullOrEmpty()) activity.getString(R.string.einr_ebene_titel_ohne)
                else activity.getString(R.string.einr_ebene_titel, titel)
            textSize = 20f
            setTextColor(0xFFEAF6F8.toInt())
        })
        texte.addView(TextView(activity).apply {
            text = activity.getString(R.string.einr_ebene_menue)
            textSize = 15f
            setTextColor(0xFFA9C2CC.toInt())
        })
        kopf.addView(texte, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        kopf.addView(MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = activity.getString(R.string.einr_unterbrechen)
            isAllCaps = false
            minHeight = maxOf(64, px(56f))
            setOnClickListener { unterbrechen() }
        })
        spalte.addView(kopf, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val inhalt = FrameLayout(activity).apply { id = WEB_INHALT_ID }
        spalte.addView(inhalt, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        ebene.addView(spalte, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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
        val rueckruf = object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { schliesseWeb() }
        }
        (activity as? androidx.activity.ComponentActivity)?.onBackPressedDispatcher?.addCallback(rueckruf)
        zurueckRueckruf = rueckruf
        val web = WebViewFragment().apply {
            arguments = Bundle().apply {
                putString(WebViewFragment.ARG_PAGE_PATH, normalisiereAssistentWebPfad(pfad))
            }
        }
        fa.supportFragmentManager.beginTransaction().replace(WEB_INHALT_ID, web, TAG_WEB).commitAllowingStateLoss()
    }

    private fun schliesseWeb() {
        val fa = activity as? FragmentActivity
        fa?.supportFragmentManager?.findFragmentByTag(TAG_WEB)?.let { fa.supportFragmentManager.beginTransaction().remove(it).commitAllowingStateLoss() }
        webEbene?.let { (it.parent as? ViewGroup)?.removeView(it) }
        webEbene = null
        zurueckRueckruf?.remove()
        zurueckRueckruf = null
    }

    /**
     * 0.1.38.11 (M1): „Einrichtung unterbrechen“ – Assistent zu (beenden(false) markiert nichts als erledigt) und zurück
     * zur Stelle, von der man meist kommt: Einstellungen → Einrichtung, mit Menüleiste.
     */
    override fun unterbrechen() {
        beenden()
        android.widget.Toast.makeText(activity, R.string.einr_unterbrochen, android.widget.Toast.LENGTH_LONG).show()
        (activity as? at.greenchili.surfmate.ui.MainActivity)?.zeigeWebSeite(EINRICHTUNG_SEITE)
    }


    companion object {
        private const val WEB_EBENE_ID = 0x0F1E0001
        private const val WEB_INHALT_ID = 0x0F1E0002
        private const val TAG_WEB = "einrichtung_web"
        /** Ziel von „Einrichtung unterbrechen“: die Karte „Einrichtung“ in den Einstellungen (system.html:668). */
        const val EINRICHTUNG_SEITE = "/system.html#einrichtung"

        /**
         * 0.1.38.11 (M1): Nach einem Neuaufbau der Activity stellt der FragmentManager das Ebenen-Fragment wieder her,
         * ohne die Ebene (Container 0x0F1E0002 fehlt) – im Emulator belegt: kein Absturz, aber ein unsichtbares
         * WebViewFragment, das die Seite nachlädt. Darum beim Neuaufbau entfernen.
         */
        @JvmStatic
        fun verwaisteEbeneEntfernen(fa: FragmentActivity) {
            val f = fa.supportFragmentManager.findFragmentByTag(TAG_WEB) ?: return
            fa.supportFragmentManager.beginTransaction().remove(f).commitAllowingStateLoss()
        }
    }
}

/**
 * [WebViewFragment] hängt diesen Wert direkt an `http://127.0.0.1:<port>`.
 * Ohne führenden Slash entsteht z. B. `:8180system.html` und die Ladeblende
 * bleibt auf alten WebViews ohne verwertbaren Fehler stehen.
 */
internal fun normalisiereAssistentWebPfad(pfad: String): String {
    val sauber = pfad.trim()
    return when {
        sauber.isEmpty() -> "/"
        sauber.startsWith("/") -> sauber
        else -> "/$sauber"
    }
}
