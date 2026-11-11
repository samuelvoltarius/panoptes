package at.greenchili.panoptes.launcher

import org.json.JSONObject

data class LauncherSummary(
    val vehicleName: String?,
    val rangeKm: Double?,
    val socPct: Double?,
    val acc: String?,
    val gear: String?,
    val charging: LauncherCharging? = null,
    val healthPct: Double? = null,
    val navi: LauncherNavi? = null,
    val waechter: LauncherWaechter? = null,
    val media: LauncherMedia? = null,
    val modelId: String? = null,
    val farbe: String? = null,
    val speedKmh: Double? = null,
    val reifen: LauncherReifen? = null,
    val luft: LauncherLuft? = null,
    val klima: LauncherKlima? = null,
    /** Läuft gerade etwas am Fahrerdisplay (dann zeigt der Launcher „Tacho wieder normal“)? */
    val tachoAktiv: Boolean = false,
    /** 0.1.38: Zeitstempel des Layouts im Kern; ändert er sich, holt der Launcher das Layout neu. */
    val layoutGeaendert: Long? = null,
    /** 0.1.38.1: Haftungshinweis „Verstanden, ich entscheide selbst“ in Panoptes bestätigt (fehlt = nein). */
    val fahrtHinweis: Boolean = false,
    /** 0.1.38.5: am Tacho gewählte Navi-App (oder null) und bekannte Navi-Pakete als Rückfall. */
    val naviGewaehlt: String? = null,
    val naviBekannt: List<String> = emptyList(),
    /** 0.1.38.8: Akku-Gesundheit laut Auto (BYD-Index) und Panoptes-Schätzung, je null = nicht vorhanden. */
    val sohAutoPct: Double? = null,
    val sohSchaetzungPct: Double? = null,
    /** 0.1.40: Türschloss laut Kern state.locked (true/false), null = unbekannt. Nur lesen. */
    val locked: Boolean? = null,
    /** 0.1.41 (LA): Nacht laut Sonnenstand am Auto-Standort (Kern env.isNight), null = kein Wert (dann Uhrzeit). */
    val istNacht: Boolean? = null
)

data class LauncherReifen(val vl: Double?, val vr: Double?, val hl: Double?, val hr: Double?, val unten: Double?, val oben: Double?,
                          /** 0.1.41 (LK): Kern-Warnung je Rad [VL, VR, HL, HR] (ReifenWarnung), null = keine/unbekannt. */
                          val warn: List<String?> = listOf(null, null, null, null))
data class LauncherLuft(val innen: Double?, val aussen: Double?)
data class LauncherKlima(
    val sollC: Double?, val stufe: Int?, val innenC: Double?, val an: Boolean? = null,
    /** 0.1.38.5: Auto stellt die Temperatur in festen Stufen (Surf); dann gilt [tempStufe] 1..17 statt [sollC]. */
    val stufenModell: Boolean = false,
    val tempStufe: Int? = null,
    /** 0.1.38.6 Komfort: Sitzheizung/-lüftung 0..2 (null = nicht lesbar, dann keine Kachel), Lenkrad und Umluft an/aus. */
    val sitzFahrer: Int? = null, val sitzBeifahrer: Int? = null,
    val lueftungFahrer: Int? = null, val lueftungBeifahrer: Int? = null,
    val lenkrad: Boolean? = null, val umluft: Boolean? = null,
    /** 0.1.38.6: "stufen", "grad" oder "unbekannt" (dann keine Temperatur-Bedienung); null = älterer Kern, wie bisher. */
    val tempArt: String? = null,
    /** 0.1.38.7: letzter Fern-Start laut Befehl (nie ein Messwert): läuft noch / beendet um (ms), Stufe 1..17 und Art nur auf Stufen-Modellen. */
    val fernAktiv: Boolean? = null, val fernGestartetMs: Long? = null, val fernBisMs: Long? = null,
    val fernBeendetUmMs: Long? = null, val fernStufe: Int? = null, val fernArt: String? = null
)

