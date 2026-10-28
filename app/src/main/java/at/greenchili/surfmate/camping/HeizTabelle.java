package at.greenchili.surfmate.camping;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Kennfeld für Intervall-Heizen ohne Innenraumfühler (CAMPING_PLAN.md 2.1): Außentemperatur → alle x min für
 * y min mit Heizstufe S. Die Werte stehen in {@code assets/camping/heiztabelle.json} (bzw. einer lokalen
 * Kopie, die den Asset ersetzt) – nichts davon ist im Code festgelegt.
 *
 * <p>Zwischen den Zeilen wird linear gerechnet: Taktanteil (Dauer/Intervall) und Stufe werden interpoliert,
 * das Intervall folgt aus Dauer ÷ Taktanteil. Außerhalb der Tabelle gilt die jeweils letzte Zeile.
 * Die Wahl mild/warm/sehr warm und die gelernte Verschiebung verschieben die Außentemperatur, mit der
 * nachgeschlagen wird (zu kalt → kleinere Temperatur → mehr Heizen).
 */
public final class HeizTabelle {

    public static final String MILD = "mild", WARM = "warm", SEHR_WARM = "sehr_warm";

    /** Eine (ggf. interpolierte) Zeile. */
    public static final class Zeile {
        public final double aussenC;
        public final int intervallMin, dauerMin, stufe, geblaese;
        Zeile(double aussenC, int intervallMin, int dauerMin, int stufe, int geblaese) {
            this.aussenC = aussenC; this.intervallMin = intervallMin; this.dauerMin = dauerMin;
            this.stufe = stufe; this.geblaese = geblaese;
        }
        public double takt() { return intervallMin <= 0 ? 0 : (double) dauerMin / intervallMin; }
        public JSONObject alsJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("intervallMin", intervallMin).put("dauerMin", dauerMin).put("stufe", stufe)
                        .put("geblaese", geblaese).put("taktProzent", Math.round(takt() * 100));
            } catch (Exception ignored) { }
            return o;
        }
    }

    private final List<Zeile> zeilen;
    private final Map<String, Double> verschiebung;
    private final TreeMap<Integer, Double> leistung;
    public final double grundlastKwAutoAn;
    public final int vorheizStufe, vorheizGeblaese;
    public final int morgensDauerMin, morgensStufe, morgensGeblaese;
    public final int mindestDauerMin, hoechstIntervallMin;
    public final double ersatzAussenC;
    /** Cloud-Sollwert (SDK-Skala 17–33, 0.1.26) für H2-Sitzungen; 33 = Stufe 17 = stärkstes Heizen wie bisher Roh 17. */
    public final int cloudTemperatur;

    private HeizTabelle(List<Zeile> zeilen, Map<String, Double> verschiebung, TreeMap<Integer, Double> leistung,
                        JSONObject j) {
        this.zeilen = zeilen;
        this.verschiebung = verschiebung;
        this.leistung = leistung;
        this.grundlastKwAutoAn = j.optDouble("grundlastKwAutoAn", Double.NaN);
        JSONObject v = j.optJSONObject("vorheizen");
        this.vorheizStufe = v == null ? 0 : v.optInt("stufe", 0);
        this.vorheizGeblaese = v == null ? 0 : v.optInt("geblaese", 0);
        JSONObject m = j.optJSONObject("morgens");
        this.morgensDauerMin = m == null ? 0 : m.optInt("dauerMin", 0);
        this.morgensStufe = m == null ? 0 : m.optInt("stufe", 0);
        this.morgensGeblaese = m == null ? 0 : m.optInt("geblaese", 0);
        this.mindestDauerMin = j.optInt("mindestDauerMin", 10);
        this.hoechstIntervallMin = j.optInt("hoechstIntervallMin", 240);
        this.ersatzAussenC = j.optDouble("ersatzAussenC", 0);
        JSONObject c = j.optJSONObject("cloud");
        // 0.1.26: SDK-Skala; der alte Wert 31 bedeutete Roh 17 (°C − 14) = stärkstes Heizen, jetzt 33 (Roh = Stufe 17)
        this.cloudTemperatur = c == null ? 33 : Math.max(17, Math.min(33, c.optInt("temperaturHeizen", 33)));
    }

    /** Liest das Kennfeld; wirft bei fehlender oder unsinniger Tabelle (dann heizt der Regler nicht). */
    public static HeizTabelle aus(String json) throws Exception {
        JSONObject j = new JSONObject(json);
        JSONArray a = j.getJSONArray("zeilen");
        List<Zeile> z = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject r = a.getJSONObject(i);
            int iv = r.getInt("intervallMin"), du = r.getInt("dauerMin"), st = r.getInt("stufe");
            if (iv <= 0 || du <= 0 || du > iv) throw new IllegalArgumentException("Zeile " + i + ": Dauer/Intervall");
            if (st < 17 || st > 33) throw new IllegalArgumentException("Zeile " + i + ": Stufe 17–33");
            z.add(new Zeile(r.getDouble("aussenC"), iv, du, st, r.optInt("geblaese", 2)));
        }
        if (z.isEmpty()) throw new IllegalArgumentException("keine Zeilen");
        Collections.sort(z, (x, y) -> Double.compare(x.aussenC, y.aussenC));
        Map<String, Double> vs = new java.util.HashMap<>();
        JSONObject v = j.optJSONObject("verschiebungK");
        if (v != null) {
            for (Iterator<String> it = v.keys(); it.hasNext(); ) {
                String k = it.next();
                vs.put(k, v.getDouble(k));
            }
        }
        TreeMap<Integer, Double> le = new TreeMap<>();
        JSONObject l = j.optJSONObject("leistungKwJeStufe");
        if (l != null) {
            for (Iterator<String> it = l.keys(); it.hasNext(); ) {
                String k = it.next();
                le.put(Integer.parseInt(k), l.getDouble(k));
            }
        }
        return new HeizTabelle(z, vs, le, j);
    }

    /** Verschiebung in K für die Wahl (mild +, sehr warm −); unbekannte Wahl = 0. */
    public double verschiebungK(String wahl) {
        Double d = wahl == null ? null : verschiebung.get(wahl);
        return d == null ? 0 : d;
    }

    /**
     * Zeile für die Außentemperatur, verschoben um Wahl und Lernwert.
     *
     * @param aussenC   Außentemperatur oder NaN (dann {@link #ersatzAussenC})
     * @param wahl      mild / warm / sehr_warm
     * @param gelerntK  Lern-Verschiebung in K (negativ = mehr heizen)
     */
    public Zeile fuer(double aussenC, String wahl, double gelerntK) {
        double t = (Double.isNaN(aussenC) ? ersatzAussenC : aussenC) + verschiebungK(wahl) + gelerntK;
        Zeile erste = zeilen.get(0), letzte = zeilen.get(zeilen.size() - 1);
        if (t <= erste.aussenC) return new Zeile(t, erste.intervallMin, erste.dauerMin, erste.stufe, erste.geblaese);
        if (t >= letzte.aussenC) return new Zeile(t, letzte.intervallMin, letzte.dauerMin, letzte.stufe, letzte.geblaese);
        for (int i = 1; i < zeilen.size(); i++) {
            Zeile a = zeilen.get(i - 1), b = zeilen.get(i);
            if (t <= b.aussenC) {
                double f = (t - a.aussenC) / (b.aussenC - a.aussenC);
                double takt = a.takt() + f * (b.takt() - a.takt());
                int dauer = (int) Math.round(a.dauerMin + f * (b.dauerMin - a.dauerMin));
                int stufe = (int) Math.round(a.stufe + f * (b.stufe - a.stufe));
                int intervall = (int) Math.round(dauer / Math.max(takt, 1e-6));
                int geblaese = f < 0.5 ? a.geblaese : b.geblaese;
                return new Zeile(t, Math.max(intervall, dauer), dauer, stufe, geblaese);
            }
        }
        return new Zeile(t, letzte.intervallMin, letzte.dauerMin, letzte.stufe, letzte.geblaese);
    }

    /** Geschätzte Heizleistung (kW) für die Stufe, linear zwischen den Kennfeldpunkten; NaN, wenn keine. */
    public double leistungKw(int stufe) {
        if (leistung.isEmpty()) return Double.NaN;
        Map.Entry<Integer, Double> lo = leistung.floorEntry(stufe), hi = leistung.ceilingEntry(stufe);
        if (lo == null) return hi.getValue();
        if (hi == null) return lo.getValue();
        if (lo.getKey().equals(hi.getKey())) return lo.getValue();
        double f = (double) (stufe - lo.getKey()) / (hi.getKey() - lo.getKey());
        return lo.getValue() + f * (hi.getValue() - lo.getValue());
    }

    public int zeilenAnzahl() { return zeilen.size(); }
}
