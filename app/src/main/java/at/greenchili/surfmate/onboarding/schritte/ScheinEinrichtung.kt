package at.greenchili.surfmate.onboarding.schritte

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Schein-§0 NUR für Screenshots/Abnahme im Emulator (WIZARD_AUFTRAG_NEUBAU: „bis dahin gegen einen Schein-Endpunkt“).
 *
 * Aktiv ausschließlich, wenn BEIDES gilt: Build mit -PpanoptesScreenshotModus=true (BuildConfig.SCREENSHOT_MODUS) UND die System-Eigenschaft
 * `debug.panoptes.einrichtung_schein=1` (nur per `adb shell setprop` im Emulator setzbar). Im Release-Build nie.
 * Antwortet wie der Kern laut §0 (Muster aus EinrichtungApiHandlerTest), schreibt nichts außer im Speicher.
 */
object ScheinEinrichtung {
    private const val TAG = "ScheinEinrichtung"

    fun aktivieren() {
        if (!at.greenchili.surfmate.BuildConfig.SCREENSHOT_MODUS) return
        val an = try {
            val c = Class.forName("android.os.SystemProperties")
            (c.getMethod("get", String::class.java, String::class.java).invoke(null, "debug.panoptes.einrichtung_schein", "0") as String) == "1"
        } catch (_: Throwable) { false }
        if (!an) return
        Log.w(TAG, "Schein-§0 aktiv (nur Screenshot-Build + debug.panoptes.einrichtung_schein=1)")
        val echt = EinrichtungApi.transport
        EinrichtungApi.transport = { m, p, b -> antwort(m, p, b) ?: echt(m, p, b) }
    }

    private val status = linkedMapOf(
        "sprache" to "erledigt", "haftung" to "erledigt", "autostart" to "erledigt", "ueberlagerung" to "spaeter",
        "rechte" to "erledigt", "usb_freigabe" to "erledigt", "auto" to "spaeter", "kameras" to "spaeter",
        "waechter" to "offen", "betrieb" to "spaeter", "karte" to "spaeter", "daten" to "spaeter", "blitzer" to "spaeter",
        "updates" to "offen", "extra_telegram" to "spaeter", "extra_handy" to "offen", "extra_planer" to "spaeter",
        "extra_cockpit" to "spaeter", "extra_overdrive" to "spaeter", "extra_fernzugang" to "spaeter", "fertig" to "offen",
    )
    private val pflicht = setOf("sprache", "haftung", "usb_freigabe", "waechter", "updates")
    private val schnellstart = setOf("sprache", "haftung", "autostart", "ueberlagerung", "rechte", "usb_freigabe", "waechter", "updates", "extra_handy", "fertig")
    private var brueckeEin = false

    private fun stand(): JSONObject {
        val arr = JSONArray()
        status.forEach { (id, st) -> arr.put(JSONObject().put("id", id).put("pflicht", id in pflicht).put("status", st).put("schnellstart", id in schnellstart)) }
        val aktuell = status.entries.firstOrNull { it.value == "offen" }?.key ?: "fertig"
        return JSONObject().put("version", 1).put("abgeschlossen", status.none { it.key in pflicht && it.value == "offen" })
            .put("aktuell", aktuell).put("schritte", arr).put("neuZaehler", 0).put("art", "neu")
            .put("offen_spaeter", JSONArray(status.filter { it.value == "spaeter" }.keys.toList()))
            .put("faehigkeiten", JSONObject()
                .put("kameras", JSONObject().put("anzahl", 4).put("status", "ja").put("quelle", "schein"))
                .put("werte", JSONObject().put("ladestand", "ja").put("spannung12v", "ja").put("innentemperatur", "unbekannt")
                    .put("akkutemperatur", "unbekannt").put("reifendruck", "ja"))
                .put("ueberProjektion", JSONObject().put("carplay", "ja").put("androidauto", "unbekannt"))
                .put("sim", true))
            .put("selbsttest", JSONObject.NULL)
    }

    private fun ok(j: JSONObject) = EinrichtungApi.Antwort(200, j)

