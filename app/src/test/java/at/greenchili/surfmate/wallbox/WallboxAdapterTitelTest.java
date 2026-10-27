package at.greenchili.surfmate.wallbox;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 0.1.19.1: Der Kachel-Titel kommt aus {@code wallbox.ad_<id>}, die Unterzeile aus ad_cloud / ad_lan / ad_ueber_bruecke.
 * Der Adapter „bruecke“ traf früher den Unterzeilen-Schlüssel ad_bruecke und hieß dann „über eine Brücke“.
 */
public class WallboxAdapterTitelTest {

    private static final String[] UNTERZEILEN = {"cloud", "lan", "ueber_bruecke"};

    @Test
    public void adapterIdsTreffenKeineUnterzeile() throws Exception {
        JSONObject roh = new JSONObject("{\"a\":" + lies("app/src/main/assets/web/shared/wallbox-adapter.json").trim() + "}");
        Object a = roh.get("a");
        JSONArray liste = a instanceof JSONArray ? (JSONArray) a : ((JSONObject) a).optJSONArray("adapter");
        assertTrue(liste != null && liste.length() > 0);
        for (int i = 0; i < liste.length(); i++) {
            String id = liste.getJSONObject(i).optString("id");
            for (String u : UNTERZEILEN) assertFalse("Adapter-id " + id + " = Unterzeilen-Schlüssel", u.equals(id));
        }
        String html = lies("app/src/main/assets/web/local/wallbox.html");
        assertTrue(html.contains("t('wallbox.ad_ueber_bruecke'"));
        assertFalse(html.contains("t('wallbox.ad_bruecke'"));
        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject w = new JSONObject(lies("app/src/main/assets/web/i18n/" + l + ".json")).getJSONObject("wallbox");
            assertFalse(l + ": ad_bruecke wäre der Titel des Adapters „bruecke“", w.has("ad_bruecke"));
            assertFalse(l, w.optString("ad_ueber_bruecke").isEmpty());
        }
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
