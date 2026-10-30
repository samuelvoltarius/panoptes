package at.greenchili.surfmate.onboarding.schritte

import org.json.JSONArray
import org.json.JSONObject

/**
 * Einrichtungsassistent 0.1.10 – reine Logik ohne Android (JVM-testbar).
 *
 * Quelle der Wahrheit ist der Kern-Vertrag §0 (WIZARD_AUFTRAG_KERN.md):
 *   GET /api/einrichtung → { version, abgeschlossen, aktuell, schritte:[{id,pflicht,status,grund}],
 *                            offen_spaeter:[id], faehigkeiten:{…}, selbsttest:null|{…} }
 * Die Oberfläche entscheidet nichts selbst über Status oder Speichern, sie liest nur und schickt Antworten.
 *
 * Vorlauf: Vor der USB-Freigabe läuft der Daemon nicht, §0 ist dann nicht erreichbar. Für diese Zeit gibt es
 * eine feste Schrittliste [VORLAUF]; deren Antworten (Sprache, Haftung) werden nachgereicht, sobald §0 antwortet.
 */
object Einrichtung {

    /** Feste Schritt-IDs laut §0 (Reihenfolge = Anzeige-Reihenfolge, falls der Kern keine Liste liefert). */
    val ALLE = listOf(
        "sprache", "haftung", "autostart", "ueberlagerung", "rechte", "usb_freigabe", "auto", "kameras",
        "waechter", "betrieb", "karte", "daten", "blitzer", "updates", "strompreis",
        "extra_telegram", "extra_handy", "extra_planer", "extra_cockpit", "extra_overdrive", "extra_gewohnheiten",
        // 0.1.24: „Für Fortgeschrittene“ (Tailscale) ans Ende
        "extra_fernzugang", "extra_notzugang", "fertig",
    )

    /** Schritte, die ohne laufenden Daemon gezeigt werden (Neuinstallation vor der USB-Freigabe). */
    val VORLAUF = listOf("sprache", "haftung", "autostart", "ueberlagerung", "rechte", "usb_freigabe")

    /** Pflichtschritte, 0.1.32 Schnellstart (gilt, falls der Kern „pflicht“ nicht mitliefert). */
    val PFLICHT = setOf("sprache", "haftung", "usb_freigabe", "waechter", "updates")

    /** Teil des Bildschirms „Panoptes erlauben“ ([FreigabenSchritt], Schritt-ID autostart), kein eigener Bildschirm im Ablauf. */
    val IM_SAMMELSCHRITT = setOf("ueberlagerung", "rechte")

    /** Schritte des Schnellstarts nach dem Vorlauf (waechter, updates, extra_handy): nur für „Schritt n von m“ ohne Daemon. */
    const val NACH_VORLAUF = 3

    const val ERLEDIGT = "erledigt"
    const val OFFEN = "offen"
    const val SPAETER = "spaeter"
    const val ENTFAELLT = "entfaellt"
}

data class Schritt(val id: String, val pflicht: Boolean, val status: String, val grund: String?, val schnellstart: Boolean = true) {
    val sichtbar: Boolean get() = status != Einrichtung.ENTFAELLT
    /** Kommt im geführten Ablauf dran (der Rest steht in „Noch einrichten“). */
    val imAblauf: Boolean get() = sichtbar && schnellstart && id !in Einrichtung.IM_SAMMELSCHRITT
    val zaehlt: Boolean get() = sichtbar && id != "fertig"
}

