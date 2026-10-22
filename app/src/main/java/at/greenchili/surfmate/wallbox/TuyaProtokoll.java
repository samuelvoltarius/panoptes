package at.greenchili.surfmate.wallbox;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.zip.CRC32;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Tuya-LAN-Protokoll 3.3 / 3.4 / 3.5 (WALLBOX_VERTRAG §4b), eigene Umsetzung nach Protokollbeschreibung (Clean-Room).
 * Rahmenformate gegen tinytuya 1.20 (MIT) als Black-Box-Orakel geprüft (Prüfvektoren in WallboxTuyaTest).
 * <pre>
 * 55AA (3.3/3.4): 000055AA | seq | cmd | len | [retcode] nutzdaten | CRC32 (3.3) bzw. HMAC-SHA256 (3.4) | 0000AA55
 *                 len = nutzdaten + Prüfteil + 4 (Suffix); Prüfteil über alles davor
 * 6699 (3.5):     00006699 | 0000 | seq | cmd | len | IV(12) | AES-GCM(klar) | Tag(16) | 00009966
 *                 len = 12 + klar + 16; AAD = Bytes 4..18 (ab 0000 bis len)
 * </pre>
 * Rein (keine Netzwerk- oder Android-Klassen).
 */
public final class TuyaProtokoll {

    public static final int PREFIX_55AA = 0x000055AA;
    public static final int SUFFIX_55AA = 0x0000AA55;
    public static final int PREFIX_6699 = 0x00006699;
    public static final int SUFFIX_6699 = 0x00009966;

    public static final int SESS_START = 0x03;
    public static final int SESS_ANTWORT = 0x04;
    public static final int SESS_ENDE = 0x05;
    public static final int CONTROL = 0x07;
    public static final int STATUS = 0x08;
    public static final int HEART_BEAT = 0x09;
    public static final int DP_QUERY = 0x0A;
    public static final int CONTROL_NEW = 0x0D;
    public static final int DP_QUERY_NEW = 0x10;

    /** Größter Rahmen, den wir annehmen. */
    public static final int MAX_LAENGE = 64 * 1024;

    /** Ein gelesener Rahmen: Rückgabecode (−1 = keiner) und Nutzdaten (bei 6699 bereits entschlüsselt). */
    public static final class Rahmen {
        public final int seq;
        public final int cmd;
        public final long retcode;
        public final byte[] daten;

        Rahmen(int seq, int cmd, long retcode, byte[] daten) {
            this.seq = seq;
            this.cmd = cmd;
            this.retcode = retcode;
            this.daten = daten;
        }
    }

    private TuyaProtokoll() {}

    // ------------------------------------------------------------------ 55AA

    /** Rahmen 55AA; {@code hmacSchluessel} null = CRC32 (3.3), sonst HMAC-SHA256 (3.4). */
    public static byte[] rahmen55aa(int seq, int cmd, byte[] daten, byte[] hmacSchluessel) {
        int pruef = hmacSchluessel == null ? 4 : 32;
        ByteBuffer b = ByteBuffer.allocate(16 + daten.length + pruef + 4);
        b.putInt(PREFIX_55AA).putInt(seq).putInt(cmd).putInt(daten.length + pruef + 4).put(daten);
        byte[] bisher = Arrays.copyOf(b.array(), 16 + daten.length);
        if (hmacSchluessel == null) {
            CRC32 c = new CRC32();
            c.update(bisher);
            b.putInt((int) c.getValue());
        } else {
            b.put(hmac(hmacSchluessel, bisher));
        }
        b.putInt(SUFFIX_55AA);
        return b.array();
    }

    /** Prüft und zerlegt einen 55AA-Rahmen vom Gerät. Ein führender Rückgabecode (obere 3 Bytes 0) wird abgetrennt. */
    public static Rahmen lies55aa(byte[] f, byte[] hmacSchluessel) throws WallboxFehler {
        int pruef = hmacSchluessel == null ? 4 : 32;
        if (f.length < 16 + pruef + 4) throw new WallboxFehler("tuya_antwort_ungueltig", 502);
        ByteBuffer b = ByteBuffer.wrap(f);
        if (b.getInt(0) != PREFIX_55AA || b.getInt(f.length - 4) != SUFFIX_55AA) throw new WallboxFehler("tuya_antwort_ungueltig", 502);
        int seq = b.getInt(4), cmd = b.getInt(8), len = b.getInt(12);
        if (len != f.length - 16) throw new WallboxFehler("tuya_antwort_ungueltig", 502);
        int ende = f.length - 4 - pruef;
        byte[] bisher = Arrays.copyOf(f, ende);
        byte[] ist = Arrays.copyOfRange(f, ende, ende + pruef);
        if (hmacSchluessel == null) {
            CRC32 c = new CRC32();
            c.update(bisher);
            if ((int) c.getValue() != ByteBuffer.wrap(ist).getInt()) throw new WallboxFehler("tuya_pruefsumme", 502);
        } else if (!MessageDigest.isEqual(hmac(hmacSchluessel, bisher), ist)) {
            throw new WallboxFehler("tuya_schluessel", 502);
        }
        byte[] daten = Arrays.copyOfRange(f, 16, ende);
        return mitRetcode(seq, cmd, daten);
    }

