package at.greenchili.surfmate.wallbox;

import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.crypto.Cipher;

/**
 * Tuya-Gerät als Attrappe (Geräteseite, 3.3/3.4/3.5) auf 127.0.0.1 für JVM-Tests. Spiegelt tools/wallbox/tuya_simulator.py,
 * dessen Verhalten gegen den tinytuya-Client (MIT) geprüft ist. DPs: 1 Schalter, 3 Status, 4 Strom A, 9 Leistung W.
 */
final class TuyaGeraetAttrappe implements AutoCloseable {

    static final String ID = "bf0123456789abcdefgh";
    static final String SCHLUESSEL = "0123456789abcdef";

    final String version;
    final JSONObject dps = new JSONObject();
    final List<Integer> befehle = Collections.synchronizedList(new ArrayList<>());
    private final ServerSocket server;
    private final SecureRandom zufall = new SecureRandom();
    volatile byte[] falscherSchluessel;

    /** DP-Belegung: einfach (1/3/4/9) oder wie Tuya-Standardladegeräte qccdz (18 Schalter, 3 work_state, 4 Strom, 9 Leistung). */
    final String dpSchalter;
    final String statusAn;
    final String statusAus;

    TuyaGeraetAttrappe(String version) throws Exception {
        this(version, false);
    }

    TuyaGeraetAttrappe(String version, boolean standard) throws Exception {
        this.version = version;
        dpSchalter = standard ? "18" : "1";
        statusAn = standard ? "charger_charging" : "charging";
        statusAus = standard ? "charger_insert" : "connected";
        dps.put(dpSchalter, false).put("3", statusAus).put("4", 16).put("9", 0);
        server = new ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket s = server.accept()) {
                    s.setSoTimeout(4000);
                    sitzung(s.getInputStream(), s.getOutputStream());
                } catch (Exception ignored) {
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    /** Verbinder: jede (private) IP landet bei dieser Attrappe. */
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

    private void sitzung(InputStream inRoh, OutputStream out) throws Exception {
        DataInputStream in = new DataInputStream(inRoh);
        byte[] lk = falscherSchluessel != null ? falscherSchluessel : SCHLUESSEL.getBytes(StandardCharsets.UTF_8);
        byte[] key = lk;
        byte[] lokal = null, fern = null;
        int seq = 100;
        while (true) {
            byte[] f = lies(in);
            if (f == null) return;
            TuyaProtokoll.Rahmen r;
            byte[] klar;
            if (version.equals("3.5")) {
                r = TuyaProtokoll.lies6699(f, key);
                klar = TuyaProtokoll.ohneVersionsKopf(r.daten);
            } else if (version.equals("3.4")) {
                r = TuyaProtokoll.lies55aa(f, key);
                klar = TuyaProtokoll.ohneVersionsKopf(TuyaProtokoll.ecb(Cipher.DECRYPT_MODE, key, r.daten, true));
            } else {
                r = TuyaProtokoll.lies55aa(f, null);
                klar = TuyaProtokoll.ecb(Cipher.DECRYPT_MODE, key, TuyaProtokoll.ohneVersionsKopf(r.daten), true);
            }
            befehle.add(r.cmd);
            byte[] antwort = null;
            int cmd = r.cmd;
            switch (r.cmd) {
                case TuyaProtokoll.SESS_START:
                    lokal = Arrays.copyOf(klar, 16);
                    fern = new byte[16];
                    zufall.nextBytes(fern);
                    antwort = TuyaProtokoll.verbinde(fern, TuyaProtokoll.hmac(lk, lokal));
                    cmd = TuyaProtokoll.SESS_ANTWORT;
                    break;
                case TuyaProtokoll.SESS_ENDE:
                    if (!Arrays.equals(Arrays.copyOf(klar, 32), TuyaProtokoll.hmac(lk, fern))) return;
                    key = TuyaProtokoll.sitzungsSchluessel(version, lk, lokal, fern);
                    continue;
                case TuyaProtokoll.DP_QUERY:
                case TuyaProtokoll.DP_QUERY_NEW:
                    synchronized (dps) {
                        antwort = new JSONObject().put("devId", ID).put("dps", new JSONObject(dps.toString())).toString()
                                .getBytes(StandardCharsets.UTF_8);
                    }
                    break;
                case TuyaProtokoll.CONTROL:
                case TuyaProtokoll.CONTROL_NEW: {
                    JSONObject j = new JSONObject(new String(klar, StandardCharsets.UTF_8));
                    JSONObject neu = j.optJSONObject("dps") != null ? j.optJSONObject("dps") : j.getJSONObject("data").getJSONObject("dps");
                    synchronized (dps) {
                        for (java.util.Iterator<String> it = neu.keys(); it.hasNext(); ) {
                            String k = it.next();
                            if (k.equals(dpSchalter) || k.equals("4")) dps.put(k, neu.get(k));
                        }
                        boolean an = dps.getBoolean(dpSchalter);
                        dps.put("3", an ? statusAn : statusAus).put("9", an ? dps.getInt("4") * 230 : 0);
                    }
                    sende(out, ++seq, r.cmd, new byte[0], key);
                    continue;
                }
                default:
                    continue;
            }
            sende(out, ++seq, cmd, antwort, key);
        }
    }

    private void sende(OutputStream out, int seq, int cmd, byte[] klar, byte[] key) throws Exception {
        byte[] rc = new byte[4];
        byte[] f;
        if (version.equals("3.5")) {
            byte[] iv = new byte[12];
            zufall.nextBytes(iv);
            f = TuyaProtokoll.rahmen6699(seq, cmd, TuyaProtokoll.verbinde(rc, klar), key, iv);
        } else if (version.equals("3.4")) {
            byte[] enc = klar.length == 0 ? klar : TuyaProtokoll.ecb(Cipher.ENCRYPT_MODE, key, klar, true);
            f = TuyaProtokoll.rahmen55aa(seq, cmd, TuyaProtokoll.verbinde(rc, enc), key);
        } else {
            byte[] enc = klar.length == 0 ? klar : TuyaProtokoll.ecb(Cipher.ENCRYPT_MODE, key, klar, true);
            f = TuyaProtokoll.rahmen55aa(seq, cmd, TuyaProtokoll.verbinde(rc, enc), null);
        }
        out.write(f);
        out.flush();
    }

    private static byte[] lies(DataInputStream in) throws IOException {
        int p;
        try {
            p = in.readInt();
        } catch (IOException e) {
            return null;
        }
        if (p == TuyaProtokoll.PREFIX_55AA) {
            byte[] k = new byte[12];
            in.readFully(k);
            int len = ByteBuffer.wrap(k, 8, 4).getInt();
            byte[] r = new byte[len];
            in.readFully(r);
            return ByteBuffer.allocate(16 + len).putInt(p).put(k).put(r).array();
        }
        byte[] k = new byte[14];
        in.readFully(k);
        int len = ByteBuffer.wrap(k, 10, 4).getInt();
        byte[] r = new byte[len + 4];
        in.readFully(r);
        return ByteBuffer.allocate(18 + len + 4).putInt(p).put(k).put(r).array();
    }

    @Override
    public void close() throws IOException {
        server.close();
    }
}
