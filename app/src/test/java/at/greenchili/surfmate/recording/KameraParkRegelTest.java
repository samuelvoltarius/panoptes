package at.greenchili.surfmate.recording;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class KameraParkRegelTest {

    private static KameraParkRegel.Lage parkAnwesend() {
        KameraParkRegel.Lage l = new KameraParkRegel.Lage();
        l.schalterAn = true;
        l.zuendungAn = true;
        l.gangP = true;
        return l;
    }

    @Test
    public void erstNachKarenzGeschlossen() {
        KameraParkRegel r = new KameraParkRegel();
        KameraParkRegel.Lage l = parkAnwesend();
        assertFalse(r.freigeben(l, 0));
        assertFalse(r.freigeben(l, KameraParkRegel.KARENZ_MS - 1));
        assertTrue(r.freigeben(l, KameraParkRegel.KARENZ_MS));
        assertTrue(r.freigeben(l, KameraParkRegel.KARENZ_MS * 5));
    }

    @Test
    public void gangAusPSofortWeg() {
        KameraParkRegel r = new KameraParkRegel();
        KameraParkRegel.Lage l = parkAnwesend();
        r.freigeben(l, 0);
        assertTrue(r.freigeben(l, KameraParkRegel.KARENZ_MS));
        l.gangP = false;
        assertFalse(r.freigeben(l, KameraParkRegel.KARENZ_MS + 1));
        // zurück nach P: Karenz beginnt neu
        l.gangP = true;
        assertFalse(r.freigeben(l, KameraParkRegel.KARENZ_MS + 2));
        assertTrue(r.freigeben(l, KameraParkRegel.KARENZ_MS * 2 + 2));
    }

    @Test
    public void jederEinzelneHalterVerhindertFreigabe() {
        for (int i = 0; i < 9; i++) {
            KameraParkRegel r = new KameraParkRegel();
            KameraParkRegel.Lage l = parkAnwesend();
            switch (i) {
                case 0: l.schalterAn = false; break;
                case 1: l.zuendungAn = false; break;
                case 2: l.gangP = false; break;
                case 3: l.dauerhafteKamera = true; break;
                case 4: l.aufnahmeOderWaechter = true; break;
                case 5: l.streamAktiv = true; break;
                case 6: l.kameraAnsichtAktiv = true; break;
                case 7: l.totwinkelAnsichtSichtbar = true; break;
                default: l.totwinkelKalibrierung = true; break;
            }
            assertFalse("Fall " + i, r.freigeben(l, 0));
            assertFalse("Fall " + i + " nach Karenz", r.freigeben(l, KameraParkRegel.KARENZ_MS * 3));
        }
    }

    @Test
    public void streamOderWaechterWeckenSofort() {
        KameraParkRegel r = new KameraParkRegel();
        KameraParkRegel.Lage l = parkAnwesend();
        r.freigeben(l, 0);
        assertTrue(r.freigeben(l, KameraParkRegel.KARENZ_MS));
        l.streamAktiv = true;
        assertFalse(r.freigeben(l, KameraParkRegel.KARENZ_MS + 1));
        l.streamAktiv = false;
        l.aufnahmeOderWaechter = true;
        assertFalse(r.freigeben(l, KameraParkRegel.KARENZ_MS + 2));
    }

    @Test
    public void zuruecksetzenStartetKarenzNeu() {
        KameraParkRegel r = new KameraParkRegel();
        KameraParkRegel.Lage l = parkAnwesend();
        r.freigeben(l, 0);
        r.zuruecksetzen();
        assertFalse(r.freigeben(l, KameraParkRegel.KARENZ_MS));
    }

    @Test
    public void ohneLageNieFreigeben() {
        assertFalse(new KameraParkRegel().freigeben(null, 0));
    }
}
