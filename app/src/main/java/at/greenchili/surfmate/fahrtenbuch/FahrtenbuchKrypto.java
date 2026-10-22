package at.greenchili.surfmate.fahrtenbuch;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Fahrtenbuch für /ev/, Ende-zu-Ende verschlüsselt (Planer-Vertrag API_FUER_SURFMATE.md §8, Version 1):
 * Einmal-Schlüsselpaar P-256 je Antwort, {@code geheim = ECDH(auto_priv, browser_pub)},
 * {@code schluessel = HKDF-SHA256(geheim, salt = anfrageId, info = "panoptes-fahrtenbuch-v1", 32 Byte)},
 * AES-256-GCM mit zufälligem 12-Byte-iv ohne Zusatzdaten (Tag hinten, wie WebCrypto). Öffentliche Schlüssel als
 * unkomprimierter Rohpunkt (65 Byte), alles base64url ohne Füllzeichen.
 *
 * <p>Der Planer sieht nur Chiffretext. Der private Schlüssel des Autos lebt nur für eine Antwort im Speicher.
 */
public final class FahrtenbuchKrypto {

    private FahrtenbuchKrypto() {}

    public static final String INFO = "panoptes-fahrtenbuch-v1";
    private static final byte[] SPKI_P256 = hex("3059301306072a8648ce3d020106082a8648ce3d030107034200");
    private static final BigInteger P = new BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16);
    private static final BigInteger B = new BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Ergebnis: {pubkey, iv, chiffretext} base64url. */
    public static final class Paket {
        public final String pubkey, iv, chiffretext;
        Paket(String p, String i, String c) { pubkey = p; iv = i; chiffretext = c; }
    }

    public static Paket verschluesseln(String browserPubB64, String anfrageId, byte[] klartext) throws Exception {
        PublicKey browser = rohpunkt(b64d(browserPubB64));
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
        KeyPair auto = g.generateKeyPair();
        byte[] geheim = ecdh(auto, browser);
        byte[] schluessel = hkdf(geheim, anfrageId.getBytes(StandardCharsets.UTF_8),
                INFO.getBytes(StandardCharsets.UTF_8), 32);
        Arrays.fill(geheim, (byte) 0);
        byte[] iv = new byte[12];
        RANDOM.nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(schluessel, "AES"), new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal(klartext);
        Arrays.fill(schluessel, (byte) 0);
        return new Paket(b64(rohpunkt((ECPublicKey) auto.getPublic())), b64(iv), b64(ct));
    }

    static byte[] ecdh(KeyPair eigen, PublicKey fremd) throws Exception {
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(eigen.getPrivate());
        ka.doPhase(fremd, true);
        return ka.generateSecret();
    }

    /** HKDF-SHA256 (RFC 5869). */
    static byte[] hkdf(byte[] ikm, byte[] salt, byte[] info, int laenge) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt.length == 0 ? new byte[32] : salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        byte[] out = new byte[laenge];
        byte[] t = new byte[0];
        int pos = 0;
        for (int i = 1; pos < laenge; i++) {
            mac.update(t);
            mac.update(info);
            mac.update((byte) i);
            t = mac.doFinal();
            int n = Math.min(t.length, laenge - pos);
            System.arraycopy(t, 0, out, pos, n);
            pos += n;
        }
        return out;
    }

    /** Unkomprimierter Rohpunkt (65 Byte) → Schlüssel; prüft, dass der Punkt auf P-256 liegt. */
    static PublicKey rohpunkt(byte[] roh) throws Exception {
        if (roh == null || roh.length != 65 || roh[0] != 0x04) throw new IllegalArgumentException("pubkey");
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(roh, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(roh, 33, 65));
        if (x.compareTo(P) >= 0 || y.compareTo(P) >= 0) throw new IllegalArgumentException("pubkey");
        BigInteger links = y.multiply(y).mod(P);
        BigInteger rechts = x.pow(3).subtract(x.multiply(BigInteger.valueOf(3))).add(B).mod(P);
        if (!links.equals(rechts)) throw new IllegalArgumentException("pubkey nicht auf P-256");
        byte[] spki = new byte[SPKI_P256.length + 65];
        System.arraycopy(SPKI_P256, 0, spki, 0, SPKI_P256.length);
        System.arraycopy(roh, 0, spki, SPKI_P256.length, 65);
        return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(spki));
    }

    static byte[] rohpunkt(ECPublicKey k) {
        byte[] out = new byte[65];
        out[0] = 0x04;
        kopiere32(k.getW().getAffineX(), out, 1);
        kopiere32(k.getW().getAffineY(), out, 33);
        return out;
    }

    private static void kopiere32(BigInteger v, byte[] ziel, int ab) {
        byte[] b = v.toByteArray();
        int n = Math.min(b.length, 32);
        System.arraycopy(b, b.length - n, ziel, ab + 32 - n, n);
    }

    public static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static byte[] b64d(String s) {
        if (s == null) return null;
        return Base64.getUrlDecoder().decode(s.trim().replace('+', '-').replace('/', '_').replace("=", ""));
    }

    static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }
}
