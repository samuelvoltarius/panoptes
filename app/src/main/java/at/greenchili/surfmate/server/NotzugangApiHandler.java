package at.greenchili.surfmate.server;

import at.greenchili.surfmate.notzugang.Notzugang;

import org.json.JSONObject;

import java.io.OutputStream;

/**
 * 0.1.14 (Kern 7): Notzugang für die Fernwartung (NOTZUGANG_KONZEPT.md). Nur im Auto bzw. lokal/mit Login, nie über
 * die Cockpit-Kopplung.
 * <ul>
 *   <li>{@code GET  /api/notzugang} – {eingerichtet, an, aufpasser, laeuft, adbServe, zustand, ip?, name?, tags[]?,
 *       getaggt?, keyAblauf? (null = läuft nie ab), keyAblaufTage?} (0.1.15: adbServe, Tag/Key-Ablauf)</li>
 *   <li>Antwort von einrichten zusätzlich {adbServe, warnung?:"adb_serve", text?} – Knoten ist trotzdem angemeldet</li>
 *   <li>{@code POST /api/notzugang/einrichten {authKey, name}} – Weg 1 muss laufen (409 weg1_fehlt); Key wird nie
 *       gespeichert oder protokolliert</li>
 *   <li>{@code POST /api/notzugang/an} bzw. {@code /aus}</li>
 * </ul>
 */
public final class NotzugangApiHandler {

    private NotzugangApiHandler() {}

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String p = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        if (!p.equals("/api/notzugang") && !p.startsWith("/api/notzugang/")) return false;
        // 0.1.14.1: ein Fehler (auch Error wie NoSuchMethodError) darf den HTTP-Thread nie beenden → 500
        try {
            return handleIntern(method, p, body, out);
        } catch (Throwable t) {
            try {
                HttpResponse.sendJson(out, 500, new JSONObject().put("success", false).put("error", "intern")
                        .put("art", t.getClass().getSimpleName()).toString());
            } catch (Throwable ignored) {
            }
            return true;
        }
    }

    private static boolean handleIntern(String method, String p, String body, OutputStream out) throws Exception {
        if (AuthMiddleware.requestKopplungsId() != null) {
            HttpResponse.sendJson(out, 403, "{\"success\":false,\"error\":\"nur_im_auto\"}");
            return true;
        }
        if (p.equals("/api/notzugang") && method.equals("GET")) {
            HttpResponse.sendJson(out, Notzugang.status().put("success", true).toString());
            return true;
        }
        if (!method.equals("POST")) return false;
        JSONObject r;
        switch (p) {
            case "/api/notzugang/einrichten": {
                JSONObject req;
                try {
                    req = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
                } catch (Exception e) {
                    HttpResponse.sendJson(out, 400, "{\"success\":false,\"error\":\"json\"}");
                    return true;
                }
                r = Notzugang.einrichten(req.optString("authKey", ""), req.optString("name", "panoptes-notzugang"));
                break;
            }
            case "/api/notzugang/an":
                r = Notzugang.einschalten();
                break;
            case "/api/notzugang/aus":
                r = Notzugang.ausschalten();
                break;
            default:
                return false;
        }
        int status = r.optBoolean("success") ? 200 : "weg1_fehlt".equals(r.optString("error")) ? 409 : 422;
        if (!r.optBoolean("success")) {
            r.put("text", Messages.text("notzugang.fehler." + r.optString("error"), r.optString("error")));
        } else if (r.has("warnung")) {
            r.put("text", Messages.text("notzugang.fehler." + r.optString("warnung"), r.optString("warnung")));
        }
        HttpResponse.sendJson(out, status, r.toString());
        return true;
    }
}
