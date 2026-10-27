package at.greenchili.surfmate.ladeplan;

import at.greenchili.surfmate.charging.Zeittarif;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.LongToDoubleFunction;
import java.util.function.LongPredicate;

/**
 * Ladeplan-Planer (LADEPLAN_KONZEPT §2/§4, 0.1.20): reine Logik, keine Android-Klassen, keine Uhr – alles kommt herein.
 * <pre>
 * Bedarf_kWh  = (Ziel% − SoC%) / 100 × Kapazität_kWh / Wirkungsgrad   (Energie aus dem Netz)
 * Leistung_kW = Strom_A × Phasen × 230 V / 1000, höchstens die AC-Grenze des Autos
 * Slots       = Viertelstunden (an der Uhr ausgerichtet) von jetzt bis Abfahrt, je mit Preis
 * Wahl        = billigste Slots, bis der Bedarf gedeckt ist (gleich teuer → früher zuerst; fester Preis → später zuerst,
 *               Entscheidung Koordination 27.10.: „so spät wie möglich“, schont den Akku)
 * </pre>
 * Vorrang: Sofort &gt; Mindestladung &gt; Günstig bis Abfahrt &gt; Zeitfenster. Lücken von einer Viertelstunde werden
 * geschlossen, höchstens {@link #MAX_SCHALTUNGEN} Schaltvorgänge (Start + Stopp) je Plan.
 */
public final class Planer {

    public static final long SLOT_MS = 15 * 60_000L;
    public static final int MAX_SCHALTUNGEN = 12;
    /** Planungshorizont für Zeitfenster und Mindestladung ohne Abfahrt. */
    static final long HORIZONT_MS = 24 * 3_600_000L;
    /** Längster Plan (7 Tage), schützt vor Unsinn aus der Datei. */
    static final long MAX_HORIZONT_MS = 7 * 24 * 3_600_000L;

    private Planer() {}

    /** Eingabe – alle Werte vom Aufrufer (Dienst bzw. Test). */
    public static final class Eingabe {
        public long jetzt;
        public ZoneId zone = ZoneId.systemDefault();
        public double socProzent = Double.NaN;
        public double kapazitaetKwh;
        public double wirkungsgrad = 0.9;
        public double leistungKw;
        /** "aus" | "guenstig" | "zeitfenster" */
        public String modus = "guenstig";
        /** „Jetzt sofort“ (bis {@link #sofortZiel}), Vorrang vor allem. */
        public boolean sofort;
        public int sofortZiel = 100;
        /** 0 = aus. */
        public int mindestladung = 30;
        /** Ziel für Zeitfenster (Günstig: Ziel der Abfahrt). */
        public int ziel = 80;
        /** Nächste Abfahrt (ms) für „Günstig bis Abfahrt“, 0 = keine. */
        public long abfahrt;
        public int abfahrtZiel = 80;
        /** Preis €/kWh zu einem Zeitpunkt. */
        public LongToDoubleFunction preis = t -> 0;
        /** Fester Preis (kein Zeittarif): Gleichstand → so spät wie möglich. */
        public boolean festerPreis = true;
        /** Zeitfenster: darf zu diesem Zeitpunkt geladen werden? */
        public LongPredicate fenster = t -> false;
        public int maxSchaltungen = MAX_SCHALTUNGEN;
    }

    /** Ergebnis: Slots, Ladezeiten und Vorschauzahlen. */
    public static final class Plan {
        public String grund;
        public String meldung;
        public final List<long[]> bloecke = new ArrayList<>();
        public long[] slotStart = new long[0];
        public double[] slotPreis = new double[0];
        public boolean[] laden = new boolean[0];
        public double bedarfKwh;
        public double kwh;
        public double kosten;
        public double kostenSofort;
        public long fertig;
        public boolean ladeJetzt;

        public int schaltungen() {
            return bloecke.size() * 2;
        }

