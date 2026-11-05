package at.greenchili.surfmate.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 0.1.37 UI-Aufbau: Die Seitenliste in app-shell.js (SECTIONS) ist die eine Quelle für Leiste, Reiter und Suche.
 * Jede Seite unter web/local/ steht genau einmal im Menü oder auf der Versteckt-Liste, es gibt sieben Bereiche,
 * jeder Teil hat einen Reiternamen, und alle Menü-Schlüssel gibt es in allen fünf Sprachen.
 * Die Handy-App ruft acht Seiten direkt auf, die müssen im Menü bleiben.
 */
public class MenueEineQuelleTest {

    private static File basis() {
        return new File("src/main/assets").isDirectory() ? new File(".") : new File("app");
    }

    private static String lies(String pfad) throws Exception {
        return new String(Files.readAllBytes(new File(basis(), pfad).toPath()), StandardCharsets.UTF_8);
    }

    private static String shell() throws Exception {
        return lies("src/main/assets/web/shared/app-shell.js");
    }

    private static String block(String js, String anfang, String ende) {
        int a = js.indexOf(anfang);
        assertTrue(anfang, a >= 0);
        int b = js.indexOf(ende, a);
        assertTrue(ende, b > a);
        return js.substring(a, b);
    }

    private static String sections() throws Exception {
        return block(shell(), "var SECTIONS = [", "var TEILE = {");
    }

    @Test
    public void jedeSeiteStehtGenauEinmalImMenueOderIstVersteckt() throws Exception {
        String versteckt = block(shell(), "var VERSTECKT = [", "];");
        Matcher m = Pattern.compile("href: '([a-z0-9_-]+\\.html)'").matcher(sections());
        Set<String> doppelt = new TreeSet<>();
        Set<String> imMenue = new LinkedHashSet<>();
        while (m.find()) if (!imMenue.add(m.group(1))) doppelt.add(m.group(1));
        assertEquals("doppelt im Menü", "[]", doppelt.toString());

        Set<String> verborgen = new TreeSet<>();
        Matcher v = Pattern.compile("'([a-z0-9_-]+\\.html)'").matcher(versteckt);
        while (v.find()) verborgen.add(v.group(1));

        Set<String> fehlt = new TreeSet<>();
        Set<String> beides = new TreeSet<>();
        for (File f : new File(basis(), "src/main/assets/web/local").listFiles()) {
            String n = f.getName();
            if (!n.endsWith(".html")) continue;
            boolean menue = imMenue.contains(n), vs = verborgen.contains(n);
            if (!menue && !vs) fehlt.add(n);
            if (menue && vs) beides.add(n);
        }
        assertEquals("Seiten ohne Platz im Menü (in SECTIONS oder VERSTECKT eintragen)", "[]", fehlt.toString());
        assertEquals("im Menü und versteckt zugleich", "[]", beides.toString());
    }

    @Test
    public void siebenBereicheMitIconUndKurzwort() throws Exception {
        Matcher m = Pattern.compile(
                "\\{ id: '([a-z]+)', label: '[^']+', i18n: 'shell\\.[a-z_]+', kurz: 'shell\\.[a-z_]+', icon: '([a-z]+)'")
                .matcher(sections());
        String icons = block(shell(), "var ICONS = {", "};");
        List<String> ids = new ArrayList<>();
        while (m.find()) {
            ids.add(m.group(1));
            assertTrue("Icon " + m.group(2), icons.contains(m.group(2) + ":"));
        }
        assertEquals(Arrays.asList("start", "drive", "car", "guard", "auto", "apps", "system"), ids);
    }

    @Test
    public void handyAppSeitenBleibenImMenue() throws Exception {
        String sections = sections();
        // panoptes-app: PanoptesApi, App.kt (Leiste und Karten), AppLogik/BrueckeWeb, Adresse.kt (#koppeln)
        for (String s : new String[] {"index.html", "live-view.html", "navi.html", "wallbox.html", "trips.html",
                "waechter.html", "vehicle-control.html", "system.html"}) {
            assertTrue(s, sections.contains("{ href: '" + s + "'"));
            assertFalse(s + " darf nicht versteckt sein",
                    Pattern.compile("href: '" + Pattern.quote(s) + "'[^}]*hidden: true").matcher(sections).find());
        }
    }

    @Test
    public void jederTeilHatEinenReiterUndMindestensZweiSeiten() throws Exception {
        String teile = block(shell(), "var TEILE = {", "};");
        Matcher m = Pattern.compile("teil: '([a-z]+)'").matcher(sections());
        List<String> alle = new ArrayList<>();
        while (m.find()) alle.add(m.group(1));
        for (String t : new TreeSet<>(alle)) {
            assertTrue("TEILE kennt " + t, teile.contains(t + ":"));
            int n = 0;
            for (String a : alle) if (a.equals(t)) n++;
            assertTrue("Teil " + t + " braucht mindestens zwei Seiten", n >= 2);
        }
    }

    @Test
    public void menueSchluesselGibtEsInAllenSprachen() throws Exception {
        String menue = block(shell(), "var SECTIONS = [", "var VERSTECKT = [");
        Matcher m = Pattern.compile("(?:i18n|kurz): '([a-z_]+)\\.([a-z_]+)'").matcher(menue);
        List<String[]> schluessel = new ArrayList<>();
        while (m.find()) schluessel.add(new String[] {m.group(1), m.group(2)});
        assertTrue(schluessel.size() > 40);
        for (String lang : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject k = new JSONObject(lies("src/main/assets/web/i18n/" + lang + ".json"));
            for (String[] s : schluessel) {
                assertTrue(lang + ": " + s[0] + "." + s[1],
                        k.has(s[0]) && !k.getJSONObject(s[0]).optString(s[1]).trim().isEmpty());
            }
        }
    }

    @Test
    public void menueOhneTechnikwoerter() throws Exception {
        // REGEL DAU: was im Menü steht, ist Alltagssprache. Interne Namen (Dateien, Schlüssel) bleiben.
        String menue = block(shell(), "var SECTIONS = [", "var VERSTECKT = [");
        Matcher m = Pattern.compile("label: '([^']+)'").matcher(menue);
        List<String> labels = new ArrayList<>();
        while (m.find()) labels.add(m.group(1));
        assertTrue(labels.size() > 40);
        for (String wort : new String[] {"Tailnet", "Tailscale", "MQTT", "ABRP", "WireGuard", "Cloud", "Fernansicht", "Leistung"}) {
            for (String l : labels) assertFalse(l, l.contains(wort));
        }
        JSONObject de = new JSONObject(lies("src/main/assets/web/i18n/de.json")).getJSONObject("shell");
        assertFalse(de.getString("pill_tail_on").contains("Tailnet"));
        assertFalse(shell().contains("<span>Tailnet</span>"));
    }
}
