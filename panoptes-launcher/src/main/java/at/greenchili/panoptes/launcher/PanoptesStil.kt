package at.greenchili.panoptes.launcher

/**
 * Panoptes-Look als Kotlin-Werte: dieselben Design-Tokens wie die Weboberfläche
 * (`app/src/main/assets/web/shared/design-tokens.css`, `web/local/index.html` Startseite).
 * PanoptesStilTest vergleicht jede Farbe mit der CSS-Datei, damit beides nicht auseinanderläuft.
 *
 * Farben als ARGB-Int (0xAARRGGBB). Reine Werte ohne Android, damit sie im Unit-Test laufen.
 */
data class Palette(
    val hell: Boolean,
    val background: Int,          // --background
    val surface: Int,             // --surface
    val onSurface: Int,           // --on-surface
    val onSurfaceVariant: Int,    // --on-surface-variant
    val outline: Int,             // --outline (Kachel-Überschrift)
    val aqua: Int,                // --tertiary (dunkel = --sm-aqua)
    val lime: Int,                // --primary (dunkel = --sm-lime)
    val onPrimary: Int,           // --on-primary
    val warning: Int,             // --status-warning
    val danger: Int,              // --status-danger
    val line: Int,                // --sm-line
    val card: Int,                // --sm-card
    val act: Int,                 // .act Hintergrund
    val seiteVon: Int,            // --sm-page-bg linear-gradient Start
    val seiteBis: Int,            // --sm-page-bg linear-gradient Ende
    val glowA: Int,               // --sm-page-bg radial 1 (oben rechts)
    val glowB: Int,               // --sm-page-bg radial 2 (unten links)
    val heroVon: Int,             // .c-hero linear-gradient Start
    val heroBis: Int              // .c-hero linear-gradient Ende
)

object PanoptesStil {
    private fun rgba(r: Int, g: Int, b: Int, a: Double): Int = ((Math.round(a * 255).toInt() and 0xFF) shl 24) or (r shl 16) or (g shl 8) or b
    private fun hex(v: Long): Int = (0xFF000000 or v).toInt()

    val DUNKEL = Palette(
        hell = false,
        background = hex(0x071A2B),
        surface = hex(0x0A2238),
        onSurface = hex(0xEAF6F8),
        onSurfaceVariant = hex(0xA9C7CE),
        outline = hex(0x6E8E97),
        aqua = hex(0x5FD6E6),
        lime = hex(0xB5F23D),
        onPrimary = hex(0x1D2A00),
        warning = hex(0xFFC266),
        danger = hex(0xFF8A6B),
        line = rgba(160, 230, 240, 0.12),
        card = rgba(255, 255, 255, 0.045),
        act = rgba(255, 255, 255, 0.075),
        seiteVon = hex(0x0A2238),
        seiteBis = hex(0x071A2B),
        glowA = rgba(11, 92, 102, 0.55),
        glowB = rgba(95, 214, 230, 0.10),
        heroVon = rgba(11, 92, 102, 0.45),
        heroBis = rgba(10, 34, 56, 0.35)
    )

    val HELL = Palette(
        hell = true,
        background = hex(0xEEF6F8),
        surface = hex(0xF6FBFC),
        onSurface = hex(0x0A2238),
        onSurfaceVariant = hex(0x3A5862),
        outline = hex(0x5F7C86),
        aqua = hex(0x00758A),
        lime = hex(0x4E7F00),
        onPrimary = hex(0xFFFFFF),
        warning = hex(0x9A5B00),
        danger = hex(0xB3361B),
        line = rgba(10, 34, 56, 0.10),
        card = rgba(255, 255, 255, 0.75),
        act = rgba(10, 34, 56, 0.05),
        seiteVon = hex(0xF6FBFC),
        seiteBis = hex(0xE3F0F4),
        glowA = 0,
        glowB = 0,
        heroVon = rgba(0, 117, 138, 0.14),
        heroBis = rgba(255, 255, 255, 0.60)
    )

    /** Cockpit-Ansicht (index.html `data-home-layout="cockpit"`): eigene dichte Kacheloptik, nur dunkel. */
    val COCKPIT_KARTE_VON = rgba(35, 50, 67, 0.92)
    val COCKPIT_KARTE_BIS = rgba(18, 29, 42, 0.88)
    val COCKPIT_RAND = rgba(186, 211, 232, 0.19)
    val COCKPIT_TITEL = hex(0xB8C8D8)
    val COCKPIT_SEITE = intArrayOf(hex(0x07121D), hex(0x101D2C), hex(0x071019))
    val COCKPIT_GLOW = rgba(64, 153, 201, 0.22)

    /** Pegel (Ladestand) wie `.gauge`: Wasser normal / niedrig / kritisch. */
    val PEGEL_NORMAL = intArrayOf(hex(0x43C9DB), hex(0x0B5C66))
    val PEGEL_NIEDRIG = intArrayOf(hex(0xFFB15E), hex(0x8A4B14))
    val PEGEL_KRITISCH = intArrayOf(hex(0xFF8A6B), hex(0x7A2414))

    /** Radien in dp wie index.html: Karte 20 (Cockpit 14), Knopf .act 16, .hbtn 12. */
    const val RADIUS_KARTE = 20
    const val RADIUS_KARTE_COCKPIT = 14
    const val RADIUS_ACT = 16

    /** "dark" | "light" | "auto" (appearance.modus) → Palette. auto folgt dem System wie theme.js. */
    fun palette(modus: String?, systemHell: Boolean): Palette = when (modus) {
        "light" -> HELL
        "auto" -> if (systemHell) HELL else DUNKEL
        else -> DUNKEL
    }

    /** Pegelfarbe wie Home.js: unter 20 % niedrig, unter 10 % kritisch. */
    fun pegel(socPct: Double?): IntArray = when {
        socPct == null -> PEGEL_NORMAL
        socPct < 10 -> PEGEL_KRITISCH
        socPct < 20 -> PEGEL_NIEDRIG
        else -> PEGEL_NORMAL
    }
}
