package at.greenchili.panoptes.launcher

import android.content.Context

/**
 * 0.1.43.3 (Rang 2, Design-Bausteine): die eine Quelle für Abstände, Radien, Schrift, Kontrast und Bewegung im
 * Launcher. Andere Pakete (Dock, Sprache, Fahrzeugbild, Hotspots, Kamera) nehmen diese Werte statt eigener Zahlen.
 * Eigenes Panoptes-Design, nichts aus anderen Launchern. Reine Werte und Regeln, im Unit-Test belegt
 * (LauncherGestaltungTest); Android nur in [Bewegung.weniger]/[Bewegung.setzen].
 */
object Mass {
    /** Alles liegt auf einem 8-dp-Raster; [XS] nur für Abstände innerhalb eines Bausteins. */
    const val RASTER = 8
    const val XS = 4
    const val S = 8
    const val M = 16
    const val L = 24
    const val XL = 32
    val ABSTAENDE = listOf(XS, S, M, L, XL)

    /** Drei Radien, mehr nicht: klein (Plakette, Eingabe, kleiner Knopf), mittel (Knopf, Dock), groß (Karte, Fenster). */
    const val RADIUS_KLEIN = 12
    const val RADIUS_MITTEL = 16
    const val RADIUS_GROSS = 20
    /** Pille/Kreis: halbe Höhe. */
    const val RADIUS_RUND = 999
    val RADIEN = listOf(RADIUS_KLEIN, RADIUS_MITTEL, RADIUS_GROSS)

    /** Kleinste Tippfläche (Zielgruppe 50+, Auto): 64 dp, mit „Große Bedienung“ mehr ([Ui.bd]). */
    const val TIPPFLAECHE = 64

    /** Karten-Innenabstand: kompakt (A3) = [M], Kachel sehr klein = [S]. */
    fun innen(klein: Boolean): Int = if (klein) S else M

    /** Ein beliebiger Radius auf den nächsten der drei (für alte Stellen, die eigene Zahlen hatten). */
    fun radius(wunsch: Int): Int = if (wunsch >= RADIUS_RUND) RADIUS_RUND else RADIEN.minByOrNull { Math.abs(it - wunsch) }!!
}

/**
 * Schriftskala (A6): Zahlen groß und halbfett in Sora mit gleich breiten Ziffern, Beschriftungen klein und gedämpft
 * in Manrope. Größen in sp; „Große Bedienung“ vergrößert Text und Titel ([Ui.stufe]), große Zahlen nicht (sie sind
 * schon groß und liefen sonst aus der Kachel).
 */
enum class Stufe(val sp: Float, val gewicht: Int, val sora: Boolean, val gross: Boolean = false,
                 val sperrung: Float = 0f, val ziffern: Boolean = false, val gedaempft: Boolean = false) {
    // 0.1.43.3 (L3, Projektinhaber 13.11. „launcher passt“): Entwurf 1920 px → Surf 1280 dp (× 2/3): Tempo 66 → 44, Reichweite 46 → 30
    ZAHL_GROSS(44f, 700, true, sperrung = -0.02f, ziffern = true),
    ZAHL(30f, 700, true, sperrung = -0.02f, ziffern = true),
    WERT(20f, 600, true, ziffern = true),
    TITEL(13f, 600, true, gross = true, sperrung = 0.08f),
    /** L3-Plakette (Verriegelt, Fenster offen, Lädt …): 20 px → 14 sp, halbfett. */
    PLAKETTE(14f, 600, false),
    TEXT(15f, 500, false),
    KLEIN(13f, 500, false, gedaempft = true),
    /** L3-Beschriftung über Werten („REICHWEITE“, „FAHRSTATUS“): Manrope 600, Großbuchstaben, weit gesperrt, gedämpft. */
    BESCHRIFTUNG(12f, 600, false, gross = true, sperrung = 0.14f, gedaempft = true);

    /** Wächst mit „Große Bedienung“? Nur Text, Titel und Beschriftung. */
    val waechst get() = !ziffern
}

/** Kontrast (A6/A9): Text mindestens 4,5:1, im Nacht-Design 7:1 (WCAG, Rechnung aus [Schleier.kontrast]). */
object Kontrast {
    const val TAG = 4.5f
    const val NACHT = 7f

