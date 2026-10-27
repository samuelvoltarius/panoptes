package at.greenchili.surfmate.bruecke;

import com.google.crypto.tink.aead.internal.InsecureNonceChaCha20Poly1305;
import com.google.crypto.tink.subtle.X25519;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/**
 * Noise {@code Noise_IK_25519_ChaChaPoly_BLAKE2s} (Noise-Spezifikation Rev. 34) für die Panoptes-Brücke
 * (BRUECKE_VERTRAG §2.5). Keine Eigenbau-Krypto: X25519 und ChaCha20-Poly1305 kommen aus Tink (liegt ohnehin im APK),
 * nur BLAKE2s selbst (RFC 7693). Das Auto ist Responder (statischer Schlüssel A, dem Handy aus dem QR bekannt),
 * das Handy Initiator. Initiator-Rolle für Tests und künftige Gegenstellen.
 *
 * <pre>
 *   &lt;- s
 *   ...
 *   -&gt; e, es, s, ss
 *   &lt;- e, ee, se
 * </pre>
 * Prolog {@link #PROLOG}. Nachrichten ≤ 65535 Byte. Nicht threadsicher (eine Instanz je Kanal).
 */
public final class NoiseIK {

    public static final String PROTOKOLL = "Noise_IK_25519_ChaChaPoly_BLAKE2s";
    public static final byte[] PROLOG = "panoptes-bruecke/1".getBytes(StandardCharsets.US_ASCII);
    public static final int MAX_NACHRICHT = 65535, DHLEN = 32, TAGLEN = 16;

    /** Schlüsselpaar X25519. */
    public static final class Schluessel {
        public final byte[] privat, oeffentlich;

        public Schluessel(byte[] privat) throws GeneralSecurityException {
            if (privat == null || privat.length != DHLEN) throw new GeneralSecurityException("privater Schlüssel");
            this.privat = privat.clone();
            this.oeffentlich = X25519.publicFromPrivate(this.privat);
        }

        public static Schluessel neu() throws GeneralSecurityException {
            return new Schluessel(X25519.generatePrivateKey());
        }
    }

    /** Erzeugt Ephemeralschlüssel – im Test austauschbar (feste Werte). */
    interface Zufall {
        Schluessel ephemeral() throws GeneralSecurityException;
    }

    /** Ein Richtungs-Chiffrezustand (k, n). */
    static final class Chiffre {
        private byte[] k;
        private long n;

        void setze(byte[] key) {
            k = key == null ? null : Arrays.copyOf(key, 32);
            n = 0;
        }

        boolean hatSchluessel() { return k != null; }

        private static byte[] nonce(long n) {
            byte[] r = new byte[12];
            for (int i = 0; i < 8; i++) r[4 + i] = (byte) (n >>> (8 * i));   // 32 Bit 0 + 64 Bit little endian
            return r;
        }

        byte[] verschluesseln(byte[] ad, byte[] klar) throws GeneralSecurityException {
            if (k == null) return klar.clone();
            if (n == -1L) throw new GeneralSecurityException("Nonce erschöpft");
            byte[] c = new InsecureNonceChaCha20Poly1305(k).encrypt(nonce(n), klar, ad);
            n++;
            return c;
        }

        byte[] entschluesseln(byte[] ad, byte[] chiffre) throws GeneralSecurityException {
            if (k == null) return chiffre.clone();
            if (n == -1L) throw new GeneralSecurityException("Nonce erschöpft");
            byte[] p = new InsecureNonceChaCha20Poly1305(k).decrypt(nonce(n), chiffre, ad);
            n++;   // nur nach erfolgreicher Prüfung (Spezifikation 5.1)
            return p;
        }
    }

    private final boolean initiator;
    private final Schluessel s;
    private final Zufall zufall;
    private byte[] rs, re;
    private Schluessel e;
    private byte[] ck, h;
    private final Chiffre hs = new Chiffre();
    private Chiffre senden, empfangen;
    private int schritt;   // 0: Nachricht 1 offen, 1: Nachricht 2 offen, 2: fertig