        public JSONObject json() throws JSONException {
            JSONObject o = new JSONObject().put("grund", grund).put("meldung", meldung == null ? JSONObject.NULL : meldung)
                    .put("ladeJetzt", ladeJetzt).put("bedarfKwh", r2(bedarfKwh)).put("kwh", r2(kwh))
                    .put("kosten", r2(kosten)).put("kostenSofort", r2(kostenSofort))
                    .put("ersparnis", r2(Math.max(0, kostenSofort - kosten)))
                    .put("start", bloecke.isEmpty() ? JSONObject.NULL : (Object) bloecke.get(0)[0])
                    .put("fertig", fertig > 0 ? (Object) fertig : JSONObject.NULL)
                    .put("schaltungen", schaltungen());
            JSONArray b = new JSONArray();
            for (long[] x : bloecke) b.put(new JSONObject().put("von", x[0]).put("bis", x[1]));
            o.put("bloecke", b);
            // Balken je Stunde: Durchschnittspreis + ob in der Stunde geladen wird (für Seite, Cockpit, Handy)
            JSONArray std = new JSONArray();
            long stunde = -1;
            double summe = 0;
            int n = 0;
            boolean l = false;
            for (int i = 0; i < slotStart.length; i++) {
                long h = slotStart[i] / 3_600_000L;
                if (h != stunde && n > 0) {
                    std.put(new JSONObject().put("ab", stunde * 3_600_000L).put("preis", r4(summe / n)).put("laden", l));
                    summe = 0;
                    n = 0;
                    l = false;
                }
                stunde = h;
                summe += slotPreis[i];
                n++;
                l |= laden[i];
            }
            if (n > 0) std.put(new JSONObject().put("ab", stunde * 3_600_000L).put("preis", r4(summe / n)).put("laden", l));
            o.put("stunden", std);
            return o;
        }
    }

    static double r2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    static double r4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    /** Bedarf aus dem Netz in kWh (≥ 0). */
    public static double bedarf(double soc, int ziel, double kapazitaetKwh, double wirkungsgrad) {
        if (Double.isNaN(soc) || kapazitaetKwh <= 0) return 0;
        double w = wirkungsgrad > 0.3 && wirkungsgrad <= 1 ? wirkungsgrad : 0.9;
        return Math.max(0, (ziel - soc) / 100.0 * kapazitaetKwh / w);
    }

    /** Ladeleistung in kW aus Strom, Phasen und der AC-Grenze des Autos. */
    public static double leistungKw(int stromA, int phasen, double autoAcKw) {
        double p = Math.max(0, stromA) * (phasen == 3 ? 3 : 1) * 230.0 / 1000.0;
        return autoAcKw > 0 ? Math.min(p, autoAcKw) : p;
    }

    /** Nächste Abfahrt nach {@code jetzt}: {@code {ms, ziel}} oder null (Werktag/Wochenende, Feiertag = Wochenende). */
    public static long[] naechsteAbfahrt(JSONObject abfahrt, long jetzt, ZoneId zone, String land) {
        if (abfahrt == null) return null;
        LocalDate tag = Instant.ofEpochMilli(jetzt).atZone(zone).toLocalDate();
        for (int i = 0; i < 8; i++) {
            LocalDate d = tag.plusDays(i);
            JSONObject e = abfahrt.optJSONObject(Zeittarif.tagesart(d, land));
            if (e == null || !e.optBoolean("an", false)) continue;
            int m = minute(e.optString("zeit", ""));
            if (m < 0) continue;
            long t = d.atTime(m / 60, m % 60).atZone(zone).toInstant().toEpochMilli();
            if (t > jetzt) return new long[]{t, Math.max(20, Math.min(100, e.optInt("ziel", 80)))};
        }
        return null;
    }

    static int minute(String s) {
        if (s == null || !s.matches("\\d{2}:\\d{2}")) return -1;
        int h = Integer.parseInt(s.substring(0, 2)), m = Integer.parseInt(s.substring(3));
        return h > 23 || m > 59 ? -1 : h * 60 + m;
    }

