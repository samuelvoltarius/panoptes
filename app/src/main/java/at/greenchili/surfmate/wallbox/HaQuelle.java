package at.greenchili.surfmate.wallbox;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Home Assistant als Wallbox-Quelle (WALLBOX_VERTRAG §4). Jede Wallbox ist eine vom Nutzer angelegte Zuordnung
 * von Entitäten – nichts ist auf eine Marke festgelegt:
 * <pre>
 * {schluessel, titel, laedt, leistung, modus, modusOptionen:{aus,sofort,minpv,pv}, ziel, strom, startzeit}
 * </pre>
 * Geschrieben wird nur über die Dienste select/input_select.select_option, switch.turn_on/off,
 * number/input_number.set_value (ziel in %, strom in A), time.set_value und input_datetime.set_datetime – je nur auf die zugeordnete Entität.
 */
public final class HaQuelle implements WallboxQuelle {

    static final Pattern ENTITAET = Pattern.compile("[a-z_]{1,32}\\.[a-z0-9_]{1,128}");
    static final Pattern SCHLUESSEL = Pattern.compile("[a-z0-9_]{1,32}");
    static final Pattern UHRZEIT = Pattern.compile("([01][0-9]|2[0-3]):[0-5][0-9]");

    /** Erlaubte Domänen je Rolle. */
    static final Map<String, String[]> DOMAENEN = new HashMap<>();
    static {
        DOMAENEN.put("laedt", new String[]{"binary_sensor", "sensor", "switch", "input_boolean"});
        DOMAENEN.put("leistung", new String[]{"sensor"});
        DOMAENEN.put("modus", new String[]{"select", "input_select", "switch"});
        DOMAENEN.put("ziel", new String[]{"number", "input_number"});
        DOMAENEN.put("strom", new String[]{"number", "input_number"});
        DOMAENEN.put("startzeit", new String[]{"time", "input_datetime"});
    }

    private final String basis;
    private final String token;
    private final JSONArray zuordnungen;
    private final WallboxHttp http;
    private final Schlaf schlaf;

    public HaQuelle(String basis, String token, JSONArray zuordnungen, WallboxHttp http, Schlaf schlaf) {
        this.basis = basis;
        this.token = token;
        this.zuordnungen = zuordnungen == null ? new JSONArray() : zuordnungen;
        this.http = http;
        this.schlaf = schlaf;
    }

    @Override
    public JSONObject info() throws JSONException {
        return new JSONObject();
    }

    /** Prüft eine Zuordnung aus den Einstellungen; gibt eine bereinigte Kopie zurück. */
    static JSONObject pruefeZuordnung(JSONObject z) throws WallboxFehler, JSONException {
        if (z == null) throw new WallboxFehler("einstellung_ungueltig");
        JSONObject o = new JSONObject();
        String s = z.optString("schluessel", "");
        if (!SCHLUESSEL.matcher(s).matches()) throw new WallboxFehler("einstellung_ungueltig");
        o.put("schluessel", s);
        String titel = z.optString("titel", "").trim();
        o.put("titel", titel.length() > 40 ? titel.substring(0, 40) : titel);
        for (String rolle : DOMAENEN.keySet()) {
            String e = z.isNull(rolle) ? "" : z.optString(rolle, "").trim();
            if (e.isEmpty()) {
                o.put(rolle, JSONObject.NULL);
                continue;
            }
            if (!ENTITAET.matcher(e).matches()) throw new WallboxFehler("einstellung_ungueltig");
            boolean ok = false;
            for (String d : DOMAENEN.get(rolle)) ok |= e.startsWith(d + ".");
            if (!ok) throw new WallboxFehler("einstellung_ungueltig");
            o.put(rolle, e);
        }
        JSONObject opt = new JSONObject();
        JSONObject roh = z.optJSONObject("modusOptionen");
        for (String m : MODI) {
            String v = roh == null || roh.isNull(m) ? "" : roh.optString(m, "").trim();
            if (v.length() > 64) throw new WallboxFehler("einstellung_ungueltig");
            opt.put(m, v.isEmpty() ? JSONObject.NULL : v);
        }
        o.put("modusOptionen", opt);
        return o;
    }

    // ------------------------------------------------------------------ lesen

