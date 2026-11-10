package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 0.1.38.14 Vertrag {@code GET /api/zugang/code}: nur der Display-WebView der Panoptes-App (direkt am Auto UND App-UID),
 * nie Kopplung, PC, Brücke, Tailnet, Login von außen oder eine andere App am Auto; no-store; nie im Log.
 */
public class ZugangCodeApiHandlerTest {

    private static final int APP = 10_123;

    private static ZugangCodeApiHandler.Quelle q(final boolean direkt, final int uid, final int app, final String code) {
        return new ZugangCodeApiHandler.Quelle() {
            public boolean direktAmAuto() { return direkt; }
            public int quellport() { return direkt ? 45_000 : -1; }
            public int uid(int quellport) { return uid; }
            public int appUid() { return app; }
            public String code() { return code; }
        };
    }

    @After
    public void ab() {
        ZugangCodeApiHandler.quelle = q(false, -1, -1, null);
    }

    private static int status(Object[] a) { return (Integer) a[0]; }

    @Test
    public void nurDerAppWebViewAmAutoBekommtDenCode() throws Exception {
        Object[] a = ZugangCodeApiHandler.antwort("GET", q(true, APP, APP, "Ab3dE7gH"));
        assertEquals(200, status(a));
        assertEquals("Ab3dE7gH", ((JSONObject) a[1]).getString("code"));
    }

    @Test
    public void externKopplungPcBrueckeTailnetLoginVonAussen403() {
        // all das ist nicht „direkt am Auto“ (AuthMiddleware.isDirektAmAuto false)
        Object[] a = ZugangCodeApiHandler.antwort("GET", q(false, APP, APP, "Ab3dE7gH"));
        assertEquals(403, status(a));
        assertFalse(a[1].toString().contains("Ab3dE7gH"));
    }

    @Test
    public void andereAppAmAutoUndLauncher403() {
        assertEquals(403, status(ZugangCodeApiHandler.antwort("GET", q(true, 10_555, APP, "Ab3dE7gH"))));
        assertEquals("shell (tailscaled) nie", 403, status(ZugangCodeApiHandler.antwort("GET", q(true, 2000, APP, "Ab3dE7gH"))));
        assertEquals("App-UID unbekannt: fail-closed", 403, status(ZugangCodeApiHandler.antwort("GET", q(true, APP, -1, "Ab3dE7gH"))));
    }

    @Test
    public void nurLesen() {
        assertEquals(405, status(ZugangCodeApiHandler.antwort("POST", q(true, APP, APP, "Ab3dE7gH"))));
    }

    @Test
    public void antwortNoStoreUndExtern403ImHandler() throws Exception {
        ZugangCodeApiHandler.quelle = q(true, APP, APP, "Ab3dE7gH");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ZugangCodeApiHandler.handle("GET", ZugangCodeApiHandler.PFAD, out));
        String r = new String(out.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(r.startsWith("HTTP/1.1 200"));
        assertTrue("no-store", r.contains("Cache-Control: no-store"));

        ZugangCodeApiHandler.quelle = q(false, APP, APP, "Ab3dE7gH");
        out = new ByteArrayOutputStream();
        ZugangCodeApiHandler.handle("GET", ZugangCodeApiHandler.PFAD + "?x=1", out);
        r = new String(out.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(r.startsWith("HTTP/1.1 403"));
        assertFalse(r.contains("Ab3dE7gH"));
    }

    @Test
    public void kopplungenErreichenDenPfadNie() {
        assertTrue("nicht in der Kopplungs-Erlaubnisliste", ExternGuard.blocks("GET", ZugangCodeApiHandler.PFAD));
    }

    @Test
    public void nieImLog() throws Exception {
        assertNull("GET steht nicht im Befehls-Log", SchreibProtokoll.pfadOhneWerte("GET", ZugangCodeApiHandler.PFAD));
        String s = lies("src/main/java/at/greenchili/surfmate/server/ZugangCodeApiHandler.java");
        assertFalse(s.contains("Log."));
        assertFalse(s.contains("logger"));
        assertFalse(s.contains("System.out"));
        String js = lies("src/main/assets/web/shared/zugangscode.js");
        assertFalse("kein Log im Browser", js.contains("console."));
        assertTrue("verdeckt nach 60 s", js.contains("ZEIT_MS = 60000"));
        assertTrue("Rückfrage", js.contains("confirmDialog") && js.contains("window.confirm"));
        assertTrue("verdeckt beim Verlassen", js.contains("pagehide") && js.contains("visibilitychange"));
    }

    private static String lies(String rel) throws Exception {
        Path p = Paths.get(rel);
        if (!Files.exists(p)) p = Paths.get("app").resolve(rel);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