    fun nacht(p: Palette): Boolean = p.background == PanoptesStil.NACHT.background && p.surface == PanoptesStil.NACHT.surface
    fun ziel(p: Palette): Float = if (nacht(p)) NACHT else TAG

    /**
     * Farbe [vorne] so weit zu [richtung] (meist onSurface) mischen, bis sie auf [grund] das [ziel] erreicht.
     * Erreicht sie es schon, bleibt sie unverändert; so bleiben Panoptes-Farben, wo sie genügen.
     */
    fun sicher(vorne: Int, grund: Int, ziel: Float, richtung: Int): Int {
        if (Schleier.kontrast(vorne, grund) >= ziel) return vorne
        var t = 0.05f
        while (t <= 1f) {
            val f = mischen(vorne, richtung, t)
            if (Schleier.kontrast(f, grund) >= ziel) return f
            t += 0.05f
        }
        return richtung
    }

    /** Gedämpfter Text (Beschriftung) einer Palette, auf [ziel] gesichert. */
    fun leise(p: Palette): Int = sicher(p.onSurfaceVariant, p.surface, ziel(p), p.onSurface)
    /** Kachel-Überschrift einer Palette, auf [ziel] gesichert. */
    fun titel(p: Palette): Int = sicher(p.outline, p.surface, ziel(p), p.onSurface)

    /** Ohne android.graphics.Color, damit es im Unit-Test läuft. */
    private fun mischen(a: Int, b: Int, t: Float): Int {
        fun k(s: Int) = Math.round(((a ushr s) and 0xFF) + (((b ushr s) and 0xFF) - ((a ushr s) and 0xFF)) * t) and 0xFF
        return (k(24) shl 24) or (k(16) shl 16) or (k(8) shl 8) or k(0)
    }
}

/**
 * Bewegung (A8/A11): kurze, ruhige Übergänge, nie länger als [MAX], kein Dauer-Loop. Schalter „Weniger Bewegung“
 * (ab Werk aus) und Android „Animationen aus“ (Skala 0) schalten alle Übergänge ab: dann gilt Dauer 0.
 */
object Bewegung {
    const val KURZ = 150L
    const val MITTEL = 220L
    const val MAX = 250L
    private const val PREFS = "launcher"
    private const val KEY = "weniger_bewegung"

    fun dauer(ms: Long, weniger: Boolean, systemSkala: Float = 1f): Long =
        if (weniger || systemSkala <= 0f) 0L else ms.coerceIn(0L, MAX)

    fun weniger(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setzen(ctx: Context, an: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, an).apply()
    }

    /** Android-Einstellung „Animationsdauer“ (Entwickleroptionen / Bedienungshilfen „Animationen entfernen“). */
    fun systemSkala(ctx: Context): Float = runCatching {
        android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }.getOrDefault(1f)

    fun dauer(ctx: Context, ms: Long): Long = dauer(ms, weniger(ctx), systemSkala(ctx))
}

/**
 * Leere Werte (A3): „—“ steht ruhig und gedämpft statt in voller Zahlenfarbe da. Wird der Wert wieder voll, kommt die
 * vorige Farbe zurück; setzt der Aufrufer danach eine eigene Farbe (z. B. Warnung), gilt seine.
 */
object LeerWert {
    const val STRICH = "—"

    fun leer(t: CharSequence?): Boolean = t == null || t.toString().trim().let { it.isEmpty() || it == STRICH }

    fun ruhig(tv: android.widget.TextView, voll: Int, leise: Int) {
        var vorher = voll
        fun anwenden(t: CharSequence?) {
            val jetzt = tv.currentTextColor
            if (leer(t)) { if (jetzt != leise) { vorher = jetzt; tv.setTextColor(leise) } }
            else if (jetzt == leise) tv.setTextColor(vorher)
        }
        anwenden(tv.text)
        tv.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = anwenden(s)
        })
    }
}

/**
 * 0.1.43.3 (L3): Glas-Karten. Im Entwurf liegt hinter dem Glas eine Unschärfe (22 px, Sättigung 150 %); Android 10 im
 * Auto kann das nicht live (RenderEffect erst ab Android 12). Ersatz: dichtere Füllung als im Entwurf (dunkel .58 → .78,
 * hell .62 → .92, sonst scheint der Schatten durch), damit die Szene dahinter die Schrift nicht stört; Rand 1 dp, oben 1 dp Innenlicht, weicher Schatten.
 * Werte als ARGB, ohne Android, damit sie im Unit-Test laufen; gezeichnet wird in [Ui.glas].
 */
