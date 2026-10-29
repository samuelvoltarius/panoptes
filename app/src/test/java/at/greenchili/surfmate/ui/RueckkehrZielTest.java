package at.greenchili.surfmate.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.byd.MessageOverlayController;

import org.junit.Test;

/** 0.1.28 Rückkehr-Karte: „Ansehen“ öffnet die Aufnahmen-Seite mit genau dieser Aufnahme; Overlay-Befehl sicher gequotet. */
public class RueckkehrZielTest {

    @Test
    public void aufnahmePfadNurMitHarmloserKennung() {
        assertEquals("/events?filter=sentry&id=rec_2026-10-29_0815.mp4", NavigateZiel.aufnahmePfad("rec_2026-10-29_0815.mp4"));
        assertEquals("/events?filter=sentry", NavigateZiel.aufnahmePfad("a&b=c"));
        assertEquals("/events?filter=sentry", NavigateZiel.aufnahmePfad(null));
        assertFalse(NavigateZiel.aufnahmeGueltig("../x"));
        assertFalse(NavigateZiel.aufnahmeGueltig(""));
        assertNull("kein festes Ziel", NavigateZiel.pfad(NavigateZiel.ZIEL_AUFNAHME));
    }

    @Test
    public void overlayBefehl() {
        String c = MessageOverlayController.rueckkehrCommand("aufnahmen", "Während du weg warst", "3 Aufnahmen",
                "Ansehen", "Schließen", "rec_1.mp4", 120);
        assertTrue(c, c.contains("--es kind rueckkehr"));
        assertTrue(c, c.contains("--es art 'aufnahmen'"));
        assertTrue(c, c.contains("--es aufnahme 'rec_1.mp4'"));
        assertTrue(c, c.contains("--ei timeoutSec 120"));
        String b = MessageOverlayController.rueckkehrCommand("nichts", "Wächter war scharf – nichts passiert", "",
                "A", "S", "x;rm -rf /", 1);
        assertTrue("ungültige Kennung fällt weg", b.contains("--es aufnahme ''"));
        assertTrue(b.contains("--ei timeoutSec 5"));
        String q = MessageOverlayController.rueckkehrCommand("nicht_scharf", "it's", "a'b", "A", "S", null, 8);
        assertTrue("Hochkomma sicher gequotet", q.contains("'it'\\''s'"));
        assertNull(MessageOverlayController.rueckkehrCommand("hupe", "x", "y", "A", "S", null, 8));
    }
}