    /** Zeitfenster-Liste {@code [{tage, von, bis}]} als Prüfung je Zeitpunkt (Band-Regeln wie im Zeittarif). */
    public static LongPredicate fenster(JSONArray liste, ZoneId zone, String land) {
        JSONObject zt = new JSONObject();
        try {
            JSONArray b = new JSONArray();
            for (int i = 0; liste != null && i < liste.length() && i < Zeittarif.MAX_BAENDER; i++) {
                JSONObject f = liste.optJSONObject(i);
                if (f == null) continue;
                b.put(new JSONObject().put("tage", f.optString("tage", "alle")).put("von", f.optString("von"))
                        .put("bis", f.optString("bis")).put("preis", 0));
            }
            zt.put("art", "zeittarif").put("grundpreis", 1).put("land", land == null ? "" : land).put("baender", b);
        } catch (JSONException e) {
            return t -> false;
        }
        Zeittarif z = Zeittarif.oderNull(zt);
        if (z == null) return t -> false;
        return t -> z.preis(t, zone) == 0;
    }

    // ------------------------------------------------------------------ Planen

    public static Plan plane(Eingabe e) {
        Plan p = new Plan();
        if ("aus".equals(e.modus) && !e.sofort) {
            p.grund = "aus";
            return p;
        }
        if (Double.isNaN(e.socProzent) || e.kapazitaetKwh <= 0 || e.leistungKw <= 0) {
            p.grund = "daten_fehlen";
            return p;
        }
        long ende;
        if (e.sofort) ende = e.jetzt + HORIZONT_MS;
        else if ("guenstig".equals(e.modus) && e.abfahrt > e.jetzt) ende = Math.min(e.abfahrt, e.jetzt + MAX_HORIZONT_MS);
        else ende = e.jetzt + HORIZONT_MS;
        slots(p, e, ende);
        int n = p.slotStart.length;
        double[] kwhSlot = new double[n];
        for (int i = 0; i < n; i++) kwhSlot[i] = e.leistungKw * (slotEnde(p, i, ende) - p.slotStart[i]) / 3_600_000.0;
        boolean[] wahl = new boolean[n];

        if (e.sofort) {
            p.bedarfKwh = bedarf(e.socProzent, e.sofortZiel, e.kapazitaetKwh, e.wirkungsgrad);
            fuelleVonVorn(wahl, kwhSlot, p.bedarfKwh, null);
            p.grund = p.bedarfKwh <= 0 ? "ziel_erreicht" : "sofort";
            return fertig(p, e, wahl, kwhSlot, ende);
        }

        // Mindestladung: unter X % sofort bis X %, egal was der Plan sagt (Schutz für spontane Fahrten)
        double mindest = e.mindestladung > 0 ? bedarf(e.socProzent, e.mindestladung, e.kapazitaetKwh, e.wirkungsgrad) : 0;
        int belegt = mindest > 0 ? fuelleVonVorn(wahl, kwhSlot, mindest, null) : 0;
        double mindestGedeckt = 0;
        for (int i = 0; i < n; i++) if (wahl[i]) mindestGedeckt += kwhSlot[i];

        if ("guenstig".equals(e.modus)) {
            if (e.abfahrt <= e.jetzt) {
                p.bedarfKwh = mindest;
                p.grund = mindest > 0 ? "mindestladung" : "keine_abfahrt";
                return fertig(p, e, wahl, kwhSlot, ende);
            }
            p.bedarfKwh = Math.max(mindest, bedarf(e.socProzent, e.abfahrtZiel, e.kapazitaetKwh, e.wirkungsgrad));
            double rest = p.bedarfKwh - mindestGedeckt;
            if (rest > 1e-9) {
                double moeglich = 0;
                for (int i = 0; i < n; i++) if (!wahl[i]) moeglich += kwhSlot[i];
                if (moeglich + 1e-9 < rest) {
                    // Zeit reicht nicht: sofort laden, ehrlich melden
                    Arrays.fill(wahl, true);
                    p.grund = "guenstig";
                    p.meldung = "ziel_nicht_erreichbar";
                    return fertig(p, e, wahl, kwhSlot, ende);
                }
                waehleGuenstig(wahl, kwhSlot, p.slotPreis, rest, e.festerPreis);
                glaette(wahl, kwhSlot, p.slotPreis, p.bedarfKwh, e.festerPreis, Math.max(2, e.maxSchaltungen) / 2, belegt);
            }
            p.grund = p.bedarfKwh <= 0 ? "ziel_erreicht" : mindest > 0 && rest <= 1e-9 ? "mindestladung" : "guenstig";
            return fertig(p, e, wahl, kwhSlot, ende);
        }

        if ("zeitfenster".equals(e.modus)) {
            p.bedarfKwh = Math.max(mindest, bedarf(e.socProzent, e.ziel, e.kapazitaetKwh, e.wirkungsgrad));
            double rest = p.bedarfKwh - mindestGedeckt;
            if (rest > 1e-9) {
                boolean[] erlaubt = new boolean[n];
                for (int i = 0; i < n; i++) erlaubt[i] = e.fenster.test(p.slotStart[i]);
                fuelleVonVorn(wahl, kwhSlot, rest, erlaubt);
            }
            p.grund = p.bedarfKwh <= 0 ? "ziel_erreicht" : mindest > 0 && rest <= 1e-9 ? "mindestladung" : "zeitfenster";
            return fertig(p, e, wahl, kwhSlot, ende);
        }
        p.grund = "aus";
        return p;
    }

