package at.greenchili.panoptes.launcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Schaltet den HOME-Alias des Launchers (eigene Komponente, dafür braucht eine App keine Berechtigung). Im Manifest ist
 * er aus. Ein: der Launcher erscheint dem System als Startbildschirm. Aus: Android nimmt wieder den BYD-Startbildschirm.
 */
object HomeSchalter {
    private fun alias(c: Context) = ComponentName(c.packageName, "at.greenchili.panoptes.launcher.HomeAlias")

    fun istAn(c: Context): Boolean = try {
        c.packageManager.getComponentEnabledSetting(alias(c)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    } catch (_: Exception) {
        false
    }

    private const val PREFS = "launcher"
    private const val KEY_LOKAL = "home_lokal_gewaehlt"

    /** 0.1.43.4 (Paket S): Startbildschirm wurde OHNE Panoptes im Launcher gewählt (Panoptes übernimmt das später). */
    fun lokal(c: Context): Boolean = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LOKAL, false)

    fun lokalMerken(c: Context, an: Boolean) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_LOKAL, an).apply()
    }

    /** Android-Seite „Standard-Startbildschirm“ (dort wählt der Nutzer selbst), sonst die Android-Einstellungen. */
    fun androidWahlOeffnen(c: Context): Boolean = listOf(android.provider.Settings.ACTION_HOME_SETTINGS, android.provider.Settings.ACTION_SETTINGS).any { a ->
        try {
            c.startActivity(android.content.Intent(a).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); true
        } catch (_: Exception) {
            false
        }
    }

    fun setze(c: Context, an: Boolean) {
        try {
            c.packageManager.setComponentEnabledSetting(
                alias(c),
                if (an) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (_: Exception) {
        }
    }
}
