package at.greenchili.surfmate.einstellungen;

import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.testutil.Quelltext;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 0.1.38.10 (Befund backup-8): Jedes sichtbare Katalog-Feld muss in der Web-Oberfläche irgendwo gezeichnet werden.
 * Sonst gibt es einen Schalter, den die Einstellungs-Suche findet, aber niemand bedienen kann.
 *
 * <p>Gezeichnet heißt hier (Quelltext, alle Seiten und Bausteine unter assets/web außer vendor und i18n):
 * (a) der Schlüssel steht ausdrücklich in einer Datei (z. B. {@code nur: […]} oder ein eigener Baustein), oder
 * (b) ein {@code zeichne(…, {gruppe: 'G'})} ohne {@code nur} zeichnet die Gruppe des Felds (Abschnitt passend,
 * Feld nicht unter {@code ohne}).
 */
public class KatalogGezeichnetTest {

    private static final Path WEB = Paths.get("src/main/assets/web");
    private static final Pattern GRUPPE = Pattern.compile("gruppe:\\s*'([a-zA-Z_]+)'");
    private static final Pattern ABSCHNITT = Pattern.compile("abschnitt:\\s*'([a-zA-Z_]+)'");
    private static final Pattern OHNE = Pattern.compile("ohne:\\s*\\[([^\\]]*)\\]");

    private static final class Aufruf {
        String gruppe, abschnitt;
        boolean nur;
        String ohne = "";
    }

    private static List<String> quellen() throws IOException {
        List<String> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(Quelltext.aufloesen(WEB))) {
            for (Path p : s.filter(Files::isRegularFile).collect(Collectors.toList())) {
                String n = p.toString().replace('\\', '/');
                if (n.contains("/vendor/") || n.contains("/i18n/")) continue;
                if (!(n.endsWith(".html") || n.endsWith(".js"))) continue;
                out.add(Quelltext.lies(p));
            }
        }
        return out;
    }

    private static List<Aufruf> aufrufe(List<String> quellen) {
        List<Aufruf> out = new ArrayList<>();
        for (String q : quellen) {
            for (String o : zeichneAufrufe(q)) {
                Matcher g = GRUPPE.matcher(o);
                if (!g.find()) continue;
                Aufruf a = new Aufruf();
                a.gruppe = g.group(1);
                Matcher ab = ABSCHNITT.matcher(o);
                a.abschnitt = ab.find() ? ab.group(1) : null;
                a.nur = o.contains("nur:");
                Matcher oh = OHNE.matcher(o);
                if (oh.find()) a.ohne = oh.group(1);
                out.add(a);
            }
        }
        return out;
    }

    /** Text jedes Aufrufs {@code zeichne( … )} bis zur passenden schließenden Klammer (Optionen dürfen verschachtelt sein). */
    static List<String> zeichneAufrufe(String q) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while ((i = q.indexOf("zeichne(", i)) >= 0) {
            int tiefe = 0, j = i + "zeichne".length();
            for (; j < q.length(); j++) {
                char c = q.charAt(j);
                if (c == '(') tiefe++;
                else if (c == ')' && --tiefe == 0) break;
            }
            out.add(q.substring(i, Math.min(j + 1, q.length())));
            i = j;
        }
        return out;
    }

    private static boolean gezeichnet(Feld f, List<String> quellen, List<Aufruf> aufrufe) {
        String k1 = "'" + f.key + "'", k2 = "\"" + f.key + "\"";
        for (String q : quellen) {
            if (q.contains(k1) || q.contains(k2)) return true;
        }
        for (Aufruf a : aufrufe) {
            if (a.nur || !a.gruppe.equals(f.gruppe)) continue;
            if (a.abschnitt != null && !a.abschnitt.equals(f.abschnitt)) continue;
            if (a.ohne.contains("'" + f.key + "'")) continue;
            return true;
        }
        return false;
    }

    @Test
    public void jedesSichtbareFeldWirdGezeichnet() throws Exception {
        Katalog.Kontext k = new Katalog.Kontext();
        k.cluster = true;
        k.hud = true;   // auch Felder, die nur mit Kombiinstrument/HUD erscheinen
        List<String> quellen = quellen();
        assertTrue("keine Web-Quellen gefunden", quellen.size() > 20);
        List<Aufruf> aufrufe = aufrufe(quellen);
        TreeSet<String> fehlen = new TreeSet<>();
        for (Feld f : Katalog.felder(k)) {
            if (f.verborgen) continue;
            if (!gezeichnet(f, quellen, aufrufe)) fehlen.add(f.key + " (" + f.gruppe + ")");
        }
        TreeSet<String> neu = new TreeSet<>(fehlen);
        neu.removeAll(OFFEN);
        assertTrue("Diese sichtbaren Katalog-Felder zeichnet keine Seite: " + neu, neu.isEmpty());
        TreeSet<String> erledigt = new TreeSet<>(OFFEN);
        erledigt.removeAll(fehlen);
        assertTrue("Jetzt gezeichnet, bitte aus OFFEN streichen: " + erledigt, erledigt.isEmpty());
    }

    /**
     * Bekannte Lücken. Leer seit 0.1.38.10 (fix-einstellungen-sichtbar: die 14 Felder der Gruppe „system“ zeichnet
     * system.html bzw. display.html, Liste KARTEN_FELDER/SEITEN_FELDER in system-overview.js). Bleibt leer.
     */
    static final java.util.Set<String> OFFEN = new TreeSet<>();
}
