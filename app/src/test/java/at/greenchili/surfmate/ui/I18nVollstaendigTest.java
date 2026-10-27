package at.greenchili.surfmate.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 0.1.20 Tempo (Projektinhaber): Die Web-Seiten laden nur noch die gewählte Sprache; en kommt erst nach, wenn ein Schlüssel
 * fehlt. Darum müssen de/en/fr/it/nl vollständig bleiben (sonst sähe man kurz den rohen Schlüssel).
 */
public class I18nVollstaendigTest {

    private static void schluessel(JSONObject o, String p, Set<String> out) {
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            Object v = o.opt(k);
            if (v instanceof JSONObject && !((JSONObject) v).has("other")) schluessel((JSONObject) v, p + k + ".", out);
            else out.add(p + k);
        }
    }

    @Test
    public void gepflegteSprachenHabenAlleEnSchluessel() throws Exception {
        Set<String> en = new LinkedHashSet<>();
        schluessel(new JSONObject(lies("app/src/main/assets/web/i18n/en.json")), "", en);
        for (String l : new String[] {"de", "fr", "it", "nl"}) {
            Set<String> s = new LinkedHashSet<>();
            schluessel(new JSONObject(lies("app/src/main/assets/web/i18n/" + l + ".json")), "", s);
            Set<String> fehlt = new LinkedHashSet<>(en);
            fehlt.removeAll(s);
            assertTrue(l + " fehlt: " + fehlt, fehlt.isEmpty());
        }
    }

    @Test
    public void coreLaedtEnNurBeiFehlendemSchluessel() throws Exception {
        String core = lies("app/src/main/assets/web/shared/core.js");
        int init = core.indexOf("function init()");
        String initTeil = core.substring(init, core.indexOf("function fetchServerWebLocale", init));
        assertFalse("init lädt en nicht vorsorglich", initTeil.contains("fetchCatalog('en')"));
        int t = core.indexOf("function t(key, vars)");
        assertTrue(core.substring(t, core.indexOf("function modelName", t)).contains("rueckfallLaden()"));
        int r = core.indexOf("function rueckfallLaden()");
        String rt = core.substring(r, core.indexOf("\n    }", r));
        assertTrue(rt.contains("if (state.enLoading) return;") && rt.contains("hydrate(document)") && rt.contains("notify()"));
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
