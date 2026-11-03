package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** GET /api/weckwort-messung: nur direkt am Autobildschirm, nur lesen, nur die Zahlen-JSON. */
public class WeckwortMessApiHandlerTest {

    @After
    public void aufraeumen() {
        AuthMiddleware.setRequestPeerForTest(null);
        AuthMiddleware.setRequestScopeForTest(null);
    }

    private static String antwort(String method) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(WeckwortMessApiHandler.handle(method, "/api/weckwort-messung", out));
        return out.toString("UTF-8");
    }

    @Test
    public void ausDerFerneUndFuerKopplungenGesperrt() throws Exception {
        AuthMiddleware.RelayProbe alt = AuthMiddleware.relayProbe;
        try {
            AuthMiddleware.relayProbe = (a, b) -> false;
            AuthMiddleware.setRequestScopeForTest(null);
            AuthMiddleware.setRequestPeerForTest(new InetSocketAddress("100.64.0.9", 40000));
            assertTrue(antwort("GET").contains("403"));
            AuthMiddleware.setRequestPeerForTest(new InetSocketAddress("127.0.0.1", 40000));
            AuthMiddleware.setRequestScopeForTest(at.greenchili.surfmate.auth.PairingCodes.SCOPE);
            assertTrue("Kopplung", antwort("GET").contains("403"));
            AuthMiddleware.setRequestScopeForTest(at.greenchili.surfmate.auth.WebSitzung.SCOPE);
            assertTrue("Handy-Web", antwort("GET").contains("403"));
            AuthMiddleware.setRequestScopeForTest(null);
            AuthMiddleware.relayProbe = (a, b) -> true;
            assertTrue("über tailscaled", antwort("GET").contains("403"));
            AuthMiddleware.relayProbe = (a, b) -> false;
            String ok = antwort("GET");
            assertTrue(ok, ok.contains("200"));
            assertTrue(ok.contains("\"schalter\""));
        } finally {
            AuthMiddleware.relayProbe = alt;
        }
    }

    @Test
    public void nurLesenKeinStartPerHttp() throws Exception {
        AuthMiddleware.setRequestPeerForTest(new InetSocketAddress("127.0.0.1", 40000));
        assertTrue(antwort("POST").contains("405"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertFalse(WeckwortMessApiHandler.handle("POST", "/api/weckwort-messung/start", out));
        assertFalse(WeckwortMessApiHandler.handle("GET", "/api/weckwort-messungx", out));
    }

    @Test
    public void liestNurGueltigeErgebnisdatei() throws Exception {
        File d = File.createTempFile("weckwort", ".json");
        try {
            assertFalse(WeckwortMessApiHandler.antwort(new File(d.getPath() + ".fehlt")).getBoolean("vorhanden"));
            Files.write(d.toPath(), "{\"art\":\"etwas anderes\"}".getBytes(StandardCharsets.UTF_8));
            assertFalse(WeckwortMessApiHandler.antwort(d).getBoolean("vorhanden"));
            Files.write(d.toPath(), "kaputt".getBytes(StandardCharsets.UTF_8));
            assertFalse(WeckwortMessApiHandler.antwort(d).getBoolean("vorhanden"));
            Files.write(d.toPath(), "{\"art\":\"weckwort-messung\",\"cpu\":{\"kerne\":8}}".getBytes(StandardCharsets.UTF_8));
            JSONObject r = WeckwortMessApiHandler.antwort(d);
            assertTrue(r.getBoolean("vorhanden"));
            assertEquals(8, r.getJSONObject("ergebnis").getJSONObject("cpu").getInt("kerne"));
        } finally {
            d.delete();
        }
    }
}
