package at.greenchili.surfmate.navmap.nav

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Fahrroute fuer die Panoptes-Fuehrung (G3, NAVI_ABGLEICH_0139 6b): ueber den eigenen Kern
 * `POST 127.0.0.1:8180/api/navi/fuehrung`, der an den Panoptes-Planer (`/ev/api/fuehrung`, Vertrag fuehrung/1)
 * weiterreicht. Der Planer fragt Valhalla auf dem eigenen Server - im Auto liegt kein Routing-Schluessel mehr,
 * Stadia/BYOK ist ausgebaut. Die Antwort traegt den Valhalla-`trip` unveraendert, darum bleibt der Parser unten
 * wie er war.
 *
 * <p>Datenschutz: Position und Ziel gehen nur im Body an den eigenen Kern (Loopback), nie in eine URL; hier wird
 * keine Koordinate geloggt (nur HTTP-Code bzw. Fehlerklasse).
 *
 * <p>Wie bisher: SYNC (die Activity ruft im Hintergrund), wirft nie, Fehler = leere Liste + [lastError].
 * fuehrung/1 kennt keine Alternativrouten - [routesWithAlternates] liefert hoechstens eine Route.
 */
object ValhallaRouteClient {

    private const val TAG = "ValhallaRouteClient"

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** Eigener Kern (gleiche Adresse wie OrteFavoritenSync / RoadSenseHazardApiClient), ohne Proxy. */
    internal const val FUEHRUNG_URL = "http://127.0.0.1:8180/api/navi/fuehrung"

    /** fuehrung/1: hoechstens 5 Zwischenstopps (Planer MAX_VIA). */
    const val MAX_VIA = 5

    /** Sprachen, die der Planer fuer die Manoever-Texte kennt; sonst Englisch. */
    private val SPRACHEN = setOf("de", "en", "fr", "it", "nl")

    // Kern -> Planer -> Valhalla: lange Routen ueber LTE brauchen Zeit, Kern und Planer begrenzen selbst.
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /** Erste Route von Start zu Ziel, oder `null`. */
    fun route(startLat: Double, startLng: Double, endLat: Double, endLng: Double): NavRoute? =
        routesWithAlternates(startLat, startLng, endLat, endLng, 0).firstOrNull()

    /**
     * Warum der LETZTE Versuch scheiterte (die Activity zeigt dazu eine passende Meldung).
     *   NONE     — noch nicht versucht / zuletzt erfolgreich
     *   AUTH     — 401: Panoptes ist nicht mit dem Planer verbunden (kein Geraete-/Mitglieds-Schluessel)
     *   LIMIT    — 429: zu viele Neuberechnungen, [lastRetryAfterS] sagt, wann wieder
     *   HTTP     — anderer Fehlercode (z. B. 400 Werte)
     *   TIMEOUT  — Kern antwortet nicht rechtzeitig
     *   NETWORK  — Kern oder Planer nicht erreichbar (503/502/504, Verbindung)
     *   EMPTY    — kein befahrbarer Weg (422) bzw. Antwort ohne Route
     */
    enum class RouteError { NONE, AUTH, LIMIT, HTTP, TIMEOUT, NETWORK, EMPTY }

    @Volatile
    var lastError: RouteError = RouteError.NONE
        private set

    /** HTTP-Code der letzten Fehlantwort (0 = kein HTTP-Fehler). */
    @Volatile
    var lastHttpCode: Int = 0
        private set

    /** Retry-After der letzten 429/503-Antwort in Sekunden (0 = keine Angabe). */
    @Volatile
    var lastRetryAfterS: Int = 0
        private set

    private fun classify(t: Throwable): RouteError =
        if (t is java.net.SocketTimeoutException ||
            (t.message?.contains("timeout", ignoreCase = true) == true)
        ) RouteError.TIMEOUT else RouteError.NETWORK

    /**
     * Route durch eine GEORDNETE Punktliste: Start, Zwischenstopps, Ziel (mindestens 2 Punkte, hoechstens
     * [MAX_VIA] Zwischenstopps). [startHeading] = Fahrtrichtung am Start (Neuberechnung unterwegs, `kurs`).
     */
    fun routeVia(points: List<GeoPoint>, startHeading: Double? = null): List<NavRoute> {
        if (points.size < 2) return emptyList()
        if (points.size - 2 > MAX_VIA) return fail(RouteError.HTTP, 400)   // die Activity begrenzt schon auf MAX_STOPS
        return fuehrung(buildFuehrungBody(points, startHeading, MapNetworking.valhallaLanguage), "routeVia")
    }

