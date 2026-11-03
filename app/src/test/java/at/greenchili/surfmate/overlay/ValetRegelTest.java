package at.greenchili.surfmate.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Display-Sperre: nie in Fahrt, weicht bei R, Fahrt, Kamera, Anruf und Neustart; PIN mit Meldung, ohne Dauersperre. */
public class ValetRegelTest {

    private static ValetRegel an() {
        ValetRegel r = new ValetRegel();
        assertNull(r.anschalten("2468", null));
        return r;
    }

    @Test
    public void nieWaehrendDerFahrtEinschalten() {
        for (String grund : new String[]{"not_park", "moving", "gear_unknown", "state_unknown", "speed_unknown"}) {
            ValetRegel r = new ValetRegel();
            assertEquals(grund, "nicht_in_p", r.anschalten("2468", grund));
            assertFalse(r.aktiv());
        }
        ValetRegel r = new ValetRegel();
        assertEquals("pin_ungueltig", r.anschalten("12", null));
        assertEquals("pin_ungueltig", r.anschalten("12a4", null));
        assertEquals("pin_ungueltig", r.anschalten("123456789", null));
        assertNull(r.anschalten("1234", null));
        assertEquals("schon_an", r.anschalten("9999", null));
    }

    @Test
    public void weichtBeiGangR() {
        ValetRegel r = an();
        assertTrue(r.takt(null, false, null, false).zeigen);
        ValetRegel.Lage l = r.takt(null, true, null, false);
        assertFalse(l.aktiv);
        assertFalse(l.zeigen);
        assertEquals("rueckwaerts", l.grund);
        assertFalse(r.aktiv());
        // bleibt weg, auch wenn danach wieder P
        assertFalse(r.takt(null, false, null, false).zeigen);
    }

    @Test
    public void weichtBeiFahrtUndUnklaremZustand() {
        for (String grund : new String[]{"not_park", "moving", "gear_unknown", "state_unknown", "speed_unknown"}) {
            ValetRegel r = an();
            ValetRegel.Lage l = r.takt(grund, false, null, false);
            assertFalse(grund, l.aktiv);
            assertFalse(grund, l.zeigen);
            assertEquals("fahrt", l.grund);
        }
    }

    @Test
    public void ziehtSichBeiKameraAnrufUndNotrufZurueckOhneAufzuheben() {
        ValetRegel r = an();
        String[] kamera = {"com.byd.avm", "com.byd.panorama.view", "com.byd.rvc", "com.byd.camera"};
        for (String k : kamera) {
            ValetRegel.Lage l = r.takt(null, false, k, false);
            assertTrue(k, l.aktiv);
            assertFalse(k, l.zeigen);
            assertEquals("kamera", l.grund);
        }
        for (String k : new String[]{"com.android.dialer", "com.android.incallui", "com.byd.ecall", "com.byd.sos"}) {
            ValetRegel.Lage l = r.takt(null, false, k, false);
            assertTrue(l.aktiv);
            assertFalse(k, l.zeigen);
        }
        assertFalse(r.takt(null, false, "com.spotify.music", true).zeigen);
        assertTrue(r.takt(null, false, "com.spotify.music", false).zeigen);
        // Panoptes selbst im Vordergrund lässt die Sperre stehen
        assertTrue(r.takt(null, false, "at.greenchili.surfmate", false).zeigen);
    }

    @Test
    public void neustartHebtDieSperreAuf() {
        ValetRegel r = an();
        assertTrue(r.aktiv());
        // Nur im Speicher: ein neuer Daemon hat eine neue, leere Regel
        ValetRegel nachNeustart = new ValetRegel();
        assertFalse(nachNeustart.aktiv());
        assertFalse(nachNeustart.takt(null, false, null, false).zeigen);
        // Overlay ohne frische Bestätigung des Daemons verschwindet
        long t = 1_000_000L;
        assertTrue(ValetRegel.overlayZeigen(true, t, t + ValetRegel.HERZSCHLAG_MS));
        assertFalse(ValetRegel.overlayZeigen(true, t, t + ValetRegel.HERZSCHLAG_MS + 1));
        assertFalse(ValetRegel.overlayZeigen(true, 0, t));
        assertFalse(ValetRegel.overlayZeigen(false, t, t));
        assertFalse(ValetRegel.overlayZeigen(true, t, t - 1));
    }

    @Test
    public void pinRichtigHebtAuf() {
        ValetRegel r = an();
        ValetRegel.Versuch v = r.entsperren("2468", 10);
        assertTrue(v.entsperrt);
        assertFalse(r.aktiv());
        assertEquals("pin", r.aufgehobenGrund());
        assertEquals("nicht_aktiv", r.entsperren("2468", 20).grund);
    }

    @Test
    public void fuenfFehlversucheMeldenOhneDauersperre() {
        ValetRegel r = an();
        long t = 1_000L;
        for (int i = 1; i <= 4; i++) {
            ValetRegel.Versuch v = r.entsperren("0000", t++);
            assertFalse(v.melden);
            assertEquals("falsch", v.grund);
        }
        ValetRegel.Versuch fuenf = r.entsperren("0000", t);
        assertTrue("Meldung ans Handy nach 5", fuenf.melden);
        assertEquals(5, fuenf.fehlversuche);
        // kurz warten, aber keine dauerhafte Sperre
        assertEquals("warten", r.entsperren("2468", t + 1).grund);
        ValetRegel.Versuch spaeter = r.entsperren("2468", t + ValetRegel.WARTEN_MS + 1);
        assertTrue(spaeter.entsperrt);
    }

    @Test
    public void nachZehnFehlversuchenWiederMeldung() {
        ValetRegel r = an();
        long t = 0;
        int meldungen = 0;
        for (int i = 0; i < 10; i++) {
            t += ValetRegel.WARTEN_MS + 1;
            if (r.entsperren("1111", t).melden) meldungen++;
        }
        assertEquals(2, meldungen);
        assertTrue(r.aktiv());
    }
}
