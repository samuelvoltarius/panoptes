package at.greenchili.surfmate.server;

import org.json.JSONObject;

import java.io.OutputStream;

/**
 * 0.1.22/0.1.23 Gewohnheiten (GEWOHNHEITEN_KONZEPT §2–§5).
 * <ul>
 *   <li>{@code GET /api/gewohnheiten} – Schalter, Zahl der Einträge, Herkünfte, erkannte Gewohnheiten (das Log selbst
 *   wird nie ausgeliefert).</li>
 *   <li>{@code GET /api/gewohnheiten/karte} – die eine Karte (Vorschlag, Probelauf-Rückfrage, Freigabe) plus
 *   {@code imStand}: das Display zeigt sie nur in P. Auch für das Cockpit (ExternGuard LESEN).</li>
 *   <li>{@code POST /api/gewohnheiten/antwort {art, schluessel|automation, antwort}} – Display und Cockpit
 *   (ExternGuard SCHREIBEN); das Handy antwortet über {@code /api/handy/v1/gewohnheit}.</li>
 * </ul>
 * Schalter, Aufbewahrung und „Alles vergessen“ laufen über /api/einstellungen ({@code gewohnheiten.*}).
 */
public final class GewohnheitenApiHandler {

    private GewohnheitenApiHandler() {}

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String p = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        at.greenchili.surfmate.gewohnheiten.Gewohnheiten g = at.greenchili.surfmate.gewohnheiten.Gewohnheiten.get();
        if ("/api/gewohnheiten".equals(p) && "GET".equals(method)) {
            HttpResponse.sendJson(out, 200, g.status().toString());
            return true;
        }
        if ("/api/gewohnheiten/karte".equals(p) && "GET".equals(method)) {
            HttpResponse.sendJson(out, 200, karte().toString());
            return true;
        }
        if ("/api/gewohnheiten/antwort".equals(p) && "POST".equals(method)) {
            JSONObject b;
            try {
                b = new JSONObject(body == null || body.trim().isEmpty() ? "{}" : body);
            } catch (Exception e) {
                HttpResponse.sendJson(out, 400, "{\"ok\":false,\"grund\":\"json\"}");
                return true;
            }
            JSONObject r = g.angebot().antworte(b);
            HttpResponse.sendJson(out, r.optBoolean("ok", false) ? 200 : 400, r.toString());
            return true;
        }
        return false;
    }

    /** Karte plus {@code imStand} (Gang P; unbekannt = nicht im Stand, die Karte bleibt dann am Display weg). */
    public static JSONObject karte() throws Exception {
        JSONObject k = at.greenchili.surfmate.gewohnheiten.Gewohnheiten.get().angebot().karte();
        boolean imStand = false;
        try {
            at.greenchili.surfmate.byd.BydVehicleData d = at.greenchili.surfmate.byd.BydDataCollector.getInstance().getData();
            imStand = d != null && "P".equals(at.greenchili.surfmate.monitor.GearMonitor.gearToString(d.gearMode));
        } catch (Throwable ignored) {
        }
        return k.put("imStand", imStand);
    }
}
