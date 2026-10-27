package at.greenchili.surfmate.handy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

/** 0.1.18 Handy-Vertrag §3.1 + Ratenlimit: Signatur gültig/abgelaufen/Nonce doppelt/falscher Schlüssel. */
public class HandySignaturTest {

    static KeyPair paar(String kurve) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec(kurve));
        return g.generateKeyPair();
    }

    static String pub(KeyPair p) {
        return Base64.getEncoder().encodeToString(p.getPublic().getEncoded());
    }

    static String sig(KeyPair p, String text) throws Exception {
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(p.getPrivate());
        s.update(text.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(s.sign());
    }

    static final String K = "k-1234abcd";
    static final long T = 1_800_000_000_000L;

    @Test
    public void gueltigEinmalDannNonceVerbraucht() throws Exception {
        KeyPair p = paar("secp256r1");
        HandySignatur h = new HandySignatur();
        String n = "nonce-0001-abcdef";
        String s = sig(p, HandySignatur.nachricht("aufsperren", K, T, n));
        assertEquals("panoptes-handy-v1|aufsperren|" + K + "|" + T + "|" + n,
                HandySignatur.nachricht("aufsperren", K, T, n));
        assertNull(h.pruefe(pub(p), "aufsperren", K, T, n, s, T + 5_000));
        assertEquals("Wiederholung", HandySignatur.GRUND_NONCE, h.pruefe(pub(p), "aufsperren", K, T, n, s, T + 6_000));
    }

    @Test
    public void abgelaufenFalscherSchluesselFalscherText() throws Exception {
        KeyPair p = paar("secp256r1");
        KeyPair fremd = paar("secp256r1");
        HandySignatur h = new HandySignatur();
        String n = "nonce-0002-abcdef";
        String s = sig(p, HandySignatur.nachricht("aufsperren", K, T, n));
        assertEquals(HandySignatur.GRUND_ZEIT, h.pruefe(pub(p), "aufsperren", K, T, n, s, T + 30_001));
        assertEquals(HandySignatur.GRUND_ZEIT, h.pruefe(pub(p), "aufsperren", K, T, n, s, T - 30_001));
        assertEquals("falscher Schlüssel", HandySignatur.GRUND_SIGNATUR,
                h.pruefe(pub(fremd), "aufsperren", K, T, n, s, T));
        assertEquals("andere Kopplung", HandySignatur.GRUND_SIGNATUR,
                h.pruefe(pub(p), "aufsperren", "k-andere", T, n, s, T));
        assertEquals("andere Aktion", HandySignatur.GRUND_SIGNATUR,
                h.pruefe(pub(p), "zusperren", K, T, n, s, T));
        assertEquals("kaputte Signatur", HandySignatur.GRUND_SIGNATUR,
                h.pruefe(pub(p), "aufsperren", K, T, n, "AAAA", T));
        assertEquals("kaputte Nonce", HandySignatur.GRUND_SIGNATUR,
                h.pruefe(pub(p), "aufsperren", K, T, "x|y", s, T));
        // Fehlschläge verbrauchen die Nonce nicht – das echte Signat geht danach noch
        assertNull(h.pruefe(pub(p), "aufsperren", K, T, n, s, T));
    }

    @Test
    public void nurP256() throws Exception {
        assertNotNull(HandySignatur.schluessel(pub(paar("secp256r1"))));
        assertNull("P-384", HandySignatur.schluessel(pub(paar("secp384r1"))));
        KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
        rsa.initialize(2048);
        assertNull("RSA", HandySignatur.schluessel(
                Base64.getEncoder().encodeToString(rsa.generateKeyPair().getPublic().getEncoded())));
        assertNull(HandySignatur.schluessel("kein base64 !!"));
        assertEquals(HandySignatur.GRUND_KEIN_GERAET, new HandySignatur().pruefe(null, "aufsperren", K, T,
                "nonce-0003-abcdef", "AAAA", T));
    }

    @Test
    public void nonceSpeicherBegrenzt() throws Exception {
        KeyPair p = paar("secp256r1");
        HandySignatur h = new HandySignatur();
        String erste = "nonce-erste-000";
        assertNull(h.pruefe(pub(p), "a", K, T, erste, sig(p, HandySignatur.nachricht("a", K, T, erste)), T));
        for (int i = 0; i < HandySignatur.NONCES; i++) {
            String n = "nonce-" + String.format("%08d", i);
            assertNull(h.pruefe(pub(p), "a", K, T, n, sig(p, HandySignatur.nachricht("a", K, T, n)), T));
        }
        // die älteste ist nach 500 weiteren vergessen; die Zeitprüfung (±30 s) schützt trotzdem vor Wiederholung
        assertNull(h.pruefe(pub(p), "a", K, T, erste, sig(p, HandySignatur.nachricht("a", K, T, erste)), T));
    }

    @Test
    public void ratenlimit() {
        HandyRatenlimit r = new HandyRatenlimit();
        for (int i = 0; i < 6; i++) assertTrue(r.erlauben(K, false, T + i));
        assertFalse("7. in der Minute", r.erlauben(K, false, T + 10));
        assertTrue("andere Kopplung", r.erlauben("k-andere", false, T + 10));
        assertTrue("nach einer Minute", r.erlauben(K, false, T + 60_001));

        HandyRatenlimit u = new HandyRatenlimit();
        assertTrue(u.erlauben(K, true, T));
        assertTrue(u.erlauben(K, true, T + 61_000));
        assertTrue(u.erlauben(K, true, T + 122_000));
        assertFalse("4. Aufsperren in 10 min", u.erlauben(K, true, T + 183_000));
        assertTrue("Zusperren geht weiter", u.erlauben(K, false, T + 183_000));
        assertTrue("nach 10 min", u.erlauben(K, true, T + 600_001));
    }
}
