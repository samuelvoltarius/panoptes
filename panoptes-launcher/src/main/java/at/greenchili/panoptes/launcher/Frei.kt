package at.greenchili.panoptes.launcher

import org.json.JSONObject

/**
 * 0.1.44 (E2 „freier Editor“, 12.11.: „ja beides freier editor einplanen“): reine Regeln für die frei gebaute
 * Startseite. Jede Seite hat ein Feinraster von 48 × 24 Einheiten (8 je Zelle); Elemente liegen auf diesen Einheiten,
 * überlappen sich nie und haben eine Mindestgröße (Lesbarkeit, Tippfläche). Einrasten (abschaltbar) rastet auf
 * Viertelzellen und an Kanten der Nachbarn (Hilfslinien). Je Element: Ecken, Deckung, Unschärfe-Stufe, Akzentfarbe.
 * Dieselben Regeln prüft der Kern ({@code LauncherLayoutPruefung}). Alles ohne Android, im Unit-Test belegt.
 */
object Frei {
    const val FEIN = 8
    const val BREITE = Raster.SPALTEN * FEIN   // 48
    const val HOEHE = Raster.ZEILEN * FEIN     // 24
    /** Einrasten: auf Viertelzellen (2 Einheiten); aus = jede Einheit (Achtelzelle). */
    const val RASTER_SCHRITT = 2
    /** Kanten innerhalb dieser Entfernung rasten an Nachbarkanten (Hilfslinie). */
    const val FANG = 2
    const val ECKE_MAX = 48
    const val DECKUNG_MIN = 30
    const val UNSCHAERFE_MAX = 3
    const val MAX_TEXT = Raster.MAX_NAME

    /** Mindestgröße (Breite × Höhe in Einheiten): Kachel 8 × 6, Dock-/Reiterleiste 8 × 4, App/Ordner 4 × 4, Text 4 × 2. */
    fun min(t: ElementTyp, k: Kachel? = null): Pair<Int, Int> = when {
        t == ElementTyp.WIDGET && (k == Kachel.DOCKLEISTE || k == Kachel.REITERLEISTE) -> 8 to 4
        t == ElementTyp.WIDGET -> 8 to 6
        t == ElementTyp.TEXT -> 4 to 2
        else -> 4 to 4
    }

    /** Rechteck [x, y, b, h] in Einheiten: die feine Lage, sonst die Zellen. */
    fun rect(e: Element): IntArray =
        if (e.fx != null && e.fy != null && e.fb != null && e.fh != null) intArrayOf(e.fx, e.fy, e.fb, e.fh)
        else intArrayOf(e.x * FEIN, e.y * FEIN, e.b * FEIN, e.h * FEIN)

    fun ueberlappt(a: IntArray, b: IntArray) = a[0] < b[0] + b[2] && b[0] < a[0] + a[2] && a[1] < b[1] + b[3] && b[1] < a[1] + a[3]

    fun imRand(r: IntArray) = r[2] >= 1 && r[3] >= 1 && r[0] >= 0 && r[1] >= 0 && r[0] + r[2] <= BREITE && r[1] + r[3] <= HOEHE

    fun passt(seite: List<Element>, r: IntArray, ohne: String? = null): Boolean = imRand(r) && seite.none { it.id != ohne && ueberlappt(rect(it), r) }

    /** Zellen, die das feine Rechteck umfassen (für ältere Leser und das Web-Raster). */
    fun zellen(r: IntArray): IntArray {
        val x = r[0] / FEIN; val y = r[1] / FEIN
        return intArrayOf(x, y, (r[0] + r[2] + FEIN - 1) / FEIN - x, (r[1] + r[3] + FEIN - 1) / FEIN - y)
    }

    fun rasten(v: Int, an: Boolean): Int = if (an) Math.round(v / RASTER_SCHRITT.toFloat()) * RASTER_SCHRITT else v

    /** Lage und Stil aus der Datei (gleiche Regeln wie der Kern); ungültige Lage = Element fällt weg, ungültiger Stil = Feld fällt weg. */
    fun lesen(o: JSONObject, e: Element, frei: Boolean): Element? {
        var neu = e
        if (frei) {
            val r = intArrayOf(o.optInt("fx", -1), o.optInt("fy", -1), o.optInt("fb", 0), o.optInt("fh", 0))
            val (mb, mh) = min(e.typ, e.kachel)
            if (!imRand(r) || r[2] < mb || r[3] < mh) return null
            val z = zellen(r)
            neu = neu.copy(x = z[0], y = z[1], b = z[2], h = z[3], fx = r[0], fy = r[1], fb = r[2], fh = r[3])
        }
        return neu.copy(
            ecke = o.optInt("ecke", -1).takeIf { o.has("ecke") && it in 0..ECKE_MAX },
            deckung = o.optInt("deckung", -1).takeIf { o.has("deckung") && it in DECKUNG_MIN..100 },
            unschaerfe = o.optInt("unschaerfe", -1).takeIf { o.has("unschaerfe") && it in 0..UNSCHAERFE_MAX },
            akzent = Raster.akzentGueltig(o.optString("akzent", "")))
    }