data class EinrichtungStand(
    val version: Int,
    val abgeschlossen: Boolean,
    val aktuell: String?,
    val schritte: List<Schritt>,
    val offenSpaeter: List<String>,
    val faehigkeiten: JSONObject?,
    val selbsttest: JSONObject?,
    /** true = aus §0 gelesen, false = Vorlauf ohne Daemon. */
    val vomKern: Boolean,
) {
    fun schritt(id: String): Schritt? = schritte.firstOrNull { it.id == id }

    /** Schritte des Ablaufs in Anzeige-Reihenfolge (entfallene und freiwillige ausgelassen). */
    val sichtbar: List<Schritt> get() = schritte.filter { it.imAblauf }

    /**
     * „Schritt n von m“: m = sichtbare Schritte ohne „fertig“, n = Position des Schritts darin (1-basiert).
     * Für „fertig“ gibt es keine Nummer (null). Übersprungene/spätere Schritte zählen mit, aber nicht als Fehler.
     */
    fun fortschritt(id: String): Pair<Int, Int>? {
        val liste = sichtbar.filter { it.zaehlt }
        val i = liste.indexOfFirst { it.id == id }
        return if (i < 0) null else Pair(i + 1, liste.size + if (vomKern) 0 else Einrichtung.NACH_VORLAUF)
    }

    /** Erster Schritt, an dem der Assistent beginnen bzw. fortsetzen soll. */
    fun startSchritt(): String {
        aktuell?.let { a ->
            if (a in Einrichtung.IM_SAMMELSCHRITT && schritt("autostart")?.imAblauf == true) return "autostart"
            if (schritt(a)?.sichtbar == true) return a
        }
        return sichtbar.firstOrNull { it.status == Einrichtung.OFFEN }?.id ?: "fertig"
    }

    fun naechster(id: String): String? {
        val l = sichtbar; val i = l.indexOfFirst { it.id == id }
        return if (i < 0 || i + 1 >= l.size) null else l[i + 1].id
    }

    fun vorheriger(id: String): String? {
        val l = sichtbar; val i = l.indexOfFirst { it.id == id }
        return if (i <= 0) null else l[i - 1].id
    }

    /**
     * 0.1.26: Steht „rechte“ noch auf offen/später, meldet die App beim Start den echten Zustand nach
     * (nur wenn alle Rechte erteilt sind; nie umgekehrt). Den Daemon fragt das niemand sonst.
     */
    fun rechteNachmelden(): Boolean = schritt("rechte")?.status.let { it == Einrichtung.OFFEN || it == Einrichtung.SPAETER }

    /** „Später“ ist nur bei Nicht-Pflichtschritten erlaubt (Kern antwortet sonst 422). */
    fun spaeterErlaubt(id: String): Boolean = schritt(id)?.pflicht == false

    /** Für die Liste „Noch einrichten“: offen_spaeter + offene/spätere Schritte (ohne „fertig“). */
    fun nochOffen(): List<String> {
        val ids = LinkedHashSet<String>(offenSpaeter)
        schritte.filter { it.id != "fertig" && it.sichtbar && (it.status == Einrichtung.OFFEN || it.status == Einrichtung.SPAETER) }
            .forEach { ids.add(it.id) }
        return ids.toList()
    }

    companion object {
        fun ausJson(o: JSONObject): EinrichtungStand {
            val arr: JSONArray = o.optJSONArray("schritte") ?: JSONArray()
            val schritte = ArrayList<Schritt>()
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val id = s.optString("id", "")
                if (id.isEmpty()) continue
                schritte.add(Schritt(
                    id = id,
                    pflicht = if (s.has("pflicht")) s.optBoolean("pflicht") else id in Einrichtung.PFLICHT,
                    status = s.optString("status", Einrichtung.OFFEN),
                    grund = if (s.isNull("grund")) null else s.optString("grund", "").ifEmpty { null },
                    schnellstart = s.optBoolean("schnellstart", true),
                ))
            }
            val spaeter = ArrayList<String>()
            o.optJSONArray("offen_spaeter")?.let { a -> for (i in 0 until a.length()) a.optString(i, "").takeIf { it.isNotEmpty() }?.let(spaeter::add) }
            return EinrichtungStand(
                version = o.optInt("version", 1),
                abgeschlossen = o.optBoolean("abgeschlossen", false),
                aktuell = if (o.isNull("aktuell")) null else o.optString("aktuell", "").ifEmpty { null },
                schritte = schritte,
                offenSpaeter = spaeter,
                faehigkeiten = o.optJSONObject("faehigkeiten"),
                selbsttest = o.optJSONObject("selbsttest"),
                vomKern = true,
            )
        }

        /** Vorlauf ohne Daemon: feste Liste, erledigt ist, was die App lokal schon weiß. */
        fun vorlauf(erledigt: Set<String>): EinrichtungStand = EinrichtungStand(
            version = 1, abgeschlossen = false, aktuell = null,
            schritte = Einrichtung.VORLAUF.map {
                Schritt(it, it in Einrichtung.PFLICHT, if (it in erledigt) Einrichtung.ERLEDIGT else Einrichtung.OFFEN, null)
            },
            offenSpaeter = emptyList(), faehigkeiten = null, selbsttest = null, vomKern = false,
        )
    }
}

