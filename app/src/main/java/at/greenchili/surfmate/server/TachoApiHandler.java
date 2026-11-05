package at.greenchili.surfmate.server;

import at.greenchili.surfmate.tacho.DiLink5TachoAbnahme;
import at.greenchili.surfmate.tacho.TachoAnsicht;
import at.greenchili.surfmate.tacho.TachoAuswahl;
import at.greenchili.surfmate.tacho.TachoRueckmeldung;
import at.greenchili.surfmate.tacho.TachoFaehigkeit;
import at.greenchili.surfmate.tacho.TachoNaviApp;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.URLDecoder;

/**
 * Fähigkeitstest und paketbezogene App-Freigabe fuer das Fahrerdisplay.
 *
 * <ul>
 *   <li>{@code GET  /api/tacho/faehigkeit} – nur lesend, nur im Stand; mit {@code text} zum Kopieren.</li>
 *   <li>{@code GET  /api/tacho/navi} – Schalter, gewählte App, startbare Apps und Freigaben.</li>
 *   <li>{@code GET  /api/tacho/dilink5-abnahme?paket=...} – nur lesender, nicht startbarer
 *       Messvertrag fuer eine spaetere physische DiLink-5-Abnahme.</li>
 *   <li>{@code PUT  /api/tacho/navi} {@code {an, paket}} – Ausschalten immer, Einschalten nur im Stand und
 *       nur fuer eine zuvor in P gepruefte und freigegebene App.</li>
 *   <li>{@code POST /api/tacho/navi/probe} {@code {paket}} – 20 s im Stand, dann von selbst zurück.</li>
 *   <li>{@code POST /api/tacho/rueckmeldung} {@code {einwilligung: true, text}} – Tacho-Test-Ergebnis
 *       ueber „Fehler melden“; nur mit Einwilligung und nur exakt der gezeigte Text.</li>
 *   <li>{@code PUT  /api/tacho/navi/hinweis} {@code {verstanden: true}} – einmaliger Hinweis vor der
 *       ersten Probe bzw. dem ersten Start.</li>
 *   <li>{@code PUT  /api/tacho/ansicht} {@code {design}} – Design der Panoptes-Tacho-Ansicht.</li>
 *   <li>{@code GET/PUT /api/tacho/groesse} {@code {profil: 0|29|31|30}} – „Größe im Fahrerdisplay“ (0.1.38.11),
 *       setzen nur im Stand, zeigt die Größe sofort ({@link at.greenchili.surfmate.tacho.TachoGroesse}).</li>
 *   <li>{@code GET  /api/tacho/auswahl}, {@code POST /api/tacho/auswahl} {@code {id}} – „Was zeigt der
 *       Tacho?“: Designs, Navi- und freigegebene Apps, {@code normal} ({@link TachoAuswahl}).</li>
 *   <li>{@code PUT  /api/tacho/navi/warnleuchten} {@code {frei: ja|nein, paket}} – Antwort nach
 *       der Probe oder paketbezogener Widerruf.</li>
 * </ul>
 * Nur Login/lokal, nie Kopplungs-Zugänge. An einen Server geht nur die Tacho-Rueckmeldung, und nur
 * nach ausdruecklicher Einwilligung je Sendung.
 */
public final class TachoApiHandler {

    /** Gemeinsame Auswahl fuer Web, Handy-Apps und Cockpit (Vertrag TACHO_AUSWAHL_API.md). */
    public static final String AUSWAHL = "/api/tacho/auswahl";

