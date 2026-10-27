package at.greenchili.surfmate.server;

import at.greenchili.surfmate.ladeplan.Ladeplan;
import at.greenchili.surfmate.ladeplan.LadeplanDienst;

import org.json.JSONObject;

import java.io.OutputStream;

/**
 * Ladeplan zu Hause (LADEPLAN_KONZEPT §7, 0.1.20).
 * <ul>
 *   <li>{@code GET  /api/ladeplan} – Einstellungen, Zustand, Vorschau (Slots je Stunde, kWh, Kosten, Ersparnis), letzte
 *       Schaltungen. Schaltet nie.</li>
 *   <li>{@code POST /api/ladeplan} – {@code {einstellungen?:{…}, aktion?:"sofort"|"sofort_aus"|"weiter"|"plan_aus"}}.
 *       Gekoppelte Geräte (Cockpit/Handy, {@link ExternGuard}) dürfen lesen und den Plan ändern, aber nie die Freigabe
 *       oder den Ladepunkt (403 {@code nur_am_auto}).</li>
 * </ul>
 */
public final class LadeplanApiHandler {

    private LadeplanApiHandler() {}

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String p = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        if (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        if (!"/api/ladeplan".equals(p)) return false;
        LadeplanDienst d = Ladeplan.dienst();
        if ("GET".equals(method)) {
            HttpResponse.sendJson(out, 200, d.status().toString());
            return true;
        }
        if (!"POST".equals(method)) return false;
        JSONObject b;
        try {
            b = body == null || body.trim().isEmpty() ? new JSONObject() : new JSONObject(body);
        } catch (Exception e) {
            HttpResponse.sendJson(out, 400, fehler("einstellung_ungueltig"));
            return true;
        }
        boolean extern = AuthMiddleware.isExternRequest();
        JSONObject e = b.optJSONObject("einstellungen");
        if (e != null) {
            String f = d.aendere(e, extern);
            if (f != null) {
                HttpResponse.sendJson(out, "nur_am_auto".equals(f) ? 403 : 400, fehler(f));
                return true;
            }
        }
        switch (b.optString("aktion", "")) {
            case "": break;
            case "sofort": d.sofort(true); break;
            case "sofort_aus": d.sofort(false); break;
            case "weiter": d.weiter(); break;
            case "plan_aus": {
                String f = d.aendere(new JSONObject().put("modus", "aus"), extern);
                if (f != null) {
                    HttpResponse.sendJson(out, 400, fehler(f));
                    return true;
                }
                break;
            }
            default:
                HttpResponse.sendJson(out, 400, fehler("aktion_ungueltig"));
                return true;
        }
        HttpResponse.sendJson(out, 200, d.status().toString());
        return true;
    }

    private static String fehler(String grund) {
        return "{\"ok\":false,\"grund\":\"" + grund + "\"}";
    }
}
