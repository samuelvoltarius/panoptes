package at.greenchili.panoptes.launcher

import org.json.JSONArray
import org.json.JSONObject

/**
 * Frei anpassbare Startseite: Seiten mit einem Raster aus Kacheln (Widgets, App-Verknüpfungen,
 * Ordner), ein Dock mit Favoriten und die Wahl des Hintergrunds. Reines Modell ohne Android,
 * alle Regeln im Unit-Test belegt. Gespeichert nur lokal im Launcher (nicht am Panoptes-Konto),
 * als Datei export- und importierbar.
 *
 * Jede Änderung liefert ein neues Layout; ist sie nicht möglich (kein Platz, ungültig), kommt
 * das unveränderte Layout zurück.
 */
enum class ElementTyp { WIDGET, APP, ORDNER }

data class Element(
    val id: String,
    val typ: ElementTyp,
    val x: Int,
    val y: Int,
    val b: Int,
    val h: Int,
    val kachel: Kachel? = null,
    val paket: String? = null,
    val name: String? = null,
    val apps: List<String> = emptyList()
)

data class StartLayout(
    val seiten: List<List<Element>>,
    val dock: List<String>,
    val hintergrund: String,
    val zaehler: Int,
    /** App-Verknüpfungen rechts in der Reiterzeile. */
    val schnell: List<String> = emptyList(),
    /** Ein-/ausblendbare Teile: Statusleiste, Reiterzeile, Klima-Anzeige im Dock, 3D-Fahrzeug. */
    val zeigeStatus: Boolean = true,
    val zeigeReiter: Boolean = true,
    val zeigeKlima: Boolean = true,
    val zeige3d: Boolean = true,
    /** Apps neben dem Launcher öffnen (geteilter Bildschirm), Standard aus. */
    val nebeneinander: Boolean = false,
    /** Apps, für die das Gegenteil von [nebeneinander] gilt (pro App überschreibbar). */
    val nebenAnders: List<String> = emptyList(),
    /** 0.1.40 (L2) eigenes Design: "auto" (wie Panoptes, ab Werk) | dunkel | hell | kontrast | nacht. */
    val design: String = "auto",
    /** Akzentfarbe "#RRGGBB"; null = Farbe des Designs. */
    val akzent: String? = null,
    /** Rasterdichte: Abstand zwischen den Kacheln (eng | normal | weit). */
    val abstand: String = "normal",
    /** Statusspalte im Reiter „Karte“: gewählte Teile in fester Reihenfolge ([Raster.SPALTE_TEILE]). */
    val spalte: List<String> = Raster.SPALTE_STANDARD
)

object Raster {
    const val SPALTEN = 6
    const val ZEILEN = 3
    const val MAX_SEITEN = 5
    const val MAX_DOCK = 8
    const val MAX_SCHNELL = 6
    const val MAX_ORDNER_APPS = 24
    const val MAX_NAME = 24
    const val MAX_NEBEN_ANDERS = 64
    const val VERSION = 1
    const val PANOPTES = "at.greenchili.surfmate"
    /** BYDs eigene Projektions-Apps (Paket-Inventur Surf): ab Werk im Dock, sichtbar nur wenn startbar. */
    const val ANDROID_AUTO = "com.byd.androidauto"
    const val CARPLAY = "com.byd.carplay.ui"
    val PROJEKTION = listOf(ANDROID_AUTO, CARPLAY)
    /** „eigen“ = Bild, das der Nutzer im Launcher gewählt hat (liegt nur auf dem Gerät, nie in der Datei). */
    val HINTERGRUENDE = listOf("panoptes", "cockpit", "bild", "video", "eigen")
    val DESIGNS = listOf("auto", "dunkel", "hell", "kontrast", "nacht")
    val ABSTAENDE = listOf("eng", "normal", "weit")
    val SPALTE_TEILE = listOf("akku", "reichweite", "gang", "waechter", "musik", "uhr", "kamera")
    val SPALTE_STANDARD = listOf("akku", "reichweite", "gang", "waechter", "musik", "kamera")
    /** Vorgeschlagene Akzentfarben (eigene Auswahl, Panoptes-Töne zuerst). */
    val AKZENTE = listOf("#5FD6E6", "#B5F23D", "#FFC266", "#FF8A6B", "#C792EA", "#4DA3FF", "#FFD400", "#FFFFFF")
    private val HEX = Regex("^#[0-9A-Fa-f]{6}$")
    /** Kachelgröße in drei Stufen; je Stufe die erlaubten Größen, die erste passende gewinnt. */
    val STUFEN = linkedMapOf("klein" to listOf(2 to 1, 1 to 1), "mittel" to listOf(2 to 2, 3 to 2, 2 to 3, 2 to 1),
        "gross" to listOf(3 to 3, 4 to 3, 4 to 2, 3 to 2))

