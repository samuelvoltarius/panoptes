package at.greenchili.surfmate.server;

import at.greenchili.surfmate.camping.Tiermodus;

import org.json.JSONObject;

import java.io.OutputStream;

/**
 * Tiermodus (03.11.2026) für die Display-Seite {@code tiermodus.html}. Login; für Kopplungen gesperrt (nicht in
 * {@link ExternGuard}) und nicht im Automationsweg von {@link HttpServer}.
 * <ul>
 *   <li>{@code GET /api/tiermodus} – Stand (aktiv, Anzeige ok/pruefen, Probleme, Innentemperatur nur gemessen)</li>
 *   <li>{@code POST /api/tiermodus} {@code {"an": true}} – nur direkt am Autobildschirm
 *       ({@link AuthMiddleware#isDirektAmAuto()}); ein Login aus der Ferne reicht nicht. Aus der Ferne startet nur
 *       die Handy-App mit Recht {@code klima} ({@code /api/handy/v1/aktion}).</li>
 *   <li>{@code POST /api/tiermodus} {@code {"an": false}} – aus</li>
 *   <li>{@code POST /api/tiermodus/gesehen} – Meldung bestätigt, Wiederholung hört auf</li>
 *   <li>{@code POST /api/tiermodus/hinweis} {@code {"version": …}} – Haftungshinweis bestätigt (Pflicht vor dem Start)</li>
 * </ul>
 */
public final class TiermodusApiHandler {

    private TiermodusApiHandler() {}

    /** Prüfung „direkt am Auto“ (in Tests ersetzbar). */
    static java.util.function.BooleanSupplier direktAmAuto = AuthMiddleware::isDirektAmAuto;

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String p = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        if (p.equals("/api/tiermodus") && "GET".equals(method)) {
            HttpResponse.sendJson(out, Tiermodus.status().toString());
            return true;
        }
        if (p.equals("/api/tiermodus") && "POST".equals(method)) {
            JSONObject r = schalten(json(body));
            int code = r.optBoolean("ok", false) ? 200
                    : "nur_am_auto".equals(r.optString("fehler")) ? 403
                    : "an".equals(r.optString("fehler")) ? 400 : 409;
            HttpResponse.sendJson(out, code, r.toString());
            return true;
        }
        if (p.equals("/api/tiermodus/hinweis") && "POST".equals(method)) {
            // Haftungshinweis bestätigt (Knopf „Verstanden, ich bin selbst verantwortlich“), nur aktuelle Version
            JSONObject req = json(body);
            JSONObject r = Tiermodus.hinweisBestaetigen(req == null ? "" : req.optString("version", ""));
            HttpResponse.sendJson(out, r.optBoolean("ok", false) ? 200 : 409, r.toString());
            return true;
        }
        if (p.equals("/api/tiermodus/gesehen") && "POST".equals(method)) {
            JSONObject r = Tiermodus.quittieren("web");
            HttpResponse.sendJson(out, r.optBoolean("ok", false) ? 200 : 409, r.toString());
            return true;
        }
        return false;
    }

    static JSONObject schalten(JSONObject req) throws Exception {
        if (req == null || !(req.opt("an") instanceof Boolean)) {
            return new JSONObject().put("ok", false).put("fehler", "an");
        }
        if (!req.getBoolean("an")) return Tiermodus.aus("web");
        if (!direktAmAuto.getAsBoolean()) return new JSONObject().put("ok", false).put("fehler", "nur_am_auto");
        return Tiermodus.ein("display");
    }

    private static JSONObject json(String body) {
        try {
            return new JSONObject(body == null || body.isEmpty() ? "{}" : body);
        } catch (Exception e) {
            return null;
        }
    }
}
