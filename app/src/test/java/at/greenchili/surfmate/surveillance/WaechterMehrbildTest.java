package at.greenchili.surfmate.surveillance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 0.1.38.8 Wächter-KI A1/B1/B4: scharfe Mehrbild-Bestätigung je Klasse, strengere Fahrzeuge
 * nachts und die Größensperre im Kamera-Bezug für den 640er-Ausschnitt.
 *
 * <p>Die Fälle sind den Feldzahlen vom 03./04.11.2026 nachgebildet (nur Klasse, Konfidenz,
 * Bildanzahl – keine Bilder).
 */
public class WaechterMehrbildTest {

    private static final long T0 = 1_000_000L;

    // ---------- B1: Klassenregel ----------

    @Test
    public void fahrzeugNieNachEinemBild() {
        // Feld: LKW 0,43 nach 1 Bild → Aufnahme. Jetzt nicht mehr, auch nicht bei hoher Konfidenz.
        assertFalse(MultiBatchConfirmationShadow.bestaetigt(7, 0.43f, 1));
        assertFalse(MultiBatchConfirmationShadow.bestaetigt(2, 0.89f, 1));
        assertFalse(MultiBatchConfirmationShadow.bestaetigt(2, 0.89f, 2));
        assertTrue(MultiBatchConfirmationShadow.bestaetigt(2, 0.40f, 3));
        assertFalse("Rad wie Fahrzeug", MultiBatchConfirmationShadow.bestaetigt(1, 0.90f, 2));
        assertTrue(MultiBatchConfirmationShadow.bestaetigt(3, 0.90f, 3));
    }

    @Test
    public void personAbHalbEinBildDarunterZwei() {
        // Feld: echte Nahfälle 0,85–0,96 bestätigen weiter sofort.
        assertTrue(MultiBatchConfirmationShadow.bestaetigt(0, 0.93f, 1));
        assertTrue("Grenze inklusive", MultiBatchConfirmationShadow.bestaetigt(0, 0.50f, 1));
        // Feld: Einzelbild-Treffer auf Passanten/Schatten 0,37–0,43.
        assertFalse(MultiBatchConfirmationShadow.bestaetigt(0, 0.37f, 1));
        assertFalse(MultiBatchConfirmationShadow.bestaetigt(0, 0.49f, 1));
        assertTrue(MultiBatchConfirmationShadow.bestaetigt(0, 0.37f, 2));
    }

    @Test
    public void tiereUndUnbekanntWieBisher() {
        assertTrue(MultiBatchConfirmationShadow.bestaetigt(16, 0.30f, 1));
        assertTrue(MultiBatchConfirmationShadow.bestaetigt(60, 0.30f, 1));
    }

    // ---------- B4: Nacht ----------

    @Test
    public void nachtsZaehlenSchwacheFahrzeugbilderNicht() {
        assertFalse(MultiBatchConfirmationShadow.zaehltAlsBild(2, 0.42f, true));
        assertFalse(MultiBatchConfirmationShadow.zaehltAlsBild(1, 0.49f, true));
        assertTrue(MultiBatchConfirmationShadow.zaehltAlsBild(2, 0.50f, true));
        assertTrue("tagsüber unverändert", MultiBatchConfirmationShadow.zaehltAlsBild(2, 0.30f, false));
    }

    @Test
    public void nachtsPersonenUnveraendert() {
        // 28.10. 18:49: Projektinhaber dunkel und nah mit 0,38 – darf nachts nicht herausfallen.
        assertTrue(MultiBatchConfirmationShadow.zaehltAlsBild(0, 0.38f, true));
        assertTrue(MultiBatchConfirmationShadow.zaehltAlsBild(16, 0.30f, true));
    }

    // ---------- Zusammenspiel mit dem Zähler ----------

    @Test
    public void fahrzeugBraucht3VerschiedeneBilderImFenster() {
        MultiBatchConfirmationShadow z = new MultiBatchConfirmationShadow();
        assertFalse(MultiBatchConfirmationShadow.bestaetigt(2, 0.6f, z.observe(5, 2, T0, T0)));
        assertFalse("gleiches Bild doppelt zählt nicht",
                MultiBatchConfirmationShadow.bestaetigt(2, 0.6f, z.observe(5, 2, T0, T0)));
        assertFalse(MultiBatchConfirmationShadow.bestaetigt(2, 0.6f, z.observe(5, 2, T0 + 500, T0)));
        assertTrue(MultiBatchConfirmationShadow.bestaetigt(2, 0.6f, z.observe(5, 2, T0 + 1000, T0)));
    }

    @Test
    public void vorbeifahrendesAutoMitLueckeBestaetigtNicht() {
        // Drei Einzeltreffer, aber über mehr als 4,5 s verstreut (Schatten-/Scheinwerfer-Muster).
        MultiBatchConfirmationShadow z = new MultiBatchConfirmationShadow();
        z.observe(9, 2, T0, T0);
        z.observe(9, 2, T0 + 3_000, T0);
        int n = z.observe(9, 2, T0 + 8_000, T0);
        assertFalse(MultiBatchConfirmationShadow.bestaetigt(2, 0.7f, n));
    }

    @Test
    public void schwachePersonMitZweitemBildBestaetigt() {
        MultiBatchConfirmationShadow z = new MultiBatchConfirmationShadow();
        assertFalse(MultiBatchConfirmationShadow.bestaetigt(0, 0.40f, z.observe(3, 0, T0, T0)));
        assertTrue(MultiBatchConfirmationShadow.bestaetigt(0, 0.40f, z.observe(3, 0, T0 + 300, T0)));
    }

    // ---------- A1: Größensperre im 640er-Ausschnitt ----------

    @Test
    public void mosaikUndFehlendeAbbildungUnveraendert() {
        assertEquals(0.18f, SurveillanceEngineGpu.groessenSperreImAusschnitt(0.18f, false, true, 0.25f), 1e-6f);
        assertEquals(0.18f, SurveillanceEngineGpu.groessenSperreImAusschnitt(0.18f, true, false, 0.25f), 1e-6f);
        assertEquals(0.18f, SurveillanceEngineGpu.groessenSperreImAusschnitt(0.18f, true, true, 0f), 1e-6f);
        assertEquals(0.18f, SurveillanceEngineGpu.groessenSperreImAusschnitt(0.18f, true, true, Float.NaN), 1e-6f);
    }

    @Test
    public void ausschnittHaeltDieEntfernungDesReglers() {
        // 640 px Ausschnitt, ay = 0,25 → 160 der 240 Kachel-Zeilen: gleiche Person ist 1,5× so hoch.
        assertEquals(0.27f, SurveillanceEngineGpu.groessenSperreImAusschnitt(0.18f, true, true, 0.25f), 1e-5f);
        // Gespiegelte Rolle (negativer Maßstab) gleich behandeln.
        assertEquals(0.27f, SurveillanceEngineGpu.groessenSperreImAusschnitt(0.18f, true, true, -0.25f), 1e-5f);
    }

    @Test
    public void ausschnittNieUnerfuellbar() {
        assertTrue(SurveillanceEngineGpu.groessenSperreImAusschnitt(0.9f, true, true, 0.05f) <= 0.95f);
        // Ausschnitt, der die ganze Kachel abdeckt: kein Umrechnen nötig.
        assertEquals(0.18f, SurveillanceEngineGpu.groessenSperreImAusschnitt(0.18f, true, true, 0.375f), 1e-6f);
    }
}