    /** Größen für Widgets (Breite × Höhe in Zellen), in dieser Reihenfolge durchgeschaltet. */
    val GROESSEN = listOf(1 to 1, 2 to 1, 2 to 2, 2 to 3, 3 to 2, 3 to 3, 4 to 2, 4 to 3, 6 to 3)

    // ---------- Platz ----------

    fun passt(seite: List<Element>, x: Int, y: Int, b: Int, h: Int, ohne: String? = null): Boolean {
        if (b < 1 || h < 1 || x < 0 || y < 0 || x + b > SPALTEN || y + h > ZEILEN) return false
        return seite.none { it.id != ohne && x < it.x + it.b && it.x < x + b && y < it.y + it.h && it.y < y + h }
    }

    fun freierPlatz(seite: List<Element>, b: Int, h: Int): Pair<Int, Int>? {
        for (y in 0..ZEILEN - h) for (x in 0..SPALTEN - b) if (passt(seite, x, y, b, h)) return x to y
        return null
    }

    fun finde(l: StartLayout, id: String): Pair<Int, Element>? {
        l.seiten.forEachIndexed { i, s -> s.firstOrNull { it.id == id }?.let { return i to it } }
        return null
    }

    private fun mitSeite(l: StartLayout, i: Int, neu: List<Element>) =
        l.copy(seiten = l.seiten.mapIndexed { j, s -> if (j == i) neu else s })

    private fun ersetze(l: StartLayout, e: Element): StartLayout {
        val (i, _) = finde(l, e.id) ?: return l
        return mitSeite(l, i, l.seiten[i].map { if (it.id == e.id) e else it })
    }

    // ---------- Verschieben, Größe, Entfernen ----------

    fun verschieben(l: StartLayout, id: String, zielSeite: Int, x: Int, y: Int): StartLayout {
        val (quelle, e) = finde(l, id) ?: return l
        if (zielSeite !in l.seiten.indices) return l
        if (!passt(l.seiten[zielSeite], x, y, e.b, e.h, ohne = id)) return l
        val ohne = mitSeite(l, quelle, l.seiten[quelle].filter { it.id != id })
        return mitSeite(ohne, zielSeite, ohne.seiten[zielSeite] + e.copy(x = x, y = y))
    }

    /** Auf eine andere Seite an den ersten freien Platz. */
    fun aufSeite(l: StartLayout, id: String, zielSeite: Int): StartLayout {
        val (_, e) = finde(l, id) ?: return l
        if (zielSeite !in l.seiten.indices) return l
        val (x, y) = freierPlatz(l.seiten[zielSeite].filter { it.id != id }, e.b, e.h) ?: return l
        return verschieben(l, id, zielSeite, x, y)
    }

