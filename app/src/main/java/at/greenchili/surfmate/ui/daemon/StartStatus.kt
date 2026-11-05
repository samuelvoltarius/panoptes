package at.greenchili.surfmate.ui.daemon

import org.json.JSONArray
import org.json.JSONObject

/** 0.1.38.10: Stufen des gestaffelten Starts, in dieser Reihenfolge (PLAN_START_GESTAFFELT_013810). */
enum class StartStufe(val schluessel: String) {
    /** eigenes tailscaled, nur wenn `daemons.tailscale` an ist */
    ZUGANG("zugang"),
    /** Webserver :8180 (Kamera-Daemon) */
    OBERFLAECHE("oberflaeche"),
    /** Sentry + ACC-Sentry */
    WAECHTER("waechter"),
    /** Telegram, Brücke, MQTT, WireGuard – nur was eingeschaltet ist. Angezeigt wird die Stufe nur mit Telegram, denn nur
     *  Telegram hat eine Prüfung (Prozess); Brücke/MQTT stecken im Kamera-Daemon, WireGuard hat keinen eigenen Test. */
    REST("rest");
}

enum class StufeZustand(val schluessel: String) { STARTET("startet"), BEREIT("bereit"), STARTET_NICHT("startet_nicht") }

/**
 * 0.1.38.10 „Anzeige je Dienst beim Start“: EINE Stelle für den Zustand des Starts, im App-Prozess. Welche Stufen es
 * gibt, legt der Start fest ([beginne], aus `DaemonStartPlan`), der Start meldet je Stufe [melde]. Leser: der native
 * Ladebildschirm („Panoptes startet …“, gleicher Prozess) und die Startseite über `GET /api/start/status`, das die
 * WebView der App selbst beantwortet (kein Daemon, keine Datei, keine zweite Liste).
 * Ohne [beginne] ist der Stand leer: dann zeigen beide nichts Neues.
 */
object StartStatus {
    /**
     * Rückfall nur für die Anzeige: Meldet niemand mehr (Ablauf abgebrochen), gilt „startet …“ nach dieser Zeit als
     * „startet nicht“. Länger als der längste Ablauf (Boot-Schutz 45 s + Oberfläche 60 s + Wächter 20 s + Beobachtung
     * 60 s), damit der Ablauf seine eigenen Zeitlimits ([DaemonStartPlan.zeitlimitMs]) selbst meldet.
     */
    const val ZEITLIMIT_MS = 300_000L

    /**
     * 0.1.38.11: Abstand je Stufe, in dem ein Abruf (Startkarte, Ladebildschirm) nachprüfen lässt, ob der Dienst läuft.
     * So wird „✓“ auch gemeldet, wenn ein anderer Weg (Health-Check, Fremdstart) ihn gestartet hat oder der Ablauf
     * abgebrochen wurde (cleanup, Übergabe Boot → MainActivity) – statt nach [ZEITLIMIT_MS] „startet nicht“.
     */
    const val NACHPRUEF_MS = 5_000L

    private val lock = Any()
    private var stufen: List<StartStufe> = emptyList()
    private val zustand = LinkedHashMap<StartStufe, StufeZustand>()
    /** Zeit (gleiche Uhr wie [beginne]) des letzten Wechsels je Stufe. */
    private val wechselMs = HashMap<StartStufe, Long>()
    private var beginnMs = -1L
    /** Letzte Nachprüfung je Stufe (gleiche Uhr). */
    private val geprueftMs = HashMap<StartStufe, Long>()
    /** Stufen, deren „startet nicht“ schon im Start-Protokoll steht (einmal je Start). */
    private val protokolliert = HashSet<StartStufe>()

    /**
     * Prüft, ob die Stufe läuft – dieselben Prüfungen wie der Ablauf (DaemonStartupManager.stufeBereit), eine Quelle.
     * Setzt der Manager, der gerade lebt; bei cleanup nimmt er sich wieder heraus ([nachprueferEntfernen]).
     */
    private var nachpruefer: ((StartStufe, (Boolean) -> Unit) -> Unit)? = null
    private var nachprueferVon: Any? = null

    /** Schreibt ins Start-Protokoll (daemon_launcher.log); in JVM-Tests ersetzbar. */
    @JvmStatic
    var protokoll: (String, String) -> Unit = { ereignis, werte ->
        try { at.greenchili.surfmate.logging.LauncherEreignisse.merken(ereignis, werte) } catch (_: Throwable) {}
    }

    @JvmStatic
    fun nachprueferSetzen(von: Any, p: (StartStufe, (Boolean) -> Unit) -> Unit) = synchronized(lock) {
        nachpruefer = p
        nachprueferVon = von
    }

    /** Nur der eigene Eintrag wird entfernt: ein neuerer Manager (MainActivity) bleibt eingetragen. */
    @JvmStatic
    fun nachprueferEntfernen(von: Any) = synchronized(lock) {
        if (nachprueferVon === von) { nachpruefer = null; nachprueferVon = null }
    }