    /**
     * Route von Start zu Ziel. [alternates] bleibt fuer die Aufrufer erhalten, fuehrung/1 liefert aber nur die
     * Hauptroute (Alternativen kommen hoechstens mit fuehrung/2).
     */
    @Suppress("UNUSED_PARAMETER")
    fun routesWithAlternates(
        startLat: Double, startLng: Double, endLat: Double, endLng: Double, alternates: Int,
        startHeading: Double? = null
    ): List<NavRoute> = fuehrung(
        buildFuehrungBody(listOf(GeoPoint(startLat, startLng), GeoPoint(endLat, endLng)), startHeading,
            MapNetworking.valhallaLanguage), "route")

    private fun fuehrung(body: String, tag: String): List<NavRoute> {
        return try {
            val req = Request.Builder().url(FUEHRUNG_URL).post(body.toRequestBody(JSON)).build()
            http.newCall(req).execute().use { resp ->
                val text = try { resp.body?.string() } catch (_: Throwable) { null }
                if (!resp.isSuccessful) {
                    httpFehler(tag, resp.code, resp.header("Retry-After"), text)
                    return emptyList()
                }
                if (text == null) return fail(RouteError.EMPTY, 200)
                finish(parseRoutes(text))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "$tag failed: ${t.javaClass.simpleName}")
            lastError = classify(t)
            lastHttpCode = 0
            emptyList()
        }
    }

    private fun fail(err: RouteError, httpCode: Int = 0): List<NavRoute> {
        lastError = err
        lastHttpCode = httpCode
        return emptyList()
    }

    private fun finish(routes: List<NavRoute>): List<NavRoute> {
        lastError = if (routes.isEmpty()) RouteError.EMPTY else RouteError.NONE
        lastHttpCode = 200
        lastRetryAfterS = 0
        return routes
    }

    /** Fehlantwort einordnen. Geloggt werden nur Code und `code`-Feld, nie der Body (kein Ort im Log). */
    private fun httpFehler(tag: String, code: Int, retryAfter: String?, body: String?) {
        val feld = try { JSONObject(body ?: "").optString("code", "") } catch (_: Throwable) { "" }
        Log.w(TAG, "POST $tag -> HTTP $code ${feld.ifEmpty { "-" }}")
        lastRetryAfterS = retryAfterS(retryAfter, body)
        fail(fehlerArt(code), code)
    }

    /** HTTP-Code der Kern-Antwort -> [RouteError] (fuehrung/1 §5, Kern reicht den Code durch). */
    internal fun fehlerArt(code: Int): RouteError = when (code) {
        401, 403 -> RouteError.AUTH
        429 -> RouteError.LIMIT
        422 -> RouteError.EMPTY
        502, 503, 504 -> RouteError.NETWORK
        else -> RouteError.HTTP
    }

    /**
     * Wartezeit in s (hoechstens 1 h): Der Kern (G2) schickt keinen Retry-After-Kopf, sondern im Body `warte_ms`
     * (Kern-Pause), `warte_s` (429 vom Planer) oder `wiederInS`; der Kopf zaehlt nur als Rueckfall. Sonst 0.
     */
    internal fun retryAfterS(header: String?, body: String?): Int {
        val j = try { JSONObject(body ?: "") } catch (_: Throwable) { null }
        val s = when {
            j == null -> -1L
            j.has("warte_ms") -> j.optLong("warte_ms", 0L) / 1000L
            j.has("warte_s") -> j.optLong("warte_s", 0L)
            j.has("wiederInS") -> j.optLong("wiederInS", 0L)
            else -> -1L
        }
        if (s >= 0) return s.coerceIn(0L, 3600L).toInt()
        return header?.trim()?.toIntOrNull()?.coerceIn(0, 3600) ?: 0
    }

    /** "de-DE"/"de" -> "de"; unbekannt -> "en" (fuehrung/1 `sprache`). */
    internal fun sprache(tag: String?): String {
        val s = (tag ?: "").substringBefore('-').substringBefore('_').lowercase()
        return if (s in SPRACHEN) s else "en"
    }

    /**
     * Anfrage fuer fuehrung/1: `{start, ziel, via?, sprache, kurs?}` mit `[lat, lon]`. Einheiten sind immer km
     * (der Planer fragt Valhalla in Kilometern; der Parser rechnet km -> m, die Anzeige formatiert selbst).
     */
    internal fun buildFuehrungBody(points: List<GeoPoint>, startHeading: Double?, languageTag: String?): String {
        fun pkt(p: GeoPoint) = JSONArray().put(p.lat).put(p.lng)
        val body = JSONObject()
            .put("start", pkt(points.first()))
            .put("ziel", pkt(points.last()))
            .put("sprache", sprache(languageTag))
        if (points.size > 2) {
            val via = JSONArray()
            for (i in 1 until points.size - 1) via.put(pkt(points[i]))
            body.put("via", via)
        }
        // Neuberechnung unterwegs: Fahrtrichtung mitgeben, damit die neue Route nicht "bitte wenden" verlangt.
        if (startHeading != null && !startHeading.isNaN()) {
            body.put("kurs", (((startHeading % 360.0) + 360.0) % 360.0).toInt() % 360)
        }
        return body.toString()
    }

