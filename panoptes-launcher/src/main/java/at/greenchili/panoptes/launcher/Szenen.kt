package at.greenchili.panoptes.launcher

/**
 * 0.1.44 (D1): eigene Szenen-Hintergründe für die Fahrzeug-Startseite, ganz per Code gezeichnet (keine Bilddatei,
 * keine fremde oder KI-Grafik): 11 Nachtstraße mit Skyline, 12 Showroom-Halle, 13 Bergstraße, 14 Garage.
 * Farben aus dem aktuellen Design (Grund, Akzent, Warnfarbe), damit „Tag/Nacht automatisch“ greift; im hellen Design
 * werden die Szenen heller (Dämmerung statt Nacht). Der untere Bereich bleibt ruhig, dort steht das Fahrzeug.
 */
object Szenen {
    const val ERSTE = 11
    const val LETZTE = 14
    /** Auch die gerenderten Szenen ([SzenenBilder], 0.1.43.4) zählen als Szene (Glas-Karten, Auto frei). */
    fun ist(nr: Int?) = nr != null && (nr in ERSTE..LETZTE || SzenenBilder.ist(nr))

    /** Feste Zahlenfolge (kein Zufall zur Laufzeit): immer das gleiche Bild. */
    private class Folge(var s: Long) { fun naechste(): Float { s = (s * 1103515245L + 12345L) and 0x7fffffffL; return (s % 10000) / 10000f } }

    private fun himmel(p: Palette, oben: Float, unten: Float): Pair<Int, Int> {
        val grund = if (p.hell) Farbe.mix(p.background, p.aqua, 0.10f) else Farbe.dunkler(p.background, 0.55f)
        return Farbe.dunkler(grund, oben) to Farbe.mix(grund, p.aqua, unten)
    }

    fun zeichne(nr: Int, l: Leinwand, w: Float, h: Float, p: Palette) {
        when (nr) {
            11 -> nachtstrasse(l, w, h, p)
            12 -> halle(l, w, h, p)
            13 -> bergstrasse(l, w, h, p)
            else -> garage(l, w, h, p)
        }
    }