    JSONObject entitaet(String id) throws WallboxFehler, JSONException {
        WallboxHttp.Antwort a = http.anfrage("GET", basis + "/api/states/" + id, kopf(), null);
        if (a.code == 404) return null;
        EvccQuelle.pruefeCode(a);
        try {
            return new JSONObject(a.text);
        } catch (Exception e) {
            throw new WallboxFehler("antwort_ungueltig", 502);
        }
    }

    @Override
    public JSONArray ladepunkte() throws WallboxFehler, JSONException {
        JSONArray aus = new JSONArray();
        for (int i = 0; i < zuordnungen.length(); i++) {
            JSONObject z = zuordnungen.optJSONObject(i);
            if (z != null) aus.put(ladepunkt(z));
        }
        return aus;
    }

    JSONObject ladepunkt(JSONObject z) throws WallboxFehler, JSONException {
        Map<String, JSONObject> e = new HashMap<>();
        for (String rolle : DOMAENEN.keySet()) {
            String id = z.isNull(rolle) ? "" : z.optString(rolle, "");
            if (!id.isEmpty()) e.put(rolle, entitaet(id));
        }
        return baue(z, e);
    }

    /** Rein: Ladepunkt aus Zuordnung und gelesenen HA-Zuständen (fehlende Entität = null). */
    static JSONObject baue(JSONObject z, Map<String, JSONObject> e) throws JSONException {
        JSONObject o = new JSONObject();
        String schluessel = z.optString("schluessel");
        o.put("id", "ha:" + schluessel);
        String titel = z.optString("titel", "");
        o.put("titel", titel.isEmpty() ? schluessel : titel);
        o.put("art", "wallbox");
        o.put("verbunden", JSONObject.NULL);
        o.put("laedt", laedt(e.get("laedt")));
        o.put("leistungW", leistungW(e.get("leistung")));

        JSONObject modus = e.get("modus");
        JSONArray modi = new JSONArray();
        Object modusWert = JSONObject.NULL;
        Object modusRoh = JSONObject.NULL;
        String modusId = z.isNull("modus") ? "" : z.optString("modus", "");
        if (modus != null && gueltig(modus)) {
            String st = modus.optString("state");
            modusRoh = st;
            if (modusId.startsWith("switch.")) {
                modi.put(MODUS_AUS).put(MODUS_SOFORT);
                modusWert = "on".equals(st) ? MODUS_SOFORT : "off".equals(st) ? MODUS_AUS : JSONObject.NULL;
            } else {
                JSONObject opt = z.optJSONObject("modusOptionen");
                JSONArray vorhanden = modus.optJSONObject("attributes") == null ? null
                        : modus.optJSONObject("attributes").optJSONArray("options");
                for (String m : MODI) {
                    String o2 = opt == null || opt.isNull(m) ? "" : opt.optString(m, "");
                    if (o2.isEmpty() || !enthaelt(vorhanden, o2)) continue;
                    modi.put(m);
                    if (o2.equals(st)) modusWert = m;
                }
            }
        }
        o.put("modus", modusWert);
        o.put("modusRoh", modusRoh);

        JSONObject ziel = e.get("ziel");
        boolean zielOk = ziel != null && gueltig(ziel);
        Object zielWert = JSONObject.NULL;
        int min = 20, max = 100;
        if (zielOk) {
            try {
                zielWert = EvccQuelle.zahl(Double.parseDouble(ziel.optString("state")));
            } catch (Exception ignored) { }
            JSONObject at = ziel.optJSONObject("attributes");
            if (at != null) {
                min = (int) Math.max(0, Math.round(at.optDouble("min", 20)));
                max = (int) Math.min(100, Math.round(at.optDouble("max", 100)));
            }
        }
        o.put("zielSoc", zielWert);
        o.put("zielMin", min);
        o.put("zielMax", max);

        JSONObject strom = e.get("strom");
        boolean stromOk = strom != null && gueltig(strom);
        Object stromWert = JSONObject.NULL;
        JSONObject stromF = null;
        if (stromOk) {
            try {
                stromWert = EvccQuelle.zahl(Double.parseDouble(strom.optString("state")));
            } catch (Exception ignored) { }
            JSONObject at = strom.optJSONObject("attributes");
            int smin = at == null ? 6 : (int) Math.max(1, Math.round(at.optDouble("min", 6)));
            int smax = at == null ? 16 : (int) Math.min(80, Math.round(at.optDouble("max", 16)));
            if (smin <= smax) stromF = new JSONObject().put("min", smin).put("max", smax);
        }
        o.put("stromA", stromWert);
        o.put("fahrzeug", JSONObject.NULL);
        o.put("fahrzeugSoc", JSONObject.NULL);

        JSONObject start = e.get("startzeit");
        boolean startOk = start != null && gueltig(start) && nurUhrzeit(start);
        String startZeit = startOk ? start.optString("state") : "";
        if (startZeit.length() >= 5) startZeit = startZeit.substring(0, 5);
        o.put("plan", startOk && UHRZEIT.matcher(startZeit).matches()
                ? new JSONObject().put("art", "start_um").put("zeit", startZeit) : JSONObject.NULL);

        JSONObject f = new JSONObject();
        f.put("modus", modi);
        f.put("ziel", zielOk);
        f.put("strom", stromF == null ? JSONObject.NULL : stromF);
        f.put("plan", startOk ? "start_um" : JSONObject.NULL);
        o.put("faehigkeiten", f);
        return o;
    }

