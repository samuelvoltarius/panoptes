package at.greenchili.surfmate.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * 0.1.38.14 (P3): erkennt eine eingefrorene Oberfläche (UI-Thread antwortet nicht), schreibt sie ins Log und schickt
 * dem Besitzer eine Telegram-Notiz mit dem Hinweis auf `/fehler` (Seal U: Hänger beim Umschalten der Kartenquelle und
 * in der Tacho-Einrichtung, ohne Weg zu einem Fehlerbericht).
 *
 * Ein eigener Faden stellt alle [TAKT_MS] eine Marke in die Haupt-Schleife. Bleibt sie länger als [SCHWELLE_MS]
 * liegen, gilt die Oberfläche als hängend: einmal je Hänger ins Log, höchstens alle [MELDE_ABSTAND_MS] eine Notiz
 * (Telegram-Kategorie „Gesundheit“, also abschaltbar). Gemessen wird mit uptimeMillis – im Tiefschlaf steht die Uhr,
 * so gibt es nach dem Aufwachen keinen falschen Alarm. Ändert nichts am Ablauf der App.
 */
object UiHaengerWaechter {

    private const val TAG = "UiHaenger"
    const val TAKT_MS = 2_000L
    const val SCHWELLE_MS = 10_000L
    const val MELDE_ABSTAND_MS = 30 * 60_000L

    @Volatile private var letzterTakt = 0L
    @Volatile private var gestartet = false
    private var imHaenger = false
    private var letzteMeldung = 0L

    /** Rein: hängt die Oberfläche (letzte Marke älter als [schwelle])? */
    @JvmStatic
    fun haengt(letzterTaktMs: Long, jetztMs: Long, schwelle: Long = SCHWELLE_MS): Boolean =
        letzterTaktMs > 0L && jetztMs - letzterTaktMs > schwelle

    /** Rein: darf wieder eine Telegram-Notiz raus? */
    @JvmStatic
    fun meldenErlaubt(letzteMeldungMs: Long, jetztMs: Long): Boolean =
        letzteMeldungMs == 0L || jetztMs - letzteMeldungMs >= MELDE_ABSTAND_MS

    @JvmStatic
    fun starten(context: Context) {
        if (gestartet) return
        gestartet = true
        val app = context.applicationContext
        val haupt = Handler(Looper.getMainLooper())
        val marke = Runnable { letzterTakt = SystemClock.uptimeMillis() }
        letzterTakt = SystemClock.uptimeMillis()
        Thread({
            while (true) {
                try {
                    haupt.post(marke)
                    Thread.sleep(TAKT_MS)
                    pruefen(app, SystemClock.uptimeMillis())
                } catch (_: InterruptedException) {
                    return@Thread
                } catch (t: Throwable) {
                    Log.w(TAG, "Prüfung fehlgeschlagen: ${t.message}")
                }
            }
        }, "UiHaengerWaechter").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    private fun pruefen(app: Context, jetzt: Long) {
        val takt = letzterTakt
        if (!haengt(takt, jetzt)) {
            if (imHaenger) Log.i(TAG, "UI_HAENGER vorbei")
            imHaenger = false
            return
        }
        if (imHaenger) return
        imHaenger = true
        val sekunden = (jetzt - takt) / 1000
        Log.w(TAG, "UI_HAENGER: UI-Thread antwortet seit $sekunden s nicht\n" +
            Looper.getMainLooper().thread.stackTrace.take(12).joinToString("\n") { "  at $it" })
        try {
            at.greenchili.surfmate.logging.LauncherEreignisse.merken("UI_HAENGER", "s=$sekunden")
        } catch (_: Throwable) {}
        if (!meldenErlaubt(letzteMeldung, jetzt)) return
        letzteMeldung = jetzt
        try {
            val text = app.getString(at.greenchili.surfmate.R.string.ui_haenger_telegram, sekunden.toInt())
            at.greenchili.surfmate.telegram.TelegramNotifier.sendMessage(text, "HEALTH")
        } catch (t: Throwable) {
            Log.w(TAG, "Telegram-Notiz nicht möglich: ${t.message}")
        }
    }
}