    private fun nachtstrasse(l: Leinwand, w: Float, h: Float, p: Palette) {
        val hz = h * 0.46f
        val (oben, unten) = himmel(p, 0.25f, 0.22f)
        l.rechteck(0f, 0f, w, h, 0f, Linear(0f, 0f, 0f, hz, intArrayOf(oben, unten), floatArrayOf(0f, 1f)))
        val f = Folge(424242L)
        repeat(70) { l.ellipse(f.naechste() * w, f.naechste() * hz * 0.55f, 1.2f + f.naechste() * 1.4f, 1.2f + f.naechste() * 1.4f, Voll(Farbe.alpha(p.onSurface, 60 + (f.naechste() * 80).toInt()))) }
        // Glühen am Horizont
        l.ellipse(w * 0.5f, hz, w * 0.6f, h * 0.22f, Radial(w * 0.5f, hz, w * 0.6f, h * 0.22f,
            intArrayOf(Farbe.alpha(p.aqua, if (p.hell) 70 else 110), Farbe.alpha(p.aqua, 0)), floatArrayOf(0f, 1f)))
        // Skyline: zwei Reihen Häuser, hinten heller
        for (reihe in 0..1) {
            val g = Folge(1000L + reihe * 77)
            var x = -w * 0.02f
            val farbe = if (reihe == 0) Farbe.mix(oben, unten, 0.55f) else Farbe.dunkler(oben, 0.25f)
            while (x < w) {
                val bb = w * (0.025f + g.naechste() * 0.045f)
                val mitte = Math.abs(x + bb / 2 - w / 2) / (w / 2)   // in der Mitte niedriger: Blick auf die Straße
                val hh = h * (0.08f + g.naechste() * (if (reihe == 0) 0.30f else 0.22f)) * (0.55f + mitte * 0.6f)
                l.rechteck(x, hz - hh, bb, hh + 1f, 0f, Voll(farbe))
                if (reihe == 1) {
                    var wy = hz - hh + h * 0.015f
                    while (wy < hz - h * 0.01f) {
                        var wx = x + bb * 0.18f
                        while (wx < x + bb * 0.82f) {
                            if (g.naechste() > 0.62f) l.rechteck(wx, wy, bb * 0.09f, h * 0.008f, 0f,
                                Voll(Farbe.alpha(if (g.naechste() > 0.5f) p.warning else p.aqua, 90 + (g.naechste() * 90).toInt())))
                            wx += bb * 0.2f
                        }
                        wy += h * 0.022f
                    }
                }
                x += bb + w * 0.004f
            }
        }
        // Straße in Fluchtpunkt-Perspektive
        val boden = if (p.hell) Farbe.mix(p.background, p.onSurface, 0.35f) else Farbe.dunkler(p.background, 0.7f)
        l.rechteck(0f, hz, w, h - hz, 0f, Linear(0f, hz, 0f, h, intArrayOf(Farbe.mix(boden, p.aqua, 0.10f), boden), floatArrayOf(0f, 1f)))
        val fx = w * 0.5f
        l.vieleck(floatArrayOf(fx - w * 0.015f, hz, fx + w * 0.015f, hz, w * 0.88f, h, w * 0.12f, h),
            Linear(0f, hz, 0f, h, intArrayOf(Farbe.mix(boden, p.aqua, 0.18f), Farbe.dunkler(boden, 0.2f)), floatArrayOf(0f, 1f)))
        for (seite in listOf(-1f, 1f)) l.vieleck(floatArrayOf(fx + seite * w * 0.015f, hz, fx + seite * w * 0.016f, hz,
            w / 2 + seite * w * 0.385f, h, w / 2 + seite * w * 0.38f, h),
            Linear(0f, hz, 0f, h, intArrayOf(Farbe.alpha(p.onSurface, 90), Farbe.alpha(p.onSurface, 10)), floatArrayOf(0f, 1f)))
        var t = 0.04f
        while (t < 1f) {
            val t2 = minOf(1f, t + 0.05f + t * 0.12f)
            l.linie(fx, hz + (h - hz) * t, fx, hz + (h - hz) * t2, h * (0.002f + t * 0.006f), Farbe.alpha(p.warning, 90))
            t = t2 + 0.05f + t * 0.14f
        }
        // Laternen-Lichtpunkte am Straßenrand
        for (k in 1..4) {
            val q = k / 7f; val y = hz + (h - hz) * q * q
            for (seite in listOf(-1f, 1f)) {
                val x = fx + seite * (w * 0.02f + w * 0.42f * q * q * 1.15f)
                l.ellipse(x, y - h * 0.18f * q, w * 0.016f * q, w * 0.016f * q, Radial(x, y - h * 0.18f * q, w * 0.016f * q, w * 0.016f * q,
                    intArrayOf(Farbe.alpha(p.warning, 90), Farbe.alpha(p.warning, 0)), floatArrayOf(0f, 1f)))
            }
        }
    }

