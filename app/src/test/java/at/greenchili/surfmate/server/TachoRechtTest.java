package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.auth.Kopplungen;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;

/**
 * 0.1.37 Recht „tacho“: Standard aus, nie automatisch; Kopplungen (Handy, Cockpit) und Web-Sitzung erreichen nur
 * „Was zeigt der Tacho?“ und „Tacho wieder normal“, alle anderen Tacho-Routen bleiben „nur mit Login“.
 */
public class TachoRechtTest {

    private static Kopplungen neu(JSONArray start) {
        final JSONArray[] g = {start};
        return new Kopplungen(new Kopplungen.Speicher() {
            public JSONArray lade() { return g[0]; }
            public boolean speichere(JSONArray x) { g[0] = x; return true; }
        });
    }

    @Test
    public void rechtStandardAusUndNieAutomatisch() throws Exception {
        assertEquals("tacho", Kopplungen.RECHT_TACHO);
        assertTrue(Kopplungen.RECHTE.contains("tacho"));
        assertTrue("Cockpit kann es tragen", Kopplungen.FREMD_RECHTE.contains("tacho"));
        Kopplungen k = neu(new JSONArray());
        String handy = k.anlegen("Handy", "handy", 1000L);
        String cockpit = k.anlegen("Cockpit", "cockpit", 2000L);
        assertFalse(k.hatRecht(handy, "tacho"));
        assertFalse(k.hatRecht(cockpit, "tacho"));
        JSONArray alt = new JSONArray().put(new JSONObject().put("id", "alt1").put("art", "handy").put("name", "A")
                .put("angelegt", 1L).put("zuletzt", System.currentTimeMillis()));
        assertFalse("Bestand bekommt es nicht", neu(alt).rechte("alt1").contains("tacho"));
        k.rechteSetzen(cockpit, Arrays.asList("tacho"));
        assertTrue(k.hatRecht(cockpit, "tacho"));
    }

    @Test
    public void kopplungenNurAuswahlUndNormalMitRecht() {
        for (String art : new String[] {"handy", "cockpit"}) {
            assertFalse(ExternGuard.blocks("GET", "/api/tacho/auswahl", art));
            assertFalse(ExternGuard.blocks("POST", "/api/tacho/auswahl", art));
            assertFalse(ExternGuard.blocks("POST", "/api/tacho/normal", art));
            assertTrue(ExternGuard.blocks("GET", "/api/tacho/normal", art));
            for (String zu : new String[] {"/api/tacho/navi", "/api/tacho/navi/warnleuchten", "/api/tacho/navi/fahrt",
                    "/api/tacho/navi/hinweis", "/api/tacho/ansicht", "/api/tacho/auswahl/../navi"}) {
                assertTrue(art + " " + zu, ExternGuard.blocks("POST", zu, art));
            }
        }
        for (String art : new String[] {"homeassistant", "dienst", "web", null}) {
            assertTrue(String.valueOf(art), ExternGuard.blocks("POST", "/api/tacho/auswahl", art));
        }
        assertEquals("tacho", ExternGuard.noetigesRecht("/api/tacho/auswahl"));
        assertEquals("tacho", ExternGuard.noetigesRecht("/api/tacho/normal?x=1"));
        assertFalse(ExternGuard.rechtReicht("tacho", r -> r.equals("kamera")));
        assertTrue(ExternGuard.rechtReicht("tacho", r -> r.equals("tacho")));
    }

    @Test
    public void webSitzungNurMitRecht() throws Exception {
        assertTrue(WebHandyGuard.blocks("POST", "/api/tacho/auswahl"));
        assertFalse(WebHandyGuard.tachoErlaubt("/api/tacho/auswahl", false));
        assertTrue(WebHandyGuard.tachoErlaubt("/api/tacho/auswahl?x=1", true));
        assertFalse(WebHandyGuard.tachoErlaubt("/api/tacho/navi", true));
        String http = TachoQuelle.lies("app/src/main/java/at/greenchili/surfmate/server/HttpServer.java");
        assertTrue(http.contains("WebHandyGuard.tachoErlaubt(pathOnly"));
        String h = TachoQuelle.lies("app/src/main/java/at/greenchili/surfmate/server/TachoApiHandler.java");
        assertTrue(h.contains("AuthMiddleware.isExternRequest() && !kopplungDarf(p)"));
        assertTrue(h.contains("if (!AUSWAHL.equals(p) && !\"/api/tacho/normal\".equals(p)) return false;"));
        assertTrue(h.contains("Kopplungen.RECHT_TACHO"));
    }

    /** Quelltext lesen (CRLF-fest), wie in den anderen Quelltext-Tests. */
    static final class TachoQuelle {
        static String lies(String rel) throws java.io.IOException {
            java.nio.file.Path cur = java.nio.file.Paths.get(System.getProperty("user.dir")).toAbsolutePath();
            while (cur != null) {
                java.nio.file.Path f = cur.resolve(rel);
                if (java.nio.file.Files.exists(f)) {
                    return new String(java.nio.file.Files.readAllBytes(f), java.nio.charset.StandardCharsets.UTF_8)
                            .replace("\r\n", "\n");
                }
                java.nio.file.Path g = cur.resolve(rel.replaceFirst("^app/", ""));
                if (java.nio.file.Files.exists(g)) {
                    return new String(java.nio.file.Files.readAllBytes(g), java.nio.charset.StandardCharsets.UTF_8)
                            .replace("\r\n", "\n");
                }
                cur = cur.getParent();
            }
            throw new AssertionError("nicht gefunden: " + rel);
        }
    }
}
