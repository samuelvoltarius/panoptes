package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 0.1.38.7 Chromium 74 (Deploy (2): die WebView am Auto ist 74.0.3729.186). Zwei Sorten Fehler:
 * <ul>
 *   <li><b>rot</b> – JS-Syntax, die die ganze Datei mit einem Syntaxfehler tötet ({@code ?.}, {@code ??}, {@code ||=},
 *       {@code &&=}, Numeric Separators, private Methoden, {@code static { }}, Top-Level-await in Modulen);</li>
 *   <li><b>gelb</b> – JS-APIs, die es erst später gibt ({@code replaceAll}, {@code at()}, {@code Object.hasOwn},
 *       {@code structuredClone}, {@code Promise.any/allSettled}, …);</li>
 *   <li><b>grau</b> – CSS, das 74 ignoriert: {@code :is/:where/:has}, {@code :not(a, b)}, Selektorlisten mit
 *       {@code :focus-visible} (die ganze Regel fällt weg), {@code appearance} ohne {@code -webkit-}, und
 *       {@code min()/max()/clamp()/color-mix()/dvh} ohne vorherige gewöhnliche Angabe derselben Eigenschaft.</li>
 * </ul>
 * Es gibt keinen JS-Parser in den Testabhängigkeiten, deshalb ein Lexer (Strings, Kommentare, Regex, Templates) mit
 * Mustern auf dem reinen Code. Fremdbibliotheken (vendor, maplibre, *.min.*) bleiben außen vor. Flex-{@code gap} (ab 84)
 * bildet zur Laufzeit {@code shared/kompat.js} nach (von {@code app-shell.js} geladen), siehe {@link #flexGapRueckfallKompat}.
 */
public class Chromium74WebTest {

    // ------------------------------------------------------------------ Lexer

    /** Code ohne Strings, Kommentare und Regex-Körper (Zeilenumbrüche bleiben, ${…} in Templates bleibt Code). */
    static String code(String src) {
        int n = src.length();
        char[] out = src.toCharArray();
        Deque<Integer> stack = new ArrayDeque<>();
        int brace = 0;
        char prev = 0;
        String prevWord = "";
        Set<String> kw = new HashSet<>(Arrays.asList("return", "typeof", "instanceof", "in", "of", "new", "delete", "void",
                "throw", "case", "do", "else", "yield", "await"));
        int i = 0;
        while (i < n) {
            char c = src.charAt(i);
            if (c == '\n') { i++; continue; }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                int j = src.indexOf('\n', i);
                j = j < 0 ? n : j;
                blank(out, i, j); i = j; continue;
            }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int j = src.indexOf("*/", i + 2);
                j = j < 0 ? n : j + 2;
                blank(out, i, j); i = j; continue;
            }
            if (c == '\'' || c == '"') {
                int j = i + 1;
                while (j < n && src.charAt(j) != c && src.charAt(j) != '\n') j += src.charAt(j) == '\\' ? 2 : 1;
                blank(out, i, Math.min(j + 1, n)); i = j + 1; prev = c; prevWord = ""; continue;
            }
            if (c == '`' || (c == '}' && !stack.isEmpty() && brace == 0)) {
                int j;
                if (c == '}') {
                    brace = stack.pop();
                    blank(out, i, i + 1);
                    j = i + 1;
                } else {
                    blank(out, i, i + 1);
                    j = i + 1;
                }
                int txt = j;
                boolean ende = false;
                while (j < n) {
                    char d = src.charAt(j);
                    if (d == '\\') { j += 2; continue; }
                    if (d == '`') { blank(out, txt, j + 1); j++; ende = true; break; }
                    if (d == '$' && j + 1 < n && src.charAt(j + 1) == '{') {
                        blank(out, txt, j + 2);
                        stack.push(brace); brace = 0;
                        j += 2; break;
                    }
                    j++;
                }
                if (!ende && j >= n) blank(out, txt, n);
                i = j;
                if (ende) { prev = '`'; prevWord = ""; }
                continue;
            }
            if (c == '{') { brace++; prev = c; prevWord = ""; i++; continue; }
            if (c == '}') { brace = Math.max(0, brace - 1); prev = c; prevWord = ""; i++; continue; }
            if (c == '/') {
                boolean regex;
                if (prev == 0) regex = true;
                else if (")]}".indexOf(prev) >= 0) regex = false;
                else if (Character.isLetterOrDigit(prev) || prev == '_' || prev == '$') regex = kw.contains(prevWord);
                else regex = true;
                if (regex) {
                    int j = i + 1;
                    boolean cls = false;
                    while (j < n && src.charAt(j) != '\n') {
                        char d = src.charAt(j);
                        if (d == '\\') { j += 2; continue; }
                        if (d == '[') cls = true;
                        else if (d == ']') cls = false;
                        else if (d == '/' && !cls) break;
                        j++;
                    }
                    if (j < n && src.charAt(j) == '/') {
                        int k = j + 1;
                        while (k < n && Character.isLowerCase(src.charAt(k))) k++;
                        blank(out, i, j + 1);
                        i = k; prev = ')'; prevWord = ""; continue;
                    }
                }
            }
            if (Character.isLetter(c) || c == '_' || c == '$') {
                int j = i;
                while (j < n && (Character.isLetterOrDigit(src.charAt(j)) || src.charAt(j) == '_' || src.charAt(j) == '$')) j++;
                prev = src.charAt(j - 1); prevWord = src.substring(i, j); i = j; continue;
            }
            if (Character.isDigit(c)) {
                int j = i;
                while (j < n && (Character.isLetterOrDigit(src.charAt(j)) || src.charAt(j) == '_' || src.charAt(j) == '.')) j++;
                // Numeric Separator sichtbar lassen: Ziffern + _ + Ziffer
                prev = '0'; prevWord = ""; i = j; continue;
            }
            if (!Character.isWhitespace(c)) { prev = c; prevWord = ""; }
            i++;
        }
        return new String(out);
    }

    private static void blank(char[] out, int a, int b) {
        for (int k = a; k < b && k < out.length; k++) if (out[k] != '\n') out[k] = ' ';
    }

    // ------------------------------------------------------------------ Muster

    private static final String[][] ROT = {
            {"?. (Optional Chaining, ab 80)", "\\?\\.(?!\\d)"},
            {"?? (Nullish Coalescing, ab 80)", "(?<!\\?)\\?\\?(?!\\?)"},
            {"||= &&= ??= (ab 85)", "(\\|\\||&&|\\?\\?)="},
            {"Numeric Separator (ab 75)", "(?<![A-Za-z_$\\d.])\\d+_\\d"},
            {"Private Methode #m() (ab 84)", "(?<![\\w$.#])#[A-Za-z_$][\\w$]*\\s*\\("},
            {"#x in obj (ab 91)", "(?<![\\w$])#[A-Za-z_$][\\w$]*\\s+in\\s"},
            {"static { } (ab 94)", "\\bstatic\\s*\\{"},
    };
    private static final String[][] GELB = {
            {"replaceAll (ab 85)", "\\.replaceAll\\s*\\("},
            {".at() (ab 92)", "\\.at\\s*\\(\\s*-?\\d*\\s*\\)"},
            {"Object.hasOwn (ab 93)", "\\bObject\\.hasOwn\\b"},
            {"structuredClone (ab 98)", "\\bstructuredClone\\b"},
            {"Promise.any / AggregateError (ab 85)", "\\bPromise\\.any\\b|\\bAggregateError\\b"},
            {"Promise.allSettled (ab 76)", "\\bPromise\\.allSettled\\b"},
            {"findLast (ab 97)", "\\.findLast(Index)?\\s*\\("},
            {"toSorted/toReversed/toSpliced (ab 110)", "\\.(toSorted|toReversed|toSpliced)\\s*\\("},
            {"replaceChildren (ab 86)", "\\.replaceChildren\\s*\\("},
            {"crypto.randomUUID (ab 92)", "\\brandomUUID\\b"},
            {"AbortSignal.timeout/any (ab 103)", "\\bAbortSignal\\.(timeout|any)\\b"},
            {"WeakRef/FinalizationRegistry (ab 84)", "\\bWeakRef\\b|\\bFinalizationRegistry\\b"},
            {"Intl.Segmenter/DisplayNames (ab 81/87)", "\\bIntl\\.(Segmenter|DisplayNames)\\b"},
            {"Array.fromAsync/groupBy/withResolvers (neu)", "\\b(Array\\.fromAsync|Object\\.groupBy|Map\\.groupBy|Promise\\.withResolvers)\\b"},
            {"checkVisibility (ab 105)", "\\.checkVisibility\\s*\\("},
            {"showPicker/showPopover (ab 99/114)", "\\.showPicker\\s*\\(|\\.showPopover\\s*\\("},
            {"borderBoxSize (ab 84)", "\\.borderBoxSize\\b|\\.contentBoxSize\\b"},
            {"Intl dateStyle/timeStyle/notation/unit (ab 76/77)", "\\b(dateStyle|timeStyle|fractionalSecondDigits|signDisplay)\\s*:|style\\s*:\\s*['\"]unit['\"]"},
    };

    static List<String> jsFehler(String name, String src, boolean modul) {
        String c = code(src);
        List<String> f = new ArrayList<>();
        for (String[][] gruppe : new String[][][]{ROT, GELB}) {
            for (String[] r : gruppe) {
                Matcher m = Pattern.compile(r[1]).matcher(c);
                while (m.find()) f.add(name + ":" + zeile(c, m.start()) + " " + r[0]);
            }
        }
        if (modul) {
            Matcher m = Pattern.compile("(?m)^(?!\\s*(async\\s+)?function|.*=>|.*\\{)\\s*await\\s").matcher(c);
            while (m.find()) f.add(name + ":" + zeile(c, m.start()) + " Top-Level-await (ab 89)");
        }
        return f;
    }

    private static int zeile(String s, int pos) {
        int z = 1;
        for (int i = 0; i < pos && i < s.length(); i++) if (s.charAt(i) == '\n') z++;
        return z;
    }

    // ------------------------------------------------------------------ CSS

    private static final Pattern RISIKO = Pattern.compile(
            "(?<![-\\w.])(min|max|clamp)\\s*\\(|color-mix\\s*\\(|\\d(dvh|svh|lvh|dvw|svw|lvw)\\b|(?<![-\\w])(lab|lch|oklab|oklch)\\s*\\(");

    static List<String> cssFehler(String name, String css) {
        String c = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL).matcher(css).replaceAll(m -> m.group().replaceAll("[^\\n]", " "));
        List<String> f = new ArrayList<>();
        Matcher bm = Pattern.compile("([^{}]*)\\{([^{}]*)\\}").matcher(c);
        while (bm.find()) {
            String sel = bm.group(1).trim();
            String body = bm.group(2).replaceAll("url\\([^)]*\\)", "url()");
            int z = zeile(c, bm.start(2));
            // Selektorfehler: ganze Regel ungültig
            if (sel.startsWith("@")) continue;
            if (Pattern.compile(":(is|where|has)\\(").matcher(sel).find()) f.add(name + ":" + z + " :is/:where/:has in " + abk(sel));
            if (Pattern.compile(":not\\([^)]*,").matcher(sel).find()) f.add(name + ":" + z + " :not(a, b) in " + abk(sel));
            if (sel.contains(":focus-visible") && sel.contains(",")) f.add(name + ":" + z + " Selektorliste mit :focus-visible in " + abk(sel));
            // Eigenschaften
            String[] decl = body.split(";");
            Set<String> gewoehnlich = new HashSet<>();
            for (String d : decl) {
                int k = d.indexOf(':');
                if (k < 0) continue;
                String prop = d.substring(0, k).trim().toLowerCase();
                String val = d.substring(k + 1).trim();
                if (prop.startsWith("--")) continue;
                if (prop.equals("appearance")) {
                    boolean mit = false;
                    for (String d2 : decl) if (d2.trim().toLowerCase().startsWith("-webkit-appearance")) mit = true;
                    if (!mit) f.add(name + ":" + z + " appearance ohne -webkit-appearance in " + abk(sel));
                }
                if (RISIKO.matcher(val).find()) {
                    if (!gewoehnlich.contains(prop)) f.add(name + ":" + z + " " + prop + ": " + abk(val) + " ohne Rückfall davor in " + abk(sel));
                } else {
                    gewoehnlich.add(prop);
                }
            }
        }
        return f;
    }

    private static String abk(String s) {
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 50 ? s.substring(0, 50) + "…" : s;
    }

    // ------------------------------------------------------------------ Web-Assets lesen

    private static Path web() {
        Path p = Paths.get("src/main/assets/web");
        return Files.exists(p) ? p : Paths.get("app/src/main/assets/web");
    }

    private static List<Path> dateien() throws IOException {
        try (Stream<Path> s = Files.walk(web())) {
            return s.filter(Files::isRegularFile).filter(p -> {
                String n = p.toString().replace('\\', '/').toLowerCase();
                return (n.endsWith(".css") || n.endsWith(".html") || n.endsWith(".js"))
                        && !n.contains("vendor") && !n.contains("maplibre") && !n.contains(".min.");
            }).collect(Collectors.toList());
        }
    }

    private static String lies(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private static List<String> verschiebe(List<String> fehler, String name, int off) {
        List<String> f = new ArrayList<>();
        for (String e : fehler) {
            int k = e.indexOf(':'), k2 = e.indexOf(' ', k);
            f.add(name + ":" + (Integer.parseInt(e.substring(k + 1, k2)) + off) + e.substring(k2));
        }
        return f;
    }

    /** JS-Fehler einer Datei: .js, <script>-Blöcke und on…-Attribute einer .html. */
    static List<String> jsAnteile(String name, String inhalt) {
        List<String> f = new ArrayList<>();
        if (name.endsWith(".js")) return jsFehler(name, inhalt, false);
        if (!name.endsWith(".html")) return f;
        Matcher sm = Pattern.compile("<script\\b([^>]*)>(.*?)</script>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(inhalt);
        while (sm.find()) {
            String attrs = sm.group(1);
            if (Pattern.compile("\\bsrc\\s*=", Pattern.CASE_INSENSITIVE).matcher(attrs).find()) continue;
            Matcher tm = Pattern.compile("type\\s*=\\s*[\"']?([^\"'\\s>]+)", Pattern.CASE_INSENSITIVE).matcher(attrs);
            String typ = tm.find() ? tm.group(1).toLowerCase() : "";
            if (!typ.isEmpty() && !typ.equals("text/javascript") && !typ.equals("module") && !typ.equals("application/javascript")) continue;
            f.addAll(verschiebe(jsFehler(name, sm.group(2), typ.equals("module")), name, zeile(inhalt, sm.start(2)) - 1));
        }
        Matcher hm = Pattern.compile("\\s(on[a-z]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')", Pattern.CASE_INSENSITIVE).matcher(inhalt);
        while (hm.find()) {
            String h = hm.group(3) != null ? hm.group(3) : hm.group(4);
            f.addAll(verschiebe(jsFehler(name, h, false), name, zeile(inhalt, hm.start()) - 1));
        }
        return f;
    }

    /** CSS-Fehler einer Datei: .css und <style>-Blöcke einer .html. */
    static List<String> cssAnteile(String name, String inhalt) {
        List<String> f = new ArrayList<>();
        if (name.endsWith(".css")) return cssFehler(name, inhalt);
        if (!name.endsWith(".html")) return f;
        Matcher stm = Pattern.compile("<style\\b[^>]*>(.*?)</style>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(inhalt);
        while (stm.find()) f.addAll(verschiebe(cssFehler(name, stm.group(1)), name, zeile(inhalt, stm.start(1)) - 1));
        return f;
    }

    // ------------------------------------------------------------------ Tests

    @Test
    public void keinJsSyntaxfehlerKeineFehlendenApis() throws IOException {
        List<Path> d = dateien();
        assertTrue("web-Assets gefunden", d.size() > 100);
        List<String> f = new ArrayList<>();
        for (Path p : d) f.addAll(jsAnteile(web().relativize(p).toString().replace('\\', '/'), lies(p)));
        assertEquals("rot/gelb (JS) für Chromium 74: " + f, 0, f.size());
    }

    @Test
    public void keinCssDasChromium74VerwirftOhneRueckfall() throws IOException {
        List<String> f = new ArrayList<>();
        for (Path p : dateien()) f.addAll(cssAnteile(web().relativize(p).toString().replace('\\', '/'), lies(p)));
        assertEquals("grau (CSS) für Chromium 74: " + f, 0, f.size());
    }

    @Test
    public void webUiZielIstFuerEntwicklerDokumentiert() throws IOException {
        for (String d : new String[]{"CODEKARTE.md", "LAUNCHER_SPEC.md"}) {
            Path p = Paths.get("../docs/" + d);
            if (!Files.exists(p)) p = Paths.get("docs/" + d);
            String t = lies(p);
            assertTrue(d + " nennt das Web-UI-Ziel", t.contains("Web-UI-Ziel = Chrome 74"));
            if (d.equals("LAUNCHER_SPEC.md")) assertTrue(d + " nennt die WebView-Version", t.contains("74.0.3729.186"));
        }
    }

    @Test
    public void lexerUndMusterErkennenDasRichtige() {
        String js = "var a = b?.c; var d = e ?? f; x ||= 1; s.replaceAll('a','b'); var n = 1_000; Promise.allSettled([]);\n"
                + "var t = `x ${u?.v} y`; var ok = a ? .5 : 1; var r = /a?.b ?? c/g; // x?.y\n/* y ?? z */ var s2 = \"a?.b ?? c\";";
        List<String> f = jsFehler("t.js", js, false);
        String alles = String.join("|", f);
        for (String erwartet : new String[]{"Optional Chaining", "Nullish", "||= &&=", "replaceAll", "Numeric Separator", "allSettled"}) {
            assertTrue(erwartet + " in " + alles, alles.contains(erwartet));
        }
        assertEquals("Optional Chaining nur in Code und in ${…}, nicht in Strings, Regex, Kommentaren", 2, f.stream().filter(e -> e.contains("Optional Chaining")).count());
        assertEquals(1, f.stream().filter(e -> e.contains("Nullish")).count());
        // CSS
        assertEquals(1, cssFehler("t.css", ".x { -webkit-mask: none; appearance: none; }").size());
        assertEquals(0, cssFehler("t.css", ".x { -webkit-appearance: none; appearance: none; }").size());
        assertEquals(1, cssFehler("t.css", ".x { width: min(10px, 5vw); }").size());
        assertEquals(0, cssFehler("t.css", ".x { width: 10px; width: min(10px, 5vw); }").size());
        assertEquals(1, cssFehler("t.css", ".a:hover, .a:focus-visible { color: red; }").size());
        assertEquals(0, cssFehler("t.css", ".a:hover { color: red; }\n.a:focus-visible { color: red; }").size());
        assertEquals(1, cssFehler("t.css", ".a:is(.b, .c) { color: red; }").size());
        assertEquals(1, cssFehler("t.css", "a:not(.b, .c) { color: red; }").size());
        assertEquals(1, cssFehler("t.css", ".x { min-height: 100dvh; }").size());
        assertEquals(0, cssFehler("t.css", ".x { min-height: 100vh; min-height: 100dvh; }").size());
        assertEquals("url(data:…;base64) hat Semikolons, kein Fehlalarm", 0,
                cssFehler("t.css", ".x { background: url(data:image/svg+xml;base64,AAA) no-repeat; }").size());
    }

    /** Flex-{@code gap}: {@code kompat.js} bildet es nach, wird von {@code app-shell.js} geladen und zählt auch Text im Container. */
    @Test
    public void flexGapRueckfallKompat() throws IOException {
        String k = lies(web().resolve("shared/kompat.js"));
        assertTrue("Erkennung per row-gap in einer Flex-Spalte", k.contains("row-gap:1px") && k.contains("scrollHeight === 1"));
        assertTrue("nur Flex, Grid-gap bleibt", k.contains("cs.display !== 'flex' && cs.display !== 'inline-flex'"));
        assertTrue("0.1.38.7: Text direkt im Container ist ein Flex-Element (Symbol + „22 km“)",
                k.contains("c.nodeType === 3") && k.contains("el.childNodes"));
        assertTrue("neue Inhalte", k.contains("MutationObserver"));
        assertTrue("app-shell.js lädt kompat.js", lies(web().resolve("shared/app-shell.js")).contains("shared/kompat.js"));
    }
}
