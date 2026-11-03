package at.greenchili.panoptes.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Der Launcher muss aussehen wie Panoptes: Jede Farbe der Kotlin-Palette wird gegen die
 * Design-Tokens der Weboberfläche geprüft (design-tokens.css, Startseite index.html).
 * Ändert jemand dort eine Farbe, schlägt dieser Test fehl, bis der Launcher nachzieht.
 */
class PanoptesStilTest {
    private fun lies(rel: String) = String(Files.readAllBytes(Paths.get("../$rel")), Charsets.UTF_8)
    private val tokens = lies("app/src/main/assets/web/shared/design-tokens.css")
    private val start = lies("app/src/main/assets/web/local/index.html")

    private fun block(selector: String): String {
        val i = tokens.indexOf("$selector {")
        assertTrue("Block $selector fehlt", i >= 0)
        return tokens.substring(i, tokens.indexOf("\n}", i))
    }

    private fun hex(block: String, name: String): Int {
        val m = Regex("--$name:\\s*#([0-9A-Fa-f]{6});").find(block) ?: error("--$name fehlt")
        return (0xFF000000 or m.groupValues[1].toLong(16)).toInt()
    }

    private fun rgba(text: String, name: String): Int {
        val m = Regex("--$name:\\s*rgba\\((\\d+),\\s*(\\d+),\\s*(\\d+),\\s*([0-9.]+)\\)").find(text) ?: error("--$name fehlt")
        val (r, g, b, a) = m.destructured
        return ((Math.round(a.toDouble() * 255).toInt() and 0xFF) shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
    }

    private fun pruefe(p: Palette, b: String) {
        assertEquals("background", hex(b, "background"), p.background)
        assertEquals("surface", hex(b, "surface"), p.surface)
        assertEquals("on-surface", hex(b, "on-surface"), p.onSurface)
        assertEquals("on-surface-variant", hex(b, "on-surface-variant"), p.onSurfaceVariant)
        assertEquals("outline", hex(b, "outline"), p.outline)
        assertEquals("tertiary", hex(b, "tertiary"), p.aqua)
        assertEquals("primary", hex(b, "primary"), p.lime)
        assertEquals("on-primary", hex(b, "on-primary"), p.onPrimary)
        assertEquals("status-warning", hex(b, "status-warning"), p.warning)
        assertEquals("status-danger", hex(b, "status-danger"), p.danger)
        assertEquals("sm-line", rgba(b, "sm-line"), p.line)
        assertEquals("sm-card", rgba(b, "sm-card"), p.card)
    }

    @Test fun dunkelWieDesignTokens() {
        val b = block(":root")
        pruefe(PanoptesStil.DUNKEL, b)
        assertEquals(hex(b, "sm-aqua"), PanoptesStil.DUNKEL.aqua)
        assertEquals(hex(b, "sm-lime"), PanoptesStil.DUNKEL.lime)
        assertTrue(b.contains("linear-gradient(160deg, #0A2238, #071A2B 70%)"))
        assertTrue(b.contains("rgba(11, 92, 102, 0.55)") && b.contains("rgba(95, 214, 230, 0.10)"))
    }

    @Test fun hellWieDesignTokens() {
        val b = block(":root[data-theme=\"light\"]")
        pruefe(PanoptesStil.HELL, b)
        assertTrue(b.contains("linear-gradient(160deg, #F6FBFC, #E3F0F4 70%)"))
    }

    @Test fun kartenUndCockpitWieStartseite() {
        assertTrue(start.contains(".hc { background: var(--sm-card); border: 1px solid var(--sm-line); border-radius: 20px;"))
        assertEquals(20, PanoptesStil.RADIUS_KARTE)
        assertTrue(start.contains("border-radius: 14px; padding: 14px;"))
        assertEquals(14, PanoptesStil.RADIUS_KARTE_COCKPIT)
        assertTrue(start.contains("background: linear-gradient(145deg, rgba(35, 50, 67, .92), rgba(18, 29, 42, .88));"))
        assertTrue(start.contains("border-color: rgba(186, 211, 232, .19);"))
        assertTrue(start.contains("color: #b8c8d8;"))
        assertTrue(start.contains("background: linear-gradient(180deg, #43c9db, #0B5C66)"))
        assertTrue(start.contains(".act { border: 1px solid var(--sm-line); background: rgba(255, 255, 255, .075); color: var(--on-surface); border-radius: 16px;"))
        assertEquals(16, PanoptesStil.RADIUS_ACT)
    }

    @Test fun schriftenUndLogoAusPanoptes() {
        // Gleiche Schriftdateien (Sora/Manrope, OFL) und gleiches Logo wie Panoptes.
        assertTrue(Files.exists(Paths.get("src/main/assets/fonts/sora.ttf")))
        assertTrue(Files.exists(Paths.get("src/main/assets/fonts/manrope.ttf")))
        assertTrue(Files.exists(Paths.get("src/main/assets/fonts/sora-OFL.txt")))
        val logo = String(Files.readAllBytes(Paths.get("src/main/res/drawable/logo_square.xml")), Charsets.UTF_8)
        val svg = String(Files.readAllBytes(Paths.get("../design/logo/square.svg")), Charsets.UTF_8)
        assertTrue(svg.contains("#0A2238") && logo.contains("#0A2238"))
        assertTrue(svg.contains("M 360 214 L 318 274") && logo.contains("M360,214L318,274"))
        assertTrue(svg.contains("#B8F33B") && logo.contains("#B8F33B"))
    }

    @Test fun modusWieThemeJs() {
        assertEquals(PanoptesStil.DUNKEL, PanoptesStil.palette("dark", true))
        assertEquals(PanoptesStil.HELL, PanoptesStil.palette("light", false))
        assertEquals(PanoptesStil.HELL, PanoptesStil.palette("auto", true))
        assertEquals(PanoptesStil.DUNKEL, PanoptesStil.palette("auto", false))
        assertEquals(PanoptesStil.DUNKEL, PanoptesStil.palette(null, true))
    }
}
