package at.greenchili.surfmate.server;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 0.1.38.7 (Demo-Video): Die CSS-Kurzform {@code inset} gibt es erst ab Chrome 87, die WebView am Auto ist älter
 * (Chrome 83 am Emulator, am Fahrzeug ungeklärt). Der Clip-Player in events.html ({@code .video-modal} mit
 * {@code position: fixed; inset: 0}) lag deshalb außerhalb des Bildes. Jede Stelle mit {@code inset:} braucht in
 * derselben Regel zuerst {@code top/right/bottom/left}. Fremde Bibliotheken (vendor, maplibre, *.min.*) bleiben unberührt.
 */
public class CssInsetRueckfallTest {

    private static final Pattern INSET = Pattern.compile("(?<![-\\w])inset\\s*:\\s*([^;}'\"`]+)");
    private static final Pattern KOMMENTAR = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    private static Path web() {
        Path p = Paths.get("src/main/assets/web");
        return Files.exists(p) ? p : Paths.get("app/src/main/assets/web");
    }

    static boolean hatRueckfall(String s, int pos) {
        int start = Math.max(Math.max(s.lastIndexOf('{', pos), s.lastIndexOf('\'', pos)),
                Math.max(s.lastIndexOf('"', pos), s.lastIndexOf('`', pos))) + 1;
        String block = s.substring(start, pos);
        for (String k : new String[]{"top", "right", "bottom", "left"}) {
            if (!Pattern.compile("(?<![-\\w])" + k + "\\s*:").matcher(block).find()) return false;
        }
        return true;
    }

    static List<String> ohneRueckfall(String name, String inhalt) {
        List<int[]> kommentare = new ArrayList<>();
        Matcher km = KOMMENTAR.matcher(inhalt);
        while (km.find()) kommentare.add(new int[]{km.start(), km.end()});
        List<String> fehler = new ArrayList<>();
        Matcher m = INSET.matcher(inhalt);
        while (m.find()) {
            boolean imKommentar = false;
            for (int[] k : kommentare) if (m.start() >= k[0] && m.start() < k[1]) imKommentar = true;
            if (imKommentar || hatRueckfall(inhalt, m.start())) continue;
            int zeile = 1;
            for (int i = 0; i < m.start(); i++) if (inhalt.charAt(i) == '\n') zeile++;
            fehler.add(name + ":" + zeile + " " + m.group(0).trim());
        }
        return fehler;
    }

    @Test
    public void keinInsetOhneRueckfall() throws IOException {
        List<Path> dateien;
        try (Stream<Path> s = Files.walk(web())) {
            dateien = s.filter(Files::isRegularFile).filter(p -> {
                String n = p.toString().replace('\\', '/').toLowerCase();
                return (n.endsWith(".css") || n.endsWith(".html") || n.endsWith(".js"))
                        && !n.contains("vendor") && !n.contains("maplibre") && !n.contains(".min.");
            }).collect(Collectors.toList());
        }
        assertTrue("web-Assets gefunden", dateien.size() > 50);
        List<String> fehler = new ArrayList<>();
        for (Path p : dateien) {
            fehler.addAll(ohneRueckfall(web().relativize(p).toString().replace('\\', '/'),
                    new String(Files.readAllBytes(p), StandardCharsets.UTF_8)));
        }
        assertTrue("inset ohne top/right/bottom/left davor (Chrome < 87): " + fehler, fehler.isEmpty());
    }

    @Test
    public void erkennerFindetUndAkzeptiert() {
        assertTrue(ohneRueckfall("a.css", ".x { position: fixed; inset: 0; }").size() == 1);
        assertTrue(ohneRueckfall("a.css", ".x { position: fixed; top: 0; right: 0; bottom: 0; left: 0; inset: 0; }").isEmpty());
        assertTrue(ohneRueckfall("a.js", "e.style.cssText = 'position:fixed;inset:0;z-index:1';").size() == 1);
        assertTrue(ohneRueckfall("a.js", "e.style.cssText = 'position:fixed;top:0;right:0;bottom:0;left:0;inset:0;z-index:1';").isEmpty());
        assertTrue("Kommentar zählt nicht", ohneRueckfall("a.css", "/* NOT inset:0 */ .x { top: 0; }").isEmpty());
        assertTrue("box-shadow inset ist etwas anderes", ohneRueckfall("a.css", ".x { box-shadow: inset 0 1px #000; }").isEmpty());
        assertTrue("nur ein Teil der Kanten reicht nicht", ohneRueckfall("a.css", ".x { top: 0; left: 0; inset: 0; }").size() == 1);
    }

    @Test
    public void clipPlayerHatSeinenRueckfall() throws IOException {
        String ev = new String(Files.readAllBytes(web().resolve("local/events.html")), StandardCharsets.UTF_8);
        int i = ev.indexOf(".video-modal {");
        String regel = ev.substring(i, ev.indexOf('}', i));
        assertTrue(regel.contains("position: fixed"));
        assertTrue(regel.matches("(?s).*top:\\s*0;\\s*right:\\s*0;\\s*bottom:\\s*0;\\s*left:\\s*0;.*inset:\\s*0.*"));
    }
}
