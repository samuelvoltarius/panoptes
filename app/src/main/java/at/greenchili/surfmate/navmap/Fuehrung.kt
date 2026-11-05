package at.greenchili.surfmate.navmap

import android.content.Context
import android.util.Log
import at.greenchili.surfmate.navmap.nav.SearchResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * Einstieg in die Panoptes-Führung aus navi.html (0.1.38.10, Paket G4) – die eine Quelle für Bridge und Activity.
 *
 * <p>navi.html übergibt über `AndroidBridge.openNavigation(json)` nur den Auftrag
 * `{plan_id?, ziel{name,lat,lon}, etappen[{art:"zwischenziel",name,lat,lon}]}` – keine Route und keine Position. Die
 * Bridge legt daraus die Stopps in [NavTripStore] ab (nicht navigierend) und öffnet [RoadSenseMapActivity] mit
 * [EXTRA_NEU]; die Activity zeigt die Vorschau, geführt wird erst nach dem Tipp auf Start (A+). Ladestopps (art
 * "laden") gehen erst mit G6 in die Führung und werden hier übersprungen.
 *
 * <p>[verfuegbar] entscheidet, ob navi.html die Führung anbietet oder wie bisher das Werksnavi; G3 ersetzt nur
 * diesen Rumpf, sobald das Routing über den Kern läuft. [stand] liefert das Band „Führung läuft“ (nur Name und Anzahl,
 * keine Koordinaten).
 */
object Fuehrung {

    private const val TAG = "Fuehrung"
    private const val PREFS = "navmap_fuehrung"
    private const val KEY_PLAN_ID = "plan_id"

    /** Intent-Extra: neuer Auftrag liegt in [NavTripStore], die Activity soll ihn (auch bei onNewIntent) übernehmen. */
    const val EXTRA_NEU = "fuehrung_neu"

    /** Höchstens so viele Zwischenstopps; = ValhallaRouteClient.MAX_VIA (G3, Planergrenze fuehrung/1). Mehr → ungültig. */
    const val MAX_ZWISCHEN = 5

    /** Ein gelesener Auftrag: Stopps in Fahrreihenfolge, das Ziel zuletzt. */
    data class Auftrag(val stopps: List<SearchResult>, val planId: String?)

    /**
     * Bietet navi.html die Panoptes-Führung an? Immer ja: eigener Dienst über Kern /api/navi/fuehrung (G2/G3), kein
     * Schlüssel nötig. Fehler wie fehlende Planer-Kopplung (401) oder kein Netz (503) meldet die Activity beim Routen.
     * Bewusst ohne Netzabfrage – billig und synchron, weil die Bridge es bei jedem Zeichnen fragt.
     */
    @JvmStatic
    fun verfuegbar(): Boolean = true

    /** Auftrag aus navi.html prüfen; null, wenn kein gültiges Ziel oder mehr als [MAX_ZWISCHEN] Zwischenstopps. Ungültige Etappen fallen weg. */
    @JvmStatic
    fun auftragLesen(json: String?): Auftrag? {
        if (json.isNullOrBlank()) return null
        return try {
            val o = JSONObject(json)
            val ziel = punkt(o.optJSONObject("ziel")) ?: return null
            val stopps = ArrayList<SearchResult>()
            val et = o.optJSONArray("etappen") ?: JSONArray()
            for (i in 0 until et.length()) {
                val e = et.optJSONObject(i) ?: continue
                if (e.optString("art", "zwischenziel") != "zwischenziel") continue   // Ladestopps erst mit G6
                val p = punkt(e) ?: continue
                if (stopps.size >= MAX_ZWISCHEN) return null   // nicht still kürzen: der Auftrag gilt als ungültig
                stopps.add(p)
            }
            stopps.add(ziel)
            val pid = o.optString("plan_id", "").trim()
            Auftrag(stopps, if (pid.isEmpty() || pid.length > 80) null else pid)
        } catch (e: Throwable) {
            null
        }
    }

    private fun punkt(o: JSONObject?): SearchResult? {
        if (o == null) return null
        val lat = o.optDouble("lat", Double.NaN)
        val lon = o.optDouble("lon", o.optDouble("lng", Double.NaN))
        if (lat.isNaN() || lon.isNaN() || lat < -90 || lat > 90 || lon < -180 || lon > 180) return null
        val name = o.optString("name", "").trim().take(80)
        return SearchResult(if (name.isEmpty()) String.format(java.util.Locale.ROOT, "%.5f, %.5f", lat, lon) else name, lat, lon)
    }

    /** Auftrag ablegen (Stopps nicht navigierend, plan_id für G3/G6). */
    @JvmStatic
    fun ablegen(context: Context, a: Auftrag) {
        NavTripStore.save(context, a.stopps, false)
        try {
            prefs(context).edit().apply { if (a.planId != null) putString(KEY_PLAN_ID, a.planId) else remove(KEY_PLAN_ID) }.apply()
        } catch (e: Throwable) {
            Log.w(TAG, "ablegen: ${e.message}")
        }
    }

    /** plan_id des zuletzt übergebenen Auftrags (für G3/G6), sonst null. */
    @JvmStatic
    fun planId(context: Context): String? = try {
        prefs(context).getString(KEY_PLAN_ID, null)
    } catch (e: Throwable) {
        null
    }

    /** Stand für navi.html: {verfuegbar, aktiv, ziel_name, stopps}. Keine Koordinaten. */
    @JvmStatic
    fun stand(context: Context): JSONObject = standJson(NavTripStore.load(context), verfuegbar())

    @JvmStatic
    fun standJson(trip: NavTripStore.Trip?, verfuegbar: Boolean): JSONObject {
        val o = JSONObject().put("verfuegbar", verfuegbar)
        val aktiv = trip != null && trip.navigating && trip.stops.isNotEmpty()
        o.put("aktiv", aktiv)
        if (aktiv) {
            o.put("ziel_name", trip!!.stops.last().label)
            o.put("stopps", trip.stops.size)
        }
        return o
    }

    /** Laufende oder gemerkte Führung verwerfen. */
    @JvmStatic
    fun beenden(context: Context) {
        NavTripStore.clear(context)
        try {
            prefs(context).edit().remove(KEY_PLAN_ID).apply()
        } catch (e: Throwable) {
            Log.w(TAG, "beenden: ${e.message}")
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
