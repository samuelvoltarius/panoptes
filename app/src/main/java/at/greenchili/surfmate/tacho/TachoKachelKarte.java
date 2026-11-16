package at.greenchili.surfmate.tacho;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 0.1.43.9 (T3): Kartenbild für das Element „Karte“ im Tacho. Reine Regeln, im Test belegt; gerendert wird in der
 * Tacho-Ansicht mit dem MapLibre-Snapshotter (gleicher Stil, gleiche Kachel-Quelle, gleicher Cache wie
 * die Panoptes-Karte, siehe {@code TachoKarteBild}).
 *
 * <ul>
 *   <li>Nur mit gewählter Kartenquelle ({@code community}/{@code osm}); ohne Quelle keine Kacheln.</li>
 *   <li>Nur solange das aktive Design eine Karte hat ({@link #kasten}).</li>
 *   <li>Neu höchstens alle {@link #TAKT_MIN_MS}, und nur wenn das Auto sich bewegt/dreht oder spätestens nach
 *       {@link #NEU_SPAETESTENS_MS}.</li>
 *   <li>Die Position geht vom Daemon nur an die Activity (gleiches Gerät), nie an die Seite; die Seite bekommt nur das
 *       fertige Bild, den Maßstab und die Namensnennung.</li>
 * </ul>
 */
public final class TachoKachelKarte {
    public static final long TAKT_MIN_MS = 3000;
    public static final long NEU_SPAETESTENS_MS = 30_000;
    public static final double NEU_AB_METER = 20;
    public static final double NEU_AB_GRAD = 12;
    /** Älter als das: keine Position, kein Bild. */
    public static final long POS_MAX_ALTER_MS = 30_000;
    /** Fahrerdisplay-Mittelbereich in Pixeln je Einheit der Fläche (960 / 1000). */
    public static final double PX_JE_EINHEIT = 0.96;
    public static final int MAX_BREITE_PX = 640;
    /** Sichtbare Breite des Kastens in Metern. */
    public static final double METER_FAHRT = 700, METER_NORD = 900;
    /** Auto im Kasten: fahrtrichtungsoben bei 72 % der Höhe (wie die Seite), nordoben in der Mitte. */
    public static final double AUTO_Y_FAHRT = 0.72;
    static final double ERDUMFANG = 40_075_016.686;

    private TachoKachelKarte() {}

    /** Größe des Kastens in Pixeln und Blick, oder null ohne Karte im Design. */
    public static JSONObject kasten(String design, JSONObject eigen) {
        try {
            if ("kombi".equals(design)) return kastenPx(384, 259, "fahrt");
            if ("kasten".equals(design)) return kastenPx(442, 285, "nord");
            if (!TachoEigen.NAME.equals(design) || eigen == null || eigen.optInt("version") != TachoFrei.VERSION) return null;
            JSONArray el = eigen.optJSONArray("elemente");
            for (int i = 0; el != null && i < el.length(); i++) {
                JSONObject e = el.optJSONObject(i);
                if (e == null || !"karte".equals(e.optString("typ"))) continue;
                return kastenPx((int) Math.round(e.optInt("b") * PX_JE_EINHEIT), (int) Math.round(e.optInt("h") * PX_JE_EINHEIT),
                        "nord".equals(e.optString("blick")) ? "nord" : "fahrt");
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static JSONObject kastenPx(int b, int h, String blick) throws Exception {
        if (b < 8 || h < 8) return null;
        if (b > MAX_BREITE_PX) { h = (int) Math.round(h * (double) MAX_BREITE_PX / b); b = MAX_BREITE_PX; }
        return new JSONObject().put("b", b).put("h", h).put("blick", blick);
    }

    /** Position für die Activity; null ohne Fix oder wenn älter als {@link #POS_MAX_ALTER_MS}. */
    public static JSONObject position(double lat, double lon, double kurs, long fixZeit, long jetzt) {
        try {
            if (Double.isNaN(lat) || Double.isNaN(lon) || (lat == 0 && lon == 0)) return null;
            if (Math.abs(lat) > 85 || Math.abs(lon) > 180) return null;
            long alter = jetzt - fixZeit;
            if (fixZeit <= 0 || alter < 0 || alter > POS_MAX_ALTER_MS) return null;
            JSONObject p = new JSONObject().put("lat", lat).put("lon", lon);
            if (!Double.isNaN(kurs) && !Double.isInfinite(kurs) && kurs >= 0) p.put("kurs", ((kurs % 360) + 360) % 360);
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    /** Meter je Pixel bei MapLibre-Zoom z (512er Kacheln). */
    public static double mpp(double lat, double zoom) {
        return ERDUMFANG * Math.cos(Math.toRadians(lat)) / (512 * Math.pow(2, zoom));
    }

    /** Zoom, bei dem [breitePx] etwa [meter] zeigen; 10..18. */
    public static double zoom(double lat, int breitePx, double meter) {
        double z = Math.log(ERDUMFANG * Math.cos(Math.toRadians(lat)) * breitePx / (512 * meter)) / Math.log(2);
        return Math.max(10, Math.min(18, z));
    }

    public static double meter(String blick) { return "nord".equals(blick) ? METER_NORD : METER_FAHRT; }

    /** Mitte des Bildes: fahrtrichtungsoben voraus versetzt, damit das Auto bei {@link #AUTO_Y_FAHRT} steht. */
    public static double[] mitte(double lat, double lon, double kurs, String blick, int hoehePx, double mpp) {
        if ("nord".equals(blick) || Double.isNaN(kurs)) return new double[] {lat, lon};
        double d = (AUTO_Y_FAHRT - 0.5) * hoehePx * mpp, k = Math.toRadians(kurs);
        return new double[] {lat + d * Math.cos(k) / 111_320.0, lon + d * Math.sin(k) / (111_320.0 * Math.cos(Math.toRadians(lat)))};
    }

    /** Abstand in Metern (genähert, für kurze Strecken). */
    public static double abstandM(double lat1, double lon1, double lat2, double lon2) {
        double x = Math.toRadians(lon2 - lon1) * Math.cos(Math.toRadians((lat1 + lat2) / 2)), y = Math.toRadians(lat2 - lat1);
        return Math.sqrt(x * x + y * y) * 6_371_000.0;
    }

    /** Letztes angefordertes Bild. */
    public static final class Stand {
        public final double lat, lon, kurs;
        public final String schluessel;
        public final long zeit;

        public Stand(double lat, double lon, double kurs, String schluessel, long zeit) {
            this.lat = lat; this.lon = lon; this.kurs = kurs; this.schluessel = schluessel; this.zeit = zeit;
        }
    }

    /** Braucht es ein neues Bild? [schluessel] fasst Quelle, Kasten, Blick und Tag/Nacht zusammen. */
    public static boolean neuNoetig(Stand alt, double lat, double lon, double kurs, String blick, String schluessel, long jetzt) {
        if (alt == null) return true;
        if (jetzt - alt.zeit < TAKT_MIN_MS) return false;
        if (!alt.schluessel.equals(schluessel)) return true;
        if (jetzt - alt.zeit >= NEU_SPAETESTENS_MS) return true;
        if (abstandM(alt.lat, alt.lon, lat, lon) >= NEU_AB_METER) return true;
        if ("fahrt".equals(blick) && !Double.isNaN(kurs) && !Double.isNaN(alt.kurs)) {
            double dk = Math.abs(((kurs - alt.kurs) % 360 + 540) % 360 - 180);
            return dk >= NEU_AB_GRAD;
        }
        return false;
    }

    /** Namensnennung je Quelle, klein im Kasten (Lizenz der Kartenquelle). */
    public static String nennung(String quelle) {
        if ("osm".equals(quelle)) return "© OpenStreetMap";
        if ("community".equals(quelle)) return "© OpenStreetMap · Protomaps";
        return "";
    }
    // ------------------------------------------------------------ 0.1.43.9 (F, B1) Schutz vor Grafik-Abstuerzen

    /** MapLibre rendert mit OpenGL ES 3; ohne das (z. B. GLES 2) bricht der Snapshotter nativ ab und nimmt Panoptes mit. */
    public static final int GLES_MIN = 0x30000;
    /** Spielraum beim Vergleich der Startzeit des Geraets (Uhr gegen Laufzeit gerechnet, ungenau). */
    public static final long START_SPIEL_MS = 120_000;

    /** Reicht die Grafik des Geraets ({@code ConfigurationInfo.reqGlEsVersion}) und gibt es eine passende EGL-Konfiguration? */
    public static boolean grafikReicht(int reqGlEsVersion, boolean eglKonfig) {
        return reqGlEsVersion >= GLES_MIN && eglKonfig;
    }

    /**
     * Ist ein Snapshot in diesem Geraetestart schon einmal abgestuerzt? Die Marke (Startzeit des Geraets, als der
     * Snapshot begann) bleibt nur stehen, wenn der Prozess dabei starb. Dann bis zum naechsten Neustart des Geraets
     * keine Kacheln, nur das gedaempfte Zeichen. Eine Marke aus einem frueheren Geraetestart zaehlt nicht.
     */
    public static boolean nachAbsturzGesperrt(long markeStart, long jetztStart) {
        return markeStart > 0 && Math.abs(markeStart - jetztStart) <= START_SPIEL_MS;
    }

    /**
     * Sperrt ein Fehler des Snapshotters die Kacheln fuer die Sitzung? Ja, ausser er kommt nur vom Netz bzw. vom Laden
     * des Stils (offline gestartet, Server weg): Das heilt sich, der naechste Takt versucht es erneut.
     */
    public static boolean fehlerSperrt(String meldung) {
        if (meldung == null) return true;
        String m = meldung.toLowerCase(java.util.Locale.ROOT);
        return !(m.contains("network") || m.contains("connection") || m.contains("timeout") || m.contains("timed out")
                || m.contains("resolve host") || m.contains("failed to load style") || m.contains("http"));
    }

    /** Quelle, die die Seite bekommt: ohne Kacheln (Grafik reicht nicht, Absturz, Fehler) wie ohne Quelle. */
    public static String quelleFuerSeite(String quelle, boolean kachelnMoeglich) {
        return kachelnMoeglich ? quelle : at.greenchili.surfmate.einstellungen.Kartenquelle.KEINE;
    }
}