    /**
     * Element frei setzen (Lage und Größe in Einheiten). Größe wird auf die Mindestgröße und den Rand begrenzt;
     * überlappt es, bleibt das Layout unverändert.
     */
    fun setze(l: StartLayout, id: String, x: Int, y: Int, b: Int, h: Int): StartLayout {
        val (i, e) = Raster.finde(l, id) ?: return l
        val (mb, mh) = min(e.typ, e.kachel)
        val bb = b.coerceIn(mb, BREITE); val hh = h.coerceIn(mh, HOEHE)
        val r = intArrayOf(x.coerceIn(0, BREITE - bb), y.coerceIn(0, HOEHE - hh), bb, hh)
        if (!passt(l.seiten[i], r, ohne = id)) return l
        val z = zellen(r)
        val neu = e.copy(x = z[0], y = z[1], b = z[2], h = z[3], fx = r[0], fy = r[1], fb = r[2], fh = r[3])
        return l.copy(seiten = l.seiten.mapIndexed { j, s -> if (j == i) s.map { if (it.id == id) neu else it } else s })
    }

    /** Hilfslinien: Kanten und Mitten der Nachbarn, an denen das Rechteck ausgerichtet ist bzw. einrastet. */
    data class Linien(val senkrecht: List<Int>, val waagrecht: List<Int>)

    /**
     * Rastet die Lage (Größe bleibt) an Nachbarkanten ein, wenn eine Kante näher als [FANG] liegt, und liefert die
     * dabei getroffenen Linien. Ohne Einrasten nur die Linien, die genau passen.
     */
    fun hilfslinien(seite: List<Element>, r: IntArray, ohne: String?, einrasten: Boolean): Pair<IntArray, Linien> {
        val andere = seite.filter { it.id != ohne }.map { rect(it) }
        val xs = (andere.flatMap { listOf(it[0], it[0] + it[2]) } + listOf(0, BREITE)).distinct()
        val ys = (andere.flatMap { listOf(it[1], it[1] + it[3]) } + listOf(0, HOEHE)).distinct()
        fun fange(start: Int, groesse: Int, linien: List<Int>): Pair<Int, Int?> {
            var best: Pair<Int, Int?> = start to null
            var abstand = if (einrasten) FANG + 1 else 1
            for (li in linien) for (kante in listOf(start, start + groesse)) {
                val d = Math.abs(li - kante)
                if (d < abstand) { abstand = d; best = (start + (li - kante)) to li }
            }
            return best
        }
        val (x, lx) = fange(r[0], r[2], xs); val (y, ly) = fange(r[1], r[3], ys)
        return intArrayOf(x, y, r[2], r[3]) to Linien(listOfNotNull(lx), listOfNotNull(ly))
    }

    /** Stil eines Elements setzen; null-Werte = wie das Design. Ungültige Werte werden verworfen. */
    fun stil(l: StartLayout, id: String, ecke: Int?, deckung: Int?, unschaerfe: Int?, akzent: String?): StartLayout {
        val (i, e) = Raster.finde(l, id) ?: return l
        val neu = e.copy(ecke = ecke?.takeIf { it in 0..ECKE_MAX }, deckung = deckung?.takeIf { it in DECKUNG_MIN..100 },
            unschaerfe = unschaerfe?.takeIf { it in 0..UNSCHAERFE_MAX }, akzent = Raster.akzentGueltig(akzent))
        return l.copy(seiten = l.seiten.mapIndexed { j, s -> if (j == i) s.map { if (it.id == id) neu else it } else s })
    }

    /** Erster freier Platz für ein Rechteck b × h (Schritt eine Viertelzelle); null = Seite voll. */
    fun freierPlatz(seite: List<Element>, b: Int, h: Int): Pair<Int, Int>? {
        var y = 0
        while (y + h <= HOEHE) {
            var x = 0
            while (x + b <= BREITE) { if (passt(seite, intArrayOf(x, y, b, h))) return x to y; x += RASTER_SCHRITT }
            y += RASTER_SCHRITT
        }
        return null
    }

