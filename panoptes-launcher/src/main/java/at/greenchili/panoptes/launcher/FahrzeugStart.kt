package at.greenchili.panoptes.launcher

import java.util.Locale

/**
 * 0.1.44 (D1 „Fahrzeug-Startseite“, Projektinhaber 12.11.: „das wollen die user“): reine Regeln und die Geometrie der eigenen
 * Fahrzeug-Ansicht. Nur die Funktionsidee ist übernommen; Aufteilung, Formen und Farben sind eigene Arbeit im
 * Panoptes-Stil (keine fremden Bilder, Autos, Szenen oder Namen, kein BYD-Bild).
 *
 * Alle Werte kommen aus dem Kern-Summary; fehlt einer, steht „—“ bzw. wird der Teil neutral gezeichnet – nie geraten.
 * Die Ansicht ([AutoAnsicht]) und die Kacheln ([FahrzeugKacheln]) zeichnen nur, was hier berechnet wird.
 */
object FahrzeugStart {
    enum class Form { KOMPAKT, SUV, LIMOUSINE }

    const val PREFS = "launcher"
    const val KEY_FORM = "fahrzeug_form"
    const val AUTO = "auto"
    /** Wahl in den Einstellungen; „auto“ = aus dem Modell. */
    val FORMEN = listOf(AUTO, "kompakt", "suv", "limousine")

    val GAENGE = listOf("P", "R", "N", "D")
    /** Fenster in der Reihenfolge der Knöpfe; „dach“ nur, wenn der Kern ein Dach meldet. */
    val SEITENFENSTER = listOf("vl", "vr", "hl", "hr")
    const val DACH = "dach"
    /** Ab hier gilt ein Fenster als offen (der Kern liefert 0..100 %). */
    const val OFFEN_AB = 3
    /** Vollausschlag des Tempo-Rings. */
    const val RING_MAX_KMH = 180.0

    /** Grundform aus dem Kern-Modell: Tabelle aller bekannten Modelle ([FahrzeugModelle.form]). */
    fun formAusModell(modelId: String?): Form = FahrzeugModelle.form(modelId)

    /** Eigene Wahl zuerst, sonst aus dem Modell. */
    fun form(wahl: String?, modelId: String?): Form = when (wahl) {
        "kompakt" -> Form.KOMPAKT
        "suv" -> Form.SUV
        "limousine" -> Form.LIMOUSINE
        else -> formAusModell(modelId)
    }

    fun formWahl(ctx: android.content.Context): String =
        ctx.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getString(KEY_FORM, AUTO)?.takeIf { it in FORMEN } ?: AUTO

    fun formSetzen(ctx: android.content.Context, w: String) {
        if (w in FORMEN) ctx.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putString(KEY_FORM, w).apply()
    }

    /** Index des hervorgehobenen Gangs (P, R, N, D) oder -1, wenn unbekannt. */
    fun gangIndex(gear: String?): Int = GAENGE.indexOf(gear)

    /** Anteil des Tempo-Rings 0..1; null = kein Messwert (Ring leer, Zahl „—“). */
    fun ringAnteil(kmh: Double?): Float? =
        kmh?.takeIf { it >= 0 && it < 400 }?.let { (it / RING_MAX_KMH).coerceIn(0.0, 1.0).toFloat() }

    /** Akku-Balken 0..1; null = unbekannt. */
    fun akkuAnteil(soc: Double?): Float? = soc?.takeIf { it in 0.0..100.0 }?.let { (it / 100.0).toFloat() }

    /** Reifendruck „2,4“ (eine Nachkommastelle, Sprache des Geräts) oder „—“. */
    fun druck(bar: Double?, l: Locale = Locale.getDefault()): String =
        bar?.takeIf { it > 0 && it < 10 }?.let { String.format(l, "%.1f", it) } ?: ZusatzKacheln.STRICH

    /** Welche Fenster-Knöpfe gezeigt werden: nur Fenster mit bekanntem Stand, Dach nur, wenn vorhanden. */
    fun knoepfe(k: LauncherKoerper?): List<String> {
        if (k == null) return emptyList()
        val seiten = SEITENFENSTER.filter { k.fenster[it] != null }
        return if (k.dach && k.fenster[DACH] != null) seiten + DACH else seiten
    }

    fun offen(pct: Int?): Boolean = pct != null && pct >= OFFEN_AB

    /** Vorgeschlagenes Ziel im Bestätigungsfeld: offenes Fenster → zu, sonst auf. */
    fun vorschlag(pct: Int?): String = if (offen(pct)) "zu" else "auf"

