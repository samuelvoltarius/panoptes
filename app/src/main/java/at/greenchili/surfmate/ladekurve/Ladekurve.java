package at.greenchili.surfmate.ladekurve;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 0.1.19 Ladekurve (27.10.2026): beim DC-Laden SoC → kW messen, lokal speichern; Teilen nur mit Einwilligung
 * („Ladekurve anonym teilen“, Standard aus, {@link LadekurveTeilen}). Reine Logik ohne Android, damit Bänder, Median,
 * Qualität und die geteilte Zusammenfassung mit echten Zahlen testbar sind.
 *
 * <p>Je Ladung (Sitzung) wird pro SoC-Band ({@link #BAND} %) EIN Wert gemerkt: der Median der Messpunkte in diesem
 * Band (mindestens {@link #MIN_PUNKTE_JE_BAND}). So zählt jede Ladung gleich, egal wie lange sie im Band war.
 * Qualität je Ladung: {@code gemessen} (echte Leistung, mindestens die Hälfte der Punkte MEASURED), sonst
 * {@code geschaetzt} (Schätzer aus der SoC-Steigung, z. B. ringEstimator), {@code sitzung} (nur Durchschnitt der Ladung
 * aus dem Verlauf, Startkurve). Leistung = in den Akku (Pack-Seite), wie der Planer sie für DC rechnet.
 *
 * <p>Lokaler Speicher: {@code {version, importiert, sitzungen:[{start, q, b:{"10":41.2, …}}]}} – höchstens
 * {@link #MAX_SITZUNGEN} Ladungen, älteste fallen weg. {@code start} bleibt im Auto (nie geteilt).
 */
public final class Ladekurve {

    private Ladekurve() {}

    public static final int VERSION = 1;
    /** Bandbreite in SoC-Prozent. */
    public static final int BAND = 5;
    public static final int MAX_SITZUNGEN = 200;
    static final int MIN_PUNKTE_JE_BAND = 2;
    /** Für eine Band-Angabe in der geteilten Kurve mindestens so viele Ladungen. */
    public static final int MIN_LADUNGEN_JE_BAND = 2;
    /** Geteilt wird erst ab so vielen Ladungen insgesamt (eine einzelne Ladung sagt nichts über ein Modell). */
    public static final int MIN_LADUNGEN_TEILEN = 3;
    static final double KW_MIN = 1.0, KW_MAX = 400.0;

    public static final String Q_GEMESSEN = "gemessen";
    public static final String Q_GESCHAETZT = "geschaetzt";
    public static final String Q_SITZUNG = "sitzung";

    /** Messpunkt während einer Ladung. */
    public static final class Punkt {
        final double soc, kw;
        final boolean gemessen;

        public Punkt(double soc, double kw, boolean gemessen) {
            this.soc = soc;
            this.kw = kw;
            this.gemessen = gemessen;
        }
    }

    /** Untere Grenze des Bandes für einen SoC (0, 5, …, 95). */
    public static int band(double soc) {
        int b = (int) Math.floor(soc / BAND) * BAND;
        return Math.max(0, Math.min(100 - BAND, b));
    }

    static boolean gueltig(double soc, double kw) {
        return soc >= 0 && soc <= 100 && kw >= KW_MIN && kw <= KW_MAX && !Double.isNaN(soc) && !Double.isNaN(kw);
    }

    static double median(List<Double> v) {
        if (v.isEmpty()) return Double.NaN;
        List<Double> s = new ArrayList<>(v);
        Collections.sort(s);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0;
    }

    static double runde(double kw) {
        return Math.round(kw * 10) / 10.0;
    }

    /**
     * Eine Ladung zusammenfassen: {@code {start, q, b}} oder null (keine verwertbaren Bänder).
     */
    public static JSONObject sitzung(long start, List<Punkt> punkte) throws Exception {
        Map<Integer, List<Double>> je = new TreeMap<>();
        int gueltige = 0, gemessen = 0;
        for (Punkt p : punkte) {
            if (!gueltig(p.soc, p.kw)) continue;
            gueltige++;
            if (p.gemessen) gemessen++;
            List<Double> l = je.get(band(p.soc));
            if (l == null) { l = new ArrayList<>(); je.put(band(p.soc), l); }
            l.add(p.kw);
        }
        JSONObject b = new JSONObject();
        for (Map.Entry<Integer, List<Double>> e : je.entrySet()) {
            if (e.getValue().size() >= MIN_PUNKTE_JE_BAND) b.put(String.valueOf(e.getKey()), runde(median(e.getValue())));
        }
        if (b.length() == 0) return null;
        return new JSONObject().put("start", start).put("q", gemessen * 2 >= gueltige ? Q_GEMESSEN : Q_GESCHAETZT).put("b", b);
    }

    /**
     * Startkurve aus dem Verlauf, wenn keine Messpunkte da sind: Durchschnittsleistung der Ladung für jedes Band, das
     * ganz zwischen Start- und End-SoC liegt (grob, Qualität {@code sitzung}). null bei unbrauchbaren Werten.
     */
    public static JSONObject ausDurchschnitt(long start, double socStart, double socEnde, double kwSchnitt) throws Exception {
        if (!gueltig(socStart, kwSchnitt) || !gueltig(socEnde, kwSchnitt) || socEnde - socStart < BAND) return null;
        JSONObject b = new JSONObject();
        for (int u = 0; u < 100; u += BAND) {
            if (u >= socStart && u + BAND <= socEnde) b.put(String.valueOf(u), runde(kwSchnitt));
        }
        if (b.length() == 0) return null;
        return new JSONObject().put("start", start).put("q", Q_SITZUNG).put("b", b);
    }

    /** Leerer Speicher. */
    public static JSONObject leer() throws Exception {
        return new JSONObject().put("version", VERSION).put("importiert", false).put("sitzungen", new JSONArray());
    }

    /** Ladung aufnehmen (gleicher {@code start} ersetzt), höchstens {@link #MAX_SITZUNGEN}, nach Start sortiert. */
    public static void aufnehmen(JSONObject speicher, JSONObject sitzung) throws Exception {
        if (sitzung == null) return;
        JSONArray alt = speicher.optJSONArray("sitzungen");
        List<JSONObject> l = new ArrayList<>();
        for (int i = 0; alt != null && i < alt.length(); i++) {
            JSONObject s = alt.optJSONObject(i);
            if (s != null && s.optLong("start") != sitzung.optLong("start")) l.add(s);
        }
        l.add(sitzung);
        Collections.sort(l, (a, c) -> Long.compare(a.optLong("start"), c.optLong("start")));
        while (l.size() > MAX_SITZUNGEN) l.remove(0);
        speicher.put("sitzungen", new JSONArray(l));
    }

    /**
     * Kurve je Band aus allen Ladungen: {@code [{soc_von, soc_bis, kw, n, qualitaet}]}, aufsteigend. Je Band zählt die
     * beste Stufe mit mindestens {@code minN} Ladungen: gemessen, sonst gemessen+geschätzt, sonst inklusive Sitzungs-
     * Durchschnitt. {@code kw} = Median über die Ladungen.
     */
    public static JSONArray kurve(JSONObject speicher, int minN) throws Exception {
        Map<Integer, List<Double>> mess = new TreeMap<>(), schaetz = new TreeMap<>(), sitz = new TreeMap<>();
        JSONArray s = speicher == null ? null : speicher.optJSONArray("sitzungen");
        for (int i = 0; s != null && i < s.length(); i++) {
            JSONObject x = s.optJSONObject(i);
            JSONObject b = x == null ? null : x.optJSONObject("b");
            if (b == null) continue;
            String q = x.optString("q");
            Map<Integer, List<Double>> ziel = Q_GEMESSEN.equals(q) ? mess : Q_GESCHAETZT.equals(q) ? schaetz : sitz;
            java.util.Iterator<String> it = b.keys();
            while (it.hasNext()) {
                String k = it.next();
                int u;
                try { u = Integer.parseInt(k); } catch (NumberFormatException e) { continue; }
                double kw = b.optDouble(k, Double.NaN);
                if (u < 0 || u > 100 - BAND || u % BAND != 0 || !gueltig(u, kw)) continue;
                List<Double> l = ziel.get(u);
                if (l == null) { l = new ArrayList<>(); ziel.put(u, l); }
                l.add(kw);
            }
        }
        JSONArray out = new JSONArray();
        for (int u = 0; u < 100; u += BAND) {
            List<Double> m = mess.getOrDefault(u, Collections.<Double>emptyList());
            List<Double> ms = new ArrayList<>(m);
            ms.addAll(schaetz.getOrDefault(u, Collections.<Double>emptyList()));
            List<Double> alle = new ArrayList<>(ms);
            alle.addAll(sitz.getOrDefault(u, Collections.<Double>emptyList()));
            List<Double> w;
            String q;
            if (m.size() >= minN) { w = m; q = Q_GEMESSEN; }
            else if (ms.size() >= minN) { w = ms; q = Q_GESCHAETZT; }
            else if (alle.size() >= minN) { w = alle; q = Q_SITZUNG; }
            else continue;
            out.put(new JSONObject().put("soc_von", u).put("soc_bis", u + BAND).put("kw", runde(median(w)))
                    .put("n", w.size()).put("qualitaet", q));
        }
        return out;
    }

    /** Anzahl Ladungen im Speicher. */
    public static int ladungen(JSONObject speicher) {
        JSONArray s = speicher == null ? null : speicher.optJSONArray("sitzungen");
        return s == null ? 0 : s.length();
    }

    /**
     * Geteilte Zusammenfassung (Vertrag LADEKURVE_VERTRAG.md): nur Modell, Bänder, Median, Anzahl – keine Zeit, kein
     * Ort, keine VIN, keine einzelnen Ladungen. null, wenn zu wenig Daten oder kein Modell bekannt.
     */
    public static JSONObject zusammenfassung(JSONObject speicher, String fahrzeug, String kennung, String version)
            throws Exception {
        if (fahrzeug == null || fahrzeug.trim().isEmpty() || kennung == null || kennung.isEmpty()) return null;
        if (ladungen(speicher) < MIN_LADUNGEN_TEILEN) return null;
        JSONArray k = kurve(speicher, MIN_LADUNGEN_JE_BAND);
        if (k.length() == 0) return null;
        JSONObject o = new JSONObject().put("kennung", kennung).put("fahrzeug", fahrzeug.trim())
                .put("quelle", "panoptes").put("baender", k).put("ladungen", Math.min(ladungen(speicher), MAX_SITZUNGEN));
        if (version != null && version.matches("[0-9][0-9A-Za-z.-]{0,15}")) o.put("version", version);
        return o;
    }

    /** Kurzer Fingerabdruck der geteilten Bänder (nur senden, wenn sich etwas geändert hat). */
    public static String fingerabdruck(JSONObject zusammenfassung) {
        if (zusammenfassung == null) return "";
        return Integer.toHexString((zusammenfassung.optString("fahrzeug") + "|"
                + zusammenfassung.optJSONArray("baender")).hashCode()).toLowerCase(Locale.ROOT);
    }
}
