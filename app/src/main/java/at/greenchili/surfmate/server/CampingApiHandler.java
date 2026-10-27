package at.greenchili.surfmate.server;

import at.greenchili.surfmate.camping.Camping;

import org.json.JSONObject;

import java.io.OutputStream;

/**
 * Camping-Modus (CAMPING_PLAN.md, 0.1.16 Stufe 2a). Eine Panoptes-Funktion, kein Fahrzeugbefehl: In 2a heizt
 * nichts (Trockenlauf). Login oder Kopplung; die Pfade stehen ausdrücklich in {@link ExternGuard}.
 * <ul>
 *   <li>{@code GET /api/camping} – Status (aktiv, Phase, nächster Zyklus, Budget, Verbrauch, Trockenlauf-Protokoll;
 *       nie eine Innentemperatur ohne echten Fühler)</li>
 *   <li>{@code POST /api/camping} {@code {"an": true|false}} – ein/aus</li>
 *   <li>{@code POST /api/camping/rueckmeldung} {@code {"art": "zu_kalt"|"passt"|"zu_warm"}}</li>
 *   <li>{@code POST /api/camping/weiter} – nach „Auto hat abgeschaltet“ weiter regeln</li>
 *   <li>{@code POST /api/camping/plan} {@code {"start": ISO|HH:MM, "ende": ISO|HH:MM|null}} bzw. {@code {"loeschen": true}}
 *       – geplanter Start (0.1.17, Cockpit)</li>
 * </ul>
 */
public final class CampingApiHandler {

    private CampingApiHandler() {}

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String p = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        if (p.equals("/api/camping") && "GET".equals(method)) {
            HttpResponse.sendJson(out, Camping.status().toString());
            return true;
        }
        if (p.equals("/api/camping") && "POST".equals(method)) {
            JSONObject req = json(body);
            if (req == null || !req.has("an")) {
                HttpResponse.sendJson(out, 400, "{\"ok\":false,\"fehler\":\"an\"}");
                return true;
            }
            JSONObject r = req.optBoolean("an", false) ? Camping.ein("web") : Camping.aus("tipp");
            HttpResponse.sendJson(out, r.optBoolean("ok", false) ? 200 : 409, r.toString());
            return true;
        }
        if (p.equals("/api/camping/rueckmeldung") && "POST".equals(method)) {
            JSONObject req = json(body);
            JSONObject r = Camping.rueckmeldung(req == null ? "" : req.optString("art", ""));
            HttpResponse.sendJson(out, r.optBoolean("ok", false) ? 200 : 400, r.toString());
            return true;
        }
        if (p.equals("/api/camping/plan") && "POST".equals(method)) {
            JSONObject req = json(body);
            JSONObject r = Camping.plan(req);
            HttpResponse.sendJson(out, r.optBoolean("ok", false) ? 200
                    : "camping_laeuft".equals(r.optString("fehler")) ? 409 : 400, r.toString());
            return true;
        }
        if (p.equals("/api/camping/weiter") && "POST".equals(method)) {
            HttpResponse.sendJson(out, Camping.weiter().toString());
            return true;
        }
        return false;
    }

    private static JSONObject json(String body) {
        try {
            return new JSONObject(body == null || body.isEmpty() ? "{}" : body);
        } catch (Exception e) {
            return null;
        }
    }
}