    private TachoApiHandler() {}

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String p = path;
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        if (!p.startsWith("/api/tacho/")) return false;
        if (AuthMiddleware.isExternRequest() && !kopplungDarf(p)) {
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
            r.put("rueckmeldung", TachoRueckmeldung.text(r));
            HttpResponse.sendJson(out, r.toString());
            return true;
        }
        if (p.equals("/api/tacho/rueckmeldung") && method.equals("POST")) {
            // Nur mit Einwilligung fuer genau diese Sendung und nur exakt der gezeigte Text.
            String aktuell = TachoRueckmeldung.text(TachoFaehigkeit.bewerte(TachoFaehigkeit.erhebe()));
            JSONObject r = TachoRueckmeldung.senden(in.opt("einwilligung"), in.optString("text", ""),
                    aktuell, RueckmeldungApiHandler::sendeTachoTest);
            String grund = r.optString("grund");
            int status = r.optBoolean("ok") ? 200 : "keine_einwilligung".equals(grund) ? 400
                    : "vorschau_alt".equals(grund) ? 409 : "zuviel".equals(r.optString("stand")) ? 429 : 502;
            HttpResponse.sendJson(out, status, r.toString());
            return true;
        }
        // 0.1.37 „Was zeigt der Tacho?“: eine Auswahl aus Designs, Navi- und freigegebenen Apps.
        if (p.equals(AUSWAHL) && method.equals("GET")) {
            HttpResponse.sendJson(out, TachoAuswahl.liste().toString());
            return true;
        }
        if (p.equals(AUSWAHL) && (method.equals("PUT") || method.equals("POST"))) {
            JSONObject r = TachoAuswahl.waehle(in.optString("id", ""));
            HttpResponse.sendJson(out, r.optBoolean("ok") ? 200
                    : "ungueltige_auswahl".equals(r.optString("grund")) ? 400 : 409, r.toString());
            return true;
        }
        // 0.1.38.11 „Größe im Fahrerdisplay“: lesen jederzeit, setzen nur im Stand (sendet sofort ans Fahrerdisplay)
        if (p.equals("/api/tacho/groesse") && method.equals("GET")) {
            HttpResponse.sendJson(out, at.greenchili.surfmate.tacho.TachoGroesse.stand().toString());
            return true;
        }
        if (p.equals("/api/tacho/groesse") && (method.equals("PUT") || method.equals("POST"))) {
            String bewegung = bewegungsGrund();
            if (bewegung != null) {
                HttpResponse.sendJson(out, 409, new JSONObject().put("ok", false)
                        .put("grund", "nur_im_stand").put("detail", bewegung).toString());
                return true;
            }
            JSONObject r = at.greenchili.surfmate.tacho.TachoGroesse.setze(in.optInt("profil", -1));
            HttpResponse.sendJson(out, r.optBoolean("ok") ? 200 : 400, r.toString());
            return true;
        }
        // 0.1.37 „Tacho wieder normal“: stoppt jede Projektion (App, Spiegel, Karte), Instrumentenanzeige zurueck.
        if (p.equals("/api/tacho/normal") && method.equals("POST")) {
            HttpResponse.sendJson(out, TachoNaviApp.tachoNormal().toString());
            return true;
        }
        // 0.1.37 „auch beim Fahren“ je App: eigener Haftungshinweis, dann Option setzen. Nur mit Login
        // (Kopplungen oben abgewiesen, Handy-Web per WebHandyGuard, Automation/KI nicht in der Erlaubnisliste).
        if (p.equals("/api/tacho/navi/fahrt/hinweis") && (method.equals("PUT") || method.equals("POST"))) {
            JSONObject r = TachoNaviApp.fahrtHinweisBestaetigen(in.optString("version", ""));
            HttpResponse.sendJson(out, r.optBoolean("ok") ? 200 : 400, r.toString());
            return true;
        }
        if (p.equals("/api/tacho/navi/fahrt") && (method.equals("PUT") || method.equals("POST"))) {
            JSONObject r = TachoNaviApp.setzeFahrt(in.optString("paket", ""), in.optBoolean("an", false));
            HttpResponse.sendJson(out, r.optBoolean("ok") ? 200 : 409, r.toString());
            return true;
        }
        if (p.equals("/api/tacho/navi/hinweis") && (method.equals("PUT") || method.equals("POST"))) {
            if (!in.optBoolean("verstanden", false)) {
                HttpResponse.sendJson(out, 400, "{\"ok\":false,\"grund\":\"verstanden_noetig\"}");
                return true;
            }
            HttpResponse.sendJson(out, TachoNaviApp.hinweisBestaetigen().toString());
            return true;
        }
        if (p.equals("/api/tacho/ansicht") && (method.equals("PUT") || method.equals("POST"))) {
            boolean ok = TachoAnsicht.setzeDesign(in.optString("design", ""));
            HttpResponse.sendJson(out, ok ? 200 : 400, new JSONObject().put("ok", ok)
                    .put("design", TachoAnsicht.design()).toString());
            return true;
        }
        if (p.equals("/api/tacho/dilink5-abnahme") && method.equals("GET")) {
            String bewegung = bewegungsGrund();
            if (bewegung != null) {
                HttpResponse.sendJson(out, 409, new JSONObject().put("success", false)
                        .put("grund", "nur_im_stand").put("detail", bewegung).toString());
                return true;
            }
            TachoFaehigkeit.Eingaben raw = TachoFaehigkeit.erhebe();
            JSONObject capability = TachoFaehigkeit.bewerte(raw);
            JSONObject contract = DiLink5TachoAbnahme.messvertrag(
                    capability.optJSONObject("plattform"), query(path, "paket"));
            HttpResponse.sendJson(out, contract.toString());
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
            JSONObject r = TachoNaviApp.bestaetige(
                    in.optString("frei", ""), in.optString("paket", ""));
            HttpResponse.sendJson(out, r.optBoolean("ok") ? 200 : 400, r.toString());
            return true;
        }
        return false;
    }

    /**
     * Kopplungen nur fuer Auswahl und „Tacho wieder normal“, und nur mit Recht {@code tacho}. HttpServer prueft
     * das schon ueber {@code ExternGuard}; hier noch einmal, damit dieser Handler allein dicht ist.
     */
    private static boolean kopplungDarf(String p) {
        if (!AUSWAHL.equals(p) && !"/api/tacho/normal".equals(p)) return false;
        try {
            return at.greenchili.surfmate.auth.Kopplungen.get().hatRecht(AuthMiddleware.requestKopplungsId(),
                    at.greenchili.surfmate.auth.Kopplungen.RECHT_TACHO);
        } catch (Throwable t) {
            return false;
        }
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

    private static String query(String path, String key) {
        if (path == null) return "";
        int q = path.indexOf('?');
        if (q < 0 || q == path.length() - 1) return "";
        for (String part : path.substring(q + 1).split("&")) {
            int eq = part.indexOf('=');
            String name = eq < 0 ? part : part.substring(0, eq);
            if (!key.equals(decode(name))) continue;
            String value = eq < 0 ? "" : part.substring(eq + 1);
            return decode(value);
        }
        return "";
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception ignored) {
            return "";
        }
    }
}
