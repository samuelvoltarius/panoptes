package at.greenchili.surfmate.ui

/**
 * 0.1.43.18 (F4a, KAMERA_AUSSETZER_ANALYSE): Anlaufzeit für „Kamera-Zuordnung öffnen“ in der App am Auto.
 *
 * Bisher führte eine einzige fehlgeschlagene Abfrage von `/api/surveillance/config` sofort zum Hinweis
 * „Panoptes erreicht die Kameras gerade nicht …“, auch in den ersten Sekunden nach Kern-Start oder Zündung, wenn der
 * Kern nur noch anlief. Jetzt wird in der Anlaufzeit nachgefragt, dabei einmal „Kameras starten noch …“ gezeigt; erst
 * danach der bisherige Hinweis. Der Einrichtungs-Coach wartet selbst auf den Kern und hat einen eigenen Abbruch, darum
 * dort kein Nachfragen. Rein, JVM-testbar.
 *
 * 0.1.43.18b (E2E_014318 A2): Der Kern war nach 18 s erreichbar, aber noch am Initialisieren; der Abruf lief in die
 * 4-s-Lesegrenze und danach war das Fenster zu → Hinweis, obwohl der Kern lief. Darum:
 * - Ein Abruf, der bis zum Fensterende beginnt, läuft zu Ende; der Hinweis kommt erst danach, wenn er fehlschlug.
 * - Antwortet der Kern im Fenster überhaupt ([Abruf.KERN_ANTWORTET]: verbunden, aber Lesezeit-Ende, 5xx oder noch
 *   ohne Konfiguration), verlängert sich das Fenster einmal um [VERLAENGERUNG_MS].
 * - Harte Obergrenze: Ein Abruf beginnt nur, wenn er bis [GRENZE_MS] fertig sein kann ([Fenster.zeitgrenzen]).
 * - Nach einem langsamen Abruf (≥ [INTERVALL_MS]) sofort der nächste, nie zwei gleichzeitig.
 * - Echter Fehler ([Abruf.FEHLER], z. B. 4xx: der Kern lehnt ab) → Hinweis sofort.
 */
object KameraZuordnungAnlauf {
    const val ANLAUF_MS = 25_000L
    const val INTERVALL_MS = 2_000L
    const val VERLAENGERUNG_MS = 10_000L
    const val GRENZE_MS = 35_000L

    /** Verbindungsaufbau- und Lesegrenze von `fetchCameraMappingState` (3 s + 4 s). */
    const val VERBINDEN_MS = 3_000L
    const val LESEN_MS = 4_000L
    const val MAX_ABRUF_MS = VERBINDEN_MS + LESEN_MS

    /** Kürzester Abruf nahe der Grenze: volle Lesezeit, Verbindungsaufbau auf 1 s gekürzt (Loopback antwortet sofort). */
    const val MIN_VERBINDEN_MS = 1_000L
    const val MIN_ABRUF_MS = MIN_VERBINDEN_MS + LESEN_MS

    /** Ausgang eines Abrufs. */
    enum class Abruf {
        /** Antwort mit Konfiguration, Dialog kann öffnen. */
        OK,
        /** Kern erreicht (Verbindung steht), aber noch keine brauchbare Antwort: Lesezeit-Ende, 5xx, ohne Konfiguration. */
        KERN_ANTWORTET,
        /** Kein Verbindungsaufbau (Kern noch nicht da). */
        NICHT_DA,
        /** Echter Fehler, Warten hilft nicht (z. B. 4xx). */
        FEHLER
    }

    /**
     * Ein Fenster je Tipp, alle Zeiten mit derselben monotonen Uhr. [nachAbruf] sagt nach jedem Abruf, wie lange bis
     * zum nächsten zu warten ist (≥ 0), oder [SCHLUSS] (Dialog öffnen bzw. Hinweis zeigen).
     */
    class Fenster(private val startMs: Long, private val mitCoach: Boolean) {
        var endeMs: Long = startMs + ANLAUF_MS
            private set
        var verlaengert: Boolean = false
            private set

        /** Spätester Beginn eines Abrufs: bis Fensterende und so, dass er bis zur harten Grenze fertig ist. */
        fun spaetesterBeginnMs(): Long = minOf(endeMs, startMs + GRENZE_MS - MIN_ABRUF_MS)

        /**
         * Zeitgrenzen (Verbinden, Lesen) für einen Abruf ab [beginnMs]: normal 3 s + 4 s; nahe der harten Grenze wird
         * der Verbindungsaufbau gekürzt (nie unter [MIN_VERBINDEN_MS]), damit der Abruf bis [GRENZE_MS] endet.
         */
        fun zeitgrenzen(beginnMs: Long): Pair<Int, Int> {
            val rest = (startMs + GRENZE_MS - beginnMs).coerceAtLeast(MIN_ABRUF_MS)
            val verbinden = minOf(VERBINDEN_MS, rest - LESEN_MS)
            return Pair(verbinden.toInt(), LESEN_MS.toInt())
        }

        fun nachAbruf(ergebnis: Abruf, abrufBeginnMs: Long, jetztMs: Long): Long {
            if (ergebnis == Abruf.OK || ergebnis == Abruf.FEHLER || mitCoach) return SCHLUSS
            if (jetztMs < startMs || abrufBeginnMs < startMs) return SCHLUSS   // Uhr rückwärts: nie endlos warten
            if (ergebnis == Abruf.KERN_ANTWORTET && !verlaengert && abrufBeginnMs <= endeMs) {
                verlaengert = true
                endeMs = minOf(endeMs + VERLAENGERUNG_MS, startMs + GRENZE_MS)
            }
            val spaetestens = spaetesterBeginnMs()
            if (jetztMs >= spaetestens) return SCHLUSS
            val dauer = (jetztMs - abrufBeginnMs).coerceAtLeast(0L)
            val naechster = minOf(jetztMs + (INTERVALL_MS - dauer).coerceAtLeast(0L), spaetestens)
            return naechster - jetztMs
        }
    }

    const val SCHLUSS = -1L

    /**
     * Ausgang zu einem HTTP-Status ohne brauchbare Antwort. 5xx (Kern initialisiert noch) sowie 401/403/408/429
     * (Anmeldung beim Anlauf noch nicht abgeglichen, Zeitüberschreitung, zu viele Anfragen) sind vorübergehend; jeder
     * andere 4xx ist ein echter Fehler, auf den Warten nicht hilft.
     */
    fun ausgangFuerStatus(code: Int): Abruf = when {
        code in 200..299 -> Abruf.OK
        code >= 500 -> Abruf.KERN_ANTWORTET
        code == 401 || code == 403 || code == 408 || code == 429 -> Abruf.KERN_ANTWORTET
        code in 400..499 -> Abruf.FEHLER
        else -> Abruf.KERN_ANTWORTET
    }

    /** F5: Diagnosezeile fürs Start-Protokoll (ohne Ort, ohne Kennung). */
    fun diagnose(gewartetMs: Long, versuche: Int): String =
        "quelle=zuordnung gewartet_ms=$gewartetMs versuche=$versuche"

    /** 0.1.43.18b: Diagnose mit Ausgang des letzten Abrufs und ob verlängert wurde. */
    fun diagnose(gewartetMs: Long, versuche: Int, letzter: Abruf, verlaengert: Boolean): String =
        diagnose(gewartetMs, versuche) + " letzter=${letzter.name.lowercase()} verlaengert=${if (verlaengert) 1 else 0}"
}
