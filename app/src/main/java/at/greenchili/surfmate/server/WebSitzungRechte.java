package at.greenchili.surfmate.server;

import at.greenchili.surfmate.auth.Kopplungen;
import at.greenchili.surfmate.handy.HandySignatur;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 0.1.38.10 (Koordination 05.11., ENTWURF_WEBSITZUNG_RECHTE): Die Web-Sitzung einer Handy-Kopplung (scope
 * {@code handyweb}) darf an Fahrzeug-Wegen genau, was die Kopplung dahinter darf – eine Quelle für die Prüfung im Kern,
 * die Handy-App ({@link #fuerHandyAktion}) und das Ausblenden in der Oberfläche ({@code GET /api/sitzung/rechte}).
 *
 * <ul>
 *   <li>Login am Display oder im Browser (kein scope) bleibt voll; diese Klasse greift dort nie.</li>
 *   <li>Schreibend unter {@link #BEREICHE} ist eine <b>Erlaubnisliste</b>: ein Pfad, der in {@link #PFADE} fehlt, ist
 *       für die Web-Sitzung zu, bis er bewusst eingeordnet ist.</li>
 *   <li>Fenster, Fahrzeug-Einstellungen und System nie über die Web-Sitzung ({@link #NIE}).</li>
 *   <li>Außen Sicht- oder Hörbares ({@link #AUSSEN}) zusätzlich nur mit Rückfrage ({@code bestaetigt:true}) und
 *       Eintrag im Befehls-Log (REGEL A+); Ausschalten geht ohne.</li>
 *   <li>Kofferraum nur mit {@code aufsperren} und frischer Passkey-Bestätigung ({@link #PASSKEY}).</li>
 * </ul>
 * Ein PC bleibt strenger bei {@link WebHandyGuard#PC_WEB_SCHREIBEN}.
 */
public final class WebSitzungRechte {

    private WebSitzungRechte() {}

    /** Nie über die Web-Sitzung, nur am Display oder mit Login. */
    public static final String NIE = "nie";
    /** Ohne eigenes Recht (Stoppen ist sicher). */
    public static final String FREI = "frei";

    /** Bereiche, deren schreibende Pfade diese Tabelle regelt (Präfix bzw. genauer Pfad). */
    static final String[] BEREICHE = {
            "/api/vehicle", "/api/camping", "/api/navi",
            "/api/system/ivi-reboot", "/api/system/background-access",
            "/api/audio/engine-sound", "/api/audio/avas-tone"};

    /** Schreibender Pfad → nötiges Recht, {@link #NIE} oder {@link #FREI}. */
    static final Map<String, String> PFADE;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("/api/vehicle/lock", Kopplungen.RECHT_ZUSPERREN);
        for (String p : new String[]{"climate", "climate-schedule", "seat", "battery-heat"}) {
            m.put("/api/vehicle/" + p, Kopplungen.RECHT_KLIMA);
        }
        m.put("/api/vehicle/trunk", Kopplungen.RECHT_AUFSPERREN);
        m.put("/api/vehicle/window", NIE);
        for (String p : new String[]{"flash", "find-car", "warnblinker", "lights", "media", "play-audio", "speak", "message"}) {
            m.put("/api/vehicle/" + p, Kopplungen.RECHT_SIGNAL);
        }
        m.put("/api/vehicle/stop-audio", FREI);
        for (String p : new String[]{"start-charging", "charging-schedule", "charge-cap", "ac-charge-current-limit"}) {
            m.put("/api/vehicle/" + p, Kopplungen.RECHT_LADEN);
        }
        m.put("/api/vehicle/setting", NIE);
        m.put("/api/vehicle/adas", NIE);
        m.put("/api/vehicle/system", NIE);
        for (String p : new String[]{"cluster-cast", "cluster-stop", "cluster-resize", "cluster-window", "cluster-mirror",
                "cluster-touch"}) {
            m.put("/api/vehicle/" + p, Kopplungen.RECHT_TACHO);
        }
        m.put("/api/system/ivi-reboot", NIE);
        m.put("/api/system/background-access", NIE);
        m.put("/api/audio/engine-sound", Kopplungen.RECHT_SIGNAL);
        m.put("/api/audio/avas-tone", Kopplungen.RECHT_SIGNAL);
        for (String p : new String[]{"", "/plan", "/weiter", "/rueckmeldung"}) m.put("/api/camping" + p, Kopplungen.RECHT_KLIMA);
        for (String p : new String[]{"unterwegs/ablehnen", "planer-route/annehmen", "planer-route/ablehnen", "link", "orte",
                "plan", "umkreis"}) {
            m.put("/api/navi/" + p, Kopplungen.RECHT_ROUTE);
        }
        // 0.1.38.10 Integration B: Panoptes-Führung (G2) nur am Display oder mit Login, nie aus der Web-Sitzung
        m.put("/api/navi/fuehrung", NIE);
        // 0.1.44 (P3b): das Tempolimit der Führung meldet nur die App am Auto selbst, nie eine Web-Sitzung
        m.put("/api/navi/tempolimit", NIE);
        PFADE = Collections.unmodifiableMap(m);
    }

    /** Außen sicht- oder hörbar: Rückfrage im Moment (REGEL A+). Hupe/Blinker/Warnblinker prüft FahrzeugAktionen. */
    static final Set<String> AUSSEN = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "/api/vehicle/lights", "/api/audio/engine-sound", "/api/audio/avas-tone")));

    /** Nur mit frischer Passkey-Bestätigung (wie das Web-Aufsperren 0.1.33). */
    static final Set<String> PASSKEY = Collections.singleton("/api/vehicle/trunk");

    /** Handy-App ({@code /api/handy/v1/aktion}): Aktion → nötiges Recht; unbekannt = null. */
    static final Map<String, String> HANDY_AKTIONEN;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        for (String a : new String[]{"klima_an", "klima_aus", "sitzheizung", "akku_heizen", "heizen_stoppen", "tiermodus",
                "tiermodus_stand"}) {
            m.put(a, Kopplungen.RECHT_KLIMA);
        }
        m.put("zusperren", Kopplungen.RECHT_ZUSPERREN);
        m.put("aufsperren", Kopplungen.RECHT_AUFSPERREN);
        for (String a : new String[]{"auto_finden", "licht", "warnblinker"}) m.put(a, Kopplungen.RECHT_SIGNAL);
        m.put("wallbox", Kopplungen.RECHT_WALLBOX);
        for (String a : new String[]{"route", "route_stand", "plan_vorschau", "plan_vorschau_stand"}) {
            m.put(a, Kopplungen.RECHT_ROUTE);
        }
        m.put("update_starten", Kopplungen.RECHT_UPDATE);
        HANDY_AKTIONEN = Collections.unmodifiableMap(m);
    }

    /** Signierter Zweck der Kofferraum-Bestätigung (eigener Zweck, nie als Aufsperren verwendbar). */
    public static final String KOFFERRAUM = "kofferraum";

    public static String fuerHandyAktion(String aktion) {
        return aktion == null ? null : HANDY_AKTIONEN.get(aktion);
    }

    static String reinerPfad(String pathOnly) {
        if (pathOnly == null) return null;
        String p = pathOnly;
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        if (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }

    static boolean lesend(String method) {
        return "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
    }

    /** Regelt diese Tabelle den Pfad? */
    public static boolean geregelt(String pathOnly) {
        String p = reinerPfad(pathOnly);
        if (p == null) return false;
        for (String b : BEREICHE) {
            if (b.endsWith("/") ? p.startsWith(b) : (p.equals(b) || p.startsWith(b + "/"))) return true;
        }
        return false;
    }

    /**
     * Nötiges Recht für eine Anfrage der Web-Sitzung: null = diese Tabelle regelt sie nicht (lesend oder anderer
     * Bereich), sonst ein Recht, {@link #NIE} oder {@link #FREI}. Nicht eingeordnete Pfade im Bereich: {@link #NIE}.
     */
    public static String noetig(String method, String pathOnly) {
        if (lesend(method) || !geregelt(pathOnly)) return null;
        String p = reinerPfad(pathOnly);
        if (p.contains("..") || p.contains("//")) return NIE;
        String r = PFADE.get(p);
        return r == null ? NIE : r;
    }

    /** Prüf-Umgebung (Kofferraum-Passkey); im Test ersetzbar. */
    interface Passkey {
        /** null = gültig (Nonce verbraucht, Zähler gespeichert), sonst Grund. */
        String pruefe(Kopplungen k, String kid, JSONObject webauthn, long jetzt);
    }

    static Passkey passkey = (k, kid, w, jetzt) -> {
        if (!k.istPasskey(kid)) return HandySignatur.GRUND_KEIN_GERAET;
        HandySignatur.Ergebnis e = HandyApiHandler.SIGNATUR.pruefeWebauthn(KOFFERRAUM, k.schluessel(kid),
                k.credentialId(kid), k.zaehler(kid), kid, w.optLong("zeit", -1), w.optString("nonce", ""),
                w.optString("credentialId", ""), w.optString("clientDataJSON", ""), w.optString("authenticatorData", ""),
                w.optString("signature", ""), k.anzeigeName(kid), jetzt);
        if (e.grund != null) return e.grund;
        if (e.zaehler > 0 && !k.zaehlerSetzen(kid, e.zaehler)) return "speichern";
        return null;
    };

    /**
     * Prüft eine Anfrage der Web-Sitzung (nicht PC). null = darf weiter; sonst die Antwort samt {@code status}
     * (403 Recht fehlt / nie, 409 Rückfrage nötig) – {@code status} entfernt der Aufrufer vor dem Senden.
     */
    public static JSONObject pruefe(String method, String pathOnly, String body, String kid, Kopplungen k, long jetzt) {
        String noetig = noetig(method, pathOnly);
        if (noetig == null || FREI.equals(noetig)) return null;
        String p = reinerPfad(pathOnly);
        try {
            if (NIE.equals(noetig)) {
                return new JSONObject().put("status", 403).put("ok", false).put("grund", "nie_web")
                        .put("text", Messages.text("websitzung.nie_web",
                                "Das geht nur am Autobildschirm oder mit Login, nicht in der Weboberfläche der App."));
            }
            if (kid == null || !k.hatRecht(kid, noetig)) return rechtFehlt(noetig);
            JSONObject req = null;
            try {
                req = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
            } catch (Exception ignored) {
                req = new JSONObject();
            }
            if (PASSKEY.contains(p)) {
                JSONObject w = req.optJSONObject("webauthn");
                String g = w == null ? "passkey_noetig" : passkey.pruefe(k, kid, w, jetzt);
                if (g != null) {
                    return new JSONObject().put("status", 403).put("ok", false).put("grund", "passkey_noetig")
                            .put("detail", g).put("text", Messages.text("websitzung.passkey_noetig",
                                    "Den Kofferraum öffnet die Weboberfläche nur mit dem Recht „Aufsperren“ und einer frischen Bestätigung mit Fingerabdruck oder Face ID."));
                }
                BefehlsLog.ereignis("kofferraum_passkey", null, null, null);   // Protokoll: mit Passkey bestätigt
            }
            if (AUSSEN.contains(p) && !aus(p, req)) {
                if (!req.optBoolean("bestaetigt", false)) {
                    return new JSONObject().put("status", 409).put("ok", false).put("grund", "aktion_bestaetigen")
                            .put("frage", Messages.text("websitzung.rueckfrage",
                                    "Das ist außen am Auto zu sehen oder zu hören. Jetzt auslösen?"));
                }
                BefehlsLog.ereignis("aussen_bestaetigt", null, null, null);   // Protokoll (REGEL A+)
            }
            return null;
        } catch (Exception e) {
            return new JSONObject();   // nie offen: leere Antwort heißt gesperrt
        }
    }

    /** Ausschalten braucht keine Rückfrage (Stoppen ist sicher). */
    static boolean aus(String p, JSONObject req) {
        if ("/api/audio/avas-tone".equals(p)) return req.optBoolean("stop", false);
        if ("/api/vehicle/lights".equals(p)) {
            return "dayTimeLight".equals(req.optString("target")) && req.has("enable") && !req.optBoolean("enable", true);
        }
        return false;
    }

    static JSONObject rechtFehlt(String recht) throws Exception {
        String wort = Messages.text("websitzung.recht_wort." + recht, recht);
        return new JSONObject().put("status", 403).put("ok", false).put("grund", "recht_fehlt").put("recht", recht)
                .put("text", Messages.text("websitzung.recht_fehlt",
                        "Dafür fehlt diesem Handy das Recht „{0}“. Einschalten: am Auto unter Gekoppelte Geräte.", wort));
    }

    /** Antwort senden (Status aus dem Objekt). */
    public static void sende(java.io.OutputStream out, JSONObject antwort) throws Exception {
        int status = antwort.optInt("status", 403);
        antwort.remove("status");
        if (!antwort.has("ok")) antwort.put("ok", false).put("grund", "gesperrt");
        HttpResponse.sendJson(out, status, antwort.toString());
    }

    /**
     * {@code GET /api/sitzung/rechte}: Login → {@code {web:false}}; Web-Sitzung → Art, Rechte und die geregelten Pfade,
     * die sie nicht nutzen darf ({@code gesperrt}); dazu {@code rueckfrage} und {@code passkey}. Die Oberfläche blendet
     * danach aus; der Kern prüft trotzdem jede Anfrage.
     */
    public static JSONObject stand(boolean web, String kid, Kopplungen k) throws Exception {
        JSONObject o = new JSONObject().put("ok", true).put("web", web);
        if (!web) return o;
        boolean pc = kid != null && k.istPc(kid);
        List<String> rechte = kid == null ? Collections.<String>emptyList() : k.rechte(kid);
        JSONArray gesperrt = new JSONArray();
        for (Map.Entry<String, String> e : PFADE.entrySet()) {
            String r = e.getValue();
            boolean zu = pc ? WebHandyGuard.pcBlocks("POST", e.getKey())
                    : NIE.equals(r) || (!FREI.equals(r) && (kid == null || !k.hatRecht(kid, r)))
                    // Kofferraum: die Oberfläche kann den Passkey noch nicht liefern, also Knopf weg (Kern prüft trotzdem)
                    || PASSKEY.contains(e.getKey());
            if (zu) gesperrt.put(e.getKey());
        }
        return o.put("art", kid == null ? JSONObject.NULL : (Object) (k.art(kid) == null ? "" : k.art(kid)))
                .put("rechte", new JSONArray(rechte)).put("gesperrt", gesperrt)
                .put("rueckfrage", new JSONArray(AUSSEN)).put("passkey", new JSONArray(PASSKEY));
    }
}