    private fun halle(l: Leinwand, w: Float, h: Float, p: Palette) {
        val hz = h * 0.46f
        val wand = if (p.hell) Farbe.mix(p.background, p.onSurface, 0.06f) else Farbe.dunkler(p.background, 0.45f)
        l.rechteck(0f, 0f, w, hz, 0f, Linear(0f, 0f, 0f, hz, intArrayOf(Farbe.dunkler(wand, 0.35f), Farbe.mix(wand, p.aqua, 0.08f)), floatArrayOf(0f, 1f)))
        // Wandfelder
        val felder = 9
        for (k in 0..felder) l.linie(w * k / felder, h * 0.10f, w * k / felder, hz, 1.5f, Farbe.alpha(p.onSurface, 18))
        // Lichtbänder an der Decke
        for (k in 0..1) {
            val y = h * (0.015f + k * 0.04f); val halb = w * (0.42f - k * 0.12f)
            l.rechteck(w / 2 - halb, y, halb * 2, h * 0.012f, h * 0.006f,
                Linear(w / 2 - halb, 0f, w / 2 + halb, 0f, intArrayOf(Farbe.alpha(p.onSurface, 0), Farbe.alpha(Farbe.heller(p.aqua, 0.6f), 150), Farbe.alpha(p.onSurface, 0)), floatArrayOf(0f, 0.5f, 1f)))
        }
        // spiegelnder Boden
        val boden = if (p.hell) Farbe.mix(p.background, p.onSurface, 0.18f) else Farbe.dunkler(p.background, 0.62f)
        l.rechteck(0f, hz, w, h - hz, 0f, Linear(0f, hz, 0f, h, intArrayOf(Farbe.mix(boden, p.aqua, 0.12f), boden), floatArrayOf(0f, 1f)))
        for (k in -8..8) l.linie(w / 2 + k * w * 0.012f, hz, w / 2 + k * w * 0.14f, h, 1.2f, Farbe.alpha(p.onSurface, 14))
        var y = hz; var a = h * 0.012f
        while (y < h) { l.linie(0f, y, w, y, 1.2f, Farbe.alpha(p.onSurface, 14)); y += a; a *= 1.35f }
        // Spot auf die Standfläche
        l.ellipse(w / 2, h * 0.78f, w * 0.36f, h * 0.22f, Radial(w / 2, h * 0.78f, w * 0.36f, h * 0.22f,
            intArrayOf(Farbe.alpha(Farbe.heller(p.aqua, 0.5f), 70), Farbe.alpha(p.aqua, 0)), floatArrayOf(0f, 1f)))
    }

    private fun bergstrasse(l: Leinwand, w: Float, h: Float, p: Palette) {
        val hz = h * 0.50f
        val (oben, _) = himmel(p, 0.2f, 0f)
        val warm = Farbe.mix(p.warning, p.danger, 0.3f)
        l.rechteck(0f, 0f, w, hz, 0f, Linear(0f, 0f, 0f, hz, intArrayOf(oben, Farbe.mix(oben, p.aqua, 0.35f), Farbe.mix(oben, warm, 0.55f)), floatArrayOf(0f, 0.6f, 1f)))
        // Sonne kurz über dem Grat
        l.ellipse(w * 0.66f, hz - h * 0.16f, w * 0.16f, w * 0.16f, Radial(w * 0.66f, hz - h * 0.16f, w * 0.16f, w * 0.16f,
            intArrayOf(Farbe.alpha(Farbe.heller(p.warning, 0.4f), 200), Farbe.alpha(p.warning, 60), Farbe.alpha(p.warning, 0)), floatArrayOf(0f, 0.12f, 1f)))
        // drei Bergketten, hinten heller (Luftperspektive)
        val ketten = listOf(Triple(0.46f, 0.85f, 0.30f), Triple(0.30f, 0.8f, 0.55f), Triple(0.17f, 0.75f, 0.80f))
        ketten.forEachIndexed { i, (hoch, gipfel, dunkel) ->
            val f = Folge(9000L + i * 31)
            val pts = ArrayList<Float>(); pts += 0f; pts += h
            var x = 0f
            while (x <= w) { pts += x; pts += hz - h * hoch * (0.35f + 0.65f * f.naechste()) * gipfel; x += w * (0.05f + f.naechste() * 0.07f) }
            pts += w; pts += hz - h * hoch * 0.4f; pts += w; pts += h
            val farbe = Farbe.dunkler(Farbe.mix(oben, p.aqua, 0.25f), dunkel)
            l.vieleck(pts.toFloatArray(), Linear(0f, hz - h * hoch, 0f, h, intArrayOf(farbe, Farbe.dunkler(farbe, 0.4f)), floatArrayOf(0f, 1f)))
        }
        // Straße, die sich ins Tal windet
        val boden = Farbe.dunkler(Farbe.mix(oben, p.aqua, 0.2f), 0.85f)
        l.rechteck(0f, hz, w, h - hz, 0f, Voll(boden))
        val links = ArrayList<Float>(); val rechts = ArrayList<Float>()
        for (k in 0..20) {
            val q = k / 20f; val y = hz + (h - hz) * q
            val mitte = w * (0.56f + 0.10f * kotlin.math.sin(q * 5.5f) * (1f - q))
            val halb = w * (0.008f + 0.36f * q * q)
            links += mitte - halb; links += y; rechts += mitte + halb; rechts += y
        }
        val strasse = ArrayList<Float>(links)
        var i = rechts.size - 2
        while (i >= 0) { strasse += rechts[i]; strasse += rechts[i + 1]; i -= 2 }
        l.vieleck(strasse.toFloatArray(), Linear(0f, hz, 0f, h, intArrayOf(Farbe.mix(boden, p.aqua, 0.15f), Farbe.dunkler(boden, 0.3f)), floatArrayOf(0f, 1f)))
        l.streckenzug(links.toFloatArray(), h * 0.003f, Farbe.alpha(p.onSurface, 90))
        l.streckenzug(rechts.toFloatArray(), h * 0.003f, Farbe.alpha(p.onSurface, 90))
    }

