package at.greenchili.surfmate.charging;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * Zeittarif am Tarif des Heimorts (LADEPLAN_KONZEPT §3, 0.1.20): Zeitbänder mit €/kWh, getrennt nach Werktag und
 * Wochenende (Feiertage zählen als Wochenende, Liste je Land AT/DE), Rest = Grundpreis. Eine Quelle für den Ladeplan
 * und die Abrechnung ({@code SocHistoryDatabase.zeittarifNachpreisen}). Reine Logik, keine Android-Klassen.
 * <pre>
 * {art:"zeittarif", grundpreis:0.30, land:"AT"|"DE"|"",
 *  baender:[{tage:"werktag"|"wochenende"|"alle", von:"11:00", bis:"15:00", preis:0.12}]}
 * </pre>
 * Ein Band gehört zum Kalendertag des Zeitpunkts: „22:00–06:00 werktag“ gilt Mo–Fr 00:00–06:00 und 22:00–24:00.
 * {@code bis:"24:00"} = Tagesende. Überlappen Bänder, gilt das erste passende.
 * <p>0.1.21 Börse ({@code art:"boerse"}, nur mit Schalter „dynamischer Tarif“): {@code {art:"boerse", land:"AT"|"DE",
 * aufschlag:0.12, grundpreis:0.30, baender:[…Zusatzaufschläge…]}} – Preis = Börse ({@link BoersenPreise}) + Aufschlag +
 * Aufschlag des passenden Bands. Ist kein Börsenpreis bekannt (Schalter aus, noch nicht veröffentlicht), gilt der
 * Grundpreis als Ersatz. Börsenpreise dürfen negativ sein.
 */
public final class Zeittarif {

    public static final int MAX_BAENDER = 12;
    static final double MAX_PREIS = 10.0;

    /** Vorlagen der Einrichtung (Schritt 1). */
    public static final String VORLAGE_TAG_NACHT = "tag_nacht";
    public static final String VORLAGE_MITTAG = "mittag";

    public static final class Band {
        final String tage;
        final int vonMin;
        final int bisMin;
        final double preis;

        Band(String tage, int vonMin, int bisMin, double preis) {
            this.tage = tage;
            this.vonMin = vonMin;
            this.bisMin = bisMin;
            this.preis = preis;
        }

        boolean gilt(boolean wochenende, int minute) {
            if ("werktag".equals(tage) && wochenende) return false;
            if ("wochenende".equals(tage) && !wochenende) return false;
            if (vonMin == bisMin) return true;   // 00:00–24:00 bzw. ganzer Tag
            if (vonMin < bisMin) return minute >= vonMin && minute < bisMin;
            return minute >= vonMin || minute < bisMin;   // über Mitternacht
        }
    }

    private final double grundpreis;
    private final String land;
    private final Band[] baender;
    private final boolean boerse;
    private final double aufschlag;

    private Zeittarif(double grundpreis, String land, Band[] baender, boolean boerse, double aufschlag) {
        this.grundpreis = grundpreis;
        this.land = land;
        this.baender = baender;
        this.boerse = boerse;
        this.aufschlag = aufschlag;
    }

    /** Börsentarif (0.1.21)? */
    public boolean istBoerse() {
        return boerse;
    }