    private NoiseIK(boolean initiator, Schluessel s, byte[] rs, Zufall zufall) {
        this.initiator = initiator;
        this.s = s;
        this.rs = rs == null ? null : rs.clone();
        this.zufall = zufall;
        byte[] name = PROTOKOLL.getBytes(StandardCharsets.US_ASCII);
        h = name.length <= 32 ? Arrays.copyOf(name, 32) : Blake2s.hash(name);
        ck = h.clone();
        mixHash(PROLOG);
        mixHash(initiator ? this.rs : s.oeffentlich);   // Vorab-Nachricht: <- s
    }

    /** Auto-Seite. */
    public static NoiseIK responder(Schluessel auto) {
        return new NoiseIK(false, auto, null, Schluessel::neu);
    }

    static NoiseIK responder(Schluessel auto, Zufall zufall) {
        return new NoiseIK(false, auto, null, zufall);
    }

    /** Handy-Seite (Tests, Werkzeuge): kennt den öffentlichen Auto-Schlüssel vorab. */
    public static NoiseIK initiator(Schluessel handy, byte[] autoOeffentlich) {
        return new NoiseIK(true, handy, autoOeffentlich, Schluessel::neu);
    }

    static NoiseIK initiator(Schluessel handy, byte[] autoOeffentlich, Zufall zufall) {
        return new NoiseIK(true, handy, autoOeffentlich, zufall);
    }

    public boolean fertig() { return schritt == 2; }

    /** Statischer Schlüssel der Gegenseite (Responder: nach Nachricht 1 bekannt). */
    public byte[] gegenseite() { return rs == null ? null : rs.clone(); }

    /** Hash des Handshakes (Kanalbindung). */
    public byte[] handshakeHash() { return h.clone(); }

    // ------------------------------------------------------------------ Handshake

    /** Initiator: Nachricht 1 schreiben. */
    public byte[] schreibeNachricht1(byte[] nutzlast) throws GeneralSecurityException {
        if (!initiator || schritt != 0) throw new GeneralSecurityException("Zustand");
        e = zufall.ephemeral();
        byte[] out = verketten(e.oeffentlich);
        mixHash(e.oeffentlich);
        mixKey(dh(e.privat, rs));                                        // es
        byte[] sc = verschluesselnUndHash(s.oeffentlich);                // s
        mixKey(dh(s.privat, rs));                                        // ss
        byte[] pc = verschluesselnUndHash(nutzlast == null ? new byte[0] : nutzlast);
        schritt = 1;
        return pruefeLaenge(verketten(out, sc, pc));
    }

    /** Responder: Nachricht 1 lesen; danach ist {@link #gegenseite()} der statische Handy-Schlüssel. */
    public byte[] leseNachricht1(byte[] m) throws GeneralSecurityException {
        if (initiator || schritt != 0) throw new GeneralSecurityException("Zustand");
        if (m == null || m.length > MAX_NACHRICHT || m.length < DHLEN + DHLEN + TAGLEN + TAGLEN)
            throw new GeneralSecurityException("Länge");
        re = Arrays.copyOfRange(m, 0, DHLEN);
        mixHash(re);
        mixKey(dh(s.privat, re));                                        // es
        rs = entschluesselnUndHash(Arrays.copyOfRange(m, DHLEN, DHLEN + DHLEN + TAGLEN));   // s
        mixKey(dh(s.privat, rs));                                        // ss
        byte[] p = entschluesselnUndHash(Arrays.copyOfRange(m, DHLEN + DHLEN + TAGLEN, m.length));
        schritt = 1;
        return p;
    }

    /** Responder: Nachricht 2 schreiben; danach Transport. */
    public byte[] schreibeNachricht2(byte[] nutzlast) throws GeneralSecurityException {
        if (initiator || schritt != 1) throw new GeneralSecurityException("Zustand");
        e = zufall.ephemeral();
        byte[] out = e.oeffentlich.clone();
        mixHash(e.oeffentlich);
        mixKey(dh(e.privat, re));                                        // ee
        mixKey(dh(e.privat, rs));                                        // se
        byte[] pc = verschluesselnUndHash(nutzlast == null ? new byte[0] : nutzlast);
        teilen();
        return pruefeLaenge(verketten(out, pc));
    }