    /**
     * Parse a Valhalla `/route` response into a [NavRoute].
     *
     * <p>Shape: each `trip.legs[i].shape` is a precision-6 encoded polyline (decoded
     * via [PolylineCodec]) and ALL legs are concatenated (a multi-stop trip has one
     * leg per via-stop — see [parseTrip]); `legs[i].maneuvers[]` carry `instruction`,
     * `type`, `begin_shape_index`, `length` (km -> *1000 m), `time` (s); the
     * maneuver lat/lng is taken from `points[begin_shape_index]`. Trip totals
     * come from `trip.summary.length` (km -> *1000 m) and `trip.summary.time`
     * (s). Returns `null` if the response has no usable leg. Pure function —
     * exposed for unit testing.
     *
     * @param json the raw Valhalla response body
     */
    internal fun parseRoute(json: String): NavRoute? =
        parseRoutes(json).firstOrNull()

    /**
     * Parse a Valhalla response into an ordered list of [NavRoute]s: the primary
     * `trip` first, then each `alternates[i].trip` (Valhalla returns alternates
     * as a top-level array, each entry wrapping a `trip` object with the same
     * shape as the primary). Routes that fail to parse are skipped. Pure
     * function — exposed for unit testing.
     *
     * @param json the raw Valhalla response body
     */
    internal fun parseRoutes(json: String): List<NavRoute> {
        return try {
            val root = JSONObject(json)
            val out = ArrayList<NavRoute>()
            root.optJSONObject("trip")?.let { parseTrip(it)?.let(out::add) }
            val alts = root.optJSONArray("alternates")
            if (alts != null) {
                for (i in 0 until alts.length()) {
                    val altTrip = alts.optJSONObject(i)?.optJSONObject("trip") ?: continue
                    parseTrip(altTrip)?.let(out::add)
                }
            }
            out
        } catch (t: Throwable) {
            Log.w(TAG, "parseRoutes failed: ${t.message}")
            emptyList()
        }
    }

