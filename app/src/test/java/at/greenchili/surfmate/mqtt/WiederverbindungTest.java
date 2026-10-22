package at.greenchili.surfmate.mqtt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Koordination 21.10.: MQTT fiel beim Parken (Netzwechsel/power_level) auf „getrennt“ und kam nicht von
 * selbst zurück. Geprüft wird gegen einen kleinen echten Test-Broker (MQTT 3.1.1, nur das Nötigste):
 * Verbindungsabbruch, Broker kurz weg, Netzwechsel – plus die reinen Entscheidungen.
 */
public class WiederverbindungTest {

    // ------------------------------------------------------------ reine Entscheidungen

    @Test
    public void backoffFuenfBisSechzigSekundenMitStreuung() {
        assertEquals(0L, Wiederverbindung.backoffMs(0, 0.5));
        assertEquals(5_000L, Wiederverbindung.backoffMs(1, 0.5));
        assertEquals(10_000L, Wiederverbindung.backoffMs(2, 0.5));
        assertEquals(60_000L, Wiederverbindung.backoffMs(5, 0.5));   // 80 s gedeckelt
        assertEquals(60_000L, Wiederverbindung.backoffMs(40, 0.5));  // kein Überlauf
        assertEquals(48_000L, Wiederverbindung.backoffMs(9, 0.0));   // -20 %
        assertEquals(72_000L, Wiederverbindung.backoffMs(9, 1.0));   // +20 %
    }

    @Test
    public void waechterEntscheidet() {
        long jetzt = 1_000_000L;
        long grenze = Wiederverbindung.stehtNachMs(5_000L, 0L);
        assertEquals(90_000L, grenze);
        assertEquals(Wiederverbindung.Aktion.NEU_AUFSETZEN,
                Wiederverbindung.entscheide(false, false, true, jetzt - grenze - 1, jetzt, grenze));
        assertEquals(Wiederverbindung.Aktion.NETZ_NEU,
                Wiederverbindung.entscheide(true, true, true, jetzt - 1_000L, jetzt, grenze));
        assertEquals(Wiederverbindung.Aktion.SOFORT_PRUEFEN,
                Wiederverbindung.entscheide(false, true, false, jetzt - 1_000L, jetzt, grenze));
        assertEquals(Wiederverbindung.Aktion.NICHTS,
                Wiederverbindung.entscheide(false, false, false, jetzt - 1_000L, jetzt, grenze));
        assertEquals(Wiederverbindung.Aktion.NICHTS,
                Wiederverbindung.entscheide(false, false, true, 0L, jetzt, grenze));
    }

    @Test
    public void fingerabdruckUnabhaengigVonReihenfolge() {
        List<String> a = Arrays.asList("wlan0=192.168.43.1", "rmnet0=10.1.2.3");
        List<String> b = Arrays.asList("rmnet0=10.1.2.3", "wlan0=192.168.43.1");
        assertEquals(Wiederverbindung.fingerabdruck(a), Wiederverbindung.fingerabdruck(b));
        assertFalse(Wiederverbindung.fingerabdruck(a).equals(
                Wiederverbindung.fingerabdruck(Arrays.asList("rmnet0=10.9.9.9", "wlan0=192.168.43.1"))));
    }

    // ------------------------------------------------------------ gegen einen Test-Broker

    private TestBroker broker;
    private MqttPublisherService pub;

    @After
    public void aufraeumen() throws Exception {
        if (pub != null) pub.disconnect();
        if (broker != null) broker.stop();
    }

    private MqttPublisherService publisherFuer(int port) throws Exception {
        JSONObject j = new JSONObject()
                .put("id", "t1").put("name", "test")
                .put("brokerUrl", "tcp://127.0.0.1:" + port)
                .put("topic", "panoptes/test").put("enabled", true);
        return new MqttPublisherService(MqttConnectionConfig.fromJson(j), "geraet");
    }

    private static boolean wiederVerbunden(MqttPublisherService p, long maxMs) throws Exception {
        long ende = System.currentTimeMillis() + maxMs;
        while (System.currentTimeMillis() < ende) {
            if (p.ensureAlive() && p.isConnected()) return true;
            Thread.sleep(200);
        }
        return false;
    }

    /** Paho bemerkt ein vom Broker geschlossenes Socket asynchron (Empfangs-Thread). */
    private static boolean getrenntGemeldet(MqttPublisherService p, long maxMs) throws Exception {
        long ende = System.currentTimeMillis() + maxMs;
        while (System.currentTimeMillis() < ende) {
            if (!p.isConnected()) return true;
            Thread.sleep(50);
        }
        return false;
    }

    @Test
    public void verbindungsabbruchHeiltSichSelbst() throws Exception {
        broker = TestBroker.start(0);
        pub = publisherFuer(broker.port());
        assertTrue(pub.connect());
        assertEquals(1, broker.verbindungen.get());

        broker.trenneAlle();                       // Broker wirft den Client raus
        assertTrue(getrenntGemeldet(pub, 5_000L));
        assertTrue("muss sich selbst wieder verbinden", wiederVerbunden(pub, 15_000L));
        assertTrue(broker.verbindungen.get() >= 2);
    }

