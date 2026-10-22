package at.greenchili.surfmate.wallbox;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

/**
 * evcc als Wallbox-Quelle (WALLBOX_VERTRAG §4), geprüft gegen evcc 0.317.2 (demo.evcc.io) und ältere API-Formen.
 * Gesendet werden nur: Modus, Ladegrenze, Ziel-Plan des erkannten Fahrzeugs. Nie Ströme, Phasen, Konfiguration.
 */
public final class EvccQuelle implements WallboxQuelle {

    /** Plan höchstens so weit in der Zukunft. */
    static final long PLAN_MAX_TAGE = 14;

    private final String basis;
    private final String apiKey;
    private final WallboxHttp http;
    private final Schlaf schlaf;
    private final java.util.function.LongSupplier uhr;
    private String version;

    public EvccQuelle(String basis, String apiKey, WallboxHttp http, Schlaf schlaf, java.util.function.LongSupplier uhr) {
        this.basis = basis;
        this.apiKey = apiKey == null || apiKey.isEmpty() ? null : apiKey;
        this.http = http;
        this.schlaf = schlaf;
        this.uhr = uhr;
    }

    @Override
    public JSONObject info() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("evccVersion", version == null ? JSONObject.NULL : version);
        return o;
    }

    // ------------------------------------------------------------------ lesen

    JSONObject zustand() throws WallboxFehler, JSONException {
        WallboxHttp.Antwort a = http.anfrage("GET", basis + "/api/state", kopf(), null);
        pruefeCode(a);
        try {
            JSONObject d = new JSONObject(a.text);
            JSONObject s = d.optJSONObject("result") != null ? d.optJSONObject("result") : d;
            if (!s.has("loadpoints")) throw new WallboxFehler("antwort_ungueltig", 502);
            Object v = s.opt("version");
            version = v instanceof String ? (String) v : null;
            return s;
        } catch (WallboxFehler f) {
            throw f;
        } catch (Exception e) {
            throw new WallboxFehler("antwort_ungueltig", 502);
        }
    }

    @Override
    public JSONArray ladepunkte() throws WallboxFehler, JSONException {
        return ladepunkte(zustand());
    }

    static JSONArray ladepunkte(JSONObject zustand) throws JSONException {
        JSONArray roh = zustand.optJSONArray("loadpoints");
        JSONArray aus = new JSONArray();
        if (roh == null) return aus;
        for (int i = 0; i < roh.length(); i++) {
            JSONObject lp = roh.optJSONObject(i);
            if (lp != null) aus.put(ladepunkt(lp, i + 1));
        }
        return aus;
    }

    static JSONObject ladepunkt(JSONObject lp, int n) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", "evcc:" + n);
        String titel = lp.optString("title", "");
        o.put("titel", titel.isEmpty() ? "Ladepunkt " + n : titel);
        String art = lp.optBoolean("chargerFeatureHeating", false) ? "heizung"
                : lp.optBoolean("chargerFeatureIntegratedDevice", false) ? "geraet" : "wallbox";
        o.put("art", art);
        o.put("verbunden", bool(lp, "connected"));
        o.put("laedt", bool(lp, "charging"));
        o.put("leistungW", zahl(lp.opt("chargePower")));
        String mode = lp.optString("mode", "");
        o.put("modusRoh", mode.isEmpty() ? JSONObject.NULL : mode);
        String modus = modusVon(mode, lp.has("alwaysCharge") ? lp.optString("alwaysCharge", "") : null);
        o.put("modus", modus == null ? JSONObject.NULL : modus);
        boolean wallbox = "wallbox".equals(art);
        Object limit = zahl(lp.opt("limitSoc"));
        o.put("zielSoc", wallbox ? limit : JSONObject.NULL);
        o.put("zielMin", 20);
        o.put("zielMax", 100);
        String fz = lp.optString("vehicleTitle", "");
        o.put("fahrzeug", fz.isEmpty() ? JSONObject.NULL : fz);
        o.put("fahrzeugSoc", lp.optBoolean("connected", false) && !fz.isEmpty() ? zahl(lp.opt("vehicleSoc")) : JSONObject.NULL);
        String planZeit = lp.isNull("effectivePlanTime") ? "" : lp.optString("effectivePlanTime", "");
        if (wallbox && !planZeit.isEmpty() && !planZeit.startsWith("0001-")) {
            o.put("plan", new JSONObject().put("art", "ziel_bis").put("zeit", planZeit)
                    .put("soc", zahl(lp.opt("effectivePlanSoc"))));
        } else {
            o.put("plan", JSONObject.NULL);
        }
        JSONObject f = new JSONObject();
        JSONArray modi = new JSONArray();
        if (wallbox) for (String m : MODI) modi.put(m);
        f.put("modus", modi);
        f.put("ziel", wallbox);
        f.put("plan", wallbox && !lp.optString("vehicleName", "").isEmpty() ? "ziel_bis" : JSONObject.NULL);
        o.put("faehigkeiten", f);
        o.put("_fahrzeugName", lp.optString("vehicleName", ""));
        o.put("_neueModi", lp.has("alwaysCharge"));
        return o;
    }

    /** evcc-Modus → Vertragsmodus. Neues evcc: smart + alwaysCharge (off → pv, on/once → minpv). */
    static String modusVon(String mode, String alwaysCharge) throws JSONException {
        if (mode == null) return null;
        switch (mode) {
            case "off": return MODUS_AUS;
            case "now": return MODUS_SOFORT;
            case "pv": return MODUS_PV;
            case "minpv": return MODUS_MINPV;
            case "smart":
                if (alwaysCharge == null) return MODUS_PV;
                return "on".equals(alwaysCharge) || "once".equals(alwaysCharge) ? MODUS_MINPV : MODUS_PV;
            default: return null;
        }
    }

    // ------------------------------------------------------------------ steuern

    @Override
    public JSONObject steuern(String ladepunktId, String aktion, Object wert) throws WallboxFehler, JSONException {
        int n = nummer(ladepunktId);
        JSONObject vorher = finde(ladepunkte(zustand()), ladepunktId);
        if (!"wallbox".equals(vorher.optString("art"))) throw new WallboxFehler("nicht_steuerbar", 400);
        switch (aktion) {
            case "modus": {
                String m = String.valueOf(wert);
                String evcc;
                switch (m) {
                    case MODUS_AUS: evcc = "off"; break;
                    case MODUS_SOFORT: evcc = "now"; break;
                    case MODUS_MINPV: evcc = "minpv"; break;
                    case MODUS_PV: evcc = "pv"; break;
                    default: throw new WallboxFehler("wert_ungueltig", 400);
                }
                post("/api/loadpoints/" + n + "/mode/" + evcc);
                // Neues evcc: pv/minpv sind Aliase für smart; alwaysCharge ausdrücklich setzen, sonst bleibt ein altes „on“.
                if (vorher.optBoolean("_neueModi") && (m.equals(MODUS_PV) || m.equals(MODUS_MINPV))) {
                    post("/api/loadpoints/" + n + "/alwayscharge/" + (m.equals(MODUS_MINPV) ? "on" : "off"));
                }
                JSONObject nach = zuruecklesen(ladepunktId);
                return ergebnis(m.equals(nach.optString("modus")), nach);
            }
            case "ziel": {
                int soc = ganzzahl(wert, 20, 100);
                post("/api/loadpoints/" + n + "/limitsoc/" + soc);
                JSONObject nach = zuruecklesen(ladepunktId);
                return ergebnis(nach.optInt("zielSoc", -1) == soc, nach);
            }
            case "plan": {
                String name = vorher.optString("_fahrzeugName", "");
                if (name.isEmpty()) throw new WallboxFehler("nicht_unterstuetzt", 400);
                if (!(wert instanceof JSONObject)) throw new WallboxFehler("wert_ungueltig", 400);
                JSONObject p = (JSONObject) wert;
                int soc = ganzzahl(p.opt("soc"), 20, 100);
                Instant zeit = zeitpunkt(p.optString("zeit", ""));
                String ts = zeit.truncatedTo(ChronoUnit.SECONDS).toString();
                post("/api/vehicles/" + seg(name) + "/plan/soc/" + soc + "/" + seg(ts));
                JSONObject nach = zuruecklesen(ladepunktId);
                JSONObject plan = nach.optJSONObject("plan");
                boolean ok = plan != null && plan.optInt("soc", -1) == soc && gleicheMinute(plan.optString("zeit"), zeit);
                return ergebnis(ok, nach);
            }
            case "plan_loeschen": {
                String name = vorher.optString("_fahrzeugName", "");
                if (name.isEmpty()) throw new WallboxFehler("nicht_unterstuetzt", 400);
                JSONObject alt = vorher.optJSONObject("plan");
                anfrage("DELETE", "/api/vehicles/" + seg(name) + "/plan/soc");
                JSONObject nach = zuruecklesen(ladepunktId);
                JSONObject neu = nach.optJSONObject("plan");
                boolean ok = neu == null || (alt != null && !alt.optString("zeit").equals(neu.optString("zeit")));
                return ergebnis(ok, nach);
            }
            default:
                throw new WallboxFehler("aktion_ungueltig", 400);
        }
    }

    Instant zeitpunkt(String s) throws WallboxFehler, JSONException {
        Instant t;
        try {
            t = OffsetDateTime.parse(s).toInstant();
        } catch (Exception e) {
            throw new WallboxFehler("wert_ungueltig", 400);
        }
        long jetzt = uhr.getAsLong();
        if (t.toEpochMilli() <= jetzt || t.toEpochMilli() > jetzt + PLAN_MAX_TAGE * 86_400_000L) {
            throw new WallboxFehler("wert_ungueltig", 400);
        }
        return t;
    }

    static boolean gleicheMinute(String evccZeit, Instant soll) throws JSONException {
        try {
            Instant ist = OffsetDateTime.parse(evccZeit).toInstant();
            return Math.abs(ist.getEpochSecond() - soll.getEpochSecond()) < 60;
        } catch (Exception e) {
            return false;
        }
    }

    private JSONObject zuruecklesen(String id) throws WallboxFehler, JSONException {
        schlaf.ms(300);
        return oeffentlich(finde(ladepunkte(zustand()), id));
    }

    private static JSONObject ergebnis(boolean ok, JSONObject nach) throws JSONException {
        return new JSONObject().put("uebernommen", ok).put("ladepunkt", nach);
    }

    /** Interne Felder (mit _) entfernen, bevor etwas die API verlässt. */
    static JSONObject oeffentlich(JSONObject lp) throws JSONException {
        JSONObject o = new JSONObject(lp.toString());
        o.remove("_fahrzeugName");
        o.remove("_neueModi");
        return o;
    }

    static JSONObject finde(JSONArray lps, String id) throws WallboxFehler, JSONException {
        for (int i = 0; i < lps.length(); i++) {
            JSONObject lp = lps.optJSONObject(i);
            if (lp != null && id.equals(lp.optString("id"))) return lp;
        }
        throw new WallboxFehler("ladepunkt_unbekannt", 404);
    }

    static int nummer(String id) throws WallboxFehler, JSONException {
        if (id == null || !id.matches("evcc:([1-9]|1[0-9]|20)")) throw new WallboxFehler("ladepunkt_unbekannt", 404);
        return Integer.parseInt(id.substring(5));
    }

    static int ganzzahl(Object wert, int min, int max) throws WallboxFehler, JSONException {
        if (!(wert instanceof Number)) {
            try {
                wert = Integer.parseInt(String.valueOf(wert).trim());
            } catch (Exception e) {
                throw new WallboxFehler("wert_ungueltig", 400);
            }
        }
        double d = ((Number) wert).doubleValue();
        if (d != Math.rint(d) || d < min || d > max) throw new WallboxFehler("wert_ungueltig", 400);
        return (int) d;
    }

    private void post(String pfad) throws WallboxFehler, JSONException {
        anfrage("POST", pfad);
    }

    private void anfrage(String methode, String pfad) throws WallboxFehler, JSONException {
        WallboxHttp.Antwort a = http.anfrage(methode, basis + pfad, kopf(), null);
        pruefeCode(a);
    }

    private Map<String, String> kopf() throws JSONException {
        Map<String, String> k = new HashMap<>();
        if (apiKey != null) k.put("Authorization", "Bearer " + apiKey);
        return k;
    }

    static void pruefeCode(WallboxHttp.Antwort a) throws WallboxFehler {
        if (a.code == 401 || a.code == 403) throw new WallboxFehler("anmeldung", 502);
        if (a.code == 404) throw new WallboxFehler("nicht_unterstuetzt", 502);
        if (a.code < 200 || a.code >= 300) throw new WallboxFehler("quelle_fehler", 502);
    }

    private static String seg(String s) throws JSONException {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (Exception e) {
            return "";
        }
    }

    private static Object bool(JSONObject o, String k) throws JSONException {
        Object v = o.opt(k);
        return v instanceof Boolean ? v : JSONObject.NULL;
    }

    static Object zahl(Object v) throws JSONException {
        if (v instanceof Number && !Double.isNaN(((Number) v).doubleValue())) {
            double d = ((Number) v).doubleValue();
            return d == Math.rint(d) && Math.abs(d) < 1e12 ? (Object) (long) d : (Object) (Math.round(d * 10) / 10.0);
        }
        return JSONObject.NULL;
    }
}
