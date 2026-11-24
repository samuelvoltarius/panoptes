package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 0.1.43.19 (SICHERHEIT_SHELLWEGE K1): Aufrufer-Prüfung für alle Loopback-Server der Daemons, gleich nach accept.
 * 19976 {@code TcpCommandServer} hat keinen freien {@code shell}-Befehl mehr.
 */
public class LoopbackAufruferTest {

    static final int APP = 10123;
    private final IpcAufrufer.Quelle vorher = IpcAufrufer.quelle;

    @After
    public void zurueck() {
        IpcAufrufer.quelle = vorher;
    }

    private static IpcAufrufer.Quelle q(int uid, boolean tailscaled, boolean notschalter) {
        return IpcAufruferTest.q(uid, tailscaled, APP, APP, notschalter);
    }

    private static boolean shellOderApp(IpcAufrufer.Quelle q) {
        return IpcAufrufer.vertraut(1, 19976, q, IpcAufrufer.Kreis.SHELL_ODER_APP);
    }

    private static boolean lokal(IpcAufrufer.Quelle q) {
        return IpcAufrufer.vertraut(1, 19979, q, IpcAufrufer.Kreis.LOKAL);
    }

    private static String lies(String p) throws Exception {
        return new String(Files.readAllBytes(Paths.get("src/main/java/at/greenchili/surfmate/" + p)),
                StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- Regel

    @Test
    public void shellOderAppNurEigeneUids() {
        assertTrue("eigene Daemons / adb shell", shellOderApp(q(2000, false, false)));
        assertTrue("App", shellOderApp(q(APP, false, false)));
        assertTrue("App neu installiert", shellOderApp(IpcAufruferTest.q(10200, false, APP, 10200, false)));
        assertFalse("tailscaled (Tailnet → 127.0.0.1)", shellOderApp(q(2000, true, false)));
        assertFalse("fremde App", shellOderApp(q(10999, false, false)));
        assertFalse("uid 1000: kein Aufrufer auf 19976", shellOderApp(q(1000, false, false)));
        assertFalse("root", shellOderApp(q(0, false, false)));
        assertFalse("uid unbekannt", shellOderApp(q(-1, false, false)));
        assertFalse("App-uid unbekannt", shellOderApp(IpcAufruferTest.q(APP, false, -1, -1, false)));
    }

    @Test
    public void lokalWieDieWaechterIpc() {
        assertTrue(lokal(q(1000, false, false)));
        assertTrue(lokal(q(2000, false, false)));
        assertTrue(lokal(q(APP, false, false)));
        assertFalse(lokal(q(2000, true, false)));
        assertFalse(lokal(q(10999, false, false)));
        assertFalse(lokal(q(0, false, false)));
        assertFalse(lokal(q(-1, false, false)));
    }

    @Test
    public void failClosedBeiFehlerUndOhneSocket() {
        IpcAufrufer.Quelle kaputt = new IpcAufrufer.Quelle() {
            @Override public int uid(int c, int s) { throw new IllegalStateException("/proc unlesbar"); }
            @Override public boolean gehoertTailscaled(int c, int s) { return false; }
            @Override public int appUid(boolean neu) { return APP; }
            @Override public boolean notschalter() { return false; }
        };
        assertFalse(shellOderApp(kaputt));
        assertFalse(lokal(kaputt));
        assertFalse(IpcAufrufer.verbindungErlaubt(null, IpcAufrufer.Kreis.LOKAL));
        IpcAufrufer.quelle = kaputt;
        assertEquals(-1, IpcAufrufer.uidFuerProtokoll(null));
    }

    @Test
    public void notschalterGiltWieBei19977() {
        assertTrue(shellOderApp(q(10999, false, true)));
        assertTrue(lokal(q(10999, false, true)));
    }

    // ---------------------------------------------------------------- 19976 echt über Loopback

    /** Spielt {@code TcpCommandServer.handleClient} mit einem echten Loopback-Paar; liefert die erste Antwortzeile. */
    private static String tcpBefehl(IpcAufrufer.Quelle q, String befehl) throws Exception {
        IpcAufrufer.quelle = q;
        TcpCommandServer server = new TcpCommandServer(0);
        Method handle = TcpCommandServer.class.getDeclaredMethod("handleClient", Socket.class);
        handle.setAccessible(true);
        try (ServerSocket ss = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             Socket klient = new Socket(InetAddress.getByName("127.0.0.1"), ss.getLocalPort())) {
            Socket angenommen = ss.accept();
            Thread t = new Thread(() -> {
                try { handle.invoke(server, angenommen); } catch (Exception ignored) { }
            });
            t.start();
            klient.setSoTimeout(5000);
            PrintWriter w = new PrintWriter(klient.getOutputStream(), true);
            w.println(befehl);
            BufferedReader r = new BufferedReader(new InputStreamReader(klient.getInputStream()));
            String antwort;
            try {
                antwort = r.readLine();
            } catch (java.net.SocketException e) {
                antwort = null;   // vom Server getrennt (RST)
            }
            klient.close();
            t.join(5000);
            return antwort;
        }
    }

    @Test
    public void fremdeUidWirdSofortGetrennt() throws Exception {
        assertNull("shell", tcpBefehl(q(10999, false, false),
                "{\"cmd\":\"shell\",\"command\":\"id\"}"));
        assertNull("andere Befehle", tcpBefehl(q(10999, false, false), "{\"cmd\":\"auth_invalidate\"}"));
        assertNull("uid unbekannt", tcpBefehl(q(-1, false, false), "{\"cmd\":\"ping\"}"));
        assertNull("tailscaled", tcpBefehl(q(2000, true, false), "{\"cmd\":\"ping\"}"));
    }

    @Test
    public void eigeneUidBekommtAntwortAberKeineShell() throws Exception {
        for (int uid : new int[] {2000, APP}) {
            String antwort = tcpBefehl(q(uid, false, false), "{\"cmd\":\"shell\",\"command\":\"id\"}");
            JSONObject j = new JSONObject(antwort);
            assertEquals("uid " + uid, "error", j.getString("status"));
            assertEquals("Unknown command: shell", j.getString("message"));
            assertFalse(j.has("output"));
        }
    }

    // ---------------------------------------------------------------- Verdrahtung (Quelltext)

    @Test
    public void tcpCommandServerOhneShellUndOhneVollesLog() throws Exception {
        String s = lies("server/TcpCommandServer.java");
        assertFalse("kein sh -c mehr", s.contains("\"sh\", \"-c\""));
        assertFalse(s.contains("Runtime.getRuntime().exec"));
        assertFalse(s.contains("ProcessBuilder"));
        assertFalse("nie die ganze Zeile loggen", s.contains("\"TCP received: \" + line"));
        assertFalse(s.contains("Shell command received"));
        int h = s.indexOf("private void handleClient(Socket client)");
        int pruef = s.indexOf("IpcAufrufer.verbindungErlaubt(client, IpcAufrufer.Kreis.SHELL_ODER_APP)", h);
        int lesen = s.indexOf("client.getInputStream()", h);
        assertTrue("Prüfung vor dem ersten Lesen", h > 0 && pruef > h && lesen > pruef);
        assertEquals("ping", TcpCommandServer.befehlFuerProtokoll("ping"));
        assertEquals("a?b?c", TcpCommandServer.befehlFuerProtokoll("a b;c"));
        assertEquals(40, TcpCommandServer.befehlFuerProtokoll("x".repeat(100)).length());
    }

    @Test
    public void alleLoopbackServerPruefenGleichNachAccept() throws Exception {
        pruefeNachAccept("daemon/SentryDaemon.java", "controlSocket.accept()", "Kreis.LOKAL");
        pruefeNachAccept("daemon/TelegramBotDaemon.java", "serverSocket.accept()", "Kreis.LOKAL");
        pruefeNachAccept("byd/BydEventDaemon.java", "serverSocket.accept()", "Kreis.LOKAL");
        pruefeNachAccept("server/AacIngestServer.java", "serverSocket.accept()", "Kreis.SHELL_ODER_APP");
        // 19977 hatte die Prüfung schon (0.1.38.8)
        assertTrue(lies("server/SurveillanceIpcServer.java")
                .contains("IpcAufrufer.vertraut(client.getPort(), client.getLocalPort())"));
        // Ablehnungs-Logs nennen nur die uid
        for (String p : new String[] {"daemon/SentryDaemon.java", "daemon/TelegramBotDaemon.java",
                "byd/BydEventDaemon.java", "server/AacIngestServer.java", "server/TcpCommandServer.java"}) {
            assertTrue(p, lies(p).contains("Verbindung abgelehnt (uid="));
        }
    }

    private static void pruefeNachAccept(String datei, String accept, String kreis) throws Exception {
        String s = lies(datei);
        int a = s.indexOf(accept);
        assertTrue(datei, a > 0);
        int p = s.indexOf("IpcAufrufer.verbindungErlaubt(", a);
        assertTrue(datei + ": Prüfung gleich nach accept", p > a && p - a < 800);
        assertTrue(datei, s.substring(p, p + 200).contains(kreis));
        int nachPruefung = s.indexOf("getInputStream()", a);
        assertTrue(datei + ": Prüfung vor dem Lesen", nachPruefung < 0 || nachPruefung > p);
    }

    @Test
    public void telegramFeuerUndVergessenBleibtBisZurPruefungOffen() throws Exception {
        String s = lies("telegram/TelegramNotifier.java");
        int f = s.indexOf("private static void sendIpcFireAndForget(");
        String block = s.substring(f, s.indexOf("finally", f));
        assertTrue(block.contains("socket.shutdownOutput()"));
        assertTrue(block.indexOf("socket.getInputStream().read()") > block.indexOf("socket.shutdownOutput()"));
        assertTrue(block.contains("setSoTimeout(2000)"));
    }
}
