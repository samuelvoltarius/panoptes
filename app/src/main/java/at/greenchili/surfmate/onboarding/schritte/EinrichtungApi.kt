package at.greenchili.surfmate.onboarding.schritte

import android.util.Log
import at.greenchili.surfmate.util.DaemonHttpClient
import org.json.JSONObject

/**
 * Display-Seite des Vertrags §0 (WIZARD_AUFTRAG_KERN.md). Nur lesen und Antworten schicken;
 * Status, Prüfungen und Speichern macht der Kern. Alle Aufrufe blockieren → nie auf dem Main-Thread.
 *
 * Ergebnis-Konvention: [Antwort.status] = HTTP-Code, 0 = Daemon nicht erreichbar (z. B. vor der USB-Freigabe).
 */
object EinrichtungApi {
    private const val TAG = "EinrichtungApi"

    data class Antwort(val status: Int, val json: JSONObject) {
        val ok: Boolean get() = status in 200..299
        /** 422 vom Kern: Pflichtantwort fehlt; Feldnamen in „fehlt“. */
        val fehlt: List<String>
            get() = json.optJSONArray("fehlt")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
    }

    /** Für Tests bzw. den Schein-§0 austauschbar. */
    @Volatile var transport: (method: String, path: String, body: JSONObject?) -> Antwort = ::http

    fun laden(): EinrichtungStand? {
        val a = transport("GET", "/api/einrichtung", null)
        return if (a.ok && a.json.has("schritte")) EinrichtungStand.ausJson(a.json) else null
    }

    /** true, wenn §0 überhaupt antwortet (Daemon läuft und kennt die Einrichtung). */
    fun erreichbar(): Boolean = transport("GET", "/api/einrichtung", null).ok

    fun schritt(id: String, antwort: JSONObject): Antwort =
        transport("POST", "/api/einrichtung/schritt", JSONObject().put("id", id).put("antwort", antwort))

    fun spaeter(id: String): Antwort = transport("POST", "/api/einrichtung/spaeter", JSONObject().put("id", id))

    fun neu(): Antwort = transport("POST", "/api/einrichtung/neu", JSONObject())

    fun selbsttest(): Antwort = transport("POST", "/api/einrichtung/selbsttest", JSONObject())

    /** Einstellung schreiben wie das Web (PUT /api/einstellungen, etag + werte + optional bestaetigt). */
    fun einstellungSetzen(werte: JSONObject, bestaetigt: List<String> = emptyList()): Antwort {
        val etag = get("/api/einstellungen").json.optString("etag", "")
        val body = JSONObject().put("etag", etag).put("werte", werte)
        if (bestaetigt.isNotEmpty()) body.put("bestaetigt", org.json.JSONArray(bestaetigt))
        return transport("PUT", "/api/einstellungen", body)
    }

    /** Beliebiger lesender Aufruf (z. B. /api/einstellungen für guardStop-Werte). */
    fun get(path: String): Antwort = transport("GET", path, null)

    private fun http(method: String, path: String, body: JSONObject?): Antwort {
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = DaemonHttpClient.open(path, method, 3000, if (path.endsWith("selbsttest")) 30000 else 8000)
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val json = try { JSONObject(text) } catch (_: Throwable) { JSONObject() }
            Antwort(code, json)
        } catch (t: Throwable) {
            Log.d(TAG, "$method $path nicht erreichbar: ${t.message}")
            Antwort(0, JSONObject())
        } finally {
            conn?.disconnect()
        }
    }
}