    /** Initiator: Nachricht 2 lesen; danach Transport. */
    public byte[] leseNachricht2(byte[] m) throws GeneralSecurityException {
        if (!initiator || schritt != 1) throw new GeneralSecurityException("Zustand");
        if (m == null || m.length > MAX_NACHRICHT || m.length < DHLEN + TAGLEN) throw new GeneralSecurityException("Länge");
        re = Arrays.copyOfRange(m, 0, DHLEN);
        mixHash(re);
        mixKey(dh(e.privat, re));                                        // ee
        mixKey(dh(s.privat, re));                                        // se
        byte[] p = entschluesselnUndHash(Arrays.copyOfRange(m, DHLEN, m.length));
        teilen();
        return p;
    }

    // ------------------------------------------------------------------ Transport

    public byte[] verschluesseln(byte[] klar) throws GeneralSecurityException {
        if (schritt != 2) throw new GeneralSecurityException("Handshake offen");
        if (klar.length + TAGLEN > MAX_NACHRICHT) throw new GeneralSecurityException("Länge");
        return senden.verschluesseln(new byte[0], klar);
    }

    public byte[] entschluesseln(byte[] chiffre) throws GeneralSecurityException {
        if (schritt != 2) throw new GeneralSecurityException("Handshake offen");
        if (chiffre == null || chiffre.length > MAX_NACHRICHT || chiffre.length < TAGLEN) throw new GeneralSecurityException("Länge");
        return empfangen.entschluesseln(new byte[0], chiffre);
    }

    // ------------------------------------------------------------------ Bausteine (Spezifikation 5.2/5.3)

    private void teilen() {
        byte[][] k = hkdf(ck, new byte[0]);
        Chiffre c1 = new Chiffre(), c2 = new Chiffre();
        c1.setze(k[0]);
        c2.setze(k[1]);
        senden = initiator ? c1 : c2;
        empfangen = initiator ? c2 : c1;
        hs.setze(null);
        e = null;
        schritt = 2;
    }

    private void mixHash(byte[] daten) {
        h = Blake2s.hash(h, daten);
    }

    private void mixKey(byte[] ikm) {
        byte[][] k = hkdf(ck, ikm);
        ck = k[0];
        hs.setze(k[1]);
    }

    private byte[] verschluesselnUndHash(byte[] klar) throws GeneralSecurityException {
        byte[] c = hs.verschluesseln(h, klar);
        mixHash(c);
        return c;
    }

    private byte[] entschluesselnUndHash(byte[] c) throws GeneralSecurityException {
        byte[] p = hs.entschluesseln(h, c);
        mixHash(c);
        return p;
    }

    static byte[][] hkdf(byte[] chainingKey, byte[] ikm) {
        byte[] temp = Blake2s.hmac(chainingKey, ikm);
        byte[] o1 = Blake2s.hmac(temp, new byte[] {1});
        byte[] o2 = Blake2s.hmac(temp, o1, new byte[] {2});
        return new byte[][] {o1, o2};
    }

    private static byte[] dh(byte[] privat, byte[] oeffentlich) throws GeneralSecurityException {
        if (oeffentlich == null || oeffentlich.length != DHLEN) throw new GeneralSecurityException("öffentlicher Schlüssel");
        try {
            return X25519.computeSharedSecret(privat, oeffentlich);
        } catch (java.security.InvalidKeyException ex) {
            throw new GeneralSecurityException("DH");
        }
    }

    private static byte[] pruefeLaenge(byte[] m) throws GeneralSecurityException {
        if (m.length > MAX_NACHRICHT) throw new GeneralSecurityException("Länge");
        return m;
    }

    private static byte[] verketten(byte[]... teile) {
        int n = 0;
        for (byte[] t : teile) n += t.length;
        byte[] r = new byte[n];
        int o = 0;
        for (byte[] t : teile) {
            System.arraycopy(t, 0, r, o, t.length);
            o += t.length;
        }
        return r;
    }
}
