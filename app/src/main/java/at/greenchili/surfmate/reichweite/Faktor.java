package at.greenchili.surfmate.reichweite;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Verbrauchsfaktor – verbindliche Rechenweise aus dem gemeinsamen Modul panoptes-karte 1.1.0
 * (verhaeltnis, faktorAus, faktorMeldung; Koordination 21.10.2026). Rechnet nur im Auto; zum
 * Planer geht ausschließlich der fertige Faktor, nie einzelne Fahrten.
 * Prüfdaten: /tiles/lib/panoptes-karte-1.1.0.pruefdaten.json (Testressource mit sha256).
 */
public final class Faktor {

    public static final double MIN = 0.70;
    public static final double MAX = 1.40;
    public static final double K = 4;
    public static final double PLAUS_MIN = 0.4;
    public static final double PLAUS_MAX = 2.5;
    public static final int MAX_JE_PROFIL = 200;

    private Faktor() {}

    static double runden(double x, int stellen) {
        double f = Math.pow(10, stellen);
        return Math.round(x * f) / f;
    }

    /**
     * Verhältnis echt/Modell einer Fahrt oder null (unplausibel/unvollständig).
     *
     * @param kwh echter Verbrauch in kWh oder null; dann aus akkuKwh·(socStart−socEnde)/100
     */
    public static Double verhaeltnis(Double modellKwh, Double kwh, Double akkuKwh, Double socStart, Double socEnde) {
        if (modellKwh == null || !(modellKwh > 0)) return null;
        Double k = kwh;
        if (k == null) {
            if (akkuKwh == null || !(akkuKwh > 0) || socStart == null || socEnde == null) return null;
            k = akkuKwh * (socStart - socEnde) / 100.0;
        }
        double v = k / modellKwh;
        if (Double.isNaN(v) || Double.isInfinite(v) || v < PLAUS_MIN || v > PLAUS_MAX) return null;
        return runden(v, 4);
    }

    /** Median, zu 1,0 geschrumpft mit n/(n+4), begrenzt 0,70–1,40. Ungerundet. */
    public static double faktorAus(List<Double> verhaeltnisse) {
        List<Double> v = new ArrayList<>();
        if (verhaeltnisse != null) {
            for (Double x : verhaeltnisse) if (x != null && !x.isNaN() && !x.isInfinite()) v.add(x);
        }
        Collections.sort(v);
        int n = v.size();
        if (n == 0) return 1.0;
        double med = n % 2 == 1 ? v.get((n - 1) / 2) : (v.get(n / 2 - 1) + v.get(n / 2)) / 2.0;
        double f = 1.0 + (med - 1.0) * (n / (n + K));
        return Math.max(MIN, Math.min(MAX, f));
    }

    /** Neue Liste mit angehängtem Verhältnis, höchstens MAX_JE_PROFIL (älteste fallen raus). */
    public static List<Double> verhaeltnisAnhaengen(List<Double> liste, double v) {
        List<Double> alt = liste == null ? new ArrayList<>() : new ArrayList<>(liste);
        while (alt.size() > MAX_JE_PROFIL - 1) alt.remove(0);
        alt.add(v);
        return alt;
    }

    /**
     * Body für POST /ev/api/mein-faktor: nur {fahrzeug, faktor, fahrten, quelle, auto}, nie Fahrten.
     */
    public static JSONObject faktorMeldung(String fahrzeug, List<Double> verhaeltnisse, String quelle, String auto) {
        if (fahrzeug == null || fahrzeug.isEmpty()) throw new IllegalArgumentException("fahrzeug fehlt");
        if (quelle == null || quelle.isEmpty()) throw new IllegalArgumentException("quelle fehlt");
        List<Double> liste = new ArrayList<>();
        if (verhaeltnisse != null) {
            for (Double x : verhaeltnisse) if (x != null && !x.isNaN() && !x.isInfinite()) liste.add(x);
        }
        JSONObject d = new JSONObject();
        try {
            d.put("fahrzeug", fahrzeug);
            d.put("faktor", runden(faktorAus(liste), 3));
            d.put("fahrten", liste.size());
            d.put("quelle", quelle);
            if (auto != null && !auto.isEmpty()) d.put("auto", auto);
        } catch (Exception ignored) {
        }
        return d;
    }
}
