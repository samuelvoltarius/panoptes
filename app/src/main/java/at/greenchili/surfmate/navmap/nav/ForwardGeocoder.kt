package at.greenchili.surfmate.navmap.nav

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Ortssuche der Karte (Suchfeld der Fuehrung).
 *
 * <p>G3 (NAVI_ABGLEICH_0139 6b): Gesucht wird nur noch ueber den eigenen Kern - `POST 127.0.0.1:8180/api/navi/orte`
 * (Planer-Ortssuche mit Cache) und fuer Kurzlinks `POST /api/navi/link`. Stadia, Photon und Nominatim werden aus dem
 * Auto nicht mehr gefragt; die Position geht bei der Suche gar nicht mehr hinaus (nur der Suchtext).
 *
 * <ol>
 *   <li><b>Offline</b> - [CoordinateInputParser]: Koordinate, Plus Code, Karten-Link mit Koordinaten.</li>
 *   <li><b>Kurzlink</b> (nur beim Absenden) - Kern `/api/navi/link` mit `aufloesen` (nur der Link geht hinaus).</li>
 *   <li><b>Planer</b> - Kern `/api/navi/orte` `{q, limit}`.</li>
 * </ol>
 *
 * <p>Schalter `karte.sucheInternet` (Standard an): aus = nur offline, es geht nichts hinaus.
 * Wie bisher SYNC (die Activity ruft im Hintergrund) und wirft nie.
 */
object ForwardGeocoder {

    private const val TAG = "ForwardGeocoder"

    internal const val KERN = "http://127.0.0.1:8180"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    /**
     * Warum die LETZTE [search] nichts lieferte (die Activity zeigt eine passende Meldung).
     *   NONE     — zuletzt erfolgreich / nie gelaufen
     *   TIMEOUT  — Kern/Planer antwortet nicht rechtzeitig
     *   NETWORK  — Kern oder Planer nicht erreichbar (auch 502/503 vom Kern)
     *   EMPTY    — Planer hat geantwortet, aber nichts gefunden
     *   AUS      — Schalter „Karte: Suche im Internet“ ist aus, nichts wurde gesendet
     */
    enum class SearchError { NONE, TIMEOUT, NETWORK, EMPTY, AUS }

    @Volatile
    var lastError: SearchError = SearchError.NONE
        private set

    // Loopback zum Kern, ohne Proxy. Der Kern fragt den Planer selbst (mit eigenen Grenzen).
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /** Gesendete Abfrage (fuer Tests austauschbar): Pfad + JSON-Body -> (HTTP-Code, Body) oder Ausnahme. */
    @Volatile
    internal var senden: (String, String) -> Pair<Int, String> = { pfad, body ->
        val req = Request.Builder().url(KERN + pfad).post(body.toRequestBody(JSON)).build()
        http.newCall(req).execute().use { resp -> Pair(resp.code, resp.body?.string() ?: "") }
    }

    /**
     * Suche beim Absenden: bis zu [limit] Treffer. [focusLat]/[focusLng] dienen nur noch der Offline-Erkennung
     * (z. B. Plus Code ohne Ort) und gehen nicht hinaus.
     */
    fun search(
        query: String,
        limit: Int = 5,
        focusLat: Double? = null,
        focusLng: Double? = null
    ): List<SearchResult> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val n = if (limit < 1) 1 else limit
        lastError = SearchError.NONE