    /** Neuer Start: alle [neu] auf „startet …“. Stufen, die es nicht gibt (z. B. Zugang ohne Tailscale), fehlen. */
    @JvmStatic
    fun beginne(neu: List<StartStufe>, jetztMs: Long) = synchronized(lock) {
        stufen = StartStufe.values().filter { it in neu }
        zustand.clear()
        wechselMs.clear()
        stufen.forEach { zustand[it] = StufeZustand.STARTET; wechselMs[it] = jetztMs }
        beginnMs = jetztMs
        geprueftMs.clear()
        protokolliert.clear()
    }

    /**
     * Eine Stufe startet, ist bereit oder gescheitert; [jetztMs] mit derselben Uhr wie [beginne]
     * (SystemClock.elapsedRealtime). Unbekannte Stufen (nicht in [beginne]) und Wiederholungen werden ignoriert.
     */
    @JvmStatic
    fun melde(stufe: StartStufe, z: StufeZustand, jetztMs: Long) {
        val log = synchronized(lock) {
            if (zustand.containsKey(stufe) && zustand[stufe] != z) {
                zustand[stufe] = z
                wechselMs[stufe] = jetztMs
                z == StufeZustand.STARTET_NICHT && protokolliert.add(stufe)
            } else false
        }
        if (log) protokoll("STUFE_STARTET_NICHT", "stufe=${stufe.schluessel} grund=zeitlimit_ablauf")
    }

    /**
     * Beim Abruf: Stufen, die nicht „bereit“ sind, höchstens alle [NACHPRUEF_MS] nachprüfen lassen; läuft der Dienst,
     * wird „bereit“ gemeldet (der nächste Abruf zeigt ✓). Ohne Nachprüfer (kein Manager) passiert nichts.
     */
    @JvmStatic
    fun nachpruefen(jetztMs: Long) {
        val (p, offen) = synchronized(lock) {
            val p = nachpruefer ?: return
            val offen = stufen.filter { s ->
                zustand[s] != StufeZustand.BEREIT && jetztMs - (geprueftMs[s] ?: Long.MIN_VALUE / 2) >= NACHPRUEF_MS
            }
            offen.forEach { geprueftMs[it] = jetztMs }
            p to offen
        }
        offen.forEach { s ->
            try {
                p(s) { laeuft -> if (laeuft) melde(s, StufeZustand.BEREIT, uhr()) }
            } catch (_: Throwable) {}
        }
    }

    /** Wie [melde] mit der Uhr des Starts (SystemClock.elapsedRealtime), für den Ablauf in DaemonStartupManager. */
    @JvmStatic
    fun melde(stufe: StartStufe, z: StufeZustand) = melde(stufe, z, uhr())

    private fun uhr(): Long = try {
        android.os.SystemClock.elapsedRealtime()
    } catch (_: Throwable) {
        System.nanoTime() / 1_000_000L   // JVM-Tests ohne Android
    }

    /** Stand je Stufe; „startet …“ nach [ZEITLIMIT_MS] wird zu „startet nicht“. Leer = kein Start bekannt. */
    @JvmStatic
    fun stand(jetztMs: Long): List<Pair<StartStufe, StufeZustand>> {
        val neuNicht = ArrayList<StartStufe>()
        val st = synchronized(lock) {
            val alt = beginnMs >= 0 && jetztMs - beginnMs >= ZEITLIMIT_MS
            stufen.map { s ->
                val z = zustand[s] ?: StufeZustand.STARTET
                val nicht = alt && z == StufeZustand.STARTET
                if (nicht && protokolliert.add(s)) neuNicht.add(s)
                s to (if (nicht) StufeZustand.STARTET_NICHT else z)
            }
        }
        neuNicht.forEach { protokoll("STUFE_STARTET_NICHT", "stufe=${it.schluessel} grund=keine_meldung_300s") }
        return st
    }

    /** Läuft gerade ein Start, der noch nicht fertig ist (mindestens eine Stufe „startet …“)? */
    @JvmStatic
    fun laeuft(jetztMs: Long): Boolean = stand(jetztMs).any { it.second == StufeZustand.STARTET }

    /** Antwort für `GET /api/start/status`: {aktiv, fertig, seitMs, stufen:[{stufe, zustand, abMs}]}. */
    @JvmStatic
    fun json(jetztMs: Long): JSONObject {
        val st = stand(jetztMs)
        val arr = JSONArray()
        val seit = synchronized(lock) { beginnMs }
        val ab = synchronized(lock) { HashMap(wechselMs) }
        // abMs: ms seit dem Beginn des Starts beim letzten Wechsel dieser Stufe (für Messung und Anzeige)
        st.forEach { (s, z) ->
            val w = ab[s]
            arr.put(JSONObject().put("stufe", s.schluessel).put("zustand", z.schluessel)
                .put("abMs", if (w != null && seit >= 0) w - seit else JSONObject.NULL))
        }
        return JSONObject()
            .put("aktiv", st.isNotEmpty())
            .put("fertig", st.isNotEmpty() && st.none { it.second == StufeZustand.STARTET })
            .put("seitMs", if (seit >= 0) jetztMs - seit else JSONObject.NULL)
            .put("stufen", arr)
    }

    /** Nur Tests. */
    @JvmStatic
    fun zuruecksetzen() = synchronized(lock) {
        stufen = emptyList()
        zustand.clear()
        wechselMs.clear()
        beginnMs = -1L
        geprueftMs.clear()
        protokolliert.clear()
        nachpruefer = null
        nachprueferVon = null
    }
}