    // ------------------------------------------------------------------ 6699

    public static byte[] rahmen6699(int seq, int cmd, byte[] klar, byte[] schluessel, byte[] iv) throws WallboxFehler {
        ByteBuffer kopf = ByteBuffer.allocate(18);
        kopf.putInt(PREFIX_6699).putShort((short) 0).putInt(seq).putInt(cmd).putInt(12 + klar.length + 16);
        byte[] k = kopf.array();
        byte[] ct = gcm(Cipher.ENCRYPT_MODE, schluessel, iv, Arrays.copyOfRange(k, 4, 18), klar);
        ByteBuffer b = ByteBuffer.allocate(18 + 12 + ct.length + 4);
        b.put(k).put(iv).put(ct).putInt(SUFFIX_6699);
        return b.array();
    }

    public static Rahmen lies6699(byte[] f, byte[] schluessel) throws WallboxFehler {
        if (f.length < 18 + 12 + 16 + 4) throw new WallboxFehler("tuya_antwort_ungueltig", 502);
        ByteBuffer b = ByteBuffer.wrap(f);
        if (b.getInt(0) != PREFIX_6699 || b.getInt(f.length - 4) != SUFFIX_6699) throw new WallboxFehler("tuya_antwort_ungueltig", 502);
        int seq = b.getInt(6), cmd = b.getInt(10), len = b.getInt(14);
        if (len != f.length - 18 - 4) throw new WallboxFehler("tuya_antwort_ungueltig", 502);
        byte[] iv = Arrays.copyOfRange(f, 18, 30);
        byte[] klar;
        try {
            klar = gcm(Cipher.DECRYPT_MODE, schluessel, iv, Arrays.copyOfRange(f, 4, 18), Arrays.copyOfRange(f, 30, f.length - 4));
        } catch (WallboxFehler e) {
            throw new WallboxFehler("tuya_schluessel", 502);
        }
        return mitRetcode(seq, cmd, klar);
    }

    private static Rahmen mitRetcode(int seq, int cmd, byte[] daten) {
        if (daten.length >= 4 && (daten[0] | daten[1] | daten[2]) == 0) {
            long rc = ByteBuffer.wrap(daten, 0, 4).getInt() & 0xffffffffL;
            return new Rahmen(seq, cmd, rc, Arrays.copyOfRange(daten, 4, daten.length));
        }
        return new Rahmen(seq, cmd, -1, daten);
    }

    // ------------------------------------------------------------------ Nutzdaten je Version

    /** Versionskopf „3.x“ + 12 Nullbytes (nur Steuerbefehle). */
    static byte[] versionsKopf(String version) {
        byte[] k = new byte[15];
        System.arraycopy(version.getBytes(StandardCharsets.US_ASCII), 0, k, 0, 3);
        return k;
    }

    /** Entfernt einen Versionskopf „3.x“ + 12 Bytes, falls vorhanden. */
    static byte[] ohneVersionsKopf(byte[] d) {
        if (d.length >= 15 && d[0] == '3' && d[1] == '.' && d[2] >= '0' && d[2] <= '9') return Arrays.copyOfRange(d, 15, d.length);
        return d;
    }

    // ------------------------------------------------------------------ Krypto

    public static byte[] ecb(int modus, byte[] schluessel, byte[] daten, boolean auffuellen) throws WallboxFehler {
        try {
            Cipher c = Cipher.getInstance(auffuellen ? "AES/ECB/PKCS5Padding" : "AES/ECB/NoPadding");
            c.init(modus, new SecretKeySpec(schluessel, "AES"));
            return c.doFinal(daten);
        } catch (Exception e) {
            throw new WallboxFehler("tuya_schluessel", 502);
        }
    }

    static byte[] gcm(int modus, byte[] schluessel, byte[] iv, byte[] aad, byte[] daten) throws WallboxFehler {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(modus, new SecretKeySpec(schluessel, "AES"), new GCMParameterSpec(128, iv));
            if (aad != null) c.updateAAD(aad);
            return c.doFinal(daten);
        } catch (Exception e) {
            throw new WallboxFehler("tuya_schluessel", 502);
        }
    }

    public static byte[] hmac(byte[] schluessel, byte[] daten) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(schluessel, "HmacSHA256"));
            return m.doFinal(daten);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Sitzungsschlüssel nach der Aushandlung: 3.4 AES-ECB(local_key, lokal⊕fern); 3.5 AES-GCM(local_key, iv=lokal[0..12]) ohne Tag. */
    public static byte[] sitzungsSchluessel(String version, byte[] localKey, byte[] lokal, byte[] fern) throws WallboxFehler {
        byte[] x = new byte[16];
        for (int i = 0; i < 16; i++) x[i] = (byte) (lokal[i] ^ fern[i]);
        if ("3.4".equals(version)) return ecb(Cipher.ENCRYPT_MODE, localKey, x, false);
        byte[] ct = gcm(Cipher.ENCRYPT_MODE, localKey, Arrays.copyOf(lokal, 12), null, x);
        return Arrays.copyOf(ct, 16);
    }

    static byte[] verbinde(byte[]... teile) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] t : teile) o.write(t, 0, t.length);
        return o.toByteArray();
    }
}