    /** Fenster bedienen nur im Stand – dieselbe Regel wie das Bedienen im Launcher (der Kern prüft noch einmal). */
    fun darfFenster(gear: String?, acc: String?, speedKmh: Double?): Boolean =
        Fahrsicherheit.bearbeitenErlaubt(gear, acc, speedKmh)

    // ---------- Eigene Fahrzeug-Ansicht: Seitenprofil (Bug rechts, linke Fahrzeugseite zum Betrachter) ----------

    /** Ein Profil in Einheiten der Fahrzeuglänge: x 0 (Heck) … 1 (Bug), y Höhe über dem Boden. */
    class Profil(
        /** Stützpunkte der Silhouette (x, y), geschlossen, werden geglättet. */
        val umriss: List<Float>,
        /** Seitenfenster-Fläche (x, y), ungeglättet. */
        val fenster: List<Float>,
        /** B-Säule: trennt vorderes und hinteres Seitenfenster bzw. die Türen. */
        val bSaeule: Float,
        /** Türen: hintere von [tuerHinten] bis B-Säule, vordere von B-Säule bis [tuerVorn]. */
        val tuerHinten: Float, val tuerVorn: Float,
        val radHinten: Float, val radVorn: Float, val radR: Float,
        /** Ladeklappe (x, y) auf der hinteren Flanke. */
        val ladeX: Float, val ladeY: Float,
        /** Haube von [haubeVon] bis Bug, Heckklappe bis [heckBis]. */
        val haubeVon: Float, val heckBis: Float,
        /** Dachlinie: Glasdach von … bis. */
        val dachVon: Float, val dachBis: Float
    )

    fun profil(f: Form): Profil = when (f) {
        Form.KOMPAKT -> Profil(
            listOf(0.00f, 0.11f, 0.005f, 0.21f, 0.03f, 0.29f, 0.08f, 0.345f, 0.17f, 0.375f, 0.33f, 0.385f, 0.50f, 0.378f,
                0.62f, 0.315f, 0.74f, 0.262f, 0.88f, 0.240f, 0.965f, 0.215f, 0.995f, 0.17f, 1.0f, 0.10f, 0.97f, 0.065f, 0.03f, 0.065f),
            listOf(0.115f, 0.272f, 0.175f, 0.352f, 0.49f, 0.358f, 0.63f, 0.282f, 0.605f, 0.262f, 0.12f, 0.262f),
            0.38f, 0.14f, 0.64f, 0.175f, 0.815f, 0.078f, 0.085f, 0.265f, 0.70f, 0.07f, 0.24f, 0.46f)
        Form.SUV -> Profil(
            listOf(0.00f, 0.13f, 0.003f, 0.25f, 0.02f, 0.32f, 0.06f, 0.375f, 0.15f, 0.398f, 0.34f, 0.405f, 0.54f, 0.398f,
                0.66f, 0.330f, 0.78f, 0.290f, 0.90f, 0.272f, 0.975f, 0.250f, 1.0f, 0.20f, 1.0f, 0.12f, 0.97f, 0.085f, 0.03f, 0.085f),
            listOf(0.085f, 0.300f, 0.13f, 0.372f, 0.53f, 0.376f, 0.67f, 0.296f, 0.645f, 0.278f, 0.09f, 0.278f),
            0.40f, 0.13f, 0.67f, 0.18f, 0.81f, 0.088f, 0.075f, 0.285f, 0.73f, 0.06f, 0.22f, 0.50f)
        Form.LIMOUSINE -> Profil(
            listOf(0.00f, 0.09f, 0.005f, 0.165f, 0.03f, 0.215f, 0.10f, 0.236f, 0.21f, 0.275f, 0.36f, 0.303f, 0.50f, 0.300f,
                0.62f, 0.262f, 0.73f, 0.212f, 0.87f, 0.190f, 0.965f, 0.172f, 0.995f, 0.135f, 1.0f, 0.085f, 0.97f, 0.055f, 0.03f, 0.055f),
            listOf(0.17f, 0.214f, 0.27f, 0.282f, 0.50f, 0.284f, 0.625f, 0.224f, 0.60f, 0.205f, 0.17f, 0.205f),
            0.42f, 0.19f, 0.63f, 0.19f, 0.80f, 0.072f, 0.10f, 0.205f, 0.70f, 0.11f, 0.30f, 0.48f)
    }

