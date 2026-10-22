package at.greenchili.surfmate.wallbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Stufe 2b: Modbus-Kern mit Datentabelle (KEBA P40, Heidelberg EC) und KEBA UDP – gegen Attrappen, nie gegen echte Geräte. */
public class WallboxModbusKebaTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    final AtomicLong jetzt = new AtomicLong(Instant.parse("2026-10-22T10:00:00Z").toEpochMilli());

    static JSONObject tabelle() throws Exception {
        return new JSONObject(new String(Files.readAllBytes(Paths.get("src/main/assets/web/shared/wallbox-modbus-modelle.json")),
                StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ Modbus-Attrappe

    /** Modbus-TCP-Server: Holding (3) und Input (4) getrennt, Unit-ID geprüft, FC 6/16 schreiben Holding. */
    static final class ModbusAttrappe implements AutoCloseable {
        final int einheit;
        final Map<Integer, Integer> holding = Collections.synchronizedMap(new HashMap<>());
        final Map<Integer, Integer> input = Collections.synchronizedMap(new HashMap<>());
        final List<String> log = Collections.synchronizedList(new ArrayList<>());
        final ServerSocket server;
        Runnable nachSchreiben = () -> { };

        ModbusAttrappe(int einheit) throws IOException {
            this.einheit = einheit;
            server = new ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"));
            Thread t = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket s = server.accept()) {
                        s.setSoTimeout(4000);
                        bediene(new DataInputStream(s.getInputStream()), s.getOutputStream());
                    } catch (Exception ignored) { }
                }
            });
            t.setDaemon(true);
            t.start();
        }

        void u32(Map<Integer, Integer> m, int reg, long v) {
            m.put(reg, (int) (v >>> 16) & 0xffff);
            m.put(reg + 1, (int) v & 0xffff);
        }

        private void bediene(DataInputStream in, OutputStream out) throws IOException {
            int tid = in.readUnsignedShort(), proto = in.readUnsignedShort(), len = in.readUnsignedShort();
            int unit = in.readUnsignedByte();
            byte[] pdu = new byte[len - 1];
            in.readFully(pdu);
            ByteBuffer p = ByteBuffer.wrap(pdu);
            int fc = p.get() & 0xff;
            log.add("fc" + fc + " unit" + unit);
            byte[] antwort;
            if (unit != einheit || proto != 0) return;   // falsche Einheit: keine Antwort
            int adr = p.getShort() & 0xffff;
            if (fc == 3 || fc == 4) {
                int n = p.getShort() & 0xffff;
                Map<Integer, Integer> m = fc == 3 ? holding : input;
                boolean da = true;
                for (int i = 0; i < n; i++) da &= m.containsKey(adr + i);
                if (!da) {
                    antwort = new byte[]{(byte) (fc | 0x80), 2};
                } else {
                    ByteBuffer b = ByteBuffer.allocate(2 + 2 * n).put((byte) fc).put((byte) (2 * n));
                    for (int i = 0; i < n; i++) b.putShort((short) (int) m.get(adr + i));
                    antwort = b.array();
                }
            } else if (fc == 6) {
                holding.put(adr, p.getShort() & 0xffff);
                log.add("schreibe " + adr + "=" + holding.get(adr));
                nachSchreiben.run();
                antwort = pdu;
            } else if (fc == 16) {
                int n = p.getShort() & 0xffff;
                p.get();
                for (int i = 0; i < n; i++) holding.put(adr + i, p.getShort() & 0xffff);
                log.add("schreibe16 " + adr + "=" + holding.get(adr));
                nachSchreiben.run();
                antwort = ByteBuffer.allocate(5).put((byte) 16).putShort((short) adr).putShort((short) n).array();
            } else {
                antwort = new byte[]{(byte) (fc | 0x80), 1};
            }
            out.write(ByteBuffer.allocate(7 + antwort.length).putShort((short) tid).putShort((short) 0)
                    .putShort((short) (1 + antwort.length)).put((byte) unit).put(antwort).array());
            out.flush();
        }

        TuyaLan.Verbinder verbinder() {
            return (ip, port) -> {
                Socket s = new Socket("127.0.0.1", server.getLocalPort());
                s.setSoTimeout(4000);
                return new TuyaLan.Kanal() {
                    @Override public InputStream ein() { try { return s.getInputStream(); } catch (IOException e) { throw new IllegalStateException(e); } }
                    @Override public OutputStream aus() { try { return s.getOutputStream(); } catch (IOException e) { throw new IllegalStateException(e); } }
                    @Override public void close() { try { s.close(); } catch (IOException ignored) { } }
                };
            };
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }

    /** KEBA P40 wie im Datenblatt: 5004 mA (0 = gesperrt), Zustand 1000, Kabel 1004, Leistung 1020 mW, Failsafe 1602. */
    static ModbusAttrappe kebaP40(long failsafe) throws IOException {
        ModbusAttrappe m = new ModbusAttrappe(255);
        m.u32(m.holding, 1000, 2);
        m.u32(m.holding, 1004, 7);
        m.u32(m.holding, 1020, 0);
        m.holding.put(5004, 0);
        m.u32(m.holding, 1602, failsafe);
        m.nachSchreiben = () -> {
            int ma = m.holding.get(5004);
            m.u32(m.holding, 1000, ma > 0 ? 3 : 2);
            m.u32(m.holding, 1020, ma > 0 ? (long) ma * 230 : 0);   // mA × V = mW
        };
        return m;
    }

    static ModbusAttrappe heidelberg() throws IOException {
        ModbusAttrappe m = new ModbusAttrappe(1);
        m.input.put(5, 5);
        m.input.put(14, 0);
        m.holding.put(261, 0);
        m.holding.put(257, 0);
        m.nachSchreiben = () -> {
            int dA = m.holding.get(261);
            m.input.put(5, dA > 0 ? 7 : 5);
            m.input.put(14, dA > 0 ? dA * 23 : 0);
        };
        return m;
    }

    WallboxDienst dienst(TuyaLan.Verbinder v, KebaUdpAdapter.Udp udp) throws Exception {
        JSONObject t = tabelle();
        return new WallboxDienst(new File(tmp.getRoot(), "wb.json"), new File(tmp.getRoot(), "p.jsonl"), WallboxTest.GEHEIM, null,
                WallboxTest.DNS, () -> "steht", jetzt::get, ms -> { }, v, () -> null).mitDirekt(() -> t, udp);
    }

    static JSONObject mbGeraet(String modell) throws Exception {
        return new JSONObject().put("schluessel", "wb").put("titel", "Wallbox").put("adapter", "modbus")
                .put("adresse", "192.168.1.50").put("modell", modell);
    }

    @Test
    public void tabelle_gueltig() throws Exception {
        JSONArray m = tabelle().getJSONArray("modelle");
        assertTrue(m.length() >= 2);
        for (int i = 0; i < m.length(); i++) ModbusAdapter.pruefeModell(m.getJSONObject(i));
    }

    @Test
    public void kebaP40_modbus_einAusStrom() throws Exception {
        try (ModbusAttrappe m = kebaP40(0)) {
            WallboxDienst d = dienst(m.verbinder(), null);
            assertEquals(200, d.speichereEinstellungen(WallboxDirektTest.konfig(mbGeraet("keba-p40-modbus"))).status);
            JSONObject lp = d.status().json.getJSONArray("ladepunkte").getJSONObject(0);
            assertTrue(lp.toString(), d.status().json.getBoolean("erreichbar"));
            assertEquals("aus", lp.getString("modus"));
            assertTrue(lp.getBoolean("verbunden"));
            assertFalse(lp.getBoolean("laedt"));
            WallboxTuyaTest.gibFrei(d, "direkt:wb");
            assertEquals(400, WallboxTuyaTest.status(d, "direkt:wb", "strom", 10));   // erst einschalten
            JSONObject r = WallboxTuyaTest.fuehreAus(d, "direkt:wb", "modus", "sofort");
            assertTrue(r.getBoolean("uebernommen"));
            assertEquals(6000, (int) m.holding.get(5004));                            // vorsichtig: Mindeststrom
            assertTrue(r.getJSONObject("ladepunkt").getBoolean("laedt"));
            assertTrue(WallboxTuyaTest.fuehreAus(d, "direkt:wb", "strom", 16).getBoolean("uebernommen"));
            assertEquals(16000, (int) m.holding.get(5004));
            assertEquals(3680, d.status().json.getJSONArray("ladepunkte").getJSONObject(0).getLong("leistungW"));
            assertTrue(WallboxTuyaTest.fuehreAus(d, "direkt:wb", "modus", "aus").getBoolean("uebernommen"));
            assertEquals(0, (int) m.holding.get(5004));
            for (String l : m.log) if (l.startsWith("fc")) assertTrue(l, l.endsWith("unit255"));
            assertTrue(m.log.contains("schreibe 5004=16000"));   // FC 6
        }
    }

    @Test
    public void heidelberg_inputRegister_fc16_zehntelAmpere() throws Exception {
        try (ModbusAttrappe m = heidelberg()) {
            WallboxDienst d = dienst(m.verbinder(), null);
            d.speichereEinstellungen(WallboxDirektTest.konfig(mbGeraet("heidelberg-energy-control")));
            JSONObject lp = d.status().json.getJSONArray("ladepunkte").getJSONObject(0);
            assertTrue(lp.getBoolean("verbunden"));
            WallboxTuyaTest.gibFrei(d, "direkt:wb");
            assertTrue(WallboxTuyaTest.fuehreAus(d, "direkt:wb", "modus", "sofort").getBoolean("uebernommen"));
            assertEquals(60, (int) m.holding.get(261));
            assertTrue(WallboxTuyaTest.fuehreAus(d, "direkt:wb", "strom", 10).getBoolean("uebernommen"));
            assertEquals(100, (int) m.holding.get(261));
            assertTrue(m.log.contains("schreibe16 261=100"));
            assertEquals(400, WallboxTuyaTest.status(d, "direkt:wb", "strom", 20));   // Tabelle max 16 A
        }
    }

    @Test
    public void failsafeAktiv_nurAnzeige() throws Exception {
        try (ModbusAttrappe m = kebaP40(60)) {
            WallboxDienst d = dienst(m.verbinder(), null);
            d.speichereEinstellungen(WallboxDirektTest.konfig(mbGeraet("keba-p40-modbus")));
            JSONObject lp = d.status().json.getJSONArray("ladepunkte").getJSONObject(0);
            assertEquals("modbus_failsafe", lp.getString("hinweis"));
            assertEquals(0, lp.getJSONObject("faehigkeiten").getJSONArray("modus").length());
            WallboxTuyaTest.gibFrei(d, "direkt:wb");
            assertEquals(400, WallboxTuyaTest.status(d, "direkt:wb", "modus", "sofort"));
            assertFalse(String.join(",", m.log).contains("schreibe"));
        }
    }

    @Test
    public void modbus_falscheEinheit_unbekanntesModell_oeffentlicheAdresse() throws Exception {
        try (ModbusAttrappe m = kebaP40(0)) {
            WallboxDienst d = dienst(m.verbinder(), null);
            d.speichereEinstellungen(WallboxDirektTest.konfig(mbGeraet("keba-p40-modbus").put("einheit", 7)));
            assertEquals("nicht_erreichbar", d.status().json.getString("fehler"));    // Gerät schweigt bei falscher Unit
            d.speichereEinstellungen(WallboxDirektTest.konfig(mbGeraet("gibts-nicht")));
            assertEquals("modbus_modell_unbekannt", d.status().json.getString("fehler"));
            assertEquals("adresse_oeffentlich", d.speichereEinstellungen(WallboxDirektTest.konfig(mbGeraet("keba-p40-modbus")
                    .put("adresse", "8.8.8.8"))).json.getString("grund"));
            assertEquals(400, d.speichereEinstellungen(WallboxDirektTest.konfig(mbGeraet("keba-p40-modbus").put("modell", JSONObject.NULL))).status);
        }
    }

    /** Gegenprobe gegen pymodbus (tools/wallbox/modbus_orakel.py), nur mit WALLBOX_MODBUS_ORAKEL=<port>. */
    @Test
    public void orakel_pymodbus_kebaP40() throws Exception {
        String port = System.getenv("WALLBOX_MODBUS_ORAKEL");
        org.junit.Assume.assumeTrue(port != null && !port.isEmpty());
        TuyaLan.Verbinder zumOrakel = (ip, p) -> {
            Socket s = new Socket("127.0.0.1", Integer.parseInt(port));
            s.setSoTimeout(4000);
            return new TuyaLan.Kanal() {
                @Override public InputStream ein() { try { return s.getInputStream(); } catch (IOException e) { throw new IllegalStateException(e); } }
                @Override public OutputStream aus() { try { return s.getOutputStream(); } catch (IOException e) { throw new IllegalStateException(e); } }
                @Override public void close() { try { s.close(); } catch (IOException ignored) { } }
            };
        };
        WallboxDienst d = dienst(zumOrakel, null);
        d.speichereEinstellungen(WallboxDirektTest.konfig(mbGeraet("keba-p40-modbus")));
        JSONObject s = d.status().json;
        assertTrue(s.toString(), s.getBoolean("erreichbar"));
        JSONObject lp = s.getJSONArray("ladepunkte").getJSONObject(0);
        assertEquals("aus", lp.getString("modus"));
        assertTrue(lp.getBoolean("verbunden"));
        WallboxTuyaTest.gibFrei(d, "direkt:wb");
        JSONObject r1 = WallboxTuyaTest.fuehreAus(d, "direkt:wb", "modus", "sofort");
        JSONObject r2 = WallboxTuyaTest.fuehreAus(d, "direkt:wb", "strom", 16);
        JSONObject r3 = WallboxTuyaTest.fuehreAus(d, "direkt:wb", "modus", "aus");
        assertTrue(r1.getBoolean("uebernommen") && r2.getBoolean("uebernommen") && r3.getBoolean("uebernommen"));
        assertTrue(r2.getJSONObject("ladepunkt").getBoolean("laedt"));
        assertEquals(3680, r2.getJSONObject("ladepunkt").getLong("leistungW"));
        String ziel = System.getenv("WALLBOX_MODBUS_BERICHT");
        if (ziel != null && !ziel.isEmpty()) {
            Files.write(Paths.get(ziel), new JSONObject().put("vorher", lp).put("sofort", r1).put("strom16", r2).put("aus", r3)
                    .toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }

    // ------------------------------------------------------------------ KEBA UDP (echtes UDP über Loopback)

    /** KEBA-UDP-Attrappe: antwortet – wie die echte Box – immer an einen festen Port (hier: lokalPort). */
    static final class KebaAttrappe implements AutoCloseable {
        final DatagramSocket s;
        final int antwortPort;
        volatile int enaUser = 0, currUser = 16000;
        final List<String> befehle = Collections.synchronizedList(new ArrayList<>());

        KebaAttrappe(int antwortPort) throws Exception {
            this.antwortPort = antwortPort;
            s = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
            Thread t = new Thread(() -> {
                byte[] b = new byte[512];
                while (!s.isClosed()) {
                    try {
                        DatagramPacket p = new DatagramPacket(b, b.length);
                        s.receive(p);
                        String m = new String(p.getData(), 0, p.getLength(), StandardCharsets.US_ASCII).trim();
                        befehle.add(m);
                        String a;
                        boolean laedt = enaUser == 1;
                        if (m.equals("report 2")) {
                            a = new JSONObject().put("ID", "2").put("State", laedt ? 3 : 2).put("Plug", 7).put("Enable sys", enaUser)
                                    .put("Enable user", enaUser).put("Curr user", currUser).put("Curr HW", 16000).put("AuthON", 0).toString();
                        } else if (m.equals("report 3")) {
                            a = new JSONObject().put("ID", "3").put("P", laedt ? currUser * 230L : 0).toString();
                        } else if (m.startsWith("ena ")) {
                            enaUser = Integer.parseInt(m.substring(4));
                            a = KebaUdpAdapter.OK;
                        } else if (m.startsWith("curr ")) {
                            currUser = Integer.parseInt(m.substring(5));
                            a = KebaUdpAdapter.OK;
                        } else {
                            continue;
                        }
                        byte[] r = a.getBytes(StandardCharsets.UTF_8);
                        s.send(new DatagramPacket(r, r.length, p.getAddress(), antwortPort));
                    } catch (Exception ignored) { }
                }
            });
            t.setDaemon(true);
            t.start();
        }

        @Override
        public void close() {
            s.close();
        }
    }

    static int freierUdpPort() throws Exception {
        try (DatagramSocket d = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            return d.getLocalPort();
        }
    }

    @Test
    public void kebaUdp_einAusStrom_echtesUdp() throws Exception {
        int lokal = freierUdpPort();
        try (KebaAttrappe k = new KebaAttrappe(lokal)) {
            KebaUdpAdapter.Udp udp = new KebaUdpAdapter.Udp(lokal, k.s.getLocalPort(), ip -> "127.0.0.1");
            WallboxDienst d = dienst(null, udp);
            JSONObject g = new JSONObject().put("schluessel", "keba").put("titel", "KEBA").put("adapter", "keba").put("adresse", "192.168.1.60");
            assertEquals(200, d.speichereEinstellungen(WallboxDirektTest.konfig(g)).status);
            JSONObject lp = d.status().json.getJSONArray("ladepunkte").getJSONObject(0);
            assertTrue(d.status().json.toString(), d.status().json.getBoolean("erreichbar"));
            assertEquals("aus", lp.getString("modus"));
            assertEquals(16, lp.getJSONObject("faehigkeiten").getJSONObject("strom").getInt("max"));   // aus "Curr HW"
            WallboxTuyaTest.gibFrei(d, "direkt:keba");
            assertTrue(WallboxTuyaTest.fuehreAus(d, "direkt:keba", "strom", 10).getBoolean("uebernommen"));
            JSONObject r = WallboxTuyaTest.fuehreAus(d, "direkt:keba", "modus", "sofort");
            assertTrue(r.getBoolean("uebernommen"));
            assertTrue(r.getJSONObject("ladepunkt").getBoolean("laedt"));
            assertEquals(2300, r.getJSONObject("ladepunkt").getLong("leistungW"));
            assertTrue(k.befehle.contains("curr 10000"));
            assertTrue(k.befehle.contains("ena 1"));
            assertEquals(400, WallboxTuyaTest.status(d, "direkt:keba", "strom", 20));
        }
    }

    @Test
    public void kebaUdp_portBelegt_ehrlicheMeldung() throws Exception {
        try (DatagramSocket belegt = new DatagramSocket(0, InetAddress.getByName("0.0.0.0"))) {
            belegt.setReuseAddress(false);
            KebaUdpAdapter.Udp udp = new KebaUdpAdapter.Udp(belegt.getLocalPort(), 9, ip -> "127.0.0.1");
            try {
                new KebaUdpAdapter("192.168.1.60", udp).lies();
            } catch (WallboxFehler f) {
                assertTrue(f.code, f.code.equals("keba_port_belegt") || f.code.equals("nicht_erreichbar"));
                return;
            }
            throw new AssertionError("Fehler erwartet");
        }
    }
}
