package at.greenchili.surfmate.wallbox;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;

/**
 * Adressprüfung für evcc und Home Assistant (WALLBOX_VERTRAG §2).
 * <ul>
 *   <li>privat (immer erlaubt): RFC 1918, 100.64.0.0/10 (Tailscale/CGNAT = eigener VPN), IPv6 ULA fc00::/7</li>
 *   <li>öffentlich: nur https und nur mit {@code oeffentlichErlaubt}</li>
 *   <li>gesperrt: Loopback, Link-Local, any, Multicast, 0/8, Broadcast und unsere eigenen Server</li>
 * </ul>
 * Jede aufgelöste Adresse muss passen (ein Name mit einer öffentlichen und einer privaten Adresse zählt als öffentlich).
 */
public final class WallboxAdresse {

    /** DNS-Auflösung, in Tests ersetzbar. */
    public interface Aufloeser {
        InetAddress[] alle(String host) throws Exception;
    }

    public static final Aufloeser DNS = InetAddress::getAllByName;

    /** Unsere eigenen Server: dort landen nie Wallbox-Zugangsdaten (Vertrag §1.4). */
    static final String[] EIGENE_SERVER = {"serveone.at"};

    enum Art { PRIVAT, OEFFENTLICH, GESPERRT }

    private WallboxAdresse() {}

    /**
     * Prüft die URL und gibt die Basis ohne abschließenden Schrägstrich zurück (Schema, Host, Port, Pfad).
     * @throws WallboxFehler {@code adresse_ungueltig} oder {@code adresse_gesperrt}
     */
    public static String pruefe(String url, boolean oeffentlichErlaubt, Aufloeser aufloeser) throws WallboxFehler {
        if (url == null || url.trim().isEmpty()) throw new WallboxFehler("adresse_ungueltig");
        URI u;
        try {
            u = new URI(url.trim());
        } catch (Exception e) {
            throw new WallboxFehler("adresse_ungueltig");
        }
        String schema = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (!schema.equals("http") && !schema.equals("https")) throw new WallboxFehler("adresse_ungueltig");
        if (u.getRawUserInfo() != null || u.getRawQuery() != null || u.getRawFragment() != null) {
            throw new WallboxFehler("adresse_ungueltig");
        }
        String host = u.getHost();
        if (host == null || host.isEmpty()) throw new WallboxFehler("adresse_ungueltig");
        int port = u.getPort();
        if (port != -1 && (port < 1 || port > 65535)) throw new WallboxFehler("adresse_ungueltig");
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        for (String eigen : EIGENE_SERVER) {
            if (h.equals(eigen) || h.endsWith("." + eigen)) throw new WallboxFehler("adresse_gesperrt");
        }
        InetAddress[] alle;
        try {
            alle = aufloeser.alle(h);
        } catch (Exception e) {
            throw new WallboxFehler("nicht_erreichbar", 502);
        }
        if (alle == null || alle.length == 0) throw new WallboxFehler("nicht_erreichbar", 502);
        boolean oeffentlich = false;
        for (InetAddress a : alle) {
            Art art = art(a);
            if (art == Art.GESPERRT) throw new WallboxFehler("adresse_gesperrt");
            if (art == Art.OEFFENTLICH) oeffentlich = true;
        }
        if (oeffentlich && !(oeffentlichErlaubt && schema.equals("https"))) {
            throw new WallboxFehler("adresse_oeffentlich");
        }
        String pfad = u.getRawPath() == null ? "" : u.getRawPath();
        while (pfad.endsWith("/")) pfad = pfad.substring(0, pfad.length() - 1);
        if (pfad.contains("..")) throw new WallboxFehler("adresse_ungueltig");
        String hostTeil = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        return schema + "://" + hostTeil + (port == -1 ? "" : ":" + port) + pfad;
    }

    /**
     * Tuya LAN (Vertrag §2/§4b): Host oder IP muss <b>privat</b> sein (Heimnetz bzw. eigener VPN), nie öffentlich, nie Loopback.
     * @return die aufgelöste IP (wird für die Verbindung festgehalten, kein zweites Auflösen)
     */
    public static String pruefeLanHost(String host, Aufloeser aufloeser) throws WallboxFehler {
        if (host == null || host.trim().isEmpty() || host.length() > 100 || !host.trim().matches("[A-Za-z0-9.:\\[\\]_-]+")) {
            throw new WallboxFehler("adresse_ungueltig");
        }
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        for (String eigen : EIGENE_SERVER) {
            if (h.equals(eigen) || h.endsWith("." + eigen)) throw new WallboxFehler("adresse_gesperrt");
        }
        InetAddress[] alle;
        try {
            alle = aufloeser.alle(h);
        } catch (Exception e) {
            throw new WallboxFehler("nicht_erreichbar", 502);
        }
        if (alle == null || alle.length == 0) throw new WallboxFehler("nicht_erreichbar", 502);
        for (InetAddress a : alle) {
            Art art = art(a);
            if (art == Art.GESPERRT) throw new WallboxFehler("adresse_gesperrt");
            if (art == Art.OEFFENTLICH) throw new WallboxFehler("adresse_oeffentlich");
        }
        return alle[0].getHostAddress();
    }

    /** Schema + Host + Port in Kleinschreibung, zum Vergleich „gleicher Host?“ (Vertrag §2, Geheimnis verwerfen). */
    public static String ziel(String url) {
        try {
            URI u = new URI(url == null ? "" : url.trim());
            String s = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            String h = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
            return s + "://" + h + ":" + u.getPort();
        } catch (Exception e) {
            return "";
        }
    }

    static Art art(InetAddress a) {
        if (a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isLinkLocalAddress() || a.isMulticastAddress()) {
            return Art.GESPERRT;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int o0 = b[0] & 0xff, o1 = b[1] & 0xff;
            if (o0 == 0 || (o0 == 255 && o1 == 255)) return Art.GESPERRT;
            if (o0 == 10) return Art.PRIVAT;
            if (o0 == 172 && o1 >= 16 && o1 <= 31) return Art.PRIVAT;
            if (o0 == 192 && o1 == 168) return Art.PRIVAT;
            if (o0 == 100 && o1 >= 64 && o1 <= 127) return Art.PRIVAT;
            return Art.OEFFENTLICH;
        }
        if (a instanceof Inet6Address) {
            if ((b[0] & 0xfe) == 0xfc) return Art.PRIVAT;
            return Art.OEFFENTLICH;
        }
        return Art.GESPERRT;
    }
}
