package at.greenchili.panoptes.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import java.text.Collator
import java.util.Locale

/** Eine startbare App aus dem Android-PackageManager (gleiche Liste wie jeder Launcher). */
data class InstallierteApp(val paket: String, val name: String, val icon: Drawable?, val video: Boolean)

/** Reine Hilfen für die App-Schublade, im Unit-Test belegt. */
object AppSuche {
    /** Filtert nach Name oder Paket, ohne Groß/Klein und Akzente; leere Suche = alle. */
    fun filter(apps: List<Pair<String, String>>, suche: String?): List<Pair<String, String>> {
        val q = normal(suche.orEmpty())
        if (q.isEmpty()) return apps
        return apps.filter { (paket, name) -> normal(name).contains(q) || paket.lowercase(Locale.ROOT).contains(q) }
    }

    fun normal(s: String): String = java.text.Normalizer.normalize(s.trim().lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
}

/**
 * 0.1.43.3 (Befund L3-Bild): Anzeigename einer App – nie der Paketname. Reihenfolge: Label der App; fehlt es, für die
 * eigene Panoptes-App „Panoptes“; sonst ein neutrales Wort („App“).
 */
object AppName {
    const val PANOPTES_NAME = "Panoptes"

    fun anzeige(paket: String, label: String?, ohneName: String): String =
        label?.trim()?.takeIf { it.isNotEmpty() && it != paket }
            ?: if (paket == Raster.PANOPTES) PANOPTES_NAME else ohneName

    /** Label über den PackageManager (auch ohne LAUNCHER-Eintrag); nicht sichtbar/nicht installiert = null. */
    fun label(ctx: Context, paket: String): String? = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(paket, 0)).toString()
    }.getOrNull()

    fun anzeige(ctx: Context, paket: String, bekannt: String?): String =
        anzeige(paket, bekannt?.takeIf { it.isNotBlank() && it != paket } ?: label(ctx, paket), ctx.getString(R.string.app_ohne_name))
}

object AppQuelle {
    /**
     * Alle Apps mit MAIN/LAUNCHER, alphabetisch, ohne den Launcher selbst. Läuft im Hintergrund-Thread
     * (Icons laden kostet Zeit). BYD-eigene Apps erscheinen hier, sofern sie einen LAUNCHER-Eintrag haben.
     */
    fun laden(ctx: Context): List<InstallierteApp> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val gesehen = HashSet<String>()
        val roh = pm.queryIntentActivities(intent, 0).mapNotNull { ri ->
            val paket = ri.activityInfo?.packageName ?: return@mapNotNull null
            if (paket == ctx.packageName || !gesehen.add(paket)) return@mapNotNull null
            val name = AppName.anzeige(paket, ri.loadLabel(pm)?.toString(), ctx.getString(R.string.app_ohne_name))
            val icon = try { ri.loadIcon(pm) } catch (_: Throwable) { null }
            val video = ri.activityInfo.applicationInfo?.category == ApplicationInfo.CATEGORY_VIDEO
            InstallierteApp(paket, name, icon, video)
        }
        val c = Collator.getInstance()
        return roh.sortedWith { a, b -> c.compare(a.name, b.name) }
    }
}