    /** Geschlossene Catmull-Rom-Glättung einer Punktliste (x, y …); [schritte] Teilpunkte je Abschnitt. */
    fun glatt(p: List<Float>, schritte: Int = 8): List<Float> {
        val n = p.size / 2
        if (n < 3) return p
        val o = ArrayList<Float>(n * schritte * 2)
        fun x(i: Int) = p[((i % n + n) % n) * 2]
        fun y(i: Int) = p[((i % n + n) % n) * 2 + 1]
        for (i in 0 until n) for (k in 0 until schritte) {
            val t = k / schritte.toFloat(); val t2 = t * t; val t3 = t2 * t
            fun cr(a: Float, b: Float, c: Float, d: Float) = 0.5f * (2 * b + (-a + c) * t + (2 * a - 5 * b + 4 * c - d) * t2 + (-a + 3 * b - 3 * c + d) * t3)
            o += cr(x(i - 1), x(i), x(i + 1), x(i + 2)); o += cr(y(i - 1), y(i), y(i + 1), y(i + 2))
        }
        return o
    }

    /** Vieleck an einer senkrechten (x = [c]) bzw. waagrechten (y = [c]) Linie beschneiden; [behalteKleiner] = Seite. */
    fun schneide(p: List<Float>, c: Float, senkrecht: Boolean, behalteKleiner: Boolean): List<Float> {
        val n = p.size / 2
        val o = ArrayList<Float>()
        fun w(i: Int) = if (senkrecht) p[(i % n) * 2] else p[(i % n) * 2 + 1]
        fun drin(i: Int) = if (behalteKleiner) w(i) <= c else w(i) >= c
        for (i in 0 until n) {
            val j = i + 1
            val ax = p[i * 2]; val ay = p[i * 2 + 1]; val bx = p[(j % n) * 2]; val by = p[(j % n) * 2 + 1]
            if (drin(i)) { o += ax; o += ay }
            if (drin(i) != drin(j)) {
                val t = (c - w(i)) / (w(j) - w(i))
                o += ax + (bx - ax) * t; o += ay + (by - ay) * t
            }
        }
        return o
    }

    /** Zustand der Ansicht, nur echte Kernwerte. */
    data class Zustand(
        val tuerOffen: Map<String, Boolean> = emptyMap(),
        val fenster: Map<String, Int> = emptyMap(),
        val dach: Boolean = false,
        val verriegelt: Boolean? = null,
        val laedt: Boolean = false
    )

    fun zustand(s: LauncherSummary?): Zustand {
        val k = s?.koerper
        return Zustand(k?.tueren.orEmpty(), k?.fenster.orEmpty(), k?.dach == true, s?.locked,
            s?.charging?.active == true || s?.acc == "charging")
    }

    /** Darstellung je Teil: offen (Warnfarbe), zu, unbekannt (neutral) bzw. Laden an/aus. */
    enum class Rolle { KLAPPE, KLAPPE_OFFEN, FENSTER_ZU, FENSTER_OFFEN, UNBEKANNT, LADEN, LADEN_AN }

    fun rolle(name: String, z: Zustand): Rolle = when (name) {
        "lf", "rf", "lr", "rr", "hood", "trunk" -> if (z.tuerOffen[name] == true) Rolle.KLAPPE_OFFEN else Rolle.KLAPPE
        "vl", "vr", "hl", "hr", DACH -> when {
            z.fenster[name] == null -> Rolle.UNBEKANNT
            offen(z.fenster[name]) -> Rolle.FENSTER_OFFEN
            else -> Rolle.FENSTER_ZU
        }
        "ladeklappe" -> if (z.laedt) Rolle.LADEN_AN else Rolle.LADEN
        else -> Rolle.KLAPPE
    }

    /** Lackfarbe: echte Farbe aus dem Kern („#RRGGBB“), sonst Graphit (dunkel) bzw. Silber (hell). */
    fun lack(farbe: String?, p: Palette): Int = Farbe.hex(farbe) ?: Farbe.rgb(if (p.hell) 0xC9D1D6 else 0x3B4753)

    // ---------- 0.1.44 (D1b): echtes Renderbild mit Ebene darunter (Bodenlicht) und darüber (Symbole) ----------

    /** Seitenverhältnis der Panoptes-Renderbilder (600 × 338). */
    const val BILD_SEITEN = 600f / 338f

