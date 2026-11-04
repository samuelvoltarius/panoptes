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
            "/api/telegram-anmeldung",   // 0.1.21: eine Web-Sitzung erlaubt keine weiteren Anmeldungen
            "/api/cloud-uebergabe",   // 0.1.21: Erlauben/Ablehnen der BYD-Cloud-Übergabe nur am Auto bzw. mit Login
            "/api/kopplung/weiteres-geraet",   // 0.1.25: Erlauben/Ablehnen nur am Auto bzw. mit Login
            "/api/abrp/token",
            "/api/backup/",
            "/api/system/tailscale", "/api/system/zugriffe", "/api/tailscale/", "/api/wireguard", "/api/notzugang", "/api/hotspot",
            "/api/bruecke", "/api/dev-view/",
            "/api/appupdate", "/api/update/",
            "/api/handy/",
            "/api/dateien",   // Dateien Handy ↔ Auto: nur mit Recht „dateien“ der Kopplung dahinter (dateienErlaubt)
            "/api/display",   // 0.1.37 Hintergrundbild und Display-Sperre: nur am Auto oder mit vollem Login
            "/api/apps/verwaltung",   // 0.1.37 Apps deaktivieren/aktivieren: nur am Auto oder mit vollem Login
            "/api/tacho/navi/fahrt",   // 0.1.37 „auch beim Fahren“: nur am Auto oder mit vollem Login
            "/api/tacho/auswahl",   // 0.1.37 „Was zeigt der Tacho?“: nur mit Recht „tacho“ der Kopplung (tachoErlaubt)
            "/api/launcher/v1/layout",   // 0.1.38 Launcher-Layout: nur mit Recht „launcher“ (launcherErlaubt)
            "/api/ha/geraete",   // 0.1.39 HA-Geräte (Namen, Räume privat): nur am Auto oder mit vollem Login
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
            "notzugang", "wireguard", "tailscale", "bruecke", "abrp", "ha", "genAi", "hotspot", "nativeShell",
            // 0.1.38.8 Hupe/Licht/Aufsperren-Schalter: einschalten nur am Auto oder mit Login, aus geht übers Handy
            "fahrzeugAktionen"};

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

    /**
     * 0.1.26.1 „derselbe Knopf in der Web-UI“: Update-Stand lesen, suchen und starten, wenn die Kopplung hinter der
     * Web-Sitzung das Recht „update“ hat. Die Regeln (nur freigegebene Version, Bestätigung, geparkt, Telegram) prüft
     * {@link AppUpdateApiHandler} über {@code FernUpdate}. Einstellungen des Updaters bleiben gesperrt.
     */
    public static boolean updateErlaubt(String method, String pathOnly, boolean rechtUpdate) {
        if (pathOnly == null) return false;
        String p = pathOnly;
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        // 0.1.38.2 Panoptes und Launcher (UPDATE_API_CLIENTS.md): Stand lesen immer, Starten nur mit Recht „update“
        if (AppUpdateApiHandler.FERN_PFAD.equals(p)) return "GET".equals(method) || ("POST".equals(method) && rechtUpdate);
        if (!rechtUpdate) return false;
        if ("GET".equals(method)) return "/api/appupdate/status".equals(p);
        return "POST".equals(method) && ("/api/appupdate/installieren".equals(p) || "/api/appupdate/suchen".equals(p));
    }

    /**
     * Dateien Handy ↔ Auto: die Web-Sitzung darf {@code /api/dateien} nur, wenn die Kopplung dahinter das Recht
     * {@code dateien} hat (Standard aus, nie automatisch). Pfad-Tricks bleiben zu.
     */
    /** 0.1.37: „Was zeigt der Tacho?“ in der Web-Sitzung nur mit Recht {@code tacho} der Kopplung dahinter. */
    public static boolean tachoErlaubt(String pathOnly, boolean rechtTacho) {
        if (!rechtTacho || pathOnly == null) return false;
        String p = pathOnly;
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        return p.equals("/api/tacho/auswahl");
    }

    public static boolean dateienErlaubt(String pathOnly, boolean rechtDateien) {
        if (!rechtDateien || pathOnly == null) return false;
        String p = pathOnly;
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        if (p.contains("..") || p.contains("//")) return false;
        return p.equals(DateienApiHandler.PFAD) || p.startsWith(DateienApiHandler.PFAD + "/");
    }

    /**
     * 0.1.38 Launcher-Layout: die Web-Sitzung darf die Layout-Pfade nur, wenn die Kopplung dahinter das Recht
     * {@code launcher} hat (Standard aus, nie automatisch). Genau die Pfade und Methoden des ExternGuard.
     */
    public static boolean launcherErlaubt(String method, String pathOnly, boolean rechtLauncher) {
        return rechtLauncher && ExternGuard.launcherErlaubt(method, pathOnly, "handy");
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
        // 0.1.38.7 Sicherheitsfix: außen Sicht-/Hörbares nie aus der Web-Sitzung der Handy-App
        return !"GET".equals(method) && aussenAusloeser(p);
    }

    /**
     * 0.1.38.7 Sicherheitsfix (Befund 04.11.2026): Hupe, Licht, Warnblinker, „Auto finden“ und Aufsperren löst die
     * Web-Sitzung der Handy-App nie aus – weder direkt noch über die Test-/Auslöse-Wege, hinter denen dieselben Befehle
     * liegen können (Taste testen, Schnellknopf, Regel testen, Aktionsgruppe, Licht-Diagnose). Display und voller Login
     * bleiben wie bisher; Kopplungen erreichen {@code /api/vehicle/*} ohnehin nicht (ExternGuard-Erlaubnisliste).
     */
    /**
     * 0.1.38.8 Paket B: Die Web-Sitzung darf genau die drei direkten Knöpfe (Auto finden, Lichthupe, Warnblinker) per
     * POST, wenn die Kopplung dahinter das Recht {@code signal} hat. Schalter, Rückfrage, Ratenlimit und Meldung prüft
     * danach {@code VehicleControlApiHandler.aktionWeb} über {@code FahrzeugAktionen}. Alle anderen Auslöse-Wege
     * ({@link #AUSSEN_AUSLOESER_PRAEFIX}, Taste, BYD-Cloud-Test, Aufsperren) bleiben für die Web-Sitzung zu.
     */
    public static boolean signalErlaubt(String method, String pathOnly, boolean rechtSignal) {
        if (!rechtSignal || !"POST".equals(method) || pathOnly == null) return false;
        String p = pathOnly;
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        return p.equals("/api/vehicle/flash") || p.equals("/api/vehicle/find-car") || p.equals("/api/vehicle/warnblinker");
    }

    /**
     * 0.1.39 PC über die Brücke (E2E 04.11.): Die Web-Sitzung eines PCs darf schreibend NUR diese Pfade – eine
     * Erlaubnisliste wie {@code ExternGuard}. Keine Fahrzeug-Aktion (Zusperren, Kofferraum, Klima, Fenster, Ton …), kein
     * Auslöser, kein Wächter-Schalten, keine Roh-Konfiguration ({@code /api/settings/unified}); Einstellungen nur über den
     * Katalog ({@code /api/einstellungen}, dort höchstens Stufe KOPPLUNG). Lesen (GET/HEAD) bleibt wie in Stufe B.
     */
    public static final Set<String> PC_WEB_SCHREIBEN = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "/api/einstellungen",
            "/api/sitzung/erneuern",
            "/api/sitzung/abmelden",
            "/api/laden",
            "/api/laden/nachfrage",
            "/api/laden/nachfrage/spaeter",
            "/api/laden/nachfrage/kein-preis",
            "/api/gewohnheiten/antwort")));

    /** 0.1.39: Anfrage eines PCs (Web-Sitzung oder Kopplungs-JWT) außerhalb von {@link #PC_WEB_SCHREIBEN}? */
    public static boolean pcBlocks(String method, String pathOnly) {
        if (method == null || pathOnly == null) return true;
        if ("GET".equals(method) || "HEAD".equals(method)) return false;
        String p = pathOnly;
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        if (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        if (p.contains("..") || p.contains("//")) return true;
        return !PC_WEB_SCHREIBEN.contains(p);
    }

    static boolean aussenAusloeser(String p) {
        if (p == null) return true;
        for (String a : AUSSEN_AUSLOESER) if (p.equals(a)) return true;
        for (String a : AUSSEN_AUSLOESER_PRAEFIX) if (p.startsWith(a)) return true;
        return p.startsWith("/api/action-groups/") && p.endsWith("/run");
    }

    static final String[] AUSSEN_AUSLOESER = {
            "/api/vehicle/flash", "/api/vehicle/find-car", "/api/vehicle/unlock", "/api/vehicle/hazard",
            "/api/vehicle/warnblinker",
            "/api/bydcloud/test", "/api/keymap/fire"};
    static final String[] AUSSEN_AUSLOESER_PRAEFIX = {
            "/api/quick-controls/fire/", "/api/automations/test/", "/api/debug/light/"};
}
