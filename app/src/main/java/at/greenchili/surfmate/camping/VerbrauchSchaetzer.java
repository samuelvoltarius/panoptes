package at.greenchili.surfmate.camping;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Misst den Verbrauch im Stand aus SoC-Proben (CAMPING_PLAN.md 2.1/3.3): Grundlast und zusätzliche
 * Heizlast in %/h, per kleinster Quadrate über das Fenster (Standard 120 min).
 *
 * <p>Modell: SoC(t) = a − g·t − h·H(t), mit t in Stunden seit der ersten Probe und H(t) den bis dahin
 * geheizten Stunden. Ohne Wechsel zwischen Heizen und Pause (H ändert sich kaum) wird nur eine Gesamtrate
 * geschätzt. Unter 30 min Messdauer oder 3 Proben: noch keine Schätzung (NaN).
 */
public final class VerbrauchSchaetzer {

    public static final long MIN_SPANNE_MS = 30 * 60_000L;

    private static final class Probe {
        final long t; final double soc; final double heizStunden;
        Probe(long t, double soc, double heizStunden) { this.t = t; this.soc = soc; this.heizStunden = heizStunden; }
    }

    private final long fensterMs;
    private final Deque<Probe> proben = new ArrayDeque<>();

    public VerbrauchSchaetzer() { this(120 * 60_000L); }
    public VerbrauchSchaetzer(long fensterMs) { this.fensterMs = fensterMs; }

    /** Probe ablegen. {@code heizStundenKumuliert} = bis jetzt geheizte Stunden (monoton). */
    public synchronized void probe(long tMs, double socProzent, double heizStundenKumuliert) {
        if (Double.isNaN(socProzent) || socProzent <= 0 || socProzent > 100) return;
        Probe letzte = proben.peekLast();
        if (letzte != null && tMs <= letzte.t) return;
        proben.addLast(new Probe(tMs, socProzent, heizStundenKumuliert));
        while (!proben.isEmpty() && tMs - proben.peekFirst().t > fensterMs) proben.removeFirst();
    }

    public synchronized void leeren() { proben.clear(); }

    /** Ergebnis einer Schätzung (Raten positiv = Verbrauch). */
    public static final class Ergebnis {
        public final double gesamtProzentProStunde, grundProzentProStunde, heizZusatzProzentProStunde;
        public final long spanneMs;
        Ergebnis(double gesamt, double grund, double heiz, long spanneMs) {
            this.gesamtProzentProStunde = gesamt; this.grundProzentProStunde = grund;
            this.heizZusatzProzentProStunde = heiz; this.spanneMs = spanneMs;
        }
        public boolean gemessen() { return !Double.isNaN(gesamtProzentProStunde); }
    }

    public synchronized Ergebnis schaetzen() {
        int n = proben.size();
        if (n < 3) return new Ergebnis(Double.NaN, Double.NaN, Double.NaN, 0);
        long t0 = proben.peekFirst().t, spanne = proben.peekLast().t - t0;
        if (spanne < MIN_SPANNE_MS) return new Ergebnis(Double.NaN, Double.NaN, Double.NaN, spanne);
        double[] x = new double[n], h = new double[n], y = new double[n];
        double h0 = proben.peekFirst().heizStunden;
        int i = 0;
        for (Probe p : proben) {
            x[i] = (p.t - t0) / 3_600_000.0;
            h[i] = p.heizStunden - h0;
            y[i] = p.soc;
            i++;
        }
        double gesamt = -steigung(x, y);
        double hSpanne = h[n - 1] - h[0];
        double stunden = spanne / 3_600_000.0;
        // Nur zerlegen, wenn sowohl geheizt als auch pausiert wurde (je ≥ 10 % der Spanne)
        if (hSpanne >= 0.1 * stunden && hSpanne <= 0.9 * stunden) {
            double[] gh = zweiRegressoren(x, h, y);
            if (gh != null) {
                double grund = -gh[0], heiz = -gh[1];
                if (grund >= 0 && heiz >= 0) return new Ergebnis(gesamt, grund, heiz, spanne);
            }
        }
        return new Ergebnis(gesamt, Double.NaN, Double.NaN, spanne);
    }

    static double steigung(double[] x, double[] y) {
        int n = x.length;
        double mx = 0, my = 0;
        for (int i = 0; i < n; i++) { mx += x[i]; my += y[i]; }
        mx /= n; my /= n;
        double sxy = 0, sxx = 0;
        for (int i = 0; i < n; i++) { sxy += (x[i] - mx) * (y[i] - my); sxx += (x[i] - mx) * (x[i] - mx); }
        return sxx == 0 ? Double.NaN : sxy / sxx;
    }

    /** y = a + b·x + c·h → {b, c}, oder null bei schlecht bestimmtem System. */
    static double[] zweiRegressoren(double[] x, double[] h, double[] y) {
        int n = x.length;
        double mx = 0, mh = 0, my = 0;
        for (int i = 0; i < n; i++) { mx += x[i]; mh += h[i]; my += y[i]; }
        mx /= n; mh /= n; my /= n;
        double sxx = 0, shh = 0, sxh = 0, sxy = 0, shy = 0;
        for (int i = 0; i < n; i++) {
            double dx = x[i] - mx, dh = h[i] - mh, dy = y[i] - my;
            sxx += dx * dx; shh += dh * dh; sxh += dx * dh; sxy += dx * dy; shy += dh * dy;
        }
        double det = sxx * shh - sxh * sxh;
        if (Math.abs(det) < 1e-9 * Math.max(1, sxx * shh)) return null;
        double b = (sxy * shh - shy * sxh) / det;
        double c = (shy * sxx - sxy * sxh) / det;
        return new double[]{b, c};
    }
}
