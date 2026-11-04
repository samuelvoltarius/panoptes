package at.greenchili.surfmate.server;

import at.greenchili.surfmate.auth.Kopplungen;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 0.1.38.8 Besitzer-Handy (ENTWURF_BESITZER_01388, Koordination 04.11.): was das Handy mit Recht {@code besitzer}
 * zusätzlich darf, damit niemand zum Auto laufen muss. Nur Kopplungen der Art {@code handy} (ExternGuard), jeder
 * Schreibweg verlangt {@code bestaetigt:true} (die App fragt vorher per Biometrie). Jede Aktion: Befehls-Log + Meldung.
 * <ul>
 *   <li>{@code GET /api/handy/v1/kopplung/liste} – gekoppelte Geräte (wie Gekoppelte Geräte am Display, ohne Schlüssel)</li>
 *   <li>{@code POST /api/handy/v1/kopplung/code {art, rechte[], bestaetigt}} – Kopplungs-Code (10 min) mit fester Art
 *       und festen Rechten, nie {@code besitzer}</li>
 *   <li>{@code GET /api/handy/v1/kopplung/anfragen}, {@code POST …/anfragen/entscheiden {ref, erlauben, bestaetigt}}</li>
 *   <li>{@code POST /api/handy/v1/kopplung/entfernen {id, bestaetigt}} – nur fremde Kopplungen jünger als 24 h</li>
 *   <li>{@code POST /api/handy/v1/besitzer/weitergeben {id, bestaetigt}}, {@code …/abgeben {bestaetigt}}</li>
 *   <li>{@code POST /api/handy/v1/besitzer/rechte {rechte[], bestaetigt}} – eigene Rechte aus BESITZER_SELBST (Kamera,
 *       Standbild, Unfall-Clips, Protokoll, Dateien, Tacho, Launcher) für sich selbst, nie automatisch</li>
 *   <li>{@code POST /api/handy/v1/dienst {name: camera|acc|sentry, bestaetigt}} – nur Starten</li>
 *   <li>{@code POST /api/handy/v1/waechter {an, bestaetigt}} – wie Telegram {@code /waechter an|aus}</li>
 * </ul>
 * Nie hier: fremde Kopplungen (älter als 24 h) löschen, Rechte entziehen, Zugang (Tailscale, Notzugang, WireGuard,
 * Brücke), Updates-Quelle, Dienste stoppen. Fahrzeug-Aktionen bleiben bei ihren eigenen Rechten.
 */
public final class BesitzerApiHandler {

    private BesitzerApiHandler() {}

    public static final String LISTE = "/api/handy/v1/kopplung/liste", CODE = "/api/handy/v1/kopplung/code",
            ANFRAGEN = "/api/handy/v1/kopplung/anfragen", ENTSCHEIDEN = "/api/handy/v1/kopplung/anfragen/entscheiden",
            ENTFERNEN = "/api/handy/v1/kopplung/entfernen", WEITERGEBEN = "/api/handy/v1/besitzer/weitergeben",
            ABGEBEN = "/api/handy/v1/besitzer/abgeben", DIENST = "/api/handy/v1/dienst",
            SELBST = "/api/handy/v1/besitzer/rechte",
            WAECHTER = "/api/handy/v1/waechter";

    public static final java.util.Set<String> LESEN = java.util.Collections.unmodifiableSet(
            new java.util.HashSet<>(java.util.Arrays.asList(LISTE, ANFRAGEN)));
    public static final java.util.Set<String> SCHREIBEN = java.util.Collections.unmodifiableSet(
            new java.util.HashSet<>(java.util.Arrays.asList(CODE, ENTSCHEIDEN, ENTFERNEN, WEITERGEBEN, ABGEBEN, DIENST,
                    WAECHTER, SELBST)));