    /** Nächste passende Größe (nur Widgets). Schaltet durch und landet zuletzt wieder bei 1×1. */
    fun groesseWechseln(l: StartLayout, id: String): StartLayout {
        val (i, e) = finde(l, id) ?: return l
        if (e.typ != ElementTyp.WIDGET) return l
        val start = GROESSEN.indexOf(e.b to e.h)
        for (k in 1..GROESSEN.size) {
            val (b, h) = GROESSEN[(start + k).mod(GROESSEN.size)]
            if (passt(l.seiten[i], e.x, e.y, b, h, ohne = id)) return ersetze(l, e.copy(b = b, h = h))
        }
        return l
    }

    /** Stufe einer Kachel aus ihrer Fläche: bis 2 Zellen klein, bis 6 mittel, sonst groß. */
    fun stufeVon(e: Element): String = when { e.b * e.h <= 2 -> "klein"; e.b * e.h <= 6 -> "mittel"; else -> "gross" }

    /** 0.1.40: Kachel auf Stufe klein/mittel/groß; erst am alten Platz, sonst am ersten freien Platz der Seite. */
    fun kachelStufe(l: StartLayout, id: String, stufe: String): StartLayout {
        val (i, e) = finde(l, id) ?: return l
        if (e.typ != ElementTyp.WIDGET) return l
        val groessen = STUFEN[stufe] ?: return l
        for ((b, h) in groessen) if (passt(l.seiten[i], e.x, e.y, b, h, ohne = id)) return ersetze(l, e.copy(b = b, h = h))
        val rest = l.seiten[i].filter { it.id != id }
        for ((b, h) in groessen) {
            val p = freierPlatz(rest, b, h) ?: continue
            return ersetze(l, e.copy(x = p.first, y = p.second, b = b, h = h))
        }
        return l
    }

    // ---------- Design (0.1.40) ----------

    fun design(l: StartLayout, d: String): StartLayout = if (d in DESIGNS) l.copy(design = d) else l
    fun akzentGueltig(hex: String?): String? = hex?.takeIf { HEX.matches(it) }?.uppercase()
    fun akzent(l: StartLayout, hex: String?): StartLayout =
        if (hex == null) l.copy(akzent = null) else akzentGueltig(hex)?.let { l.copy(akzent = it) } ?: l
    fun abstand(l: StartLayout, a: String): StartLayout = if (a in ABSTAENDE) l.copy(abstand = a) else l
    fun abstandDp(a: String): Int = when (a) { "eng" -> 6; "weit" -> 24; else -> 14 }
    /** Nur bekannte Teile, in der festen Reihenfolge; „Start“ ist immer da und nicht Teil der Liste. */
    fun spalte(l: StartLayout, teile: Collection<String>): StartLayout = l.copy(spalte = SPALTE_TEILE.filter { it in teile })

    /** Wahl im Menü „Hintergrund“: Bild/Video nur mit Kern-Schalter, „eigen“ nur, wenn ein eigenes Bild da ist. */
    fun hintergrundWahl(bild: Boolean, video: Boolean, eigen: Boolean): List<String> =
        listOf("panoptes", "cockpit") + listOfNotNull(if (bild) "bild" else null, if (video) "video" else null, if (eigen) "eigen" else null)

    fun entfernen(l: StartLayout, id: String): StartLayout {
        val (i, _) = finde(l, id) ?: return l
        return mitSeite(l, i, l.seiten[i].filter { it.id != id })
    }

    // ---------- Hinzufügen ----------

    private fun neueId(l: StartLayout, vor: String): Pair<StartLayout, String> =
        l.copy(zaehler = l.zaehler + 1) to "$vor${l.zaehler + 1}"

    fun hatWidget(l: StartLayout, k: Kachel) = l.seiten.flatten().any { it.typ == ElementTyp.WIDGET && it.kachel == k }