    /** Viertelstunden ab jetzt; die erste ist bis zur nächsten vollen Viertelstunde verkürzt. */
    private static void slots(Plan p, Eingabe e, long ende) {
        List<Long> st = new ArrayList<>();
        long t = e.jetzt;
        while (t < ende) {
            st.add(t);
            long naechste = naechsteGrenze(t, e.zone);
            t = Math.min(naechste, ende);
        }
        p.slotStart = new long[st.size()];
        p.slotPreis = new double[st.size()];
        for (int i = 0; i < st.size(); i++) {
            p.slotStart[i] = st.get(i);
            double pr = e.preis.applyAsDouble(st.get(i));
            p.slotPreis[i] = Double.isNaN(pr) || pr < 0 ? 0 : pr;
        }
    }

    /** Nächste volle Viertelstunde nach der lokalen Uhr (Zeitzonen mit halben Stunden bleiben richtig). */
    static long naechsteGrenze(long t, ZoneId zone) {
        ZonedDateTime z = Instant.ofEpochMilli(t).atZone(zone);
        ZonedDateTime n = z.withSecond(0).withNano(0).withMinute(z.getMinute() / 15 * 15).plusMinutes(15);
        long ms = n.toInstant().toEpochMilli();
        return ms > t ? ms : t + SLOT_MS;
    }

    private static long slotEnde(Plan p, int i, long ende) {
        return i + 1 < p.slotStart.length ? p.slotStart[i + 1] : ende;
    }

    /** Ab jetzt der Reihe nach (nur erlaubte Slots) bis der Bedarf gedeckt ist; liefert die Zahl gewählter Slots. */
    private static int fuelleVonVorn(boolean[] wahl, double[] kwh, double bedarf, boolean[] erlaubt) {
        double s = 0;
        int k = 0;
        for (int i = 0; i < wahl.length && s + 1e-9 < bedarf; i++) {
            if (wahl[i] || (erlaubt != null && !erlaubt[i])) continue;
            wahl[i] = true;
            s += kwh[i];
            k++;
        }
        return k;
    }

    /** Billigste freie Slots bis der Rest gedeckt ist. */
    private static void waehleGuenstig(boolean[] wahl, double[] kwh, double[] preis, double rest, boolean spaet) {
        Integer[] idx = new Integer[wahl.length];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        Arrays.sort(idx, (a, b) -> {
            int c = Double.compare(preis[a], preis[b]);
            if (c != 0) return c;
            return spaet ? Integer.compare(b, a) : Integer.compare(a, b);
        });
        double s = 0;
        for (Integer i : idx) {
            if (s + 1e-9 >= rest) break;
            if (wahl[i] || kwh[i] <= 0) continue;
            wahl[i] = true;
            s += kwh[i];
        }
    }

