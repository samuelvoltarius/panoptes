package at.greenchili.panoptes.launcher

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View

/**
 * 0.1.41 (LO, Punkt 9): Android-Widgets fremder Apps (Wetter, Kalender, …) als Kachel „Android-Widget“.
 *
 * Ablauf, nur mit Zustimmung über Android selbst:
 * 1. eigene Liste der installierten Widgets ([anbieter]), der Nutzer tippt eines an,
 * 2. Widget-Nummer vom eigenen AppWidgetHost, Bindung mit [AppWidgetManager.bindAppWidgetIdIfAllowed];
 *    ohne Erlaubnis öffnet Android seinen Dialog ACTION_APPWIDGET_BIND („Erlauben, Widgets zu erstellen?“),
 * 3. hat das Widget eine Einrichtungsseite, öffnet Android sie,
 * 4. erst dann entsteht die Kachel mit der Widget-Nummer.
 * Keine Freigabe per adb-Befehl, kein Daemon. Bricht der Nutzer ab, wird die Nummer wieder freigegeben.
 */
class GeraeteWidgets(private val activity: Activity) {

    companion object {
        /** Eigene Host-Nummer des Launchers (beliebig, aber fest, sonst gehen Bindungen nach Neustart verloren). */
        const val HOST_ID = 4141
        const val REQ_BIND = 4142
        const val REQ_EINRICHTEN = 4143

        /** Rein: Widget-Nummer gültig (AppWidgetManager vergibt positive Zahlen). */
        fun gueltig(wid: Int?): Boolean = wid != null && wid > 0

        /** Rein: Kachelgröße aus der Mindestgröße des Widgets (Zelle ≈ 120 dp), auf erlaubte Größen gerundet. */
        fun groesse(minBreiteDp: Int, minHoeheDp: Int): List<Pair<Int, Int>> {
            val b = ((minBreiteDp + 119) / 120).coerceIn(1, 4)
            val h = ((minHoeheDp + 119) / 120).coerceIn(1, 3)
            val wunsch = listOf(b to h, 2 to 2, 2 to 1, 1 to 1)
            return wunsch.filter { it in Raster.GROESSEN }.distinct()
        }

        /**
         * Rein (0.1.41 E2E, G7): echte Kachelgröße in dp für das Widget. Viele Widgets (z. B. die Digitaluhr) richten
         * Schrift und Inhalt nach der gemeldeten Größe; ohne Meldung bleibt ihr Text winzig. null, solange nicht gemessen.
         */
        fun kachelDp(breitePx: Int, hoehePx: Int, dichte: Float): Pair<Int, Int>? {
            if (breitePx <= 0 || hoehePx <= 0 || dichte <= 0f) return null
            return (breitePx / dichte).toInt() to (hoehePx / dichte).toInt()
        }
    }

    private val manager = AppWidgetManager.getInstance(activity)
    val host = AppWidgetHost(activity.applicationContext, HOST_ID)

    /** Wartende Auswahl zwischen Bindung/Einrichtung und fertiger Kachel. */
    private var wartend: Int? = null
    private var fertig: ((Int, AppWidgetProviderInfo) -> Unit)? = null

    fun start() = runCatching { host.startListening() }
    fun stop() = runCatching { host.stopListening() }

    /** Installierte Widgets, nach Name sortiert. */
    fun anbieter(): List<Pair<String, AppWidgetProviderInfo>> = runCatching {
        manager.installedProviders.map { it.loadLabel(activity.packageManager).toString() to it }.sortedBy { it.first.lowercase() }
    }.getOrDefault(emptyList())

    /** Schritt 2–4 für das gewählte Widget; [ok] bekommt Widget-Nummer und Info, wenn alles erlaubt und eingerichtet ist. */
    fun hinzufuegen(info: AppWidgetProviderInfo, ok: (Int, AppWidgetProviderInfo) -> Unit) {
        val wid = runCatching { host.allocateAppWidgetId() }.getOrNull() ?: return
        wartend = wid; fertig = ok
        val gebunden = runCatching { manager.bindAppWidgetIdIfAllowed(wid, info.provider) }.getOrDefault(false)
        if (gebunden) { einrichten(wid); return }
        // Android fragt selbst (Systemdialog)
        val frage = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, wid)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
        if (runCatching { @Suppress("DEPRECATION") activity.startActivityForResult(frage, REQ_BIND) }.isFailure) abbrechen()
    }

    private fun einrichten(wid: Int) {
        val info = manager.getAppWidgetInfo(wid) ?: return abbrechen()
        if (info.configure != null) {
            val ok = runCatching {
                host.startAppWidgetConfigureActivityForResult(activity, wid, 0, REQ_EINRICHTEN, null)
            }.isSuccess
            if (!ok) abbrechen()
            return
        }
        abschliessen(wid)
    }

    private fun abschliessen(wid: Int) {
        val info = manager.getAppWidgetInfo(wid) ?: return abbrechen()
        val f = fertig
        wartend = null; fertig = null
        f?.invoke(wid, info)
    }

    private fun abbrechen() {
        wartend?.let { runCatching { host.deleteAppWidgetId(it) } }
        wartend = null; fertig = null
    }

    /** Aus MainActivity.onActivityResult: true, wenn das Ergebnis zu den Widgets gehörte. */
    fun ergebnis(requestCode: Int, resultCode: Int): Boolean {
        val wid = wartend
        if (requestCode != REQ_BIND && requestCode != REQ_EINRICHTEN) return false
        if (wid == null) return true
        if (resultCode != Activity.RESULT_OK) { abbrechen(); return true }
        if (requestCode == REQ_BIND) einrichten(wid) else abschliessen(wid)
        return true
    }

    /** Ansicht für eine Kachel; null, wenn das Widget nicht (mehr) da ist. */
    fun ansicht(ctx: Context, wid: Int): View? {
        val info = runCatching { manager.getAppWidgetInfo(wid) }.getOrNull() ?: return null
        return runCatching {
            host.createView(ctx.applicationContext, wid, info).apply { setAppWidget(wid, info); groesseMelden(this) }
        }.getOrNull()
    }

    /** G7: Sobald die Kachel gemessen ist (und bei jeder Größenänderung), dem Widget die Kachelgröße melden. */
    private fun groesseMelden(v: AppWidgetHostView) {
        var zuletzt: Pair<Int, Int>? = null
        v.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val dp = kachelDp(r - l, b - t, v.resources.displayMetrics.density)
            if (dp != null && dp != zuletzt) {
                zuletzt = dp
                // nach dem Layout-Durchgang, nicht mittendrin
                v.post {
                    @Suppress("DEPRECATION")
                    runCatching { v.updateAppWidgetSize(Bundle(), dp.first, dp.second, dp.first, dp.second) }
                }
            }
        }
    }

    /** Kachel entfernt: Bindung freigeben. */
    fun freigeben(wid: Int?) {
        if (gueltig(wid)) runCatching { host.deleteAppWidgetId(wid!!) }
    }
}
