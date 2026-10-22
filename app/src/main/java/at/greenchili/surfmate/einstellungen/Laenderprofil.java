package at.greenchili.surfmate.einstellungen;

/**
 * Länderprofil aus GPS, offline (keine Koordinaten an fremde Server). Heute nur die Frage
 * „Schweiz (inkl. Liechtenstein)?“ für die Wächter-Regel: KI-Erkennung überall ist dort nicht
 * zulässig (RECHTSLAGE_WAECHTER.md, Koordination 21.10.2026).
 *
 * <p>Vereinfachtes Grenzpolygon (~50 Punkte): im Landesinneren sicher, an der Grenze auf einige
 * Kilometer unscharf. Ohne GPS-Fix gibt es keine Aussage ({@link #unbekannt}).
 */
public final class Laenderprofil {

    private Laenderprofil() {}

    /** lon, lat – im Uhrzeigersinn ab Genf. */
    private static final double[][] CH = {
            {5.956, 46.132}, {6.10, 46.41}, {6.37, 46.71}, {6.47, 46.91}, {6.73, 47.05},
            {6.95, 47.37}, {7.00, 47.50}, {7.20, 47.50}, {7.56, 47.58}, {7.62, 47.59},
            {8.20, 47.62}, {8.57, 47.81}, {8.68, 47.80}, {8.90, 47.66}, {9.17, 47.65},
            {9.56, 47.54}, {9.65, 47.47}, {9.67, 47.37}, {9.63, 47.20}, {9.53, 47.06},
            {9.87, 47.01}, {10.15, 46.85}, {10.47, 46.98}, {10.49, 46.87}, {10.45, 46.75},
            {10.49, 46.55}, {10.27, 46.48}, {10.05, 46.45}, {10.16, 46.24}, {10.05, 46.22},
            {9.55, 46.30}, {9.25, 46.43}, {9.13, 46.16}, {9.02, 45.82}, {8.85, 45.93},
            {8.71, 46.10}, {8.44, 46.25}, {8.10, 46.15}, {7.86, 45.92}, {7.53, 45.98},
            {7.17, 45.87}, {7.04, 45.93}, {6.86, 46.04}, {6.79, 46.39}, {6.59, 46.40},
            {6.24, 46.30}, {6.31, 46.25}, {6.07, 46.15},
    };

    public enum Land { SCHWEIZ, ANDERES, UNBEKANNT }

    public static Land land(double lat, double lon, boolean fix) {
        if (!fix || Double.isNaN(lat) || Double.isNaN(lon) || (lat == 0.0 && lon == 0.0)) return Land.UNBEKANNT;
        return inPolygon(lon, lat, CH) ? Land.SCHWEIZ : Land.ANDERES;
    }

    public static boolean unbekannt(Land l) { return l == Land.UNBEKANNT; }

    static boolean inPolygon(double x, double y, double[][] poly) {
        boolean inside = false;
        for (int i = 0, j = poly.length - 1; i < poly.length; j = i++) {
            double xi = poly[i][0], yi = poly[i][1], xj = poly[j][0], yj = poly[j][1];
            if (((yi > y) != (yj > y)) && (x < (xj - xi) * (y - yi) / (yj - yi) + xi)) inside = !inside;
        }
        return inside;
    }

    /** Aktuelles Land aus dem GpsMonitor des Daemons (Fix höchstens 10 min alt). */
    public static Land aktuell() {
        try {
            at.greenchili.surfmate.monitor.GpsMonitor g = at.greenchili.surfmate.monitor.GpsMonitor.getInstance();
            long age = System.currentTimeMillis() - g.getLastUpdate();
            boolean fix = g.getLastUpdate() > 0 && age >= 0 && age < 10 * 60_000L;
            return land(g.getLatitude(), g.getLongitude(), fix);
        } catch (Throwable t) {
            return Land.UNBEKANNT;
        }
    }
}