/** 0.1.38.5: Ausgang von „neben dem Launcher öffnen“. */
/** VOLLBILD (0.1.38.8): Kern konnte nicht teilen, ohne etwas anzufassen, und hat die App im Vollbild geöffnet. */
enum class NebenErgebnis { OK, VOLLBILD, NICHT_TEILBAR, FEHLER }

/** Antwort von `POST klima`: ok, Grund bei Ablehnung, neuer Klima-Stand (falls mitgeschickt). */
data class KlimaAntwort(val ok: Boolean, val grund: String?, val klima: LauncherKlima?)

/** Ein Paket der Update-Karte (Panoptes oder Launcher). */
data class UpdatePaket(
    val version: String,
    val versionCode: Int,
    val groesseBytes: Long?,
    val installierbar: Boolean,
    val mobilFrage: Boolean,
    val nichtWeil: String?,
    val phase: String?
)

/** `GET update`: was es Neues gibt; null je Paket = nichts Neueres. */
data class UpdateStand(val panoptes: UpdatePaket?, val launcher: UpdatePaket?)

data class LauncherCharging(val active: Boolean, val kw: Double?, val targetPct: Double?, val etaMin: Double?)
data class LauncherNavi(
    val aktiv: Boolean,
    val grund: String?,
    val naechsterStopp: String?,
    val naechsterKm: Double?,
    val istZiel: Boolean,
    val prognoseProzent: Double?
)
data class LauncherWaechter(val an: Boolean?, val scharf: Boolean?, val aufnahme: Boolean?, val zustand: String?)
data class LauncherMedia(val title: String?, val artist: String?)

data class LaunchableApp(val packageName: String, val label: String)
data class LauncherTraffic(val nearby: Int, val approaching: Int)
data class LauncherHistory(val lastTripKm: Double?, val lastChargeKwh: Double?,
                           /** 0.1.41 (LK): letzte Fahrt aus Kern-history: Dauer in min und Verbrauch in Wh/km, null = fehlt. */
                           val lastTripMin: Double? = null, val lastTripWhPerKm: Double? = null)
data class LauncherAppearance(
    val theme: String,
    val material: String,
    val startAnsicht: String = "panoptes",
    val hintergrundBild: Boolean = false,
    val hintergrundVideo: Boolean = false,
    val modus: String = "dark"
)

object LauncherContract {
    val START_ANSICHTEN = setOf("panoptes", "fahren", "fahrzeug", "kompakt", "cockpit")

