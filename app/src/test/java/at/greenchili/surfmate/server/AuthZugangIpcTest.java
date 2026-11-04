package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** 0.1.38.8 Paket A: deviceSecret über die IPC nur an App-uid und SentryDaemon (uid 1000), nie an shell/tailscaled. */
public class AuthZugangIpcTest {

    static final int APP = 10123;

    static AuthZugangIpc.Quelle quelle(int uid, int appUid, String[] zugang) {
        return new AuthZugangIpc.Quelle() {
            @Override public int uid(int c, int s) { return uid; }
            @Override public int appUid() { return appUid; }
            @Override public String[] zugang() { return zugang; }
        };
    }

    @Test
    public void nurAppUndSystem() {
        assertTrue(AuthZugangIpc.erlaubt(1000, -1));
        assertTrue(AuthZugangIpc.erlaubt(APP, APP));
        assertFalse(AuthZugangIpc.erlaubt(2000, APP));     // shell: auch tailscaled (Tailnet-Weiterleitung)
        assertFalse(AuthZugangIpc.erlaubt(0, APP));
        assertFalse(AuthZugangIpc.erlaubt(10124, APP));    // fremde App
        assertFalse(AuthZugangIpc.erlaubt(-1, APP));
        assertFalse(AuthZugangIpc.erlaubt(APP, -1));       // App-uid unbekannt → fail-closed
        assertFalse(AuthZugangIpc.erlaubt(2000, 2000));    // nie über eine „App-uid“ unter 10000
    }

    @Test
    public void antwortGibtSchluesselNurErlaubten() throws Exception {
        String[] z = {"byd-x", "geheim88"};
        JSONObject ok = AuthZugangIpc.antwort(40000, 19977, quelle(APP, APP, z));
        assertTrue(ok.getBoolean("success"));
        assertEquals("geheim88", ok.getString("deviceSecret"));
        assertTrue(AuthZugangIpc.antwort(40000, 19977, quelle(1000, -1, z)).getBoolean("success"));

        for (int uid : new int[] {2000, 0, 10999, -1}) {
            JSONObject nein = AuthZugangIpc.antwort(40000, 19977, quelle(uid, APP, z));
            assertFalse(nein.getBoolean("success"));
            assertFalse(String.valueOf(uid), nein.has("deviceSecret"));
            assertFalse(nein.toString().contains("geheim88"));
        }
        JSONObject leer = AuthZugangIpc.antwort(40000, 19977, quelle(APP, APP, null));
        assertFalse(leer.getBoolean("success"));
    }

    @Test
    public void appUidGenauDiesesPakets() {
        assertEquals(10123, AuthZugangIpc.appUidAusListe("package:at.greenchili.surfmate uid:10123\n"));
        assertEquals(10125, AuthZugangIpc.appUidAusListe(
                "package:at.greenchili.surfmatex uid:10124\npackage:at.greenchili.surfmate uid:10125"));
        assertEquals(-1, AuthZugangIpc.appUidAusListe("package:at.greenchili.surfmate.helfer uid:10124"));
        assertEquals(-1, AuthZugangIpc.appUidAusListe(""));
        assertEquals(-1, AuthZugangIpc.appUidAusListe(null));
    }

    @Test
    public void ausAntwortLiestNurErfolg() {
        assertEquals("g", AuthZugangIpc.ausAntwort("{\"success\":true,\"deviceSecret\":\"g\"}"));
        assertNull(AuthZugangIpc.ausAntwort("{\"success\":false,\"error\":\"nicht_erlaubt\"}"));
        assertNull(AuthZugangIpc.ausAntwort("{\"success\":true,\"deviceSecret\":\"\"}"));
        assertNull(AuthZugangIpc.ausAntwort("kaputt"));
        assertNull(AuthZugangIpc.ausAntwort(null));
    }

    @Test
    public void clientSprichtDasZeilenprotokoll() throws Exception {
        try (ServerSocket ss = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Thread t = new Thread(() -> {
                try (Socket c = ss.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
                    JSONObject req = new JSONObject(in.readLine());
                    PrintWriter out = new PrintWriter(c.getOutputStream(), true);
                    out.println(AuthZugangIpc.BEFEHL.equals(req.optString("command"))
                            ? "{\"success\":true,\"deviceSecret\":\"geheim88\"}" : "{\"success\":false}");
                } catch (Exception ignored) { }
            });
            t.start();
            assertEquals("geheim88", AuthZugangIpc.holenDirekt(ss.getLocalPort(), 2000));
            t.join(2000);
        }
        // niemand hört zu → null, kein Hänger
        int frei;
        try (ServerSocket s = new ServerSocket(0)) { frei = s.getLocalPort(); }
        assertNull(AuthZugangIpc.holenDirekt(frei, 500));
    }

    @Test
    public void ipcServerAntwortetMitDerUidDieserVerbindung() throws Exception {
        String src = new String(Files.readAllBytes(Paths.get(
                "src/main/java/at/greenchili/surfmate/server/SurveillanceIpcServer.java")), StandardCharsets.UTF_8);
        int befehl = src.indexOf("AuthZugangIpc.BEFEHL.equals");
        assertTrue(befehl > 0);
        // die Antwort hängt an der uid des Gegen-Sockets dieser Verbindung
        assertTrue(src.substring(befehl, befehl + 300).contains("AuthZugangIpc.antwort(client.getPort(), client.getLocalPort())"));
        assertTrue(src.substring(befehl - 200, befehl).contains("!diLink5Authenticated"));
    }
}
