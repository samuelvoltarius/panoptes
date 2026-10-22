package at.greenchili.surfmate.wallbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/** Stufe 2c: openWB 1.x/2.x über MQTT – echter Paho-Client gegen eine MQTT-3.1.1-Broker-Attrappe mit openWB-Verhalten. */
public class WallboxOpenWbTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    final AtomicLong jetzt = new AtomicLong(Instant.parse("2026-10-22T10:00:00Z").toEpochMilli());

    // ------------------------------------------------------------------ Broker-Attrappe

    /** Minimaler MQTT-3.1.1-Broker: CONNECT, SUBSCRIBE (gespeicherte Werte), PUBLISH QoS 0/1, PING, DISCONNECT. */
    static final class Broker implements AutoCloseable {
        final ServerSocket server;
        final Map<String, String> gespeichert = new ConcurrentHashMap<>();
        final List<String> empfangen = Collections.synchronizedList(new ArrayList<>());
        BiConsumer<String, String> logik = (t, v) -> { };
        String benutzer, passwort;

        Broker() throws IOException {
            server = new ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"));
            Thread t = new Thread(() -> {
                while (!server.isClosed()) {
                    try {
                        Socket s = server.accept();
                        Thread c = new Thread(() -> sitzung(s));
                        c.setDaemon(true);
                        c.start();
                    } catch (IOException ignored) { }
                }
            });
            t.setDaemon(true);
            t.start();
        }

        int port() {
            return server.getLocalPort();
        }

        private static int laenge(DataInputStream in) throws IOException {
            int m = 1, v = 0, b;
            do {
                b = in.readUnsignedByte();
                v += (b & 127) * m;
                m *= 128;
            } while ((b & 128) != 0);
            return v;
        }

        private static void schreibe(OutputStream o, int kopf, byte[] rumpf) throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            b.write(kopf);
            int l = rumpf.length;
            do {
                int x = l % 128;
                l /= 128;
                if (l > 0) x |= 128;
                b.write(x);
            } while (l > 0);
            b.write(rumpf);
            synchronized (o) {
                o.write(b.toByteArray());
                o.flush();
            }
        }

        private static byte[] str(String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            byte[] r = new byte[b.length + 2];
            r[0] = (byte) (b.length >> 8);
            r[1] = (byte) b.length;
            System.arraycopy(b, 0, r, 2, b.length);
            return r;
        }

        private static String liesStr(byte[] d, int[] pos) {
            int n = ((d[pos[0]] & 0xff) << 8) | (d[pos[0] + 1] & 0xff);
            String s = new String(d, pos[0] + 2, n, StandardCharsets.UTF_8);
            pos[0] += 2 + n;
            return s;
        }

        private void sitzung(Socket s) {
            try (Socket so = s) {
                DataInputStream in = new DataInputStream(so.getInputStream());
                OutputStream out = so.getOutputStream();
                while (true) {
                    int kopf = in.readUnsignedByte();
                    byte[] d = new byte[laenge(in)];
                    in.readFully(d);
                    int typ = kopf >> 4;
                    if (typ == 1) {   // CONNECT
                        int[] p = {0};
                        liesStr(d, p);                 // "MQTT"
                        p[0] += 1;                     // Level
                        int flags = d[p[0]] & 0xff;
                        p[0] += 3;                     // Flags + Keepalive
                        liesStr(d, p);                 // Client-ID
                        String u = (flags & 0x80) != 0 ? liesStr(d, p) : null;
                        String pw = (flags & 0x40) != 0 ? liesStr(d, p) : null;
                        boolean ok = benutzer == null || (benutzer.equals(u) && passwort.equals(pw));
                        schreibe(out, 0x20, new byte[]{0, (byte) (ok ? 0 : 4)});
                        if (!ok) return;
                    } else if (typ == 8) {   // SUBSCRIBE
                        int[] p = {2};
                        List<String> topics = new ArrayList<>();
                        while (p[0] < d.length) {
                            topics.add(liesStr(d, p));
                            p[0] += 1;
                        }
                        byte[] ack = new byte[2 + topics.size()];
                        ack[0] = d[0];
                        ack[1] = d[1];
                        schreibe(out, 0x90, ack);
                        for (String t : topics) {
                            String v = gespeichert.get(t);
                            if (v == null) continue;
                            byte[] ts = str(t), pl = v.getBytes(StandardCharsets.UTF_8);
                            byte[] r = new byte[ts.length + pl.length];
                            System.arraycopy(ts, 0, r, 0, ts.length);
                            System.arraycopy(pl, 0, r, ts.length, pl.length);
                            schreibe(out, 0x31, r);
                        }
                    } else if (typ == 3) {   // PUBLISH
                        int qos = (kopf >> 1) & 3;
                        int[] p = {0};
                        String t = liesStr(d, p);
                        if (qos > 0) {
                            schreibe(out, 0x40, new byte[]{d[p[0]], d[p[0] + 1]});
                            p[0] += 2;
                        }
                        String v = new String(d, p[0], d.length - p[0], StandardCharsets.UTF_8);
                        empfangen.add(t + "=" + v);
                        logik.accept(t, v);
                    } else if (typ == 12) {
                        schreibe(out, 0xD0, new byte[0]);
                    } else if (typ == 14) {
                        return;
                    }
                }
            } catch (IOException ignored) {
            }
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }

    /** openWB 1.x: Ladepunkt 1, Modus global; Sofortladen mit Ladepunkt an → lädt. */
    static Broker openWb1() throws IOException {
        Broker b = new Broker();
        Map<String, String> g = b.gespeichert;
        g.put("openWB/global/ChargeMode", "2");
        g.put("openWB/lp/1/ChargePointEnabled", "1");
        g.put("openWB/lp/1/boolChargeStat", "0");
        g.put("openWB/lp/1/boolPlugStat", "1");
        g.put("openWB/lp/1/W", "0");
        g.put("openWB/config/get/sofort/lp/1/current", "16");
        b.logik = (t, v) -> {
            if (t.equals("openWB/set/ChargeMode") && v.matches("[0-4]")) g.put("openWB/global/ChargeMode", v);
            if (t.equals("openWB/set/lp/1/ChargePointEnabled") && v.matches("[01]")) g.put("openWB/lp/1/ChargePointEnabled", v);
            if (t.equals("openWB/config/set/sofort/lp/1/current")) {
                int a = Integer.parseInt(v);
                if (a >= 6 && a <= 32) g.put("openWB/config/get/sofort/lp/1/current", v);
            }
            boolean laedt = "1".equals(g.get("openWB/lp/1/ChargePointEnabled")) && "0".equals(g.get("openWB/global/ChargeMode"));
            g.put("openWB/lp/1/boolChargeStat", laedt ? "1" : "0");
            g.put("openWB/lp/1/W", laedt ? String.valueOf(Integer.parseInt(g.get("openWB/config/get/sofort/lp/1/current")) * 230) : "0");
        };
        return b;
    }

    static final String PROFIL = "{\"id\":0,\"name\":\"Standard-Ladeprofil\",\"et\":{\"active\":false,\"max_price\":0.0002},"
            + "\"chargemode\":{\"selected\":\"pv_charging\",\"instant_charging\":{\"current\":16,\"limit\":{\"selected\":\"none\"}},"
            + "\"pv_charging\":{\"min_current\":0,\"min_soc\":0}}}";

    /** openWB 2.x: Ladepunkt 1, Profil am Ladepunkt; Sperre des Nutzers über manual_lock. */
    static Broker openWb2(boolean gesperrt) throws IOException {
        Broker b = new Broker();
        Map<String, String> g = b.gespeichert;
        g.put("openWB/chargepoint/1/get/charge_state", "false");
        g.put("openWB/chargepoint/1/get/plug_state", "true");
        g.put("openWB/chargepoint/1/get/power", "0");
        g.put("openWB/chargepoint/1/set/manual_lock", gesperrt ? "true" : "false");
        g.put("openWB/chargepoint/1/set/charge_template", PROFIL);
        b.logik = (t, v) -> {
            if (!t.equals("openWB/set/chargepoint/1/set/charge_template")) return;
            try {
                JSONObject p = new JSONObject(v);
                g.put("openWB/chargepoint/1/set/charge_template", p.toString());
                JSONObject cm = p.getJSONObject("chargemode");
                boolean laedt = !gesperrt && "instant_charging".equals(cm.getString("selected"));
                g.put("openWB/chargepoint/1/get/charge_state", String.valueOf(laedt));
                g.put("openWB/chargepoint/1/get/power", laedt ? String.valueOf(cm.getJSONObject("instant_charging").getInt("current") * 230.0) : "0");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        return b;
    }

    WallboxDienst dienst(Broker b) {
        OpenWbAdapter.Mqtt umgeleitet = new OpenWbAdapter.Mqtt() {   // jede private IP → Test-Broker, sonst echter Paho-Weg
            @Override
            public Map<String, String> lies(String h, int p, String u, String pw, List<String> t,
                                            java.util.function.Predicate<Map<String, String>> f, long w) throws WallboxFehler {
                return OpenWbAdapter.PAHO.lies("127.0.0.1", b.port(), u, pw, t, f, 1500);
            }

            @Override
            public void sende(String h, int p, String u, String pw, String t, String v) throws WallboxFehler {
                OpenWbAdapter.PAHO.sende("127.0.0.1", b.port(), u, pw, t, v);
            }
        };
        return new WallboxDienst(new File(tmp.getRoot(), "wb.json"), new File(tmp.getRoot(), "p.jsonl"), WallboxTest.GEHEIM, null,
                WallboxTest.DNS, () -> "steht", jetzt::get, ms -> { }).mitOpenWb(umgeleitet);
    }

    static JSONObject geraet() throws Exception {
        return new JSONObject().put("schluessel", "owb").put("titel", "openWB").put("adapter", "openwb").put("adresse", "192.168.1.70").put("nummer", 1);
    }

    @Test
    public void openWb1_modiStromZurueckgelesen() throws Exception {
        try (Broker b = openWb1()) {
            WallboxDienst d = dienst(b);
            assertEquals(200, d.speichereEinstellungen(WallboxDirektTest.konfig(geraet())).status);
            JSONObject lp = d.status().json.getJSONArray("ladepunkte").getJSONObject(0);
            assertTrue(d.status().json.toString(), d.status().json.getBoolean("erreichbar"));
            assertEquals("pv", lp.getString("modus"));
            assertEquals(16, lp.getInt("stromA"));
            assertTrue(lp.getBoolean("verbunden"));
            assertEquals(4, lp.getJSONObject("faehigkeiten").getJSONArray("modus").length());
            WallboxTuyaTest.gibFrei(d, "direkt:owb");
            assertTrue(WallboxTuyaTest.fuehreAus(d, "direkt:owb", "strom", 10).getBoolean("uebernommen"));
            JSONObject r = WallboxTuyaTest.fuehreAus(d, "direkt:owb", "modus", "sofort");
            assertTrue(r.getBoolean("uebernommen"));
            assertTrue(r.getJSONObject("ladepunkt").getBoolean("laedt"));
            assertEquals(2300, r.getJSONObject("ladepunkt").getLong("leistungW"));
            assertTrue(WallboxTuyaTest.fuehreAus(d, "direkt:owb", "modus", "minpv").getBoolean("uebernommen"));
            assertTrue(WallboxTuyaTest.fuehreAus(d, "direkt:owb", "modus", "aus").getBoolean("uebernommen"));
            assertEquals("0", b.gespeichert.get("openWB/lp/1/ChargePointEnabled"));
            assertEquals("1", b.gespeichert.get("openWB/global/ChargeMode"));   // Aus lässt den globalen Modus stehen
            assertTrue(b.empfangen.contains("openWB/config/set/sofort/lp/1/current=10"));
        }
    }

    @Test
    public void openWb2_profilNurEinFeldGeaendert() throws Exception {
        try (Broker b = openWb2(false)) {
            WallboxDienst d = dienst(b);
            d.speichereEinstellungen(WallboxDirektTest.konfig(geraet()));
            JSONObject lp = d.status().json.getJSONArray("ladepunkte").getJSONObject(0);
            assertEquals("pv", lp.getString("modus"));
            assertEquals(3, lp.getJSONObject("faehigkeiten").getJSONArray("modus").length());   // kein Min+PV in 2.x
            WallboxTuyaTest.gibFrei(d, "direkt:owb");
            assertTrue(WallboxTuyaTest.fuehreAus(d, "direkt:owb", "strom", 12).getBoolean("uebernommen"));
            JSONObject r = WallboxTuyaTest.fuehreAus(d, "direkt:owb", "modus", "sofort");
            assertTrue(r.getBoolean("uebernommen"));
            assertTrue(r.getJSONObject("ladepunkt").getBoolean("laedt"));
            assertEquals(2760, r.getJSONObject("ladepunkt").getLong("leistungW"));
            JSONObject p = new JSONObject(b.gespeichert.get("openWB/chargepoint/1/set/charge_template"));
            assertEquals("Standard-Ladeprofil", p.getString("name"));                    // Rest unverändert
            assertEquals(0.0002, p.getJSONObject("et").getDouble("max_price"), 1e-9);
            assertEquals("none", p.getJSONObject("chargemode").getJSONObject("instant_charging").getJSONObject("limit").getString("selected"));
            assertTrue(WallboxTuyaTest.fuehreAus(d, "direkt:owb", "modus", "aus").getBoolean("uebernommen"));
            assertEquals("stop", new JSONObject(b.gespeichert.get("openWB/chargepoint/1/set/charge_template")).getJSONObject("chargemode").getString("selected"));
            assertEquals(400, WallboxTuyaTest.status(d, "direkt:owb", "modus", "minpv"));
            for (String e : b.empfangen) assertFalse(e, e.contains("manual_lock"));
        }
    }

    @Test
    public void openWb2_sperreDesNutzersBleibt() throws Exception {
        try (Broker b = openWb2(true)) {
            WallboxDienst d = dienst(b);
            d.speichereEinstellungen(WallboxDirektTest.konfig(geraet()));
            JSONObject lp = d.status().json.getJSONArray("ladepunkte").getJSONObject(0);
            assertEquals("aus", lp.getString("modus"));
            assertEquals("openwb_gesperrt", lp.getString("hinweis"));
            WallboxTuyaTest.gibFrei(d, "direkt:owb");
            assertFalse(WallboxTuyaTest.fuehreAus(d, "direkt:owb", "modus", "sofort").getBoolean("uebernommen"));   // ehrlich: abweichend
            assertEquals("true", b.gespeichert.get("openWB/chargepoint/1/set/manual_lock"));
        }
    }

    @Test
    public void openWb_leererBroker_undFalscheAnmeldung() throws Exception {
        try (Broker b = new Broker()) {
            WallboxDienst d = dienst(b);
            d.speichereEinstellungen(WallboxDirektTest.konfig(geraet()));
            assertEquals("openwb_keine_daten", d.status().json.getString("fehler"));
        }
        try (Broker b = openWb1()) {
            b.benutzer = "openwb";
            b.passwort = "richtig";
            new File(tmp.getRoot(), "wb.json").delete();
            WallboxDienst d = dienst(b);
            d.speichereEinstellungen(WallboxDirektTest.konfig(geraet().put("benutzer", "openwb").put("passwort", "falsch")));
            assertEquals("anmeldung", d.status().json.getString("fehler"));
            d.speichereEinstellungen(WallboxDirektTest.konfig(geraet().put("benutzer", "openwb").put("passwort", "richtig")));
            assertTrue(d.status().json.toString(), d.status().json.getBoolean("erreichbar"));
        }
    }
}
