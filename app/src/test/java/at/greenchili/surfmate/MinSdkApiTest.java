package at.greenchili.surfmate;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 0.1.14.1 (Kern 7, Koordination): Am Auto (DiLink 3 = Android 10, API 29; minSdk 28) gibt es Java-9+-Instanzmethoden
 * wie {@code InputStream.readAllBytes()} nicht (Android erst ab API 33), und D8 portiert sie NICHT zurück – Folge war
 * NoSuchMethodError im Notzugang. Dieser Test verbietet solche Aufrufe im App-Quelltext (statische Backports wie
 * {@code List.of}/{@code Map.of} übersetzt D8 und sind erlaubt; {@code Files.readAllBytes(Path)} ist API 26).
 */
public class MinSdkApiTest {

    private static final Pattern[] VERBOTEN = {
            Pattern.compile("\\.readAllBytes\\(\\s*\\)"),          // InputStream.readAllBytes() – API 33
            Pattern.compile("\\.readNBytes\\("),                    // InputStream.readNBytes – API 33
            Pattern.compile("\\.skipNBytes\\("),                    // InputStream.skipNBytes – API 33
            Pattern.compile("\\bnullInputStream\\(|\\bnullOutputStream\\("),
            Pattern.compile("(?<![A-Za-z])(in|is|inputStream|stream|input)\\.transferTo\\("),   // InputStream.transferTo – API 33
            Pattern.compile("\\bFiles\\.readString\\(|\\bFiles\\.writeString\\("),            // Java 11 – API 33
            Pattern.compile("\\bPath\\.of\\("),                                                 // Java 11 – API 33
    };

    @Test
    public void keineApiUeberMinSdkImAppQuelltext() throws Exception {
        File wurzel = new File("src/main/java");
        if (!wurzel.isDirectory()) wurzel = new File("app/src/main/java");
        assertTrue(wurzel.getAbsolutePath(), wurzel.isDirectory());
        List<String> funde = new ArrayList<>();
        pruefe(wurzel, funde);
        assertTrue("Aufrufe über minSdk (Android 10 am Auto): " + funde, funde.isEmpty());
    }

    private static void pruefe(File f, List<String> funde) throws Exception {
        if (f.isDirectory()) {
            File[] k = f.listFiles();
            if (k != null) for (File x : k) pruefe(x, funde);
            return;
        }
        String n = f.getName();
        if (!n.endsWith(".java") && !n.endsWith(".kt")) return;
        String[] zeilen = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).split("\n");
        for (int i = 0; i < zeilen.length; i++) {
            String z = zeilen[i].trim();
            if (z.startsWith("//") || z.startsWith("*") || z.startsWith("/*")) continue;
            int kommentar = z.indexOf("//");
            if (kommentar >= 0) z = z.substring(0, kommentar);
            for (Pattern p : VERBOTEN) {
                if (p.matcher(z).find()) funde.add(f.getName() + ":" + (i + 1) + " " + z.trim());
            }
        }
    }
}
