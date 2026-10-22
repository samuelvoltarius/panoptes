package at.greenchili.surfmate.einstellungen;

import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Jeder Text, den GET /api/einstellungen/schema anfragt (Gruppen, Abschnitte, Titel, Hilfe,
 * Optionen, Sperrgründe, Bestätigungen), muss in allen ausgelieferten Sprachen vorhanden sein –
 * in server-i18n oder, wie im EinstellungenApiHandler, ersatzweise im Web-Katalog.
 * Sonst zeigt die Weboberfläche bei „de“ englische oder leere Texte.
 */
public class SchemaTexteAlleSprachenTest {

    private static final String[] SPRACHEN = {"en", "de", "fr", "it", "nl"};
    private static final Map<String, JSONObject> SERVER = new LinkedHashMap<>();
    private static final Map<String, JSONObject> WEB = new LinkedHashMap<>();

    @BeforeClass
    public static void lade() throws Exception {
        for (String l : SPRACHEN) {
            SERVER.put(l, new JSONObject(lies("app/src/main/assets/server-i18n/" + l + ".json")));
            WEB.put(l, new JSONObject(lies("app/src/main/assets/web/i18n/" + l + ".json")));
        }
    }

    /** Alle Schlüssel, die das Schema in irgendeinem Kontext anfragt. */
    private static Map<String, Boolean> angefragt() throws Exception {
        Map<String, Boolean> keys = new LinkedHashMap<>(); // key → true = Unterbaum (unter)
        Einstellungen.Texte merker = new Einstellungen.Texte() {
            public String text(String key) { if (key != null && !key.isEmpty()) keys.putIfAbsent(key, false); return key; }
            public JSONObject unter(String p) { if (p != null && !p.isEmpty()) keys.put(p, true); return new JSONObject(); }
        };
        List<Katalog.Kontext> kontexte = new ArrayList<>();
        for (Laenderprofil.Land land : Laenderprofil.Land.values()) {
            for (String chemie : new String[] {AkkuChemie.BLEI, AkkuChemie.LFP}) {
                for (boolean extra : new boolean[] {false, true}) {
                    Katalog.Kontext k = new Katalog.Kontext();
                    k.land = land; k.akkuChemie = chemie; k.cluster = extra; k.hud = extra;
                    kontexte.add(k);
                }
            }
        }
        for (Katalog.Kontext k : kontexte) {
            Einstellungen.schema(Katalog.felder(k), new JSONObject(), k, merker);
        }
        return keys;
    }

    @Test
    public void jederSchemaTextInAllenSprachen() throws Exception {
        Map<String, Boolean> keys = angefragt();
        assertTrue("Schema fragt keine Texte an?", keys.size() > 20);
        TreeSet<String> fehlend = new TreeSet<>();
        for (Map.Entry<String, Boolean> e : keys.entrySet()) {
            for (String l : SPRACHEN) {
                boolean da = e.getValue()
                        ? unterbaum(SERVER.get(l), e.getKey()) || unterbaum(WEB.get(l), e.getKey())
                        : text(SERVER.get(l), e.getKey()) || text(WEB.get(l), e.getKey());
                if (!da) fehlend.add(l + ": " + e.getKey());
            }
        }
        assertTrue("Schema-Texte fehlen (" + fehlend.size() + "): " + fehlend, fehlend.isEmpty());
    }

    private static Object walk(JSONObject cat, String key) {
        if (cat.has(key)) return cat.opt(key);
        Object cur = cat;
        for (String part : key.split("\\.")) {
            if (!(cur instanceof JSONObject)) return null;
            cur = ((JSONObject) cur).opt(part);
            if (cur == null) return null;
        }
        return cur;
    }

    private static boolean text(JSONObject cat, String key) {
        Object v = walk(cat, key);
        return v instanceof String && !((String) v).trim().isEmpty();
    }

    private static boolean unterbaum(JSONObject cat, String key) {
        Object v = walk(cat, key);
        return v instanceof JSONObject && ((JSONObject) v).length() > 0;
    }

    private static String lies(String relativePath) throws Exception {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            for (Path p : new Path[] {current.resolve(relativePath), current.resolve(relativePath.replaceFirst("^app/", ""))}) {
                if (Files.isRegularFile(p)) return new String(Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Nicht gefunden: " + relativePath);
    }
}
