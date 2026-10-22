package at.greenchili.surfmate.blitzer;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Blitzer-Warnung beim Fahren (21.10.2026). Rein, offline.
 *
 * <ul>
 *   <li><b>Nur in Österreich</b> (Auto im AT-Polygon UND Blitzer aus dem AT-Datensatz). In DE, CH,
 *       FR usw. aus – dort ist die Warnung für Fahrende nicht erlaubt.</li>
 *   <li>Ausnahme: Alfreds versteckter Testschalter ({@code speedcam.testBis}, läuft nach 24 h ab):
 *       dann alle Länder.</li>
 *   <li>Nur in Fahrtrichtung: Peilung Auto → Blitzer höchstens {@link #KURS_TOLERANZ}° vom Kurs.</li>
 *   <li>Warnabstand je Tempo (≈ 20 s, {@link #MIN_M}–{@link #MAX_M}), ab {@link #AB_KMH} km/h.</li>
 *   <li>Ruhegrenze je Blitzer {@link #RUHE_MS}.</li>
 * </ul>
 * Die Grenze ist vereinfacht (~60 Punkte, an der Grenze auf wenige Kilometer unscharf). Weil zusätzlich
 * nur Blitzer aus dem AT-Datensatz zählen, warnt Panoptes nie vor einem Blitzer in Deutschland.
 */
public final class BlitzerWarner {

    static final double KURS_TOLERANZ = 30.0;
    static final double AB_KMH = 15.0;
    static final double MIN_M = 300, MAX_M = 1500;
    static final double VORLAUF_S = 20.0;
    static final long RUHE_MS = 10 * 60_000L;

    public static final class Blitzer {
        public final String id, land;
        public final double lat, lon;
        /** Tempolimit km/h, 0 = unbekannt. */
        public final int maxspeed;
        public Blitzer(String id, String land, double lat, double lon, int maxspeed) {
            this.id = id; this.land = land; this.lat = lat; this.lon = lon; this.maxspeed = maxspeed;
        }
    }

    public static final class Warnung {
        public final Blitzer blitzer;
        /** Abstand auf 50 m gerundet. */
        public final int meter;
        Warnung(Blitzer b, int meter) { blitzer = b; this.meter = meter; }
    }

    private final Map<String, Long> gewarnt = new HashMap<>();

    /** Gilt die Warnung hier? AT immer, sonst nur mit aktivem Testschalter. */
    public static boolean erlaubt(double lat, double lon, boolean testAktiv) {
        return testAktiv || inOesterreich(lat, lon);
    }

    /** Testschalter: an, solange jetzt vor {@code testBis} liegt (höchstens 24 h ab Setzen). */
    public static boolean testAktiv(long testBis, long now) {
        return testBis > now && testBis - now <= 24 * 3600_000L + 60_000L;
    }

    /**
     * @param kursGrad Fahrtrichtung 0–360, negativ/NaN = unbekannt (dann keine Warnung)
     * @return nächster passender Blitzer oder null
     */
    public synchronized Warnung pruefe(long now, double lat, double lon, double kursGrad, double kmh,
                                       boolean testAktiv, List<Blitzer> blitzer) {
        aufraeumen(now);
        if (Double.isNaN(lat) || Double.isNaN(lon) || blitzer == null) return null;
        if (!erlaubt(lat, lon, testAktiv)) return null;
        if (Double.isNaN(kmh) || kmh < AB_KMH) return null;
        if (Double.isNaN(kursGrad) || kursGrad < 0) return null;
        double reichweite = Math.max(MIN_M, Math.min(MAX_M, kmh / 3.6 * VORLAUF_S));
        Blitzer best = null;
        double bestD = Double.MAX_VALUE;
        for (Blitzer b : blitzer) {
            if (!testAktiv && !"at".equals(b.land)) continue;
            double d = distM(lat, lon, b.lat, b.lon);
            if (d > reichweite || d >= bestD || d < 20) continue;
            if (winkel(peilung(lat, lon, b.lat, b.lon), kursGrad) > KURS_TOLERANZ) continue;
            if (gewarnt.containsKey(b.id)) continue;
            best = b;
            bestD = d;
        }
        if (best == null) return null;
        gewarnt.put(best.id, now);
        return new Warnung(best, (int) (Math.round(bestD / 50.0) * 50));
    }

    private void aufraeumen(long now) {
        Iterator<Map.Entry<String, Long>> it = gewarnt.entrySet().iterator();
        while (it.hasNext()) if (now - it.next().getValue() >= RUHE_MS) it.remove();
    }

    // ---------------------------------------------------------------- Geometrie

    static double distM(double lat1, double lon1, double lat2, double lon2) {
        double r = 6_371_000.0;
        double dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    static double peilung(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2), dl = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0;
    }

    static double winkel(double a, double b) {
        double d = Math.abs(a - b) % 360.0;
        return d > 180 ? 360 - d : d;
    }

    /** Vereinfachte Grenze Österreichs (lon, lat), im Uhrzeigersinn ab Bodensee. */
    private static final double[][] AT = {
            {9.56, 47.50}, {9.73, 47.55}, {9.96, 47.54}, {10.09, 47.42}, {10.17, 47.27}, {10.44, 47.41},
            {10.45, 47.55}, {10.88, 47.53}, {10.98, 47.42}, {11.26, 47.40}, {11.57, 47.52}, {11.84, 47.58},
            {12.20, 47.61}, {12.50, 47.67}, {12.78, 47.67}, {12.92, 47.71}, {12.75, 47.60}, {12.95, 47.47},
            {13.08, 47.55}, {13.10, 47.68}, {13.00, 47.78}, {12.98, 47.90}, {12.85, 48.08}, {12.95, 48.20},
            {13.03, 48.275}, {13.25, 48.30}, {13.40, 48.40}, {13.42, 48.48}, {13.45, 48.53}, {13.73, 48.52}, {13.82, 48.77}, {14.05, 48.60}, {14.70, 48.58},
            {15.00, 48.98}, {15.30, 48.98}, {16.10, 48.75}, {16.54, 48.81}, {16.94, 48.62}, {16.85, 48.37},
            {17.05, 48.15}, {17.16, 48.00}, {17.07, 47.70}, {16.72, 47.66}, {16.65, 47.73},
            {16.52, 47.73}, {16.45, 47.62}, {16.45, 47.40}, {16.50, 46.95},
            {16.11, 46.86}, {16.00, 46.68}, {15.65, 46.70}, {15.00, 46.62}, {14.55, 46.43}, {13.72, 46.52},
            {12.70, 46.65}, {12.17, 46.98}, {11.50, 47.00}, {11.00, 46.77}, {10.50, 46.84}, {10.47, 46.93}, {10.40, 47.00},
            {10.30, 46.95}, {10.12, 46.84},
            {9.88, 46.92}, {9.60, 47.05}, {9.62, 47.15}, {9.53, 47.27},
    };

    public static boolean inOesterreich(double lat, double lon) {
        boolean inside = false;
        for (int i = 0, j = AT.length - 1; i < AT.length; j = i++) {
            double xi = AT[i][0], yi = AT[i][1], xj = AT[j][0], yj = AT[j][1];
            if (((yi > lat) != (yj > lat)) && (lon < (xj - xi) * (lat - yi) / (yj - yi) + xi)) inside = !inside;
        }
        return inside;
    }
}
