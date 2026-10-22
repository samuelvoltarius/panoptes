package at.greenchili.surfmate.mqtt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Deploy-Befund 21.10.: Panoptes-tailscaled ohne Kernel-Tun → MQTT zu Tailnet-Brokern über die
 * LocalAPI wählen (wie `tailscale nc`). Geprüft gegen eine nachgebaute LocalAPI, die nach
 * „101 Switching Protocols“ zum Test-Broker durchreicht.
 */
public class TailnetDialTest {

    private WiederverbindungTest.TestBroker broker;
    private FakeLocalApi api;
    private MqttPublisherService pub;
    private int altPort;

    @After
    public void aufraeumen() throws Exception {
        if (pub != null) pub.disconnect();
        if (api != null) api.stop();
        if (broker != null) broker.stop();
        if (altPort != 0) TailnetDial.localApiPort = altPort;
    }

    @Test
    public void entscheidung() {
        assertFalse("Internet-Broker: nie Dial", MqttPublisherService.nutzeTailnetDial("tcp://broker.example.org:1883", 0));
        assertFalse("jeder 3. Fehlversuch direkt", MqttPublisherService.nutzeTailnetDial("tcp://100.71.1.2:1883", 2));
    }

    @Test
    public void anfrageWieTailscaleNc() {
        String a = TailnetDial.anfrage("100.64.0.10", 1883);
        assertTrue(a.startsWith("POST /localapi/v0/dial HTTP/1.1\r\n"));
        assertTrue(a.contains("Upgrade: ts-dial\r\n"));
        assertTrue(a.contains("Dial-Host: 100.64.0.10\r\n"));
        assertTrue(a.contains("Dial-Port: 1883\r\n"));
        assertTrue(a.endsWith("\r\n\r\n"));
    }

    @Test
    public void mqttLaeuftUeberDieLocalApi() throws Exception {
        broker = WiederverbindungTest.TestBroker.start(0);
        api = FakeLocalApi.start(broker.port(), 101);
        altPort = TailnetDial.localApiPort;
        TailnetDial.localApiPort = api.port();

        pub = new MqttPublisherService(MqttConnectionConfig.fromJson(new JSONObject()
                .put("id", "t2").put("name", "tailnet")
                .put("brokerUrl", "tcp://100.64.0.10:1883")
                .put("topic", "panoptes/test").put("enabled", true)), "geraet");
        boolean ok = pub.connect();
        assertTrue(pub.getStatus().toString() + " anfragen=" + api.anfragen, ok);
        assertEquals(1, broker.verbindungen.get());
        assertEquals("tailnet", pub.getStatus().getString("weg"));
        assertTrue(api.anfragen.get(0).contains("Dial-Host: 100.64.0.10"));
        assertTrue(api.anfragen.get(0).contains("Dial-Port: 1883"));
    }

    @Test
    public void abgelehnterDialScheitertSauber() throws Exception {
        api = FakeLocalApi.start(1, 403);
        altPort = TailnetDial.localApiPort;
        TailnetDial.localApiPort = api.port();
        Socket s = TailnetDial.factory().createSocket();
        try {
            s.connect(InetSocketAddress.createUnresolved("100.64.0.10", 1883), 2_000);
            throw new AssertionError("hätte scheitern müssen");
        } catch (IOException erwartet) {
            assertTrue(erwartet.getMessage(), erwartet.getMessage().contains("403"));
        }
        assertTrue(s.isClosed());
    }

    /** Nachgebaute LocalAPI: liest den Kopf, antwortet mit {@code status}, reicht bei 101 zum Ziel durch. */
    static final class FakeLocalApi {
        final ServerSocket server;
        final List<String> anfragen = new CopyOnWriteArrayList<>();
        final int zielPort, status;

        private FakeLocalApi(ServerSocket s, int zielPort, int status) {
            this.server = s; this.zielPort = zielPort; this.status = status;
        }

        static FakeLocalApi start(int zielPort, int status) throws IOException {
            ServerSocket s = new ServerSocket();
            s.bind(new InetSocketAddress("127.0.0.1", 0));
            FakeLocalApi a = new FakeLocalApi(s, zielPort, status);
            Thread t = new Thread(a::annehmen, "FakeLocalApi");
            t.setDaemon(true);
            t.start();
            return a;
        }

        int port() { return server.getLocalPort(); }

        void annehmen() {
            while (true) {
                Socket c;
                try {
                    c = server.accept();
                } catch (IOException e) {
                    return;
                }
                Thread t = new Thread(() -> bediene(c), "FakeLocalApi-Client");
                t.setDaemon(true);
                t.start();
            }
        }

        void bediene(Socket c) {
            try {
                String kopf = TailnetDial.leseKopf(c.getInputStream());
                anfragen.add(kopf);
                OutputStream out = c.getOutputStream();
                if (status != 101) {
                    out.write(("HTTP/1.1 " + status + " Forbidden\r\nContent-Length: 0\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    c.close();
                    return;
                }
                out.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: ts-dial\r\nConnection: upgrade\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII));
                out.flush();
                Socket ziel = new Socket("127.0.0.1", zielPort);
                pumpe(c.getInputStream(), ziel.getOutputStream());
                pumpe(ziel.getInputStream(), c.getOutputStream());
            } catch (IOException ignored) {
                // z. B. die kurze Erreichbarkeitsprobe ohne Anfrage
            }
        }

        static void pumpe(InputStream in, OutputStream out) {
            Thread t = new Thread(() -> {
                byte[] buf = new byte[4096];
                int n;
                try {
                    while ((n = in.read(buf)) >= 0) { out.write(buf, 0, n); out.flush(); }
                } catch (IOException ignored) {
                } finally {
                    try { out.close(); } catch (IOException ignored) {}
                }
            }, "Pumpe");
            t.setDaemon(true);
            t.start();
        }

        void stop() throws IOException { server.close(); }
    }
}
