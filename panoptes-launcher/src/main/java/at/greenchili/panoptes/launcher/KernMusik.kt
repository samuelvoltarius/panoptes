package at.greenchili.panoptes.launcher

import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * M1 (13.11.2026): Titel und Interpret ohne Benachrichtigungszugriff. Am BYD lässt sich die Android-Seite
 * „Benachrichtigungszugriff“ nicht öffnen (DiLink: „IVI-System unterstützt diesen Vorgang nicht“), erteilt wird er
 * nie per Befehl. Der Panoptes-Kern liest darum die laufende Mediensitzung selbst (`GET /api/launcher/v1/musik`,
 * nur lesen, nur für die Launcher-APK). Gesteuert wird weiter mit Android-Medientasten wie bisher ohne Zugriff.
 *
 * Abgefragt wird nur, solange der Takt läuft und der Launcher keinen eigenen Zugriff hat, höchstens alle [ABSTAND_MS],
 * auf einem eigenen Faden (nie auf dem Haupt-Thread).
 */
class KernMusik(private val holen: (String?) -> String?) {
    data class Stand(val lesbar: Boolean, val paket: String?, val titel: String?, val kuenstler: String?, val spielt: Boolean, val um: Long)

    @Volatile var stand: Stand? = null
        private set
    private var zuletzt = 0L
    private val laeuft = AtomicBoolean(false)
    private val faden = Executors.newSingleThreadExecutor { r -> Thread(r, "KernMusik").apply { isDaemon = true } }

    /** Neue Abfrage anstoßen, wenn keine läuft und die letzte alt genug ist. [fertig] kommt auf dem Abfrage-Faden. */
    fun anstossen(jetzt: Long, fokusPaket: String?, fertig: () -> Unit = {}) {
        if (jetzt - zuletzt < ABSTAND_MS && jetzt >= zuletzt) return
        if (!laeuft.compareAndSet(false, true)) return
        zuletzt = jetzt
        faden.execute {
            try {
                val neu = parse(runCatching { holen(fokusPaket) }.getOrNull(), System.currentTimeMillis())
                val alt = stand
                stand = neu
                if (alt?.copy(um = 0) != neu?.copy(um = 0)) fertig()
            } finally { laeuft.set(false) }
        }
    }

    /** Liefert der Kern gerade Titel (frisch und lesbar)? */
    fun liefert(jetzt: Long): Boolean = frisch(stand, jetzt)

    /** Als [Wiedergabe] für Karte und Statusleiste; ohne Controller (Tasten gehen als Medientasten), ohne Cover. */
    fun wiedergabe(jetzt: Long): Wiedergabe? = stand?.takeIf { frisch(it, jetzt) }?.let { s ->
        s.paket?.let { Wiedergabe(it, s.titel, s.kuenstler, null, s.spielt, null, null, null) }
    }

    companion object {
        const val ABSTAND_MS = 2_000L
        /** Älter als das gilt als „Kern liefert nicht“ (Kern weg, Abfrage hängt). */
        const val FRISCH_MS = 15_000L

        fun frisch(s: Stand?, jetzt: Long): Boolean = s != null && s.lesbar && jetzt - s.um in 0..FRISCH_MS

        /** Antwort des Kerns; null = nicht erreichbar oder unbrauchbar. */
        fun parse(text: String?, jetzt: Long): Stand? = try {
            if (text.isNullOrBlank()) null else {
                val o = JSONObject(text)
                if (!o.optBoolean("ok", false)) null
                else Stand(o.optBoolean("lesbar", false), wert(o, "paket"), wert(o, "titel"), wert(o, "kuenstler"),
                    o.optBoolean("spielt", false), jetzt)
            }
        } catch (_: Exception) { null }

        private fun wert(o: JSONObject, k: String): String? =
            if (o.isNull(k)) null else o.optString(k, "").trim().takeIf { it.isNotEmpty() }
    }
}

/**
 * M1: Wie zeigt die Musik-Karte ohne eigenen Zugriff? Reine Regel.
 * - [LOKAL]: Launcher hat Zugriff (wie bisher, mit Cover und Fortschritt).
 * - [KERN]: Titel und Interpret kommen vom Kern; kein Zugriffsknopf.
 * - [OHNE_TITEL]: BYD und Kern liefert nicht. Tasten bleiben (Medientasten), Hinweis statt Titel, KEIN Knopf zur
 *   Android-Seite – die ist am BYD gesperrt und führte nur in eine Fehlermeldung.
 * - [ZUGRIFF]: anderes Android-Gerät ohne Kern: der bisherige Knopf zur Android-Seite.
 */
object MusikAnzeige {
    enum class Modus { LOKAL, KERN, OHNE_TITEL, ZUGRIFF }

    /** Werks-Pakete, an denen der Launcher den BYD erkennt (die Android-Seite „Benachrichtigungszugriff“ ist dort gesperrt). */
    val BYD_PAKETE = listOf("com.byd.mediacenter", Raster.CARPLAY, Raster.ANDROID_AUTO)

    fun modus(erlaubt: Boolean, kernLiefert: Boolean, byd: Boolean): Modus = when {
        erlaubt -> Modus.LOKAL
        kernLiefert -> Modus.KERN
        byd -> Modus.OHNE_TITEL
        else -> Modus.ZUGRIFF
    }

    /** Darf der Launcher die Android-Seite „Benachrichtigungszugriff“ öffnen? Am BYD nie. */
    fun androidSeite(byd: Boolean): Boolean = !byd
}
