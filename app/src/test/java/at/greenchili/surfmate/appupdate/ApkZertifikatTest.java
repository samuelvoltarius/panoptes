package at.greenchili.surfmate.appupdate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assume.assumeTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Zertifikat aus dem APK-Signaturblock: nachgebaute APKs (v2/v3, kaputt, zwei Unterzeichner) + echte APK. */
public class ApkZertifikatTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    static byte[] le32(int v) { return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array(); }
    static byte[] le64(long v) { return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array(); }

    static byte[] cat(byte[]... t) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] b : t) o.write(b, 0, b.length);
        return o.toByteArray();
    }

    static byte[] lp(byte[] b) { return cat(le32(b.length), b); }

    static byte[] signer(byte[] cert) {
        byte[] signed = cat(lp(new byte[]{1, 2, 3}), lp(lp(cert)), lp(new byte[0]));
        return cat(lp(signed), lp(new byte[]{9}), lp(new byte[]{8}));
    }

    static byte[] schema(byte[]... signers) {
        byte[] alle = new byte[0];
        for (byte[] s : signers) alle = cat(alle, lp(s));
        return lp(alle);
    }

    static byte[] paar(int id, byte[] wert) { return cat(le64(4 + wert.length), le32(id), wert); }

    /** ZIP-Rumpf + Signaturblock + Zentralverzeichnis + EOCD. */
    static byte[] apk(byte[] paare) {
        byte[] rumpf = "PK\u0003\u0004 nur Füllstoff".getBytes(StandardCharsets.UTF_8);
        long groesse = paare.length + 24;
        byte[] block = cat(le64(groesse), paare, le64(groesse), "APK Sig Block 42".getBytes(StandardCharsets.US_ASCII));
        byte[] cd = "PK\u0001\u0002 zentral".getBytes(StandardCharsets.UTF_8);
        int cdOffset = rumpf.length + block.length;
        ByteBuffer eocd = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN);
        eocd.putInt(0x06054b50).putShort((short) 0).putShort((short) 0).putShort((short) 1).putShort((short) 1)
                .putInt(cd.length).putInt(cdOffset).putShort((short) 0);
        return cat(rumpf, block, cd, eocd.array());
    }

    File schreibe(byte[] b) throws Exception {
        File f = tmp.newFile();
        Files.write(f.toPath(), b);
        return f;
    }

    static final byte[] CERT_A = "zertifikat-A (DER)".getBytes(StandardCharsets.UTF_8);
    static final byte[] CERT_B = "zertifikat-B (DER)".getBytes(StandardCharsets.UTF_8);

    @Test
    public void v2EinUnterzeichner() throws Exception {
        File f = schreibe(apk(paar(ApkZertifikat.ID_V2, schema(signer(CERT_A)))));
        assertEquals(ApkZertifikat.hex(ApkZertifikat.sha256(CERT_A)), ApkZertifikat.sha256(f));
    }

    @Test
    public void v3HatVorrangVorV2() throws Exception {
        byte[] paare = cat(paar(ApkZertifikat.ID_V2, schema(signer(CERT_A))),
                paar(0x42726577, new byte[]{0, 0, 0, 0}),   // Füll-Block wird übersprungen
                paar(ApkZertifikat.ID_V3, schema(signer(CERT_B))));
        assertEquals(ApkZertifikat.hex(ApkZertifikat.sha256(CERT_B)), ApkZertifikat.sha256(schreibe(apk(paare))));
    }

    @Test
    public void zweiUnterzeichnerWerdenAbgelehnt() throws Exception {
        File f = schreibe(apk(paar(ApkZertifikat.ID_V2, schema(signer(CERT_A), signer(CERT_B)))));
        assertNull(ApkZertifikat.sha256(f));
    }

    @Test
    public void ohneSignaturblockNull() throws Exception {
        byte[] b = apk(paar(ApkZertifikat.ID_V2, schema(signer(CERT_A))));
        b[b.length - 22 - 20] ^= 0x55;  // Magie im Fuß zerstören (liegt vor dem Zentralverzeichnis)
        assertNull(ApkZertifikat.sha256(schreibe(b)));
        assertNull(ApkZertifikat.sha256(schreibe("kein zip".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    public void abgeschnittenNull() throws Exception {
        byte[] b = apk(paar(ApkZertifikat.ID_V2, schema(signer(CERT_A))));
        byte[] kurz = java.util.Arrays.copyOf(b, b.length - 30);
        assertNull(ApkZertifikat.sha256(schreibe(kurz)));
    }

    /** Echte Panoptes-APK am PC (falls vorhanden): Fingerabdruck = Freigabe.ZERTIFIKAT (wie apksigner in der CI). */
    @Test
    public void echteApk() {
        File dir = new File("build/abgabe");
        File[] apks = dir.listFiles((d, n) -> n.startsWith("panoptes-kern-") && n.endsWith(".apk"));
        assumeTrue(apks != null && apks.length > 0);
        assertEquals(Freigabe.ZERTIFIKAT, ApkZertifikat.sha256(apks[0]));
    }
}
