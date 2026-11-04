package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 0.1.38.8: Die WebView am Auto ist Chromium 74 und kennt {@code backdrop-filter} nicht (erst ab 76). Jede Glasfläche,
 * deren Hintergrund dünner als 0,9 deckt, braucht dort einen Ersatz: einen Block
 * {@code @supports not ((backdrop-filter: blur(1px)) or (-webkit-backdrop-filter: blur(1px)))} mit demselben Selektor
 * direkt danach (damit spätere Regeln wie Hover oder helles Thema weiter gewinnen).
 * Sichtprüfung 04.11. auf AVD panoptes_surf29 (WebView 74) gegen surf30 (83), Bilder im Notizordner.
 */
public class BackdropErsatzTest {
    static final String ERSATZ = "@supports not ((backdrop-filter: blur(1px)) or (-webkit-backdrop-filter: blur(1px)))";
    private static final String MARKE = "@ersatz-chromium74";
    private static final Pattern GLAS = Pattern.compile("(?<![-\\w])backdrop-filter\\s*:\\s*[^;)]*blur");
    private static final Pattern RGBA = Pattern.compile("background(?:-color)?\\s*:\\s*rgba\\([^)]*,\\s*(0?\\.\\d+)\\s*\\)");

    /** Regeln mit dünnem Glas ohne Ersatz, als {@code datei: selektor}. */
    static List<String> ohneErsatz(String datei, String quelle) {
        // Die Bedingung selbst nennt backdrop-filter: vorher durch eine Marke ersetzen
        String s = quelle.replace(ERSATZ, MARKE);
        List<String> funde = new ArrayList<>();
        Matcher m = GLAS.matcher(s);
        while (m.find()) {
            int auf = s.lastIndexOf('{', m.start());
            int zu = s.indexOf('}', m.start());
            if (auf < 0 || zu < 0) continue;
            int kommentar = s.lastIndexOf("*/", auf);
            int anfang = Math.max(Math.max(s.lastIndexOf('}', auf), kommentar < 0 ? -1 : kommentar + 1),
                    s.lastIndexOf('{', auf - 1)) + 1;
            String selektor = s.substring(anfang, auf).trim().replaceAll("\\s+", " ");
            if (selektor.isEmpty() || selektor.startsWith("@")) continue;
            Matcher bg = RGBA.matcher(s.substring(auf, zu));
            if (!bg.find() || Double.parseDouble(bg.group(1)) >= 0.9) continue;
            int ersatz = s.indexOf(MARKE, zu);
            String danach = ersatz < 0 ? ""
                    : s.substring(ersatz, Math.min(s.length(), ersatz + MARKE.length() + selektor.length() + 200));
            if (!danach.replaceAll("\\s+", " ").contains(selektor)) funde.add(datei + ": " + selektor);
        }
        return funde;
    }

    @Test
    public void duennesGlasHatEinenErsatzFuerChromium74() throws Exception {
        Path web = Paths.get("src/main/assets/web");
        List<String> funde = new ArrayList<>();
        try (Stream<Path> dateien = Files.walk(web)) {
            for (Path p : (Iterable<Path>) dateien::iterator) {
                String n = p.toString().replace('\\', '/');
                if (!(n.endsWith(".css") || n.endsWith(".html")) || n.contains("/vendor/") || n.contains(".min.")) continue;
                funde.addAll(ohneErsatz(web.relativize(p).toString().replace('\\', '/'),
                        new String(Files.readAllBytes(p), StandardCharsets.UTF_8)));
            }
        }
        assertEquals("Glas ohne Chromium-74-Ersatz: " + funde, 0, funde.size());
    }

    @Test
    public void erkenntFehlendenErsatz() {
        String ohne = ".leiste { background: rgba(0, 0, 0, 0.5); backdrop-filter: blur(8px); }";
        assertEquals(1, ohneErsatz("x.css", ohne).size());
        String mit = ohne + "\n" + ERSATZ + " { .leiste { background: rgba(0, 0, 0, 0.8); } }";
        assertEquals(0, ohneErsatz("x.css", mit).size());
        assertEquals("dichtes Glas braucht keinen Ersatz", 0,
                ohneErsatz("x.css", ".tip { background: rgba(10, 10, 15, 0.96); backdrop-filter: blur(12px); }").size());
    }
}
