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
            val name = ri.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() } ?: paket
            val icon = try { ri.loadIcon(pm) } catch (_: Throwable) { null }
            val video = ri.activityInfo.applicationInfo?.category == ApplicationInfo.CATEGORY_VIDEO
            InstallierteApp(paket, name, icon, video)
        }
        val c = Collator.getInstance()
        return roh.sortedWith { a, b -> c.compare(a.name, b.name) }
    }
}
