package at.greenchili.surfmate.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.byd.AutoServiceBridge;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 0.1.18 (Sicherheit, Koordination): Über keinen /api/debug-Weg darf ein Schreib-Opcode ans Auto gehen – weder
 * POWER_OFF noch Türen, Schloss, Fenster, Kofferraum, Licht. Drei unabhängige Sperren:
 * (1) DebugSchreibsperre (404 vor jedem Debug-Handler), (2) die Handler kennen keine Fernsteuer-Route mehr,
 * (3) AutoServiceBridge lässt nur lesende Transaktionen (tx 6/8) an den Binder.
 */
public class DebugNurLesendTest {

    static String lies(String rel) throws Exception {
        File f = new File(rel);
        if (!f.isFile()) f = new File("app/" + rel);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    static final String[] SCHREIBVERSUCHE = {
            "/api/debug/autoservice/remote-control?op=9",                 // POWER_OFF
            "/api/debug/autoservice/remote-control?op=POWER_OFF&confirm=YES",
            "/api/debug/autoservice/remote-control?op=5",                 // UNLOCK (Tür)
            "/api/debug/autoservice/remote-control?op=7",                 // LOCK (Schloss)
            "/api/debug/autoservice/remote-control?op=17",                // CLOSE_WINDOWS (Fenster)
            "/api/debug/autoservice/remote-control?op=6",                 // TRUNK_OPEN
            "/api/debug/autoservice/remote-control?op=22",                // FLASH_LIGHTS
            "/api/debug/autoservice/remote-control/",
            "/api/debug/autoservice/sdk-remote-control?method=setDoorLock&arg=0",
            "/api/debug/autoservice/sdk-remote-control?method=powerOff",
            "/api/debug/car-property/set?key=0x1&value=1&confirm=YES",
            "/api/debug/light/fire?candidate=A&confirm=YES",
            "/api/debug/light/fire-all?confirm=YES",
            "/api/debug/seat/write?axis=horizontal&value=52&confirm=YES",
            "/api/debug/bs-spike",
    };

    @Test
    public void sperreLehntAlleSchreibversucheAb() {
        for (String p : SCHREIBVERSUCHE) assertTrue(p, DebugSchreibsperre.gesperrt(p));
        // lesende Debug-Wege bleiben
        for (String p : new String[]{"/api/debug/autoservice/get-int?area=1&cmd=2", "/api/debug/autoservice/status",
                "/api/debug/car-property/get?key=0x1", "/api/debug/seat/read", "/api/debug/light/resolve",
                "/api/debug/adas/read"}) {
            assertFalse(p, DebugSchreibsperre.gesperrt(p));
        }
    }

    @Test
    public void autoserviceHandlerKenntKeineFernsteuerung() throws Exception {
        // auch ohne Sperre davor: unbekannter Pfad → 404, nichts aufgerufen
        for (String p : new String[]{"/api/debug/autoservice/remote-control?op=9",
                "/api/debug/autoservice/sdk-remote-control?method=powerOff"}) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            AutoServiceDebugApiHandler.handle("GET", p, null, out);
            assertTrue(p, new String(out.toByteArray(), StandardCharsets.UTF_8).startsWith("HTTP/1.1 404"));
        }
        // sdk-getter (0.1.16) nimmt nur Getter: Tür, Schloss, Fenster, Ausschalten → 400
        for (String m : new String[]{"powerOff", "setDoorLock", "unlockDoor", "lock", "openWindow",
                "setWindowPosition", "closeAllWindows", "setPowerLevel"}) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            AutoServiceDebugApiHandler.handle("GET", "/api/debug/autoservice/sdk-getter?class=Bodywork&method=" + m,
                    null, out);
            assertTrue(m, new String(out.toByteArray(), StandardCharsets.UTF_8).startsWith("HTTP/1.1 400"));
        }
    }

    @Test
    public void bridgeLaesstNurLesendeTransaktionenDurch() throws Exception {
        assertTrue(AutoServiceBridge.lesendErlaubt(6));
        assertTrue(AutoServiceBridge.lesendErlaubt(8));
        for (int tx : new int[]{40, 42, 5, 7, 9, 17, 22, 0, 1, -1, 41, 43, 100}) {
            assertFalse("tx " + tx, AutoServiceBridge.lesendErlaubt(tx));
        }
        String k = lies("src/main/java/at/greenchili/surfmate/byd/AutoServiceBridge.kt");
        assertFalse(k.contains("TX_REMOTE_CONTROL"));
        assertFalse(k.contains("TX_SEND_CAN"));
        // jede Stelle, die den Binder ruft, ist lesend: tx 6/8 fest, readDiLink5 nur tx 5/7, transactInt geprüft
        Matcher m = Pattern.compile("\\.transact\\(([^,]+),").matcher(k);
        int n = 0;
        while (m.find()) {
            n++;
            String tx = m.group(1).trim();
            assertTrue("Binder-Aufruf mit " + tx, tx.equals("TX_GET_BUFFER") || tx.equals("tx"));
        }
        assertTrue(n >= 3);
        int ti = k.indexOf("private inline fun transactInt(tx: Int");
        assertTrue(k.substring(ti, ti + 200).contains("if (!lesendErlaubt(tx)) {"));
        int sc = k.indexOf("private fun shellServiceCall(tx: Int");
        assertTrue(k.substring(sc, sc + 200).contains("if (!lesendErlaubt(tx)) {"));
        int rd = k.indexOf("fun readDiLink5(tx: Int");
        assertTrue(k.substring(rd, rd + 200).contains("(tx != 5 && tx != 7)"));
    }
}