    private fun antwort(m: String, p: String, b: JSONObject?): EinrichtungApi.Antwort? = when {
        p == "/status" -> ok(JSONObject().put("ok", true))
        p == "/api/einrichtung" -> ok(stand())
        p == "/api/einrichtung/schritt" && b != null -> {
            val id = b.optString("id"); val a = b.optJSONObject("antwort") ?: JSONObject()
            val fehlt = Antworten.fehlt(id, a)
            if (id in SchrittAnsicht.MIT_ANTWORT && fehlt != null) EinrichtungApi.Antwort(422, JSONObject().put("error", "fehlt").put("fehlt", JSONArray(fehlt)))
            else { status[id] = if (a.has("ok") && !a.optBoolean("ok")) "offen" else "erledigt"; ok(JSONObject().put("success", true)) }
        }
        p == "/api/einrichtung/spaeter" && b != null -> {
            val id = b.optString("id")
            if (id in pflicht) EinrichtungApi.Antwort(422, JSONObject().put("error", "pflicht")) else { status[id] = "spaeter"; ok(JSONObject().put("success", true)) }
        }
        p == "/api/einrichtung/neu" -> { status.keys.forEach { status[it] = "offen" }; ok(JSONObject().put("success", true)) }
        p == "/api/einrichtung/selbsttest" -> ok(JSONObject().put("punkte", JSONArray()
            .put(JSONObject().put("id", "autostart").put("text", "Panoptes startet von selbst").put("status", "ok"))
            .put(JSONObject().put("id", "kameras").put("text", "Kameras liefern Bild (4)").put("status", "ok"))
            .put(JSONObject().put("id", "speicher").put("text", "Speicher frei (21 GB)").put("status", "ok"))
            .put(JSONObject().put("id", "planer").put("text", "Routenplaner verbunden").put("status", "fehlt").put("ziel", "extra_planer"))
            .put(JSONObject().put("id", "fernzugang").put("text", "Fernzugang").put("status", "ungeprueft"))))
        p == "/api/bruecke" && m == "GET" -> ok(JSONObject().put("ein", brueckeEin).put("p", "c2NoZWlu").put("a", "schein").put("zustimmungVersion", 1)
            .apply { if (brueckeEin) put("zustimmung", JSONObject().put("version", 1)) })
        p == "/api/bruecke" && m == "POST" -> { brueckeEin = true; ok(JSONObject().put("ok", true).put("ein", true)) }
        p == "/api/kopplung/code" -> ok(JSONObject().put("code", "123456").put("gueltigBis", System.currentTimeMillis() + 600_000).put("brueckeEinmal", "schein"))
        p == "/api/kopplungen" -> ok(JSONObject().put("geraete", JSONArray()))
        p == "/api/system/tailscale" -> ok(JSONObject())
        p == "/api/einstellungen" && m == "GET" -> ok(JSONObject().put("etag", "e-1").put("werte", JSONObject()
            .put("guardStop.socBelow", JSONObject().put("wert", 20)).put("guardStop.v12Below", JSONObject().put("wert", 12.2))
            .put("guardStop.maxParkHours", JSONObject().put("wert", 24)).put("update.mobilfunk", JSONObject().put("wert", true))))
        p == "/api/einstellungen" && m == "PUT" -> ok(JSONObject().put("ok", true))
        p == "/api/einstellungen/schema" -> ok(JSONObject().put("gruppen", JSONArray().put(JSONObject().put("id", "datenschutz").put("felder", JSONArray()
            .put(JSONObject().put("key", "planner.zustandSenden").put("typ", "bool").put("titel", "Zustand an den Routenplaner senden")
                .put("hilfe", "Was: Akku, Reichweite, Ladezustand – nie die Position. Wohin: dein Routenplaner. Wie lange: bis du es ausschaltest."))
            .put(JSONObject().put("key", "roadsense.teilen").put("typ", "bool").put("titel", "Straßenzustand teilen")
                .put("hilfe", "Was: Schlaglöcher mit Ort. Wofür: Warnungen für alle. Wohin: Community-Server, 30 Tage."))))))
        p == "/api/uebernahme/overdrive" && m == "GET" -> ok(JSONObject().put("vorhanden", true).put("vorschau_id", "abc").put("posten", JSONArray()
            .put(JSONObject().put("id", "aufbewahrung").put("titel", "Aufnahmen aufbewahren").put("aktion", "neu").put("overdrive", "72 h").put("panoptes", "nicht gesetzt"))
            .put(JSONObject().put("id", "mqtt").put("titel", "Smart-Home-Anbindung (MQTT-Adresse)").put("aktion", "ueberschreiben").put("overdrive", "192.168.0.30").put("panoptes", "192.168.0.10"))
            .put(JSONObject().put("id", "ausloeser").put("titel", "Wächter-Auslöser").put("aktion", "manuell").put("overdrive", "KI überall").put("panoptes", "–")
                .put("hinweis", "In Overdrive war „KI überall“ – bitte selbst wählen."))))
        p == "/api/uebernahme/overdrive" && m == "POST" -> ok(JSONObject().put("ok", true).put("uebernommen", b?.optJSONArray("posten") ?: JSONArray()))
        else -> null
    }
}