    fun parseSummary(json: String): LauncherSummary {
        val root = JSONObject(json)
        val vehicle = root.optJSONObject("vehicle")
        val range = root.optJSONObject("range")
        val state = root.optJSONObject("state")
        val battery = root.optJSONObject("battery")
        val charging = root.optJSONObject("charging")
        val navi = root.optJSONObject("navi")
        val waechter = root.optJSONObject("waechter")
        val media = root.optJSONObject("media")
        return LauncherSummary(
            vehicle?.nullableString("name"),
            range?.nullableDouble("totalKm"),
            range?.nullableDouble("socPct") ?: battery?.nullableDouble("socPct"),
            state?.nullableString("acc"),
            state?.nullableString("gear"),
            charging?.let {
                LauncherCharging(it.optBoolean("active", false), it.nullableDouble("kw"), it.nullableDouble("targetPct"), it.nullableDouble("etaMin"))
            },
            battery?.nullableDouble("healthPct"),
            navi?.let {
                LauncherNavi(
                    it.optBoolean("aktiv", false),
                    it.nullableString("grund"),
                    it.nullableString("naechsterStopp"),
                    it.nullableDouble("naechsterKm"),
                    it.optBoolean("istZiel", false),
                    it.nullableDouble("prognoseProzent")
                )
            },
            waechter?.let {
                LauncherWaechter(it.nullableBoolean("an"), it.nullableBoolean("scharf"), it.nullableBoolean("aufnahme"), it.nullableString("zustand"))
            },
            media?.let { LauncherMedia(it.nullableString("title"), it.nullableString("artist")) },
            vehicle?.nullableString("modelId")?.takeIf { it.matches(Regex("^[a-z0-9_-]{1,40}$")) },
            vehicle?.nullableString("color")?.takeIf { it.matches(Regex("^#[0-9A-Fa-f]{6}$")) },
            state?.nullableDouble("speedKmh"),
            root.optJSONObject("tyres")?.let {
                LauncherReifen(it.nullableDouble("flBar"), it.nullableDouble("frBar"), it.nullableDouble("rlBar"),
                    it.nullableDouble("rrBar"), it.nullableDouble("loBar"), it.nullableDouble("hiBar"),
                    it.optJSONObject("warn")?.let { w -> listOf("fl", "fr", "rl", "rr").map { r -> w.nullableString(r)?.takeIf { x -> x in ZusatzKacheln.WARNUNGEN } } }
                        ?: listOf(null, null, null, null))
            },
            root.optJSONObject("air")?.let { LauncherLuft(it.nullableDouble("pm25Inside"), it.nullableDouble("pm25Outside")) }
                ?.takeIf { it.innen != null || it.aussen != null },
            root.optJSONObject("klima")?.let { klima(it) },
            root.optJSONObject("tacho")?.optBoolean("aktiv", false) == true,
            root.optJSONObject("launcherLayout")?.takeIf { it.has("geaendert") && !it.isNull("geaendert") }?.optLong("geaendert"),
            root.optJSONObject("tacho")?.optBoolean("fahrtHinweis", false) == true,
            root.optJSONObject("naviApp")?.nullableString("gewaehlt")?.takeIf { PAKET.matches(it) },
            root.optJSONObject("naviApp")?.optJSONArray("bekannt")?.let { a ->
                (0 until minOf(a.length(), 32)).mapNotNull { a.optString(it).takeIf { p -> PAKET.matches(p) } }
            } ?: emptyList(),
            battery?.nullableDouble("sohAutoPct")?.takeIf { it > 0 && it <= 110 },
            battery?.nullableDouble("sohSchaetzungPct")?.takeIf { it > 0 && it <= 110 },
            locked = state?.takeIf { it.has("locked") && !it.isNull("locked") }?.optBoolean("locked"),
            istNacht = root.optJSONObject("env")?.let { e -> if (e.has("isNight") && !e.isNull("isNight")) e.optBoolean("isNight") else null }
        )
    }

    /**
     * Wie die Akku-Gesundheit gezeigt wird: ZWEI = BYD-Wert und Schätzung nebeneinander (ein fehlender heißt
     * „nicht verfügbar“), EINZELWERT = alter `healthPct` nur als Rückfall, wenn der Kern die zwei Werte nicht liefert.
     */
    enum class Gesundheit { ZWEI, EINZELWERT, KEINE }

    fun gesundheitArt(s: LauncherSummary?): Gesundheit = when {
        s == null -> Gesundheit.KEINE
        s.sohAutoPct != null || s.sohSchaetzungPct != null -> Gesundheit.ZWEI
        s.healthPct != null -> Gesundheit.EINZELWERT
        else -> Gesundheit.KEINE
    }

    private val PAKET = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    private fun klima(it: JSONObject) =
        LauncherKlima(it.nullableDouble("sollC"), it.nullableDouble("stufe")?.toInt(), it.nullableDouble("innenC"), it.nullableBoolean("an"),
            it.optBoolean("stufenModell", false), it.nullableDouble("tempStufe")?.toInt()?.takeIf { s -> s in 1..17 },
            stufe02(it, "sitzFahrer"), stufe02(it, "sitzBeifahrer"), stufe02(it, "lueftungFahrer"), stufe02(it, "lueftungBeifahrer"),
            it.nullableBoolean("lenkrad"), it.nullableBoolean("umluft"),
            it.nullableString("tempArt")?.takeIf { a -> a == "stufen" || a == "grad" || a == "unbekannt" },
            it.optJSONObject("fern")?.optBoolean("aktiv"), it.optJSONObject("fern")?.optLong("gestartet")?.takeIf { v -> v > 0 },
            it.optJSONObject("fern")?.optLong("bis")?.takeIf { v -> v > 0 },
            it.optJSONObject("fern")?.takeIf { f -> !f.isNull("beendetUm") }?.optLong("beendetUm")?.takeIf { v -> v > 0 },
            it.optJSONObject("fern")?.takeIf { f -> !f.isNull("stufe") }?.optInt("stufe")?.takeIf { v -> v in 1..17 },
            it.optJSONObject("fern")?.takeIf { f -> !f.isNull("art") }?.optString("art")?.takeIf { a -> a == "heizen" || a == "kuehlen" || a == "neutral" })

