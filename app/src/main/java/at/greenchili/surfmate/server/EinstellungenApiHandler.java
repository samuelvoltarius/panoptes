package at.greenchili.surfmate.server;

import at.greenchili.surfmate.auth.AuthManager;
import at.greenchili.surfmate.auth.Kopplungen;
import at.greenchili.surfmate.auth.PairingCodes;
import at.greenchili.surfmate.config.UnifiedConfigManager;
import at.greenchili.surfmate.einstellungen.AkkuChemie;
import at.greenchili.surfmate.einstellungen.Einstellungen;
import at.greenchili.surfmate.einstellungen.Feld;
import at.greenchili.surfmate.einstellungen.Katalog;
import at.greenchili.surfmate.einstellungen.Laenderprofil;
import at.greenchili.surfmate.storage.Aufbewahrung;

import org.json.JSONObject;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;

/**
 * Einstellungs-Schema (Vertrag EINSTELLUNGEN_SCHEMA.md v1.1) plus Kopplung und Planer-Kennung:
 * <ul>
 *   <li>{@code GET /api/einstellungen/schema?sprache=de}</li>
 *   <li>{@code GET /api/einstellungen}, {@code PUT /api/einstellungen}</li>
 *   <li>{@code POST /api/kopplung/code} – Kopplungs-Code erzeugen (nur lokal, ExternGuard)</li>
 *   <li>{@code GET /api/kopplungen}, {@code POST /api/kopplung/widerrufen {id}} – gekoppelte Geräte
 *       (nur mit Login oder lokal; ExternGuard sperrt beide für Kopplungs-JWTs)</li>
 *   <li>{@code POST /api/sitzung/erneuern} – frischer JWT (30 Tage), gleiche Kopplungs-id</li>
 *   <li>{@code GET/POST /api/aufnahmen/behalten}, {@code POST /api/aufnahmen/freigeben {datei}} –
 *       Aufbewahrung (72 h, Behalten bis keepDays, danach Rückfrage)</li>
 *   <li>{@code GET /api/planer/identitaet} – feste Zufallskennung {@code planner.autoId}, nie die VIN</li>
 * </ul>
 * Zugriff: nur nach AuthMiddleware (Login oder Kopplungs-JWT).
 */
public final class EinstellungenApiHandler {

    private static final SecureRandom RANDOM = new SecureRandom();

