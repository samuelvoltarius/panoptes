package at.greenchili.surfmate.server;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 0.1.20 Web-Sitzung der Handy-App (scope {@code handyweb}), Stufe B (Koordination 27.10.): alles wie mit PIN, außer
 * Sicherheits-Einstellungen. Gesperrt, in jeder Methode (auch lesen, weil dort Geheimnisse stehen):
 * <ul>
 *   <li>PIN/Anmeldung und Einrichtung, Kopplungen verwalten</li>
 *   <li>Telegram-Token, Fremd-Zugangsdaten (BYD-Cloud, ABRP), Sicherung (enthält Zugangsdaten)</li>
 *   <li>Netz: Tailscale, WireGuard, Notzugang, Hotspot, Brücke, Fernansicht</li>
 *   <li>Updates</li>
 *   <li>{@code /api/settings/unified}: geheime Abschnitte weder lesen noch schreiben ({@link #GEHEIME_ABSCHNITTE})</li>
 * </ul>
 * Aufsperren gibt es im Web-UI nicht; die Handy-Pfade ({@code /api/handy/v1/*}) brauchen den Kopplungs-JWT.
 */
public final class WebHandyGuard {

    private WebHandyGuard() {}

    /** Ganze Bereiche (Präfix, jede Methode). */
    static final String[] GESPERRT_PRAEFIX = {
            "/api/kopplung", "/api/kopplungen",
            "/api/einrichtung",
            "/api/telegram/token", "/api/telegram/owner", "/api/telegram/clear", "/api/telegram/pin",
            "/api/bydcloud/setup", "/api/bydcloud/clear", "/api/bydcloud/settings",
            "/api/abrp/token",
            "/api/backup/",
            "/api/system/tailscale", "/api/tailscale/", "/api/wireguard", "/api/notzugang", "/api/hotspot",
            "/api/bruecke", "/api/dev-view/",
            "/api/appupdate", "/api/update/",
            "/api/handy/",
    };

    /** Seiten, die nur Sicherheits-Funktionen tragen. */
    static final Set<String> GESPERRTE_SEITEN = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "/wireguard", "/wireguard.html", "/remote-dev-view", "/remote-dev-view.html")));

    /**
     * Abschnitte der Gesamt-Konfiguration, die die Web-Sitzung weder lesen ({@code GET /api/settings/unified}) noch
     * schreiben darf: JWT-Schlüssel, Kopplungen, Tokens, Zugangsdaten, Netz, Updates, Einrichtung.
     */
    public static final String[] GEHEIME_ABSCHNITTE = {
            "auth", "kopplungen", "update", "telegram", "bydCloud", "daemons", "einrichtung", "planner",
            "notzugang", "wireguard", "tailscale", "bruecke", "abrp", "ha", "genAi", "hotspot", "nativeShell"};

    /** POST/PUT {@code /api/settings/unified} mit einem geheimen Abschnitt. */
    public static boolean blocksEinstellungsAbschnitt(String method, String pathOnly, String body) {
        if (!"/api/settings/unified".equals(pathOnly) || "GET".equals(method)) return false;
        try {
            String a = new org.json.JSONObject(body == null || body.isEmpty() ? "{}" : body).optString("section", "");
            for (String g : GEHEIME_ABSCHNITTE) if (g.equalsIgnoreCase(a)) return true;
            return a.isEmpty();
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * {@code POST /api/einstellungen}: Geheimnisse (Typ GEHEIM) und Update-Felder sind für die Web-Sitzung nur lesend.
     * Entfernt sie aus {@code req.werte} und liefert ihre Schlüssel (Fehler „nurAutoOderPin“).
     */
    public static java.util.List<String> gesperrteFelder(java.util.List<at.greenchili.surfmate.einstellungen.Feld> felder,
                                                         org.json.JSONObject req) {
        java.util.List<String> r = new java.util.ArrayList<>();
        org.json.JSONObject werte = req == null ? null : req.optJSONObject("werte");
        if (werte == null) return r;
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (java.util.Iterator<String> it = werte.keys(); it.hasNext(); ) keys.add(it.next());
        for (String key : keys) {
            at.greenchili.surfmate.einstellungen.Feld f = at.greenchili.surfmate.einstellungen.Katalog.finde(felder, key);
            boolean geheim = f != null && f.typ == at.greenchili.surfmate.einstellungen.Feld.Typ.GEHEIM;
            String abschnitt = key.contains(".") ? key.substring(0, key.indexOf('.')) : key;
            boolean sicher = false;
            for (String g : GEHEIME_ABSCHNITTE) if (g.equals(abschnitt)) sicher = true;
            if (geheim || sicher) {
                werte.remove(key);
                r.add(key);
            }
        }
        return r;
    }

    /** 403 mit dem Satz „Das geht nur am Auto oder mit PIN“ – als Seite oder als JSON. */
    public static void sendeGesperrt(java.io.OutputStream out, String pathOnly) throws Exception {
        String text = Messages.get("errors.only_car_or_pin");
        if (pathOnly != null && pathOnly.startsWith("/api/")) {
            HttpResponse.sendJson(out, 403, new org.json.JSONObject().put("ok", false).put("grund", "nur_auto_oder_pin")
                    .put("text", text).toString());
            return;
        }
        byte[] b = ("<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width\">"
                + "<p style=\"font:16px sans-serif;padding:24px\">" + text.replace("<", "&lt;") + "</p>").getBytes("UTF-8");
        out.write(("HTTP/1.1 403 Forbidden\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\n"
                + "Content-Length: " + b.length + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
        out.write(b);
        out.flush();
    }

    /** true, wenn die Web-Sitzung des Handys diesen Pfad nicht nutzen darf. */
    public static boolean blocks(String method, String pathOnly) {
        if (pathOnly == null) return true;
        String p = pathOnly;
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        if (p.contains("..") || p.contains("//")) return true;
        if (GESPERRTE_SEITEN.contains(p)) return true;
        for (String g : GESPERRT_PRAEFIX) {
            if (p.equals(g) || p.startsWith(g.endsWith("/") ? g : g + "/") || (g.endsWith("/") && p.equals(g.substring(0, g.length() - 1)))) {
                return true;
            }
        }
        return false;
    }
}
