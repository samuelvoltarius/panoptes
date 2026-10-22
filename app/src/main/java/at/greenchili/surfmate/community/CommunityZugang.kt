package at.greenchili.surfmate.community

import org.json.JSONObject

/**
 * Gemeinsamer Zugang zum eigenen Community-Server (Projekt-Server `/community/api`, Server-Vertrag)
 *  für „Regeln teilen“ und „Straßenzustand gemeinsam“.
 *
 * - **Standard aus:** ohne `community.enabled = true` geht kein einziger Aufruf hinaus,
 *   auch wenn eine URL oder RoadSense-Crowd-Schalter gesetzt sind (Koordination 21.10.).
 * - **Mitglieds-Schlüssel** ist derselbe wie für den Planer (`planner.memberToken`, 32 hex),
 *   geht nur im Header `X-Member-Token`, nie in URL oder Log.
 * - **Nie Overdrive-Server** (21.10.): alte Worker-URLs des Ursprungsprojekts
 *   (`*.yash321sri.workers.dev`) werden durch den eigenen Server ersetzt.
 *
 * Rein (nur JSON), damit die Regeln ohne Android testbar sind.
 */
object CommunityZugang {

    const val STANDARD_REGELN = "https://byd.serveone.at/community/api"
    const val STANDARD_STRASSE = "https://byd.serveone.at/community/api/strasse"
    const val HEADER = "X-Member-Token"
    const val HEADER_GERAET = "X-Geraete-Token"

    private val GERAET = Regex("^[0-9a-f]{64}$")

    /** Fehlertext des Providers, wenn der Schalter aus ist (wird zu 403 + hinweis „community_aus“). */
    const val AUS = "community_aus"

    private val SCHLUESSEL = Regex("^[0-9a-fA-F]{32}$")

    /** Schalter `community.enabled`, Standard aus. */
    fun aktiv(root: JSONObject?): Boolean =
        root?.optJSONObject("community")?.optBoolean("enabled", false) ?: false

    /** Mitglieds-Schlüssel aus `planner.memberToken` oder null. */
    fun schluessel(root: JSONObject?): String? {
        val t = root?.optJSONObject("planner")?.optString("memberToken", "")?.trim() ?: return null
        return if (SCHLUESSEL.matches(t)) t else null
    }

    /**
     * F3 (0.1.9.1): EIN Planer-Zugang für Planer und Community. Zuerst der Geräte-Schlüssel aus „Mit
     * Routenplaner verbinden“ (`planner.geraeteSchluessel`, entschlüsselt), als Rückfall der alte
     * Mitglieds-Schlüssel (`planner.memberToken`). Reihenfolge = Versuchsreihenfolge; bei 401 versuchen die
     * Provider einmal den nächsten.
     */
    fun kandidaten(root: JSONObject?, geraetKlar: String?): List<Pair<String, String>> {
        val l = ArrayList<Pair<String, String>>(2)
        if (geraetKlar != null && GERAET.matches(geraetKlar)) l.add(HEADER_GERAET to geraetKlar)
        schluessel(root)?.let { l.add(HEADER to it) }
        return l
    }

    /** Laufzeit: Config lesen, Geräte-Schlüssel mit CredentialCipher entschlüsseln. */
    @JvmStatic
    fun kandidatenLaufzeit(): List<Pair<String, String>> = try {
        val root = at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig()
        val enc = root?.optJSONObject("planner")?.optString("geraeteSchluessel", "") ?: ""
        val klar = if (enc.isEmpty()) null else try {
            at.greenchili.surfmate.byd.cloud.crypto.CredentialCipher.decrypt(enc)
        } catch (_: Throwable) { null }
        kandidaten(root, klar)
    } catch (_: Throwable) {
        emptyList()
    }

    /** Alte Worker des Ursprungsprojekts – dort geht nie etwas hin. */
    fun istFremd(url: String?): Boolean {
        val u = url?.trim()?.lowercase() ?: return false
        return u.contains("yash321sri") || u.contains("overdrive")
    }

    /**
     * Wirksame Basis-URL oder null (= kein Aufruf).
     *
     * @param gesetzt     Wert aus der Config, null wenn der Schlüssel fehlt
     * @param standard    eigener Server
     */
    fun basis(root: JSONObject?, gesetzt: String?, standard: String): String? {
        if (!aktiv(root)) return null
        val u = gesetzt?.trim()
        if (u == null || u.isEmpty() || istFremd(u)) return standard
        if (!u.startsWith("https://")) return standard
        return u.trimEnd('/')
    }

    /** Hinweis-Kennung für die Oberfläche zu einem Server-Status. */
    fun hinweis(code: Int, fehler: String?): String? = when {
        fehler == AUS -> "community_aus"
        code == 401 -> "mitgliedsschluessel_eintragen"
        code == 422 -> "persoenliche_daten"
        code == 429 -> "ratenlimit"
        else -> null
    }

    /** Platzhalter des Servers für WLAN-/Bluetooth-Namen in einer importierten Regel. */
    const val PLATZHALTER = "BITTE-EINTRAGEN"

    fun hatPlatzhalter(rules: JSONObject?): Boolean = rules?.toString()?.contains(PLATZHALTER) ?: false
}