    /** Bildrahmen [x, y, b, h] für das Renderbild in einer Fläche w × h: zentriert, Platz für Symbole oben und Ring unten. */
    fun bildRahmen(w: Float, h: Float): FloatArray {
        val bb = minOf(w * 0.94f, h * 0.78f * BILD_SEITEN)
        val bh = bb / BILD_SEITEN
        return floatArrayOf((w - bb) / 2f, h * 0.10f + (h * 0.80f - bh) / 2f, bb, bh)
    }

    /** Unter dem Bild: Bodenlicht, feiner Lichtring (beim Laden in der Ladefarbe) und weicher Schatten. */
    fun bodenlicht(l: Leinwand, z: Zustand, p: Palette, r: FloatArray) {
        val akzent = if (z.laedt) p.lime else p.aqua
        val cx = r[0] + r[2] / 2f; val boden = r[1] + r[3] * 0.90f
        l.ellipse(cx, boden, r[2] * 0.62f, r[3] * 0.24f, Radial(cx, boden, r[2] * 0.62f, r[3] * 0.24f,
            intArrayOf(Farbe.alpha(akzent, if (p.hell) 70 else 95), Farbe.alpha(akzent, 22), Farbe.alpha(akzent, 0)), floatArrayOf(0f, 0.55f, 1f)))
        l.ellipseRahmen(cx, boden, r[2] * 0.52f, r[3] * 0.16f, r[2] * 0.012f, Voll(Farbe.alpha(akzent, 30)))
        l.ellipseRahmen(cx, boden, r[2] * 0.52f, r[3] * 0.16f, r[2] * 0.0035f, Voll(Farbe.alpha(akzent, 150)))
        l.ellipse(cx, boden, r[2] * 0.44f, r[3] * 0.07f, Radial(cx, boden, r[2] * 0.44f, r[3] * 0.07f,
            intArrayOf(Farbe.argb(200, 0, 0, 0), Farbe.argb(90, 0, 0, 0), Farbe.argb(0, 0, 0, 0)), floatArrayOf(0f, 0.7f, 1f)))
    }

    /** Welche Symbole über dem Bild stehen (Reihenfolge links → rechts). Nur echte Werte; nichts Unbekanntes. */
    fun symbole(z: Zustand): List<String> = buildList {
        add("schloss")
        if (z.tuerOffen.filterKeys { it in listOf("lf", "rf", "lr", "rr") }.values.any { it }) add("tuer")
        if (z.tuerOffen["hood"] == true || z.tuerOffen["trunk"] == true) add("klappe")
        if (z.fenster.filterKeys { it in SEITENFENSTER }.values.any { offen(it) }) add("fenster")
        if (z.dach && offen(z.fenster[DACH])) add("dach")
        if (z.laedt) add("laden")
    }

    /** Über dem Bild: Symbol-Plaketten (Schloss immer; Tür, Klappe, Fenster, Dach offen in der Warnfarbe; Laden). */
    fun symboleZeichnen(l: Leinwand, z: Zustand, p: Palette, r: FloatArray) {
        val liste = symbole(z)
        val e = r[2] * 0.034f
        val abstand = e * 2.9f
        var x = r[0] + r[2] / 2f - (liste.size - 1) * abstand / 2f
        val y = r[1] - e * 0.4f
        for (art in liste) {
            val farbe = when (art) {
                "schloss" -> when (z.verriegelt) { true -> p.aqua; false -> p.warning; null -> Farbe.alpha(p.onSurface, 110) }
                "laden" -> p.lime
                else -> p.warning
            }
            l.ellipse(x, y, e * 1.25f, e * 1.25f, Voll(Farbe.argb(190, 6, 14, 20)))
            l.ellipseRahmen(x, y, e * 1.25f, e * 1.25f, e * 0.12f, Voll(Farbe.alpha(farbe, 220)))
            when (art) {
                "schloss" -> {
                    val bv = if (z.verriegelt == false) e * 0.22f else 0f
                    l.streckenzug(floatArrayOf(x - e * 0.35f + bv, y, x - e * 0.35f + bv, y - e * 0.42f, x - e * 0.18f + bv, y - e * 0.62f,
                        x + e * 0.18f + bv, y - e * 0.62f, x + e * 0.35f + bv, y - e * 0.42f, x + e * 0.35f + bv, y - (if (z.verriegelt == false) e * 0.3f else 0f)), e * 0.14f, farbe)
                    l.rechteck(x - e * 0.5f, y - e * 0.06f, e, e * 0.68f, e * 0.15f, Voll(farbe))
                }
                "tuer" -> { l.rahmen(x - e * 0.42f, y - e * 0.55f, e * 0.6f, e * 1.1f, e * 0.08f, e * 0.13f, farbe)
                    l.vieleck(floatArrayOf(x + e * 0.18f, y - e * 0.55f, x + e * 0.55f, y - e * 0.35f, x + e * 0.55f, y + e * 0.7f, x + e * 0.18f, y + e * 0.55f), Voll(farbe)) }
                "klappe" -> { l.linie(x - e * 0.55f, y + e * 0.35f, x + e * 0.55f, y + e * 0.35f, e * 0.14f, farbe); l.linie(x - e * 0.45f, y + e * 0.2f, x + e * 0.4f, y - e * 0.45f, e * 0.14f, farbe) }
                "fenster", "dach" -> { l.rahmen(x - e * 0.55f, y - e * 0.45f, e * 1.1f, e * 0.9f, e * 0.12f, e * 0.13f, farbe)
                    l.linie(x - e * 0.3f, y + e * 0.12f, x + e * 0.3f, y + e * 0.12f, e * 0.13f, farbe)
                    l.linie(x, y - e * 0.25f, x, y + e * 0.05f, e * 0.13f, farbe) }
                "laden" -> l.vieleck(floatArrayOf(x + e * 0.1f, y - e * 0.7f, x - e * 0.4f, y + e * 0.1f, x - e * 0.02f, y + e * 0.1f,
                    x - e * 0.12f, y + e * 0.7f, x + e * 0.4f, y - e * 0.1f, x + e * 0.02f, y - e * 0.1f), Voll(farbe))
            }
            x += abstand
        }
    }

