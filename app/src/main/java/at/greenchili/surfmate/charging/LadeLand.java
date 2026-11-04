package at.greenchili.surfmate.charging;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Land einer Ladung bzw. Säule für den eigenen Preis (0.1.38.6, Befund planer-preise: ein „Lidl“-Preis aus AT landete
 * an DE-Säulen und umgekehrt). Rein.
 *
 * <p>Ein Land gilt nur, wenn es sicher ist. Reihenfolge:
 * <ol>
 *   <li>Land der zugeordneten Säule: der Ländercode vor dem ersten {@code *} der EVSE-ID ({@code AT*LDL*E0000…} → AT;
 *       Felder {@code stationId}, {@code evse}, {@code id}). Das gilt auch in Grenznähe, wo Koordinaten nichts taugen;</li>
 *   <li>ein ausdrückliches {@code land} (zwei Buchstaben);</li>
 *   <li>erst danach die Koordinate, und nur in einem Kernbereich von AT, DE oder CH, der <b>weit</b> von jeder Grenze
 *       entfernt ist.</li>
 * </ol>
 * Alles andere ist {@code null}: dann gibt es keinen eigenen Preis. Lieber kein „~“-Preis als ein falscher. Weitere
 * Länder oder Kernbereiche kommen nach, ohne dass bestehende Zuordnungen kippen.
 */
public final class LadeLand {

    private LadeLand() {}

    /** Kernbereiche {Land, lat von, lat bis, lon von, lon bis}, alle deutlich im Inland (> ~10 km von der Grenze). */
    private static final Object[][] KERN = {
            // Österreich
            {"AT", 48.0, 48.6, 15.0, 16.4},     // Wien, Niederösterreich
            {"AT", 47.8, 48.3, 13.4, 14.8},     // Oberösterreich, Salzkammergut
            {"AT", 47.0, 47.6, 14.3, 15.8},     // Steiermark
            {"AT", 47.15, 47.35, 10.4, 13.2},   // Tirol, Pinzgau, Pongau
            {"AT", 46.65, 46.9, 13.4, 14.6},    // Kärnten
            {"AT", 47.1, 47.4, 9.8, 10.1},      // Vorarlberg
            // Deutschland
            {"DE", 48.1, 49.6, 10.4, 12.6},     // Bayern
            {"DE", 48.0, 49.3, 8.6, 9.9},       // Baden-Württemberg
            {"DE", 50.0, 52.0, 7.5, 11.9},      // Hessen, NRW, Niedersachsen Süd, Thüringen West
            {"DE", 52.0, 54.3, 8.0, 13.0},      // Norddeutschland
            {"DE", 51.1, 52.6, 11.9, 14.2},     // Sachsen-Anhalt, Sachsen, Brandenburg
            {"DE", 49.4, 50.0, 7.5, 10.4},      // Pfalz, Rhein-Main
            // Schweiz
            {"CH", 46.7, 47.4, 7.2, 8.7},       // Mittelland
    };

    /** AT, DE oder CH, wenn die Koordinate sicher im Kernbereich liegt, sonst null. */
    public static String ausPosition(double lat, double lon) {
        if (Double.isNaN(lat) || Double.isNaN(lon) || Double.isInfinite(lat) || Double.isInfinite(lon)) return null;
        if (lat == 0 && lon == 0) return null;
        for (Object[] k : KERN) {
            if (lat >= (Double) k[1] && lat <= (Double) k[2] && lon >= (Double) k[3] && lon <= (Double) k[4]) return (String) k[0];
        }
        return null;
    }

    /** Zwei Buchstaben, groß; alles andere null. */
    public static String norm(String land) {
        if (land == null) return null;
        String s = land.trim().toUpperCase(Locale.ROOT);
        return s.matches("^[A-Z]{2}$") ? s : null;
    }

    private static final java.util.regex.Pattern EVSE = java.util.regex.Pattern.compile("^([A-Za-z]{2})\\*[A-Za-z0-9]{2,5}\\*.+");

    /** Ländercode aus einer EVSE-ID ({@code AT*LDL*E00000092} → AT), sonst null. */
    public static String ausEvse(String id) {
        if (id == null) return null;
        java.util.regex.Matcher m = EVSE.matcher(id.trim());
        return m.matches() ? m.group(1).toUpperCase(Locale.ROOT) : null;
    }

    /**
     * Land eines Datensatzes (Ladung wie {@code /api/charging} oder Säule der Planer-Antwort): zuerst die EVSE-ID der
     * zugeordneten Säule, dann ausdrückliches {@code land}, dann {@code lat}/{@code lng|lon} im Kernbereich, sonst null.
     */
    public static String von(JSONObject o) {
        if (o == null) return null;
        for (String k : new String[]{"stationId", "evse", "id"}) {
            String e = o.isNull(k) ? null : ausEvse(o.optString(k, ""));
            if (e != null) return e;
        }
        String l = o.isNull("land") ? null : norm(o.optString("land", ""));
        if (l != null) return l;
        if (o.isNull("lat")) return null;
        double lat = o.optDouble("lat", Double.NaN);
        double lon = !o.isNull("lng") ? o.optDouble("lng", Double.NaN) : (!o.isNull("lon") ? o.optDouble("lon", Double.NaN) : Double.NaN);
        return ausPosition(lat, lon);
    }
}
