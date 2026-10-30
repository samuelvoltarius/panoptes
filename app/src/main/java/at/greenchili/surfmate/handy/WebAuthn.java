package at.greenchili.surfmate.handy;

import org.json.JSONObject;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * 0.1.33 Web-Aufsperren: Prüfung von Passkey-Antworten (WebAuthn, ES256), ohne Bibliothek. Rein, ohne Zustand.
 *
 * <p>Der öffentliche Schlüssel wird nicht aus beliebigem CBOR gelesen, sondern muss Byte für Byte der einen Form
 * entsprechen, die ein ES256-Passkey liefert ({@link #COSE_ANFANG}). Alles andere bricht ab: So gibt es am Auto keinen
 * CBOR-Parser. Jede Prüfung lehnt im Zweifel ab.
 */
public final class WebAuthn {

    public static final String RP_ID = "panoptes.serveone.at";
    public static final String ORIGIN = "https://" + RP_ID;

    public static final int MAX_CLIENT_DATA = 1024;
    public static final int MAX_GESAMT = 4096;
    public static final int MAX_CREDENTIAL_ID = 256;
    public static final int MAX_SIGNATUR = 80;

    static final int FLAG_UP = 0x01, FLAG_UV = 0x04, FLAG_BE = 0x08, FLAG_BS = 0x10, FLAG_AT = 0x40, FLAG_ED = 0x80;

    static final String GRUND_SIGNATUR = HandySignatur.GRUND_SIGNATUR;
    static final String GRUND_UV = HandySignatur.GRUND_UV;

    private static final int RP_HASH = 32, KOPF = RP_HASH + 1 + 4, AAGUID = 16, ID_LAENGE = 2, KOORDINATE = 32;

    /** kty 2, alg -7, crv 1, x: 32 Byte; danach {@link #COSE_MITTE} und y. Kanonische CBOR-Form aus CTAP2. */
    private static final byte[] COSE_ANFANG = {(byte) 0xA5, 0x01, 0x02, 0x03, 0x26, 0x20, 0x01, 0x21, 0x58, 0x20};
    private static final byte[] COSE_MITTE = {0x22, 0x58, 0x20};
    private static final int COSE_LAENGE = COSE_ANFANG.length + KOORDINATE + COSE_MITTE.length + KOORDINATE;

    /** X.509-Kopf eines unkomprimierten P-256-Schlüssels, dahinter 0x04, x, y. */
    private static final byte[] SPKI_KOPF = hex("3059301306072a8648ce3d020106082a8648ce3d030107034200");

    private static final BigInteger P = new BigInteger(
            "ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16);
    private static final BigInteger B = new BigInteger(
            "5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16);

    private static final Pattern BASE64URL = Pattern.compile("[A-Za-z0-9_-]+");

    private WebAuthn() {}

    /** Ergebnis der Anlage-Prüfung: {@code anlage} ≠ null genau dann, wenn {@code grund} == null. */
    public static final class Pruefung {
        public final String grund;
        public final Anlage anlage;

        private Pruefung(String grund, Anlage anlage) {
            this.grund = grund;
            this.anlage = anlage;
        }
    }

    /** Geprüfter neuer Passkey. */
    public static final class Anlage {
        public final String credentialId;
        public final String schluessel;
        public final long zaehler;
        public final boolean synchronisiert;
        public final String pruefzahl;

        private Anlage(String credentialId, String schluessel, long zaehler, boolean synchronisiert, String pruefzahl) {
            this.credentialId = credentialId;
            this.schluessel = schluessel;
            this.zaehler = zaehler;
            this.synchronisiert = synchronisiert;
            this.pruefzahl = pruefzahl;
        }
    }

    /** Strenges base64url ohne Auffüllung; null, wenn ungültig oder leer. */
    public static byte[] dekodiere(String s) {
        if (s == null || !BASE64URL.matcher(s).matches()) return null;
        try {
            return Base64.getUrlDecoder().decode(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static String kodiere(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static byte[] sha256(byte[] b) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(b);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Challenge beim Aufsperren: base64url von SHA-256 des signierten Textes, das Auto rechnet sie selbst nach. */
    public static String aufsperrChallenge(String kopplungId, long zeitMs, String nonce) {
        return kodiere(sha256(HandySignatur.nachricht(HandySignatur.AUFSPERREN, kopplungId, zeitMs, nonce)
                .getBytes(StandardCharsets.UTF_8)));
    }

    /** {@code type}, {@code challenge}, {@code origin} exakt, {@code crossOrigin} nicht gesetzt. true = gültig. */
    static boolean clientDataGueltig(byte[] clientData, String typ, String challenge) {
        if (clientData == null || clientData.length > MAX_CLIENT_DATA || challenge == null) return false;
        try {
            JSONObject o = new JSONObject(new String(clientData, StandardCharsets.UTF_8));
            if (!typ.equals(o.opt("type")) || !ORIGIN.equals(o.opt("origin"))) return false;
            Object c = o.opt("challenge");
            if (!(c instanceof String) || !MessageDigest.isEqual(
                    ((String) c).getBytes(StandardCharsets.UTF_8), challenge.getBytes(StandardCharsets.UTF_8))) {
                return false;
            }
            Object fremd = o.opt("crossOrigin");
            return (fremd == null || Boolean.FALSE.equals(fremd)) && !o.has("topOrigin");
        } catch (Exception e) {
            return false;
        }
    }

    /** Gemeinsamer Teil: rpIdHash, UP, BS nur mit BE. null = gültig, sonst Grund; UV zuletzt, eigener Grund. */
    static String kopfGrund(byte[] authDaten) {
        if (authDaten == null || authDaten.length < KOPF) return GRUND_SIGNATUR;
        byte[] rp = Arrays.copyOfRange(authDaten, 0, RP_HASH);
        if (!MessageDigest.isEqual(rp, sha256(RP_ID.getBytes(StandardCharsets.UTF_8)))) return GRUND_SIGNATUR;
        int f = authDaten[RP_HASH] & 0xFF;
        if ((f & FLAG_UP) == 0) return GRUND_SIGNATUR;
        if ((f & FLAG_BS) != 0 && (f & FLAG_BE) == 0) return GRUND_SIGNATUR;
        return (f & FLAG_UV) == 0 ? GRUND_UV : null;
    }

    /** Signaturzähler (nur nach bestandener {@link #kopfGrund}-Prüfung). */
    static long zaehler(byte[] authDaten) {
        long z = 0;
        for (int i = RP_HASH + 1; i < KOPF; i++) z = (z << 8) | (authDaten[i] & 0xFF);
        return z;
    }

    /** Prüft die Antwort auf {@code navigator.credentials.create} gegen die ausgegebene Challenge. */
    public static Pruefung pruefeAnlage(String challenge, String credentialIdB64, String clientDataB64,
                                        String authDatenB64) {
        byte[] id = dekodiere(credentialIdB64), clientData = dekodiere(clientDataB64), auth = dekodiere(authDatenB64);
        if (id == null || clientData == null || auth == null
                || id.length > MAX_CREDENTIAL_ID || auth.length > MAX_GESAMT
                || !clientDataGueltig(clientData, "webauthn.create", challenge)) {
            return new Pruefung(GRUND_SIGNATUR, null);
        }
        String g = kopfGrund(auth);
        if (g != null) return new Pruefung(g, null);
        int f = auth[RP_HASH] & 0xFF;
        if ((f & FLAG_AT) == 0 || (f & FLAG_ED) != 0 || auth.length < KOPF + AAGUID + ID_LAENGE) {
            return new Pruefung(GRUND_SIGNATUR, null);
        }
        int p = KOPF + AAGUID;
        int laenge = ((auth[p] & 0xFF) << 8) | (auth[p + 1] & 0xFF);
        p += ID_LAENGE;
        if (laenge != id.length || auth.length != p + laenge + COSE_LAENGE
                || !MessageDigest.isEqual(Arrays.copyOfRange(auth, p, p + laenge), id)) {
            return new Pruefung(GRUND_SIGNATUR, null);
        }
        p += laenge;
        byte[] spki = spki(auth, p);
        if (spki == null) return new Pruefung(GRUND_SIGNATUR, null);
        String schluessel = Base64.getEncoder().encodeToString(spki);
        if (HandySignatur.schluessel(schluessel) == null) return new Pruefung(GRUND_SIGNATUR, null);
        return new Pruefung(null, new Anlage(credentialIdB64, schluessel, zaehler(auth), (f & FLAG_BE) != 0,
                pruefzahl(id, spki)));
    }

    /** X.509-Schlüssel aus der festen COSE-Form bei {@code p}; null bei jeder Abweichung oder Punkt außerhalb. */
    private static byte[] spki(byte[] b, int p) {
        if (!stimmt(b, p, COSE_ANFANG)) return null;
        int px = p + COSE_ANFANG.length, py = px + KOORDINATE + COSE_MITTE.length;
        if (!stimmt(b, px + KOORDINATE, COSE_MITTE)) return null;
        byte[] x = Arrays.copyOfRange(b, px, px + KOORDINATE), y = Arrays.copyOfRange(b, py, py + KOORDINATE);
        if (!aufKurve(new BigInteger(1, x), new BigInteger(1, y))) return null;
        byte[] out = Arrays.copyOf(SPKI_KOPF, SPKI_KOPF.length + 1 + KOORDINATE * 2);
        out[SPKI_KOPF.length] = 0x04;
        System.arraycopy(x, 0, out, SPKI_KOPF.length + 1, KOORDINATE);
        System.arraycopy(y, 0, out, SPKI_KOPF.length + 1 + KOORDINATE, KOORDINATE);
        return out;
    }

    private static boolean stimmt(byte[] b, int p, byte[] muster) {
        if (p + muster.length > b.length) return false;
        for (int i = 0; i < muster.length; i++) if (b[p + i] != muster[i]) return false;
        return true;
    }

    /** y² = x³ − 3x + b (mod p), P-256. */
    static boolean aufKurve(BigInteger x, BigInteger y) {
        if (x.compareTo(P) >= 0 || y.compareTo(P) >= 0) return false;
        BigInteger rechts = x.pow(3).subtract(x.multiply(BigInteger.valueOf(3))).add(B).mod(P);
        return y.multiply(y).mod(P).equals(rechts);
    }

    /** Vier Ziffern aus SHA-256(credentialId ‖ Schlüssel) für die Sichtkontrolle am Auto. */
    static String pruefzahl(byte[] credentialId, byte[] spki) {
        byte[] alles = Arrays.copyOf(credentialId, credentialId.length + spki.length);
        System.arraycopy(spki, 0, alles, credentialId.length, spki.length);
        byte[] h = sha256(alles);
        int z = ((h[0] & 0xFF) << 24 | (h[1] & 0xFF) << 16 | (h[2] & 0xFF) << 8 | (h[3] & 0xFF)) >>> 1;
        return String.format(java.util.Locale.ROOT, "%04d", z % 10_000);
    }

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return b;
    }
}