    /**
     * Fahrzeuglänge in Pixeln, damit Auto, Lichtring und Plakette in die Fläche passen. Fixrunde 0.1.43b (G2): vorher
     * `h / 0.62` – bei flachen Flächen lag die Schloss-Plakette über dem Dach oben außerhalb (angeschnitten). Die Höhe
     * reicht jetzt für Plakette ([plaketteOben]) bis Bodenlicht ([bodenUnten]) bei Mitte `h / 2`, für jede Grundform.
     */
    fun laenge(w: Float, h: Float): Float = minOf(w / 1.22f, h / 0.80f).coerceAtLeast(0f)

    /** Oberkante der Schloss-Plakette (mit Rand) bei Mitte [cy] und Länge [s] – wie in [zeichne]. */
    fun plaketteOben(f: Form, cy: Float, s: Float): Float {
        val dachY = profil(f).umriss.filterIndexed { i, _ -> i % 2 == 1 }.maxOrNull() ?: 0.4f
        val e = s * 0.018f
        return cy + s * 0.17f - (dachY + 0.085f) * s - e * 1.9f - s * 0.003f
    }

    /** Unterkante des Bodenlichts bei Mitte [cy] und Länge [s] – wie in [zeichne]. */
    fun bodenUnten(cy: Float, s: Float): Float = cy + s * 0.17f + s * 0.12f

