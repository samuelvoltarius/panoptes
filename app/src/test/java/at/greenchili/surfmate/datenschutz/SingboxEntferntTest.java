package at.greenchili.surfmate.datenschutz;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.daemon.proxy.Safe;

import org.junit.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Regel Nr. 2 / Koordination 21.10.: Der sing-box-Proxy mit festverdrahtetem Fremdserver ist samt
 * Binary, Startpfad, Einstellungen und Oberfläche entfernt. Die Serveradresse darf weder im Klartext
 * noch verschlüsselt (Safe.s) irgendwo in der App stehen.
 */
public class SingboxEntferntTest {

    /** Zusammengesetzt, damit dieser Test selbst die Adresse nicht im Klartext enthält. */
    private static final String ADRESSE = "80.225." + "224.92";
    private static final Path MAIN = Paths.get("src/main");
    private static final Pattern SAFE = Pattern.compile("Safe\\.s\\(\"([A-Za-z0-9+/=]+)\"\\)");

    @Test
    public void adresseNirgendsImKlartext() throws Exception {
        List<String> treffer = new ArrayList<>();
        for (Path p : dateien()) {
            String t = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
            if (t.contains(ADRESSE)) treffer.add(p.toString());
        }
        assertTrue("Adresse gefunden in " + treffer, treffer.isEmpty());
    }

    @Test
    public void adresseNirgendsVerschluesselt() throws Exception {
        Cipher c = entschluessler();
        // Positivfall: die Entschlüsselung funktioniert wirklich (sonst wäre der Test blind).
        assertEquals("at.greenchili.surfmate", entschluessle(c, ersteKonstante("daemon/proxy/Enc.java")));
        int geprueft = 0;
        for (Path p : dateien()) {
            String n = p.toString();
            if (!n.endsWith(".java") && !n.endsWith(".kt")) continue;
            Matcher m = SAFE.matcher(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
            while (m.find()) {
                String klar = entschluessle(c, m.group(1));
                assertFalse("verschlüsselte Adresse in " + n, klar.contains(ADRESSE));
                geprueft++;
            }
        }
        assertTrue(geprueft >= 1);
    }

    @Test
    public void proxyTeileWeg() throws Exception {
        assertFalse(Files.exists(MAIN.resolve("jniLibs/arm64-v8a/libsingbox.so")));
        String j = "java/at/greenchili/surfmate/";
        assertFalse(Files.exists(MAIN.resolve(j + "daemon/GlobalProxyDaemon.java")));
        assertFalse(Files.exists(MAIN.resolve(j + "launcher/SingboxLauncher.kt")));
        assertFalse(Files.exists(MAIN.resolve(j + "ui/daemon/SingboxController.kt")));
        assertFalse(Files.exists(MAIN.resolve(j + "daemon/proxy/ProxyConfiguration.kt")));
        String enc = new String(Files.readAllBytes(MAIN.resolve(j + "daemon/proxy/Enc.java")), StandardCharsets.UTF_8);
        assertFalse(enc.contains("PROXY_"));
        assertFalse(enc.contains("SINGBOX"));
        String typ = new String(Files.readAllBytes(MAIN.resolve(j + "ui/model/DaemonType.kt")), StandardCharsets.UTF_8);
        assertFalse(typ.contains("SINGBOX"));
    }

    private static List<Path> dateien() throws Exception {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(MAIN)) {
            s.filter(Files::isRegularFile).forEach(out::add);
        }
        assertTrue(out.size() > 100);
        return out;
    }

    private static String ersteKonstante(String rel) throws Exception {
        Matcher m = SAFE.matcher(new String(Files.readAllBytes(
                MAIN.resolve("java/at/greenchili/surfmate/" + rel)), StandardCharsets.UTF_8));
        assertTrue(m.find());
        return m.group(1);
    }

    /** Gleicher Schlüssel wie {@link Safe}, aber mit java.util.Base64 (android.util ist im JVM-Test nicht da). */
    private static Cipher entschluessler() throws Exception {
        byte[] key = new byte[32];
        for (int i = 0; i < 4; i++) {
            System.arraycopy(feld("K_PART_" + (i + 1)), 0, key, i * 8, 8);
        }
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5PADDING");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(feld("I_RAW")));
        return c;
    }

    private static byte[] feld(String name) throws Exception {
        Field f = Safe.class.getDeclaredField(name);
        f.setAccessible(true);
        return (byte[]) f.get(null);
    }

    private static String entschluessle(Cipher c, String b64) throws Exception {
        return new String(c.doFinal(Base64.getDecoder().decode(b64)), StandardCharsets.UTF_8);
    }
}
