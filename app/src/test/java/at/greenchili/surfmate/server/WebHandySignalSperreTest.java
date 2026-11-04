package at.greenchili.surfmate.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 0.1.38.7 Sicherheitsfix (Befund 04.11.2026): Die Web-Sitzung der Handy-App (scope handyweb) löst Hupe, Licht,
 * Warnblinker, „Auto finden“ und Aufsperren nie aus – direkt oder über Tasten-, Schnellknopf-, Regel-, Aktionsgruppen-
 * und Licht-Test. Display und voller Login bleiben unverändert (der Wächter greift nur bei handyweb), Kopplungen
 * erreichen die Pfade über die ExternGuard-Erlaubnisliste gar nicht.
 */
public class WebHandySignalSperreTest {

    static final String[] PFADE = {
            "/api/vehicle/flash", "/api/vehicle/find-car", "/api/vehicle/unlock", "/api/vehicle/hazard",
            "/api/bydcloud/test", "/api/keymap/fire", "/api/quick-controls/fire/abc123",
            "/api/automations/test/regel-1", "/api/action-groups/gruppe-1/run", "/api/debug/light/fire",
            "/api/debug/light/fire-all"};

    @Test
    public void handyWebGesperrtJePfad() {
        for (String p : PFADE) {
            assertTrue(p, WebHandyGuard.blocks("POST", p));
            assertTrue(p + "?x", WebHandyGuard.blocks("POST", p + "?x=1"));
            assertTrue(p + " PUT", WebHandyGuard.blocks("PUT", p));
        }
    }

    @Test
    public void anderesBleibtWieBisher() {
        // Lesen und harmlose Befehle bleiben für die Web-Sitzung offen
        assertFalse(WebHandyGuard.blocks("GET", "/api/vehicle/state"));
        assertFalse(WebHandyGuard.blocks("POST", "/api/vehicle/climate"));
        assertFalse(WebHandyGuard.blocks("POST", "/api/vehicle/lock"));
        assertFalse(WebHandyGuard.blocks("GET", "/api/quick-controls"));
        assertFalse(WebHandyGuard.blocks("GET", "/api/keymap"));
        assertFalse(WebHandyGuard.blocks("POST", "/api/action-groups/gruppe-1"));
    }

    @Test
    public void signalAusnahmeNurDreiDirekteKnoepfe() {
        // 0.1.38.8 Paket B: mit Recht „signal“ nur Auto finden, Lichthupe, Warnblinker (Rest prüft FahrzeugAktionen)
        for (String p : new String[] {"/api/vehicle/flash", "/api/vehicle/find-car", "/api/vehicle/warnblinker"}) {
            assertTrue(p, WebHandyGuard.blocks("POST", p));
            assertTrue(p, WebHandyGuard.signalErlaubt("POST", p, true));
            assertFalse(p + " ohne Recht", WebHandyGuard.signalErlaubt("POST", p, false));
            assertFalse(p + " PUT", WebHandyGuard.signalErlaubt("PUT", p, true));
        }
        for (String p : new String[] {"/api/vehicle/unlock", "/api/vehicle/hazard", "/api/bydcloud/test",
                "/api/keymap/fire", "/api/quick-controls/fire/abc123", "/api/automations/test/regel-1",
                "/api/action-groups/gruppe-1/run", "/api/debug/light/fire"}) {
            assertFalse(p + " bleibt zu", WebHandyGuard.signalErlaubt("POST", p, true));
        }
    }

    @Test
    public void kopplungenErreichenDiePfadeNicht() {
        for (String p : PFADE) {
            assertFalse(p, ExternGuard.erlaubt("POST", p));
            assertFalse(p, ExternGuard.handyErlaubt("POST", p, "handy"));
        }
    }

    @Test
    public void nurHandyWebWirdGeprueftLoginUnveraendert() throws Exception {
        // Der Wächter hängt im HttpServer nur an handyweb – Display (Loopback) und voller Login laufen wie bisher
        String s = lies("app/src/main/java/at/greenchili/surfmate/server/HttpServer.java");
        assertTrue(s.contains("if (AuthMiddleware.isHandyWebRequest() && (WebHandyGuard.blocks(method, pathOnly)"));
    }

    static String lies(String rel) throws Exception {
        Path p = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (p != null) {
            for (Path c : new Path[] {p.resolve(rel), p.resolve(rel.replaceFirst("^app/", ""))}) {
                if (Files.isRegularFile(c)) return new String(Files.readAllBytes(c), StandardCharsets.UTF_8);
            }
            p = p.getParent();
        }
        throw new AssertionError(rel);
    }
}