    /**
     * Zeichnet das Fahrzeug plastisch auf [l] in der Seitenansicht (Bug rechts, linke Seite zum Betrachter), Mitte
     * ([cx], [cy]), Länge [s] Pixel: Lack mit Licht von oben, Schulterlinie und Glanz, Glas mit Spiegelung, Felgen
     * mit Glanz, weicher Schatten, Bodenlicht und feiner Lichtring in der Akzentfarbe (beim Laden in der Ladefarbe).
     * Zustände am Auto: Fenster der sichtbaren Seite fahren um ihren Prozentwert herunter (Warnfarbe), offene
     * Türen/Klappen in der Warnfarbe, Fenster und Türen der abgewandten Seite als Warnlinie über dem Dach,
     * Schloss-Plakette, Ladekabel leuchtet. Nur Kern-Werte; unbekannt = neutral.
     */
    fun zeichne(l: Leinwand, f: Form, z: Zustand, p: Palette, lackFarbe: Int, cx: Float, cy: Float, s: Float) {
        val pr = profil(f)
        val boden = cy + s * 0.17f
        fun sx(x: Float) = cx + (x - 0.5f) * s
        fun sy(y: Float) = boden - y * s
        fun pts(n: List<Float>): FloatArray { val o = FloatArray(n.size); var i = 0; while (i + 1 < n.size) { o[i] = sx(n[i]); o[i + 1] = sy(n[i + 1]); i += 2 }; return o }
        val akzent = if (z.laedt) p.lime else p.aqua
        val umriss = glatt(pr.umriss)
        val dachY = pr.umriss.filterIndexed { i, _ -> i % 2 == 1 }.maxOrNull() ?: 0.4f
        // Bodenlicht, Lichtring und Schatten
        l.ellipse(cx, boden, s * 0.66f, s * 0.12f, Radial(cx, boden, s * 0.66f, s * 0.12f,
            intArrayOf(Farbe.alpha(akzent, if (p.hell) 70 else 95), Farbe.alpha(akzent, 22), Farbe.alpha(akzent, 0)), floatArrayOf(0f, 0.55f, 1f)))
        l.ellipseRahmen(cx, boden + s * 0.004f, s * 0.58f, s * 0.085f, s * 0.014f, Voll(Farbe.alpha(akzent, 30)))
        l.ellipseRahmen(cx, boden + s * 0.004f, s * 0.58f, s * 0.085f, s * 0.0035f, Voll(Farbe.alpha(akzent, 150)))
        l.ellipse(cx, boden, s * 0.50f, s * 0.032f, Radial(cx, boden, s * 0.50f, s * 0.032f,
            intArrayOf(Farbe.argb(210, 0, 0, 0), Farbe.argb(110, 0, 0, 0), Farbe.argb(0, 0, 0, 0)), floatArrayOf(0f, 0.7f, 1f)))
        // Radhäuser (dunkel hinter den Rädern)
        for (rx in listOf(pr.radHinten, pr.radVorn)) l.ellipse(sx(rx), sy(pr.radR), s * pr.radR * 1.18f, s * pr.radR * 1.18f, Voll(Farbe.rgb(0x07090B)))
        // Ladekabel hinter dem Auto zum Boden
        if (z.laedt) {
            val kabel = floatArrayOf(sx(pr.ladeX), sy(pr.ladeY), sx(pr.ladeX - 0.05f), sy(pr.ladeY - 0.04f), sx(pr.ladeX - 0.09f), sy(0.06f), sx(pr.ladeX - 0.16f), boden + s * 0.03f)
            l.streckenzug(kabel, s * 0.022f, Farbe.alpha(p.lime, 45))
            l.streckenzug(kabel, s * 0.007f, Farbe.alpha(p.lime, 235))
        }
        // Karosserie: Licht von oben, dunkle Schwelle
        val kar = pts(umriss)
        l.vieleck(kar, Linear(0f, sy(dachY), 0f, sy(0.05f), intArrayOf(Farbe.heller(lackFarbe, 0.38f), Farbe.heller(lackFarbe, 0.08f), lackFarbe, Farbe.dunkler(lackFarbe, 0.55f)),
            floatArrayOf(0f, 0.35f, 0.62f, 1f)))
        // Schulterlinie (Glanz) und dunkle Schwelle
        val schulter = schneide(schneide(umriss, 0.22f, false, true), 0.20f, false, false)
        if (schulter.size >= 6) l.vieleck(pts(schulter), Voll(Farbe.argb(if (p.hell) 40 else 28, 255, 255, 255)))
        val schwelle = schneide(umriss, 0.10f, false, true)
        if (schwelle.size >= 6) l.vieleck(pts(schwelle), Voll(Farbe.argb(90, 0, 0, 0)))
        // Fenster: Innenraum dunkel, Glas der sichtbaren Seite fährt um den Prozentwert herunter
        val fenster = pr.fenster
        val fy = fenster.filterIndexed { i, _ -> i % 2 == 1 }
        val gurt = fy.minOrNull() ?: 0.28f; val oberkante = fy.maxOrNull() ?: 0.36f
        l.vieleck(pts(fenster), Voll(Farbe.rgb(0x05090C)))
        val glas = Linear(0f, sy(oberkante), 0f, sy(gurt), intArrayOf(Farbe.rgb(0x3A6272), Farbe.rgb(0x10232C), Farbe.rgb(0x1C3946)), floatArrayOf(0f, 0.5f, 1f))
        for ((name, hinten) in listOf("hl" to true, "vl" to false)) {
            val teil = schneide(fenster, pr.bSaeule + (if (hinten) -0.006f else 0.006f), true, hinten)
            val pct = z.fenster[name]
            if (teil.size < 6) continue
            when {
                pct == null -> l.vieleck(pts(teil), Voll(Farbe.alpha(Farbe.rgb(0x1C3946), 200)))
                offen(pct) -> {
                    val bis = oberkante - (oberkante - gurt) * (pct / 100f).coerceIn(0.15f, 1f)
                    val rest = schneide(teil, bis, false, true)
                    if (rest.size >= 6) l.vieleck(pts(rest), glas)
                    l.linie(sx(teil.filterIndexed { i, _ -> i % 2 == 0 }.minOrNull()!!), sy(bis), sx(teil.filterIndexed { i, _ -> i % 2 == 0 }.maxOrNull()!!), sy(bis), s * 0.006f, p.warning)
                    l.vieleck(pts(teil), Voll(Farbe.alpha(p.warning, 40)))
                }
                else -> l.vieleck(pts(teil), glas)
            }
        }
        // Spiegelung quer über das Glas
        val glanz = schneide(schneide(fenster, pr.bSaeule + 0.03f, true, false), pr.bSaeule + 0.12f, true, true)
        if (glanz.size >= 6) l.vieleck(pts(schneide(glanz, gurt + (oberkante - gurt) * 0.55f, false, false)), Voll(Farbe.argb(26, 255, 255, 255)))
        // B-Säule
        l.linie(sx(pr.bSaeule), sy(gurt), sx(pr.bSaeule + 0.01f), sy(oberkante), s * 0.012f, Farbe.rgb(0x0A0E12))
        // Türen der sichtbaren Seite: Fugen; offen = Warnfarbe, Tür leicht ausgestellt
        val tuerUnten = 0.10f
        for ((name, von, bis) in listOf(Triple("lr", pr.tuerHinten, pr.bSaeule), Triple("lf", pr.bSaeule, pr.tuerVorn))) {
            if (z.tuerOffen[name] == true) {
                val tuer = listOf(von, gurt, bis, gurt, bis, tuerUnten, von, tuerUnten)
                l.vieleck(pts(tuer), Voll(Farbe.rgb(0x0A0D10)))
                val d = 0.035f
                l.vieleck(pts(listOf(von + d, gurt - 0.01f, bis + d * 1.6f, gurt + 0.01f, bis + d * 1.6f, tuerUnten - 0.02f, von + d, tuerUnten - 0.005f)),
                    Linear(0f, sy(gurt), 0f, sy(tuerUnten), intArrayOf(Farbe.heller(lackFarbe, 0.1f), Farbe.dunkler(lackFarbe, 0.4f)), floatArrayOf(0f, 1f)))
                l.streckenzug(pts(listOf(von + d, gurt - 0.01f, bis + d * 1.6f, gurt + 0.01f, bis + d * 1.6f, tuerUnten - 0.02f, von + d, tuerUnten - 0.005f, von + d, gurt - 0.01f)), s * 0.005f, p.warning)
            } else {
                l.linie(sx(von), sy(gurt - 0.005f), sx(von), sy(tuerUnten), s * 0.0025f, Farbe.alpha(Farbe.dunkler(lackFarbe, 0.8f), 200))
                l.linie(sx(bis), sy(gurt - 0.005f), sx(bis), sy(tuerUnten), s * 0.0025f, Farbe.alpha(Farbe.dunkler(lackFarbe, 0.8f), 200))
            }
        }
        // abgewandte Seite (vr, hr, rf, rr): offen = Warnlinie über dem Dach an der passenden Stelle
        for ((name, von, bis) in listOf(Triple("hr", pr.tuerHinten, pr.bSaeule), Triple("vr", pr.bSaeule, pr.tuerVorn),
                Triple("rr", pr.tuerHinten, pr.bSaeule), Triple("rf", pr.bSaeule, pr.tuerVorn))) {
            val offenHier = if (name.length == 2 && name[1] == 'r' && name[0] != 'r') offen(z.fenster[name]) else z.tuerOffen[name] == true
            if (offenHier) l.linie(sx(von + 0.02f), sy(dachY + 0.014f), sx(bis - 0.02f), sy(dachY + 0.014f), s * 0.005f, Farbe.alpha(p.warning, 200))
        }
        // Haube und Heckklappe offen
        if (z.tuerOffen["hood"] == true) l.vieleck(pts(listOf(pr.haubeVon, gurt - 0.01f, 0.97f, 0.22f, 0.99f, 0.30f, pr.haubeVon + 0.02f, gurt + 0.05f)), Voll(Farbe.alpha(p.warning, 200)))
        if (z.tuerOffen["trunk"] == true) l.vieleck(pts(listOf(0.0f, 0.24f, pr.heckBis, gurt + 0.02f, pr.heckBis + 0.04f, oberkante + 0.06f, 0.0f, 0.34f)), Voll(Farbe.alpha(p.warning, 200)))
        // Glasdach nur, wenn vorhanden: Streifen auf der Dachlinie
        if (z.dach) {
            val pct = z.fenster[DACH]
            l.linie(sx(pr.dachVon), sy(dachY - 0.004f), sx(pr.dachBis), sy(dachY - 0.004f), s * 0.006f,
                when { pct == null -> Farbe.rgb(0x1C3946); offen(pct) -> p.warning; else -> Farbe.rgb(0x3A6272) })
        }
        // Lichter: reine Gestaltung, kein Fahrzeugzustand
        l.vieleck(pts(listOf(0.93f, 0.225f, 0.99f, 0.18f, 0.995f, 0.165f, 0.925f, 0.205f)), Voll(Farbe.alpha(Farbe.heller(p.aqua, 0.75f), 230)))
        l.vieleck(pts(listOf(0.0f, 0.255f, 0.05f, 0.27f, 0.05f, 0.255f, 0.0f, 0.24f)), Voll(Farbe.alpha(p.danger, 210)))
        // Räder: Reifen, Felge mit Glanz, Speichen, Nabe
        for (rx in listOf(pr.radHinten, pr.radVorn)) {
            val x = sx(rx); val y = sy(pr.radR); val r = s * pr.radR
            l.ellipse(x, y, r, r, Radial(x, y, r, r, intArrayOf(Farbe.rgb(0x1B2026), Farbe.rgb(0x0B0E11)), floatArrayOf(0.6f, 1f)))
            l.ellipse(x, y, r * 0.66f, r * 0.66f, Radial(x - r * 0.2f, y - r * 0.25f, r * 0.9f, r * 0.9f,
                intArrayOf(Farbe.rgb(0xD5DCE1), Farbe.rgb(0x7D8892), Farbe.rgb(0x3A434B)), floatArrayOf(0f, 0.55f, 1f)))
            for (k in 0 until 5) {
                val w = Math.toRadians(90.0 + k * 72.0)
                l.linie(x, y, x + (Math.cos(w) * r * 0.6f).toFloat(), y - (Math.sin(w) * r * 0.6f).toFloat(), r * 0.16f, Farbe.rgb(0x5D6872))
            }
            l.ellipse(x, y, r * 0.16f, r * 0.16f, Voll(Farbe.alpha(akzent, 230)))
        }
        // Ladeklappe
        l.ellipse(sx(pr.ladeX), sy(pr.ladeY), s * 0.012f, s * 0.010f, Voll(if (z.laedt) p.lime else Farbe.dunkler(lackFarbe, 0.45f)))
        if (z.laedt) l.ellipse(sx(pr.ladeX), sy(pr.ladeY), s * 0.035f, s * 0.035f, Radial(sx(pr.ladeX), sy(pr.ladeY), s * 0.035f, s * 0.035f,
            intArrayOf(Farbe.alpha(p.lime, 170), Farbe.alpha(p.lime, 0)), floatArrayOf(0f, 1f)))
        // Schloss-Plakette über dem Dach
        val bx = sx((pr.dachVon + pr.dachBis) / 2f); val by = sy(dachY + 0.085f); val e = s * 0.018f
        val sf = when (z.verriegelt) { true -> p.aqua; false -> p.warning; null -> Farbe.alpha(p.onSurface, 110) }
        l.ellipse(bx, by, e * 1.9f, e * 1.9f, Voll(Farbe.argb(170, 6, 14, 20)))
        l.ellipseRahmen(bx, by, e * 1.9f, e * 1.9f, s * 0.003f, Voll(Farbe.alpha(sf, 210)))
        val bv = if (z.verriegelt == false) e * 0.35f else 0f
        l.streckenzug(floatArrayOf(bx - e * 0.55f + bv, by - e * 0.05f, bx - e * 0.55f + bv, by - e * 0.65f, bx - e * 0.3f + bv, by - e * 0.95f,
            bx + e * 0.3f + bv, by - e * 0.95f, bx + e * 0.55f + bv, by - e * 0.65f, bx + e * 0.55f + bv, by - e * (if (z.verriegelt == false) 0.45f else 0.05f)),
            s * 0.004f, sf)
        l.rechteck(bx - e * 0.8f, by - e * 0.1f, e * 1.6f, e * 1.1f, e * 0.25f, Voll(sf))
    }
}
