package at.greenchili.surfmate.tacho

import org.json.JSONArray
import org.json.JSONObject

/**
 * Naechstes Manoever der Panoptes-Navigation fuer die [TachoAnsichtActivity] (gleicher App-Prozess,
 * kein IPC). Die interaktive Karte ([at.greenchili.surfmate.navmap.RoadSenseMapActivity]) schreibt hier
 * neben dem OEM-Pfeil. Manoevertyp, Entfernungen, Restzeit, eine grobe Himmelsrichtung (8 Sektoren)
 * und die Routenskizze voraus in Metern relativ zum Auto, fahrtrichtungsoben gedreht. Keine
 * Position, kein Strassenname. Nach [STALE_MS] ohne neuen Wert gilt: keine Route aktiv.
 */
object TachoAnsichtManoever {
    const val STALE_MS = 12_000L
    /** Hoechstens so viele Punkte der Skizze, auf ganze Meter gerundet. */
    const val SKIZZE_MAX = 48

    data class Stand(
        val typ: Int,
        val distanzM: Int,
        val zeitMs: Long,
        val restM: Int = -1,
        val restS: Int = -1,
        val richtung: String? = null,
        /** Paare {x rechts, y voraus} in Metern, relativ zum Auto. */
        val skizze: IntArray = IntArray(0)
    )

    @Volatile
    private var stand: Stand? = null

    @JvmStatic
    fun setze(typ: Int, distanzM: Double, jetztMs: Long = System.currentTimeMillis()) {
        stand = Stand(typ, distanzM.toInt().coerceAtLeast(0), jetztMs)
    }

    /**
     * Wie [setze], dazu Rest bis zum Ziel und die Skizze voraus. [voraus] sind lokale Meter
     * {x Ost, y Nord} (aus `NavGuidanceEngine.aheadLocal`), [kursGrad] die Fahrtrichtung; ohne
     * Kurs bleibt die Skizze nordoben.
     */
    @JvmStatic
    fun setze(typ: Int, distanzM: Double, restM: Double, restS: Double, kursGrad: Double?,
              voraus: List<DoubleArray>, jetztMs: Long = System.currentTimeMillis()) {
        stand = Stand(
            typ, distanzM.toInt().coerceAtLeast(0), jetztMs,
            if (restM.isFinite() && restM >= 0) restM.toInt() else -1,
            if (restS.isFinite() && restS >= 0) restS.toInt() else -1,
            kursGrad?.let { richtung(it) },
            skizze(voraus, kursGrad)
        )
    }

    @JvmStatic
    fun loesche() { stand = null }

    @JvmStatic
    fun aktuell(jetztMs: Long = System.currentTimeMillis()): Stand? {
        val s = stand ?: return null
        return if (jetztMs - s.zeitMs in 0..STALE_MS) s else null
    }

    /** Acht Sektoren, deutsch abgekuerzt (N, NO, O, SO, S, SW, W, NW). */
    @JvmStatic
    fun richtung(kursGrad: Double): String? {
        if (!kursGrad.isFinite()) return null
        val k = ((kursGrad % 360.0) + 360.0) % 360.0
        return arrayOf("N", "NO", "O", "SO", "S", "SW", "W", "NW")[((k + 22.5) / 45.0).toInt() % 8]
    }

    /** Dreht die Punkte fahrtrichtungsoben und rundet auf ganze Meter. */
    @JvmStatic
    fun skizze(voraus: List<DoubleArray>, kursGrad: Double?): IntArray {
        val n = minOf(voraus.size, SKIZZE_MAX)
        val r = Math.toRadians(if (kursGrad != null && kursGrad.isFinite()) kursGrad else 0.0)
        val c = Math.cos(r); val s = Math.sin(r)
        val out = IntArray(n * 2)
        for (i in 0 until n) {
            val p = voraus[i]
            if (p.size < 2) continue
            out[i * 2] = Math.round(p[0] * c - p[1] * s).toInt().coerceIn(-5000, 5000)
            out[i * 2 + 1] = Math.round(p[0] * s + p[1] * c).toInt().coerceIn(-5000, 5000)
        }
        return out
    }

    /** Teil der Daten fuer den Renderer; null ohne aktive Route. */
    @JvmStatic
    fun json(s: Stand?): JSONObject? {
        if (s == null) return null
        val o = JSONObject().put("typ", s.typ).put("distanzM", s.distanzM)
        if (s.restM >= 0) o.put("restM", s.restM)
        if (s.restS >= 0) o.put("restS", s.restS)
        if (s.richtung != null) o.put("richtung", s.richtung)
        if (s.skizze.isNotEmpty()) {
            val a = JSONArray()
            for (v in s.skizze) a.put(v)
            o.put("skizze", a)
        }
        return o
    }
}
