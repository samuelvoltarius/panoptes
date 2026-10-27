package at.greenchili.surfmate.wallbox;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Wallbox Chargers (Pulsar, Commander, Copper) über das Wallbox-Konto (0.1.20, WALLBOX_FERN_KONZEPT §6). Es gibt keine
 * offizielle Doku; Endpunkte und Felder wie die Home-Assistant-Integration {@code homeassistant/components/wallbox}
 * (Python-Paket {@code wallbox}, cliviu74/wallbox, MIT) – geprüft 27.10.2026, gilt als „inoffiziell“:
 * <pre>
 * GET  https://user-api.wall-box.com/users/signin  (Basic-Anmeldung, Kopf Partner: wallbox) → data.attributes.token, ttl
 * GET  https://api.wall-box.com/chargers/status/{id} → status_id, charging_power (kW), config_data.max_charging_current
 * POST https://api.wall-box.com/v3/chargers/{id}/remote-action {"action":1} fortsetzen | {"action":2} pausieren
 * PUT  https://api.wall-box.com/v2/charger/{id} {"maxChargingCurrent":A}
 * status_id: 193–195 lädt, 178/182 pausiert, 177/179 geplant, 180/181 wartet aufs Auto, 0/161/162/163 kein Auto
 * </pre>
 * Nur mit eigener Einwilligung (Regel 2), Konto verschlüsselt in der Konfig, Token nur im Speicher.
 */
public final class PulsarAdapter implements DirektAdapter {

    public static final String API = "https://api.wall-box.com";
    public static final String ANMELDUNG = "https://user-api.wall-box.com";
    static final Map<String, Object[]> TOKENS = new ConcurrentHashMap<>();

    private final String id;
    private final String benutzer;
    private final String passwort;
    private final WallboxHttp http;
    private final LongSupplier uhr;

    public PulsarAdapter(String geraeteId, String benutzer, String passwort, WallboxHttp http, LongSupplier uhr) throws WallboxFehler {
        if (geraeteId == null || !geraeteId.matches("[0-9]{1,12}")) throw new WallboxFehler("einstellung_ungueltig");
        if (benutzer == null || benutzer.isEmpty() || passwort == null || passwort.isEmpty()) throw new WallboxFehler("anmeldung", 502);
        this.id = geraeteId;
        this.benutzer = benutzer;
        this.passwort = passwort;
        this.http = http;
        this.uhr = uhr;
    }

    @Override
    public long[] pausen() {
        return CloudAdapter.PAUSEN;
    }

    private String schluessel() {
        return TuyaCloud.sha256("pulsar\n" + benutzer + "\n" + passwort);
    }

    private String token() throws WallboxFehler {
        Object[] t = TOKENS.get(schluessel());
        if (t != null && (Long) t[1] > uhr.getAsLong()) return (String) t[0];
        Map<String, String> k = new HashMap<>();
        k.put("Partner", "wallbox");
        k.put("Accept", "application/json");
        k.put("Authorization", "Basic " + Base64.getEncoder()
                .encodeToString((benutzer + ":" + passwort).getBytes(StandardCharsets.UTF_8)));
        WallboxHttp.Antwort a = http.anfrage("GET", ANMELDUNG + "/users/signin", k, null);
        if (a.code == 401 || a.code == 403) throw new WallboxFehler("anmeldung", 502);
        if (a.code == 429) throw new WallboxFehler("cloud_zu_viele_anfragen", 502);
        EvccQuelle.pruefeCode(a);
        String tok;
        long ttl;
        try {
            JSONObject at = new JSONObject(a.text).getJSONObject("data").getJSONObject("attributes");
            tok = at.optString("token", "");
            ttl = at.optLong("ttl", 0);
        } catch (Exception e) {
            throw new WallboxFehler("antwort_ungueltig", 502);
        }
        if (tok.isEmpty()) throw new WallboxFehler("anmeldung", 502);
        // ttl ist ein Zeitpunkt (ms) bzw. fehlt → 15 min
        long bis = ttl > uhr.getAsLong() ? ttl - 60_000L : uhr.getAsLong() + 15 * 60_000L;
        TOKENS.put(schluessel(), new Object[]{tok, bis});
        return tok;
    }

    private WallboxHttp.Antwort rufe(String methode, String url, String rumpf) throws WallboxFehler {
        Map<String, String> k = new HashMap<>();
        k.put("Accept", "application/json");
        k.put("Content-Type", "application/json;charset=UTF-8");
        k.put("Authorization", "Bearer " + token());
        WallboxHttp.Antwort a = http.anfrage(methode, url, k, rumpf);
        if (a.code == 401 || a.code == 403) {
            TOKENS.remove(schluessel());
            throw new WallboxFehler("anmeldung", 502);
        }
        if (a.code == 429) throw new WallboxFehler("cloud_zu_viele_anfragen", 502);
        EvccQuelle.pruefeCode(a);
        return a;
    }

    @Override
    public JSONObject lies() throws WallboxFehler, JSONException {
        WallboxHttp.Antwort a = rufe("GET", API + "/chargers/status/" + id, null);
        JSONObject s;
        try {
            s = new JSONObject(a.text);
        } catch (Exception e) {
            throw new WallboxFehler("antwort_ungueltig", 502);
        }
        int st = s.optInt("status_id", -1);
        JSONObject o = DirektAdapter.leer();
        boolean laedt = st >= 193 && st <= 195;
        o.put("laedt", st < 0 ? JSONObject.NULL : (Object) laedt);
        boolean ohneAuto = st == 0 || st == 161 || st == 162 || st == 163;
        o.put("verbunden", st < 0 || st == 14 || st == 15 || st == 165 || st == 166 || st == 209 ? JSONObject.NULL : (Object) !ohneAuto);
        if (s.opt("charging_power") instanceof Number) o.put("leistungW", Math.round(s.getDouble("charging_power") * 1000));
        String modus = laedt || st == 180 || st == 181 || st == 183 || st == 184 || st == 185 || st == 186 || st == 189
                ? WallboxQuelle.MODUS_SOFORT : st == 178 || st == 182 || st == 177 || st == 179 ? WallboxQuelle.MODUS_AUS : null;
        o.put("modus", modus == null ? JSONObject.NULL : modus);
        o.put("modusRoh", "status_id=" + st);
        JSONObject cfg = s.optJSONObject("config_data");
        if (cfg != null && cfg.opt("max_charging_current") instanceof Number) o.put("stromA", cfg.getInt("max_charging_current"));
        int max = s.optInt("max_available_current", 32);
        o.put("faehigkeiten", new JSONObject().put("modus", new JSONArray().put(WallboxQuelle.MODUS_AUS).put(WallboxQuelle.MODUS_SOFORT))
                .put("ziel", false).put("plan", JSONObject.NULL)
                .put("strom", new JSONObject().put("min", 6).put("max", max >= 6 && max <= 80 ? max : 32)));
        return o;
    }

    @Override
    public void setze(String aktion, Object wert) throws WallboxFehler, JSONException {
        if ("modus".equals(aktion)) {
            int act = WallboxQuelle.MODUS_SOFORT.equals(wert) ? 1 : 2;
            rufe("POST", API + "/v3/chargers/" + id + "/remote-action", new JSONObject().put("action", act).toString());
        } else if ("strom".equals(aktion)) {
            int a = EvccQuelle.ganzzahl(wert, 6, 80);
            rufe("PUT", API + "/v2/charger/" + id, new JSONObject().put("maxChargingCurrent", a).toString());
        } else {
            throw new WallboxFehler("nicht_unterstuetzt", 400);
        }
    }
}
