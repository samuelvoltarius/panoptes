package at.greenchili.surfmate.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 0.1.20: design-tokens.json (für Cockpit/Handy-App) muss genau design-tokens.css entsprechen – eine Quelle. */
public class DesignTokensJsonTest {

    private static Map<String, String> block(String css, String selektor) {
        css = css.replaceAll("(?s)/[*].*?[*]/", "");
        Map<String, String> out = new LinkedHashMap<>();
        Matcher b = Pattern.compile("([^{}]+)[{]([^{}]*)[}]").matcher(css);
        while (b.find()) {
            if (!b.group(1).trim().replaceAll("[ \t\r\n]+", " ").equals(selektor)) continue;
            Matcher v = Pattern.compile("--([a-z0-9-]+)[ \t\r\n]*:[ \t\r\n]*([^;]+);").matcher(b.group(2));
            while (v.find()) out.put(v.group(1), v.group(2).trim().replaceAll("[ \t\r\n]+", " "));
        }
        return out;
    }

    @Test
    public void jsonEntsprichtDemCss() throws Exception {
        String css = lies("app/src/main/assets/web/shared/design-tokens.css");
        JSONObject j = new JSONObject(lies("app/src/main/assets/web/shared/design-tokens.json"));
        Map<String, String> dunkel = block(css, ":root");
        Map<String, String> hell = block(css, ":root[data-theme=\"light\"]");
        assertTrue(dunkel.size() > 50);
        assertEquals("dunkel: python tools/design_tokens_json.py laufen lassen", dunkel.size(), j.getJSONObject("dunkel").length());
        for (Map.Entry<String, String> e : dunkel.entrySet()) assertEquals(e.getKey(), e.getValue(), j.getJSONObject("dunkel").getString(e.getKey()));
        assertEquals(hell.size(), j.getJSONObject("hell").length());
        for (Map.Entry<String, String> e : hell.entrySet()) assertEquals(e.getKey(), e.getValue(), j.getJSONObject("hell").getString(e.getKey()));
        assertEquals("#B5F23D", j.getJSONObject("dunkel").getString("primary"));
        assertEquals("dunkel", j.getString("standard"));
    }

    private static String lies(String rel) throws Exception {
        Path p = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (p != null) {
            for (Path c : new Path[] {p.resolve(rel), p.resolve(rel.replaceFirst("^app/", ""))}) {
                if (Files.isRegularFile(c)) return new String(Files.readAllBytes(c), "UTF-8");
            }
            p = p.getParent();
        }
        throw new java.io.FileNotFoundException(rel);
    }
}