    /** Jedes Widget höchstens einmal. Größe 2×1, sonst 1×1. */
    fun widgetHinzufuegen(l: StartLayout, seite: Int, k: Kachel): StartLayout {
        if (seite !in l.seiten.indices || hatWidget(l, k)) return l
        for ((b, h) in listOf(2 to 1, 1 to 1)) {
            val p = freierPlatz(l.seiten[seite], b, h) ?: continue
            val (l2, id) = neueId(l, "w")
            return mitSeite(l2, seite, l2.seiten[seite] + Element(id, ElementTyp.WIDGET, p.first, p.second, b, h, kachel = k))
        }
        return l
    }

    fun appHinzufuegen(l: StartLayout, seite: Int, paket: String): StartLayout {
        if (seite !in l.seiten.indices || !gueltigesPaket(paket)) return l
        val p = freierPlatz(l.seiten[seite], 1, 1) ?: return l
        val (l2, id) = neueId(l, "a")
        return mitSeite(l2, seite, l2.seiten[seite] + Element(id, ElementTyp.APP, p.first, p.second, 1, 1, paket = paket))
    }

    // ---------- Ordner ----------

    /** Macht aus einer App-Kachel einen Ordner an derselben Stelle, der diese App enthält. */
    fun neuerOrdner(l: StartLayout, appId: String, rohName: String): StartLayout {
        val name = name(rohName) ?: return l
        val (_, e) = finde(l, appId) ?: return l
        if (e.typ != ElementTyp.APP || e.paket == null) return l
        val (l2, id) = neueId(l, "o")
        val (i, _) = finde(l2, appId)!!
        return mitSeite(l2, i, l2.seiten[i].map {
            if (it.id == appId) Element(id, ElementTyp.ORDNER, e.x, e.y, 1, 1, name = name, apps = listOf(e.paket)) else it
        })
    }

    /** Legt die App-Kachel in einen bestehenden Ordner (die Kachel verschwindet). */
    fun inOrdner(l: StartLayout, appId: String, ordnerId: String): StartLayout {
        val (_, app) = finde(l, appId) ?: return l
        val (_, o) = finde(l, ordnerId) ?: return l
        if (app.typ != ElementTyp.APP || app.paket == null || o.typ != ElementTyp.ORDNER) return l
        if (o.apps.size >= MAX_ORDNER_APPS) return l
        val mit = ersetze(l, o.copy(apps = (o.apps - app.paket) + app.paket))
        return entfernen(mit, appId)
    }

    /** Nimmt eine App aus dem Ordner und legt sie auf dieselbe Seite. Kein Platz: bleibt im Ordner. */
    fun ausOrdner(l: StartLayout, ordnerId: String, paket: String): StartLayout {
        val (i, o) = finde(l, ordnerId) ?: return l
        if (o.typ != ElementTyp.ORDNER || paket !in o.apps) return l
        val rest = o.apps - paket
        var neu = if (rest.isEmpty()) entfernen(l, ordnerId) else ersetze(l, o.copy(apps = rest))
        val vorher = neu
        neu = appHinzufuegen(neu, i, paket)
        return if (neu === vorher) l else neu
    }

    fun umbenennen(l: StartLayout, ordnerId: String, rohName: String): StartLayout {
        val name = name(rohName) ?: return l
        val (_, o) = finde(l, ordnerId) ?: return l
        if (o.typ != ElementTyp.ORDNER) return l
        return ersetze(l, o.copy(name = name))
    }

    // ---------- Seiten ----------

    fun seiteHinzufuegen(l: StartLayout): StartLayout =
        if (l.seiten.size >= MAX_SEITEN) l else l.copy(seiten = l.seiten + listOf(emptyList()))

    /** Entfernt eine Seite samt Inhalt; mindestens eine bleibt. */
    fun seiteEntfernen(l: StartLayout, i: Int): StartLayout =
        if (l.seiten.size <= 1 || i !in l.seiten.indices) l else l.copy(seiten = l.seiten.filterIndexed { j, _ -> j != i })

    // ---------- Dock (der Rückweg zum BYD-Startbildschirm ist fest davor, nicht Teil der Liste) ----------

