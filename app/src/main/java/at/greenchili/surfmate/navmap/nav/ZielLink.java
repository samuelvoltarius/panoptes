package at.greenchili.surfmate.navmap.nav;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * „Ziel ans Auto schicken“ aus einem geteilten Karten-Link (Konkurrenzvergleich Kinex, 03.11.2026): Google Maps
 * ({@code maps.google.*}, {@code google.*}/maps, {@code goo.gl/maps}, {@code maps.app.goo.gl}), Waze
 * ({@code waze.com/ul?ll=…}, {@code waze.com/ul/h<geohash>}), {@code geo:}-URIs (auch {@code geo:0,0?q=lat,lng(Name)}),
 * OpenStreetMap und Apple Karten. Die Koordinaten aus langen Links liest {@link CoordinateInputParser}.
 *
 * <p>Rein und offline bis auf {@link #aufloesen}: Kurzlinks tragen keine Koordinaten. Sie werden nur auf
 * ausdrücklichen Wunsch und nur vom Auto selbst aufgelöst, per {@code HEAD} ohne Weiterleitung folgen zu lassen:
 * gelesen wird nur der {@code Location}-Kopf. Gesendet wird nur der Link selbst, kein Cookie, kein Referer, keine
 * Sprache, keine Position. Höchstens {@link #MAX_SPRUENGE} Sprünge, nur https, nur Kurzlink-Hosts.
 */
public final class ZielLink {

    static final int MAX_SPRUENGE = 3;
    static final int MAX_LAENGE = 2048;
    private static final int ZEIT_MS = 5000;

    /** Ergebnis: entweder Koordinaten oder ein Grund (Kennwort). */
    public static final class Ziel {
        public final double lat, lng;
        public final String name;     // nur aus dem Link selbst (geo-Label, /maps/place/<Name>), sonst null
        public final String quelle;   // google | waze | geo | osm | apple
        public final String grund;    // null = ok; kurzlink | kein_ort | unbekannter_link | leer | nicht_aufloesbar

        Ziel(double lat, double lng, String name, String quelle, String grund) {
            this.lat = lat;
            this.lng = lng;
            this.name = name;
            this.quelle = quelle;
            this.grund = grund;
        }

        public boolean ok() {
            return grund == null;
        }

        static Ziel fehler(String grund, String quelle) {
            return new Ziel(Double.NaN, Double.NaN, null, quelle, grund);
        }
    }

    private static final Pattern URL_IM_TEXT = Pattern.compile("(?i)(https?://[^\\s<>\"]+|geo:[^\\s<>\"]+)");
    private static final Pattern GEO_Q = Pattern.compile(
            "(?i)^geo:[^?]*\\?(?:.*&)?q=([+-]?\\d+(?:\\.\\d+)?)(?:,|%2C)\\s*([+-]?\\d+(?:\\.\\d+)?)(?:\\s*\\(([^)]*)\\))?");
    private static final Pattern GEO_PUNKT = Pattern.compile("(?i)^geo:([+-]?\\d+(?:\\.\\d+)?),([+-]?\\d+(?:\\.\\d+)?)");
    private static final Pattern WAZE_GEOHASH = Pattern.compile("(?i)^/ul/h([0-9b-hjkmnp-z]{4,12})/?$");
    private static final Pattern WAZE_TO = Pattern.compile(
            "(?i)[?&]to=ll\\.([+-]?\\d+(?:\\.\\d+)?)(?:,|%2C)([+-]?\\d+(?:\\.\\d+)?)");
    private static final Pattern PLACE_NAME = Pattern.compile("/maps/place/([^/@?#]{1,200})");
    private static final String GEOHASH = "0123456789bcdefghjkmnpqrstuvwxyz";

    private ZielLink() {}

    /** Link aus Text (auch „Name https://…“ aus dem Teilen-Menü) lesen. Nie eine Ausnahme. */
    public static Ziel lesen(String text) {
        try {
            String t = text == null ? "" : text.trim();
            if (t.isEmpty()) return Ziel.fehler("leer", null);
            if (t.length() > MAX_LAENGE) return Ziel.fehler("unbekannter_link", null);
            Matcher m = URL_IM_TEXT.matcher(t);
            if (!m.find()) return Ziel.fehler("unbekannter_link", null);
            String link = m.group(1);
            if (link.regionMatches(true, 0, "geo:", 0, 4)) return geo(link);
            URI u = new URI(link.replace(" ", "%20"));
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
            String pfad = u.getRawPath() == null ? "" : u.getRawPath();
            String quelle = quelle(host, pfad);
            if (quelle == null) return Ziel.fehler("unbekannter_link", null);
            if (istKurzlink(host, pfad)) return Ziel.fehler("kurzlink", quelle);
            if ("waze".equals(quelle)) {
                Matcher g = WAZE_GEOHASH.matcher(pfad);
                if (g.matches()) {
                    double[] p = geohash(g.group(1));
                    return p == null ? Ziel.fehler("kein_ort", quelle) : punkt(p[0], p[1], null, quelle);
                }
                Matcher to = WAZE_TO.matcher(link);
                if (to.find()) return punktText(to.group(1), to.group(2), null, quelle);
            }
            SearchResult r = CoordinateInputParser.INSTANCE.parse(link, null, null);
            if (r == null) return Ziel.fehler("kein_ort", quelle);
            return punkt(r.getLat(), r.getLng(), "google".equals(quelle) ? ortsname(pfad) : null, quelle);
        } catch (Throwable e) {
            return Ziel.fehler("unbekannter_link", null);
        }
    }

    /** Welche Karte? null = kein bekannter Karten-Link (dann wird er gar nicht weiter angefasst). */
    static String quelle(String host, String pfad) {
        if (host.isEmpty()) return null;
        if (host.equals("maps.app.goo.gl") || (host.equals("goo.gl") && pfad.startsWith("/maps"))) return "google";
        if (host.matches("(www\\.|maps\\.)?google\\.[a-z]{2,3}(\\.[a-z]{2})?")) {
            return host.startsWith("maps.") || pfad.startsWith("/maps") ? "google" : null;
        }
        if (host.equals("waze.com") || host.equals("www.waze.com")) {
            return pfad.startsWith("/ul") || pfad.contains("/live-map") ? "waze" : null;
        }
        if (host.equals("openstreetmap.org") || host.endsWith(".openstreetmap.org") || host.equals("osm.org")) return "osm";
        if (host.equals("maps.apple.com")) return "apple";
        return null;
    }

    /** Kurzlink ohne Koordinaten (nur Google; Waze-Kurzform {@code /ul/h…} ist ein Geohash und offline lesbar). */
    static boolean istKurzlink(String host, String pfad) {
        return host.equals("maps.app.goo.gl") || (host.equals("goo.gl") && pfad.startsWith("/maps"));
    }

    private static Ziel geo(String link) {
        Matcher q = GEO_Q.matcher(link);
        if (q.find()) {
            String name = q.group(3) == null ? null : sauber(dekodiere(q.group(3)));
            return punktText(q.group(1), q.group(2), name, "geo");
        }
        Matcher p = GEO_PUNKT.matcher(link);
        if (p.find()) {
            double lat = Double.parseDouble(p.group(1)), lng = Double.parseDouble(p.group(2));
            // geo:0,0 heißt „kein Punkt, nur Suche“ (Android-Konvention)
            if (lat == 0 && lng == 0) return Ziel.fehler("kein_ort", "geo");
            return punkt(lat, lng, null, "geo");
        }
        return Ziel.fehler("kein_ort", "geo");
    }

    private static Ziel punktText(String lat, String lng, String name, String quelle) {
        try {
            return punkt(Double.parseDouble(lat), Double.parseDouble(lng), name, quelle);
        } catch (NumberFormatException e) {
            return Ziel.fehler("kein_ort", quelle);
        }
    }

    private static Ziel punkt(double lat, double lng, String name, String quelle) {
        if (Double.isNaN(lat) || Double.isNaN(lng) || lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            return Ziel.fehler("kein_ort", quelle);
        }
        return new Ziel(lat, lng, name == null || name.isEmpty() ? null : name, quelle, null);
    }

    static String ortsname(String rawPfad) {
        Matcher m = PLACE_NAME.matcher(rawPfad == null ? "" : rawPfad);
        if (!m.find()) return null;
        return sauber(dekodiere(m.group(1).replace('+', ' ')));
    }

    private static String dekodiere(String s) {
        try {
            return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /** Anzeigename: Steuerzeichen und spitze Klammern weg, höchstens 80 Zeichen. */
    static String sauber(String s) {
        if (s == null) return null;
        String t = s.replaceAll("[\\p{Cntrl}<>]", "").trim();
        return t.length() > 80 ? t.substring(0, 80) : t;
    }

    /** Geohash → {lat, lng} (Mitte der Zelle) oder null. */
    static double[] geohash(String h) {
        if (h == null || h.isEmpty()) return null;
        double[] lat = {-90, 90}, lng = {-180, 180};
        boolean gerade = true;
        for (char c : h.toLowerCase(Locale.ROOT).toCharArray()) {
            int v = GEOHASH.indexOf(c);
            if (v < 0) return null;
            for (int bit = 4; bit >= 0; bit--) {
                double[] r = gerade ? lng : lat;
                double mitte = (r[0] + r[1]) / 2;
                if (((v >> bit) & 1) == 1) r[0] = mitte; else r[1] = mitte;
                gerade = !gerade;
            }
        }
        return new double[] {(lat[0] + lat[1]) / 2, (lng[0] + lng[1]) / 2};
    }

    // ------------------------------------------------------------------ Kurzlink auflösen (nur auf Wunsch)

    /** Ein HEAD-Abruf: liefert den {@code Location}-Kopf einer Weiterleitung oder null. */
    public interface Kopf {
        String location(String httpsUrl);
    }

    /**
     * Kurzlink auflösen: HEAD, nur den {@code Location}-Kopf lesen, höchstens {@link #MAX_SPRUENGE}-mal. Ein weiterer
     * Sprung geht nur zu einem Kurzlink-Host; jedes andere Ziel wird nur gelesen, nie abgerufen.
     */
    public static Ziel aufloesen(String text, Kopf kopf) {
        Ziel z = lesen(text);
        if (!"kurzlink".equals(z.grund)) return z;
        try {
            Matcher m = URL_IM_TEXT.matcher(text.trim());
            if (!m.find() || kopf == null) return Ziel.fehler("nicht_aufloesbar", z.quelle);
            String url = m.group(1);
            for (int i = 0; i < MAX_SPRUENGE; i++) {
                URI u = new URI(url);
                if (!"https".equalsIgnoreCase(u.getScheme())) return Ziel.fehler("nicht_aufloesbar", z.quelle);
                String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
                if (!istKurzlink(host, u.getRawPath() == null ? "" : u.getRawPath())) {
                    return Ziel.fehler("nicht_aufloesbar", z.quelle);
                }
                String ziel = kopf.location(url);
                if (ziel == null || ziel.isEmpty() || ziel.length() > MAX_LAENGE) {
                    return Ziel.fehler("nicht_aufloesbar", z.quelle);
                }
                ziel = u.resolve(ziel).toString();
                Ziel n = lesen(ziel);
                if (!"kurzlink".equals(n.grund)) return n.ok() ? n : Ziel.fehler("nicht_aufloesbar", z.quelle);
                url = ziel;
            }
        } catch (Throwable e) {
            return Ziel.fehler("nicht_aufloesbar", z.quelle);
        }
        return Ziel.fehler("nicht_aufloesbar", z.quelle);
    }

    /** Echter HEAD-Abruf ohne Weiterleitung, ohne Cookies, Referer und Sprache. */
    public static String headLocation(String httpsUrl) {
        HttpURLConnection c = null;
        try {
            URL u = new URL(httpsUrl);
            if (!"https".equalsIgnoreCase(u.getProtocol())) return null;
            c = (HttpURLConnection) u.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setRequestMethod("HEAD");
            c.setUseCaches(false);
            c.setConnectTimeout(ZEIT_MS);
            c.setReadTimeout(ZEIT_MS);
            c.setRequestProperty("User-Agent", "Panoptes");
            c.setRequestProperty("Accept", "*/*");
            int code = c.getResponseCode();
            if (code < 300 || code > 399) return null;
            return c.getHeaderField("Location");
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
