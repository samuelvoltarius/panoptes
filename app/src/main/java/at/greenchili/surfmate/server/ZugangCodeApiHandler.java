package at.greenchili.surfmate.server;

import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 0.1.38.14: {@code GET /api/zugang/code} – der Zugangscode für die Karte „Dein Zugangscode“ in {@code system.html}.
 * Ersetzt 1:1, was das alte native Dashboard am Autobildschirm konnte (die Oberfläche ist mit 0.1.38.14 raus).
 *
 * <ul>
 *   <li>Nur der Display-WebView der Panoptes-App: direkt am Auto ({@link AuthMiddleware#isDirektAmAuto()}: Loopback,
 *       kein Tunnel, keine Brücke, nicht über tailscaled, keine Kopplung, keine Web-Sitzung) UND die Linux-UID des
 *       Gegen-Sockets ist die des Pakets {@value AuthZugangIpc#APP_PAKET}. Andere Apps am Auto (auch der Launcher),
 *       Kopplungen, PC, Tailnet und ein Login von außen bekommen 403.</li>
 *   <li>Antwort mit {@code Cache-Control: no-store}. Der Code wird nie geloggt (GET steht nicht im Befehls-Log).</li>
 *   <li>Fail-closed: UID oder App-UID nicht lesbar → 403.</li>
 * </ul>
 */
final class ZugangCodeApiHandler {

    static final String PFAD = "/api/zugang/code";

    /** Gerät (im Test nachgebaut). */
    interface Quelle {
        boolean direktAmAuto();
        /** Quellport der laufenden Anfrage oder −1. */
        int quellport();
        /** UID des Gegen-Sockets oder −1. */
        int uid(int quellport);
        /** UID des Pakets der Panoptes-App oder −1. */
        int appUid();
        /** Der Zugangscode oder null. */
        String code();
    }

    static volatile Quelle quelle = new Geraet();

    private ZugangCodeApiHandler() {}

    /** Rein: Status (200/403/404/405/503) und Antwort-JSON. */
    static Object[] antwort(String method, Quelle q) {
        try {
            if (!"GET".equalsIgnoreCase(method)) return new Object[] {405, fehler("methode")};
            if (!q.direktAmAuto()) return new Object[] {403, fehler("nur_display")};
            int port = q.quellport();
            int uid = port > 0 ? q.uid(port) : -1;
            int app = q.appUid();
            if (app < 10_000 || uid != app) return new Object[] {403, fehler("nur_display")};
            String c = q.code();
            if (c == null || c.isEmpty()) return new Object[] {503, fehler("kein_code")};
            return new Object[] {200, new JSONObject().put("ok", true).put("code", c)};
        } catch (Throwable t) {
            return new Object[] {403, fehler("nur_display")};
        }
    }

    private static JSONObject fehler(String grund) {
        try {
            return new JSONObject().put("ok", false).put("grund", grund);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static boolean handle(String method, String path, OutputStream out) throws Exception {
        String p = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        if (!PFAD.equals(p)) return false;
        Object[] a = antwort(method, quelle);
        int status = (Integer) a[0];
        byte[] body = a[1].toString().getBytes(StandardCharsets.UTF_8);
        if (status == 200) {
            HttpResponse.sendBinaryNoStore(out, "application/json; charset=utf-8", body, null);
        } else {
            HttpResponse.sendJson(out, status, a[1].toString());
        }
        return true;
    }

    private static final class Geraet implements Quelle {
        @Override public boolean direktAmAuto() { return AuthMiddleware.isDirektAmAuto(); }
        @Override public int quellport() { return AuthMiddleware.direktQuellport(); }
        @Override public int uid(int quellport) {
            return LoopbackPeer.clientUid(quellport, at.greenchili.surfmate.daemon.CameraDaemon.HTTP_PORT);
        }
        @Override public int appUid() { return IpcAufrufer.quelle.appUid(false); }
        @Override public String code() {
            at.greenchili.surfmate.auth.AuthManager.AuthState s = at.greenchili.surfmate.auth.AuthManager.getState();
            return s == null ? null : s.getSecret();
        }
    }
}
