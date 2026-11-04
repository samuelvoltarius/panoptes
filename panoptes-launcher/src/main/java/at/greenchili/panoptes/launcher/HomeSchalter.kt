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