    fun dockHinzufuegen(l: StartLayout, paket: String): StartLayout =
        if (!gueltigesPaket(paket) || paket in l.dock || l.dock.size >= MAX_DOCK) l else l.copy(dock = l.dock + paket)

    fun dockEntfernen(l: StartLayout, paket: String): StartLayout = l.copy(dock = l.dock - paket)

    /** Öffnet diese App neben dem Launcher? Einstellung, pro App umkehrbar. */
    fun oeffnetNeben(l: StartLayout, paket: String): Boolean = l.nebeneinander != (paket in l.nebenAnders)

    /** Diese App umgekehrt zur Einstellung öffnen bzw. wieder wie alle. */
    fun nebenUmschalten(l: StartLayout, paket: String): StartLayout =
        if (!gueltigesPaket(paket)) l
        else if (paket in l.nebenAnders) l.copy(nebenAnders = l.nebenAnders - paket)
        else if (l.nebenAnders.size >= MAX_NEBEN_ANDERS) l
        else l.copy(nebenAnders = l.nebenAnders + paket)

    fun dockVerschieben(l: StartLayout, paket: String, schritt: Int): StartLayout {
        val i = l.dock.indexOf(paket)
        val j = i + schritt
        if (i < 0 || j !in l.dock.indices) return l
        val m = l.dock.toMutableList(); m.removeAt(i); m.add(j, paket)
        return l.copy(dock = m)
    }

    // ---------- Schnellleiste (Reiterzeile rechts) ----------

    fun schnellHinzufuegen(l: StartLayout, paket: String): StartLayout =
        if (!gueltigesPaket(paket) || paket in l.schnell || l.schnell.size >= MAX_SCHNELL) l else l.copy(schnell = l.schnell + paket)

    fun schnellEntfernen(l: StartLayout, paket: String): StartLayout = l.copy(schnell = l.schnell - paket)

    fun schnellVerschieben(l: StartLayout, paket: String, schritt: Int): StartLayout {
        val i = l.schnell.indexOf(paket)
        val j = i + schritt
        if (i < 0 || j !in l.schnell.indices) return l
        val m = l.schnell.toMutableList(); m.removeAt(i); m.add(j, paket)
        return l.copy(schnell = m)
    }

    fun hintergrund(l: StartLayout, wert: String): StartLayout = if (wert in HINTERGRUENDE) l.copy(hintergrund = wert) else l

    // ---------- Standard ----------

    /**
     * Standard-Layout: Seite 1 großes Fahrzeug-Feld links, Medien-Karte rechts; Seite 2 die
     * übrigen Kacheln in der Reihenfolge der Startansicht der Weboberfläche (anzeige.startAnsicht).
     */
    fun standard(startAnsicht: String?): StartLayout {
        fun w(id: String, k: Kachel, x: Int, y: Int, b: Int, h: Int) = Element(id, ElementTyp.WIDGET, x, y, b, h, kachel = k)
        val eins = listOf(w("w1", Kachel.FAHRZEUG, 0, 0, 4, 3), w("w2", Kachel.MUSIK, 4, 0, 2, 3))
        val zwei = when (startAnsicht) {
            "fahren" -> listOf(w("w3", Kachel.NAVI, 0, 0, 2, 2), w("w4", Kachel.AKKU, 2, 0, 2, 2), w("w5", Kachel.UHR, 4, 0, 2, 1), w("w6", Kachel.WAECHTER, 4, 1, 2, 1))
            "fahrzeug" -> listOf(w("w3", Kachel.AKKU, 0, 0, 2, 2), w("w4", Kachel.WAECHTER, 2, 0, 2, 2), w("w5", Kachel.NAVI, 4, 0, 2, 1), w("w6", Kachel.UHR, 4, 1, 2, 1))
            "kompakt" -> listOf(w("w3", Kachel.AKKU, 0, 0, 2, 2), w("w4", Kachel.UHR, 2, 0, 2, 1))
            else -> listOf(w("w3", Kachel.AKKU, 0, 0, 2, 2), w("w4", Kachel.NAVI, 2, 0, 2, 1), w("w5", Kachel.WAECHTER, 2, 1, 2, 1), w("w6", Kachel.UHR, 4, 0, 2, 1))
        }
        return StartLayout(listOf(eins, zwei), listOf(PANOPTES) + PROJEKTION, if (startAnsicht == "cockpit") "cockpit" else "panoptes", 10)
    }

