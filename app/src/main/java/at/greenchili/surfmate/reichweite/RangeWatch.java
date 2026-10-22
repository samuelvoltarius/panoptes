package at.greenchili.surfmate.reichweite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * F1 „Reicht es noch?“ – Rechnung im Auto (F1_KONZEPT.md §3/§4, Koordination 21.10.2026).
 * Rein, ohne Android; der Daemon füttert Plan und Messwerte, die Web-Seite liest das Ergebnis.
 *
 * <ol>
 *   <li>Fortschritt: Position auf die Planlinie projizieren → km-Stand s; nur vorwärts, Sprünge
 *       über {@link #MAX_SPRUNG_KM} erst nach {@link #SPRUNG_BESTAETIGUNG} gleichen Messungen.</li>
 *   <li>Soll-Ladestand bei s: linear zwischen (0, socStart), (km_i, socAn_i), (km_i, socAb_i),
 *       (zielKm, socAnkunft).</li>
 *   <li>Abweichung Δ = Ist − Soll.</li>
 *   <li>Faktor unterwegs ab 10 km seit dem letzten Stopp: Ist-Verbrauch / Plan-Verbrauch,
 *       gedämpft 1 + (f−1)·n/(n+4) mit n = km/10, begrenzt 0,7–1,4.</li>
 *   <li>Prognose am nächsten Stopp bzw. Ziel: Ist − Plan-Verbrauch(s → dort)·f.</li>
 * </ol>
 * Stufen mit 2-min-Flatterschutz, Rückkehr auf „Passt“ erst mit +2 % Abstand, unter 0 °C
 * Reserve +3 %. Nur aktiv auf der Route (≤ 1 km neben der Linie).
 */
public final class RangeWatch {

    public enum Stufe { PASST, KNAPP, REICHT_NICHT }

    static final double MAX_ABSTAND_KM = 1.0;
    static final double MAX_SPRUNG_KM = 2.0;
    static final int SPRUNG_BESTAETIGUNG = 3;
    static final double FAKTOR_AB_KM = 10.0;
    static final long FLATTER_MS = 120_000L;
    static final double KNAPP_ABSTAND = 5.0;
    static final double DELTA_KNAPP = -5.0;
    static final double DELTA_REICHT_NICHT_HINWEIS = -10.0;
    static final double RUECKKEHR_ABSTAND = 2.0;
    static final double KAELTE_ZUSCHLAG = 3.0;

    // ================================================================ Plan

    public static final class Stopp {
        public final String name;
        public final double km, socAn, socAb;
        /** Lage und Leistung des Stopps (für die Live-Belegung); NaN/0, wenn unbekannt. */
        public final double lat, lon, kw;
        public Stopp(String name, double km, double socAn, double socAb) {
            this(name, km, socAn, socAb, Double.NaN, Double.NaN, 0);
        }
        public Stopp(String name, double km, double socAn, double socAb, double lat, double lon, double kw) {
            this.name = name; this.km = km; this.socAn = socAn; this.socAb = socAb;
            this.lat = lat; this.lon = lon; this.kw = kw;
        }
    }

    public static final class Plan {
        final double[] lat, lon, kmKum;
        final List<Stopp> stopps;
        final double socStart, socAnkunft, zielKm, reserve;
        final String zielName;

        /**
         * @param linie   Punkte [lat, lon] der Planlinie in Fahrtrichtung
         * @param reserve „Ankommen mit mind.“ in % (Standard 10)
         */
        public Plan(List<double[]> linie, List<Stopp> stopps, double socStart, double socAnkunft,
                    double reserve, String zielName) {
            int n = linie.size();
            lat = new double[n]; lon = new double[n]; kmKum = new double[n];
            for (int i = 0; i < n; i++) {
                lat[i] = linie.get(i)[0]; lon[i] = linie.get(i)[1];
                if (i > 0) kmKum[i] = kmKum[i - 1] + distKm(lat[i - 1], lon[i - 1], lat[i], lon[i]);
            }
            List<Stopp> s = new ArrayList<>(stopps == null ? Collections.<Stopp>emptyList() : stopps);
            s.sort((a, b) -> Double.compare(a.km, b.km));
            this.stopps = s;
            this.socStart = socStart;
            this.socAnkunft = socAnkunft;
            this.zielKm = n > 0 ? kmKum[n - 1] : 0;
            this.reserve = reserve > 0 ? reserve : 10.0;
            this.zielName = zielName == null ? "" : zielName;
        }

        /** Soll-Ladestand bei km s (vor dem Laden an einem Stopp genau bei s: socAn). */
        public double soll(double s) {
            double km0 = 0, soc0 = socStart;
            for (Stopp st : stopps) {
                if (s <= st.km) return lin(km0, soc0, st.km, st.socAn, s);
                km0 = st.km;
                soc0 = st.socAb;
            }
            return lin(km0, soc0, zielKm, socAnkunft, Math.min(s, zielKm));
        }

        /** Geplanter Verbrauch in Prozentpunkten zwischen a und b (Laden an Stopps ausgenommen). */
        public double planVerbrauch(double a, double b) {
            if (b <= a) return 0;
            double summe = 0, km0 = 0, soc0 = socStart;
            List<double[]> stuecke = new ArrayList<>();
            for (Stopp st : stopps) {
                stuecke.add(new double[] {km0, soc0, st.km, st.socAn});
                km0 = st.km;
                soc0 = st.socAb;
            }
            stuecke.add(new double[] {km0, soc0, zielKm, socAnkunft});
            for (double[] p : stuecke) {
                double von = Math.max(a, p[0]), bis = Math.min(b, p[2]);
                if (bis <= von || p[2] <= p[0]) continue;
                double rate = (p[1] - p[3]) / (p[2] - p[0]);   // %/km
                summe += rate * (bis - von);
            }
            return summe;
        }

        /** Nächster Stopp hinter s, oder null (dann ist das Ziel gemeint). */
        public Stopp naechsterStopp(double s) {
            for (Stopp st : stopps) if (st.km > s + 0.05) return st;
            return null;
        }

        /** Letzter Stopp vor s (Kilometer), 0 wenn keiner. */
        double letzterStoppKm(double s) {
            double k = 0;
            for (Stopp st : stopps) if (st.km <= s) k = st.km;
            return k;
        }

        double letzterStoppSocAb(double s) {
            double v = socStart;
            for (Stopp st : stopps) if (st.km <= s) v = st.socAb;
            return v;
        }

        /** Projektion auf die Linie: {km, Abstand km}. */
        double[] projiziere(double plat, double plon) {
            double bestKm = 0, bestD = Double.MAX_VALUE;
            for (int i = 1; i < lat.length; i++) {
                double[] r = projSegment(lat[i - 1], lon[i - 1], lat[i], lon[i], plat, plon);
                if (r[1] < bestD) {
                    bestD = r[1];
                    bestKm = kmKum[i - 1] + r[0] * (kmKum[i] - kmKum[i - 1]);
                }
            }
            if (lat.length == 1) {
                bestD = distKm(lat[0], lon[0], plat, plon);
                bestKm = 0;
            }
            return new double[] {bestKm, bestD};
        }
    }

    // ================================================================ Zustand

    public static final class Lage {
        public boolean aktiv;
        public Stufe stufe;
        public double sollProzent, istProzent, delta, prognoseProzent, kmGefahren, faktor = 1.0;
        public String naechsterName;
        public double naechsterKm;     // Restkilometer bis dorthin
        public boolean naechsterIstZiel;
        public double reserveWirksam;
        /** Soll/Ist-Reihe für die Kurve im Web-Feld: {km, soll, ist}, höchstens {@link #VERLAUF_MAX}. */
        public java.util.List<double[]> verlauf = java.util.Collections.emptyList();
    }

    static final int VERLAUF_MAX = 50;
    private final java.util.ArrayList<double[]> verlauf = new java.util.ArrayList<>();

    private final Plan plan;
    private double s = -1;
    private int sprungZaehler;
    private double sprungZiel;
    private Stufe stufe;
    private Stufe kandidat;
    private long kandidatSeit;

    public RangeWatch(Plan plan) {
        this.plan = plan;
    }

    public Plan plan() { return plan; }

    /**
     * Ein Messpunkt.
     *
     * @param aussenC        Außentemperatur oder NaN
     * @param stoppVoll      Live-Belegung meldet den nächsten Stopp voll/außer Betrieb
     */
    public Lage messung(long nowMs, double lat, double lon, double soc, double aussenC, boolean stoppVoll) {
        return messung(nowMs, lat, lon, soc, aussenC, stoppVoll, null);
    }

    /**
     * Wie oben; {@code vollerStopp} = Name des Stopps, den die Live-Belegung voll meldet. Wirkt nur,
     * wenn genau dieser Stopp der nächste ist (nach einem Stoppwechsel nicht mehr).
     */
    public Lage messung(long nowMs, double lat, double lon, double soc, double aussenC, String vollerStopp) {
        return messung(nowMs, lat, lon, soc, aussenC, false, vollerStopp);
    }

    private Lage messung(long nowMs, double lat, double lon, double soc, double aussenC, boolean stoppVoll,
                         String vollerStopp) {
        Lage l = new Lage();
        double[] p = plan.projiziere(lat, lon);
        if (p[1] > MAX_ABSTAND_KM || Double.isNaN(soc)) {
            l.aktiv = false;
            return l;
        }
        fortschritt(p[0]);
        l.aktiv = true;
        l.kmGefahren = s;
        l.istProzent = soc;
        l.sollProzent = plan.soll(s);
        l.delta = soc - l.sollProzent;

        // Faktor seit dem letzten Stopp (ab 10 km)
        double kmSeit = s - plan.letzterStoppKm(s);
        double f = 1.0;
        if (kmSeit >= FAKTOR_AB_KM) {
            double planV = plan.planVerbrauch(plan.letzterStoppKm(s), s);
            double istV = plan.letzterStoppSocAb(s) - soc;
            if (planV > 0.5 && istV > 0) {
                double roh = istV / planV;
                double n = kmSeit / 10.0;
                f = 1.0 + (roh - 1.0) * (n / (n + 4.0));
                f = Math.max(0.7, Math.min(1.4, f));
            }
        }
        l.faktor = f;

        Stopp next = plan.naechsterStopp(s);
        double zielKm = next != null ? next.km : plan.zielKm;
        l.naechsterIstZiel = next == null;
        l.naechsterName = next != null ? next.name : plan.zielName;
        l.naechsterKm = Math.max(0, zielKm - s);
        l.prognoseProzent = soc - plan.planVerbrauch(s, zielKm) * f;

        double reserve = plan.reserve + (!Double.isNaN(aussenC) && aussenC < 0 ? KAELTE_ZUSCHLAG : 0);
        l.reserveWirksam = reserve;
        merke(s, l.sollProzent, soc);
        l.verlauf = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(verlauf));
        boolean voll = next != null && (stoppVoll || next.name.equals(vollerStopp));
        Stufe roh = bewerte(l.prognoseProzent, l.delta, reserve, voll, stufe);
        l.stufe = entflattern(roh, nowMs);
        return l;
    }

    /** Punkt für die Verlaufsreihe: etwa alle Ziel-km/50, bei Überlauf jeden zweiten verwerfen. */
    private void merke(double km, double soll, double ist) {
        double abstand = Math.max(1.0, plan.zielKm / VERLAUF_MAX);
        if (!verlauf.isEmpty() && km - verlauf.get(verlauf.size() - 1)[0] < abstand) return;
        verlauf.add(new double[] {km, soll, ist});
        if (verlauf.size() > VERLAUF_MAX) {
            for (int i = verlauf.size() - 2; i > 0; i -= 2) verlauf.remove(i);
        }
    }

    static Stufe bewerte(double prognose, double delta, double reserve, boolean stoppVoll, Stufe bisher) {
        if (prognose < reserve || stoppVoll) return Stufe.REICHT_NICHT;
        double passtAb = reserve + KNAPP_ABSTAND + (bisher != null && bisher != Stufe.PASST ? RUECKKEHR_ABSTAND : 0);
        double deltaAb = DELTA_KNAPP + (bisher != null && bisher != Stufe.PASST ? RUECKKEHR_ABSTAND : 0);
        if (prognose >= passtAb && delta >= deltaAb) return Stufe.PASST;
        return Stufe.KNAPP;
    }

    private Stufe entflattern(Stufe roh, long nowMs) {
        if (stufe == null) {
            stufe = roh;   // erste Bewertung sofort
            kandidat = null;
            return stufe;
        }
        if (roh == stufe) {
            kandidat = null;
            return stufe;
        }
        if (roh != kandidat) {
            kandidat = roh;
            kandidatSeit = nowMs;
        } else if (nowMs - kandidatSeit >= FLATTER_MS) {
            stufe = roh;
            kandidat = null;
        }
        return stufe;
    }

    private void fortschritt(double proj) {
        if (s < 0) {
            s = proj;
            return;
        }
        if (proj <= s) return;                // nur vorwärts
        if (proj - s <= MAX_SPRUNG_KM) {
            s = proj;
            sprungZaehler = 0;
            return;
        }
        // großer Sprung: erst nach mehreren übereinstimmenden Messungen übernehmen
        if (sprungZaehler > 0 && Math.abs(proj - sprungZiel) <= MAX_SPRUNG_KM) {
            sprungZaehler++;
        } else {
            sprungZaehler = 1;
        }
        sprungZiel = proj;
        if (sprungZaehler >= SPRUNG_BESTAETIGUNG) {
            s = proj;
            sprungZaehler = 0;
        }
    }

    // ================================================================ Geometrie

    static double lin(double x0, double y0, double x1, double y1, double x) {
        if (x1 <= x0) return y1;
        double t = Math.max(0, Math.min(1, (x - x0) / (x1 - x0)));
        return y0 + t * (y1 - y0);
    }

    static double distKm(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    /** {t in [0,1] entlang des Segments, Abstand km} – lokal eben (für Segmente ≪ 100 km). */
    static double[] projSegment(double aLat, double aLon, double bLat, double bLon, double pLat, double pLon) {
        double k = Math.cos(Math.toRadians((aLat + bLat) / 2));
        double ax = aLon * k, ay = aLat, bx = bLon * k, by = bLat, px = pLon * k, py = pLat;
        double dx = bx - ax, dy = by - ay;
        double len2 = dx * dx + dy * dy;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len2));
        double qLat = aLat + t * (bLat - aLat), qLon = aLon + t * (bLon - aLon);
        return new double[] {t, distKm(qLat, qLon, pLat, pLon)};
    }
}
