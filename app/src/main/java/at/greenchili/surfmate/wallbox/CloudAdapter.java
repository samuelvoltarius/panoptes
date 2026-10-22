package at.greenchili.surfmate.wallbox;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Hersteller-Clouds Easee und Zaptec (WALLBOX_VERTRAG §4c, Stufe 2d). Nur mit eigener Einwilligung je Gerät (Regel 2, Prüfung
 * im {@link WallboxDienst}: ohne Einwilligung wird dieser Adapter gar nicht erst gebaut). Feste Hosts des Herstellers, nur https,
 * nie über unsere Server. Anmeldedaten verschlüsselt in der Konfig; das Zugangs-Token liegt nur im Arbeitsspeicher.
 * <pre>
 * Easee (evcc charger/easee*.go MIT; openHAB-Easee-Bindung EPL-2.0 – nur Pfade/Feldnamen):
 *   POST /api/accounts/login {userName, password} → accessToken, expiresIn
 *   GET  /api/chargers/{id}/site → id;  GET /api/sites/{site}/state → circuitStates[].chargerStates[{chargerID, chargerState}]
 *        chargerState: chargerOpMode (1 getrennt, 2 wartet auf Start, 3 lädt, 4 fertig, 6 bereit, 7 wartet auf Freigabe),
 *        totalPower (kW), dynamicChargerCurrent (A)
 *   POST /api/chargers/{id}/commands/pause_charging | resume_charging;  POST /api/chargers/{id}/settings {dynamicChargerCurrent}
 * Zaptec (evcc charger/zaptec*.go MIT; Token-Endpunkt aus https://api.zaptec.com/.well-known/openid-configuration):
 *   POST /oauth/token grant_type=password → access_token, expires_in
 *   GET  /api/chargers/{id}/state → [{StateId, ValueAsString}]: 710 Betriebsmodus (1 getrennt, 2 verbunden, 3 lädt, 5 fertig),
 *        513 Leistung W, 708 eingestellter Strom A, 718 FinalStopActive
 *   POST /api/chargers/{id}/sendCommand/507 (fortsetzen) | 506 (endgültig stoppen); 520/528 = „ändert nichts“ → ok
 *   POST /api/chargers/{id}/update {maxChargeCurrent}
 * </pre>
 */
public final class CloudAdapter implements DirektAdapter {

    public static final String EASEE = "https://api.easee.com";
    public static final String ZAPTEC = "https://api.zaptec.com";
    static final long[] PAUSEN = {2000, 3000, 5000};

    /** Token nur im Speicher (nie auf Platte), Schlüssel = Hash aus Adapter + Benutzer + Passwort. */
    static final Map<String, Object[]> TOKENS = new ConcurrentHashMap<>();
    static final Map<String, String> EASEE_SITES = new ConcurrentHashMap<>();

    private final String art;
    private final String id;
    private final String benutzer;
    private final String passwort;
    private final WallboxHttp http;
    private final LongSupplier uhr;

    public CloudAdapter(String art, String geraeteId, String benutzer, String passwort, WallboxHttp http, LongSupplier uhr) throws WallboxFehler {
        if (!"easee".equals(art) && !"zaptec".equals(art)) throw new WallboxFehler("einstellung_ungueltig");
        if (geraeteId == null || !geraeteId.matches("[A-Za-z0-9-]{3,64}")) throw new WallboxFehler("einstellung_ungueltig");
        if (benutzer == null || benutzer.isEmpty() || passwort == null || passwort.isEmpty()) throw new WallboxFehler("anmeldung", 502);
        this.art = art;
        this.id = geraeteId;
        this.benutzer = benutzer;
        this.passwort = passwort;
        this.http = http;
        this.uhr = uhr;
    }

    @Override
    public long[] pausen() {
        return PAUSEN;
    }

    private String schluessel() {
        try {
            return TuyaCloud.hex(MessageDigest.getInstance("SHA-256")
                    .digest((art + "\n" + benutzer + "\n" + passwort).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String token() throws WallboxFehler, JSONException {
        String k = schluessel();
        Object[] t = TOKENS.get(k);
        if (t != null && (Long) t[1] > uhr.getAsLong()) return (String) t[0];
        Map<String, String> kopf = basisKopf();
        WallboxHttp.Antwort a;
        String tok;
        long sek;
        if ("easee".equals(art)) {
            a = http.anfrage("POST", EASEE + "/api/accounts/login", kopf,
                    new JSONObject().put("userName", benutzer).put("password", passwort).toString());
            JSONObject j = json(a, true);
            tok = j.optString("accessToken", "");
            sek = (long) j.optDouble("expiresIn", 3600);
        } else {
            kopf.put("Content-Type", "application/x-www-form-urlencoded");
            a = http.anfrage("POST", ZAPTEC + "/oauth/token", kopf, "grant_type=password&username=" + url(benutzer)
                    + "&password=" + url(passwort) + "&scope=openid");
            JSONObject j = json(a, true);
            tok = j.optString("access_token", "");
            sek = j.optLong("expires_in", 3600);
        }
        if (tok.isEmpty()) throw new WallboxFehler("anmeldung", 502);
        TOKENS.put(k, new Object[]{tok, uhr.getAsLong() + Math.max(60, sek - 60) * 1000L});
        return tok;
    }

    private static String url(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static Map<String, String> basisKopf() {
        Map<String, String> k = new HashMap<>();
        k.put("User-Agent", "Panoptes-Wallbox");
        return k;
    }

    private Map<String, String> kopf() throws WallboxFehler, JSONException {
        Map<String, String> k = basisKopf();
        k.put("Authorization", "Bearer " + token());
        return k;
    }

    private JSONObject json(WallboxHttp.Antwort a, boolean anmeldung) throws WallboxFehler {
        if (a.code == 401 || a.code == 403 || (anmeldung && a.code == 400)) {
            TOKENS.remove(schluessel());
            throw new WallboxFehler("anmeldung", 502);
        }
        if (a.code == 429) throw new WallboxFehler("cloud_zu_viele_anfragen", 502);
        EvccQuelle.pruefeCode(a);
        try {
            return new JSONObject(a.text.isEmpty() ? "{}" : a.text);
        } catch (Exception e) {
            throw new WallboxFehler("antwort_ungueltig", 502);
        }
    }

    private Object hole(String url) throws WallboxFehler, JSONException {
        WallboxHttp.Antwort a = http.anfrage("GET", url, kopf(), null);
        if (a.code == 401 || a.code == 403) {
            TOKENS.remove(schluessel());
            throw new WallboxFehler("anmeldung", 502);
        }
        EvccQuelle.pruefeCode(a);
        try {
            String t = a.text.trim();
            return t.startsWith("[") ? new JSONArray(t) : new JSONObject(t);
        } catch (Exception e) {
            throw new WallboxFehler("antwort_ungueltig", 502);
        }
    }

    private String seg() {
        return url(id);
    }

    // ------------------------------------------------------------------ lesen

    @Override
    public JSONObject lies() throws WallboxFehler, JSONException {
        JSONObject o = DirektAdapter.leer();
        if ("easee".equals(art)) {
            JSONObject s = easeeZustand();
            int m = s.optInt("chargerOpMode", -1);
            o.put("laedt", m == 3);
            o.put("verbunden", m >= 2 && m != 5 && m <= 7);
            if (s.opt("totalPower") instanceof Number) o.put("leistungW", EvccQuelle.zahl(Math.round(s.getDouble("totalPower") * 1000)));
            o.put("modus", m == 2 || m == 7 ? WallboxQuelle.MODUS_AUS : m == 3 || m == 4 || m == 6 ? WallboxQuelle.MODUS_SOFORT : JSONObject.NULL);
            o.put("modusRoh", "chargerOpMode=" + m);
            if (s.opt("dynamicChargerCurrent") instanceof Number) o.put("stromA", EvccQuelle.zahl(s.getDouble("dynamicChargerCurrent")));
        } else {
            Object r = hole(ZAPTEC + "/api/chargers/" + seg() + "/state");
            if (!(r instanceof JSONArray)) throw new WallboxFehler("antwort_ungueltig", 502);
            Map<Integer, String> w = new HashMap<>();
            JSONArray a = (JSONArray) r;
            for (int i = 0; i < a.length(); i++) {
                JSONObject e = a.optJSONObject(i);
                if (e != null && e.has("StateId")) w.put(e.optInt("StateId"), e.optString("ValueAsString", ""));
            }
            int m = zahl(w.get(710));
            o.put("laedt", m == 3);
            o.put("verbunden", m == 2 || m == 3 || m == 5);
            if (w.containsKey(513)) o.put("leistungW", EvccQuelle.zahl(Math.round(dbl(w.get(513)))));
            boolean stopp = "true".equalsIgnoreCase(w.getOrDefault(718, "")) || "1".equals(w.get(718));
            o.put("modus", m <= 0 ? JSONObject.NULL : stopp ? WallboxQuelle.MODUS_AUS : WallboxQuelle.MODUS_SOFORT);
            o.put("modusRoh", "710=" + m + ",718=" + w.get(718));
            if (w.containsKey(708)) o.put("stromA", EvccQuelle.zahl(dbl(w.get(708))));
        }
        o.put("faehigkeiten", new JSONObject().put("modus", new JSONArray().put(WallboxQuelle.MODUS_AUS).put(WallboxQuelle.MODUS_SOFORT))
                .put("ziel", false).put("plan", JSONObject.NULL).put("strom", new JSONObject().put("min", 6).put("max", 32)));
        return o;
    }

    private JSONObject easeeZustand() throws WallboxFehler, JSONException {
        String site = EASEE_SITES.get(id);
        if (site == null) {
            Object s = hole(EASEE + "/api/chargers/" + seg() + "/site");
            if (!(s instanceof JSONObject) || !((JSONObject) s).has("id")) throw new WallboxFehler("antwort_ungueltig", 502);
            site = String.valueOf(((JSONObject) s).get("id"));
            EASEE_SITES.put(id, site);
        }
        Object r = hole(EASEE + "/api/sites/" + url(site) + "/state");
        JSONArray kreise = r instanceof JSONObject ? ((JSONObject) r).optJSONArray("circuitStates") : null;
        for (int i = 0; kreise != null && i < kreise.length(); i++) {
            JSONArray l = kreise.getJSONObject(i).optJSONArray("chargerStates");
            for (int j = 0; l != null && j < l.length(); j++) {
                JSONObject c = l.getJSONObject(j);
                if (id.equalsIgnoreCase(c.optString("chargerID")) && c.optJSONObject("chargerState") != null) return c.getJSONObject("chargerState");
            }
        }
        throw new WallboxFehler("ladepunkt_unbekannt", 404);
    }

    private static int zahl(String s) {
        try {
            return Integer.parseInt(s == null ? "" : s.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static double dbl(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ steuern

    @Override
    public void setze(String aktion, Object wert) throws WallboxFehler, JSONException {
        boolean an = WallboxQuelle.MODUS_SOFORT.equals(wert);
        String url;
        String rumpf = null;
        if ("modus".equals(aktion)) {
            url = "easee".equals(art) ? EASEE + "/api/chargers/" + seg() + "/commands/" + (an ? "resume_charging" : "pause_charging")
                    : ZAPTEC + "/api/chargers/" + seg() + "/sendCommand/" + (an ? 507 : 506);
        } else if ("strom".equals(aktion)) {
            int a = EvccQuelle.ganzzahl(wert, 6, 32);
            if ("easee".equals(art)) {
                url = EASEE + "/api/chargers/" + seg() + "/settings";
                rumpf = new JSONObject().put("dynamicChargerCurrent", a).toString();
            } else {
                url = ZAPTEC + "/api/chargers/" + seg() + "/update";
                rumpf = new JSONObject().put("maxChargeCurrent", a).toString();
            }
        } else {
            throw new WallboxFehler("nicht_unterstuetzt", 400);
        }
        Map<String, String> k = kopf();
        k.put("Content-Type", "application/json");
        WallboxHttp.Antwort r = http.anfrage("POST", url, k, rumpf == null ? "" : rumpf);
        if ("zaptec".equals(art) && r.code >= 400) {
            int code = -1;
            try {
                code = new JSONObject(r.text).optInt("Code", -1);
            } catch (Exception ignored) { }
            if (code == 520 || code == 528) return;   // „würde nichts ändern“ (evcc-Beleg) → kein Fehler
        }
        if (r.code == 401 || r.code == 403) {
            TOKENS.remove(schluessel());
            throw new WallboxFehler("anmeldung", 502);
        }
        EvccQuelle.pruefeCode(r);
    }
}
