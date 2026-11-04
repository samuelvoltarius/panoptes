package at.greenchili.surfmate.automation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.json.JSONObject;
import org.junit.Test;

/**
 * 0.1.38.3 (gesehen im Demo-Profil: „On Change … Equal To“ englisch auf der Regeln-Seite): Jeder Regel-Text
 * ({@code new Label(id, "…")}, {@code sectionJson(id, "…")} in {@code automation/}) ist ein Katalog-Schlüssel und
 * steht in allen 5 Server-Katalogen. Ein englischer Klartext oder ein fehlender Schlüssel wird hier gefunden.
 */
public class RegelTexteUebersetztTest {

    private static final Pattern LABEL = Pattern.compile("new Label\\(\"[^\"]*\",\\s*\"([^\"]*)\"\\)");
    private static final Pattern SEKTION = Pattern.compile("sectionJson\\(\"[^\"]*\",\\s*\"([^\"]*)\"");

    private static Path wurzel() {
        Path p = Paths.get("app/src/main");
        return Files.isDirectory(p) ? p : Paths.get("src/main");
    }

    private static TreeSet<String> schluessel() throws Exception {
        TreeSet<String> k = new TreeSet<>();
        try (Stream<Path> s = Files.walk(wurzel().resolve("java/at/greenchili/surfmate/automation"))) {
            for (Path f : (Iterable<Path>) s.filter(x -> x.toString().endsWith(".java"))::iterator) {
                String q = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
                for (Pattern p : new Pattern[] {LABEL, SEKTION}) {
                    Matcher m = p.matcher(q);
                    while (m.find()) k.add(m.group(1));
                }
            }
        }
        return k;
    }

    private static boolean hat(JSONObject katalog, String key) {
        Object cur = katalog;
        for (String t : key.split("\\.")) {
            if (!(cur instanceof JSONObject) || !((JSONObject) cur).has(t)) return false;
            cur = ((JSONObject) cur).opt(t);
        }
        return cur instanceof String && !((String) cur).isEmpty();
    }

    @Test
    public void keinEnglischerKlartextInRegelTexten() throws Exception {
        List<String> roh = new ArrayList<>();
        for (String k : schluessel()) if (!k.matches("[a-z_]+\\.[A-Za-z0-9_.]+")) roh.add(k);
        assertEquals("Regel-Texte ohne Katalog-Schlüssel (werden nicht übersetzt)", "[]", roh.toString());
    }

    @Test
    public void jederRegelTextInAllenSprachen() throws Exception {
        TreeSet<String> k = schluessel();
        assertTrue(k.size() > 300);
        for (String lang : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject kat = new JSONObject(new String(Files.readAllBytes(
                    wurzel().resolve("assets/server-i18n/" + lang + ".json")), StandardCharsets.UTF_8));
            List<String> fehlt = new ArrayList<>();
            for (String s : k) if (!hat(kat, s)) fehlt.add(s);
            assertEquals(lang + ": fehlende Regel-Texte", "[]", fehlt.toString());
        }
    }

    @Test
    public void vergleicheUndAbschnitteAlsKlartext() throws Exception {
        JSONObject de = new JSONObject(new String(Files.readAllBytes(
                wurzel().resolve("assets/server-i18n/de.json")), StandardCharsets.UTF_8)).getJSONObject("automation");
        assertEquals("ist gleich", de.getString("cmp_eq"));
        assertEquals("Sobald sich ändert:", de.getString("section_triggers"));
        assertEquals("dann", de.getString("section_actions"));
    }
}
