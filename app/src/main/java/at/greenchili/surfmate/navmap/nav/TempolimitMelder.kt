package at.greenchili.surfmate.navmap.nav

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 0.1.44 (P3b): meldet das belegte Tempolimit der Panoptes-Führung an den Kern. Die Führung (RoadSenseMapActivity) läuft im
 * App-Prozess, der Launcher liest [TempolimitStand] aus dem Kern (Daemon-Prozess): darum ein Aufruf an den eigenen Kern
 * (`POST 127.0.0.1:8180/api/navi/tempolimit`, nur Loopback, nur eine Zahl, keine Position).
 *
 * <p>Gesendet wird, wenn sich der Wert ändert, sonst alle [NEU_NACH_MS] erneut, solange er gilt (der Kern zeigt ihn höchstens
 * [TempolimitStand.FRISCH_MS] lang). Fällt der Wert weg (Strecke ohne Eintrag, abseits der Route, Führung zu Ende), geht
 * einmal „leer“ raus; kommt nichts mehr, verfällt er im Kern von selbst. Ohne Route wird nie ein Wert gemeldet.
 */
object TempolimitMelder {

    private const val TAG = "TempolimitMelder"
    internal const val URL = "http://127.0.0.1:8180/api/navi/tempolimit"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** Erneut senden, solange derselbe Wert gilt (Kern-Frische ist 15 s). */
    const val NEU_NACH_MS = 5_000L

    enum class Aktion { NICHTS, SENDEN, LOESCHEN }

    /** Rein: was jetzt zu tun ist. */
    internal fun entscheide(kmh: Int?, zuletztKmh: Int?, zuletztMs: Long, jetztMs: Long): Aktion = when {
        kmh != null -> if (kmh != zuletztKmh || jetztMs - zuletztMs >= NEU_NACH_MS) Aktion.SENDEN else Aktion.NICHTS
        zuletztKmh != null -> Aktion.LOESCHEN
        else -> Aktion.NICHTS
    }

    /** Rein: Anfragekörper; ohne Wert `{"kmh":null}`. */
    internal fun koerper(kmh: Int?): String = JSONObject().put("kmh", kmh ?: JSONObject.NULL).toString()

    private var zuletztKmh: Int? = null
    private var zuletztMs = 0L

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    private val pool by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "TempolimitMelder").apply { isDaemon = true } }
    }

    /** Aktuellen Wert der Führung melden (null = kein belegtes Limit hier). Billig, darf jede Sekunde gerufen werden. */
    @JvmStatic
    fun aktualisiere(kmh: Int?, jetztMs: Long = System.currentTimeMillis()) {
        val senden: Int?
        synchronized(this) {
            val aktion = entscheide(kmh, zuletztKmh, zuletztMs, jetztMs)
            if (aktion == Aktion.NICHTS) return
            zuletztKmh = kmh
            zuletztMs = jetztMs
            senden = kmh
        }
        pool.execute {
            try {
                val req = Request.Builder().url(URL).post(koerper(senden).toRequestBody(JSON)).build()
                http.newCall(req).execute().use { }
            } catch (t: Throwable) {
                Log.w(TAG, "melden: ${t.javaClass.simpleName}")   // kein Wert im Log
            }
        }
    }

    /** Führung zu Ende: einmal „leer“ melden, falls ein Wert gemeldet war. */
    @JvmStatic
    fun beenden() = aktualisiere(null)
}