    @Test
    public void brokerKurzWegDannZurueck() throws Exception {
        broker = TestBroker.start(0);
        int port = broker.port();
        pub = publisherFuer(port);
        assertTrue(pub.connect());

        broker.stop();                             // Broker ganz weg
        broker = null;
        assertTrue(getrenntGemeldet(pub, 5_000L));
        assertFalse(wiederVerbunden(pub, 2_500L));
        assertTrue(pub.getBackoffMs() > 0);
        assertTrue("Deckel 60 s (+20 %)", pub.getBackoffMs() <= 72_000L);

        broker = TestBroker.start(port);           // wieder da
        assertTrue(wiederVerbunden(pub, 15_000L));
        assertEquals(0L, pub.getBackoffMs());
        assertEquals("verbunden", pub.getStatus().getString("zustand"));
    }

    @Test
    public void netzwechselErsetztDenSocketSofort() throws Exception {
        broker = TestBroker.start(0);
        pub = publisherFuer(broker.port());
        assertTrue(pub.connect());
        assertEquals(1, broker.verbindungen.get());

        pub.verbundenSeitFuerTest(System.currentTimeMillis() - 120_000L); // seit 2 min verbunden
        pub.netzGewechselt();                       // Wächter hat neuen Fingerabdruck gesehen
        assertEquals(0L, pub.getBackoffMs());
        assertTrue(pub.ensureAlive());
        assertEquals("neuer Socket statt Warten auf Keep-Alive", 2, broker.verbindungen.get());
    }

    @Test
    public void netzwechselKurzNachDemVerbindenBautNichtNochmalAuf() throws Exception {
        broker = TestBroker.start(0);
        pub = publisherFuer(broker.port());
        assertTrue(pub.connect());
        pub.netzGewechselt();                       // verspäteter Auslöser wenige Sekunden nach Connected
        assertTrue(pub.ensureAlive());
        assertEquals("entprellt: kein zweiter Neuaufbau", 1, broker.verbindungen.get());
    }

    /** Minimaler MQTT-3.1.1-Broker: CONNACK, PUBACK (QoS 1), SUBACK, PINGRESP. */
    static final class TestBroker {
        final ServerSocket server;
        final AtomicInteger verbindungen = new AtomicInteger();
        final List<Socket> clients = new CopyOnWriteArrayList<>();
        volatile boolean laeuft = true;

        private TestBroker(ServerSocket s) { server = s; }

        static TestBroker start(int port) throws IOException {
            ServerSocket s = new ServerSocket();
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress("127.0.0.1", port));
            TestBroker b = new TestBroker(s);
            Thread t = new Thread(b::annehmen, "TestBroker");
            t.setDaemon(true);
            t.start();
            return b;
        }

        int port() { return server.getLocalPort(); }

        void annehmen() {
            while (laeuft) {
                try {
                    Socket c = server.accept();
                    clients.add(c);
                    Thread t = new Thread(() -> bediene(c), "TestBroker-Client");
                    t.setDaemon(true);
                    t.start();
                } catch (IOException e) {
                    return;
                }
            }
        }

        void bediene(Socket c) {
            try {
                DataInputStream in = new DataInputStream(c.getInputStream());
                OutputStream out = c.getOutputStream();
                while (true) {
                    int kopf = in.read();
                    if (kopf < 0) return;
                    int laenge = 0, mult = 1, b;
                    do { b = in.readUnsignedByte(); laenge += (b & 0x7F) * mult; mult *= 128; } while ((b & 0x80) != 0);
                    byte[] rest = new byte[laenge];
                    in.readFully(rest);
                    int typ = (kopf >> 4) & 0x0F;
                    synchronized (out) {
                        if (typ == 1) {                       // CONNECT
                            verbindungen.incrementAndGet();
                            out.write(new byte[] {0x20, 0x02, 0x00, 0x00});
                        } else if (typ == 3) {                // PUBLISH
                            int qos = (kopf >> 1) & 0x03;
                            if (qos == 1) {
                                int tl = ((rest[0] & 0xFF) << 8) | (rest[1] & 0xFF);
                                out.write(new byte[] {0x40, 0x02, rest[2 + tl], rest[3 + tl]});
                            }
                        } else if (typ == 8) {                // SUBSCRIBE
                            int n = 0;
                            int i = 2;
                            while (i < rest.length) { int tl = ((rest[i] & 0xFF) << 8) | (rest[i + 1] & 0xFF); i += 2 + tl + 1; n++; }
                            byte[] ack = new byte[4 + n];
                            ack[0] = (byte) 0x90; ack[1] = (byte) (2 + n); ack[2] = rest[0]; ack[3] = rest[1];
                            out.write(ack);
                        } else if (typ == 12) {               // PINGREQ
                            out.write(new byte[] {(byte) 0xD0, 0x00});
                        } else if (typ == 14) {               // DISCONNECT
                            c.close();
                            return;
                        }
                        out.flush();
                    }
                }
            } catch (IOException ignored) {
            }
        }

        void trenneAlle() {
            for (Socket c : clients) { try { c.close(); } catch (IOException ignored) {} }
            clients.clear();
        }

        void stop() throws IOException {
            laeuft = false;
            trenneAlle();
            server.close();
        }
    }
}
