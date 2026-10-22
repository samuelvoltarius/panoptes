package at.greenchili.surfmate.telegram;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TelegramCatalogParityTest {

    private static final Pattern POSITIONAL_PLACEHOLDER =
            Pattern.compile("\\{(\\d+)(?:,[^{}]*)?\\}");

    private static Map<String, Object> english;
    private static Map<String, Object> portuguese;
    /** Übrige ausgelieferte Sprachen (aus EN erzeugt, tools/sprache/sprachen.py). */
    private static final Map<String, Map<String, Object>> others = new TreeMap<>();

    @BeforeClass
    public static void loadCatalogs() throws IOException, JSONException {
        english = loadTelegramCatalog("en");
        portuguese = loadTelegramCatalog("de"); // Panoptes: gepflegtes Paar de/en
        for (String lang : new String[] {"fr", "it", "nl"}) {
            others.put(lang, loadTelegramCatalog(lang));
        }
    }

    @Test
    public void catalogsHaveIdenticalTelegramLeafKeys() {
        Set<String> onlyEnglish = new TreeSet<>(english.keySet());
        onlyEnglish.removeAll(portuguese.keySet());

        Set<String> onlyPortuguese = new TreeSet<>(portuguese.keySet());
        onlyPortuguese.removeAll(english.keySet());

        assertTrue("Telegram keys missing from de: " + onlyEnglish, onlyEnglish.isEmpty());
        assertTrue("Telegram keys missing from en: " + onlyPortuguese, onlyPortuguese.isEmpty());
        assertFalse("Telegram catalogs must contain at least one leaf", english.isEmpty());
        for (Map.Entry<String, Map<String, Object>> e : others.entrySet()) {
            assertEquals("Telegram keys of " + e.getKey() + " differ from en",
                    english.keySet(), e.getValue().keySet());
        }
    }

    @Test
    public void everyTelegramLeafIsANonemptyString() {
        assertNonemptyStrings("en", english);
        assertNonemptyStrings("de", portuguese);
        for (Map.Entry<String, Map<String, Object>> e : others.entrySet()) {
            assertNonemptyStrings(e.getKey(), e.getValue());
        }
    }

    @Test
    public void catalogsUseIdenticalPositionalPlaceholders() {
        assertEquals("Leaf-key parity is required before comparing placeholders",
                english.keySet(), portuguese.keySet());

        for (String key : english.keySet()) {
            String enTemplate = requireString("en", key, english.get(key));
            String ptTemplate = requireString("de", key, portuguese.get(key));
            assertEquals("Placeholder mismatch for telegram." + key,
                    placeholders(enTemplate), placeholders(ptTemplate));
            for (Map.Entry<String, Map<String, Object>> e : others.entrySet()) {
                assertEquals("Placeholder mismatch in " + e.getKey() + " for telegram." + key,
                        placeholders(enTemplate),
                        placeholders(requireString(e.getKey(), key, e.getValue().get(key))));
            }
        }
    }

    // The former placeholderTemplatesHaveNoUnescapedSingleApostrophes lived
    // here. It required raw catalog strings to carry MessageFormat's '' escape,
    // which is an invariant a Crowdin-fed catalog cannot hold: translators write
    // ordinary elision (fr "l'", uk "з'", nl "{0}'s"), so the rule went red on
    // every translation sync — and it only ever covered the telegram.* subtree,
    // missing the errors.*, notifications.* and srt.* templates that were losing
    // placeholders in fr, uk, tr and pt-BR.
    //
    // Production now escapes lone apostrophes at format time
    // (at.greenchili.surfmate.util.MessageFormatSafe), so the shape of the raw string
    // no longer matters. The contract that does matter — every shipped template
    // still substitutes its placeholders — is asserted against rendered output,
    // across all locales and all subtrees, by
    // at.greenchili.surfmate.util.ServerI18nPlaceholderRenderingTest.

    @Test
    public void germanHelpUsesPanoptesCommands() {
        String guard = requireString("de", "help.guard", portuguese.get("help.guard"));
        assertTrue("de help must name /waechter", guard.contains("/waechter"));
        String guardEn = requireString("en", "help.guard", english.get("help.guard"));
        assertTrue("en help must name /guardian", guardEn.contains("/guardian"));
    }

    private static Map<String, Object> loadTelegramCatalog(String locale)
            throws IOException, JSONException {
        Path catalog = findCatalog(locale);
        String json = new String(Files.readAllBytes(catalog), StandardCharsets.UTF_8);
        JSONObject root = new JSONObject(json);
        JSONObject telegram = root.optJSONObject("telegram");
        if (telegram == null) {
            fail(catalog + " must contain a telegram object");
        }

        Map<String, Object> flattened = new TreeMap<>();
        flatten(telegram, "", flattened);
        return flattened;
    }

    private static Path findCatalog(String locale) {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            Path fromModule = current.resolve("src/main/assets/server-i18n/" + locale + ".json");
            if (Files.isRegularFile(fromModule)) return fromModule;

            Path fromRepository = current.resolve(
                    "app/src/main/assets/server-i18n/" + locale + ".json");
            if (Files.isRegularFile(fromRepository)) return fromRepository;

            current = current.getParent();
        }
        throw new AssertionError("Could not find server-i18n/" + locale
                + ".json from working directory " + System.getProperty("user.dir"));
    }

    private static void flatten(JSONObject object, String prefix, Map<String, Object> output)
            throws JSONException {
        // JSONObject.keySet() only exists in the standalone org.json artifact;
        // Android's bootclasspath org.json (which wins on the unit-test compile
        // classpath) exposes keys() instead, so iterate that for portability.
        for (java.util.Iterator<String> it = object.keys(); it.hasNext(); ) {
            String name = it.next();
            String path = prefix.isEmpty() ? name : prefix + "." + name;
            Object value = object.get(name);
            if (value instanceof JSONObject) {
                flatten((JSONObject) value, path, output);
            } else {
                assertNull("Duplicate flattened Telegram key: " + path,
                        output.put(path, value));
            }
        }
    }

    private static void assertNonemptyStrings(String locale, Map<String, Object> catalog) {
        for (Map.Entry<String, Object> entry : catalog.entrySet()) {
            String value = requireString(locale, entry.getKey(), entry.getValue());
            assertFalse(locale + " telegram." + entry.getKey() + " must not be blank",
                    value.trim().isEmpty());
        }
    }

    private static String requireString(String locale, String key, Object value) {
        assertTrue(locale + " telegram." + key + " must be a string, but was "
                        + (value == null ? "null" : value.getClass().getSimpleName()),
                value instanceof String);
        return (String) value;
    }

    private static Set<Integer> placeholders(String template) {
        Set<Integer> positions = new TreeSet<>();
        Matcher matcher = POSITIONAL_PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            positions.add(Integer.parseInt(matcher.group(1)));
        }
        return positions;
    }

}
