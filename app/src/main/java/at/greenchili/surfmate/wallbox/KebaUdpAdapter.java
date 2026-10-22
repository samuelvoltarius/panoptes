package at.greenchili.surfmate.wallbox;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.function.UnaryOperator;

/**
 * KEBA KeContact P30 über die UDP-Schnittstelle (Port 7090; an der Box per DIP-Schalter bzw. Web-Menü freizugeben).
 * Eigene Umsetzung; Befehle und Felder nach dem evcc-Treiber {@code charger/keba-udp.go}, {@code keba/*.go} (MIT, evcc.io):
 * <pre>
 * "report 2" → {"ID":"2", "State": 3 = lädt, "Plug": ≥5 = Auto angesteckt, "Enable user", "Curr user" mA, "Curr HW" mA}
 * "report 3" → {"ID":"3", "P": mW}
 * "ena 0|1", "curr &lt;mA&gt;" → "TCH-OK :done"
 * </pre>
 * Die Box antwortet immer an Port 7090 des Absenders → wir binden lokal 7090 (nur ein Programm je Rechner; im selben WLAN
 * wie die Box, z. B. Auto zu Hause im Heim-WLAN; über einen VPN mit Adressumsetzung kommen die Antworten nicht zurück).
 */
public final class KebaUdpAdapter implements DirektAdapter {

    public static final int PORT = 7090;
    public static final String OK = "TCH-OK :done";
    static final int ZEIT_MS = 4000;
    private static final Object SPERRE = new Object();

    /** UDP-Zugang; Ports und IP-Abbildung nur für Tests veränderbar. */
    public static final class Udp {
        final int lokalPort;
        final int boxPort;
        final UnaryOperator<String> ip;

        public Udp(int lokalPort, int boxPort, UnaryOperator<String> ip) {
            this.lokalPort = lokalPort;
            this.boxPort = boxPort;
            this.ip = ip;
        }

        public static final Udp ECHT = new Udp(PORT, PORT, s -> s);

        /** Sendet und wartet auf die passende Antwort derselben IP. */
        String frage(String ziel, String nachricht, String erwartet) throws WallboxFehler {
            synchronized (SPERRE) {
                try (DatagramSocket s = new DatagramSocket(null)) {
                    s.setReuseAddress(false);   // belegt (z. B. evcc auf demselben Rechner) → ehrliche Meldung statt Port-Klau
                    try {
                        s.bind(new InetSocketAddress(lokalPort));
                    } catch (Exception e) {
                        throw new WallboxFehler("keba_port_belegt", 502);
                    }
                    s.setSoTimeout(ZEIT_MS);
                    InetAddress a = InetAddress.getByName(ip.apply(ziel));
                    byte[] b = nachricht.getBytes(StandardCharsets.US_ASCII);
                    s.send(new DatagramPacket(b, b.length, a, boxPort));
                    long ende = System.currentTimeMillis() + ZEIT_MS;
                    byte[] puffer = new byte[2048];
                    while (System.currentTimeMillis() < ende) {
                        DatagramPacket p = new DatagramPacket(puffer, puffer.length);
                        s.receive(p);
                        if (!p.getAddress().equals(a)) continue;
                        String t = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8).trim();
                        if (erwartet == null ? t.equals(OK) : istReport(t, erwartet)) return t;
                    }
                    throw new WallboxFehler("nicht_erreichbar", 502);
                } catch (WallboxFehler f) {
                    throw f;
                } catch (SocketTimeoutException e) {
                    throw new WallboxFehler("nicht_erreichbar", 502);
                } catch (Exception e) {
                    throw new WallboxFehler("nicht_erreichbar", 502);
                }
            }
        }
    }

    /** Antwort auf "report n": JSON mit "ID" = n (Text oder Zahl). */
    static boolean istReport(String t, String n) {
        if (!t.startsWith("{")) return false;
        try {
            return n.equals(String.valueOf(new JSONObject(t).opt("ID")));
        } catch (Exception e) {
            return false;
        }
    }

    private final String ip;
    private final Udp udp;

    public KebaUdpAdapter(String ip, Udp udp) {
        this.ip = ip;
        this.udp = udp;
    }

    private JSONObject report(int n) throws WallboxFehler {
        try {
            return new JSONObject(udp.frage(ip, "report " + n, String.valueOf(n)));
        } catch (WallboxFehler f) {
            throw f;
        } catch (Exception e) {
            throw new WallboxFehler("antwort_ungueltig", 502);
        }
    }

    @Override
    public JSONObject lies() throws WallboxFehler, JSONException {
        JSONObject r2 = report(2);
        JSONObject r3 = report(3);
        JSONObject o = DirektAdapter.leer();
        int state = r2.optInt("State", -1), plug = r2.optInt("Plug", -1);
        o.put("laedt", state == 3);
        o.put("verbunden", plug >= 5);
        if (r3.opt("P") instanceof Number) o.put("leistungW", EvccQuelle.zahl(Math.round(r3.getDouble("P") / 1000)));
        boolean an = r2.optInt("Enable user", 0) == 1;
        o.put("modus", an ? WallboxQuelle.MODUS_SOFORT : WallboxQuelle.MODUS_AUS);
        o.put("modusRoh", "State=" + state + ",Enable user=" + r2.optInt("Enable user", -1));
        int cu = r2.optInt("Curr user", 0);
        o.put("stromA", cu > 0 ? EvccQuelle.zahl(cu / 1000.0) : JSONObject.NULL);
        int hw = r2.optInt("Curr HW", 32000);
        int max = Math.max(6, Math.min(32, hw > 0 ? hw / 1000 : 32));
        o.put("faehigkeiten", new JSONObject().put("modus", new JSONArray().put(WallboxQuelle.MODUS_AUS).put(WallboxQuelle.MODUS_SOFORT))
                .put("ziel", false).put("plan", JSONObject.NULL).put("strom", new JSONObject().put("min", 6).put("max", max)));
        if (r2.optInt("AuthON", 0) == 1 && r2.optInt("Authreq", 0) == 1) o.put("hinweis", "keba_rfid");
        return o;
    }

    @Override
    public void setze(String aktion, Object wert) throws WallboxFehler, JSONException {
        String befehl;
        if ("modus".equals(aktion)) befehl = "ena " + (WallboxQuelle.MODUS_SOFORT.equals(wert) ? 1 : 0);
        else if ("strom".equals(aktion)) befehl = "curr " + EvccQuelle.ganzzahl(wert, 6, 32) * 1000;
        else throw new WallboxFehler("nicht_unterstuetzt", 400);
        udp.frage(ip, befehl, null);
    }
}
