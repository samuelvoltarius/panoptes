package at.greenchili.surfmate.wallbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** 0.1.19 „Wallbox finden“ (WALLBOX_FERN_KONZEPT §3): nur finden, Vorschläge passen zu den Einstellungen, nur lesen. */
public class WallboxSucheTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final String TUYA_JSON = "{\"ip\":\"192.168.1.50\",\"gwId\":\"bf1234567890abcdefgh\",\"active\":2,"
            + "\"ability\":0,\"mode\":0,\"encrypt\":true,\"productKey\":\"keyqccdz\",\"version\":\"3.3\"}";

    // ------------------------------------------------------------------ Tuya

    static byte[] tuya55aa(byte[] nutz) {
        ByteBuffer b = ByteBuffer.allocate(20 + nutz.length + 8);
        b.putInt(0x000055AA).putInt(0).putInt(0x13).putInt(4 + nutz.length + 8).putInt(0).put(nutz)
                .putInt(0x12345678).putInt(0x0000AA55);
        return b.array();
    }

    static byte[] tuya33() throws Exception {
        Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(WallboxSuche.TUYA_UDP_SCHLUESSEL, "AES"));
        return tuya55aa(c.doFinal(TUYA_JSON.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void tuyaBroadcast33Und31() throws Exception {
        JSONObject d = WallboxSuche.tuyaBroadcast(tuya33());
        assertEquals("bf1234567890abcdefgh", d.getString("gwId"));
        assertEquals("3.3", d.getString("version"));
        JSONObject k = WallboxSuche.tuyaBroadcast(tuya55aa(TUYA_JSON.replace("3.3", "3.1").getBytes(StandardCharsets.UTF_8)));
        assertEquals("3.1", k.getString("version"));
        assertNull(WallboxSuche.tuyaBroadcast("hallo welt, kein tuya paket".getBytes(StandardCharsets.UTF_8)));
        byte[] kaputt = tuya33();
        kaputt[25] ^= 0x55;
        assertNull(WallboxSuche.tuyaBroadcast(kaputt));
    }

    /** 3.5-Format nach tinytuya [am echten Gerät ungeprüft] – hier nur Hin- und Rückweg. */
    @Test
    public void tuyaBroadcast35Gcm() throws Exception {
        byte[] iv = new byte[12];
        Arrays.fill(iv, (byte) 7);
        ByteBuffer kopf = ByteBuffer.allocate(18);
        byte[] klar = ("\0\0\0\0" + TUYA_JSON.replace("3.3", "3.5")).getBytes(StandardCharsets.UTF_8);
        int len = 12 + klar.length + 16;
        kopf.putInt(0x00006699).putShort((short) 0).putInt(1).putInt(0x13).putInt(len);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(WallboxSuche.TUYA_UDP_SCHLUESSEL, "AES"), new GCMParameterSpec(128, iv));
        c.updateAAD(Arrays.copyOfRange(kopf.array(), 4, 18));
        byte[] ct = c.doFinal(klar);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(kopf.array());
        o.write(iv);
        o.write(ct);
        o.write(new byte[]{0, 0, (byte) 0x99, 0x66});
        assertEquals("3.5", WallboxSuche.tuyaBroadcast(o.toByteArray()).getString("version"));
    }

    // ------------------------------------------------------------------ mDNS

    static void name(ByteArrayOutputStream o, String n) {
        for (String t : n.split("\\.")) {
            byte[] b = t.getBytes(StandardCharsets.UTF_8);
            o.write(b.length);
            o.write(b, 0, b.length);
        }
        o.write(0);
    }

    static void rr(ByteArrayOutputStream o, String n, int typ, byte[] daten) {
        name(o, n);
        o.write(typ >> 8); o.write(typ); o.write(0x80); o.write(1); o.write(0); o.write(0); o.write(0x11); o.write(0x94);
        o.write(daten.length >> 8); o.write(daten.length);
        o.write(daten, 0, daten.length);
    }

    static byte[] nameBytes(String n) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        name(o, n);
        return o.toByteArray();
    }

    /** Antwort: PTR dienst→instanz, SRV instanz→host:port, A host→ip. */
    static byte[] mdnsAntwort(String dienst, String instanz, String host, int port, String ip) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] kopf = {0, 0, (byte) 0x84, 0, 0, 0, 0, 1, 0, 0, 0, 2};
        o.write(kopf, 0, kopf.length);
        rr(o, dienst, 12, nameBytes(instanz + "." + dienst));
        ByteArrayOutputStream s = new ByteArrayOutputStream();
        s.write(0); s.write(0); s.write(0); s.write(0); s.write(port >> 8); s.write(port);
        byte[] h = nameBytes(host);
        s.write(h, 0, h.length);
        rr(o, instanz + "." + dienst, 33, s.toByteArray());
        byte[] a = new byte[4];
        String[] p = ip.split("\\.");
        for (int i = 0; i < 4; i++) a[i] = (byte) Integer.parseInt(p[i]);
        rr(o, host, 1, a);
        return o.toByteArray();
    }

    @Test
    public void mdnsAnfrageUndAntwort() throws Exception {
        byte[] q = WallboxSuche.mdnsAnfrage("_home-assistant._tcp.local");
        assertEquals(1, q[5]);
        assertEquals("_home-assistant._tcp.local", WallboxSuche.name(q, new int[]{12}));
        List<JSONObject> l = WallboxSuche.mdnsAntwort(
                mdnsAntwort("_http._tcp.local", "go-eCharger-012345", "goe.local", 80, "192.168.1.60"), "192.168.1.99");
        assertEquals(1, l.size());
        assertEquals("go-eCharger-012345", l.get(0).getString("instanz"));
        assertEquals("192.168.1.60", l.get(0).getString("ip"));
        JSONObject t = WallboxSuche.mdnsTreffer(l.get(0));
        assertEquals("goe", t.getString("art"));
        JSONObject ha = WallboxSuche.mdnsTreffer(WallboxSuche.mdnsAntwort(
                mdnsAntwort("_home-assistant._tcp.local", "Zuhause", "ha.local", 8123, "192.168.1.20"), "x").get(0));
        assertEquals("http://192.168.1.20:8123", ha.getString("url"));
        // öffentliche Adressen werden nie Treffer
        assertNull(WallboxSuche.mdnsTreffer(new JSONObject().put("dienst", "_http._tcp.local").put("instanz", "evcc").put("ip", "8.8.8.8")));
        // Kompression: Zeiger auf einen schon gelesenen Namen
        byte[] k = {3, 'a', 'b', 'c', 5, 'l', 'o', 'c', 'a', 'l', 0, 1, 'x', (byte) 0xc0, 0};
        int[] pos = {11};
        assertEquals("x.abc.local", WallboxSuche.name(k, pos));
        assertEquals(15, pos[0]);
    }

    @Test
    public void arpNurVollstaendigeEintraege() {
        List<String> z = Arrays.asList(
                "IP address       HW type     Flags       HW address            Mask     Device",
                "192.168.1.60     0x1         0x2         aa:bb:cc:dd:ee:01     *        wlan0",
                "192.168.1.61     0x1         0x0         00:00:00:00:00:00     *        wlan0",
                "192.168.1.62     0x1         0x2         aa:bb:cc:dd:ee:03     *        wlan0");
        assertEquals(Arrays.asList("192.168.1.60", "192.168.1.62"), WallboxSuche.arp(z));
        assertTrue(WallboxSuche.privat("172.20.0.1"));
        assertFalse(WallboxSuche.privat("100.108.144.19"));
    }

    // ------------------------------------------------------------------ ganze Suche

    /** Nachgebautes WLAN: Tuya-Broadcast, KEBA, HA per mDNS, go-e + Modbus als Nachbarn. */
    final class Wlan implements WallboxSuche.Netz {
        public List<WallboxSuche.Paket> lausche(int port, long ms) {
            try {
                return port == 6667 ? Collections.singletonList(new WallboxSuche.Paket("192.168.1.50", tuya33()))
                        : Collections.emptyList();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        public List<WallboxSuche.Paket> rufe(byte[] d, int ziel, int eigen, long ms) {
            assertEquals("i", new String(d, StandardCharsets.US_ASCII));
            return Collections.singletonList(new WallboxSuche.Paket("192.168.1.70",
                    "\"Firmware\":\"P30 v 3.10.16\"".getBytes(StandardCharsets.US_ASCII)));
        }
        public List<WallboxSuche.Paket> mdns(byte[] q, long ms) {
            return Collections.singletonList(new WallboxSuche.Paket("192.168.1.20",
                    mdnsAntwort("_home-assistant._tcp.local", "Zuhause", "ha.local", 8123, "192.168.1.20")));
        }
        public List<String> nachbarn() {
            return Arrays.asList("192.168.1.60", "192.168.1.80", "8.8.8.8");
        }
        public boolean tcpOffen(String ip, int port, int ms) {
            assertFalse("nie ins Internet", ip.startsWith("8."));
            return ("192.168.1.60".equals(ip) && port == 80) || ("192.168.1.80".equals(ip) && port == 502);
        }
    }

    private final List<String> anfragen = Collections.synchronizedList(new ArrayList<>());
    private final WallboxHttp http = (m, url, k, r) -> {
        anfragen.add(m + " " + url);
        if (url.equals("http://192.168.1.60/api/status")) {
            return new WallboxHttp.Antwort(200, "{\"car\":1,\"amp\":16,\"sse\":\"012345\"}");
        }
        return new WallboxHttp.Antwort(404, "");
    };

    @Test
    public void sucheFindetAlleArtenNurLesendUndVorschlaegePassen() throws Exception {
        JSONObject r = new WallboxSuche(new Wlan(), http).suche();
        JSONArray t = r.getJSONArray("treffer");
        Map<String, JSONObject> nachArt = new LinkedHashMap<>();
        for (int i = 0; i < t.length(); i++) nachArt.put(t.getJSONObject(i).getString("art"), t.getJSONObject(i));
        assertEquals(new HashSet<>(Arrays.asList("tuya", "keba", "ha", "goe", "modbus")), nachArt.keySet());
        assertEquals("bf1234567890abcdefgh", nachArt.get("tuya").getJSONObject("details").getString("geraeteId"));
        assertEquals("localKey", nachArt.get("tuya").getJSONArray("braucht").getString(0));
        assertEquals("modell", nachArt.get("modbus").getJSONArray("braucht").getString(0));
        assertTrue(nachArt.get("modbus").getBoolean("unsicher"));
        for (String a : anfragen) assertTrue("nur lesen: " + a, a.startsWith("GET "));
        // jeder Vorschlag wird von den Einstellungen angenommen (Modbus erst mit Modell)
        for (JSONObject x : nachArt.values()) {
            if (x.isNull("vorschlag")) continue;
            JSONObject v = new JSONObject(x.getJSONObject("vorschlag").toString());
            if ("modbus".equals(x.getString("art"))) {
                v.getJSONObject("direkt").getJSONArray("geraete").getJSONObject(0).put("modell", "keba_p40");
            }
            JSONObject k = WallboxKonfig.uebernimm(WallboxKonfig.leer(), v, WallboxTest.GEHEIM, 0);
            assertEquals(x.getString("art"), v.getString("quelle"), WallboxKonfig.quelle(k));
        }
    }

    @Test
    public void dienstSucheUeberApi() throws Exception {
        WallboxDienst d = new WallboxDienst(new File(tmp.getRoot(), "w.json"), new File(tmp.getRoot(), "p.jsonl"),
                WallboxTest.GEHEIM, http, WallboxTest.DNS, () -> "steht", System::currentTimeMillis, ms -> { })
                .mitSuche(new Wlan());
        WallboxDienst.Ergebnis e = d.suche();
        assertEquals(200, e.status);
        assertEquals(5, e.json.getInt("anzahl"));
        assertFalse("nichts gespeichert", new File(tmp.getRoot(), "w.json").exists());
    }
}
