package at.greenchili.surfmate.navmap

import android.content.Context
import android.util.Log
import at.greenchili.surfmate.navmap.nav.SearchResult
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 0.1.12 (Kern 7, ORTE_VERTRAG §4.4): gleicht die Navi-Favoriten ([SavedPlacesStore]) mit der Ortsliste im Daemon ab.
 * Nur lokal (127.0.0.1:8180, wie [RoadSenseHazardApiClient]) – die Favoriten verlassen das Auto nie.
 *
 * Ablauf: App-Liste + zuletzt übernommene Version an `POST /api/orte/favoriten`; der Daemon übernimmt lokale
 * Änderungen in die Ortsliste und antwortet mit `aendern` (+ Soll-Liste). Nur dann ersetzt die App ihren Speicher –
 * bei der ersten Übernahme bleibt er unverändert. Fehler (Daemon aus, Netz) ändern nie etwas; nächster Versuch beim
 * nächsten Öffnen bzw. Speichern.
 */
object OrteFavoritenSync {
    private const val TAG = "OrteFavoritenSync"
    private const val BASE_URL = "http://127.0.0.1:8180"
    private const val PREFS = "orte_favoriten_sync"
    private const val KEY_VERSION = "version"
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val laeuft = AtomicBoolean(false)

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /** Im Hintergrund abgleichen (nie auf dem UI-Thread). Läuft schon einer, wird dieser Aufruf ausgelassen. */
    fun anstossen(context: Context) {
        val app = context.applicationContext
        if (!laeuft.compareAndSet(false, true)) return
        Thread({
            try {
                abgleichen(app)
            } catch (t: Throwable) {
                Log.w(TAG, "Abgleich fehlgeschlagen: ${t.message}")
            } finally {
                laeuft.set(false)
            }
        }, "OrteFavoritenSync").apply { isDaemon = true }.start()
    }

    internal fun alsJson(places: List<SavedPlacesStore.SavedPlace>): JSONArray {
        val a = JSONArray()
        for (p in places) {
            a.put(JSONObject().put("art", p.kind).put("label", p.result.label)
                .put("lat", p.result.lat).put("lon", p.result.lng))
        }
        return a
    }

    internal fun ausJson(a: JSONArray): List<SavedPlacesStore.SavedPlace> {
        val out = ArrayList<SavedPlacesStore.SavedPlace>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val label = o.optString("label", "")
            val lat = o.optDouble("lat", Double.NaN)
            val lon = o.optDouble("lon", Double.NaN)
            if (label.isBlank() || lat.isNaN() || lon.isNaN()) continue
            val art = when (o.optString("art")) {
                SavedPlacesStore.KIND_HOME -> SavedPlacesStore.KIND_HOME
                SavedPlacesStore.KIND_WORK -> SavedPlacesStore.KIND_WORK
                else -> SavedPlacesStore.KIND_CUSTOM
            }
            out.add(SavedPlacesStore.SavedPlace(art, SearchResult(label, lat, lon)))
        }
        return out
    }

    private fun abgleichen(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val version = prefs.getLong(KEY_VERSION, -1L)
        val body = JSONObject()
            .put("liste", alsJson(SavedPlacesStore.getAll(context)))
            .put("version", version)
            .toString()
        val req = Request.Builder().url("$BASE_URL/api/orte/favoriten")
            .post(body.toRequestBody(JSON)).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "Daemon antwortet ${resp.code} – nichts geändert")
                return
            }
            val r = JSONObject(resp.body?.string() ?: return)
            if (r.optBoolean("aendern", false)) {
                val liste = r.optJSONArray("liste") ?: return
                SavedPlacesStore.replaceAll(context, ausJson(liste))
                Log.i(TAG, "Favoriten aus der Ortsliste übernommen: ${liste.length()}")
            }
            prefs.edit().putLong(KEY_VERSION, r.optLong("version", version)).apply()
        }
    }
}
