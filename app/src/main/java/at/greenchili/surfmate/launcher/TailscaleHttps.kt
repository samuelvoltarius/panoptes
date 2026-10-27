package at.greenchili.surfmate.launcher

import org.json.JSONObject

/**
 * HTTPS im eigenen Tailnet (ts.net-Zertifikat) für die Web-Oberfläche – Grundlage für Web-Push
 * (Koordination 21.10., 0.1.9, Regel B). Läuft im Daemon (Shell-UID) und ruft nur die tailscale-CLI.
 *
 * - Standard AUS. Eingeschaltet nur ausdrücklich (PUT an=true); Merkdatei wie TailscaleLauncher
 *   (`.tailscale/https_enabled`), damit beide Wege dasselbe sehen.
 * - Eigentumsprüfung über [TailscaleLauncher.parseHttpsServeSnapshot]: nur die eigene 443-Regel wird
 *   gesetzt bzw. zurückgezogen, nie `serve reset`, nie eine fremde Regel.
 * - tailscaled wird NIE beendet oder neu gestartet, auch nicht bei Fehlern.
 *
 * Antwortformat (Neubau 2): {tailscale, httpsMoeglich, an, adresse, grund}
 * grund ∈ kein_tailscale | https_im_tailnet_aus | zertifikat_fehlt | belegt | null
 */
object TailscaleHttps {

    /** Führt einen Shell-Befehl aus und liefert stdout (leer bei Fehler). Für Tests austauschbar. */
    fun interface Shell {
        fun run(cmd: String, timeoutSec: Int): String
    }

    private val HOME = at.greenchili.surfmate.util.DaemonStorage.rebase(
        "/data/user_de/0/com.android.shell/surfmate/.tailscale")
    private val CLI = "$HOME/tailscale"
    private const val SOCKET = "127.0.0.1:8632"
    private val MERKDATEI = "$HOME/https_enabled"

    /** Text, den die Oberfläche beim Einschalten zeigt (Koordination 21.10.). */
    const val HINWEIS_DE = "Dein Auto bekommt eine HTTPS-Adresse im eigenen Tailnet (ts.net). Sie ist nicht öffentlich."

    private fun cli(args: String) = "'$CLI' --socket $SOCKET $args"

    /** Reine Auswertung aus `status --json`, `serve status --json` und der Merkdatei. */
    @JvmStatic
    fun zustand(statusJson: String, serveJson: String, merk: String?): JSONObject {
        val status = try { JSONObject(statusJson.trim().ifEmpty { "{}" }) } catch (_: Exception) { JSONObject() }
        val laeuft = status.optString("BackendState", "") == "Running"
        val dns = status.optJSONObject("Self")?.optString("DNSName", "")?.trim()?.trimEnd('.') ?: ""
        val zertifikate = status.optJSONArray("CertDomains")
        val moeglich = laeuft && dns.isNotEmpty() && zertifikate != null && zertifikate.length() > 0
        val snap = TailscaleLauncher.parseHttpsServeSnapshot(serveJson)
        val eigen = snap.ownership == TailscaleLauncher.HttpsServeOwnership.OWNED ||
            snap.ownership == TailscaleLauncher.HttpsServeOwnership.LEGACY_OWNED
        val gewollt = merk?.trim() == "true"
        val an = laeuft && gewollt && eigen
        val grund: Any = when {
            !laeuft -> "kein_tailscale"
            !moeglich -> "https_im_tailnet_aus"
            snap.ownership == TailscaleLauncher.HttpsServeOwnership.CONFLICT -> "belegt"
            gewollt && !eigen -> "zertifikat_fehlt"
            else -> JSONObject.NULL
        }
        return JSONObject()
            .put("tailscale", laeuft)
            .put("httpsMoeglich", moeglich)
            .put("an", an)
            .put("adresse", if (an) "https://" + (snap.domain?.takeIf { it.isNotEmpty() } ?: dns) else JSONObject.NULL)
            .put("grund", grund)
    }

    private fun lies(sh: Shell): Triple<String, String, String> = Triple(
        sh.run(cli("status --json"), 10),
        sh.run(cli("serve status --json"), 10),
        sh.run("cat '$MERKDATEI' 2>/dev/null", 5),
    )

    @JvmStatic
    fun lesen(sh: Shell): JSONObject {
        val (st, sv, merk) = lies(sh)
        return zustand(st, sv, merk)
    }

