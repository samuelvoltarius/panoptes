package at.greenchili.surfmate.einstellungen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/** 0.1.11 (Kern 7): Ruhetage für die 12-V-Chemie-Heuristik – je Tag der kleinste Wert, 14 Tage. */
public class AkkuChemieRuheTageTest {

    @Test
    public void jeTagDerKleinsteWertZaehlt() throws Exception {
        JSONObject t = new JSONObject();
        assertTrue(AkkuChemie.ruheTagEintragen(t, "2026-10-20", 12.95, 14));
        assertFalse("tieferer Wert ändert nichts", AkkuChemie.ruheTagEintragen(t, "2026-10-20", 12.7, 14));
        assertEquals(0, AkkuChemie.tageAb(t, 13.0));
        assertTrue(AkkuChemie.ruheTagEintragen(t, "2026-10-20", 13.25, 14));
        assertEquals(1, AkkuChemie.tageAb(t, 13.0));
        assertFalse("Unsinn wird nicht gemerkt", AkkuChemie.ruheTagEintragen(t, "2026-10-21", 0.0, 14));
    }

    @Test
    public void dreiTageLfpSonstBlei() throws Exception {
        JSONObject t = new JSONObject();
        AkkuChemie.ruheTagEintragen(t, "2026-10-20", 13.2, 14);
        AkkuChemie.ruheTagEintragen(t, "2026-10-21", 13.1, 14);
        assertEquals("zwei Tage reichen nicht", "blei", AkkuChemie.ermitteln(null, null, t).chemie);
        AkkuChemie.ruheTagEintragen(t, "2026-10-22", 13.0, 14);
        assertEquals("lfp", AkkuChemie.ermitteln(null, null, t).chemie);
    }

    @Test
    public void uebergangBestandMitLfpLiveErkennungUndNullRuhetagenBleibt128() throws Exception {
        // Bestand: bisher LFP per Live-Erkennung (Ruhe-Median 13,1), noch kein belegter Ruhetag
        AkkuChemie.Ergebnis e = AkkuChemie.ermitteln(null, null, new JSONObject(), 13.1, false);
        assertEquals("lfp", e.chemie);
        assertEquals("live_median", e.grund);
        assertEquals(12.8, AkkuChemie.v12Standard(e.chemie)[0], 0);
        // Bestand: bisher LFP per DiLink 3, kein Median, 0 Ruhetage → weiter 12,8
        AkkuChemie.Ergebnis p = AkkuChemie.ermitteln("auto", null, null, Double.NaN, true);
        assertEquals("lfp", p.chemie);
        assertEquals("plattform", p.grund);
        assertEquals(12.8, AkkuChemie.v12Standard(p.chemie)[0], 0);
        // zwei Ruhetage reichen noch nicht für Blei – Hinweis LFP gilt weiter
        JSONObject zwei = new JSONObject().put("2026-10-20", 12.6).put("2026-10-21", 12.7);
        assertEquals("lfp", AkkuChemie.ermitteln(null, null, zwei, Double.NaN, true).chemie);
    }

    @Test
    public void dreiBleiRuhetageSchlagenDieUebergangsHinweise() throws Exception {
        JSONObject drei = new JSONObject().put("2026-10-20", 12.6).put("2026-10-21", 12.7).put("2026-10-22", 12.65);
        AkkuChemie.Ergebnis e = AkkuChemie.ermitteln(null, null, drei, 13.1, true);
        assertEquals("blei", e.chemie);
        assertEquals("ruhetage_blei", e.grund);
        assertEquals("ohne jeden Hinweis: Blei", "keine_hinweise",
                AkkuChemie.ermitteln(null, null, null, Double.NaN, false).grund);
        assertEquals("Nutzerwahl geht vor", "blei", AkkuChemie.ermitteln("blei", null, null, 13.2, true).chemie);
    }

    @Test
    public void nurDieNeuestenTageBleiben() throws Exception {
        JSONObject t = new JSONObject();
        for (int d = 1; d <= 20; d++) AkkuChemie.ruheTagEintragen(t, String.format("2026-10-%02d", d), 13.2, 14);
        assertEquals(14, t.length());
        assertFalse(t.has("2026-10-06"));
        assertTrue(t.has("2026-10-07"));
        assertTrue(t.has("2026-10-20"));
    }
}