        CoordinateInputParser.parse(q, focusLat, focusLng)?.let { return listOf(it) }
        if (!KartenSuchePrivat.erlaubt()) {
            lastError = SearchError.AUS
            return emptyList()
        }
        if (looksLikeShortLink(q)) {
            kurzlink(q)?.let { return listOf(it) }
            if (lastError == SearchError.TIMEOUT || lastError == SearchError.NETWORK) return emptyList()
        }
        val treffer = orte(q, n)
        if (treffer.isEmpty() && lastError == SearchError.NONE) lastError = SearchError.EMPTY
        return treffer
    }

    /** Vorschlaege beim Tippen (Activity entprellt): offline, sonst Planer. Kurzlinks erst beim Absenden. */
    fun autocomplete(
        query: String,
        limit: Int = 6,
        focusLat: Double? = null,
        focusLng: Double? = null
    ): List<SearchResult> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        CoordinateInputParser.parse(q, focusLat, focusLng)?.let { return listOf(it) }
        if (!KartenSuchePrivat.erlaubt() || q.length < 3) return emptyList()
        return orte(q, if (limit < 1) 1 else limit)
    }

    /**
     * Ortsname zu einem Kartenpunkt: Es gibt keinen eigenen Rueckwaerts-Dienst, und fremde Dienste fragt das Auto
     * nicht mehr (G3). Die Karte zeigt dann die Koordinate wie bisher bei einem Fehler.
     */
    @Suppress("UNUSED_PARAMETER")
    fun reverse(lat: Double, lng: Double): SearchResult? = null

    /** Kurz-Links, die ihre Koordinaten hinter einer Weiterleitung verstecken. */
    internal fun looksLikeShortLink(s: String): Boolean {
        val l = s.lowercase(Locale.US)
        return l.contains("maps.app.goo.gl") || l.contains("goo.gl/maps")
    }

    private fun kurzlink(link: String): SearchResult? {
        val (code, text) = abfrage("/api/navi/link", JSONObject().put("link", link).put("aufloesen", true)) ?: return null
        if (code != 200) return null
        return parseLink(text)
    }

    private fun orte(q: String, limit: Int): List<SearchResult> {
        val (code, text) = abfrage("/api/navi/orte", JSONObject().put("q", q).put("limit", limit)) ?: return emptyList()
        if (code != 200) {
            Log.w(TAG, "orte -> HTTP $code")
            if (code == 429 || code >= 500) lastError = SearchError.NETWORK
            return emptyList()
        }
        return parseOrte(text, limit)
    }

    /** Abfrage beim Kern; null bei Verbindungsfehler ([lastError] gesetzt). Suchtext wird nicht geloggt. */
    private fun abfrage(pfad: String, body: JSONObject): Pair<Int, String>? = try {
        senden(pfad, body.toString())
    } catch (t: Throwable) {
        Log.w(TAG, "$pfad failed: ${t.javaClass.simpleName}")
        lastError = if (t is java.net.SocketTimeoutException ||
            (t.message?.contains("timeout", ignoreCase = true) == true)) SearchError.TIMEOUT else SearchError.NETWORK
        null
    }

    /** Planer-Antwort `{orte:[{name, kurz?, lat, lon}]}` -> Treffer (Label „kurz, name“ wie in navi.html). */
    internal fun parseOrte(json: String, limit: Int): List<SearchResult> = try {
        val arr = JSONObject(json).optJSONArray("orte")
        val out = ArrayList<SearchResult>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                if (out.size >= limit) break
                val o = arr.optJSONObject(i) ?: continue
                val lat = o.optDouble("lat", Double.NaN)
                val lon = o.optDouble("lon", Double.NaN)
                val name = o.optString("name", "").trim()
                if (lat.isNaN() || lon.isNaN() || name.isEmpty()) continue
                val kurz = o.optString("kurz", "").trim()
                val label = if (kurz.isNotEmpty() && !name.startsWith(kurz)) "$kurz, $name" else name
                out.add(SearchResult(label, lat, lon))
            }
        }
        out
    } catch (_: Throwable) {
        emptyList()
    }

    /** Kern `/api/navi/link` `{ok, lat, lng, name?}` -> Treffer oder null. */
    internal fun parseLink(json: String): SearchResult? = try {
        val o = JSONObject(json)
        val lat = o.optDouble("lat", Double.NaN)
        val lng = o.optDouble("lng", Double.NaN)
        if (!o.optBoolean("ok", false) || lat.isNaN() || lng.isNaN()) null
        else SearchResult(o.optString("name", "").trim().ifEmpty { String.format(Locale.US, "%.5f, %.5f", lat, lng) },
            lat, lng)
    } catch (_: Throwable) {
        null
    }
}
