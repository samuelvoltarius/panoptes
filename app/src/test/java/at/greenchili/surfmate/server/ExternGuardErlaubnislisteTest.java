package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.testutil.Quelltext;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Regel A/B: Kopplungs-Tokens (scope "kopplung") dürfen nur, was {@link ExternGuard} ausdrücklich erlaubt.
 * Zählt ALLE Pfad-Literale im Quellcode auf und prüft jede Methode – neue Pfade sind damit automatisch
 * gesperrt, solange niemand sie bewusst in die Erlaubnisliste schreibt.
 */
public class ExternGuardErlaubnislisteTest {

    static final String[] METHODEN = {"GET", "HEAD", "POST", "PUT", "DELETE", "PATCH"};

    /** Alle "/api/…"- und "/ws/…"-Literale aus src/main/java. */
    static Set<String> allePfade() throws IOException {
        Pattern lit = Pattern.compile("\"(/(?:api|ws)/[A-Za-z0-9_./-]*)");
        Set<String> out = new TreeSet<>();
        try (Stream<Path> s = Files.walk(Paths.get("src/main/java"))) {
            for (Path p : (Iterable<Path>) s.filter(x -> x.toString().endsWith(".java") || x.toString().endsWith(".kt"))::iterator) {
                Matcher m = lit.matcher(Quelltext.lies(p));
                while (m.find()) {
                    String pfad = m.group(1);
                    if (pfad.length() > 5) out.add(pfad);
                }
            }
        }
        return out;
    }

    @Test
    public void everyRegisteredRouteIsBlockedUnlessAllowlisted() throws Exception {
        Set<String> pfade = allePfade();
        assertTrue("Aufzählung greift (" + pfade.size() + ")", pfade.size() > 200);
        List<String> falschOffen = new ArrayList<>();
        for (String p : pfade) {
            String norm = p.length() > 1 && p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
            for (String m : METHODEN) {
                boolean soll = (("GET".equals(m) || "HEAD".equals(m)) && ExternGuard.LESEN.contains(norm))
                        || (("POST".equals(m) || "PUT".equals(m)) && ExternGuard.SCHREIBEN.contains(norm));
                if (ExternGuard.erlaubt(m, p) != soll) falschOffen.add(m + " " + p);
            }
        }
        assertEquals("nicht erlaubte Pfade offen: " + falschOffen, 0, falschOffen.size());
    }

    @Test
    public void vehicleCommandsFireTestAndAdminAreNeverAllowed() {
        String[] nie = {
                "/api/vehicle/lock", "/api/vehicle/unlock", "/api/vehicle/climate", "/api/vehicle/window",
                "/api/vehicle/flash", "/api/vehicle/find-car", "/api/vehicle/state",
                "/api/quick-controls/fire/1", "/api/keymap/fire", "/api/automations/test/x",
                "/api/start", "/api/stop", "/api/stream/start",
                "/api/kopplung/code", "/api/kopplungen", "/api/kopplung/widerrufen", "/api/kopplung/anfragen",
                "/api/kopplung/anfrage/0123456789abcdef/erlauben", "/api/planer/verbinden", "/api/test/blitzer",
                "/api/system/tailscale", "/api/wireguard", "/api/backup/export", "/api/settings/unified",
                "/api/update/install", "/api/appupdate/installieren", "/api/telegram/token", "/api/system/daemon",
                "/api/trips/recover", "/api/trips/config"};
        for (String p : nie) {
            for (String m : new String[] {"POST", "PUT", "DELETE", "PATCH"}) {
                assertFalse(m + " " + p, ExternGuard.erlaubt(m, p));
            }
        }
        assertFalse("Zustand nur lesen", ExternGuard.erlaubt("POST", "/api/vehicle/state"));
        assertFalse("Pfad-Tricks", ExternGuard.erlaubt("GET", "/api/einstellungen/../kopplungen"));
        assertFalse(ExternGuard.erlaubt("GET", "/api/einstellungen//x"));
        assertFalse("Unterpfade nicht automatisch", ExternGuard.erlaubt("GET", "/api/gps/history"));
        assertFalse(ExternGuard.erlaubt(null, "/api/gps"));
        assertFalse(ExternGuard.erlaubt("GET", null));
    }

    @Test
    public void whatPairedDevicesNeedStillWorks() {
        String[] lesen = {"/api/mqtt/telemetry", "/api/launcher/v1/summary", "/api/vehicle/state", "/api/gps",
                "/api/planer/identitaet", "/api/einstellungen", "/api/einstellungen/schema",
                "/api/surveillance/status", "/api/surveillance/gesundheit", "/api/system/capabilities",
                "/api/trips", "/api/navi/unterwegs"};
        for (String p : lesen) assertTrue("GET " + p, ExternGuard.erlaubt("GET", p));
        assertTrue(ExternGuard.erlaubt("GET", "/api/mqtt/telemetry?x=1"));
        assertTrue(ExternGuard.erlaubt("GET", "/api/trips/"));
        assertTrue(ExternGuard.erlaubt("PUT", "/api/einstellungen"));
        assertTrue(ExternGuard.erlaubt("POST", "/api/sitzung/erneuern"));
    }

    @Test
    public void websocketsAndMainPathAreGuarded() throws Exception {
        String hs = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/HttpServer.java"));
        int ws = hs.indexOf("if (!wsAuthenticated) {");
        assertTrue(ws > 0);
        assertTrue("WebSockets für Kopplung gesperrt",
                hs.substring(ws, ws + 400).contains("if (AuthMiddleware.isExternRequest()) {"));
        int auth = hs.indexOf("boolean authenticated = remoteDevRequest");
        int guard = hs.indexOf("if (AuthMiddleware.isExternRequest() && ExternGuard.blocks(method, pathOnly)) {", auth);
        int route = hs.indexOf("if (routeToHandlers(", auth);
        assertTrue("Guard nach Anmeldung, vor jedem Handler", auth > 0 && guard > auth && guard < route);
    }
}
