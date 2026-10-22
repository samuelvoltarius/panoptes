package at.greenchili.surfmate.appupdate;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;

/**
 * Liest den SHA-256-Fingerabdruck des Signaturzertifikats aus dem APK-Signaturblock (Schema v3, sonst v2),
 * ohne PackageManager – der Daemon läuft als app_process ohne Context.
 *
 * <p>Das ist eine <b>Vorprüfung</b> („ist das unser Schlüssel?“). Die kryptografische Prüfung der Signatur
 * selbst macht Android bei {@code pm install -r}: Ein APK mit fremdem Schlüssel oder kaputter Signatur wird dort
 * abgewiesen, das alte bleibt. Wir lehnen nur früher und mit klarer Meldung ab.
 *
 * <p>Genau ein Unterzeichner ist erlaubt; mehrere oder keiner → {@code null} (= ablehnen).
 */
public final class ApkZertifikat {

    private ApkZertifikat() {}

    static final int ID_V2 = 0x7109871a;
    static final int ID_V3 = 0xf05368c0;
    private static final long MAGIC_LO = 0x20676953204b5041L; // "APK Sig "
    private static final long MAGIC_HI = 0x3234206b636f6c42L; // "Block 42"
    private static final int EOCD_SIG = 0x06054b50;
    private static final int MAX_BLOCK = 16 * 1024 * 1024;

    /** Fingerabdruck (64 hex, klein) oder null, wenn nicht lesbar/kein bzw. mehr als ein Unterzeichner. */
    public static String sha256(File apk) {
        try (RandomAccessFile f = new RandomAccessFile(apk, "r")) {
            long cd = zentralverzeichnis(f);
            if (cd < 32) return null;
            ByteBuffer block = signaturblock(f, cd);
            if (block == null) return null;
            ByteBuffer v3 = null, v2 = null;
            while (block.remaining() >= 12) {
                long len = block.getLong();
                if (len < 4 || len > block.remaining()) return null;
                int id = block.getInt();
                ByteBuffer wert = scheibe(block, (int) (len - 4));
                if (id == ID_V3) v3 = wert;
                else if (id == ID_V2) v2 = wert;
            }
            ByteBuffer schema = v3 != null ? v3 : v2;
            return schema == null ? null : ersterUnterzeichner(schema);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String ersterUnterzeichner(ByteBuffer schema) throws IOException {
        ByteBuffer signers = laengenPraefix(schema);
        if (schema.hasRemaining()) return null;
        ByteBuffer signer = laengenPraefix(signers);
        if (signers.hasRemaining()) return null;          // mehr als ein Unterzeichner
        ByteBuffer signed = laengenPraefix(signer);
        laengenPraefix(signed);                            // digests
        ByteBuffer certs = laengenPraefix(signed);
        ByteBuffer cert = laengenPraefix(certs);
        byte[] der = new byte[cert.remaining()];
        cert.get(der);
        if (der.length == 0) return null;
        return hex(sha256(der));
    }

    /** Offset des Zentralverzeichnisses aus dem EOCD (ohne ZIP64 – APKs sind < 4 GB). */
    static long zentralverzeichnis(RandomAccessFile f) throws IOException {
        long n = f.length();
        if (n < 22) return -1;
        int suche = (int) Math.min(n, 22 + 65535);
        byte[] ende = new byte[suche];
        f.seek(n - suche);
        f.readFully(ende);
        ByteBuffer b = ByteBuffer.wrap(ende).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = suche - 22; i >= 0; i--) {
            if (b.getInt(i) == EOCD_SIG) {
                int kommentar = b.getShort(i + 20) & 0xffff;
                if (i + 22 + kommentar != suche) continue;
                return b.getInt(i + 16) & 0xffffffffL;
            }
        }
        return -1;
    }

    /** Paare (ohne Größenfelder am Anfang/Ende) oder null, wenn kein Signaturblock vor dem Zentralverzeichnis. */
    static ByteBuffer signaturblock(RandomAccessFile f, long cd) throws IOException {
        byte[] fuss = new byte[24];
        f.seek(cd - 24);
        f.readFully(fuss);
        ByteBuffer b = ByteBuffer.wrap(fuss).order(ByteOrder.LITTLE_ENDIAN);
        long groesse = b.getLong(0);
        if (b.getLong(8) != MAGIC_LO || b.getLong(16) != MAGIC_HI) return null;
        if (groesse < 24 || groesse > MAX_BLOCK || groesse + 8 > cd) return null;
        long start = cd - groesse - 8;
        byte[] alles = new byte[(int) (groesse + 8)];
        f.seek(start);
        f.readFully(alles);
        ByteBuffer blk = ByteBuffer.wrap(alles).order(ByteOrder.LITTLE_ENDIAN);
        if (blk.getLong(0) != groesse) return null;
        blk.position(8);
        blk.limit(alles.length - 24);
        return blk.slice().order(ByteOrder.LITTLE_ENDIAN);
    }

    private static ByteBuffer laengenPraefix(ByteBuffer b) throws IOException {
        if (b.remaining() < 4) throw new IOException("kurz");
        int len = b.getInt();
        if (len < 0 || len > b.remaining()) throw new IOException("laenge");
        return scheibe(b, len);
    }

    private static ByteBuffer scheibe(ByteBuffer b, int len) {
        ByteBuffer s = b.slice().order(ByteOrder.LITTLE_ENDIAN);
        s.limit(len);
        b.position(b.position() + len);
        return s;
    }

    static byte[] sha256(byte[] d) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte x : d) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }
}