    /** Neues frei platziertes Element (Text, Dock-/Reiterleiste) an den ersten freien Platz; Seite voll = unverändert. */
    fun hinzufuegen(l: StartLayout, seite: Int, typ: ElementTyp, b: Int, h: Int, kachel: Kachel? = null, text: String? = null): StartLayout {
        if (seite !in l.seiten.indices) return l
        if (typ == ElementTyp.TEXT && Raster.name(text) == null) return l
        if (kachel != null && Raster.hatWidget(l, kachel)) return l
        val (x, y) = freierPlatz(l.seiten[seite], b, h) ?: return l
        val n = l.zaehler + 1
        val id = (if (typ == ElementTyp.TEXT) "t" else "w") + n
        val z = zellen(intArrayOf(x, y, b, h))
        val e = Element(id, typ, z[0], z[1], z[2], z[3], kachel = kachel, name = if (typ == ElementTyp.TEXT) Raster.name(text) else null,
            fx = x, fy = y, fb = b, fh = h)
        return l.copy(zaehler = n, seiten = l.seiten.mapIndexed { j, s -> if (j == seite) s + e else s })
    }

    /** Größe der festen Leisten, wenn man sie als Element auf eine Seite legt. */
    val LEISTE = 48 to 5
}

/** Rückgängig/Wiederholen für den Bearbeiten-Modus (nur im Speicher, höchstens [max] Schritte). */
class Verlauf(private val max: Int = 40) {
    private val zurueck = ArrayDeque<StartLayout>()
    private val vor = ArrayDeque<StartLayout>()

    /** Vor einer Änderung den alten Stand merken; eine neue Änderung löscht „Wiederholen“. */
    fun merke(alt: StartLayout) {
        if (zurueck.lastOrNull() == alt) return
        zurueck.addLast(alt); if (zurueck.size > max) zurueck.removeFirst()
        vor.clear()
    }

    fun kannZurueck() = zurueck.isNotEmpty()
    fun kannVor() = vor.isNotEmpty()

    fun zurueck(jetzt: StartLayout): StartLayout? = zurueck.removeLastOrNull()?.also { vor.addLast(jetzt) }
    fun vor(jetzt: StartLayout): StartLayout? = vor.removeLastOrNull()?.also { zurueck.addLast(jetzt) }
    fun leeren() { zurueck.clear(); vor.clear() }
}

/**
 * Lesbarkeit: Kontrast nach WCAG 2.x. Text braucht ≥ 4,5 : 1 gegen seine Karte, Akzent (Ringe, Ränder, Titel) ≥ 3 : 1.
 * Ist eine gewählte Deckung zu gering, wird sie angehoben; ein zu schwacher Akzent wird nicht übernommen.
 */
object Lesbarkeit {
    const val TEXT = 4.5
    const val AKZENT = 3.0

    private fun kanal(v: Int): Double { val c = v / 255.0; return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4) }
    fun luminanz(c: Int) = 0.2126 * kanal(Farbe.r(c)) + 0.7152 * kanal(Farbe.g(c)) + 0.0722 * kanal(Farbe.b(c))
    fun kontrast(a: Int, b: Int): Double { val la = luminanz(a); val lb = luminanz(b); return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05) }

    /** Kartenfarbe über dem Seitengrund: Kartengrund (wie die Glas-Karte) mit [deckung] % über [grund]. */
    fun karte(p: Palette, grund: Int, deckung: Int): Int {
        val basis = if (p.hell) Farbe.rgb(0xFFFFFF) else Farbe.rgb(0x0A1824)
        return Farbe.mix(Farbe.alpha(grund, 255), basis, deckung / 100f)
    }

    /** Kleinste Deckung ≥ [wunsch], bei der der Text auch über dem hellsten bzw. dunkelsten Grund lesbar bleibt. */
    fun deckung(p: Palette, wunsch: Int, gruende: List<Int>): Int {
        var d = wunsch.coerceIn(Frei.DECKUNG_MIN, 100)
        while (d < 100 && gruende.any { kontrast(p.onSurface, karte(p, it, d)) < TEXT }) d += 5
        return d.coerceAtMost(100)
    }

    fun akzentLesbar(p: Palette, akzent: String?, karte: Int): Boolean {
        val c = Farbe.hex(akzent) ?: return true
        return kontrast(c, karte) >= AKZENT
    }

    /** Ungünstigste Gründe einer Seite: hellster und dunkelster Ton des Seitenverlaufs (Szenen inbegriffen). */
    fun gruende(p: Palette): List<Int> = listOf(p.seiteVon, p.seiteBis, p.background,
        if (p.hell) Farbe.rgb(0xFFFFFF) else Farbe.heller(p.background, 0.35f))
}