    private EinstellungenApiHandler() {}

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String pathOnly = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        String query = path.contains("?") ? path.substring(path.indexOf('?') + 1) : "";
        if (pathOnly.equals("/api/einstellungen/schema") && method.equals("GET")) {
            String lang = sprache(query);
            JSONObject root = UnifiedConfigManager.loadConfig();
            Katalog.Kontext k = kontext(root);
            List<Feld> felder = Katalog.felder(k);
            JSONObject res = Messages.withLocale(lang, () -> {
                try {
                    return Einstellungen.schema(felder, root, k, texte(lang));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            HttpResponse.sendJson(out, res.toString());
            return true;
        }
        if (pathOnly.equals("/api/einstellungen") && method.equals("GET")) {
            JSONObject root = UnifiedConfigManager.loadConfig();
            Katalog.Kontext k = kontext(root);
            HttpResponse.sendJson(out, Einstellungen.werte(Katalog.felder(k), root, k).toString());
            return true;
        }
        if (pathOnly.equals("/api/einstellungen") && (method.equals("PUT") || method.equals("POST"))) {
            JSONObject req;
            try {
                req = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
            } catch (Exception e) {
                HttpResponse.sendJson(out, 400, "{\"ok\":false,\"grund\":\"json\"}");
                return true;
            }
            JSONObject root = UnifiedConfigManager.forceReload();
            Katalog.Kontext k = kontext(root);
            Einstellungen.Ergebnis e = Einstellungen.aendern(Katalog.felder(k), root, k, req,
                    AuthMiddleware.isExternRequest(), EinstellungenApiHandler::schreibe,
                    System.currentTimeMillis());
            JSONObject res = new JSONObject();
            res.put("ok", e.ok());
            res.put("etag", e.etag);
            if (e.status == 409) {
                res.put("grund", e.grund);
                HttpResponse.sendJson(out, 409, res.toString());
                return true;
            }
            res.put("gespeichert", e.gespeichert);
            res.put("fehler", e.fehler);
            nachwirkungen(e);
            HttpResponse.sendJson(out, res.toString());
            return true;
        }
        if (pathOnly.equals("/api/kopplung/code") && method.equals("POST")) {
            long now = System.currentTimeMillis();
            String code = PairingCodes.create(now);
            JSONObject res = new JSONObject();
            res.put("code", code);
            res.put("gueltigBis", now + PairingCodes.TTL_MS);
            HttpResponse.sendJson(out, res.toString());
            return true;
        }
        if (pathOnly.equals("/api/kopplungen") && method.equals("GET")) {
            if (AuthMiddleware.isExternRequest()) return verboten(out);   // doppelt zu ExternGuard
            JSONObject res = new JSONObject();
            res.put("geraete", Kopplungen.get().alle(System.currentTimeMillis()));
            res.put("gueltigOhneNutzungTage", Kopplungen.GUELTIG_OHNE_NUTZUNG_MS / 86_400_000L);
            HttpResponse.sendJson(out, res.toString());
            return true;
        }
        // Kopplung per Anfrage: offene Anfragen + Erlauben/Ablehnen, nur Login/lokal (nie Kopplungs-JWT).
        if (pathOnly.equals("/api/kopplung/anfragen") && method.equals("GET")) {
            if (AuthMiddleware.isExternRequest()) return verboten(out);
            JSONObject res = new JSONObject();
            res.put("anfragen", at.greenchili.surfmate.auth.KopplungsAnfragen.get().offeneListe(System.currentTimeMillis()));
            HttpResponse.sendJson(out, res.toString());
            return true;
        }
        if (pathOnly.startsWith("/api/kopplung/anfrage/") && method.equals("POST")
                && (pathOnly.endsWith("/erlauben") || pathOnly.endsWith("/ablehnen"))) {
            if (AuthMiddleware.isExternRequest()) return verboten(out);
            boolean erlauben = pathOnly.endsWith("/erlauben");
            String ref = pathOnly.substring("/api/kopplung/anfrage/".length(), pathOnly.lastIndexOf('/'));
            // 0.1.19: {"aufsperren":true} beim Erlauben einer Handy-Anfrage (nur Login/lokal, wie /api/kopplung/rechte)
            boolean aufsperren = false;
            try {
                aufsperren = erlauben && new JSONObject(body == null || body.isEmpty() ? "{}" : body).optBoolean("aufsperren", false);
            } catch (Exception ignored) {
            }
            at.greenchili.surfmate.auth.KopplungsAnfragen.Entscheid e =
                    at.greenchili.surfmate.auth.KopplungsAnfragen.get().entscheiden(ref, erlauben, aufsperren, System.currentTimeMillis());
            JSONObject res = new JSONObject();
            res.put("ok", e == at.greenchili.surfmate.auth.KopplungsAnfragen.Entscheid.OK);
            int code = 200;
            switch (e) {
                case OK: res.put("status", erlauben ? "erlaubt" : "abgelehnt"); break;
                case UNBEKANNT: res.put("grund", "unbekannt"); code = 404; break;
                case SCHON_ENTSCHIEDEN: res.put("grund", "schon_entschieden"); code = 409; break;
                default: res.put("grund", "abgelaufen"); code = 410; break;
            }
            HttpResponse.sendJson(out, code, res.toString());
            return true;
        }
        if (pathOnly.equals("/api/kopplung/widerrufen") && method.equals("POST")) {
            if (AuthMiddleware.isExternRequest()) return verboten(out);
            String id = "";
            try {
                id = new JSONObject(body == null || body.isEmpty() ? "{}" : body).optString("id", "");
            } catch (Exception ignored) {
            }
            int r = Kopplungen.get().widerrufen(id);
            JSONObject res = new JSONObject();
            res.put("ok", r == Kopplungen.WIDERRUFEN);
            if (r == Kopplungen.UNBEKANNT) res.put("grund", "unbekannt");
            if (r == Kopplungen.NUR_IM_SPEICHER) res.put("grund", "nicht_gespeichert");   // wirkt bis Neustart
            HttpResponse.sendJson(out, r == Kopplungen.UNBEKANNT ? 404 : r == Kopplungen.WIDERRUFEN ? 200 : 500,
                    res.toString());
            return true;
        }
        // 0.1.18 Handy-Vertrag §1/§2: Rechte einer Handy-Kopplung bzw. Geräteschlüssel – NUR mit Login (Panoptes-Web),
        // nie von einem gekoppelten Gerät (auch nicht vom Handy selbst).
        if (pathOnly.equals("/api/kopplung/rechte") && method.equals("POST")) {
            if (AuthMiddleware.isExternRequest()) return verboten(out);
            JSONObject req;
            try {
                req = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
            } catch (Exception e) {
                req = new JSONObject();
            }
            java.util.List<String> rechte = new java.util.ArrayList<>();
            org.json.JSONArray a = req.optJSONArray("rechte");
            for (int i = 0; a != null && i < a.length(); i++) rechte.add(a.optString(i));
            boolean ok = Kopplungen.get().rechteSetzen(req.optString("id", ""), rechte);
            HttpResponse.sendJson(out, ok ? 200 : 404, new JSONObject().put("ok", ok).toString());
            return true;
        }
        if (pathOnly.equals("/api/kopplung/schluessel-loeschen") && method.equals("POST")) {
            if (AuthMiddleware.isExternRequest()) return verboten(out);
            String id = "";
            try {
                id = new JSONObject(body == null || body.isEmpty() ? "{}" : body).optString("id", "");
            } catch (Exception ignored) {
            }
            boolean ok = Kopplungen.get().schluesselLoeschen(id);
            HttpResponse.sendJson(out, ok ? 200 : 404, new JSONObject().put("ok", ok).toString());
            return true;
        }
        // Gekoppeltes Gerät meldet sich selbst ab (z. B. HA-Integration wird entfernt): widerruft nur die
        // eigene Kopplung (kid aus dem JWT); Alfreds Login und andere Kopplungen bleiben unberührt.
        if (pathOnly.equals("/api/sitzung/abmelden") && method.equals("POST")) {
            String kid = AuthMiddleware.requestKopplungsId();
            JSONObject res = new JSONObject();
            if (kid == null) {
                res.put("ok", false);
                res.put("grund", "keine_kopplung");
                HttpResponse.sendJson(out, 400, res.toString());
                return true;
            }
            int r = Kopplungen.get().widerrufen(kid);
            res.put("ok", r != Kopplungen.UNBEKANNT);
            if (r == Kopplungen.NUR_IM_SPEICHER) res.put("grund", "nicht_gespeichert");
            HttpResponse.sendJson(out, r == Kopplungen.UNBEKANNT ? 404 : 200, res.toString());
            return true;
        }
        if (pathOnly.equals("/api/sitzung/erneuern") && method.equals("POST")) {
            // Gleitende Laufzeit: Kopplung bleibt dieselbe (gleiche id), nur exp wird neu gesetzt.
            String jwt = AuthMiddleware.isExternRequest()
                    ? AuthManager.generateJwt(PairingCodes.SCOPE, AuthMiddleware.requestKopplungsId())
                    : AuthManager.generateJwt();
            JSONObject res = new JSONObject();
            res.put("success", jwt != null);
            if (jwt != null) {
                res.put("jwt", jwt);
                res.put("expiresIn", AuthManager.JWT_EXPIRY_SECONDS);
            }
            HttpResponse.sendJson(out, jwt != null ? 200 : 500, res.toString());
            return true;
        }
        if (pathOnly.equals("/api/aufnahmen/behalten") && method.equals("GET")) {
            JSONObject root = UnifiedConfigManager.loadConfig();
            JSONObject res = new JSONObject();
            res.put("behalten", Aufbewahrung.liste(root, System.currentTimeMillis()));
            res.put("stunden", Aufbewahrung.maxAlterMs(root) / 3_600_000L);
            res.put("tage", Aufbewahrung.behaltenTage(root));
            HttpResponse.sendJson(out, res.toString());
            return true;
        }
        if ((pathOnly.equals("/api/aufnahmen/behalten") || pathOnly.equals("/api/aufnahmen/freigeben"))
                && method.equals("POST")) {
            String datei = "";
            try {
                datei = new JSONObject(body == null || body.isEmpty() ? "{}" : body).optString("datei", "");
            } catch (Exception ignored) {
            }
            String stamm = Aufbewahrung.stamm(datei);
            JSONObject res = new JSONObject();
            if (!Aufbewahrung.gueltigerStamm(stamm)) {
                res.put("ok", false);
                res.put("grund", "datei");
                HttpResponse.sendJson(out, 400, res.toString());
                return true;
            }
            boolean ok = pathOnly.endsWith("/behalten")
                    ? Aufbewahrung.behalten(stamm, System.currentTimeMillis())
                    : Aufbewahrung.freigeben(stamm);
            res.put("ok", ok);
            res.put("datei", stamm);
            if (!ok) res.put("grund", "nicht_gespeichert");
            HttpResponse.sendJson(out, ok ? 200 : 500, res.toString());
            return true;
        }
        // Mit Routenplaner-Konto verbinden (Geräte-Fluss, Planer-Vertrag §6): nur Login/lokal.
        if (pathOnly.equals("/api/planer/verbinden") && method.equals("POST")) {
            if (AuthMiddleware.isExternRequest()) return verboten(out);
            String geraet = "";
            try {
                geraet = new JSONObject(body == null || body.isEmpty() ? "{}" : body).optString("geraet", "");
            } catch (Exception ignored) {
            }
            JSONObject res = at.greenchili.surfmate.planer.PlanerVerbindung.get()
                    .starten(geraet.isEmpty() ? "Panoptes" : geraet, System.currentTimeMillis());
            int code = res.optBoolean("ok") ? 200 : res.optInt("http", 502);
            res.remove("http");
            HttpResponse.sendJson(out, code, res.toString());
            return true;
        }
        if (pathOnly.equals("/api/planer/verbindung") && method.equals("GET")) {
            if (AuthMiddleware.isExternRequest()) return verboten(out);
            JSONObject st = at.greenchili.surfmate.planer.PlanerVerbindung.get().status(System.currentTimeMillis());
            // Diagnose (0.1.9): läuft das Fahrtenbuch-Postfach? letzte Abfrage, HTTP-Status, an/aus, Grund
            st.put("fahrtenbuchPostfach", at.greenchili.surfmate.fahrtenbuch.FahrtenbuchPostfach.status());
            HttpResponse.sendJson(out, st.toString());
            return true;
        }
        if (pathOnly.equals("/api/planer/trennen") && method.equals("POST")) {
            if (AuthMiddleware.isExternRequest()) return verboten(out);
            JSONObject res = at.greenchili.surfmate.planer.PlanerVerbindung.get().trennen();
            HttpResponse.sendJson(out, res.optBoolean("ok") ? 200 : 500, res.toString());
            return true;
        }
        // Blitzer-Testschalter (nur Besitzer: Login/lokal, nie Kopplungs-JWT, nicht im Schema).
        if (pathOnly.equals("/api/test/blitzer") && method.equals("GET")) {
            if (AuthMiddleware.isExternRequest()) return verboten(out);
            HttpResponse.sendJson(out, at.greenchili.surfmate.blitzer.BlitzerDienst.testStatus(System.currentTimeMillis()).toString());
            return true;
        }
        if (pathOnly.equals("/api/test/blitzer") && (method.equals("PUT") || method.equals("POST"))) {
            if (AuthMiddleware.isExternRequest()) return verboten(out);
            boolean an = false;
            try {
                an = new JSONObject(body == null || body.isEmpty() ? "{}" : body).optBoolean("an", false);
            } catch (Exception ignored) {
            }
            long now = System.currentTimeMillis();
            boolean ok = at.greenchili.surfmate.blitzer.BlitzerDienst.testSetzen(an, now);
            JSONObject res = at.greenchili.surfmate.blitzer.BlitzerDienst.testStatus(now);
            res.put("ok", ok);
            HttpResponse.sendJson(out, ok ? 200 : 500, res.toString());
            return true;
        }
        if (pathOnly.equals("/api/planer/identitaet") && method.equals("GET")) {
            String id = autoId();
            JSONObject res = new JSONObject();
            if (id == null) {
                res.put("success", false);
                HttpResponse.sendJson(out, 500, res.toString());
            } else {
                res.put("success", true);
                res.put("autoId", id);
                HttpResponse.sendJson(out, res.toString());
            }
            return true;
        }
        return false;
    }

    private static boolean verboten(OutputStream out) throws Exception {
        HttpResponse.sendJson(out, 403, "{\"ok\":false,\"grund\":\"nur_mit_login\"}");
        return true;
    }

    /**
     * Feste Zufallskennung dieser Installation (planner.autoId). Wird einmal erzeugt und gespeichert;
     * stabil über Neustarts, nie aus VIN oder Geräte-ID abgeleitet.
     */
    public static synchronized String autoId() {
        try {
            JSONObject p = UnifiedConfigManager.forceReload().optJSONObject("planner");
            String id = p == null ? "" : p.optString("autoId", "");
            if (gueltigeAutoId(id)) return id;
            String neu = neueAutoId();
            java.util.Map<String, Object> v = new java.util.HashMap<>();
            v.put("autoId", neu);
            if (!UnifiedConfigManager.updateValues("planner", v)) return null;
            JSONObject check = UnifiedConfigManager.forceReload().optJSONObject("planner");
            String gespeichert = check == null ? "" : check.optString("autoId", "");
            return neu.equals(gespeichert) ? neu : null;
        } catch (Exception e) {
            return null;
        }
    }

    static boolean gueltigeAutoId(String id) {
        return id != null && id.matches("^a-[0-9a-f]{16}$");
    }

    static String neueAutoId() {
        byte[] b = new byte[8];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder("a-");
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static boolean schreibe(String abschnitt, Map<String, Object> werte) {
        try {
            return UnifiedConfigManager.updateValues(abschnitt, werte);
        } catch (Exception e) {
            return false;
        }
    }

    /** Nachwirkungen gespeicherter Werte (keine Befehle ans Auto). */
    private static void nachwirkungen(Einstellungen.Ergebnis e) {
        if (e.gespeichert.has("deterrent.light") || e.gespeichert.has("deterrent.horn")) {
            try {
                at.greenchili.surfmate.byd.cloud.BydCloudDeterrent.getInstance().reset();
            } catch (Throwable ignored) {
            }
        }
    }

    static Katalog.Kontext kontext(JSONObject root) {
        Katalog.Kontext k = new Katalog.Kontext();
        k.land = Laenderprofil.aktuell();
        k.akkuChemie = AkkuChemie.aktuell(root);
        try {
            JSONObject caps = VehicleCapabilities.get();
            k.cluster = caps.optBoolean("cluster", false);
            k.hud = caps.optBoolean("hud", false);
        } catch (Throwable ignored) {
        }
        try {
            // Nur wenn der HAL die Rate nachweislich nicht setzen kann: höhere Stufen ehrlich sperren
            JSONObject f = at.greenchili.surfmate.camera.KameraFpsStatus.alsJson();
            // (HAL lehnt ab UND liefert schon die aktuelle Anforderung nicht, z. B. DiLink 4 fest ~4,5 fps)
            double geliefert = f.optDouble("geliefert", -1);
            int angefordert = f.optInt("angefordert", 0);
            if (Boolean.FALSE.equals(f.opt("halSetzbar")) && geliefert > 0 && angefordert > 0
                    && geliefert < angefordert * 0.8) {
                k.kameraHalMaxFps = at.greenchili.surfmate.camera.KameraFpsStatus.halMaxFps();
            }
        } catch (Throwable ignored) {
        }
        return k;
    }

    static String sprache(String query) {
        for (String p : query.split("&")) {
            if (p.startsWith("sprache=")) {
                String l = p.substring(8).trim().toLowerCase(java.util.Locale.ROOT);
                if (LocaleManager.isSupported(l)) return l;
            }
        }
        return LocaleManager.get();
    }

    private static Einstellungen.Texte texte(String lang) {
        return new Einstellungen.Texte() {
            public String text(String key) {
                String s = Messages.get(key);
                if (s != null && !s.equals(key)) return s;
                String w = webText(lang, key);
                return w != null ? w : "";
            }
            public JSONObject unter(String praefix) {
                JSONObject w = webUnter(lang, praefix);
                return w.length() > 0 ? w : serverUnter(lang, praefix);
            }
        };
    }

    // ---------------------------------------------------------------- Web-Texte (guardLegal.* u. a.)

    private static JSONObject webKatalog(String lang) {
        String base = at.greenchili.surfmate.util.DaemonStorage.rebase(
                "/data/user_de/0/com.android.shell/surfmate/web/i18n/");
        for (String l : new String[] {lang, "de", "en"}) {
            try {
                File f = new File(base + l + ".json");
                if (f.isFile()) return new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static Object walk(JSONObject cat, String key) {
        if (cat == null) return null;
        if (cat.has(key)) return cat.opt(key);
        Object cur = cat;
        for (String part : key.split("\\.")) {
            if (!(cur instanceof JSONObject)) return null;
            cur = ((JSONObject) cur).opt(part);
            if (cur == null) return null;
        }
        return cur;
    }

    private static String webText(String lang, String key) {
        Object o = walk(webKatalog(lang), key);
        return o instanceof String ? (String) o : null;
    }

    /** Rückfall für Bestätigungstexte aus server-i18n (gleiches Format wie der Web-Katalog). */
    private static JSONObject serverUnter(String lang, String praefix) {
        JSONObject res = new JSONObject();
        String base = at.greenchili.surfmate.util.DaemonStorage.rebase(
                "/data/user_de/0/com.android.shell/surfmate/web/server-i18n/");
        for (String l : new String[] {lang, "de", "en"}) {
            try {
                File f = new File(base + l + ".json");
                if (!f.isFile()) continue;
                Object nested = walk(new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)), praefix);
                if (nested instanceof JSONObject && ((JSONObject) nested).length() > 0) {
                    flatten((JSONObject) nested, "", res);
                    return res;
                }
            } catch (Exception ignored) {
            }
        }
        return res;
    }

    private static JSONObject webUnter(String lang, String praefix) {
        JSONObject res = new JSONObject();
        JSONObject cat = webKatalog(lang);
        if (cat == null) return res;
        try {
            Object nested = walk(cat, praefix);
            if (nested instanceof JSONObject) flatten((JSONObject) nested, "", res);
            java.util.Iterator<String> it = cat.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (k.startsWith(praefix + ".") && cat.opt(k) instanceof String) {
                    res.put(k.substring(praefix.length() + 1), cat.optString(k));
                }
            }
        } catch (Exception ignored) {
        }
        return res;
    }

    private static void flatten(JSONObject o, String prefix, JSONObject out) throws Exception {
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            Object v = o.opt(k);
            String key = prefix.isEmpty() ? k : prefix + "." + k;
            if (v instanceof JSONObject) flatten((JSONObject) v, key, out);
            else if (v instanceof String) out.put(key, v);
        }
    }
}
