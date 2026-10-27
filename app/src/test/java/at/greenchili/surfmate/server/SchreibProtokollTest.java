package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.testutil.Quelltext;

import org.junit.Test;

import java.nio.file.Paths;

/** Schreib-Protokoll (0.1.10): jede Schreibanfrage auf /api/* eine Zeile, ohne Werte, Tokens, Positionen. */
public class SchreibProtokollTest {

    @Test
    public void nurSchreibzugriffeAufApi() {
        assertNull(SchreibProtokoll.zeile("GET", "/api/einstellungen", "lokal"));
        assertNull(SchreibProtokoll.zeile("POST", "/index.html", "lokal"));
        assertEquals("SCHREIBEN PUT /api/einstellungen von=login",
                SchreibProtokoll.zeile("PUT", "/api/einstellungen", "login"));
        assertEquals("SCHREIBEN DELETE /api/kopplung/widerrufen von=gekoppelt",
                SchreibProtokoll.zeile("delete", "/api/kopplung/widerrufen", "gekoppelt"));
    }

    @Test
    public void keineWerteTokensOderPositionen() {
        String z = SchreibProtokoll.zeile("POST",
                "/api/kopplung/anfrage/a3F9kQ2mZx81LpTr0vYb7cWd/erlauben?token=geheim&lat=48.2&lon=16.37", "lokal");
        assertEquals("SCHREIBEN POST /api/kopplung/anfrage/{x}/erlauben von=lokal", z);
        assertFalse(z.contains("geheim"));
        assertFalse(z.contains("48.2"));
        String pos = SchreibProtokoll.zeile("POST", "/api/navi/ziel/48.2082,16.3738", "lokal");
        assertEquals("SCHREIBEN POST /api/navi/ziel/{x} von=lokal", pos);
        assertEquals("SCHREIBEN POST /api/x/{x} von=lokal",
                SchreibProtokoll.zeile("POST", "/api/x/user@example.org", "lokal"));
        assertEquals("SCHREIBEN POST /api/x/{x} von=lokal",
                SchreibProtokoll.zeile("POST", "/api/x/abcdefabcdefabcdefabcdefabcdef", "lokal"));
    }

    @Test
    public void imServerVorDenHandlernVerdrahtet() throws Exception {
        String h = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/HttpServer.java"));
        int guard = h.indexOf("ExternGuard.blocks(method, pathOnly,");   // 0.1.18: mit Kopplungs-Art
        int prot = h.indexOf("SchreibProtokoll.melde(", guard);
        int route = h.indexOf("routeToHandlers(method, path, body", guard);
        assertTrue(guard > 0 && prot > guard && route > prot);
        String s = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/SchreibProtokoll.java"));
        assertFalse("Rumpf wird nie gelesen", s.contains("body"));
    }
}
