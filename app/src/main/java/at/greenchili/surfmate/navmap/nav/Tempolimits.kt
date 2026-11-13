package at.greenchili.surfmate.navmap.nav

import org.json.JSONArray

/**
 * 0.1.44 (P3b): Tempolimits entlang der Panoptes-Route. Rein, ohne Android.
 *
 * <p>Quelle ist das Planer-Feld `tempolimits` von `fuehrung/1` (OSM `maxspeed` über Valhalla `trace_attributes`, ODbL 1.0):
 * je Bein `[[von, bis, kmh], …]` mit Formindizes im Bein, wie `begin_shape_index` der Manöver. [ausBein] setzt sie mit demselben
 * Versatz wie die Manöver auf die zusammengesetzte Route um, [fuer] liefert das Limit an einer Teilstrecke. Nichts wird
 * erfunden: ein Eintrag ohne gültige Zahl fällt weg, wo nichts belegt ist, gibt es keinen Wert.
 */
object Tempolimits {

    /** Gleiche Grenzen wie `TempolimitStand` und der Planer. */
    const val MIN_KMH = 5
    const val MAX_KMH = 200

    /**
     * Abschnitte eines Beins ([bein] = `[[von, bis, kmh], …]` oder null) auf die zusammengesetzte Route umgerechnet;
     * [base] ist der Versatz dieses Beins (wie bei den Manövern). Ungültige Einträge werden übergangen.
     */
    @JvmStatic
    fun ausBein(bein: JSONArray?, base: Int): List<TempoAbschnitt> {
        if (bein == null) return emptyList()
        val out = ArrayList<TempoAbschnitt>()
        for (i in 0 until bein.length()) {
            val e = bein.optJSONArray(i) ?: continue
            if (e.length() < 3) continue
            val von = e.optInt(0, -1)
            val bis = e.optInt(1, -1)
            val kmh = e.optInt(2, 0)
            if (von < 0 || bis <= von || kmh < MIN_KMH || kmh > MAX_KMH) continue
            out.add(TempoAbschnitt(von + base, bis + base, kmh))
        }
        return out
    }

    /**
     * Limit an der Teilstrecke [segment] (Strecke zwischen `points[segment]` und `points[segment + 1]`), oder null, wenn dort
     * nichts belegt ist. Ein Abschnitt gilt für die Teilstrecken [von] bis [bis] − 1.
     */
    @JvmStatic
    fun fuer(abschnitte: List<TempoAbschnitt>, segment: Int): Int? {
        if (segment < 0) return null
        for (a in abschnitte) {
            if (segment >= a.von && segment < a.bis) return a.kmh
        }
        return null
    }
}
