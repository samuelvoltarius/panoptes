package at.greenchili.surfmate.server;

import at.greenchili.surfmate.BuildConfig;
import at.greenchili.surfmate.analytics.Mitzaehlen;
import at.greenchili.surfmate.config.UnifiedConfigManager;
import at.greenchili.surfmate.rueckmeldung.AutoAngaben;
import at.greenchili.surfmate.rueckmeldung.FehlerMelden;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.util.UUID;

/**
 * 0.1.31 (Regel 2). Nur Login oder lokal, nie für gekoppelte Geräte (ExternGuard-Erlaubnisliste, hier doppelt):
 * <ul>
 *   <li>{@code GET /api/rueckmeldung/fehler[?diagnose=1&sprache=de]} – was automatisch mitgeht, Vorschau, Stand</li>
 *   <li>{@code POST /api/rueckmeldung/fehler {text, sprache, vorschauId?, art?}} – eine Meldung absenden</li>
 *   <li>{@code GET /api/rueckmeldung/zaehlen} – ist die einmalige Frage offen, und darf sie jetzt kommen?</li>
 *   <li>{@code POST /api/rueckmeldung/zaehlen {antwort:"ja"|"nein"}} – Antwort, nur im Stand</li>
 * </ul>
 */
public final class RueckmeldungApiHandler {

    private RueckmeldungApiHandler() {}

    static final long VORSCHAU_MS = 15 * 60_000L;
    /** Darüber gilt das Auto als fahrend: Am Display nicht tippen. */
    static final double FAHRT_KMH = 3.0;