    static boolean gueltig(JSONObject s) throws JSONException {
        String st = s.optString("state", "");
        return !st.isEmpty() && !"unavailable".equals(st) && !"unknown".equals(st);
    }

    /** time.* immer; input_datetime nur, wenn es eine reine Uhrzeit ist. */
    static boolean nurUhrzeit(JSONObject s) throws JSONException {
        String id = s.optString("entity_id", "");
        if (id.startsWith("time.")) return true;
        JSONObject at = s.optJSONObject("attributes");
        return at != null && at.optBoolean("has_time", false) && !at.optBoolean("has_date", false);
    }

    static Object laedt(JSONObject s) throws JSONException {
        if (s == null || !gueltig(s)) return JSONObject.NULL;
        String st = s.optString("state").trim().toLowerCase(Locale.ROOT);
        switch (st) {
            case "on": case "true": case "charging": case "laedt": case "lädt": case "laden": case "charger_charging":
                return true;
            case "off": case "false":
                return false;
            default:
                return false;
        }
    }

    static Object leistungW(JSONObject s) throws JSONException {
        if (s == null || !gueltig(s)) return JSONObject.NULL;
        double w;
        try {
            w = Double.parseDouble(s.optString("state"));
        } catch (Exception e) {
            return JSONObject.NULL;
        }
        JSONObject at = s.optJSONObject("attributes");
        String einheit = at == null ? "W" : at.optString("unit_of_measurement", "W").trim();
        if ("kW".equalsIgnoreCase(einheit)) w *= 1000;
        else if ("MW".equals(einheit)) w *= 1_000_000;
        else if (!"W".equalsIgnoreCase(einheit)) return JSONObject.NULL;
        return EvccQuelle.zahl(Math.round(w));
    }

    private static boolean enthaelt(JSONArray a, String s) throws JSONException {
        if (a == null) return false;
        for (int i = 0; i < a.length(); i++) if (s.equals(a.optString(i))) return true;
        return false;
    }

    // ------------------------------------------------------------------ Vorschlag (Einrichtung)

    static final String[] WALLBOX_WOERTER = {"charg", "lade", "wallbox", "evse", "ev_", "_ev", "charger", "pile", "ladestation", "wb_"};

    /**
     * Liest alle Entitäten (nur lesend, nur auf Knopfdruck) und schlägt je Rolle Kandidaten vor – generisch über Domäne,
     * Einheit, device_class und Wallbox-Wörter im Namen, ohne Code je Modell. Passt u. a. zu Tuya-Wallboxen in HA
     * (Tuya-Integration bzw. localtuya: switch, number in A, sensor Leistung/Status).
     */
    public JSONObject vorschlag() throws WallboxFehler, JSONException {
        WallboxHttp.Antwort a = http.anfrage("GET", basis + "/api/states", kopf(), null);
        EvccQuelle.pruefeCode(a);
        JSONArray alle;
        try {
            alle = new JSONArray(a.text);
        } catch (Exception e) {
            throw new WallboxFehler("antwort_ungueltig", 502);
        }
        return vorschlagAus(alle);
    }

