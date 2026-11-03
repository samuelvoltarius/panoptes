package at.greenchili.surfmate.monitor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Verlust im Stand (03.11.2026): Wie viel Akku verliert das Auto je Tag, während es steht? Rein, ohne Datenbank.
 *
 * <p>Grundlage sind nur echte Ereignisse aus {@code acc_events}: ein „Zündung aus“ und das nächste „Zündung an“
 * bilden eine Parkzeit, Akku-Stand und kWh kommen aus den beiden Zeilen. Nichts wird aus Lücken geraten.
 * Eine Parkzeit zählt nicht, wenn
 * <ul>
 *   <li>sie kürzer als {@link #MIN_STUNDEN} ist (der Akku-Stand hat nur 1-%-Schritte),</li>
 *   <li>in ihr geladen wurde (Ladung überlappt oder der Akku-Stand stieg um mehr als {@link #ANSTIEG_LADEN}),</li>
 *   <li>ein Akku-Stand fehlt.</li>
 * </ul>
 * Wächter an/aus nur, wenn beide Enden es belegen: beim Abstellen (nach dem Scharfschalten gemerkt) und beim
 * Einschalten scharf = „an“, beide nicht scharf = „aus“, sonst „unbekannt“ (auch alte Zeilen ohne Merker).
 * Je Gruppe: Summe Verlust / Summe Stunden × 24. Keine Orte.
 */
public final class Standverlust {

    public static final double MIN_STUNDEN = 3.0;
    static final double ANSTIEG_LADEN = 0.5;

    public static final String AN = "an";
    public static final String AUS = "aus";
    public static final String UNBEKANNT = "unbekannt";

    private Standverlust() {}

    /** Eine Zeile aus acc_events. {@code soc}, {@code kwh}, {@code scharf} dürfen null sein. */
    public static final class Ereignis {
        public final long ts;
        public final boolean aus;
        public final Double soc;
        public final Double kwh;
        public final Boolean scharf;

        public Ereignis(long ts, boolean aus, Double soc, Double kwh, Boolean scharf) {
            this.ts = ts;
            this.aus = aus;
            this.soc = soc;
            this.kwh = kwh;
            this.scharf = scharf;
        }
    }

    /** Eine gezählte Parkzeit. */
    static final class Park {
        long von, bis;
        double stunden, verlustProzent;
        Double verlustKwh;
        String waechter;
    }

    /** Wächter-Gruppe aus den beiden Merkern. */
    static String waechter(Boolean beimAbstellen, Boolean beimEinschalten) {
        if (beimAbstellen == null || beimEinschalten == null) return UNBEKANNT;
        if (beimAbstellen && beimEinschalten) return AN;
        if (!beimAbstellen && !beimEinschalten) return AUS;
        return UNBEKANNT;
    }

    /**
     * Parkzeiten aus den Ereignissen bilden.
     *
     * @param ereignisse aufsteigend nach Zeit
     * @param ladungen   Ladezeiträume {start, ende} (ende ≤ 0 = noch offen)
     */
    static List<Park> parkzeiten(List<Ereignis> ereignisse, List<long[]> ladungen) {
        List<Park> out = new ArrayList<>();
        if (ereignisse == null) return out;
        Ereignis aus = null;
        for (Ereignis e : ereignisse) {
            if (e == null) continue;
            if (e.aus) { aus = e; continue; }
            if (aus == null) continue;
            Ereignis an = e;
            Ereignis start = aus;
            aus = null;
            if (an.ts <= start.ts) continue;
            double stunden = (an.ts - start.ts) / 3_600_000.0;
            if (stunden < MIN_STUNDEN) continue;
            if (!gueltig(start.soc) || !gueltig(an.soc)) continue;
            double verlust = start.soc - an.soc;
            if (-verlust > ANSTIEG_LADEN) continue;
            if (ueberlappt(start.ts, an.ts, ladungen)) continue;
            Park p = new Park();
            p.von = start.ts;
            p.bis = an.ts;
            p.stunden = stunden;
            p.verlustProzent = verlust;
            if (start.kwh != null && an.kwh != null && start.kwh > 0 && an.kwh > 0) {
                double dk = start.kwh - an.kwh;
                if (Math.abs(dk) < 200) p.verlustKwh = dk;
            }
            p.waechter = waechter(start.scharf, an.scharf);
            out.add(p);
        }
        return out;
    }

    static boolean gueltig(Double soc) {
        return soc != null && !soc.isNaN() && soc >= 0 && soc <= 100;
    }

    static boolean ueberlappt(long von, long bis, List<long[]> ladungen) {
        if (ladungen == null) return false;
        for (long[] l : ladungen) {
            if (l == null || l.length < 2) continue;
            long lEnde = l[1] > 0 ? l[1] : Long.MAX_VALUE;
            if (l[0] < bis && lEnde > von) return true;
        }
        return false;
    }

    /**
     * @return {@code {gruppen:{gesamt,an,aus,unbekannt}, parkzeiten:[…letzte 20…], minStunden}};
     *         je Gruppe {@code {parkzeiten, stunden, prozentProTag, kwhProTag}} (Werte null, wenn nichts belegt).
     */
    public static JSONObject berechne(List<Ereignis> ereignisse, List<long[]> ladungen) {
        List<Park> parks = parkzeiten(ereignisse, ladungen);
        JSONObject out = new JSONObject();
        try {
            JSONObject gruppen = new JSONObject();
            gruppen.put("gesamt", gruppe(parks, null));
            gruppen.put(AN, gruppe(parks, AN));
            gruppen.put(AUS, gruppe(parks, AUS));
            gruppen.put(UNBEKANNT, gruppe(parks, UNBEKANNT));
            out.put("gruppen", gruppen);
            JSONArray liste = new JSONArray();
            for (int i = parks.size() - 1; i >= 0 && liste.length() < 20; i--) {
                Park p = parks.get(i);
                JSONObject o = new JSONObject();
                o.put("von", p.von);
                o.put("bis", p.bis);
                o.put("stunden", runde(p.stunden, 1));
                o.put("verlustProzent", runde(p.verlustProzent, 1));
                o.put("verlustKwh", p.verlustKwh == null ? JSONObject.NULL : runde(p.verlustKwh, 2));
                o.put("waechter", p.waechter);
                liste.put(o);
            }
            out.put("parkzeiten", liste);
            out.put("minStunden", MIN_STUNDEN);
        } catch (Exception ignored) {
        }
        return out;
    }

    static JSONObject gruppe(List<Park> parks, String waechter) throws Exception {
        int n = 0, nKwh = 0;
        double h = 0, verlust = 0, hKwh = 0, kwh = 0;
        for (Park p : parks) {
            if (waechter != null && !waechter.equals(p.waechter)) continue;
            n++;
            h += p.stunden;
            verlust += p.verlustProzent;
            if (p.verlustKwh != null) {
                nKwh++;
                hKwh += p.stunden;
                kwh += p.verlustKwh;
            }
        }
        JSONObject g = new JSONObject();
        g.put("parkzeiten", n);
        g.put("stunden", runde(h, 1));
        g.put("prozentProTag", n > 0 && h > 0 ? runde(verlust / h * 24.0, 2) : JSONObject.NULL);
        g.put("kwhProTag", nKwh > 0 && hKwh > 0 ? runde(kwh / hKwh * 24.0, 3) : JSONObject.NULL);
        return g;
    }

    static double runde(double v, int stellen) {
        double f = Math.pow(10, stellen);
        return Math.round(v * f) / f;
    }
}