object Glas {
    fun fuellung(hell: Boolean): Int = if (hell) argb(0.92, 255, 255, 255) else argb(0.78, 16, 26, 42)
    /** Unterkante etwas dichter: die Karte wirkt nach unten satter, oben heller (Licht von oben). */
    fun fuellungUnten(hell: Boolean): Int = if (hell) argb(0.95, 246, 250, 252) else argb(0.84, 12, 20, 33)
    fun rand(hell: Boolean): Int = if (hell) argb(0.14, 20, 40, 70) else argb(0.10, 255, 255, 255)
    /** 1 dp Innenlicht an der Oberkante (Entwurf: weiß 7 %). */
    fun innenlicht(hell: Boolean): Int = if (hell) argb(0.55, 255, 255, 255) else argb(0.07, 255, 255, 255)
    /** Schatten als Erhebung in dp (Entwurf: dunkel 0 18 50 / .45, hell 0 14 40 / .22). */
    fun schatten(hell: Boolean): Int = if (hell) 8 else 12

    internal fun argb(a: Double, r: Int, g: Int, b: Int): Int = (Math.round(a * 255).toInt() shl 24) or (r shl 16) or (g shl 8) or b
}

/**
 * 0.1.43.3 (L3): Signalfarben. Akzent bleibt die Akzentfarbe des Designs (eigene Akzentfarbe gilt weiter); für die
 * Panoptes-Designs Dunkel/Hell liefert L3 Warnung (Orange, nur für Warnungen) und OK (Grün). Nacht und Kontrast behalten
 * ihre eigenen Töne. Alles auf Mindestkontrast gesichert ([Kontrast]).
 */
object Signal {
    const val AKZENT_L3 = 0xFF35D0E6.toInt()
    private const val WARN_DUNKEL = 0xFFFFB454.toInt()
    private const val WARN_HELL = 0xFFC76A00.toInt()
    private const val OK_DUNKEL = 0xFF5EE3A1.toInt()
    private const val OK_HELL = 0xFF0F9D63.toInt()

    private fun panoptes(p: Palette) = p.background == PanoptesStil.DUNKEL.background || p.background == PanoptesStil.HELL.background

    fun warnung(p: Palette): Int = Kontrast.sicher(if (panoptes(p)) (if (p.hell) WARN_HELL else WARN_DUNKEL) else p.warning, p.surface, Kontrast.TAG, p.onSurface)
    fun ok(p: Palette): Int = Kontrast.sicher(if (panoptes(p)) (if (p.hell) OK_HELL else OK_DUNKEL) else p.lime, p.surface, Kontrast.TAG, p.onSurface)
    fun gefahr(p: Palette): Int = p.danger

    /** Ton einer Plakette. */
    enum class Ton { OK, WARNUNG, INFO, GEFAHR, NEUTRAL }

    fun farbe(t: Ton, p: Palette): Int = when (t) {
        Ton.OK -> ok(p); Ton.WARNUNG -> warnung(p); Ton.INFO -> p.aqua; Ton.GEFAHR -> gefahr(p); Ton.NEUTRAL -> p.onSurface
    }
}

/**
 * 0.1.43.3 (L3): Szenenwechsel. Steht als Hintergrund eine Szene, zeigt der Launcher im Stand die gewählte Szene und in
 * Gang D die Fahrt-Szene (Nachtstraße, im hellen Design als Dämmerung). Ohne Szene (Motiv, Bild, Verlauf) ändert sich
 * nichts. Gang unbekannt = Stand-Szene (nie raten). Der Wechsel blendet über [Bewegung.MITTEL], mit „Weniger Bewegung“ sofort.
 */
object SzenenWechsel {
    const val FAHRT = 11

    fun faehrt(gear: String?): Boolean = gear == "D"

    /** Anzuzeigende Szene/Motiv-Nummer: gewählte Nummer, in Fahrt die Fahrt-Szene (nur wenn eine Szene gewählt ist). */
    fun nummer(gewaehlt: Int?, gear: String?): Int? =
        if (gewaehlt != null && Szenen.ist(gewaehlt) && faehrt(gear)) FAHRT else gewaehlt
}