    static JSONObject vorschlagAus(JSONArray alle) throws JSONException {
        JSONObject kand = new JSONObject();
        for (String r : new String[]{"modus", "strom", "ziel", "leistung", "laedt", "startzeit"}) kand.put(r, new JSONArray());
        JSONObject beste = new JSONObject();
        JSONObject punkte = new JSONObject();
        for (int i = 0; i < alle.length() && i < 5000; i++) {
            JSONObject s = alle.optJSONObject(i);
            if (s == null) continue;
            String id = s.optString("entity_id", "");
            if (!ENTITAET.matcher(id).matches()) continue;
            JSONObject at = s.optJSONObject("attributes") == null ? new JSONObject() : s.optJSONObject("attributes");
            String name = (id + " " + at.optString("friendly_name", "")).toLowerCase(Locale.ROOT);
            int wort = 0;
            for (String w : WALLBOX_WOERTER) if (name.contains(w)) wort = 2;
            String einheit = at.optString("unit_of_measurement", "");
            String klasse = at.optString("device_class", "");
            String st = s.optString("state", "");
            String dom = id.substring(0, id.indexOf('.'));
            String rolle = null;
            int p = wort;
            if (dom.equals("switch") && wort > 0) rolle = "modus";
            else if ((dom.equals("number") || dom.equals("input_number")) && ("A".equals(einheit) || "current".equals(klasse))) { rolle = "strom"; p += 2; }
            else if ((dom.equals("number") || dom.equals("input_number")) && "%".equals(einheit) && wort > 0) rolle = "ziel";
            else if (dom.equals("sensor") && ("power".equals(klasse) || "W".equals(einheit) || "kW".equals(einheit)) && wort > 0) { rolle = "leistung"; if ("power".equals(klasse)) p += 1; }
            else if (dom.equals("sensor") && st.startsWith("charger_")) { rolle = "laedt"; p += 3; }
            else if (dom.equals("binary_sensor") && ("battery_charging".equals(klasse) || wort > 0)) rolle = "laedt";
            else if (dom.equals("sensor") && wort > 0 && (name.contains("status") || name.contains("state") || name.contains("zustand"))) rolle = "laedt";
            else if (dom.equals("time") && wort > 0) rolle = "startzeit";
            if (rolle == null) continue;
            JSONArray l = kand.getJSONArray(rolle);
            if (l.length() < 20) {
                l.put(new JSONObject().put("entity_id", id).put("name", at.optString("friendly_name", id))
                        .put("zustand", st.length() > 40 ? st.substring(0, 40) : st).put("einheit", einheit));
            }
            if (p > punkte.optInt(rolle, -1)) {
                punkte.put(rolle, p);
                beste.put(rolle, id);
            }
        }
        JSONObject zuordnung = new JSONObject().put("schluessel", "wallbox").put("titel", "Wallbox");
        for (String r : new String[]{"modus", "strom", "ziel", "leistung", "laedt", "startzeit"}) {
            zuordnung.put(r, beste.has(r) ? beste.get(r) : JSONObject.NULL);
        }
        zuordnung.put("modusOptionen", new JSONObject());
        return new JSONObject().put("ok", true).put("kandidaten", kand).put("vorschlag", zuordnung)
                .put("hinweis", "vorschlag_pruefen");
    }

    // ------------------------------------------------------------------ steuern

