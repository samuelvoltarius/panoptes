package at.greenchili.panoptes.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * 0.1.44 (P1, Punkt 1, Weg 2): Bedienungshilfe des Panoptes Launchers für „Zurück“ und „Letzte App“.
 *
 * - Läuft nur, wenn der Nutzer sie selbst unter Android-Einstellungen → Bedienungshilfen einschaltet. Der Launcher
 *   öffnet nur diese Seite ([einstellungOeffnen]); er schaltet sie nie selbst ein (kein Schreiben in Systemeinstellungen,
 *   kein Daemon).
 * - Liest keine Bildschirminhalte (canRetrieveWindowContent = false, res/xml/bedienhilfe.xml). Gemerkt werden nur die
 *   Paketnamen der zuletzt vorn gewesenen Apps, nur im Speicher ([AppVerlauf]).
 */
class PanoptesBedienhilfe : AccessibilityService() {

    companion object {
        @Volatile var aktiv: PanoptesBedienhilfe? = null
            private set

        fun einstellungOeffnen(ctx: Context): Boolean = runCatching {
            ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }

    @Volatile private var verlauf: List<String> = emptyList()
    @Volatile private var aktuell: String? = null
    private var ignorieren: Set<String> = emptySet()

    override fun onServiceConnected() {
        super.onServiceConnected()
        ignorieren = startbildschirme() + setOf(packageName, "com.android.systemui", "android")
        aktiv = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val p = event.packageName?.toString() ?: return
        // Tastaturen, Dialoge des Systems u. Ä. haben keinen Start-Eintrag: kein Wechselziel
        if (p !in ignorieren && packageManager.getLaunchIntentForPackage(p) == null) return
        aktuell = p
        verlauf = AppVerlauf.merke(verlauf, p, ignorieren)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        if (aktiv === this) aktiv = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (aktiv === this) aktiv = null
        super.onDestroy()
    }

    fun zurueck(): Boolean = runCatching { performGlobalAction(GLOBAL_ACTION_BACK) }.getOrDefault(false)

    /** Direkt zur vorigen App; ohne Verlauf die Android-Übersicht. */
    fun letzteApp(): Boolean {
        val ziel = AppVerlauf.vorige(verlauf, aktuell)
        val i = ziel?.let { packageManager.getLaunchIntentForPackage(it) }
        if (i != null) return runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        return runCatching { performGlobalAction(GLOBAL_ACTION_RECENTS) }.getOrDefault(false)
    }

    private fun startbildschirme(): Set<String> = runCatching {
        packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.toSet()
    }.getOrDefault(emptySet())
}