    /**
     * Parse a single Valhalla `trip` object into a [NavRoute], or null if unusable.
     *
     * <p>CONCATENATES ALL LEGS. Valhalla splits a route at every `break` location,
     * and [buildRequestBodyVia] sends each via-stop as a break — so a multi-stop trip
     * comes back as N legs (origin→stop1, stop1→stop2, …, →destination). Reading only
     * `legs[0]` (the old behaviour) truncated a multi-stop route to origin→FIRST-stop:
     * the guidance engine's "destination" became the first via, so arrival fired at the
     * wrong place and the cluster mirrored a route that ended early. We now stitch every
     * leg's polyline into one continuous `points` list and every leg's maneuvers into one
     * ordered list, OFFSETTING each subsequent leg's `begin_shape_index` by the running
     * vertex count so the maneuver indices stay valid against the concatenated polyline.
     * The shared break vertex between consecutive legs (legN ends where legN+1 starts) is
     * de-duplicated so the polyline has no zero-length seam. Trip totals come from
     * `trip.summary` (already the whole-trip sum) with a per-leg fallback.
     */
    private fun parseTrip(trip: JSONObject): NavRoute? {
        val legs = trip.optJSONArray("legs") ?: return null
        if (legs.length() == 0) return null

        val points = ArrayList<GeoPoint>()
        val maneuvers = ArrayList<RouteManeuver>()
        var legSumDistanceM = 0.0
        var legSumDurationS = 0.0

        for (li in 0 until legs.length()) {
            val leg = legs.optJSONObject(li) ?: continue
            val legPts = PolylineCodec.decode(leg.optString("shape", ""), 1e6)
            if (legPts.isEmpty()) continue
            // Index in [points] where THIS leg's vertices begin. After the first leg,
            // drop the leg's first vertex IFF it coincides with the previous leg's last
            // vertex (the shared break point): appending it would create a duplicate/
            // zero-length segment. We test the coincidence rather than assuming it, so a
            // skipped (e.g. empty-shape) in-between leg — after which this leg's vertex 0
            // is NOT the previous appended vertex — appends all vertices instead of
            // dropping a real point and fabricating a gap. The maneuver offset uses the
            // matching base so indices stay aligned to this leg's true vertex 0.
            val base: Int
            val prevLast = points.lastOrNull()
            val first = legPts.first()
            val coincides = prevLast != null &&
                kotlin.math.abs(prevLast.lat - first.lat) < 1e-7 &&
                kotlin.math.abs(prevLast.lng - first.lng) < 1e-7
            if (prevLast == null) {
                base = 0
                points.addAll(legPts)
            } else if (coincides) {
                base = points.size - 1   // genuine shared break vertex → dedup vertex 0
                for (i in 1 until legPts.size) points.add(legPts[i])
            } else {
                base = points.size       // not adjacent (prior leg skipped) → append all
                points.addAll(legPts)
            }

            // Keep EVERY leg's maneuvers here (including each leg's terminal
            // "destination" maneuver, type 4/5/6); the via-stop dedup happens in a
            // single post-pass below. We do NOT use the loop index to decide which
            // destination maneuver is "the real arrival": a null/empty TRAILING leg
            // is skipped (continue, above) before reaching this block, so keying the
            // drop on `li == legs.length() - 1` would orphan the true arrival —
            // the previous (last vertex-yielding) leg would be treated as non-final
            // and its destination maneuver dropped, leaving the route with NO arrival
            // maneuver at all. Deferring the drop to a post-pass over the assembled
            // list makes the surviving terminal maneuver the route's true arrival
            // regardless of how many trailing legs were degenerate.
            val mArr = leg.optJSONArray("maneuvers")
            if (mArr != null) {
                for (i in 0 until mArr.length()) {
                    val m = mArr.optJSONObject(i) ?: continue
                    val beginIdx = m.optInt("begin_shape_index", 0) + base
                    // Clamp the (offset) index so a malformed/out-of-range value can't
                    // throw; pin to the nearest valid concatenated-route vertex.
                    val safeIdx = beginIdx.coerceIn(0, points.size - 1)
                    val mp = points[safeIdx]
                    maneuvers.add(
                        RouteManeuver(
                            instruction = m.optString("instruction", ""),
                            type = m.optInt("type", 0),
                            beginShapeIndex = safeIdx,
                            lengthMeters = m.optDouble("length", 0.0) * 1000.0,
                            timeSeconds = m.optDouble("time", 0.0),
                            lat = mp.lat,
                            lng = mp.lng,
                            // Enrichment (all optional in Valhalla output):
                            roundaboutExitCount = m.optInt("roundabout_exit_count", 0),
                            bearingBefore = m.optInt("bearing_before", -1),
                            bearingAfter = m.optInt("bearing_after", -1),
                            verbalPre = m.optString("verbal_pre_transition_instruction", ""),
                            verbalPost = m.optString("verbal_post_transition_instruction", "")
                        )
                    )
                }
            }
            (leg.optJSONObject("summary"))?.let {
                legSumDistanceM += it.optDouble("length", 0.0) * 1000.0
                legSumDurationS += it.optDouble("time", 0.0)
            }
        }
        if (points.isEmpty()) return null

        // Via-stop dedup post-pass: the concat above kept EVERY leg's terminal
        // "destination" maneuver (type 4/5/6), but only the LAST one is the real
        // arrival — NavGuidanceEngine.maneuverImportance() rates 4/5/6 SIGNIFICANT,
        // so nextManeuver() would otherwise surface a via-stop's destination as a
        // false mid-route "you have arrived". Drop every type-4..6 maneuver EXCEPT
        // the last in the assembled (route-order) list, so the single surviving
        // arrival maneuver is the route's TRUE destination even when a trailing
        // leg was null/empty (and therefore its own arrival was never appended).
        val lastDestIdx = maneuvers.indexOfLast { it.type in 4..6 }
        if (lastDestIdx >= 0) {
            var i = maneuvers.size - 1
            while (i >= 0) {
                if (i != lastDestIdx && maneuvers[i].type in 4..6) maneuvers.removeAt(i)
                i--
            }
        }

        // Prefer the trip-level summary (whole-trip totals); fall back to the per-leg sum
        // when it's absent/zero (a multi-stop response may omit the top-level summary).
        val summary = trip.optJSONObject("summary")
        val summaryDistanceM = (summary?.optDouble("length", 0.0) ?: 0.0) * 1000.0
        val summaryDurationS = summary?.optDouble("time", 0.0) ?: 0.0
        val totalDistanceMeters = if (summaryDistanceM > 0.0) summaryDistanceM else legSumDistanceM
        val totalDurationSeconds = if (summaryDurationS > 0.0) summaryDurationS else legSumDurationS

        return NavRoute(
            points = points,
            maneuvers = maneuvers,
            totalDistanceMeters = totalDistanceMeters,
            totalDurationSeconds = totalDurationSeconds
        )
    }
}
