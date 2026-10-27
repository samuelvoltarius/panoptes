package at.greenchili.surfmate.wallbox;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * go-e Charger über die lokale HTTP-API (v2, Rückfall v1). Eigene Umsetzung; Endpunkte und Felder nach dem evcc-Treiber
 * {@code charger/go-e.go}, {@code go-e/api.go}, {@code types*.go} (MIT, evcc.io) bzw. der go-e-API-Doku:
 * <pre>
 * v2: GET /api/status?filter=alw,car,amp,nrg,frc   car 1 frei, 2 lädt, 3 wartet aufs Auto, 4 fertig, 5 Fehler; nrg[11] = W
 *     GET /api/set?frc=1|2  (1 = aus, 2 = ein)       GET /api/set?amp=N
 * v1: GET /status (Werte als Text; nrg[11] in 0,01 kW)  GET /mqtt?payload=alw=0|1   GET /mqtt?payload=amx=N
 * </pre>
 * Die lokale API muss in der go-e-App eingeschaltet sein. Keine Zugangsdaten.
 *
 * <p>0.1.20 go-e Cloud (WALLBOX_FERN_KONZEPT §6, Doku goecharger/go-eCharger-API-v2 cloudapi-en.md): dieselbe API v2 über
 * {@code https://<6-stellige Seriennummer>.api.v3.go-e.io}, Token im Kopf {@code Authorization: Bearer} (nie in der URL,
 * damit es in keinem Log steht). Token einmal in der go-e-App erzeugen (Cloud-API einschalten); nur mit Einwilligung.
 */
public final class GoeAdapter implements DirektAdapter {

    static final int MIN_A = 6;
    static final int MAX_A = 32;

    private final String basis;
    private final WallboxHttp http;
    private final String token;
    private Boolean v2;

    public GoeAdapter(String basis, WallboxHttp http) {
        this.basis = basis;
        this.http = http;
        this.token = null;
    }

    /** go-e Cloud: Seriennummer (6 Ziffern) + API-Token. */
    public static GoeAdapter cloud(String seriennummer, String token, WallboxHttp http) throws WallboxFehler {
        if (seriennummer == null || !seriennummer.matches("[0-9]{6}")) throw new WallboxFehler("einstellung_ungueltig");
        if (token == null || token.isEmpty()) throw new WallboxFehler("anmeldung", 502);
        return new GoeAdapter("https://" + seriennummer + ".api.v3.go-e.io", http, token);
    }

    private GoeAdapter(String basis, WallboxHttp http, String token) {
        this.basis = basis;
        this.http = http;
        this.token = token;
        this.v2 = true;   // die Cloud spricht nur API v2
    }

    @Override
    public long[] pausen() {
        return token == null ? new long[]{500, 1000, 1000} : new long[]{1500, 2500, 4000};
    }

    private JSONObject hole(String pfad) throws WallboxFehler {
        java.util.Map<String, String> kopf = new java.util.HashMap<>();
        if (token != null) kopf.put("Authorization", "Bearer " + token);
        WallboxHttp.Antwort a = http.anfrage("GET", basis + pfad, kopf, null);
        if (token != null && (a.code == 401 || a.code == 403)) throw new WallboxFehler("anmeldung", 502);
        if (token != null && a.code == 429) throw new WallboxFehler("cloud_zu_viele_anfragen", 502);
        EvccQuelle.pruefeCode(a);
        try {
            return new JSONObject(a.text);
        } catch (Exception e) {
            throw new WallboxFehler("antwort_ungueltig", 502);
        }
    }

    private boolean v2() throws WallboxFehler {
        if (v2 == null) {
            try {
                v2 = hole("/api/status?filter=alw").has("alw");
            } catch (WallboxFehler f) {
                if (!"nicht_unterstuetzt".equals(f.code)) throw f;   // 404 → alte API v1
                v2 = false;
            }
        }
        return v2;
    }

    @Override
    public JSONObject lies() throws WallboxFehler, JSONException {
        boolean neu = v2();
        JSONObject s = neu ? hole("/api/status?filter=alw,car,amp,nrg,frc") : hole("/status");
        JSONObject o = DirektAdapter.leer();
        int car = zahl(s.opt("car"));
        o.put("laedt", car > 0 ? (Object) (car == 2) : JSONObject.NULL);
        o.put("verbunden", car > 0 ? (Object) (car == 2 || car == 3 || car == 4) : JSONObject.NULL);
        JSONArray nrg = s.optJSONArray("nrg");
        if (nrg != null && nrg.length() == 16) {
            double p = nrg.optDouble(11, Double.NaN);
            if (!Double.isNaN(p)) o.put("leistungW", EvccQuelle.zahl(Math.round(neu ? p : p * 10)));
        }
        int amp = zahl(s.opt("amp"));
        o.put("stromA", amp > 0 ? amp : JSONObject.NULL);
        boolean alw = neu ? s.optBoolean("alw", false) : zahl(s.opt("alw")) == 1;
        int frc = neu ? zahl(s.opt("frc")) : -1;
        // v2: frc 1 = aus erzwungen, 2 = ein erzwungen, 0 = neutral (Box entscheidet, alw zeigt das Ergebnis)
        String modus = frc == 1 ? WallboxQuelle.MODUS_AUS : frc == 2 ? WallboxQuelle.MODUS_SOFORT
                : alw ? WallboxQuelle.MODUS_SOFORT : WallboxQuelle.MODUS_AUS;
        o.put("modus", modus);
        o.put("modusRoh", neu ? "frc=" + frc + ",alw=" + alw : "alw=" + (alw ? 1 : 0));
        o.put("faehigkeiten", new JSONObject().put("modus", new JSONArray().put(WallboxQuelle.MODUS_AUS).put(WallboxQuelle.MODUS_SOFORT))
                .put("ziel", false).put("plan", JSONObject.NULL).put("strom", new JSONObject().put("min", MIN_A).put("max", MAX_A)));
        return o;
    }

    @Override
    public void setze(String aktion, Object wert) throws WallboxFehler, JSONException {
        boolean neu = v2();
        String p;
        if ("modus".equals(aktion)) {
            boolean an = WallboxQuelle.MODUS_SOFORT.equals(wert);
            p = neu ? "/api/set?frc=" + (an ? 2 : 1) : "/mqtt?payload=alw=" + (an ? 1 : 0);
        } else if ("strom".equals(aktion)) {
            int a = EvccQuelle.ganzzahl(wert, MIN_A, MAX_A);
            p = neu ? "/api/set?amp=" + a : "/mqtt?payload=amx=" + a;
        } else {
            throw new WallboxFehler("nicht_unterstuetzt", 400);
        }
        hole(p);
    }

    /** v1 liefert Zahlen als Text. */
    static int zahl(Object v) {
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof Boolean) return (Boolean) v ? 1 : 0;
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception e) {
            return -1;
        }
    }
}