    /** Ein- bzw. ausschalten; liefert danach den zurückgelesenen Zustand. */
    @JvmStatic
    fun setzen(an: Boolean, sh: Shell): JSONObject {
        val (st, sv, merk) = lies(sh)
        val vorher = zustand(st, sv, merk)
        val snap = TailscaleLauncher.parseHttpsServeSnapshot(sv)
        if (an) {
            if (!vorher.optBoolean("httpsMoeglich")) return vorher
            if (snap.ownership == TailscaleLauncher.HttpsServeOwnership.CONFLICT ||
                snap.ownership == TailscaleLauncher.HttpsServeOwnership.UNKNOWN) return vorher
            merkSchreiben(true, sh)
            if (snap.ownership == TailscaleLauncher.HttpsServeOwnership.FREE) {
                // Zertifikat holen kann dauern; Fehler werden nur zurückgelesen, nie eskaliert.
                sh.run(cli(TailscaleLauncher.secureHttpsServeArgs()) + " 2>&1 || true", 60)
            }
        } else {
            merkSchreiben(false, sh)
            when (snap.ownership) {
                TailscaleLauncher.HttpsServeOwnership.OWNED ->
                    sh.run(cli(TailscaleLauncher.disableHttpsServeArgs()) + " 2>&1 || true", 15)
                TailscaleLauncher.HttpsServeOwnership.LEGACY_OWNED ->
                    sh.run(cli("serve --tls-terminated-tcp=443 off") + " 2>&1 || true", 15)
                else -> Unit   // fremde oder keine Regel: nichts anfassen
            }
        }
        return lesen(sh)
    }

    /**
     * 0.1.21 (PWA Stufe 1): Soll der Wächter die eigene 443-Regel neu setzen? Nur wenn der Nutzer HTTPS eingeschaltet hat
     * (Merkdatei), Tailscale läuft, das Tailnet Zertifikate erlaubt und Port 443 FREI ist (Regel fehlt, z. B. nach einem
     * Zurücksetzen von tailscaled). Eine fremde oder unklare Regel wird nie angefasst.
     */
    @JvmStatic
    fun nachziehen(statusJson: String, serveJson: String, merk: String?): Boolean {
        val z = zustand(statusJson, serveJson, merk)
        if (merk?.trim() != "true" || !z.optBoolean("httpsMoeglich")) return false
        return TailscaleLauncher.parseHttpsServeSnapshot(serveJson).ownership == TailscaleLauncher.HttpsServeOwnership.FREE
    }

    /** Letzter Zustand des Wächters (für Selbsttest/Anzeige), null = noch nie geprüft. */
    @Volatile
    @JvmStatic
    var letzterZustand: JSONObject? = null
        private set

    /** Ein Wächter-Durchlauf: lesen, ggf. Regel neu setzen, zurücklesen. Liefert den Zustand danach. */
    @JvmStatic
    fun waechterLauf(sh: Shell): JSONObject {
        val (st, sv, merk) = lies(sh)
        var z = zustand(st, sv, merk)
        if (nachziehen(st, sv, merk)) {
            sh.run(cli(TailscaleLauncher.secureHttpsServeArgs()) + " 2>&1 || true", 60)
            z = lesen(sh).put("nachgezogen", true)
        }
        letzterZustand = z
        return z
    }

    private val waechterGestartet = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Startet den Wächter im Daemon (Shell-UID): alle 2 Minuten. Ein Tailscale-Neustart verliert die Regel normalerweise
     * nicht (serve --bg ist in tailscaled gespeichert); fehlt sie trotzdem, ist sie spätestens nach 2 Minuten wieder da.
     * Ohne eingeschaltetes HTTPS ändert der Wächter nie etwas.
     */
    @JvmStatic
    fun starteWaechter(sh: Shell) {
        if (!waechterGestartet.compareAndSet(false, true)) return
        val ex = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "TailscaleHttpsWaechter").apply { isDaemon = true }
        }
        ex.scheduleWithFixedDelay({
            try {
                val vorher = letzterZustand
                val z = waechterLauf(sh)
                if (z.optBoolean("nachgezogen") || vorher?.optBoolean("an") != z.optBoolean("an")) {
                    at.greenchili.surfmate.logging.DaemonLogger.getInstance("TailscaleHttps").info("Wächter: an=" + z.optBoolean("an") + " grund=" + z.opt("grund")
                            + (if (z.optBoolean("nachgezogen")) " (Regel neu gesetzt)" else ""))
                }
            } catch (_: Throwable) {
            }
        }, 30, 120, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun merkSchreiben(an: Boolean, sh: Shell) {
        sh.run("mkdir -p '$HOME' && echo $an > '$MERKDATEI' && chmod 600 '$MERKDATEI'", 5)
    }
}