    private fun stufe02(it: JSONObject, name: String): Int? = it.nullableDouble(name)?.toInt()?.takeIf { s -> s in 0..2 }

    /** Die sechs Aktionen des Kern-Klimawegs; nichts anderes wird je gesendet. */
    val KLIMA_AKTIONEN = setOf("an", "aus", "temp_hoch", "temp_runter", "luft_hoch", "luft_runter")

    /**
     * 0.1.38.6 Komfort: feste Namen, Sitze/Lenkrad/Umluft schaltet der Kern aus dem gelesenen Ist-Stand weiter, der Rest
     * kommt als an/aus-Paar. Nie Hupe, Licht, Blinker, Auf-/Zusperren, „Auto finden“, Alarm, Fenster, Dach, Kofferraum.
     */
    val KOMFORT_AKTIONEN = setOf(
        "sitz_fahrer", "sitz_beifahrer", "lueftung_fahrer", "lueftung_beifahrer", "lenkrad", "umluft",
        "auto_an", "auto_aus", "entfrosten_vorn_an", "entfrosten_vorn_aus",
        "entfrosten_hinten_an", "entfrosten_hinten_aus", "nur_geblaese_an", "nur_geblaese_aus"
    )

    fun klimaBody(aktion: String): String {
        require(aktion in KLIMA_AKTIONEN || aktion in KOMFORT_AKTIONEN) { "aktion" }
        return JSONObject().put("aktion", aktion).toString()
    }

    /** Antwort (auch 4xx-Körper); kaputt = abgelehnt ohne Grund. */
    fun parseKlima(json: String?): KlimaAntwort = try {
        val root = JSONObject(json ?: "")
        KlimaAntwort(root.optBoolean("ok", false), root.nullableString("grund"), root.optJSONObject("klima")?.let { klima(it) })
    } catch (_: Exception) {
        KlimaAntwort(false, null, null)
    }

    /**
     * Update-Karte: Panoptes nur, wenn `verfuegbar` gesetzt ist (der Kern meldet dort nur Neueres), der Launcher nur,
     * wenn `neuer` true ist und er schon installiert ist (sonst wäre es nicht dieser Launcher).
     */
    fun parseUpdate(json: String): UpdateStand {
        val root = JSONObject(json)
        val p = root.optJSONObject("panoptes")?.let { st ->
            st.optJSONObject("verfuegbar")?.let { v ->
                paket(v, st.optBoolean("installierbar", false), st.optBoolean("mobilFrage", false), st.nullableString("nichtWeil"),
                    st.nullableString("phase")?.takeIf { it != "pausiert" })
            }
        }
        val l = root.optJSONObject("launcher")?.let { st ->
            if (!st.optBoolean("neuer", false) || st.isNull("installiert")) null
            else st.optJSONObject("verfuegbar")?.let { v ->
                paket(v, st.optBoolean("installierbar", false), st.optBoolean("mobilFrage", false), st.nullableString("nichtWeil"),
                    st.nullableString("phase"))
            }
        }
        return UpdateStand(p, l)
    }

