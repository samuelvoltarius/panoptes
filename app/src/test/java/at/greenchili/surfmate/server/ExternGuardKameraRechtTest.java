package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.auth.Kopplungen;

import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/** 0.1.30 Kern Sicherheit: Kamerabilder/Clips nur mit „kamera“, Standbild mit „standbild“, Art „dienst“ minimal. */
public class ExternGuardKameraRechtTest {

    private static final String ID = "0123456789abcdef0123456789abcdef";

    private static Predicate<String> mit(String... rechte) {
        Set<String> s = new HashSet<>(Arrays.asList(rechte));
        return s::contains;
    }

    @Test
    public void kameraPfadeBrauchenKamera() {
        for (String p : new String[] {"/api/recordings", "/api/recordings?page=1", "/api/recordings/dates",
                "/api/recordings/stats", "/video/id/" + ID, "/thumb/id/" + ID}) {
            assertEquals(p, Kopplungen.RECHT_KAMERA, ExternGuard.noetigesRecht(p));
            assertFalse(p, ExternGuard.rechtReicht(ExternGuard.noetigesRecht(p), mit()));
            assertFalse(p, ExternGuard.rechtReicht(ExternGuard.noetigesRecht(p), mit("standbild")));
            assertTrue(p, ExternGuard.rechtReicht(ExternGuard.noetigesRecht(p), mit("kamera")));
        }
    }

    @Test
    public void standbildBrauchtStandbildOderKamera() {
        String p = ExternGuard.STANDBILD_PFAD + ID;
        assertEquals(Kopplungen.RECHT_STANDBILD, ExternGuard.noetigesRecht(p));
        assertFalse(ExternGuard.rechtReicht(Kopplungen.RECHT_STANDBILD, mit()));
        assertTrue(ExternGuard.rechtReicht(Kopplungen.RECHT_STANDBILD, mit("standbild")));
        assertTrue(ExternGuard.rechtReicht(Kopplungen.RECHT_STANDBILD, mit("kamera")));
        assertTrue(ExternGuard.erlaubt("GET", p));
        assertFalse(ExternGuard.erlaubt("POST", p));
        assertFalse(ExternGuard.erlaubt("GET", ExternGuard.STANDBILD_PFAD + "zzz"));
    }

    @Test
    public void andereLesePfadeBrauchenKeinZusatzrecht() {
        assertNull(ExternGuard.noetigesRecht("/api/vehicle/state"));
        assertNull(ExternGuard.noetigesRecht("/api/ha/v1/ereignisse"));
        assertTrue(ExternGuard.rechtReicht(null, mit()));
    }

    /** Pflicht-Contract: das Recht „standbild“ öffnet keinen einzigen KAMERA_PFAD (direkt oder über die Brücke). */
    @Test
    public void standbildOeffnetKeinenKameraPfad() {
        Predicate<String> nurStandbild = mit(Kopplungen.RECHT_STANDBILD);
        for (String k : at.greenchili.surfmate.bruecke.BrueckeTestZugang.kameraPfade()) {
            String p = k.endsWith("/") ? k + ID : k;
            for (String art : new String[] {"cockpit", "homeassistant", "handy", "dienst"}) {
                boolean pfadOffen = !ExternGuard.blocks("GET", p, art);
                boolean rechtOffen = ExternGuard.rechtReicht(ExternGuard.noetigesRecht(p), nurStandbild);
                assertFalse(art + " " + p, pfadOffen && rechtOffen);
            }
        }
    }

    @Test
    public void dienstDarfNurEreignisseZustandUndStandbild() {
        assertFalse(ExternGuard.blocks("GET", "/api/ha/v1/ereignisse", "dienst"));
        assertFalse(ExternGuard.blocks("GET", "/api/surveillance/status", "dienst"));
        assertFalse(ExternGuard.blocks("GET", ExternGuard.STANDBILD_PFAD + ID, "dienst"));
        assertFalse(ExternGuard.blocks("POST", "/api/sitzung/erneuern", "dienst"));
        for (String p : new String[] {"/api/gps", "/api/mqtt/telemetry", "/api/vehicle/state", "/api/trips",
                "/api/recordings", "/video/id/" + ID, "/thumb/id/" + ID, "/api/einstellungen", "/snapshot/0"}) {
            assertTrue(p, ExternGuard.blocks("GET", p, "dienst"));
        }
        assertTrue(ExternGuard.blocks("POST", "/api/einstellungen", "dienst"));
        assertTrue(ExternGuard.blocks("POST", "/api/ha/v1/aktion", "dienst"));
    }

    @Test
    public void antwortSagtWoManDasRechtEinschaltet() throws Exception {
        JSONObject o = ExternGuard.rechtFehltAntwort(Kopplungen.RECHT_KAMERA);
        assertEquals("nicht_erlaubt", o.getString("grund"));
        assertEquals("kamera", o.getString("recht"));
        assertTrue(o.getString("text").length() > 10);
        assertEquals("standbild", ExternGuard.rechtFehltAntwort(Kopplungen.RECHT_STANDBILD).getString("recht"));
    }

    /** Die Rechte-Prüfung sitzt im HttpServer direkt hinter der Pfad-Erlaubnisliste, vor jedem Handler. */
    @Test
    public void httpServerPrueftRechtVorDenHandlern() throws IOException {
        String s = lies("app/src/main/java/at/greenchili/surfmate/server/HttpServer.java");
        int guard = s.indexOf("ExternGuard.blocks(method, pathOnly,");
        int recht = s.indexOf("ExternGuard.noetigesRecht(pathOnly)");
        int handler = s.indexOf("if (routeToHandlers(method, path, body, rangeHeader");
        assertTrue(guard > 0 && recht > guard && handler > recht);
    }

    /**
     * Token-URL {@code /thumb/<datei>?t=}: gilt ohne JWT, aber nur für {@code /thumb/} und {@code /parking/asset/},
     * je Token genau eine Datei, 10 min (Push-Banner-Bild). Nie für {@code /video/} oder Listen.
     */
    @Test
    public void tokenUrlNurFuerEinBildPfad() throws IOException {
        String s = lies("app/src/main/java/at/greenchili/surfmate/server/AuthMiddleware.java");
        int t0 = s.indexOf("// Tier 0.5");
        int t1 = s.indexOf("// Tier 1", t0);
        String tier = s.substring(t0, t1);
        assertTrue(tier.contains("path.startsWith(\"/thumb/\")"));
        assertTrue(tier.contains("path.startsWith(\"/parking/asset/\")"));
        assertFalse(tier.contains("/video/"));
        assertFalse(tier.contains("/api/recordings"));
        assertFalse(tier.contains("/snapshot/"));
        assertTrue(tier.contains("validateThumbToken(decoded, token)"));
    }

    static String lies(String relativ) throws IOException {
        Path cur = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (cur != null) {
            Path c = cur.resolve(relativ);
            if (Files.isRegularFile(c)) return at.greenchili.surfmate.testutil.Quelltext.lies(c);
            Path m = cur.resolve(relativ.replaceFirst("^app/", ""));
            if (Files.isRegularFile(m)) return at.greenchili.surfmate.testutil.Quelltext.lies(m);
            cur = cur.getParent();
        }
        throw new AssertionError("nicht gefunden: " + relativ);
    }
}
