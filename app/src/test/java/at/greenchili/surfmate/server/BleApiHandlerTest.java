package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 0.1.16: GET /api/ble/zaehlen – Scan per am broadcast, höchstens alle 30 s, ohne Antwort ehrlicher Zustand. */
public class BleApiHandlerTest {

    static final BleApiHandler.Shell ECHT = BleApiHandler.shell;
    final List<String> befehle = new ArrayList<>();

    @After
    public void zurueck() {
        BleApiHandler.vergessen();
        BleApiHandler.shell = ECHT;
    }

    @Test
    public void zaehltUndDrosselt() throws Exception {
        BleApiHandler.vergessen();
        BleApiHandler.shell = (c, s) -> {
            befehle.add(c);
            return "Broadcast completed: result=0, data=\"{\"ok\":true,\"anzahl\":2,\"typen\":{\"bthome\":2},\"scanMs\":8000}\"";
        };
        JSONObject a = BleApiHandler.zaehlen(1_000_000);
        assertEquals(2, a.getInt("anzahl"));
        assertEquals(0, a.getLong("alterMs"));
        JSONObject b = BleApiHandler.zaehlen(1_000_000 + 10_000);
        assertEquals(10_000, b.getLong("alterMs"));
        assertEquals("innerhalb 30 s kein zweiter Scan", 1, befehle.size());
        BleApiHandler.zaehlen(1_000_000 + BleApiHandler.ABSTAND_MS);
        assertEquals(2, befehle.size());
        assertTrue(befehle.get(0).startsWith("am broadcast -a at.greenchili.surfmate.BLE_ZAEHLEN -n "));
    }

    @Test
    public void ohneAntwortDerApp() throws Exception {
        BleApiHandler.vergessen();
        BleApiHandler.shell = (c, s) -> "Broadcast completed: result=0";
        JSONObject r = BleApiHandler.zaehlen(5_000_000);
        assertFalse(r.getBoolean("ok"));
        assertEquals("app_antwortet_nicht", r.getString("zustand"));
    }

    @Test
    public void nurGet() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(BleApiHandler.handle("POST", "/api/ble/zaehlen", out));
        assertTrue(new String(out.toByteArray(), StandardCharsets.UTF_8).startsWith("HTTP/1.1 405"));
        assertFalse(BleApiHandler.handle("GET", "/api/ble/anders", new ByteArrayOutputStream()));
    }
}