    private fun garage(l: Leinwand, w: Float, h: Float, p: Palette) {
        val hz = h * 0.48f
        val beton = if (p.hell) Farbe.mix(p.background, p.onSurface, 0.12f) else Farbe.mix(Farbe.dunkler(p.background, 0.4f), p.onSurfaceVariant, 0.10f)
        l.rechteck(0f, 0f, w, hz, 0f, Linear(0f, 0f, 0f, hz, intArrayOf(Farbe.dunkler(beton, 0.35f), beton), floatArrayOf(0f, 1f)))
        // Schalungsfugen
        var y = h * 0.14f
        while (y < hz) { l.linie(0f, y, w, y, 1.2f, Farbe.alpha(p.onSurface, 16)); y += h * 0.11f }
        for (k in 1..5) l.linie(w * k / 6f, 0f, w * k / 6f, hz, 1.2f, Farbe.alpha(p.onSurface, 10))
        // Leuchtröhren mit Lichtkegel
        for (k in 0..2) {
            val x = w * (0.22f + k * 0.28f)
            l.rechteck(x - w * 0.07f, h * 0.06f, w * 0.14f, h * 0.012f, h * 0.006f, Voll(Farbe.alpha(Farbe.heller(p.aqua, 0.75f), 235)))
            l.vieleck(floatArrayOf(x - w * 0.07f, h * 0.072f, x + w * 0.07f, h * 0.072f, x + w * 0.16f, hz, x - w * 0.16f, hz),
                Linear(0f, h * 0.07f, 0f, hz, intArrayOf(Farbe.alpha(Farbe.heller(p.aqua, 0.6f), 45), Farbe.alpha(p.aqua, 0)), floatArrayOf(0f, 1f)))
        }
        // eigene Wandladestation (schlichter Kasten mit Akzent-Leuchtstreifen)
        val wx = w * 0.84f; val wy = hz - h * 0.30f
        l.rechteck(wx, wy, w * 0.045f, h * 0.15f, w * 0.006f, Linear(wx, 0f, wx + w * 0.045f, 0f, intArrayOf(Farbe.heller(beton, 0.25f), Farbe.dunkler(beton, 0.2f)), floatArrayOf(0f, 1f)))
        l.rechteck(wx + w * 0.012f, wy + h * 0.03f, w * 0.021f, h * 0.006f, h * 0.003f, Voll(p.lime))
        // Boden mit Stellplatz-Markierung
        val boden = Farbe.dunkler(beton, 0.45f)
        l.rechteck(0f, hz, w, h - hz, 0f, Linear(0f, hz, 0f, h, intArrayOf(Farbe.mix(boden, p.aqua, 0.06f), Farbe.dunkler(boden, 0.25f)), floatArrayOf(0f, 1f)))
        for (seite in listOf(-1f, 1f)) l.vieleck(floatArrayOf(w / 2 + seite * w * 0.20f, hz, w / 2 + seite * w * 0.205f, hz,
            w / 2 + seite * w * 0.43f, h, w / 2 + seite * w * 0.41f, h), Voll(Farbe.alpha(p.warning, 150)))
        l.ellipse(w / 2, h * 0.80f, w * 0.30f, h * 0.18f, Radial(w / 2, h * 0.80f, w * 0.30f, h * 0.18f,
            intArrayOf(Farbe.alpha(Farbe.heller(p.aqua, 0.5f), 45), Farbe.alpha(p.aqua, 0)), floatArrayOf(0f, 1f)))
    }
}
