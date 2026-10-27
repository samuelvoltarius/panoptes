package at.greenchili.surfmate.bruecke;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/**
 * 0.1.20 Brücke: BLAKE2s gegen RFC 7693 bzw. hashlib, Noise IK gegen eine unabhängige Python-Referenz
 * (hashlib.blake2s + cryptography X25519/ChaCha20Poly1305; Skript in der Übergabe-Notiz). Feste Schlüssel 1..128.
 */
public class BrueckeNoiseTest {

    static byte[] hex(String s) {
        byte[] r = new byte[s.length() / 2];
        for (int i = 0; i < r.length; i++) r[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return r;
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    static byte[] folge(int von) {
        byte[] r = new byte[32];
        for (int i = 0; i < 32; i++) r[i] = (byte) (von + i);
        return r;
    }

    @Test
    public void blake2sTestwerte() {
        assertEquals("508c5e8c327c14e2e1a72ba34eeb452f37458b209ed63a294d999b4c86675982",
                hex(Blake2s.hash("abc".getBytes(StandardCharsets.US_ASCII))));   // RFC 7693 Anhang B
        assertEquals("69217a3079908094e11121d042354a7c1f55b6482ca1a51e1b250dfd1ed0eef9", hex(Blake2s.hash(new byte[0])));
        byte[] lang = new byte[768];
        for (int i = 0; i < lang.length; i++) lang[i] = (byte) i;
        assertEquals("b928a17862e211e99759ba8819280803a914cd3dee7c5d711a3b5185aa96a7b3", hex(Blake2s.hash(lang)));
        byte[] k = new byte[70];
        java.util.Arrays.fill(k, (byte) 'k');
        assertEquals("7a7b08c4a1142171acba52ab828082256b1bb919b0d9a9e3fa0a90655d86b938",
                hex(Blake2s.hmac(k, "daten".getBytes(StandardCharsets.US_ASCII))));
    }

    static final String MSG1 = "64b101b1d0be5a8704bd078f9895001fc03e8e9f9522f188dd128d9846d48466822e8514e6389169bdfd10afecae5bded9cda3f0157ac1d03cd3620b3bb73a866421c9c63a37995f11597388d964de276fadba84a508d06f472a78d8cfd8b141aa1c90feda248edc32ed";
    static final String MSG2 = "244fe3b963e899dd295baffce248d3530f3a9a7479ba063002680ebfe7adad4985b4c79c930248ce911bbcaf33c3c69b39a64cf31427af67fc141b";

    @Test
    public void noiseIkWieDieReferenz() throws Exception {
        NoiseIK.Schluessel handy = new NoiseIK.Schluessel(folge(1)), auto = new NoiseIK.Schluessel(folge(33));
        NoiseIK.Schluessel ei = new NoiseIK.Schluessel(folge(65)), er = new NoiseIK.Schluessel(folge(97));
        NoiseIK i = NoiseIK.initiator(handy, auto.oeffentlich, () -> ei);
        NoiseIK r = NoiseIK.responder(auto, () -> er);

        byte[] m1 = i.schreibeNachricht1("hallo auto".getBytes(StandardCharsets.UTF_8));
        assertEquals(MSG1, hex(m1));
        assertEquals("hallo auto", new String(r.leseNachricht1(m1), StandardCharsets.UTF_8));
        assertArrayEquals("Auto kennt jetzt den Handy-Schlüssel", handy.oeffentlich, r.gegenseite());

        byte[] m2 = r.schreibeNachricht2("hallo handy".getBytes(StandardCharsets.UTF_8));
        assertEquals(MSG2, hex(m2));
        assertEquals("hallo handy", new String(i.leseNachricht2(m2), StandardCharsets.UTF_8));
        assertEquals("de8da9cf6ac61fa72ef3cc4933143c2a9f103cd538c4c473deebbf3c4f9f2b6c", hex(r.handshakeHash()));
        assertTrue(i.fertig() && r.fertig());

        byte[] t1 = i.verschluesseln("anfrage".getBytes(StandardCharsets.UTF_8));
        assertEquals("18060777b4ff4a1e3894653ebd2ec49d2204bc21b8d463", hex(t1));
        assertEquals("anfrage", new String(r.entschluesseln(t1), StandardCharsets.UTF_8));
        byte[] t2 = r.verschluesseln("antwort".getBytes(StandardCharsets.UTF_8));
        assertEquals("0440d8d92c344a587b13268d281c6b369c961761803ec5", hex(t2));
        assertEquals("antwort", new String(i.entschluesseln(t2), StandardCharsets.UTF_8));
        byte[] t3 = i.verschluesseln("anfrage".getBytes(StandardCharsets.UTF_8));
        assertEquals("Nonce zählt hoch", "58d50a4f514a7b1794a4d0562448371c6709dd758e8c84", hex(t3));
        try {
            r.entschluesseln(t1);   // Wiedereinspielen: Nonce passt nicht mehr
            fail();
        } catch (GeneralSecurityException erwartet) { }
    }

    @Test
    public void fremderAutoSchluesselScheitert() throws Exception {
        NoiseIK.Schluessel handy = NoiseIK.Schluessel.neu(), auto = NoiseIK.Schluessel.neu(), falsch = NoiseIK.Schluessel.neu();
        NoiseIK i = NoiseIK.initiator(handy, falsch.oeffentlich);   // Server spielt „Auto“ mit eigenem Schlüssel
        NoiseIK r = NoiseIK.responder(auto);
        try {
            r.leseNachricht1(i.schreibeNachricht1(new byte[0]));
            fail("Handshake mit falschem Auto-Schlüssel angenommen");
        } catch (GeneralSecurityException erwartet) { }
    }

    @Test
    public void veraenderterChiffretextScheitert() throws Exception {
        NoiseIK.Schluessel handy = NoiseIK.Schluessel.neu(), auto = NoiseIK.Schluessel.neu();
        NoiseIK i = NoiseIK.initiator(handy, auto.oeffentlich);
        NoiseIK r = NoiseIK.responder(auto);
        r.leseNachricht1(i.schreibeNachricht1(null));
        i.leseNachricht2(r.schreibeNachricht2(null));
        byte[] c = i.verschluesseln(new byte[] {1, 2, 3});
        c[0] ^= 1;
        try {
            r.entschluesseln(c);
            fail();
        } catch (GeneralSecurityException erwartet) { }
    }
}
