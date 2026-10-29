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
            HttpResponse.sendJson(out, 200, mitVorklima(d, d.status()).toString());
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
        // 0.1.23 Gewohnheiten E5: Automationen dürfen nur sofort/sofort_aus/weiter, nie Einstellungen (Freigabe, Plan)
        if (HttpServer.isAutomationRequest() && (e != null || "plan_aus".equals(b.optString("aktion", "")))) {
            HttpResponse.sendJson(out, 403, fehler("nur_aktionen"));
            return true;
        }
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
        // 0.1.22 Gewohnheiten: Ladeplan-Änderung mitschreiben (nur mitgeschrieben, nie vorgeschlagen)
        try {
            JSONObject w = new JSONObject();
            if (e != null) w.put("felder", new org.json.JSONArray(new java.util.TreeSet<>(iterierbar(e.keys()))));
            if (!b.optString("aktion", "").isEmpty()) w.put("aktion", b.optString("aktion"));
            if (w.length() > 0) at.greenchili.surfmate.gewohnheiten.Gewohnheiten.melde(at.greenchili.surfmate.gewohnheiten.Aktionen.LADEPLAN, w);
        } catch (Exception ignored) {
        }
        HttpResponse.sendJson(out, 200, mitVorklima(d, d.status()).toString());
        return true;
    }

    /** 0.1.28 (P6): Stand „zur Abfahrt warm“ (nächster Start, letztes Ergebnis) an die Antwort hängen. */
    private static JSONObject mitVorklima(LadeplanDienst d, JSONObject st) {
        try {
            st.put("vorklima", Ladeplan.abfahrtKlima().vorschau(d.einstellungen(), java.time.ZoneId.systemDefault(),
                    d.feiertagsLand()));
        } catch (Exception ignored) { }
        return st;
    }

    private static java.util.List<String> iterierbar(java.util.Iterator<String> it) {
        java.util.List<String> l = new java.util.ArrayList<>();
        while (it.hasNext()) l.add(it.next());
        return l;
    }

    private static String fehler(String grund) {
        return "{\"ok\":false,\"grund\":\"" + grund + "\"}";
    }
}