/** Inhalt des Kopplungs-QR, gleich wie {@code qrV2} in system.html (QR v2, HANDY_API_VERTRAG §7). */
object HandyQr {
    private const val STANDARD_BASIS = "https://byd.serveone.at/p/"

    /** @param basis Direktadresse (Tailscale) oder null; @param bruecke Antwort von GET /api/bruecke oder null */
    fun inhalt(code: String, basis: String?, bruecke: JSONObject?, einmal: String?): String {
        val j = JSONObject().put("v", 2).put("c", code)
        if (!basis.isNullOrEmpty()) j.put("u", JSONArray().put(basis))
        val p = bruecke?.optString("p", "").orEmpty()
        val adr = bruecke?.optString("a", "").orEmpty()
        if (bruecke?.optBoolean("ein", false) == true && p.isNotEmpty() && adr.isNotEmpty()) {
            val b = JSONObject().put("p", p).put("a", adr)
            if (!einmal.isNullOrEmpty()) b.put("z", einmal)
            j.put("b", b)
        }
        val b64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(j.toString().toByteArray(Charsets.UTF_8))
        return bruecke?.optString("qrBasis", "").orEmpty().ifEmpty { STANDARD_BASIS } + "#k=" + b64
    }

    /** Zustimmung zur Brücke in der aktuellen Fassung erteilt? (Felder wie GET /api/bruecke) */
    fun zugestimmt(bruecke: JSONObject?): Boolean {
        val z = bruecke?.optJSONObject("zustimmung") ?: return false
        return z.opt("version") != null && z.opt("version").toString() == bruecke.opt("zustimmungVersion")?.toString()
    }

    /** Ids der gekoppelten Handys aus GET /api/kopplungen. */
    fun handyIds(kopplungen: JSONObject?): Set<String> {
        val g = kopplungen?.optJSONArray("geraete") ?: return emptySet()
        return (0 until g.length()).mapNotNull { g.optJSONObject(it) }.filter { it.optString("art") == "handy" }
            .map { it.optString("id") }.toSet()
    }
}

/**
 * Prüft, ob eine Antwort für einen Pflichtschritt vollständig ist, BEVOR sie an den Kern geht
 * (der Kern prüft selbst noch einmal und antwortet sonst 422 + „fehlt“). Keine Vorauswahl: fehlende Felder = unvollständig.
 */
object Antworten {
    val WAECHTER = setOf("shock", "ai_everywhere", "ai_private", "aus")
    val BETRIEB = setOf("immer", "fahrt")
    /** 0.1.28 P10: „Wächter stoppt …“ (Werte im Kern: EinrichtungApiHandler.STOPP). */
    val STOPP = setOf("frueh", "normal", "lang")
    val KARTE = setOf("community", "osm", "keine")

    /** null = vollständig, sonst die Namen der fehlenden Felder (für die Anzeige „Bitte … wählen“). */
    fun fehlt(id: String, a: JSONObject?): List<String>? {
        if (a == null) return listOf("antwort")
        val f = ArrayList<String>()
        when (id) {
            "sprache" -> if (a.optString("locale", "").isEmpty()) f.add("locale")
            "haftung" -> if (!a.optBoolean("gelesen", false)) f.add("gelesen")
            "waechter" -> {
                if (a.optString("modus", "") !in WAECHTER) f.add("modus")
                if (!a.has("ton") || a.isNull("ton")) f.add("ton")
                // 0.1.28 P10: Ereignis-Clip ja/nein, ohne Vorauswahl
                if (!a.has("ereignisClip") || a.isNull("ereignisClip")) f.add("ereignisClip")
            }
            "betrieb" -> {
                if (a.optString("modus", "") !in BETRIEB) f.add("modus")
                else if (a.optString("modus") == "immer" && a.optString("stopp", "") !in STOPP) f.add("stopp")
            }
            "karte" -> if (a.optString("quelle", "") !in KARTE) f.add("quelle")
            "updates" -> if (!a.has("pruefen") || a.isNull("pruefen")) f.add("pruefen")
        }
        return if (f.isEmpty()) null else f
    }
}
