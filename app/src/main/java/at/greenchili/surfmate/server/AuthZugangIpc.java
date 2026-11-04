package at.greenchili.surfmate.server;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 0.1.38.8 Zugangsdaten Teil 2: der Signaturschlüssel der Anmeldung ({@code auth.deviceSecret}) über den Kamera-Daemon
 * statt aus der 0666-Config. Die Config muss 0666 bleiben (App und SentryDaemon, uid 1000, lesen sie direkt), deshalb
 * helfen Datei-Rechte nicht. Stattdessen fragen App und SentryDaemon den Daemon über die IPC (127.0.0.1:19977,
 * Befehl {@value #BEFEHL}), und der Daemon gibt den Schlüssel nur an die richtige Linux-UID heraus (Gegen-Socket in
 * {@code /proc/net/tcp}, wie {@link LauncherAufrufer}).
 *
 * <ul>
 *   <li>Erlaubt: uid 1000 (SentryDaemon/AccSentryDaemon) und die uid des Pakets {@value #APP_PAKET}.</li>
 *   <li>Nie uid 2000: als shell laufen auch beide tailscaled; eine Tailnet-Verbindung, die tailscaled auf 127.0.0.1
 *       weiterreicht, kommt mit uid 2000 an. Die shell-Daemons entschlüsseln {@code enc_deviceSecret} selbst.</li>
 *   <li>Fail-closed: unbekannte uid, unlesbare Tabelle, App-uid nicht ermittelbar → Absage ohne Schlüssel.</li>
 * </ul>
 *
 * <p>In 0.1.38.8 steht der Klartext noch in der Config (Rückfall auf 0.1.38.6) und bleibt maßgeblich; die App prüft
 * beim Start nur einmal, dass der IPC-Weg denselben Schlüssel liefert ({@code AuthManager}, Log ohne Wert). Erst die
 * Version, die den Klartext löscht, ist auf diesen Weg angewiesen.
 */
public final class AuthZugangIpc {

    public static final String BEFEHL = "AUTH_ZUGANG";
    static final int SYSTEM_UID = 1000;
    static final String APP_PAKET = "at.greenchili.surfmate";
    static final int IPC_PORT = 19977;

    /** Gerät (im Test nachgebaut). */
    interface Quelle {
        /** UID des Gegen-Sockets oder −1. */
        int uid(int clientPort, int serverPort);
        /** UID des Pakets {@value #APP_PAKET} oder −1. */
        int appUid();
        /** {deviceId, deviceSecret} des Daemons oder null. */
        String[] zugang();
    }

    static volatile Quelle quelle = new Geraet();

    private AuthZugangIpc() {}

    /** Rein: darf diese UID den Schlüssel bekommen? */
    static boolean erlaubt(int uid, int appUid) {
        if (uid == SYSTEM_UID) return true;
        return appUid >= 10_000 && uid == appUid;
    }

    /** Antwort des Daemons auf {@value #BEFEHL}; nie ein Schlüssel an eine nicht erlaubte UID. */
    public static JSONObject antwort(int clientPort, int serverPort) {
        return antwort(clientPort, serverPort, quelle);
    }

    static JSONObject antwort(int clientPort, int serverPort, Quelle q) {
        JSONObject r = new JSONObject();
        try {
            int uid = q.uid(clientPort, serverPort);
            if (uid < 0 || !erlaubt(uid, uid == SYSTEM_UID ? -1 : q.appUid())) {
                r.put("success", false);
                r.put("error", "nicht_erlaubt");
                return r;
            }
            String[] z = q.zugang();
            if (z == null || z[1] == null || z[1].isEmpty()) {
                r.put("success", false);
                r.put("error", "kein_zugang");
                return r;
            }
            r.put("success", true);
            r.put("deviceId", z[0] == null ? "" : z[0]);
            r.put("deviceSecret", z[1]);
        } catch (Exception e) {
            try {
                r.put("success", false);
                r.put("error", "fehler");
            } catch (Exception ignored) { }
        }
        return r;
    }

    // ------------------------------------------------------------------ Client (App, SentryDaemon)

    private static final ExecutorService ARBEITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AuthZugangIpc");
        t.setDaemon(true);
        return t;
    });

    /**
     * Schlüssel vom Daemon holen. Läuft auf einem eigenen Faden (Android verbietet Sockets auf dem UI-Faden, auch
     * zu 127.0.0.1) und wartet höchstens {@code wartenMs}. null, wenn der Daemon nicht antwortet oder absagt.
     */
    public static String holen(long wartenMs) {
        Future<String> f = ARBEITER.submit(() -> holenDirekt(IPC_PORT, (int) Math.max(200, wartenMs)));
        try {
            return f.get(wartenMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            f.cancel(true);
            return null;
        }
    }

    static String holenDirekt(int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), timeoutMs);
            s.setSoTimeout(timeoutMs);
            PrintWriter out = new PrintWriter(new java.io.OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out.println(new JSONObject().put("command", BEFEHL).toString());
            String zeile = in.readLine();
            return ausAntwort(zeile);
        } catch (Exception e) {
            return null;
        }
    }

    /** Rein: Schlüssel aus der Antwortzeile oder null. */
    static String ausAntwort(String zeile) {
        if (zeile == null || zeile.trim().isEmpty()) return null;
        try {
            JSONObject r = new JSONObject(zeile);
            if (!r.optBoolean("success", false)) return null;
            String g = r.optString("deviceSecret", "");
            return g.isEmpty() ? null : g;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ Gerät (Daemon)

    private static final Pattern UID = Pattern.compile("uid:(\\d{1,9})");

    /** Rein: uid genau des Pakets aus {@code cmd package list packages -U} (der Filter trifft auch Teilnamen). */
    static int appUidAusListe(String ausgabe) {
        if (ausgabe == null) return -1;
        for (String zeile : ausgabe.split("\n")) {
            String t = zeile.trim();
            String kopf = "package:" + APP_PAKET;
            if (!t.startsWith(kopf)) continue;
            String rest = t.substring(kopf.length());
            if (!rest.isEmpty() && !Character.isWhitespace(rest.charAt(0))) continue;
            Matcher m = UID.matcher(rest);
            if (m.find()) return Integer.parseInt(m.group(1));
        }
        return -1;
    }

    private static final class Geraet implements Quelle {
        @Override public int uid(int clientPort, int serverPort) {
            return LoopbackPeer.clientUid(clientPort, serverPort);
        }

        /** Gemeinsames Gedächtnis mit {@link IpcAufrufer} (ein {@code cmd package}-Aufruf, neu bei Neuinstallation). */
        @Override public int appUid() {
            return IpcAufrufer.quelle.appUid(false);
        }

        @Override public String[] zugang() {
            at.greenchili.surfmate.auth.AuthManager.AuthState s = at.greenchili.surfmate.auth.AuthManager.getState();
            return s == null ? null : new String[] {s.deviceId, s.deviceSecret};
        }
    }
}
