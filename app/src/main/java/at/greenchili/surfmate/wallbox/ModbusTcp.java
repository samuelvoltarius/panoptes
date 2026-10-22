package at.greenchili.surfmate.wallbox;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Minimaler Modbus-TCP-Client (eigene Umsetzung nach der Modbus-Spezifikation, modbus.org): FC 3/4 lesen, FC 6/16 schreiben.
 * Je Vorgang eine kurze Verbindung (Port 502, Zeitlimit 4 s) über einen {@link TuyaLan.Verbinder} (allgemeiner TCP-Kanal).
 */
public final class ModbusTcp {

    public static final int PORT = 502;

    private final String ip;
    private final int port;
    private final int einheit;
    private final TuyaLan.Verbinder verbinder;
    private int transaktion = 1;

    public ModbusTcp(String ip, int port, int einheit, TuyaLan.Verbinder verbinder) throws WallboxFehler {
        if (port < 1 || port > 65535 || einheit < 0 || einheit > 255) throw new WallboxFehler("einstellung_ungueltig");
        this.ip = ip;
        this.port = port;
        this.einheit = einheit;
        this.verbinder = verbinder;
    }

    /** FC 3 (Holding) oder 4 (Input): {@code anzahl} Register ab {@code adresse}, Rohbytes (2 je Register). */
    public byte[] lies(int fc, int adresse, int anzahl) throws WallboxFehler {
        if ((fc != 3 && fc != 4) || anzahl < 1 || anzahl > 125) throw new WallboxFehler("einstellung_ungueltig");
        byte[] pdu = ByteBuffer.allocate(5).put((byte) fc).putShort((short) adresse).putShort((short) anzahl).array();
        byte[] r = austausch(pdu);
        if (r.length < 2 || (r[1] & 0xff) != anzahl * 2 || r.length != 2 + anzahl * 2) throw new WallboxFehler("antwort_ungueltig", 502);
        byte[] d = new byte[anzahl * 2];
        System.arraycopy(r, 2, d, 0, d.length);
        return d;
    }

    /** FC 6 (ein Register) bzw. FC 16 (mehrere). */
    public void schreibe(int fc, int adresse, byte[] werte) throws WallboxFehler {
        byte[] pdu;
        if (fc == 6) {
            if (werte.length != 2) throw new WallboxFehler("einstellung_ungueltig");
            pdu = ByteBuffer.allocate(5).put((byte) 6).putShort((short) adresse).put(werte).array();
        } else if (fc == 16) {
            int n = werte.length / 2;
            if (n < 1 || n > 123 || werte.length % 2 != 0) throw new WallboxFehler("einstellung_ungueltig");
            pdu = ByteBuffer.allocate(6 + werte.length).put((byte) 16).putShort((short) adresse).putShort((short) n)
                    .put((byte) werte.length).put(werte).array();
        } else {
            throw new WallboxFehler("einstellung_ungueltig");
        }
        austausch(pdu);
    }

    private byte[] austausch(byte[] pdu) throws WallboxFehler {
        int tid = transaktion++ & 0xffff;
        byte[] anfrage = ByteBuffer.allocate(7 + pdu.length).putShort((short) tid).putShort((short) 0)
                .putShort((short) (1 + pdu.length)).put((byte) einheit).put(pdu).array();
        TuyaLan.Kanal k;
        try {
            k = verbinder.oeffne(ip, port);
        } catch (IOException e) {
            throw new WallboxFehler("nicht_erreichbar", 502);
        }
        try {
            k.aus().write(anfrage);
            k.aus().flush();
            DataInputStream in = new DataInputStream(k.ein());
            int rtid = in.readUnsignedShort();
            int proto = in.readUnsignedShort();
            int len = in.readUnsignedShort();
            if (rtid != tid || proto != 0 || len < 2 || len > 260) throw new WallboxFehler("antwort_ungueltig", 502);
            int unit = in.readUnsignedByte();
            byte[] r = new byte[len - 1];
            in.readFully(r);
            if (unit != einheit) throw new WallboxFehler("antwort_ungueltig", 502);
            if ((r[0] & 0x80) != 0) throw new WallboxFehler(r.length > 1 && r[1] == 2 ? "modbus_register" : "modbus_abgelehnt", 502);
            if (r[0] != pdu[0]) throw new WallboxFehler("antwort_ungueltig", 502);
            return r;
        } catch (IOException e) {
            throw new WallboxFehler("nicht_erreichbar", 502);
        } finally {
            k.close();
        }
    }
}