    private static String vorschauId;
    private static String vorschauText;
    private static long vorschauZeit;

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String pathOnly = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        String query = path.contains("?") ? path.substring(path.indexOf('?') + 1) : "";
        boolean fehler = pathOnly.equals("/api/rueckmeldung/fehler");
        boolean zaehlen = pathOnly.equals("/api/rueckmeldung/zaehlen");
        if (!fehler && !zaehlen) return false;
        if (AuthMiddleware.isExternRequest()) {
            HttpResponse.sendJson(out, 403, "{\"ok\":false,\"grund\":\"verboten\"}");
            return true;
        }
        if (fehler && method.equals("GET")) {
            HttpResponse.sendJson(out, stand(query, System.currentTimeMillis()).toString());
            return true;
        }
        if (fehler && method.equals("POST")) {
            absenden(body, out);
            return true;
        }
        if (zaehlen && method.equals("GET")) {
            JSONObject z = UnifiedConfigManager.loadConfig().optJSONObject(Mitzaehlen.ABSCHNITT);
            boolean offen = Mitzaehlen.frageOffen(z);
            HttpResponse.sendJson(out, new JSONObject().put("offen", offen)
                    .put("fragen", offen && imStand()).toString());
            return true;
        }
        if (zaehlen && method.equals("POST")) {
            antworten(body, out);
            return true;
        }
        HttpResponse.sendError(out, 405, "Method Not Allowed");
        return true;
    }

    private static JSONObject stand(String query, long jetzt) throws Exception {
        String sprache = EinstellungenApiHandler.sprache(query);
        JSONObject res = auto(UnifiedConfigManager.loadConfig(), sprache);
        JSONObject st = FehlerMelden.DATEI_SPEICHER.lese();
        JSONArray offen = st == null ? null : st.optJSONArray("offen");
        JSONArray codes = st == null ? null : st.optJSONArray("codes");
        res.put("offen", offen == null ? 0 : offen.length())
                .put("codes", codes == null ? new JSONArray() : codes)
                .put("rest", FehlerMelden.rest(st, jetzt))
                .put("textMax", FehlerMelden.TEXT_MAX)
                .put("fahrt", faehrt());
        if (("&" + query + "&").contains("&diagnose=1&")) {
            String text = FehlerMelden.diagnoseText(at.greenchili.surfmate.genai.GenAiContext.diagnosticLogs());
            String id = UUID.randomUUID().toString();
            synchronized (RueckmeldungApiHandler.class) {
                vorschauId = id;
                vorschauText = text;
                vorschauZeit = jetzt;
            }
            res.put("diagnose", text).put("vorschauId", id);
        }
        return res;
    }

    /** Was automatisch mitgeht: genau das zeigt die Seite an. */
    static JSONObject auto(JSONObject root, String sprache) throws Exception {
        JSONObject a = new JSONObject().put("version", BuildConfig.VERSION_NAME)
                .put("modell", AutoAngaben.modell(root)).put("sprache", sprache);
        Integer d = AutoAngaben.dilink();
        if (d != null) a.put("dilink", (int) d);
        return a;
    }

    private static void absenden(String body, OutputStream out) throws Exception {
        JSONObject req;
        try {
            req = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
        } catch (Exception e) {
            HttpResponse.sendJson(out, 400, "{\"ok\":false,\"grund\":\"json\"}");
            return;
        }
        long jetzt = System.currentTimeMillis();
        String diagnose = null;
        String vid = req.optString("vorschauId", "");
        if (!vid.isEmpty()) {
            // Mit geht genau die gezeigte Vorschau, nie ein neu gelesener oder vom Aufrufer gelieferter Text
            synchronized (RueckmeldungApiHandler.class) {
                if (vid.equals(vorschauId) && jetzt - vorschauZeit <= VORSCHAU_MS) diagnose = vorschauText;
            }
            if (diagnose == null) {
                HttpResponse.sendJson(out, 409, "{\"ok\":false,\"grund\":\"vorschauAlt\"}");
                return;
            }
        }
        String l = req.optString("sprache", "");
        String sprache = LocaleManager.isSupported(l) ? l : LocaleManager.get();
        JSONObject k = FehlerMelden.koerper(req.optString("text", ""), BuildConfig.VERSION_NAME, AutoAngaben.dilink(),
                AutoAngaben.modell(UnifiedConfigManager.loadConfig()), sprache, diagnose, req.optString("art", ""));
        FehlerMelden.Ergebnis e = FehlerMelden.absenden(jetzt, k, FehlerMelden.DATEI_SPEICHER, FehlerMelden.NETZ);
        JSONObject res = new JSONObject().put("ok", e.stand == FehlerMelden.Stand.GESENDET
                || e.stand == FehlerMelden.Stand.GEMERKT).put("stand", e.stand.name().toLowerCase(java.util.Locale.ROOT));
        if (e.code != null) res.put("code", e.code);
        HttpResponse.sendJson(out, e.stand == FehlerMelden.Stand.UNGUELTIG ? 400
                : e.stand == FehlerMelden.Stand.ZUVIEL ? 429 : e.stand == FehlerMelden.Stand.ABGELEHNT ? 502 : 200, res.toString());
    }

    private static void antworten(String body, OutputStream out) throws Exception {
        JSONObject req;
        try {
            req = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
        } catch (Exception e) {
            req = new JSONObject();
        }
        String a = req.optString("antwort", "");
        JSONObject z = UnifiedConfigManager.forceReload().optJSONObject(Mitzaehlen.ABSCHNITT);
        if ((!a.equals("ja") && !a.equals("nein")) || !Mitzaehlen.frageOffen(z)) {
            HttpResponse.sendJson(out, 409, "{\"ok\":false,\"grund\":\"nichtOffen\"}");
            return;
        }
        if (!imStand()) {
            HttpResponse.sendJson(out, 409, "{\"ok\":false,\"grund\":\"nichtImStand\"}");
            return;
        }
        boolean ja = a.equals("ja");
        UnifiedConfigManager.updateSection(Mitzaehlen.ABSCHNITT, Mitzaehlen.antwort(ja, java.time.Instant.now().toString()));
        if (ja) new Thread(() -> Mitzaehlen.taktImDaemon(System.currentTimeMillis()), "ZaehlenSofort").start();
        HttpResponse.sendJson(out, "{\"ok\":true}");
    }

    private static boolean imStand() {
        try {
            return at.greenchili.surfmate.communication.VehicleCommunicationSafety.isParked() && !faehrt();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean faehrt() {
        try {
            double kmh = at.greenchili.surfmate.byd.Stillstand.tempoJetzt(
                    at.greenchili.surfmate.byd.BydDataCollector.getInstance().getData());
            return kmh > FAHRT_KMH;
        } catch (Throwable t) {
            return false;
        }
    }
}
