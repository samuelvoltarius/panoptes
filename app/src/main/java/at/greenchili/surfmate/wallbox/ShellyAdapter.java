package at.greenchili.surfmate.wallbox;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Shelly-Schalter vor einem Ladegerät („einfach ein/aus“). Eigene Umsetzung nach Shelly-API-Doku bzw. evcc-Treiber
 * {@code meter/shelly/gen1.go}, {@code gen2.go} (MIT, evcc.io):
 * <pre>
 * GET /shelly → gen (fehlt = Gen1), auth / auth_en
 * Gen1:  GET /status → relays[k].ison, meters[k].power      GET /relay/k?turn=on|off   (Anmeldung: Basic)
 * Gen2+: POST /rpc/Switch.GetStatus {"id":k} → output, apower   POST /rpc/Switch.Set {"id":k,"on":…}
 * </pre>
 * Nur Ein/Aus, kein Ladestrom. Gen2+-Geräte mit Anmeldung (Digest, RFC 7616) werden in v1 nicht unterstützt
 * ({@code shelly_anmeldung}). „Lädt“ = Leistung über 100 W (sofern gemessen).
 */
public final class ShellyAdapter implements DirektAdapter {

    static final double LAEDT_AB_W = 100;

    private final String basis;
    private final int kanal;
    private final String benutzer;
    private final String passwort;
    private final WallboxHttp http;
    private Integer gen;

    public ShellyAdapter(String basis, int kanal, String benutzer, String passwort, WallboxHttp http) throws WallboxFehler {
        if (kanal < 0 || kanal > 3) throw new WallboxFehler("einstellung_ungueltig");
        this.basis = basis;
        this.kanal = kanal;
        this.benutzer = benutzer == null ? "" : benutzer;
        this.passwort = passwort == null ? "" : passwort;
        this.http = http;
    }

    private Map<String, String> kopf() {
        Map<String, String> k = new HashMap<>();
        if (!benutzer.isEmpty()) {
            k.put("Authorization", "Basic " + java.util.Base64.getEncoder()
                    .encodeToString((benutzer + ":" + passwort).getBytes(StandardCharsets.UTF_8)));
        }
        return k;
    }

    private JSONObject rufe(String methode, String pfad, String rumpf) throws WallboxFehler {
        WallboxHttp.Antwort a = http.anfrage(methode, basis + pfad, kopf(), rumpf);
        EvccQuelle.pruefeCode(a);
        try {
            return new JSONObject(a.text);
        } catch (Exception e) {
            throw new WallboxFehler("antwort_ungueltig", 502);
        }
    }

    private int gen() throws WallboxFehler {
        if (gen == null) {
            JSONObject i = rufe("GET", "/shelly", null);
            int g = i.optInt("gen", 1);
            if (g >= 2 && i.optBoolean("auth_en", false)) throw new WallboxFehler("shelly_anmeldung", 502);
            gen = Math.max(1, g);
        }
        return gen;
    }

    @Override
    public JSONObject lies() throws WallboxFehler, JSONException {
        boolean an;
        Object p = JSONObject.NULL;
        if (gen() >= 2) {
            JSONObject s = rufe("POST", "/rpc/Switch.GetStatus", new JSONObject().put("id", kanal).toString());
            an = s.optBoolean("output", false);
            if (s.opt("apower") instanceof Number) p = s.getDouble("apower");
        } else {
            JSONObject s = rufe("GET", "/status", null);
            JSONArray r = s.optJSONArray("relays");
            if (r == null || r.length() <= kanal) throw new WallboxFehler("einstellung_ungueltig", 400);
            an = r.getJSONObject(kanal).optBoolean("ison", false);
            JSONArray m = s.optJSONArray("meters");
            if (m != null && m.length() > kanal && m.getJSONObject(kanal).opt("power") instanceof Number) p = m.getJSONObject(kanal).getDouble("power");
        }
        JSONObject o = DirektAdapter.leer();
        o.put("modus", an ? WallboxQuelle.MODUS_SOFORT : WallboxQuelle.MODUS_AUS);
        o.put("modusRoh", an ? "on" : "off");
        if (p instanceof Double) {
            o.put("leistungW", EvccQuelle.zahl(Math.round((Double) p)));
            o.put("laedt", an && (Double) p > LAEDT_AB_W);
        }
        o.put("faehigkeiten", new JSONObject().put("modus", new JSONArray().put(WallboxQuelle.MODUS_AUS).put(WallboxQuelle.MODUS_SOFORT))
                .put("ziel", false).put("plan", JSONObject.NULL).put("strom", JSONObject.NULL));
        o.put("hinweis", "nur_ein_aus");
        return o;
    }

    @Override
    public void setze(String aktion, Object wert) throws WallboxFehler, JSONException {
        if (!"modus".equals(aktion)) throw new WallboxFehler("nicht_unterstuetzt", 400);
        boolean an = WallboxQuelle.MODUS_SOFORT.equals(wert);
        if (gen() >= 2) {
            rufe("POST", "/rpc/Switch.Set", new JSONObject().put("id", kanal).put("on", an).toString());
        } else {
            rufe("GET", "/relay/" + kanal + "?turn=" + (an ? "on" : "off"), null);
        }
    }
}