    /** Arten, die das Besitzer-Handy koppeln darf (web nie: das ist die Telegram-Anmeldung). */
    static final List<String> CODE_ARTEN = java.util.Collections.unmodifiableList(java.util.Arrays.asList(
            Kopplungen.ART_HANDY, Kopplungen.ART_PC, "cockpit", "homeassistant", Kopplungen.ART_DIENST));

    /** Höchstens so viele Schreib-Aktionen je Minute (Code, Entscheiden, Entfernen, Weitergeben …). */
    static final int MAX_JE_MINUTE = 10;
    private static final java.util.ArrayDeque<Long> LETZTE = new java.util.ArrayDeque<>();

    /** Wirkung nach außen (im Test ersetzbar). */
    interface Wirkung {
        default String code(long now, String art, List<String> rechte) {
            return at.greenchili.surfmate.auth.PairingCodes.createGebunden(now, art, rechte);
        }
        default JSONObject brueckeEinmal(long bis) {
            JSONObject o = new JSONObject();
            try {
                at.greenchili.surfmate.bruecke.Bruecke b = at.greenchili.surfmate.bruecke.Bruecke.get();
                if (b.ein()) {
                    String e = b.einmalAnlegen(bis);
                    if (e != null) o.put("brueckeEinmal", e);
                }
            } catch (Throwable ignored) { }
            return o;
        }
        default JSONArray anfragen(long now) {
            return at.greenchili.surfmate.auth.KopplungsAnfragen.get().offeneListe(now);
        }
        default boolean entscheiden(String ref, boolean erlauben, long now) {
            at.greenchili.surfmate.auth.KopplungsAnfragen.VOM_HANDY.set(Boolean.TRUE);
            try {
                return at.greenchili.surfmate.auth.KopplungsAnfragen.get().entscheiden(ref, erlauben, now)
                        == at.greenchili.surfmate.auth.KopplungsAnfragen.Entscheid.OK;
            } finally {
                at.greenchili.surfmate.auth.KopplungsAnfragen.VOM_HANDY.remove();
            }
        }
        default boolean dienst(String name) {
            return SystemApiHandler.kernDienstFreigeben(name);
        }
        default boolean waechter(boolean an) throws Exception {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            SurveillanceApiHandler.waechterSetzen(an, b);
            JSONObject r = HaApiHandler.antwortAus(b);
            return r != null && r.optBoolean("success", false);
        }
        default void melde(String text) {
            BesitzerMeldung.melde(text);
        }
    }

    static volatile Wirkung wirkung = new Wirkung() { };
    static volatile Kopplungen kopplungen;

    private static Kopplungen k() {
        Kopplungen x = kopplungen;
        return x != null ? x : Kopplungen.get();
    }

    /** true, wenn der Pfad hierher gehört (dann ist die Antwort geschrieben). */
    public static boolean handle(String method, String p, String body, String kid, OutputStream out) throws Exception {
        if (!LESEN.contains(p) && !SCHREIBEN.contains(p)) return false;
        JSONObject r = antwort(method, p, body, kid, System.currentTimeMillis());
        HttpResponse.sendJson(out, r.optInt("_status", 200), entferneStatus(r).toString());
        return true;
    }

