package at.greenchili.panoptes.launcher

import android.content.Context
import android.content.Intent

/**
 * 0.1.43.3 (Rang 7) Sprache im Launcher sichtbar: Daten für den Assistent-Knopf und „Was kann ich sagen?“.
 *
 * Quelle ist allein der Kern (`GET /api/launcher/v1/sprache`), der die Sätze aus den Panoptes-Sprachbefehlen liefert
 * (eine Quelle mit der Erkennung). Der Launcher hört selbst nie zu: Sprechen und Ausführen laufen in Panoptes
 * (`.sprache.SprachStart`, Fahrzeugbefehle dort immer mit Rückfrage).
 */
data class SprachEintrag(val befehl: String, val kategorie: String, val art: String, val saetze: List<String>)

data class SprachKatalog(
    val an: Boolean,
    val weckwort: Boolean,
    val weckwortSatz: String,
    val sprache: String,
    val erkennbar: Boolean,
    val modellDa: Boolean,
    val befehle: List<SprachEintrag>,
    val nie: List<String>
) {
    /** Kategorien, die es wirklich gibt (in fester Reihenfolge); leere Gruppen fallen weg. */
    fun kategorien(): List<String> = Sprache.KATEGORIEN.filter { k -> befehle.any { it.kategorie == k } }

    /** Filter für Suche und Chip. `kategorie` null = alle. Suche über Titel und Sätze, ohne Groß/Klein. */
    fun filter(suche: String, kategorie: String?, titel: (String) -> String): List<SprachEintrag> {
        val q = suche.trim().lowercase()
        return befehle.filter { e ->
            (kategorie == null || e.kategorie == kategorie) &&
                (q.isEmpty() || titel(e.befehl).lowercase().contains(q) || e.saetze.any { it.lowercase().contains(q) })
        }
    }

    /** „Zum Ausprobieren“: je Kategorie der erste Befehl mit seinem ersten Satz, höchstens [max]. */
    fun probieren(max: Int = 6): List<Pair<String, String>> {
        val l = ArrayList<Pair<String, String>>()
        for (k in kategorien()) befehle.filter { it.kategorie == k }.take(2).forEach { e ->
            e.saetze.firstOrNull()?.let { if (l.size < max) l.add(e.befehl to it) }
        }
        return l
    }

    /** Was oben steht: aus, Modell fehlt, Sprache noch nicht, bereit. */
    fun zustand(): String = when {
        !an -> "aus"
        !erkennbar -> "noch_nicht"
        !modellDa -> "kein_modell"
        else -> "bereit"
    }

    /** Assistent-Knopf nur, wenn der Fahrer Sprachbefehle in Panoptes eingeschaltet hat (ab Werk nichts sichtbar). */
    fun knopfSichtbar(knopfSchalter: Boolean): Boolean = an && knopfSchalter
}

object Sprache {
    const val ZIEL = ".sprache.SprachStart"
    const val EXTRA_BEFEHL = "befehl"
    val KATEGORIEN = listOf("klima", "navigation", "medien", "allgemein")
    /** Ehrliche Plakette je Art (Kern: SprachBefehle.ART). */
    val ARTEN = listOf("lesen", "rueckfrage", "vorschau", "medien")

    private const val PREFS = "panoptes_launcher_sprache"
    const val KEY_KNOPF = "sprach_knopf"

    fun parse(json: String?): SprachKatalog? = runCatching {
        val o = org.json.JSONObject(json ?: return null)
        val b = o.optJSONArray("befehle") ?: org.json.JSONArray()
        val liste = (0 until b.length()).mapNotNull { i ->
            val e = b.optJSONObject(i) ?: return@mapNotNull null
            val art = e.optString("art")
            val kat = e.optString("kategorie")
            if (art !in ARTEN || kat !in KATEGORIEN) return@mapNotNull null   // Unbekanntes nie anzeigen
            val s = e.optJSONArray("saetze") ?: org.json.JSONArray()
            SprachEintrag(e.optString("befehl"), kat, art, (0 until s.length()).map { s.optString(it) }.filter { it.isNotBlank() })
        }
        val n = o.optJSONArray("nie") ?: org.json.JSONArray()
        SprachKatalog(o.optBoolean("an"), o.optBoolean("weckwort"), o.optString("weckwortSatz"), o.optString("sprache", "de"),
            o.optBoolean("erkennbar"), o.optBoolean("modellDa"), liste, (0 until n.length()).map { n.optString(it) })
    }.getOrNull()

    /** Schalter „Sprech-Knopf zeigen“ (Launcher, Standard an – wirkt nur, wenn Sprachbefehle in Panoptes an sind). */
    fun knopfSchalter(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_KNOPF, true)

    fun knopfSetzen(ctx: Context, an: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_KNOPF, an).apply()
    }

    /** Sprechen bzw. einen angetippten Satz ausführen – immer über Panoptes (dort Schalter, Rückfrage, Kern-Regeln). */
    fun intent(befehl: String? = null): Intent {
        val i = Intent().setClassName(Raster.PANOPTES, Raster.PANOPTES + ZIEL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (befehl != null) i.putExtra(EXTRA_BEFEHL, befehl)
        return i
    }
}