    // ---------- Prüfen ----------

    fun name(roh: String?): String? {
        val n = roh.orEmpty().filter { !it.isISOControl() }.trim().take(MAX_NAME).trim()
        return n.ifEmpty { null }
    }

    /** Android-Paketname: Buchstaben, Ziffern, _ und Punkte, mindestens ein Punkt. */
    fun gueltigesPaket(p: String?): Boolean =
        p != null && p.length in 3..200 && p.contains('.') && p.matches(Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$"))

    // ---------- Datei (Export/Import und lokale Speicherung) ----------

    fun toJson(l: StartLayout): String {
        val seiten = JSONArray()
        l.seiten.forEach { s ->
            val a = JSONArray()
            s.forEach { e ->
                val o = JSONObject().put("id", e.id).put("typ", e.typ.name.lowercase())
                    .put("x", e.x).put("y", e.y).put("b", e.b).put("h", e.h)
                e.kachel?.let { o.put("kachel", it.name.lowercase()) }
                e.paket?.let { o.put("paket", it) }
                e.name?.let { o.put("name", it) }
                if (e.typ == ElementTyp.ORDNER) o.put("apps", JSONArray(e.apps))
                a.put(o)
            }
            seiten.put(a)
        }
        return JSONObject().put("format", "panoptes-launcher-layout").put("version", VERSION)
            .put("seiten", seiten).put("dock", JSONArray(l.dock)).put("hintergrund", l.hintergrund)
            .put("zaehler", l.zaehler).put("schnell", JSONArray(l.schnell))
            .put("zeigeStatus", l.zeigeStatus).put("zeigeReiter", l.zeigeReiter)
            .put("zeigeKlima", l.zeigeKlima).put("zeige3d", l.zeige3d)
            .put("nebeneinander", l.nebeneinander).put("nebenAnders", JSONArray(l.nebenAnders))
            // 0.1.40: Design (nur Farben/Layout, keine Geheimnisse, keine Orte; das eigene Bild bleibt auf dem Gerät)
            .put("design", l.design).put("akzent", l.akzent ?: "").put("abstand", l.abstand).put("spalte", JSONArray(l.spalte))
            .toString(2)
    }

    /**
     * Liest ein Layout und prüft alles: unbekannte Typen, ungültige Pakete, Überlappungen und
     * Ränder fallen weg. Ein falsches Format ergibt null (der Aufrufer behält das alte Layout).
     */
    fun fromJson(json: String?): StartLayout? = try {
        val root = JSONObject(json ?: "")
        if (root.optString("format") != "panoptes-launcher-layout") null
        else if (root.optInt("version", -1) !in 1..VERSION) null
        else {
            val ids = HashSet<String>()
            val seitenJson = root.optJSONArray("seiten") ?: JSONArray()
            val seiten = mutableListOf<List<Element>>()
            for (i in 0 until minOf(seitenJson.length(), MAX_SEITEN)) {
                val a = seitenJson.optJSONArray(i) ?: continue
                val s = mutableListOf<Element>()
                for (k in 0 until a.length()) {
                    val e = element(a.optJSONObject(k) ?: continue) ?: continue
                    if (!ids.add(e.id)) continue
                    if (e.typ == ElementTyp.WIDGET && s.any { it.kachel == e.kachel }) continue
                    if (seiten.flatten().any { it.typ == ElementTyp.WIDGET && it.kachel == e.kachel && e.typ == ElementTyp.WIDGET }) continue
                    if (passt(s, e.x, e.y, e.b, e.h)) s += e
                }
                seiten += s
            }
            if (seiten.isEmpty()) seiten += emptyList<Element>()
            val dockJson = root.optJSONArray("dock") ?: JSONArray()
            val dock = buildList {
                for (i in 0 until dockJson.length()) {
                    val p = dockJson.optString(i)
                    if (gueltigesPaket(p) && p !in this && size < MAX_DOCK) add(p)
                }
            }
            val hg = root.optString("hintergrund", "panoptes").takeIf { it in HINTERGRUENDE } ?: "panoptes"
            val maxId = ids.mapNotNull { it.drop(1).toIntOrNull() }.maxOrNull() ?: 0
            val schnellJson = root.optJSONArray("schnell") ?: JSONArray()
            val schnell = buildList {
                for (i in 0 until schnellJson.length()) {
                    val p = schnellJson.optString(i)
                    if (gueltigesPaket(p) && p !in this && size < MAX_SCHNELL) add(p)
                }
            }
            StartLayout(seiten, dock, hg, maxOf(root.optInt("zaehler", 0), maxId), schnell,
                root.optBoolean("zeigeStatus", true), root.optBoolean("zeigeReiter", true),
                root.optBoolean("zeigeKlima", true), root.optBoolean("zeige3d", true),
                root.optBoolean("nebeneinander", false), pakete(root.optJSONArray("nebenAnders"), MAX_NEBEN_ANDERS),
                design = root.optString("design", "auto").takeIf { it in DESIGNS } ?: "auto",
                akzent = akzentGueltig(root.optString("akzent", "")),
                abstand = root.optString("abstand", "normal").takeIf { it in ABSTAENDE } ?: "normal",
                spalte = root.optJSONArray("spalte")?.let { a -> SPALTE_TEILE.filter { t -> (0 until a.length()).any { a.optString(it) == t } } }
                    ?: SPALTE_STANDARD)
        }
    } catch (_: Exception) {
        null
    }

    private fun pakete(a: JSONArray?, max: Int): List<String> = buildList {
        for (i in 0 until (a?.length() ?: 0)) {
            val p = a!!.optString(i)
            if (gueltigesPaket(p) && p !in this && size < max) add(p)
        }
    }

    private fun element(o: JSONObject): Element? {
        val id = o.optString("id").takeIf { it.matches(Regex("^[a-z][0-9]{1,6}$")) } ?: return null
        val typ = when (o.optString("typ")) { "widget" -> ElementTyp.WIDGET; "app" -> ElementTyp.APP; "ordner" -> ElementTyp.ORDNER; else -> return null }
        val x = o.optInt("x", -1); val y = o.optInt("y", -1); val b = o.optInt("b", 1); val h = o.optInt("h", 1)
        return when (typ) {
            ElementTyp.WIDGET -> {
                val k = Kachel.values().firstOrNull { it.name.lowercase() == o.optString("kachel") } ?: return null
                if ((b to h) !in GROESSEN) return null
                Element(id, typ, x, y, b, h, kachel = k)
            }
            ElementTyp.APP -> {
                val p = o.optString("paket").takeIf { gueltigesPaket(it) } ?: return null
                Element(id, typ, x, y, 1, 1, paket = p)
            }
            ElementTyp.ORDNER -> {
                val a = o.optJSONArray("apps") ?: return null
                val apps = buildList {
                    for (i in 0 until a.length()) { val p = a.optString(i); if (gueltigesPaket(p) && p !in this && size < MAX_ORDNER_APPS) add(p) }
                }
                if (apps.isEmpty()) return null
                Element(id, typ, x, y, 1, 1, name = name(o.optString("name")) ?: "Ordner", apps = apps)
            }
        }
    }
}