    /**
     * Prüft und liest einen Zeittarif. Wirft {@link IllegalArgumentException} mit Kurzgrund bei Unsinn (die Datei ist
     * von Hand änderbar; ein kaputter Tarif darf weder planen noch abrechnen).
     */
    public static Zeittarif aus(JSONObject j) {
        if (j == null) throw new IllegalArgumentException("zeittarif_fehlt");
        String art = j.optString("art", "");
        boolean boerse = "boerse".equals(art);
        if (!"zeittarif".equals(art) && !boerse) throw new IllegalArgumentException("art_ungueltig");
        double g = j.optDouble("grundpreis", Double.NaN);
        if (!preisOk(g)) throw new IllegalArgumentException("grundpreis_ungueltig");
        String land = j.optString("land", "");
        if (!land.isEmpty() && !"AT".equals(land) && !"DE".equals(land)) throw new IllegalArgumentException("land_ungueltig");
        double aufschlag = boerse ? j.optDouble("aufschlag", Double.NaN) : 0;
        if (boerse && (land.isEmpty() || !preisOk(aufschlag) || aufschlag > 1)) throw new IllegalArgumentException("aufschlag_ungueltig");
        JSONArray b = j.optJSONArray("baender");
        if (b == null) b = new JSONArray();
        if (b.length() > MAX_BAENDER) throw new IllegalArgumentException("zu_viele_baender");
        Band[] arr = new Band[b.length()];
        for (int i = 0; i < b.length(); i++) {
            JSONObject e = b.optJSONObject(i);
            if (e == null) throw new IllegalArgumentException("band_ungueltig");
            String tage = e.optString("tage", "alle");
            if (!"werktag".equals(tage) && !"wochenende".equals(tage) && !"alle".equals(tage)) {
                throw new IllegalArgumentException("band_ungueltig");
            }
            int von = minute(e.optString("von", ""), false);
            int bis = minute(e.optString("bis", ""), true);
            double p = e.optDouble("preis", Double.NaN);
            if (von < 0 || bis < 0 || !preisOk(p)) throw new IllegalArgumentException("band_ungueltig");
            arr[i] = new Band(tage, von, bis % 1440, p);
        }
        return new Zeittarif(g, land, arr, boerse, aufschlag);
    }

