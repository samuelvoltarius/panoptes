package at.greenchili.surfmate.appupdate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 0.1.15 (Kern 8): Der Updater nimmt das alte und das neue Panoptes-Zertifikat an (Signatur-Rotation Kern 9), eine
 * fremd signierte APK nicht, und nie den Rückweg von neu auf alt.
 *
 * <p>Der Echt-Teil signiert mit dem echten apksigner (Build-Tools 36.0.0) und Wegwerf-Schlüsseln genauso wie
 * deploy/signatur/apk-signieren.sh (v3, Lineage alt → neu, rotation-min-sdk 28) und prüft, dass
 * {@link ApkZertifikat} das NEUE Zertifikat liest. Die echten Schlüssel kommen hier nie vor.
 */
public class SignaturRotationTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final String FREMD = "aa".repeat(32);

    @Test
    public void beideZertifikateZulaessigFremdNicht() {
        assertTrue(Freigabe.zulaessig(Freigabe.ZERTIFIKAT));
        assertTrue(Freigabe.zulaessig(Freigabe.ZERTIFIKAT_ALT));
        assertFalse(Freigabe.zulaessig(FREMD));
        assertFalse(Freigabe.zulaessig(null));
        assertEquals("287c31e78288a440be4d23eb59f58e415d77aca54def67e0e6bf271f1959be81", Freigabe.ZERTIFIKAT);
        assertEquals("02d5a37bf0c1e334f56277af5669efc536fa96921fde0138d7e88dea7d7d8006", Freigabe.ZERTIFIKAT_ALT);
    }

    @Test
    public void signaturPasstZurInstallation() {
        String neu = Freigabe.ZERTIFIKAT, alt = Freigabe.ZERTIFIKAT_ALT;
        assertNull("0.1.14.1 (alt) → 0.1.15 (neu mit Lineage)", Freigabe.signaturPasst(neu, alt));
        assertNull("neu → neu", Freigabe.signaturPasst(neu, neu));
        assertNull("alt → alt (vor der Rotation)", Freigabe.signaturPasst(alt, alt));
        assertNull("installiert unbekannt", Freigabe.signaturPasst(neu, null));
        assertNotNull("nie zurück von neu auf alt", Freigabe.signaturPasst(alt, neu));
        assertNotNull("fremd signierte APK", Freigabe.signaturPasst(FREMD, alt));
        assertNotNull("APK nicht lesbar", Freigabe.signaturPasst(null, alt));
        assertNotNull("fremde Installation", Freigabe.signaturPasst(neu, FREMD));
    }

    @Test
    public void versionJsonMitNeuemUndAltemZertifikat() throws Exception {
        assertEquals(Freigabe.ZERTIFIKAT, Freigabe.lies(vj(Freigabe.ZERTIFIKAT)).zertifikat);
        assertEquals(Freigabe.ZERTIFIKAT_ALT, Freigabe.lies(vj(Freigabe.ZERTIFIKAT_ALT)).zertifikat);
        assertEquals(Freigabe.ZERTIFIKAT, Freigabe.lies(vj(Freigabe.ZERTIFIKAT)).alsJson().getString("zertifikatSha256"));
        try {
            Freigabe.lies(vj(FREMD));
            throw new AssertionError("fremdes Zertifikat angenommen");
        } catch (Freigabe.Fehler f) {
            assertEquals("signatur", f.grund);
        }
    }

    private static String vj(String cert) {
        return "{\"versionCode\":30,\"versionName\":\"0.1.15\",\"kanal\":\"stabil\",\"tag\":\"v0.1.15\","
                + "\"apk\":\"panoptes-0.1.15.apk\",\"sha256\":\"" + "b".repeat(64) + "\",\"zertifikatSha256\":\""
                + cert + "\",\"zertifikatLineage\":[\"" + Freigabe.ZERTIFIKAT_ALT + "\",\"" + Freigabe.ZERTIFIKAT
                + "\"],\"minVersion\":2}";
    }

    // ------------------------------------------------------------------ echter apksigner

    private static final File APKSIGNER = new File("/opt/android-sdk/build-tools/36.0.0/lib/apksigner.jar");

    @Test
    public void rotierteApkWirdAngenommenFremdeAbgelehnt() throws Exception {
        assumeTrue("apksigner nicht vorhanden", APKSIGNER.isFile());
        File d = tmp.newFolder();
        File alt = schluessel(d, "alt"), neu = schluessel(d, "neu"), fremd = schluessel(d, "fremd");
        File roh = new File(d, "roh.apk");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(roh))) {
            z.putNextEntry(new ZipEntry("a.txt"));
            z.write("panoptes".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        File lineage = new File(d, "lineage");
        lauf(java(), "-jar", APKSIGNER.getPath(), "rotate", "--out", lineage.getPath(),
                "--old-signer", "--ks", alt.getPath(), "--ks-pass", "pass:wegwerf1",
                "--new-signer", "--ks", neu.getPath(), "--ks-pass", "pass:wegwerf1");
        File rot = new File(d, "rot.apk");
        lauf(java(), "-jar", APKSIGNER.getPath(), "sign", "--ks", neu.getPath(), "--ks-pass", "pass:wegwerf1",
                "--lineage", lineage.getPath(), "--rotation-min-sdk-version", "28", "--min-sdk-version", "28",
                "--v1-signing-enabled", "false", "--v2-signing-enabled", "false", "--v3-signing-enabled", "true",
                "--out", rot.getPath(), roh.getPath());
        File fr = new File(d, "fremd.apk");
        lauf(java(), "-jar", APKSIGNER.getPath(), "sign", "--ks", fremd.getPath(), "--ks-pass", "pass:wegwerf1",
                "--min-sdk-version", "28", "--v1-signing-enabled", "false", "--v2-signing-enabled", "false",
                "--v3-signing-enabled", "true", "--out", fr.getPath(), roh.getPath());

        String fpAlt = fingerabdruck(alt), fpNeu = fingerabdruck(neu), fpFremd = fingerabdruck(fremd);
        assertEquals("v3-Unterzeichner der rotierten APK = neues Zertifikat", fpNeu, ApkZertifikat.sha256(rot));
        assertEquals(fpFremd, ApkZertifikat.sha256(fr));

        // gleiche Regel wie Freigabe.signaturPasst, mit den Wegwerf-Fingerabdrücken
        assertNull(Freigabe.signaturPasst(ApkZertifikat.sha256(rot), fpAlt, fpNeu, fpAlt));
        assertNull(Freigabe.signaturPasst(ApkZertifikat.sha256(rot), fpNeu, fpNeu, fpAlt));
        assertNotNull(Freigabe.signaturPasst(ApkZertifikat.sha256(fr), fpAlt, fpNeu, fpAlt));
        assertNotNull(Freigabe.signaturPasst(ApkZertifikat.sha256(fr), fpNeu, fpNeu, fpAlt));
    }

    private File schluessel(File d, String name) throws Exception {
        File ks = new File(d, name + ".p12");
        lauf(new File(System.getProperty("java.home"), "bin/keytool").getPath(), "-genkeypair", "-keystore",
                ks.getPath(), "-storetype", "PKCS12", "-alias", "k", "-storepass", "wegwerf1", "-keypass", "wegwerf1",
                "-dname", "CN=" + name, "-keyalg", "RSA", "-keysize", "2048", "-validity", "2");
        return ks;
    }

    private static String fingerabdruck(File ks) throws Exception {
        KeyStore k = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(ks)) {
            k.load(in, "wegwerf1".toCharArray());
        }
        Certificate c = k.getCertificate("k");
        return ApkZertifikat.hex(MessageDigest.getInstance("SHA-256").digest(c.getEncoded()));
    }

    private static String java() {
        return new File(System.getProperty("java.home"), "bin/java").getPath();
    }

    private static void lauf(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int rc = p.waitFor();
        if (rc != 0) throw new AssertionError(String.join(" ", cmd[cmd.length > 3 ? 3 : 0]) + " rc=" + rc + ": " + out);
    }
}
