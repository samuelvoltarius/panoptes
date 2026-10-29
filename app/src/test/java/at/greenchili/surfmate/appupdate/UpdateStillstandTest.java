package at.greenchili.surfmate.appupdate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import at.greenchili.surfmate.byd.Stillstand;

import org.junit.Test;

/**
 * 0.1.28.1 Befund Deploy (2) am Auto (0.1.27): geparkt, power 0, P, ACC lange aus – der Updater meldete trotzdem
 * „faehrt“ wegen eines Tempo-Restwerts der letzten Fahrt. Mit byd.Stillstand installierbar; frisches Tempo sperrt weiter.
 */
public class UpdateStillstandTest {

    private static UpdateRegel.Lage lage() throws Exception {
        UpdateRegel.Lage l = AppUpdateTest.lage(43, Freigabe.lies(AppUpdateTest.vj(44, "0.1.28", "stabil", "v0.1.28", AppUpdateTest.SHA0)));
        l.autoAus = true;
        return l;
    }

    @Test
    public void restwertImStandSperrtNichtMehr() throws Exception {
        UpdateRegel.Lage l = lage();
        l.kmh = 1.2;   // vorher: „faehrt“
        assertEquals("faehrt", UpdateRegel.nichtWeil(l));
        l.kmh = Stillstand.tempo(1.2, 2 * 3_600_000L, true);
        assertNull(UpdateRegel.nichtWeil(l));
    }

    @Test
    public void frischesTempoSperrtWeiter() throws Exception {
        UpdateRegel.Lage l = lage();
        l.kmh = Stillstand.tempo(1.2, -1, true);          // ACC an / unbekannt
        assertEquals("faehrt", UpdateRegel.nichtWeil(l));
        l.kmh = Stillstand.tempo(1.2, 5_000L, true);       // ACC erst 5 s aus
        assertEquals("faehrt", UpdateRegel.nichtWeil(l));
        l.kmh = Stillstand.tempo(8, 60_000L, false);       // nicht P
        assertNotNull(UpdateRegel.nichtWeil(l));
    }

    @Test
    public void zweiterNachweisMitRestwert() {
        // Varianten ohne power_level: ACC aus + 0 km/h seit 2 min – mit Restwert vorher nie erfüllt
        assertEquals(-1, UpdateRegel.zweiterNachweisSeit(-1, Boolean.TRUE, 1.2, 1000));
        assertEquals(1000, UpdateRegel.zweiterNachweisSeit(-1, Boolean.TRUE, Stillstand.tempo(1.2, 60_000L, true), 1000));
    }
}
