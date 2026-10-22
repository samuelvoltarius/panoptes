package at.greenchili.surfmate.onboarding.schritte

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Texte für den Assistenten.
 *
 * - Bedientexte (Knöpfe, Überschriften) kommen aus res/values*\/strings_einrichtung.xml (Präfix einr_).
 * - Pflicht- und Rechtstexte (disclaimer.setup.*, disclaimer.guard.body, guardLegal.trigger.*, speedcam.rechtshinweis.*,
 *   kartenquelle.*) kommen WÖRTLICH aus den Web-Katalogen im APK (assets/web/i18n/<sprache>.json). So sind Display
 *   und Web nie verschieden (Abnahme C: „Texte stimmen wörtlich mit PFLICHTTEXTE/DISCLAIMER überein“).
 */
class Texte(private val ctx: Context) {
    private val katalog: JSONObject by lazy { lade(sprache()) }
    private val deutsch: JSONObject by lazy { if (sprache() == "de") katalog else lade("de") }
    private val server: JSONObject by lazy { lade(sprache(), "server-i18n") }

    fun sprache(): String {
        val l = try { ctx.resources.configuration.locales[0].language } catch (_: Throwable) { "de" }
        return if (l in SPRACHEN) l else "de"
    }

    /** Wert aus dem Web-Katalog (Punkt-Pfad), sonst Deutsch, sonst [rueckfall]. Platzhalter {name} → [werte]. */
    fun web(pfad: String, rueckfall: String = "", werte: Map<String, Any?> = emptyMap()): String {
        val v = finde(katalog, pfad) ?: finde(server, pfad) ?: finde(deutsch, pfad) ?: rueckfall
        var s = v
        for ((k, w) in werte) s = s.replace("{$k}", w?.toString() ?: "")
        return s
    }

    fun hat(pfad: String): Boolean = finde(katalog, pfad) != null || finde(deutsch, pfad) != null

    fun res(id: Int, vararg args: Any): String = ctx.getString(id, *args)

    private fun finde(o: JSONObject, pfad: String): String? {
        var cur: Any? = o
        for (teil in pfad.split('.')) {
            cur = (cur as? JSONObject)?.opt(teil) ?: return null
        }
        return (cur as? String)?.takeIf { it.isNotEmpty() }
    }

    private fun lade(l: String, ordner: String = "web/i18n"): JSONObject = try {
        ctx.assets.open("$ordner/$l.json").bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
    } catch (t: Throwable) {
        Log.w("EinrichtungTexte", "Katalog $l fehlt: ${t.message}")
        JSONObject()
    }

    companion object {
        val SPRACHEN = listOf("de", "en", "fr", "it", "nl")
    }
}