    /**
     * Schaltungen begrenzen: Lücken von einer Viertelstunde schließen, dann so lange die billigste Lücke füllen, bis
     * höchstens {@code maxBloecke} Ladeblöcke übrig sind; danach überzählige teure Rand-Slots wieder abgeben.
     * Die ersten {@code fest} Slots (Mindestladung) bleiben unangetastet.
     */
    static void glaette(boolean[] wahl, double[] kwh, double[] preis, double bedarf, boolean spaet, int maxBloecke, int fest) {
        List<int[]> l = luecken(wahl);
        for (int[] g : l) if (g[1] - g[0] == 1) wahl[g[0]] = true;
        while (bloecke(wahl) > maxBloecke) {
            int[] best = null;
            double bestK = Double.MAX_VALUE;
            for (int[] g : luecken(wahl)) {
                double k = 0;
                for (int i = g[0]; i < g[1]; i++) k += preis[i] * kwh[i];
                if (k < bestK) {
                    bestK = k;
                    best = g;
                }
            }
            if (best == null) break;
            for (int i = best[0]; i < best[1]; i++) wahl[i] = true;
        }
        // überzählige Energie an den Rändern abgeben (teuerster Rand zuerst), ohne neue Lücken
        while (true) {
            double s = 0;
            for (int i = 0; i < wahl.length; i++) if (wahl[i]) s += kwh[i];
            int weg = -1;
            for (int i = fest; i < wahl.length; i++) {
                if (!wahl[i]) continue;
                boolean rand = i == 0 || !wahl[i - 1] || i == wahl.length - 1 || !wahl[i + 1];
                boolean mitte = i > 0 && wahl[i - 1] && i < wahl.length - 1 && wahl[i + 1];
                if (!rand || mitte || s - kwh[i] + 1e-9 < bedarf) continue;
                if (weg < 0 || preis[i] > preis[weg] || (preis[i] == preis[weg] && (spaet ? i < weg : i > weg))) weg = i;
            }
            if (weg < 0) break;
            wahl[weg] = false;
        }
    }

    /** Lücken zwischen Ladeblöcken als {@code [von, bis)}. */
    static List<int[]> luecken(boolean[] w) {
        List<int[]> l = new ArrayList<>();
        int erstes = -1, letztes = -1;
        for (int i = 0; i < w.length; i++) if (w[i]) { if (erstes < 0) erstes = i; letztes = i; }
        if (erstes < 0) return l;
        int i = erstes;
        while (i <= letztes) {
            if (!w[i]) {
                int j = i;
                while (!w[j]) j++;
                l.add(new int[]{i, j});
                i = j;
            } else {
                i++;
            }
        }
        return l;
    }

    static int bloecke(boolean[] w) {
        int b = 0;
        for (int i = 0; i < w.length; i++) if (w[i] && (i == 0 || !w[i - 1])) b++;
        return b;
    }

    /** Kosten, Fertig-Zeit, Blöcke und Vergleich „sofort“ berechnen. */
    private static Plan fertig(Plan p, Eingabe e, boolean[] wahl, double[] kwh, long ende) {
        p.laden = wahl;
        double rest = p.bedarfKwh;
        for (int i = 0; i < wahl.length; i++) {
            if (!wahl[i] || rest <= 1e-9) continue;
            double k = Math.min(kwh[i], rest);
            p.kwh += k;
            p.kosten += k * p.slotPreis[i];
            rest -= k;
            if (rest <= 1e-9) {
                long s = p.slotStart[i], d = slotEnde(p, i, ende) - s;
                p.fertig = s + (kwh[i] > 0 ? Math.round(d * k / kwh[i]) : d);
            }
        }
        rest = p.bedarfKwh;
        for (int i = 0; i < wahl.length && rest > 1e-9; i++) {
            double k = Math.min(kwh[i], rest);
            p.kostenSofort += k * p.slotPreis[i];
            rest -= k;
        }
        for (int i = 0; i < wahl.length; i++) {
            if (wahl[i] && (i == 0 || !wahl[i - 1])) {
                int j = i;
                while (j + 1 < wahl.length && wahl[j + 1]) j++;
                p.bloecke.add(new long[]{p.slotStart[i], slotEnde(p, j, ende)});
            }
        }
        p.ladeJetzt = wahl.length > 0 && wahl[0] && p.bedarfKwh > 0;
        return p;
    }
}