    private fun paket(v: JSONObject, installierbar: Boolean, mobilFrage: Boolean, nichtWeil: String?, phase: String?): UpdatePaket? {
        val version = v.nullableString("version")?.takeIf { it.matches(Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){1,3}$")) } ?: return null
        val vc = v.optInt("versionCode", -1).takeIf { it > 0 } ?: return null
        val groesse = v.nullableDouble("groesseBytes")?.toLong()?.takeIf { it > 0 }
        return UpdatePaket(version, vc, groesse, installierbar, mobilFrage, nichtWeil, phase)
    }

    fun installierenBody(paket: String, p: UpdatePaket, mobil: Boolean): String =
        JSONObject().put("paket", paket).put("version", p.version).put("versionCode", p.versionCode).put("mobil", mobil).toString()

    fun parseApps(json: String): List<LaunchableApp> {
        val root = JSONObject(json)
        if (!root.optBoolean("success", false)) return emptyList()
        val array = root.optJSONArray("apps") ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val pkg = item.optString("package").trim()
                if (pkg.isEmpty()) continue
                add(LaunchableApp(pkg, item.optString("label", pkg).ifBlank { pkg }))
            }
        }
    }

    fun parseTraffic(json: String): LauncherTraffic {
        val actors = JSONObject(json).optJSONArray("actors")
            ?: return LauncherTraffic(0, 0)
        var approaching = 0
        for (i in 0 until actors.length()) {
            if (actors.optJSONObject(i)?.optString("trend") == "approaching") approaching++
        }
        return LauncherTraffic(actors.length(), approaching)
    }

    fun parseHistory(json: String): LauncherHistory {
        val root = JSONObject(json)
        val trip = root.optJSONArray("trips")?.optJSONObject(0)
        val charge = root.optJSONArray("charging")?.optJSONObject(0)
        return LauncherHistory(
            trip?.nullableDouble("distanceKm"),
            charge?.nullableDouble("kwh"),
            trip?.nullableDouble("durationMin"),
            trip?.nullableDouble("whPerKm")
        )
    }

    fun parseAppearance(json: String): LauncherAppearance {
        val root = JSONObject(json)
        val theme = root.optString("theme", "auto")
            .takeIf { it in setOf("auto", "polar", "glacier", "ember", "signal") } ?: "auto"
        val material = root.optString("material", "crystal")
            .takeIf { it == "crystal" || it == "smoke" } ?: "crystal"
        val ansicht = root.optString("startAnsicht", "panoptes").takeIf { it in START_ANSICHTEN } ?: "panoptes"
        val hg = root.optJSONObject("hintergrund")
        // Fail-closed: nur ein echtes true schaltet Bild/Video frei.
        val bild = hg?.opt("bild") == true
        val video = hg?.opt("video") == true
        val modus = root.optString("modus", "dark").takeIf { it == "dark" || it == "light" || it == "auto" } ?: "dark"
        return LauncherAppearance(theme, material, ansicht, bild, video, modus)
    }

    fun launchBody(packageName: String, mode: String = "full"): String = JSONObject()
        .put("package", packageName)
        .put("mode", mode)
        .toString()

    fun parseNeben(json: String?): NebenErgebnis = try {
        val r = JSONObject(json ?: "")
        when {
            r.optBoolean("success", false) && r.optString("modus") == "vollbild" -> NebenErgebnis.VOLLBILD
            r.optBoolean("success", false) -> NebenErgebnis.OK
            r.optString("error") == "nicht_teilbar" -> NebenErgebnis.NICHT_TEILBAR
            else -> NebenErgebnis.FEHLER
        }
    } catch (_: Exception) {
        NebenErgebnis.FEHLER
    }

    /** `{pakete:{paket:"ja"|"nein"|"unbekannt"}}`; unbekannte Werte fallen weg. */
    fun parseTeilbar(json: String): Map<String, String> {
        val o = JSONObject(json).optJSONObject("pakete") ?: return emptyMap()
        return o.keys().asSequence().mapNotNull { k -> o.optString(k).takeIf { it == "ja" || it == "nein" || it == "unbekannt" }?.let { k to it } }.toMap()
    }

    fun launchSucceeded(json: String): Boolean {
        val root = JSONObject(json)
        return root.optBoolean("success", root.optBoolean("ok", false))
    }

    private fun JSONObject.nullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun JSONObject.nullableDouble(key: String): Double? =
        if (!has(key) || isNull(key)) null else optDouble(key).takeUnless { it.isNaN() }

    private fun JSONObject.nullableBoolean(key: String): Boolean? =
        if (!has(key) || isNull(key)) null else (opt(key) as? Boolean)
}
