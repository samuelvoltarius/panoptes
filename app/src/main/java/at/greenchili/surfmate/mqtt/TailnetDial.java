package at.greenchili.surfmate.mqtt;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import javax.net.SocketFactory;

/**
 * TCP ins Tailnet über das Panoptes-eigene tailscaled, ohne Kernel-Tun und ohne SOCKS
 * (Deploy-Befund 21.10.: Panoptes-tailscaled läuft `--tun userspace-networking`; direkte Connects
 * auf 100.x liefen deshalb über die Tailscale-APP bzw. das LAN und fielen nach dem Netzwechsel aus).
 *
 * <p>Nutzt die LocalAPI wie {@code tailscale nc}: {@code POST /localapi/v0/dial} mit
 * {@code Upgrade: ts-dial}, {@code Dial-Host}, {@code Dial-Port}; nach {@code 101 Switching Protocols}
 * ist die Verbindung der rohe TCP-Strom zum Ziel. Regel B: nur ein LocalAPI-Aufruf an das laufende
 * tailscaled – kein Neustart, kein neuer Port, TailscaleLauncher unverändert.
 */
public final class TailnetDial {

    static final String LOCALAPI_HOST = "127.0.0.1";
    static volatile int localApiPort = 8632;

    private TailnetDial() {}

    /** Antwortet die LocalAPI überhaupt (kurzer TCP-Test, kein Request)? */
    public static boolean localApiErreichbar() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(LOCALAPI_HOST, localApiPort), 300);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static SocketFactory factory() { return new Factory(); }

    /** Request für den Dial (öffentlich für Tests). */
    static String anfrage(String host, int port) {
        return "POST /localapi/v0/dial HTTP/1.1\r\n"
                + "Host: local-tailscaled.sock\r\n"
                + "Upgrade: ts-dial\r\n"
                + "Connection: upgrade\r\n"
                + "Dial-Host: " + host + "\r\n"
                + "Dial-Port: " + port + "\r\n"
                + "Sec-Tailscale: localapi\r\n"
                + "Content-Length: 0\r\n"
                + "\r\n";
    }

    /** Socket, dessen connect() über die LocalAPI zum eigentlichen Ziel wählt. */
    static final class DialSocket extends Socket {
        @Override
        public void connect(SocketAddress endpoint, int timeout) throws IOException {
            if (!(endpoint instanceof InetSocketAddress)) throw new IOException("unsupported address");
            InetSocketAddress ziel = (InetSocketAddress) endpoint;
            String host = ziel.getHostString();
            int port = ziel.getPort();
            super.connect(new InetSocketAddress(LOCALAPI_HOST, localApiPort), timeout > 0 ? timeout : 10_000);
            int altTimeout = getSoTimeout();
            setSoTimeout(timeout > 0 ? timeout : 10_000);
            try {
                OutputStream out = super.getOutputStream();
                out.write(anfrage(host, port).getBytes(StandardCharsets.US_ASCII));
                out.flush();
                String kopf = leseKopf(super.getInputStream());
                String status = kopf.split("\r\n", 2)[0];
                if (!status.matches("HTTP/1\\.[01] 101.*")) {
                    close();
                    throw new IOException("Tailnet-Dial abgelehnt: " + status);
                }
            } catch (SocketTimeoutException e) {
                close();
                throw e;
            } finally {
                if (!isClosed()) setSoTimeout(altTimeout);
            }
        }

        @Override
        public void connect(SocketAddress endpoint) throws IOException {
            connect(endpoint, 0);
        }
    }

    /** Liest den HTTP-Antwortkopf Byte für Byte bis zur Leerzeile (nichts darüber hinaus). */
    static String leseKopf(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b, n = 0;
        while ((b = in.read()) >= 0) {
            sb.append((char) b);
            if (++n > 8192) throw new IOException("Antwortkopf zu lang");
            int l = sb.length();
            if (l >= 4 && sb.charAt(l - 4) == '\r' && sb.charAt(l - 3) == '\n'
                    && sb.charAt(l - 2) == '\r' && sb.charAt(l - 1) == '\n') {
                return sb.toString();
            }
        }
        throw new IOException("LocalAPI hat die Verbindung geschlossen");
    }

    static final class Factory extends SocketFactory {
        @Override public Socket createSocket() { return new DialSocket(); }

        @Override public Socket createSocket(String host, int port) throws IOException {
            Socket s = new DialSocket();
            s.connect(InetSocketAddress.createUnresolved(host, port), 10_000);
            return s;
        }

        @Override public Socket createSocket(String host, int port, java.net.InetAddress lh, int lp) throws IOException {
            return createSocket(host, port);
        }

        @Override public Socket createSocket(java.net.InetAddress host, int port) throws IOException {
            return createSocket(host.getHostAddress(), port);
        }

        @Override public Socket createSocket(java.net.InetAddress a, int port, java.net.InetAddress la, int lp)
                throws IOException {
            return createSocket(a.getHostAddress(), port);
        }
    }
}
