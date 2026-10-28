package at.greenchili.surfmate.gewohnheiten;

import at.greenchili.surfmate.automation.Automation;
import at.greenchili.surfmate.automation.AutomationAction;
import at.greenchili.surfmate.automation.AutomationCondition;
import at.greenchili.surfmate.automation.condition.BydEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 0.1.23 Gewohnheiten E3 (KONZEPT §4/§5): „Ja, automatisch“ legt eine ganz normale Automation an – keine zweite
 * Regel-Maschine. Auslöser Uhrzeit, Bedingungen Uhrzeit, Tagestyp, Ort (Name aus {@code Orte}, wie die Ruhezonen) und
 * höchstens eine Zusatzbedingung; Aktion aus dem bestehenden Katalog. Modus {@link Automation#MODE_PROBE}: zuerst nur
 * melden. Vorheizen startet 15 min vor der gewohnten Uhrzeit (Entscheidung F3: „um 7:00 warm“).
 */
public final class RegelBau {

    public static final int VORHEIZEN_MIN = 15;
    static final String[] WERKTAGE = {"monday", "tuesday", "wednesday", "thursday", "friday"};
    static final String[] WOCHENENDE = {"saturday", "sunday"};

    private RegelBau() {}

    /** Fehler beim Bau (Grund für die Antwort an die Karte). */
    public static final class Fehler extends Exception {
        public final String grund;

        Fehler(String grund) {
            super(grund);
            this.grund = grund;
        }
    }

    /** Minute, zu der die Regel schaltet (Vorheizen 15 min früher). */
    public static int schaltMinute(JSONObject v) {
        int m = v.optInt("minute");
        if (Aktionen.KLIMA_AN.equals(v.optString("aktion"))) m = (m - VORHEIZEN_MIN + 1440) % 1440;
        return m;
    }

    /**
     * @param v    ein Vorschlag aus dem {@link GewohnheitsErkenner}
     * @param orte Ortsliste (ORTE_VERTRAG) für den Namen des Ortes
     */
    public static Automation baue(JSONObject v, JSONArray orte) throws Exception {
        String aktion = v.optString("aktion");
        if (!Aktionen.VORSCHLAGBAR.contains(aktion)) throw new Fehler("nicht_vorschlagbar");
        int minute = schaltMinute(v);

        List<AutomationCondition> bed = new ArrayList<>();
        bed.add(new AutomationCondition(BydEvent.TIME, "eq", minute));
        // Tagestyp als UND-Liste von „nicht“ (werktags = weder Samstag noch Sonntag)
        for (String t : Kontext.WOCHENENDE.equals(v.optString("tag")) ? WERKTAGE : WOCHENENDE) {
            bed.add(new AutomationCondition(BydEvent.DAY, "neq", t));
        }
        String ortName = ortName(orte, v.optString("ort"));
        if (ortName == null) throw new Fehler("ort_unbekannt");
        bed.add(new AutomationCondition(BydEvent.LOCATION_ZONE, "eq", ortName));
        JSONObject b = v.optJSONObject("bedingung");
        if (b != null && b.has("aussen_unter")) bed.add(new AutomationCondition(BydEvent.OUTSIDE_TEMPERATURE, "lt", b.getInt("aussen_unter")));
        if (b != null && b.optBoolean("angesteckt", false)) bed.add(new AutomationCondition(BydEvent.CHARGE_GUN, "eq", "connected"));

        List<AutomationAction> akt = new ArrayList<>();
        akt.add(aktion(aktion, v.optJSONObject("wert") == null ? new JSONObject() : v.getJSONObject("wert")));

        List<at.greenchili.surfmate.automation.condition.EventData> ausl = new ArrayList<>();
        ausl.add(BydEvent.TIME);
        Automation a = new Automation(ausl, bed, 0, akt, false);
        a.setMode(Automation.MODE_PROBE);
        a.setName(name(v, minute));
        JSONObject g = new JSONObject();
        g.put("schluessel", v.optString("schluessel"));
        g.put("aktion", aktion);
        g.put("wert", v.optJSONObject("wert") == null ? new JSONObject() : new JSONObject(v.getJSONObject("wert").toString()));
        g.put("tag", v.optString("tag"));
        g.put("ort", v.optString("ort"));
        g.put("minute", v.optInt("minute"));
        g.put("treffer", v.optJSONArray("treffer") == null ? new JSONArray() : v.getJSONArray("treffer"));
        g.put("richtig", 0);
        g.put("angelegt", System.currentTimeMillis());
        a.setGewohnheit(g);
        // Gegenprobe: genau so, wie es gespeichert und geladen wird
        Automation geprueft = Automation.fromJson(a.toJson());
        if (geprueft == null) throw new Fehler("regel_ungueltig");
        return geprueft;
    }

    /** Aktion aus dem bestehenden Katalog (Actions). Nie Aufsperren, Licht, Hupe … (nicht vorschlagbar). */
    static AutomationAction aktion(String aktion, JSONObject w) throws Fehler {
        Map<String, Object> v = new LinkedHashMap<>();
        switch (aktion) {
            case Aktionen.KLIMA_AN:
            case Aktionen.KLIMA_AUS:
                v.put("action", Aktionen.KLIMA_AN.equals(aktion) ? "on" : "off");
                v.put("temp", Math.max(17, Math.min(33, (int) Math.round(w.optDouble("temp", 22)))));   // 0.1.26: Stufe 1–17
                v.put("remoteDurationMinutes", "20");
                v.put("autoOffMinutes", "0");
                return new AutomationAction("setAc", v);
            case Aktionen.KLIMA_TEMP:
                v.put("temperature", Math.max(17, Math.min(33, (int) Math.round(w.optDouble("temp", 22)))));
                v.put("zone", "beifahrer".equals(w.optString("zone")) ? "2" : "1");
                return new AutomationAction("setAcTemp", v);
            case Aktionen.SITZHEIZUNG:
            case Aktionen.SITZLUEFTUNG:
                v.put("type", Aktionen.SITZHEIZUNG.equals(aktion) ? "heat" : "vent");
                v.put("area", "beifahrer".equals(w.optString("sitz")) ? "passenger" : "driver");
                int s = w.optInt("stufe", 0);
                v.put("payload", s <= 0 ? "off" : s == 1 ? "low" : "high");
                return new AutomationAction("seat", v);
            case Aktionen.LENKRADHEIZUNG:
                v.put("action", w.optBoolean("an", false) ? "on" : "off");
                return new AutomationAction("steeringHeat", v);
            case Aktionen.WALLBOX_START:
            case Aktionen.WALLBOX_STOPP:
                v.put("modus", Aktionen.WALLBOX_START.equals(aktion) ? "sofort" : "aus");
                return new AutomationAction("wallboxModus", v);
            case Aktionen.WALLBOX_STROM:
                v.put("strom", Math.max(6, Math.min(32, w.optInt("a", 16))));
                return new AutomationAction("wallboxStrom", v);
            case Aktionen.WAECHTER_AN:
                v.put("action", "enable");
                return new AutomationAction("surveillance", v);
            case Aktionen.CAMPING_AN:
            case Aktionen.CAMPING_AUS:
                v.put("an", Aktionen.CAMPING_AN.equals(aktion) ? "true" : "false");
                return new AutomationAction("camping", v);
            default:
                throw new Fehler("nicht_vorschlagbar");
        }
    }

    /** „zuhause“ → Name des Ortes mit {@code eigenschaften.zuhause}; sonst der Ort mit dieser Id. */
    static String ortName(JSONArray orte, String ort) {
        if (orte == null || ort == null || ort.isEmpty()) return null;
        for (int i = 0; i < orte.length(); i++) {
            JSONObject o = orte.optJSONObject(i);
            if (o == null) continue;
            JSONObject eig = o.optJSONObject("eigenschaften");
            boolean passt = Kontext.ZUHAUSE.equals(ort) ? eig != null && eig.optBoolean("zuhause") : ort.equals(o.optString("id"));
            if (passt && !o.optString("name").isEmpty()) return o.optString("name");
        }
        return null;
    }

    /** Name in der Automations-Liste (Sprache der Anlage; die Karte baut ihren Satz selbst aus Bausteinen). */
    static String name(JSONObject v, int minute) {
        return "Gewohnheit · " + v.optString("aktion") + " · " + v.optString("tag") + " "
                + String.format(java.util.Locale.ROOT, "%02d:%02d", minute / 60, minute % 60);
    }
}
