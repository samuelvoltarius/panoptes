package at.greenchili.surfmate.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.net.InetAddress;

/** 0.1.43.4 (F7): Datei per QR – Einmal-Kennung, Ablauf, nur lokales Netz. Rein, mit eigener Uhr. */
public class DateiQrTest {

    private final long[] jetzt = {1_000_000L};
    private final DateiQr qr = new DateiQr(() -> jetzt[0]);

    @Test
    public void kennungIst128BitHexUndJedeSitzungNeu() {
        DateiQr.Sitzung a = qr.neuHochladen();
        assertTrue(a.kennung.matches("[0-9a-f]{32}"));
        DateiQr.Sitzung b = qr.neuHochladen();
        assertNotEquals(a.kennung, b.kennung);
        assertEquals(DateiQr.GUELTIG_MS, a.bis - 1_000_000L);
    }

    @Test
    public void eineNeueSitzungMachtDieAlteUngueltig() {
        DateiQr.Sitzung a = qr.neuHochladen();
        DateiQr.Sitzung b = qr.neuHochladen();
        assertEquals("falsch", qr.pruefe(a.kennung, DateiQr.HOCHLADEN).grund);
        assertTrue(qr.pruefe(b.kennung, DateiQr.HOCHLADEN).ok());
    }

    @Test
    public void ablaufNachZweiMinuten() {
        DateiQr.Sitzung s = qr.neuHochladen();
        jetzt[0] += DateiQr.GUELTIG_MS;
        assertTrue("genau am Ende noch gültig", qr.pruefe(s.kennung, DateiQr.HOCHLADEN).ok());
        jetzt[0] += 1;
        DateiQr.Pruefung p = qr.pruefe(s.kennung, DateiQr.HOCHLADEN);
        assertEquals("abgelaufen", p.grund);
        assertEquals(410, p.http);
        assertEquals(DateiQr.ABGELAUFEN, qr.aktuell().zustand());
    }

    @Test
    public void einmalGiltEinmal() {
        DateiQr.Sitzung s = qr.neuHochladen();
        assertTrue(qr.beginne(s, "id1"));
        assertFalse("zweiter Start mit derselben Kennung", qr.beginne(s, "id2"));
        assertTrue(qr.istUpload(s, "id1"));
        assertFalse(qr.istUpload(s, "fremd"));
        qr.verbrauche(s, "foto.jpg");
        assertEquals(DateiQr.FERTIG, qr.aktuell().zustand());
        assertEquals("foto.jpg", qr.aktuell().ergebnisName());
        assertEquals("verbraucht", "abgelaufen", qr.pruefe(s.kennung, DateiQr.HOCHLADEN).grund);
        assertFalse("nach dem Verbrauch kein Teil mehr", qr.istUpload(s, "id1"));
    }

    @Test
    public void zweckMussPassen() {
        DateiQr.Sitzung h = qr.neuHolen("clip.mp4", quelle("clip.mp4", 3));
        assertEquals("falsch", qr.pruefe(h.kennung, DateiQr.HOCHLADEN).grund);
        assertTrue(qr.pruefe(h.kennung, DateiQr.HOLEN).ok());
        assertTrue("null = egal (info)", qr.pruefe(h.kennung, null).ok());
        assertEquals("clip.mp4", h.anzeigeName);
        assertEquals(3, h.groesseHolen());
    }

    @Test
    public void falscheKennungenZaehlenUndSperrenKurz() {
        DateiQr.Sitzung s = qr.neuHochladen();
        for (int i = 0; i < DateiQr.FEHLER_MAX; i++) {
            assertEquals("falsch", qr.pruefe("0000000000000000000000000000000" + (i % 10), DateiQr.HOCHLADEN).grund);
        }
        DateiQr.Pruefung gesperrt = qr.pruefe(s.kennung, DateiQr.HOCHLADEN);
        assertEquals("zu_viele", gesperrt.grund);
        assertEquals(429, gesperrt.http);
        jetzt[0] += DateiQr.FEHLER_FENSTER_MS + 1_000;
        assertEquals("die Sitzung selbst ist inzwischen abgelaufen", "abgelaufen", qr.pruefe(s.kennung, DateiQr.HOCHLADEN).grund);
        DateiQr.Sitzung neu = qr.neuHochladen();
        assertTrue("nach dem Fenster wieder frei", qr.pruefe(neu.kennung, DateiQr.HOCHLADEN).ok());
    }

    @Test
    public void ohneSitzungUndOhneKennungNichts() {
        assertEquals("falsch", qr.pruefe("x", DateiQr.HOCHLADEN).grund);
        assertEquals("falsch", qr.pruefe(null, DateiQr.HOCHLADEN).grund);
        assertNull(qr.aktuell());
        qr.neuHochladen();
        qr.beenden();
        assertNull(qr.aktuell());
    }

    @Test
    public void restSekundenRunden() {
        DateiQr.Sitzung s = qr.neuHochladen();
        assertEquals(120, qr.restSekunden(s));
        jetzt[0] += 3_000;
        assertEquals(117, qr.restSekunden(s));
        jetzt[0] += 500_000;
        assertEquals(0, qr.restSekunden(s));
    }

    @Test
    public void nurLokalesNetzZaehlt() throws Exception {
        for (String ok : new String[] {"192.168.0.12", "192.168.43.2", "10.0.0.5", "172.16.1.1", "172.31.255.1", "169.254.1.1"}) {
            assertTrue(ok, DateiQr.lokalesNetz(ok));
        }
        for (String nein : new String[] {"127.0.0.1", "100.64.0.1", "100.100.1.1", "100.100.2.3", "8.8.8.8", "172.32.0.1",
                "172.15.0.1", "0.0.0.0", "224.0.0.1", "192.169.0.1", "", "abc", "192.168.0", "::1"}) {
            assertFalse(nein, DateiQr.lokalesNetz(nein));
        }
        assertFalse("IPv6 nie", DateiQr.lokalesNetz(InetAddress.getByName("fe80::1")));
        assertFalse(DateiQr.lokalesNetz((InetAddress) null));
        assertFalse(DateiQr.lokalesNetz((String) null));
    }

    @Test
    public void urlTragtKennungNurImFragment() {
        String u = DateiQr.url("192.168.0.12", 8180, "ab".repeat(16), DateiQr.HOCHLADEN);
        assertEquals("http://192.168.0.12:8180/shared/datei-handy.html#k=" + "ab".repeat(16) + "&z=u", u);
        assertTrue(DateiQr.url("10.0.0.1", 8180, "ab".repeat(16), DateiQr.HOLEN).endsWith("&z=h"));
        assertFalse("keine Kennung vor dem #", u.substring(0, u.indexOf('#')).contains("ab"));
    }

    private static DateiQr.Quelle quelle(String name, long laenge) {
        return new DateiQr.Quelle() {
            public String name() { return name; }
            public long laenge() { return laenge; }
            public java.io.InputStream oeffnen() { return new ByteArrayInputStream(new byte[(int) laenge]); }
        };
    }
}