    /** Ohne HTTP (Tests). Antwort mit optional {@code _status}. */
    static JSONObject antwort(String method, String p, String body, String kid, long now) throws Exception {
        Kopplungen k = k();
        if (kid == null || !k.istHandy(kid)) return fehler(403, "nur_handy");
        if (!k.istBesitzer(kid)) return fehler(403, "nur_besitzer");
        if (LESEN.contains(p)) {
            if (!"GET".equals(method)) return fehler(405, "nur_get");
            if (LISTE.equals(p)) return new JSONObject().put("ok", true).put("ich", kid).put("geraete", k.alle(now));
            return new JSONObject().put("ok", true).put("anfragen", wirkung.anfragen(now));
        }
        if (!"POST".equals(method)) return fehler(405, "nur_post");
        JSONObject req;
        try {
            req = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
        } catch (Exception e) {
            return fehler(400, "json");
        }
        if (!req.optBoolean("bestaetigt", false)) return fehler(400, "bestaetigung");
        if (zuOft(now)) return fehler(429, "zu_oft");
        String ich = k.anzeigeName(kid);
        switch (p) {
            case CODE: {
                String art = req.optString("art", "");
                if (!CODE_ARTEN.contains(art)) return fehler(400, "art");
                List<String> rechte = new ArrayList<>();
                JSONArray a = req.optJSONArray("rechte");
                List<String> erlaubt = Kopplungen.ART_HANDY.equals(art) ? Kopplungen.RECHTE : rechteDerArt(art);
                for (int i = 0; a != null && i < a.length(); i++) {
                    String x = a.optString(i);
                    // 0.1.38.8 Nachzug: Fahrzeug-Aktionen und geraete_steuern vergibt nur das Display
                    if (Kopplungen.NUR_DISPLAY_RECHTE.contains(x)) return fehler(400, "recht_nur_display");
                    if (Kopplungen.RECHT_BESITZER.equals(x) || !erlaubt.contains(x)) return fehler(400, "recht");
                    if (!rechte.contains(x)) rechte.add(x);
                }
                String code = wirkung.code(now, art, rechte);
                long bis = now + at.greenchili.surfmate.auth.PairingCodes.TTL_MS;
                JSONObject o = wirkung.brueckeEinmal(bis).put("ok", true).put("code", code).put("gueltigBis", bis)
                        .put("art", art).put("rechte", new JSONArray(rechte));
                BefehlsLog.ereignis("besitzer.kopplungscode." + art, "angelegt", kid, ich);
                wirkung.melde(Messages.text("besitzer.code_angelegt",
                        "Kopplungs-Code ({0}) erstellt vom Besitzer-Handy {1}, 10 min gültig.", art, ich));
                return o;
            }
            case ENTSCHEIDEN: {
                String ref = req.optString("ref", "");
                boolean erlauben = req.optBoolean("erlauben", false);
                boolean ok = wirkung.entscheiden(ref, erlauben, now);
                BefehlsLog.ereignis("besitzer.anfrage." + (erlauben ? "erlaubt" : "abgelehnt"), ok ? "ok" : "fehler", kid, ich);
                if (ok && erlauben) wirkung.melde(Messages.text("besitzer.anfrage_erlaubt",
                        "Neues Gerät erlaubt vom Besitzer-Handy {0}.", ich));
                return ok ? new JSONObject().put("ok", true) : fehler(409, "unbekannt");
            }
            case ENTFERNEN: {
                String ziel = req.optString("id", "");
                String name = k.anzeigeName(ziel);
                int e = k.jungEntfernen(kid, ziel, now);
                if (e != Kopplungen.BESITZER_OK) {
                    return fehler(e == Kopplungen.BESITZER_UNBEKANNT ? 404 : 403,
                            e == Kopplungen.BESITZER_UNBEKANNT ? "unbekannt" : "nur_neue");
                }
                wirkung.melde(Messages.text("besitzer.entfernt", "Gerät {0} entfernt vom Besitzer-Handy {1}.", name, ich));
                return new JSONObject().put("ok", true);
            }
            case WEITERGEBEN: {
                String ziel = req.optString("id", "");
                int e = k.besitzerWeitergeben(kid, ziel);
                if (e != Kopplungen.BESITZER_OK) return fehler(e == Kopplungen.BESITZER_UNBEKANNT ? 404 : 500,
                        e == Kopplungen.BESITZER_UNBEKANNT ? "unbekannt" : "speichern");
                wirkung.melde(Messages.text("besitzer.weitergegeben", "{0} ist jetzt auch Besitzer-Handy (von {1}).",
                        k.anzeigeName(ziel), ich));
                return new JSONObject().put("ok", true);
            }
            case ABGEBEN: {
                int e = k.besitzerAbgeben(kid);
                if (e == Kopplungen.BESITZER_LETZTER) return fehler(409, "letzter_besitzer");
                if (e != Kopplungen.BESITZER_OK) return fehler(500, "speichern");
                wirkung.melde(Messages.text("besitzer.abgegeben", "{0} ist nicht mehr Besitzer-Handy.", ich));
                return new JSONObject().put("ok", true);
            }
            case DIENST: {
                String name = req.optString("name", "");
                if (SystemApiHandler.kernDienst(name) == null) return fehler(400, "dienst");
                boolean ok = wirkung.dienst(name);
                BefehlsLog.ereignis("besitzer.dienst." + name, ok ? "gestartet" : "fehler", kid, ich);
                wirkung.melde(Messages.text("besitzer.dienst", "Dienst {0} freigegeben vom Besitzer-Handy {1}.", name, ich));
                return ok ? new JSONObject().put("ok", true).put("hinweis", "startet_in_30s") : fehler(500, "fehlgeschlagen");
            }
            case SELBST: {
                List<String> an = new ArrayList<>();
                JSONArray a = req.optJSONArray("rechte");
                for (int i = 0; a != null && i < a.length(); i++) an.add(a.optString(i));
                for (String x : an) if (Kopplungen.NUR_DISPLAY_RECHTE.contains(x)) return fehler(400, "recht_nur_display");
                int e = k.besitzerSelbstRechte(kid, an);
                if (e == Kopplungen.BESITZER_UNBEKANNT) return fehler(400, "recht");
                if (e != Kopplungen.BESITZER_OK) return fehler(500, "speichern");
                wirkung.melde(Messages.text("besitzer.rechte_selbst", "Besitzer-Handy {0} hat seine Rechte geändert: {1}.",
                        ich, an.isEmpty() ? "-" : String.join(", ", an)));
                return new JSONObject().put("ok", true).put("rechte", new JSONArray(k.rechte(kid)));
            }
            case WAECHTER: {
                if (!req.has("an")) return fehler(400, "an");
                boolean an = req.optBoolean("an", false);
                boolean ok = wirkung.waechter(an);
                BefehlsLog.ereignis("besitzer.waechter." + (an ? "an" : "aus"), ok ? "ok" : "fehler", kid, ich);
                if (ok) wirkung.melde(Messages.text(an ? "besitzer.waechter_an" : "besitzer.waechter_aus",
                        an ? "Wächter eingeschaltet vom Handy {0}." : "Wächter ausgeschaltet vom Handy {0}.", ich));
                return ok ? new JSONObject().put("ok", true) : fehler(500, "nicht_gespeichert");
            }
            default:
                return fehler(404, "unbekannt");
        }
    }

    /** Rechte, die eine Nicht-Handy-Art tragen kann (Kopplungen.fremdRechte ist paketlokal). */
    static List<String> rechteDerArt(String art) {
        if (Kopplungen.ART_PC.equals(art)) return Kopplungen.PC_RECHTE;
        if ("cockpit".equals(art)) return Kopplungen.COCKPIT_RECHTE;
        if ("homeassistant".equals(art)) return Kopplungen.HA_RECHTE;   // 0.1.38.8: mit geraete (geraete_steuern nur Display)
        return Kopplungen.FREMD_RECHTE;
    }

    private static synchronized boolean zuOft(long now) {
        while (!LETZTE.isEmpty() && now - LETZTE.peekFirst() >= 60_000L) LETZTE.pollFirst();
        if (LETZTE.size() >= MAX_JE_MINUTE) return true;
        LETZTE.addLast(now);
        return false;
    }

    /** Nur Tests. */
    static synchronized void ratenlimitLeeren() {
        LETZTE.clear();
    }

    private static JSONObject fehler(int status, String grund) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false).put("grund", grund).put("_status", status);
        } catch (Exception ignored) { }
        return o;
    }

    private static JSONObject entferneStatus(JSONObject o) {
        o.remove("_status");
        return o;
    }
}