    @Override
    public JSONObject steuern(String ladepunktId, String aktion, Object wert) throws WallboxFehler, JSONException {
        JSONObject z = zuordnung(ladepunktId);
        JSONObject vorher = ladepunkt(z);
        JSONObject f = vorher.getJSONObject("faehigkeiten");
        switch (aktion) {
            case "modus": {
                String m = String.valueOf(wert);
                if (!enthaelt(f.optJSONArray("modus"), m)) throw new WallboxFehler("nicht_unterstuetzt", 400);
                String id = z.getString("modus");
                if (id.startsWith("switch.")) {
                    dienst("switch", m.equals(MODUS_AUS) ? "turn_off" : "turn_on", new JSONObject().put("entity_id", id));
                } else {
                    String option = z.getJSONObject("modusOptionen").getString(m);
                    dienst(id.substring(0, id.indexOf('.')), "select_option",
                            new JSONObject().put("entity_id", id).put("option", option));
                }
                JSONObject nach = zuruecklesen(z, "modus", m);
                return ergebnis(m.equals(nach.optString("modus")), nach);
            }
            case "ziel": {
                if (!f.optBoolean("ziel")) throw new WallboxFehler("nicht_unterstuetzt", 400);
                int soc = EvccQuelle.ganzzahl(wert, vorher.optInt("zielMin", 20), vorher.optInt("zielMax", 100));
                String id = z.getString("ziel");
                dienst(id.substring(0, id.indexOf('.')), "set_value", new JSONObject().put("entity_id", id).put("value", soc));
                JSONObject nach = zuruecklesen(z, "zielSoc", soc);
                return ergebnis(nach.optInt("zielSoc", -1) == soc, nach);
            }
            case "strom": {
                JSONObject sf = f.optJSONObject("strom");
                if (sf == null) throw new WallboxFehler("nicht_unterstuetzt", 400);
                int a = EvccQuelle.ganzzahl(wert, sf.getInt("min"), sf.getInt("max"));
                String id = z.getString("strom");
                dienst(id.substring(0, id.indexOf('.')), "set_value", new JSONObject().put("entity_id", id).put("value", a));
                JSONObject nach = zuruecklesen(z, "stromA", a);
                return ergebnis(nach.optInt("stromA", -1) == a, nach);
            }
            case "plan": {
                if (!"start_um".equals(f.optString("plan"))) throw new WallboxFehler("nicht_unterstuetzt", 400);
                String zeit = wert instanceof JSONObject ? ((JSONObject) wert).optString("zeit", "") : "";
                if (!UHRZEIT.matcher(zeit).matches()) throw new WallboxFehler("wert_ungueltig", 400);
                String id = z.getString("startzeit");
                if (id.startsWith("time.")) {
                    dienst("time", "set_value", new JSONObject().put("entity_id", id).put("time", zeit + ":00"));
                } else {
                    dienst("input_datetime", "set_datetime", new JSONObject().put("entity_id", id).put("time", zeit + ":00"));
                }
                JSONObject nach = zuruecklesen(z, "plan", zeit);
                JSONObject p = nach.optJSONObject("plan");
                return ergebnis(p != null && zeit.equals(p.optString("zeit")), nach);
            }
            case "plan_loeschen":
                throw new WallboxFehler("nicht_unterstuetzt", 400);
            default:
                throw new WallboxFehler("aktion_ungueltig", 400);
        }
    }

    private JSONObject zuordnung(String id) throws WallboxFehler, JSONException {
        if (id == null || !id.startsWith("ha:")) throw new WallboxFehler("ladepunkt_unbekannt", 404);
        String s = id.substring(3);
        for (int i = 0; i < zuordnungen.length(); i++) {
            JSONObject z = zuordnungen.optJSONObject(i);
            if (z != null && s.equals(z.optString("schluessel"))) return z;
        }
        throw new WallboxFehler("ladepunkt_unbekannt", 404);
    }

    /** HA übernimmt Befehle oft verzögert (Wallbox-Integration fragt das Gerät ab): bis zu 4× nachsehen. */
    private JSONObject zuruecklesen(JSONObject z, String feld, Object soll) throws WallboxFehler, JSONException {
        JSONObject nach = null;
        for (int i = 0; i < 4; i++) {
            schlaf.ms(i == 0 ? 400 : 1000);
            nach = ladepunkt(z);
            Object ist = nach.opt(feld);
            if ("plan".equals(feld)) ist = nach.optJSONObject("plan") == null ? null : nach.optJSONObject("plan").optString("zeit");
            if (ist != null && String.valueOf(ist).equals(String.valueOf(soll))) break;
        }
        return nach;
    }

    private void dienst(String domaene, String dienst, JSONObject daten) throws WallboxFehler, JSONException {
        WallboxHttp.Antwort a = http.anfrage("POST", basis + "/api/services/" + domaene + "/" + dienst, kopf(), daten.toString());
        EvccQuelle.pruefeCode(a);
    }

    private static JSONObject ergebnis(boolean ok, JSONObject nach) throws JSONException {
        return new JSONObject().put("uebernommen", ok).put("ladepunkt", nach);
    }

    private Map<String, String> kopf() throws JSONException {
        Map<String, String> k = new HashMap<>();
        k.put("Authorization", "Bearer " + token);
        return k;
    }
}
