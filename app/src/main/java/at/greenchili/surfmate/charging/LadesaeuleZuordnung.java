package at.greenchili.surfmate.charging;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Ladesäule und Betreiber einer Ladung erkennen (Vorschlag v2, 03.11.2026) – rein, ohne Netz und ohne Datenbank.
 *
 * <p>Ablauf: Panoptes fragt den eigenen Planer ({@code POST umkreis}) mit einer auf 0,01° gerundeten Position
 * ({@link #anfrage}). Die genaue Wahl trifft das Auto mit der echten Startposition ({@link #zuordnen}):
 * <ol>
 *   <li>Kandidaten liegen höchstens {@value #KANDIDAT_M} m entfernt.</li>
 *   <li>Stromart passt: DC-Ladung nur zu DC-Säulen (auch „HPC“), AC nur zu AC; unbekannt = beide.</li>
 *   <li>Leistung plausibel: Spitze ≤ Säulen-kW × {@value #KW_TOLERANZ}.</li>
 *   <li>„auto“ nur, wenn genau EINE passende Säule näher als {@value #SICHER_M} m liegt und keine zweite passende
 *       Säule näher als {@value #NAH_M} m daneben steht. Sonst „unsicher“ (Anzeige „Betreiber? · prüfen“).</li>
 * </ol>
 * Einträge mit gleichem Namen und Betreiber gelten als eine Säule (der Planer listet manche Standorte doppelt).
 *
 * <p>Datenschutz: Koordinaten verlassen diese Klasse nie in Texten; gespeichert werden nur Abstände in Metern.
 */
public final class LadesaeuleZuordnung {

    private LadesaeuleZuordnung() {}

    public static final double KANDIDAT_M = 150;
    public static final double SICHER_M = 60;
    public static final double NAH_M = 25;
    public static final double KW_TOLERANZ = 1.15;
    /** Radius der Planer-Anfrage in km. */
    public static final int RADIUS_KM = 2;
    public static final int MAX_KANDIDATEN = 3;

    public static final String AUTO = "auto";
    public static final String UNSICHER = "unsicher";
    public static final String NUTZER = "nutzer";
    public static final String GEMERKT = "gemerkt";
    /** Gefragt, aber keine passende Säule. */
    public static final String KEINE = "keine";
    /** Nicht gefragt: privater Ort, zuhause, Ruhezone. */
    public static final String PRIVAT = "privat";
    /** Gefragt, Planer nicht erreichbar. Nur der Knopf „nachtragen“ fragt erneut. */
    public static final String FEHLER = "fehler";

    /** Ein Tarif an einer Säule, nur Preis je kWh. */
    public static final class Tarif {
        public final String name;
        public final double preisKwh;
        public final boolean adhoc;
        public final boolean meine;
        public final String stand;

        public Tarif(String name, double preisKwh, boolean adhoc, boolean meine, String stand) {
            this.name = name == null ? "" : name;
            this.preisKwh = preisKwh;
            this.adhoc = adhoc;
            this.meine = meine;
            this.stand = stand == null ? "" : stand;
        }

        JSONObject json() throws Exception {
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.put("preisKwh", preisKwh);
            o.put("adhoc", adhoc);
            o.put("meine", meine);
            o.put("stand", stand.isEmpty() ? JSONObject.NULL : stand);
            return o;
        }

        static Tarif aus(JSONObject o) {
            if (o == null) return null;
            double p = o.optDouble("preisKwh", Double.NaN);
            if (!(p > 0) || p > 10) return null;
            return new Tarif(o.optString("name", ""), p, o.optBoolean("adhoc"), o.optBoolean("meine"),
                    o.isNull("stand") ? "" : o.optString("stand", ""));
        }
    }

    /** Eine Säule laut Planer. {@code art} ist "AC" oder "DC". */
    public static final class Station {
        public final String id;
        public final String name;
        public final String betreiber;
        public final String art;
        public final double kw;
        final double lat;
        final double lon;
        public final List<Tarif> tarife;

        public Station(String id, String name, String betreiber, String art, double kw, double lat, double lon,
                       List<Tarif> tarife) {
            this.id = id == null ? "" : id;
            this.name = name == null ? "" : name.trim();
            this.betreiber = betreiber == null ? "" : betreiber.trim();
            this.art = art;
            this.kw = kw;
            this.lat = lat;
            this.lon = lon;
            this.tarife = tarife == null ? Collections.<Tarif>emptyList() : tarife;
        }

        /** Für die Anzeige: Betreiber, sonst Name. */
        public String kurz() {
            return !betreiber.isEmpty() ? betreiber : name;
        }
    }

    /** Ein Kandidat mit Abstand zur Startposition (nur Meter, nie Koordinaten). */
    public static final class Kandidat {
        public final Station station;
        public final double abstandM;

        Kandidat(Station station, double abstandM) {
            this.station = station;
            this.abstandM = abstandM;
        }

        public JSONObject json() throws Exception {
            JSONObject o = new JSONObject();
            o.put("id", station.id);
            o.put("name", station.name);
            o.put("betreiber", station.betreiber);
            o.put("art", station.art);
            o.put("kw", station.kw > 0 ? (Object) station.kw : JSONObject.NULL);
            o.put("abstandM", (int) (Math.round(abstandM / 5.0) * 5));
            JSONArray t = new JSONArray();
            for (Tarif x : station.tarife) t.put(x.json());
            o.put("tarife", t);
            return o;
        }
    }

    public static final class Ergebnis {
        public final String zuordnung;
        /** Gewählte (auto) bzw. vermutete (unsicher) Säule, sonst null. */
        public final Kandidat station;
        /** Bis zu {@link #MAX_KANDIDATEN} passende Säulen, die nächste zuerst. */
        public final List<Kandidat> kandidaten;

        Ergebnis(String zuordnung, Kandidat station, List<Kandidat> kandidaten) {
            this.zuordnung = zuordnung;
            this.station = station;
            this.kandidaten = kandidaten;
        }

        public JSONArray kandidatenJson() throws Exception {
            JSONArray a = new JSONArray();
            for (Kandidat k : kandidaten) a.put(k.json());
            return a;
        }
    }

    // ------------------------------------------------------------------ Anfrage

    /**
     * Body für {@code POST umkreis}: Position auf 0,01° gerundet (rund 1 km), Radius {@value #RADIUS_KM} km, ohne
     * Schlüssel. {@code karten} sind die Tarif-IDs aus „Meine Ladekarten“ (damit der Planer {@code preis_meine} kennt).
     */
    public static JSONObject anfrage(double lat, double lng, JSONArray karten) throws Exception {
        JSONObject b = new JSONObject();
        b.put("lat", grob(lat));
        b.put("lon", grob(lng));
        b.put("radius", RADIUS_KM);
        b.put("limit", 200);
        JSONArray k = new JSONArray();
        for (int i = 0; karten != null && i < karten.length() && k.length() < 30; i++) {
            JSONObject e = karten.optJSONObject(i);
            int id = e != null ? e.optInt("id", 0) : karten.optInt(i, 0);
            if (id > 0) k.put(id);
        }
        if (k.length() > 0) b.put("karten", k);
        return b;
    }

    /** Auf 0,01° gerundet, als exakte Dezimalzahl (kein 48.309999…). */
    public static BigDecimal grob(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    /** Planer-Zelle (0,01°) für das Nachtragen: Ladungen derselben Zelle teilen sich eine Anfrage. */
    public static String zelle(double lat, double lng) {
        return grob(lat).toPlainString() + "|" + grob(lng).toPlainString();
    }

    // ------------------------------------------------------------------ Antwort lesen

    /** Säulen aus der Planer-Antwort; Unbrauchbares wird übersprungen. */
    public static List<Station> ausPlaner(JSONObject antwort) {
        List<Station> l = new ArrayList<>();
        JSONArray st = antwort == null ? null : antwort.optJSONArray("stationen");
        for (int i = 0; st != null && i < st.length(); i++) {
            JSONObject s = st.optJSONObject(i);
            if (s == null) continue;
            double lat = s.optDouble("lat", Double.NaN), lon = s.optDouble("lon", Double.NaN);
            if (!Double.isFinite(lat) || !Double.isFinite(lon)) continue;
            String art = art(s.optString("art", ""));
            if (art == null) continue;
            String name = einzeilig(s.optString("name", ""), 120);
            String betreiber = einzeilig(s.optString("betreiber", ""), 90);
            if (name.isEmpty() && betreiber.isEmpty()) continue;
            l.add(new Station(einzeilig(s.optString("id", ""), 60), name, betreiber, art,
                    s.optDouble("kw", 0), lat, lon, tarife(s)));
        }
        return l;
    }

    static String art(String a) {
        String x = a == null ? "" : a.trim().toUpperCase(Locale.ROOT);
        if (x.equals("AC")) return "AC";
        if (x.equals("DC") || x.equals("HPC")) return "DC";
        return null;
    }

    /**
     * Tarife einer Säule: eigene Karten, ad hoc und die Tarife des Betreibers selbst (z. B. dessen App). Allgemeine
     * Fremdkarten ohne Bezug zur Säule bleiben weg. Nur Preis je kWh; Zeittarife lassen sich nicht umrechnen.
     */
    static List<Tarif> tarife(JSONObject s) {
        List<Tarif> l = new ArrayList<>();
        JSONArray za = s.optJSONArray("alle_zahlungsarten");
        for (int i = 0; za != null && i < za.length(); i++) {
            JSONObject z = za.optJSONObject(i);
            if (z == null) continue;
            boolean meine = z.optBoolean("besitze_ich"), adhoc = z.optBoolean("ist_adhoc");
            boolean eigenerBetreiber = !"*".equals(z.optString("regel_cpo", "*"))
                    || !"*".equals(z.optString("regel_station", "*"));
            if (!meine && !adhoc && !eigenerBetreiber) continue;
            double p = z.optDouble("preis_kwh", Double.NaN), min = z.optDouble("preis_min", 0);
            if (!(p > 0) || p > 10 || (Double.isFinite(min) && min > 0)) continue;
            String name = adhoc ? "ad hoc" : einzeilig((z.optString("msp", "") + " " + z.optString("tarif", "")).trim(), 80);
            JSONObject h = z.optJSONObject("herkunft");
            String stand = h == null ? "" : h.optString("preis_stand", "");
            if (stand.equals("null")) stand = "";
            l.add(new Tarif(name, p, adhoc, meine, stand));
        }
        if (za == null || za.length() == 0) {
            JSONObject m = s.optJSONObject("preis_meine");
            if (m != null && m.optDouble("preis_kwh", 0) > 0 && !(m.optDouble("preis_min", 0) > 0)) {
                l.add(new Tarif(einzeilig(m.optString("karte", ""), 80), m.optDouble("preis_kwh"), false, true,
                        m.optString("stand", "")));
            }
            JSONObject a = s.optJSONObject("preis_adhoc");
            if (a != null && a.optDouble("preis_kwh", 0) > 0) {
                l.add(new Tarif("ad hoc", a.optDouble("preis_kwh"), true, false, ""));
            }
        }
        return l;
    }

    // ------------------------------------------------------------------ Zuordnung

    /** Passt die Säule zur Ladung (Stromart, Leistung)? {@code isDc}: 1 DC, 0 AC, sonst unbekannt. */
    static boolean passt(Station s, int isDc, double peakKw) {
        if (isDc == 1 && !"DC".equals(s.art)) return false;
        if (isDc == 0 && !"AC".equals(s.art)) return false;
        if (peakKw > 0 && s.kw > 0 && peakKw > s.kw * KW_TOLERANZ) return false;
        return true;
    }

    public static Ergebnis zuordnen(List<Station> stationen, double lat, double lng, int isDc, double peakKw) {
        List<Kandidat> passend = new ArrayList<>();
        if (stationen != null && Double.isFinite(lat) && Double.isFinite(lng) && !(lat == 0 && lng == 0)) {
            for (Station s : stationen) {
                if (!passt(s, isDc, peakKw)) continue;
                double d = meter(lat, lng, s.lat, s.lon);
                if (!(d <= KANDIDAT_M)) continue;
                passend.add(new Kandidat(s, d));
            }
        }
        Collections.sort(passend, (a, b) -> Double.compare(a.abstandM, b.abstandM));
        // Doppelte (gleiche id bzw. gleicher Name + Betreiber + Art) nur einmal, die nächste bleibt
        List<Kandidat> eindeutig = new ArrayList<>();
        for (Kandidat k : passend) {
            boolean doppelt = false;
            for (Kandidat e : eindeutig) {
                if ((!k.station.id.isEmpty() && k.station.id.equals(e.station.id))
                        || (k.station.name.equalsIgnoreCase(e.station.name)
                        && k.station.betreiber.equalsIgnoreCase(e.station.betreiber)
                        && k.station.art.equals(e.station.art))) {
                    doppelt = true;
                    break;
                }
            }
            if (!doppelt) eindeutig.add(k);
        }
        if (eindeutig.isEmpty()) return new Ergebnis(KEINE, null, eindeutig);
        List<Kandidat> top = new ArrayList<>(eindeutig.subList(0, Math.min(MAX_KANDIDATEN, eindeutig.size())));
        Kandidat erster = eindeutig.get(0);
        int unterSicher = 0;
        for (Kandidat k : eindeutig) if (k.abstandM < SICHER_M) unterSicher++;
        boolean nachbar = false;
        for (int i = 1; i < eindeutig.size(); i++) {
            Station s = eindeutig.get(i).station;
            if (meter(erster.station.lat, erster.station.lon, s.lat, s.lon) < NAH_M) nachbar = true;
        }
        boolean sicher = erster.abstandM < SICHER_M && unterSicher == 1 && !nachbar;
        return new Ergebnis(sicher ? AUTO : UNSICHER, erster, top);
    }

    /** Kandidaten aus der gespeicherten Liste wieder lesen (für „ändern“). */
    public static List<JSONObject> kandidatenAus(String json) {
        List<JSONObject> l = new ArrayList<>();
        if (json == null || json.isEmpty()) return l;
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null) l.add(o);
            }
        } catch (Exception ignored) {
        }
        return l;
    }

    // ------------------------------------------------------------------ Datenschutz, gemerkt

    /**
     * Darf für diese Position gefragt werden? Nein an privaten Orten, „zuhause“ (auch Heim-Wallbox, die als Ort
     * „zuhause“ angelegt ist) und in Ruhezonen. {@code orte}: Ortsliste ({@code lat, lon, r, eigenschaften}),
     * {@code ruhe}: Ruhezonen bzw. alte Heim-/Büro-Zonen ({@code lat, lon, r}).
     */
    public static boolean privat(JSONArray orte, JSONArray ruhe, double lat, double lng) {
        for (int i = 0; orte != null && i < orte.length(); i++) {
            JSONObject o = orte.optJSONObject(i);
            if (o == null) continue;
            JSONObject e = o.optJSONObject("eigenschaften");
            boolean schutz = e != null && (e.optBoolean("privat") || e.optBoolean("zuhause") || e.optBoolean("ruhezone"));
            if (schutz && innen(o, lat, lng)) return true;
        }
        for (int i = 0; ruhe != null && i < ruhe.length(); i++) {
            JSONObject o = ruhe.optJSONObject(i);
            if (o != null && innen(o, lat, lng)) return true;
        }
        return false;
    }

    private static boolean innen(JSONObject o, double lat, double lng) {
        double r = o.has("r") ? o.optDouble("r", Double.NaN) : o.optDouble("radiusM", Double.NaN);
        double oLat = o.optDouble("lat", Double.NaN);
        double oLon = o.has("lon") ? o.optDouble("lon", Double.NaN) : o.optDouble("lng", Double.NaN);
        if (!Double.isFinite(r) || !Double.isFinite(oLat) || !Double.isFinite(oLon)) return false;
        // Sicherheitsrand: lieber einmal zu wenig fragen
        return meter(lat, lng, oLat, oLon) <= r + 50;
    }

    /**
     * Gemerkte Säule: Der Tarif, der diese Ladung bepreist hat, gehört zu einer mit „Säule merken“ angelegten Säule.
     * {@code gemerkt}: Liste {@code [{tarif, id, name, betreiber, art}]} aus Config-Abschnitt {@code ladesaeule}.
     */
    public static JSONObject gemerkt(JSONArray gemerkt, String tariffId, int isDc) {
        if (tariffId == null || tariffId.isEmpty()) return null;
        for (int i = 0; gemerkt != null && i < gemerkt.length(); i++) {
            JSONObject g = gemerkt.optJSONObject(i);
            if (g == null || !tariffId.equals(g.optString("tarif", ""))) continue;
            String art = g.optString("art", "");
            if (isDc == 1 && "AC".equals(art)) continue;
            if (isDc == 0 && "DC".equals(art)) continue;
            return g;
        }
        return null;
    }

    // ------------------------------------------------------------------ Hilfen

    static double meter(double lat1, double lon1, double lat2, double lon2) {
        double r = 6_371_000.0, dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    public static String einzeilig(String s, int max) {
        String t = s == null ? "" : s.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s{2,}", " ").trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
