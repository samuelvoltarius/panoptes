package at.greenchili.surfmate.server;

import at.greenchili.surfmate.launcher.TailscaleHttps;
import at.greenchili.surfmate.logging.DaemonLogger;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

/**
 * {@code GET/PUT /api/tailscale/https} – HTTPS im eigenen Tailnet (0.1.9, Koordination 21.10.).
 * Bewusst NICHT in der Kopplungs-Erlaubnisliste (ExternGuard): nur am Display bzw. mit Login.
 * Logik und Regel-B-Grenzen in {@link TailscaleHttps}.
 */
public final class TailscaleHttpsApiHandler {

    private static final String TAG = "TailscaleHttpsApi";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    static final String PFAD = "/api/tailscale/https";

    private TailscaleHttpsApiHandler() {}

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String clean = path.indexOf('?') >= 0 ? path.substring(0, path.indexOf('?')) : path;
        if (!PFAD.equals(clean)) return false;
        if ("GET".equals(method)) {
            HttpResponse.sendJson(out, TailscaleHttps.lesen(TailscaleHttpsApiHandler::run).toString());
            return true;
        }
        if ("PUT".equals(method) || "POST".equals(method)) {
            JSONObject in;
            try {
                in = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
            } catch (Exception e) {
                HttpResponse.sendError(out, 400, "invalid JSON");
                return true;
            }
            if (!in.has("an")) {
                HttpResponse.sendError(out, 400, "an fehlt");
                return true;
            }
            boolean an = in.optBoolean("an", false);
            logger.info("HTTPS im Tailnet " + (an ? "ein" : "aus") + " (Nutzerwunsch)");
            HttpResponse.sendJson(out, TailscaleHttps.setzen(an, TailscaleHttpsApiHandler::run).toString());
            return true;
        }
        HttpResponse.sendError(out, 405, "method not allowed");
        return true;
    }

    /** 0.1.21 PWA Stufe 1: Wächter für die eigene HTTPS-Regel starten (im Daemon). */
    public static void starteWaechter() {
        TailscaleHttps.starteWaechter(TailscaleHttpsApiHandler::run);
    }

    private static String run(String cmd, int timeoutSec) {
        Process p = null;
        try {
            p = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            p.waitFor(timeoutSec, TimeUnit.SECONDS);
            return sb.toString();
        } catch (Exception e) {
            logger.warn("command failed: " + e.getMessage());
            return "";
        } finally {
            if (p != null) p.destroy();
        }
    }
}
