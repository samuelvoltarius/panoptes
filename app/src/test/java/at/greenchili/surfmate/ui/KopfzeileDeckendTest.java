package at.greenchili.surfmate.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 0.1.39 X1: Die klebende Kopfzeile (.page-header) ist VOLL DECKEND, sonst läuft der Inhalt sichtbar unter dem Titel
 * durch. Glas mit Alpha reicht nicht: Chromium 74 am Auto hat kein backdrop-filter, und die @supports-not-Ausnahmen
 * erfassen .page-header nicht. Darum Volltonfarbe ohne Abhängigkeit vom Weichzeichner, in beiden Themen.
 */
public class KopfzeileDeckendTest {

    @Test
    public void kopfzeileIstVollDeckendAuchOhneBackdropFilter() throws Exception {
        String css = lies("src/main/assets/web/shared/styles.css");
        int i = css.indexOf("0.1.39 X1");
        assertTrue(i > 0);
        assertTrue("die durchsichtige Regel steht davor, die neue gewinnt",
                css.indexOf(".page-header, .mobile-header { background: transparent; }") < i);

        String block = regelNach(css, i, ".page-header {");
        String bg = wert(block, "background");
        assertEquals("var(--bg-base, var(--surface, #0A2238))", bg);
        assertFalse("kein Glas", bg.contains("glass") || bg.contains("rgba"));
        assertEquals("none", wert(block, "backdrop-filter"));

        // Keine spätere Regel macht die Kopfzeile wieder durchsichtig (außer display:none für Querformat-Ausnahmen).
        String danach = css.substring(css.indexOf(block) + block.length());
        Matcher m = Pattern.compile("(?m)^[^{}]*\\.page-header(?![-\\w])[^{}]*\\{([^}]*)\\}").matcher(danach);
        while (m.find()) {
            assertFalse("spätere .page-header-Regel setzt Hintergrund: " + m.group(), m.group(1).contains("background"));
        }

        // --bg-base -> --surface ist in beiden Themen eine Volltonfarbe (#RRGGBB, kein Alpha).
        String tok = lies("src/main/assets/web/shared/design-tokens.css");
        assertTrue(tok.contains("--bg-base: var(--surface);"));
        Matcher s = Pattern.compile("--surface:\\s*([^;]+);").matcher(tok);
        int n = 0;
        while (s.find()) {
            n++;
            assertTrue("--surface deckend: " + s.group(1), s.group(1).trim().matches("#[0-9A-Fa-f]{6}"));
        }
        assertEquals("dunkel und hell", 2, n);
    }

    @Test
    public void seitenLadenNeuenZwischenspeicherStand() throws Exception {
        Path web = Paths.get("src/main/assets/web/local");
        if (!Files.exists(web)) web = Paths.get("app").resolve("src/main/assets/web/local");
        try (Stream<Path> s = Files.list(web)) {
            s.filter(p -> p.toString().endsWith(".html")).forEach(p -> {
                try {
                    String h = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                    assertFalse(p.toString(), h.contains("styles.css?v=pn14") || h.contains("styles.css?v=pn15"));
                } catch (java.io.IOException e) { throw new RuntimeException(e); }
            });
        }
    }

    private static String regelNach(String css, int ab, String kopf) {
        int a = css.indexOf(kopf, ab);
        assertTrue(kopf, a > 0);
        int e = css.indexOf('}', a);
        return css.substring(a, e + 1);
    }

    private static String wert(String block, String eigenschaft) {
        Matcher m = Pattern.compile("(?m)^\\s*" + Pattern.quote(eigenschaft) + ":\\s*([^;]+);").matcher(block);
        assertTrue(eigenschaft + " fehlt", m.find());
        return m.group(1).trim();
    }

    private static String lies(String rel) throws Exception {
        Path p = Paths.get(rel);
        if (!Files.exists(p)) p = Paths.get("app").resolve(rel);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
