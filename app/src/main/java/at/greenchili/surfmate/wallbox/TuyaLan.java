package at.greenchili.surfmate.wallbox;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;

/**
 * Tuya-Gerät im Heimnetz (TCP 6668), Protokoll 3.3/3.4/3.5. Je Vorgang eine kurze Verbindung:
 * verbinden → (ab 3.4) Schlüssel aushandeln → abfragen bzw. setzen → schließen. Der local_key verlässt das Gerät nie
 * (er verschlüsselt nur). Zeitlimit je Schritt 4 s.
 */
public final class TuyaLan {

    public static final int PORT = 6668;
    static final int ZEIT_MS = 4000;

    /** TCP-Kanal, in Tests ersetzbar. */
    public interface Kanal extends AutoCloseable {
        InputStream ein();

        OutputStream aus();

        @Override
        void close();
    }

    public interface Verbinder {
        Kanal oeffne(String ip, int port) throws IOException;
    }

    public static final Verbinder TCP = (ip, port) -> {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(ip, port), ZEIT_MS);
        s.setSoTimeout(ZEIT_MS);
        s.setTcpNoDelay(true);
        return new Kanal() {
            @Override public InputStream ein() {
                try { return s.getInputStream(); } catch (IOException e) { throw new IllegalStateException(e); }
            }
            @Override public OutputStream aus() {
                try { return s.getOutputStream(); } catch (IOException e) { throw new IllegalStateException(e); }
            }
            @Override public void close() {
                try { s.close(); } catch (IOException ignored) { }
            }
        };
    };

    private final String ip;
    private final String geraeteId;
    private final byte[] localKey;
    private final String version;
    private final Verbinder verbinder;
    private final SecureRandom zufall = new SecureRandom();

    public TuyaLan(String ip, String geraeteId, byte[] localKey, String version, Verbinder verbinder) throws WallboxFehler {
        if (localKey == null || localKey.length != 16) throw new WallboxFehler("tuya_schluessel_fehlt", 400);
        if (!"3.3".equals(version) && !"3.4".equals(version) && !"3.5".equals(version)) throw new WallboxFehler("tuya_version", 400);
        this.ip = ip;
        this.geraeteId = geraeteId;
        this.localKey = localKey;
        this.version = version;
        this.verbinder = verbinder;
    }

    /** Alle DPs als JSON ({"1":true,"4":16,…}). */
    public JSONObject dps() throws WallboxFehler, JSONException {
        try (Sitzung s = new Sitzung()) {
            if ("3.3".equals(version)) {
                JSONObject j = new JSONObject().put("gwId", geraeteId).put("devId", geraeteId).put("uid", geraeteId).put("t", zeit());
                s.sende(TuyaProtokoll.DP_QUERY, j.toString().getBytes(StandardCharsets.UTF_8), false);
            } else {
                s.sende(TuyaProtokoll.DP_QUERY_NEW, "{}".getBytes(StandardCharsets.UTF_8), false);
            }
            for (int i = 0; i < 6; i++) {
                byte[] klar = s.lies(new int[]{TuyaProtokoll.DP_QUERY, TuyaProtokoll.DP_QUERY_NEW, TuyaProtokoll.STATUS});
                if (klar.length == 0) continue;
                String text = new String(klar, StandardCharsets.UTF_8);
                if (text.contains("data unvalid")) throw new WallboxFehler("tuya_variante", 502);
                JSONObject d = dpsAus(text);
                if (d != null) return d;
            }
            throw new WallboxFehler("tuya_antwort_ungueltig", 502);
        }
    }

    /** Setzt DPs; liefert, sobald das Gerät den Befehl bestätigt hat (Zurücklesen macht der Aufrufer). */
    public void setze(JSONObject dps) throws WallboxFehler, JSONException {
        try (Sitzung s = new Sitzung()) {
            if ("3.3".equals(version)) {
                JSONObject j = new JSONObject().put("devId", geraeteId).put("uid", geraeteId).put("t", zeit()).put("dps", dps);
                s.sende(TuyaProtokoll.CONTROL, j.toString().getBytes(StandardCharsets.UTF_8), true);
            } else {
                JSONObject j = new JSONObject().put("protocol", 5).put("t", Long.parseLong(zeit()))
                        .put("data", new JSONObject().put("dps", dps));
                s.sende(TuyaProtokoll.CONTROL_NEW, j.toString().getBytes(StandardCharsets.UTF_8), true);
            }
            s.lies(new int[]{TuyaProtokoll.CONTROL, TuyaProtokoll.CONTROL_NEW, TuyaProtokoll.STATUS});
        }
    }

    /** {"dps":{…}} oder {"data":{"dps":{…}}} → dps; sonst null. */
    static JSONObject dpsAus(String text) {
        try {
            JSONObject j = new JSONObject(text);
            JSONObject d = j.optJSONObject("dps");
            if (d == null && j.optJSONObject("data") != null) d = j.optJSONObject("data").optJSONObject("dps");
            return d;
        } catch (Exception e) {
            return null;
        }
    }

    private static String zeit() {
        return Long.toString(System.currentTimeMillis() / 1000);
    }

    /** Eine Verbindung inkl. Aushandlung; Sequenznummern ab 1. */
    private final class Sitzung implements AutoCloseable {
        private final Kanal kanal;
        private final DataInputStream ein;
        private byte[] schluessel = localKey;
        private int seq = 0;

        Sitzung() throws WallboxFehler {
            try {
                kanal = verbinder.oeffne(ip, PORT);
            } catch (IOException e) {
                throw new WallboxFehler("nicht_erreichbar", 502);
            }
            ein = new DataInputStream(kanal.ein());
            try {
                if (!"3.3".equals(version)) aushandeln();
            } catch (WallboxFehler f) {
                close();
                throw f;
            }
        }

        private void aushandeln() throws WallboxFehler {
            byte[] lokal = new byte[16];
            zufall.nextBytes(lokal);
            sende(TuyaProtokoll.SESS_START, lokal, false);
            byte[] antwort = lies(new int[]{TuyaProtokoll.SESS_ANTWORT});
            if (antwort.length < 48) throw new WallboxFehler("tuya_schluessel", 502);
            byte[] fern = Arrays.copyOf(antwort, 16);
            if (!MessageDigest.isEqual(Arrays.copyOfRange(antwort, 16, 48), TuyaProtokoll.hmac(localKey, lokal))) {
                throw new WallboxFehler("tuya_schluessel", 502);
            }
            sende(TuyaProtokoll.SESS_ENDE, TuyaProtokoll.hmac(localKey, fern), false);
            schluessel = TuyaProtokoll.sitzungsSchluessel(version, localKey, lokal, fern);
        }

        void sende(int cmd, byte[] klar, boolean mitKopf) throws WallboxFehler {
            byte[] rahmen;
            seq++;
            switch (version) {
                case "3.3": {
                    byte[] enc = TuyaProtokoll.ecb(Cipher.ENCRYPT_MODE, schluessel, klar, true);
                    rahmen = TuyaProtokoll.rahmen55aa(seq, cmd, mitKopf ? TuyaProtokoll.verbinde(TuyaProtokoll.versionsKopf(version), enc) : enc, null);
                    break;
                }
                case "3.4": {
                    byte[] inner = mitKopf ? TuyaProtokoll.verbinde(TuyaProtokoll.versionsKopf(version), klar) : klar;
                    rahmen = TuyaProtokoll.rahmen55aa(seq, cmd, TuyaProtokoll.ecb(Cipher.ENCRYPT_MODE, schluessel, inner, true), schluessel);
                    break;
                }
                default: {
                    byte[] inner = mitKopf ? TuyaProtokoll.verbinde(TuyaProtokoll.versionsKopf(version), klar) : klar;
                    byte[] iv = new byte[12];
                    zufall.nextBytes(iv);
                    rahmen = TuyaProtokoll.rahmen6699(seq, cmd, inner, schluessel, iv);
                }
            }
            try {
                kanal.aus().write(rahmen);
                kanal.aus().flush();
            } catch (IOException e) {
                throw new WallboxFehler("nicht_erreichbar", 502);
            }
        }

        /** Liest Rahmen, bis einer mit einem der Befehle kommt; Herzschläge u. Ä. werden übersprungen. */
        byte[] lies(int[] erwartet) throws WallboxFehler {
            for (int n = 0; n < 8; n++) {
                byte[] f = rahmen();
                TuyaProtokoll.Rahmen r;
                byte[] klar;
                switch (version) {
                    case "3.3":
                        r = TuyaProtokoll.lies55aa(f, null);
                        klar = TuyaProtokoll.ohneVersionsKopf(r.daten);
                        klar = klar.length == 0 ? klar : TuyaProtokoll.ecb(Cipher.DECRYPT_MODE, schluessel, klar, true);
                        break;
                    case "3.4":
                        r = TuyaProtokoll.lies55aa(f, schluessel);
                        klar = r.daten.length == 0 ? r.daten
                                : TuyaProtokoll.ohneVersionsKopf(TuyaProtokoll.ecb(Cipher.DECRYPT_MODE, schluessel, r.daten, true));
                        break;
                    default:
                        r = TuyaProtokoll.lies6699(f, schluessel);
                        klar = TuyaProtokoll.ohneVersionsKopf(r.daten);
                }
                if (r.retcode > 0 && r.retcode != 0xffffffffL && klar.length == 0) throw new WallboxFehler("tuya_abgelehnt", 502);
                for (int e : erwartet) if (r.cmd == e) return klar;
            }
            throw new WallboxFehler("tuya_antwort_ungueltig", 502);
        }

        private byte[] rahmen() throws WallboxFehler {
            try {
                int prefix = ein.readInt();
                if (prefix == TuyaProtokoll.PREFIX_55AA) {
                    byte[] kopf = new byte[12];
                    ein.readFully(kopf);
                    int len = ByteBuffer.wrap(kopf, 8, 4).getInt();
                    if (len < 8 || len > TuyaProtokoll.MAX_LAENGE) throw new WallboxFehler("tuya_antwort_ungueltig", 502);
                    byte[] rest = new byte[len];
                    ein.readFully(rest);
                    return ByteBuffer.allocate(16 + len).putInt(prefix).put(kopf).put(rest).array();
                }
                if (prefix == TuyaProtokoll.PREFIX_6699) {
                    byte[] kopf = new byte[14];
                    ein.readFully(kopf);
                    int len = ByteBuffer.wrap(kopf, 10, 4).getInt();
                    if (len < 28 || len > TuyaProtokoll.MAX_LAENGE) throw new WallboxFehler("tuya_antwort_ungueltig", 502);
                    byte[] rest = new byte[len + 4];
                    ein.readFully(rest);
                    return ByteBuffer.allocate(18 + len + 4).putInt(prefix).put(kopf).put(rest).array();
                }
                throw new WallboxFehler("tuya_antwort_ungueltig", 502);
            } catch (IOException e) {
                // Verbindung stand, aber keine (vollständige) Antwort: meist falscher local_key oder falsche Version
                throw new WallboxFehler("tuya_keine_antwort", 502);
            }
        }

        @Override
        public void close() {
            kanal.close();
        }
    }

    /** DP-Werte vergleichen (Zahl 16 == 16.0, Text, Bool). */
    static boolean gleich(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        return a != null && a.equals(b);
    }

}
