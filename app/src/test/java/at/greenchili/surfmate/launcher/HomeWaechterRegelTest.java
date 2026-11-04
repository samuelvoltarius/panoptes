package at.greenchili.surfmate.launcher;

import static at.greenchili.surfmate.launcher.HomeWaechterRegel.Aktion.NICHTS;
import static at.greenchili.surfmate.launcher.HomeWaechterRegel.Aktion.ZURUECK_ABSTURZ;
import static at.greenchili.surfmate.launcher.HomeWaechterRegel.Aktion.ZURUECK_SCHALTER_AUS;
import static at.greenchili.surfmate.launcher.HomeWaechterRegel.Aktion.ZURUECK_TOTMANN;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class HomeWaechterRegelTest {

    @Test
    public void ausUndNichtStandardTutNichts() {
        HomeWaechterRegel r = new HomeWaechterRegel();
        assertEquals(NICHTS, r.pruefe(false, false, false, "", 0));
        assertEquals(NICHTS, r.pruefe(false, false, false, "123", 5_000));
    }

    @Test
    public void schalterAusUndNochStandardGehtSofortZurueck() {
        HomeWaechterRegel r = new HomeWaechterRegel();
        assertEquals(ZURUECK_SCHALTER_AUS, r.pruefe(false, false, true, "", 0));
        assertEquals(ZURUECK_SCHALTER_AUS, r.pruefe(false, true, true, "7", 100));
    }

    @Test
    public void totmannNachFuenfMinutenOhneBehalten() {
        HomeWaechterRegel r = new HomeWaechterRegel();
        assertEquals(NICHTS, r.pruefe(true, false, true, "10", 0));
        assertEquals(NICHTS, r.pruefe(true, false, true, "10", HomeWaechterRegel.TOTMANN_MS - 1));
        assertEquals(ZURUECK_TOTMANN, r.pruefe(true, false, true, "10", HomeWaechterRegel.TOTMANN_MS));
    }

    @Test
    public void behaltenBeendetDenTotmann() {
        HomeWaechterRegel r = new HomeWaechterRegel();
        r.pruefe(true, false, true, "10", 0);
        assertEquals(NICHTS, r.pruefe(true, true, true, "10", HomeWaechterRegel.TOTMANN_MS * 3));
    }

    @Test
    public void zweiNeustartsInEinerMinuteLoesenDenRueckfallAus() {
        HomeWaechterRegel r = new HomeWaechterRegel();
        assertEquals(NICHTS, r.pruefe(true, true, true, "10", 0));
        assertEquals(NICHTS, r.pruefe(true, true, true, "11", 10_000));   // 1. Neustart
        assertEquals(NICHTS, r.pruefe(true, true, true, "", 12_000));     // gerade tot
        assertEquals(ZURUECK_ABSTURZ, r.pruefe(true, true, true, "12", 20_000));   // 2. Neustart
    }

    @Test
    public void einNeustartOderLangsamerNeustartLoestNichtsAus() {
        HomeWaechterRegel r = new HomeWaechterRegel();
        r.pruefe(true, true, true, "10", 0);
        assertEquals(NICHTS, r.pruefe(true, true, true, "11", 10_000));
        // der nächste Neustart liegt außerhalb des 60-s-Fensters des ersten
        assertEquals(NICHTS, r.pruefe(true, true, true, "12", 10_000 + HomeWaechterRegel.FENSTER_MS + 1));
    }

    @Test
    public void ohneStandardKeinAbsturzRueckfall() {
        HomeWaechterRegel r = new HomeWaechterRegel();
        r.pruefe(true, true, false, "10", 0);
        r.pruefe(true, true, false, "11", 1_000);
        assertEquals(NICHTS, r.pruefe(true, true, false, "12", 2_000));
    }

    @Test
    public void ersteSichtungIstKeinNeustart() {
        HomeWaechterRegel r = new HomeWaechterRegel();
        assertEquals(NICHTS, r.pruefe(true, true, true, "55", 0));
        assertEquals(NICHTS, r.pruefe(true, true, true, "55", 1_000));
    }

    @Test
    public void zuruecksetzenBeginntNeu() {
        HomeWaechterRegel r = new HomeWaechterRegel();
        r.pruefe(true, false, true, "10", 0);
        r.pruefe(true, false, true, "11", 1_000);
        r.pruefe(true, false, true, "12", 2_000);
        r.zuruecksetzen();
        assertEquals(NICHTS, r.pruefe(true, false, true, "13", 3_000));
        assertEquals(NICHTS, r.pruefe(true, false, true, "13", 3_000 + HomeWaechterRegel.TOTMANN_MS - 1));
    }
}
