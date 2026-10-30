package at.greenchili.surfmate.server;

import at.greenchili.surfmate.tacho.TachoFaehigkeit;
import at.greenchili.surfmate.tacho.TachoNaviApp;

import org.json.JSONObject;

import java.io.OutputStream;

/**
 * Kern Tacho 0.1.30: Fähigkeitstest und „Navi-App am Tacho“.
 *
 * <ul>
 *   <li>{@code GET  /api/tacho/faehigkeit} – nur lesend, nur im Stand; mit {@code text} zum Kopieren.</li>
 *   <li>{@code GET  /api/tacho/navi} – Schalter, gewählte App, Erlaubt-Liste.</li>
 *   <li>{@code PUT  /api/tacho/navi} {@code {an, paket}} – Ausschalten immer, Einschalten nur im Stand und
 *       nur, wenn der Test „Andere Apps: ja“ meldet.</li>
 *   <li>{@code POST /api/tacho/navi/probe} {@code {paket}} – 20 s im Stand, dann von selbst zurück.</li>
 *   <li>{@code PUT  /api/tacho/navi/warnleuchten} {@code {frei: ja|nein}} – Antwort nach der Probe.</li>
 * </ul>
 * Nur Login/lokal, nie Kopplungs-Zugänge. Nichts wird an einen Server geschickt.
 */
public final class TachoApiHandler {

    private TachoApiHandler() {}

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String p = path;
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        if (!p.startsWith("/api/tacho/")) return false;
        if (AuthMiddleware.isExternRequest()) {
            HttpResponse.sendJson(out, 403, "{\"ok\":false,\"grund\":\"nur_mit_login\"}");
            return true;
        }
        JSONObject in = parse(body);

        if (p.equals("/api/tacho/faehigkeit") && method.equals("GET")) {
            String bewegung = bewegungsGrund();
            if (bewegung != null) {
                HttpResponse.sendJson(out, 409, new JSONObject().put("success", false)
                        .put("grund", "nur_im_stand").put("detail", bewegung).toString());
                return true;
            }
            JSONObject r = TachoFaehigkeit.bewerte(TachoFaehigkeit.erhebe());
            r.put("text", TachoFaehigkeit.alsText(r));
            HttpResponse.sendJson(out, r.toString());
            return true;
        }
        if (p.equals("/api/tacho/navi") && method.equals("GET")) {
            HttpResponse.sendJson(out, TachoNaviApp.status().toString());
            return true;
        }
        if (p.equals("/api/tacho/navi") && (method.equals("PUT") || method.equals("POST"))) {
            JSONObject r = TachoNaviApp.setze(in.optBoolean("an", false), in.optString("paket", ""));
            HttpResponse.sendJson(out, r.optBoolean("ok") ? 200 : 409, r.toString());
            return true;
        }
        if (p.equals("/api/tacho/navi/probe") && method.equals("POST")) {
            JSONObject r = TachoNaviApp.probe(in.optString("paket", ""));
            HttpResponse.sendJson(out, r.optBoolean("ok") ? 200 : 409, r.toString());
            return true;
        }
        if (p.equals("/api/tacho/navi/warnleuchten") && (method.equals("PUT") || method.equals("POST"))) {
            JSONObject r = TachoNaviApp.bestaetige(in.optString("frei", ""));
            HttpResponse.sendJson(out, r.optBoolean("ok") ? 200 : 400, r.toString());
            return true;
        }
        return false;
    }

    private static String bewegungsGrund() {
        try {
            return at.greenchili.surfmate.byd.routing.DrivingSafetyGuard.getMovementBlockReason();
        } catch (Throwable t) {
            return "state_unknown";
        }
    }

    private static JSONObject parse(String body) {
        try {
            return new JSONObject(body == null || body.isEmpty() ? "{}" : body);
        } catch (Exception e) {
            return new JSONObject();
        }
    }
}