    /** null, wenn kein (gültiger) Zeittarif. */
    public static Zeittarif oderNull(JSONObject j) {
        try {
            return j == null ? null : aus(j);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Vorlage als JSON (Grundpreis = bisheriger fester Preis, günstig = 2/3 davon, gerundet; änderbar). */
    public static JSONObject vorlage(String name, double grundpreis, String land) throws JSONException {
        double g = preisOk(grundpreis) && grundpreis > 0 ? grundpreis : 0.30;
        double guenstig = Math.round(g * 2.0 / 3.0 * 100.0) / 100.0;
        JSONArray b = new JSONArray();
        if (VORLAGE_TAG_NACHT.equals(name)) {
            b.put(new JSONObject().put("tage", "alle").put("von", "22:00").put("bis", "06:00").put("preis", guenstig));
        } else if (VORLAGE_MITTAG.equals(name)) {
            b.put(new JSONObject().put("tage", "alle").put("von", "11:00").put("bis", "15:00").put("preis", guenstig));
        }
        return new JSONObject().put("art", "zeittarif").put("grundpreis", g).put("land", land == null ? "" : land).put("baender", b);
    }

    static boolean preisOk(double p) {
        return !Double.isNaN(p) && !Double.isInfinite(p) && p >= 0 && p <= MAX_PREIS;
    }

    /** "HH:MM" → Minute des Tages; "24:00" nur für {@code bis}. -1 = ungültig. */
    static int minute(String s, boolean bis) {
        if (s == null || !s.matches("\\d{2}:\\d{2}")) return -1;
        int h = Integer.parseInt(s.substring(0, 2));
        int m = Integer.parseInt(s.substring(3));
        if (bis && h == 24 && m == 0) return 1440;
        if (h > 23 || m > 59) return -1;
        return h * 60 + m;
    }

    public double grundpreis() {
        return grundpreis;
    }

    public String land() {
        return land;
    }

    /** Preis €/kWh zum Zeitpunkt (ms) in der Zeitzone. */
    public double preis(long ms, ZoneId zone) {
        ZonedDateTime z = Instant.ofEpochMilli(ms).atZone(zone);
        boolean we = wochenende(z.toLocalDate(), land);
        int minute = z.getHour() * 60 + z.getMinute();
        if (boerse) {
            double b = BoersenPreise.aktuell(land, ms);
            double basis = Double.isNaN(b) ? grundpreis : b + aufschlag;
            for (Band x : baender) if (x.gilt(we, minute)) return basis + x.preis;
            return basis;
        }
        for (Band b : baender) if (b.gilt(we, minute)) return b.preis;
        return grundpreis;
    }

    /** Börsentarif: ist für diesen Zeitpunkt ein echter Börsenpreis bekannt (sonst Ersatzpreis)? */
    public boolean boerseBekannt(long ms) {
        return boerse && !Double.isNaN(BoersenPreise.aktuell(land, ms));
    }

    /**
     * Energiegewichteter Preis einer Ladung (Abrechnung, §3 „zeitgenau“): Leistungsproben ({@code t} ms, {@code kw})
     * werden wie in {@code integrateSessionEnergyKwh} als Trapeze integriert (Probe ≤ 0 = Unterbrechung, Lücke über
     * 10 min zählt nicht), jedes Stück zum Preis seiner Mitte. Ohne brauchbare Proben: Mittel über die Ladezeit in
     * Viertelstunden. NaN, wenn gar nichts geht.
     */
    public double mittelpreis(long[] t, double[] kw, long start, long ende, ZoneId zone) {
        double e = 0, k = 0;
        long vt = -1;
        double vp = 0;
        for (int i = 0; t != null && i < t.length; i++) {
            double p = kw[i];
            if (!(p > 0) || p > 500) {
                vt = -1;
                continue;
            }
            if (vt > 0 && t[i] > vt && t[i] - vt <= 10 * 60_000L) {
                double ei = (vp + p) / 2.0 * (t[i] - vt) / 3_600_000.0;
                e += ei;
                k += ei * preis(vt + (t[i] - vt) / 2, zone);
            }
            vt = t[i];
            vp = p;
        }
        if (e > 1e-6) return k / e;
        if (ende <= start) return Double.NaN;
        double s = 0;
        int n = 0;
        for (long x = start; x < ende && n < 4 * 24 * 7; x += 15 * 60_000L) {
            s += preis(x, zone);
            n++;
        }
        return n == 0 ? Double.NaN : s / n;
    }

    /** Samstag, Sonntag oder gesetzlicher Feiertag (bundesweit) im Land. */
    public static boolean wochenende(LocalDate d, String land) {
        DayOfWeek w = d.getDayOfWeek();
        return w == DayOfWeek.SATURDAY || w == DayOfWeek.SUNDAY || feiertag(d, land);
    }

    /** Gesetzliche Feiertage, die landesweit gelten (AT; DE nur bundeseinheitliche). */
    public static boolean feiertag(LocalDate d, String land) {
        if (!"AT".equals(land) && !"DE".equals(land)) return false;
        return feiertage(d.getYear(), land).contains(d);
    }

    static Set<LocalDate> feiertage(int jahr, String land) {
        Set<LocalDate> s = new HashSet<>();
        LocalDate ostern = ostersonntag(jahr);
        s.add(LocalDate.of(jahr, 1, 1));
        s.add(ostern.plusDays(1));    // Ostermontag
        s.add(LocalDate.of(jahr, 5, 1));
        s.add(ostern.plusDays(39));   // Christi Himmelfahrt
        s.add(ostern.plusDays(50));   // Pfingstmontag
        s.add(LocalDate.of(jahr, 12, 25));
        s.add(LocalDate.of(jahr, 12, 26));
        if ("AT".equals(land)) {
            s.add(LocalDate.of(jahr, 1, 6));
            s.add(ostern.plusDays(60));   // Fronleichnam
            s.add(LocalDate.of(jahr, 8, 15));
            s.add(LocalDate.of(jahr, 10, 26));
            s.add(LocalDate.of(jahr, 11, 1));
            s.add(LocalDate.of(jahr, 12, 8));
        } else {
            s.add(ostern.minusDays(2));   // Karfreitag
            s.add(LocalDate.of(jahr, 10, 3));
        }
        return s;
    }

    /** Gaußsche Osterformel (gregorianisch, Anonymous-Algorithmus). */
    static LocalDate ostersonntag(int y) {
        int a = y % 19, b = y / 100, c = y % 100, d = b / 4, e = b % 4;
        int f = (b + 8) / 25, g = (b - f + 1) / 3, h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4, k = c % 4, l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int monat = (h + l - 7 * m + 114) / 31, tag = (h + l - 7 * m + 114) % 31 + 1;
        return LocalDate.of(y, monat, tag);
    }

    /** Wochentag-Typ eines Kalendertags für die Abfahrtszeiten: "werktag" oder "wochenende". */
    public static String tagesart(LocalDate d, String land) {
        return wochenende(d, land) ? "wochenende" : "werktag";
    }
}
