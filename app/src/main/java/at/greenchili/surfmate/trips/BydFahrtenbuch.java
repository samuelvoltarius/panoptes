package at.greenchili.surfmate.trips;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 0.1.43.20 „Verbrauch laut BYD“: BYDs eigenes Fahrtenbuch lesen und auswerten.
 *
 * <p>Quelle (Auto-Fundgrube 24.11.2026, am Auto belegt): {@code /sdcard/energydata/EC_database.db}, Tabelle
 * {@code EnergyConsumption (_id, month TEXT 'yyyyMM', date TEXT 'yyyyMMdd', start_timestamp/end_timestamp INTEGER
 * Sekunden, is_deleted INTEGER, duration INTEGER Sekunden, trip REAL km, electricity REAL kWh, fuel REAL)}. Keine
 * Positionen. Strom kann negativ sein (Bergab-Fahrt mit Rückgewinnung), km kann 0 sein.
 *
 * <p>Regeln: nur lesen (die Datei wird einmal in den Speicher kopiert, nie geöffnet, gesperrt oder beschrieben); fehlt
 * sie oder passt das Schema nicht, ist der Bereich still aus ({@code verfuegbar:false}, kein Fehler). Die Daten
 * bleiben im Auto: nur {@code GET /api/trips/byd-verbrauch} (Login, nie Extern), kein MQTT, kein Server.
 */
public final class BydFahrtenbuch {

    public static final String[] PFADE = {
            "/sdcard/energydata/EC_database.db",
            "/storage/emulated/0/energydata/EC_database.db",
    };
    static final String TABELLE = "EnergyConsumption";
    /** Unter dieser Strecke (km) ist kWh/100 km nur Rauschen und wird nicht gezeigt. */
    static final double MIN_KM = 1.0;
    private static final long NEU_LESEN_MS = 60_000L;

    private BydFahrtenbuch() {}

    /** Eine Fahrt laut BYD. Zeiten in ms. */
    public static final class Fahrt {
        public final long id, startMs, endeMs;
        public final int dauerSek;
        public final double km, kwh;

        Fahrt(long id, long startMs, long endeMs, int dauerSek, double km, double kwh) {
            this.id = id; this.startMs = startMs; this.endeMs = endeMs; this.dauerSek = dauerSek; this.km = km; this.kwh = kwh;
        }

        /** kWh/100 km oder NaN, wenn die Strecke zu kurz ist. */
        public double kwhPro100km() { return pro100(kwh, km); }
    }

    /** Ein Vergleichspartner aus dem Panoptes-Fahrtenbuch (nur Zeit, Strecke, Energie). */
    public static final class EigeneFahrt {
        public final long id, startMs, endeMs;
        public final double km, kwh;

        public EigeneFahrt(long id, long startMs, long endeMs, double km, double kwh) {
            this.id = id; this.startMs = startMs; this.endeMs = endeMs; this.km = km; this.kwh = kwh;
        }
    }

    // ==================================================================== Lesen (rein)

    /**
     * Fahrten aus einem Datei-Abbild, älteste zuerst. Gelöschte Zeilen fehlen. Null, wenn Tabelle oder Pflichtspalten
     * fehlen oder die Datei kaputt ist (= Bereich still aus).
     */
    public static List<Fahrt> lese(byte[] abbild) {
        try {
            SqliteNurLesen db = new SqliteNurLesen(abbild);
            SqliteNurLesen.Tabelle t = db.tabelle(TABELLE);
            if (t == null) return null;
            List<String> sp = SqliteNurLesen.spalten(t.sql);
            int iId = sp.indexOf("_id"), iStart = sp.indexOf("start_timestamp"), iEnde = sp.indexOf("end_timestamp");
            int iKm = sp.indexOf("trip"), iKwh = sp.indexOf("electricity");
            int iDauer = sp.indexOf("duration"), iWeg = sp.indexOf("is_deleted");
            if (iStart < 0 || iEnde < 0 || iKm < 0 || iKwh < 0) return null;
            List<Fahrt> aus = new ArrayList<>();
            for (SqliteNurLesen.Zeile z : db.zeilen(t.wurzel)) {
                Long weg = ganz(z.wert(iWeg));
                if (weg != null && weg != 0L) continue;
                Long s = ganz(z.wert(iStart)), e = ganz(z.wert(iEnde));
                Double km = zahl(z.wert(iKm)), kwh = zahl(z.wert(iKwh));
                if (s == null || e == null || km == null || kwh == null) continue;
                if (s <= 0 || e < s || km < 0 || km > 2000 || Math.abs(kwh) > 500) continue;
                Long dauer = ganz(z.wert(iDauer));
                Long id = iId >= 0 && z.wert(iId) != null ? ganz(z.wert(iId)) : Long.valueOf(z.rowid);
                aus.add(new Fahrt(id == null ? z.rowid : id, sekZuMs(s), sekZuMs(e),
                        dauer != null && dauer >= 0 ? (int) Math.min(dauer, Integer.MAX_VALUE) : (int) (e - s), km, kwh));
            }
            Collections.sort(aus, (a, b) -> Long.compare(a.startMs, b.startMs));
            return aus;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** BYD speichert Sekunden; sollte eine Firmware ms speichern, nicht doppelt umrechnen. */
    static long sekZuMs(long v) { return v > 100_000_000_000L ? v : v * 1000L; }

    private static Long ganz(Object o) {
        if (o instanceof Long) return (Long) o;
        if (o instanceof Double) return Math.round((Double) o);
        return null;
    }

    private static Double zahl(Object o) {
        if (o instanceof Double) return (Double) o;
        if (o instanceof Long) return ((Long) o).doubleValue();
        return null;
    }

    static double pro100(double kwh, double km) { return km >= MIN_KM ? kwh / km * 100.0 : Double.NaN; }

    // ==================================================================== Auswerten (rein)

    /** Summen je Zeitraum (Tag „2026-11-24“, Woche „2026-W48“, Monat „2026-11“), neueste zuerst. */
    static JSONArray gruppen(List<Fahrt> fahrten, String art, int max, ZoneId zone) throws Exception {
        Map<String, double[]> topf = new LinkedHashMap<>();
        for (Fahrt f : fahrten) {
            LocalDate tag = Instant.ofEpochMilli(f.startMs).atZone(zone).toLocalDate();
            String k;
            if ("woche".equals(art)) {
                k = String.format(Locale.ROOT, "%d-W%02d", tag.get(IsoFields.WEEK_BASED_YEAR), tag.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
            } else if ("monat".equals(art)) {
                k = String.format(Locale.ROOT, "%d-%02d", tag.getYear(), tag.getMonthValue());
            } else {
                k = tag.toString();
            }
            double[] s = topf.get(k);
            if (s == null) topf.put(k, s = new double[3]);
            s[0] += 1; s[1] += f.km; s[2] += f.kwh;
        }
        List<String> schluessel = new ArrayList<>(topf.keySet());
        Collections.sort(schluessel, Collections.reverseOrder());
        JSONArray aus = new JSONArray();
        for (String k : schluessel) {
            if (aus.length() >= max) break;
            double[] s = topf.get(k);
            aus.put(eintrag(new JSONObject().put("zeitraum", k).put("fahrten", (int) s[0]), s[1], s[2]));
        }
        return aus;
    }

    private static JSONObject eintrag(JSONObject o, double km, double kwh) throws Exception {
        o.put("km", rund(km, 1)).put("kwh", rund(kwh, 1));
        double v = pro100(kwh, km);
        o.put("kwhPro100km", Double.isNaN(v) ? JSONObject.NULL : rund(v, 1));
        return o;
    }

    static double rund(double v, int stellen) {
        double f = Math.pow(10, stellen);
        return Math.round(v * f) / f;
    }

    /**
     * Paare BYD-Fahrt ↔ Panoptes-Fahrt über die Zeit: gleiche Fahrt, wenn sich beide mindestens zur Hälfte der
     * kürzeren überlappen. Jede Panoptes-Fahrt zählt höchstens einmal; nur Fahrten ab {@link #MIN_KM} auf beiden Seiten.
     */
    static List<long[]> paare(List<Fahrt> byd, List<EigeneFahrt> eigene) {
        List<long[]> aus = new ArrayList<>();
        if (byd == null || eigene == null || eigene.isEmpty()) return aus;
        java.util.Set<Long> vergeben = new java.util.HashSet<>();
        for (int i = 0; i < byd.size(); i++) {
            Fahrt b = byd.get(i);
            if (b.km < MIN_KM) continue;
            long besteUeber = 0;
            int beste = -1;
            for (int j = 0; j < eigene.size(); j++) {
                EigeneFahrt e = eigene.get(j);
                if (e.km < MIN_KM || e.endeMs <= e.startMs || vergeben.contains(e.id)) continue;
                long ueber = Math.min(b.endeMs, e.endeMs) - Math.max(b.startMs, e.startMs);
                long kuerzer = Math.min(b.endeMs - b.startMs, e.endeMs - e.startMs);
                if (ueber <= 0 || ueber * 2 < kuerzer) continue;
                if (ueber > besteUeber) { besteUeber = ueber; beste = j; }
            }
            if (beste >= 0) {
                vergeben.add(eigene.get(beste).id);
                aus.add(new long[]{i, beste});
            }
        }
        return aus;
    }

    /** Antwort für {@code GET /api/trips/byd-verbrauch}. {@code fahrten == null} → {@code verfuegbar:false}. */
    public static JSONObject antwort(List<Fahrt> fahrten, List<EigeneFahrt> eigene, int maxFahrten, ZoneId zone) throws Exception {
        JSONObject o = new JSONObject().put("success", true);
        if (fahrten == null) return o.put("verfuegbar", false);
        o.put("verfuegbar", true);
        double km = 0, kwh = 0;
        for (Fahrt f : fahrten) { km += f.km; kwh += f.kwh; }
        o.put("gesamt", eintrag(new JSONObject().put("fahrten", fahrten.size()), km, kwh));
        if (!fahrten.isEmpty()) {
            o.put("seit", fahrten.get(0).startMs).put("bis", fahrten.get(fahrten.size() - 1).endeMs);
        }
        JSONArray liste = new JSONArray();
        for (int i = fahrten.size() - 1; i >= 0 && liste.length() < maxFahrten; i--) {
            Fahrt f = fahrten.get(i);
            liste.put(eintrag(new JSONObject().put("id", f.id).put("start", f.startMs).put("ende", f.endeMs)
                    .put("dauerSek", f.dauerSek), f.km, f.kwh));
        }
        o.put("fahrten", liste);
        o.put("tage", gruppen(fahrten, "tag", 31, zone));
        o.put("wochen", gruppen(fahrten, "woche", 26, zone));
        o.put("monate", gruppen(fahrten, "monat", 24, zone));

        List<long[]> p = paare(fahrten, eigene);
        JSONObject v = new JSONObject().put("paare", p.size());
        if (!p.isEmpty()) {
            double bKm = 0, bKwh = 0, eKm = 0, eKwh = 0;
            JSONArray je = new JSONArray();
            for (int i = p.size() - 1; i >= 0; i--) {
                Fahrt b = fahrten.get((int) p.get(i)[0]);
                EigeneFahrt e = eigene.get((int) p.get(i)[1]);
                bKm += b.km; bKwh += b.kwh; eKm += e.km; eKwh += e.kwh;
                if (je.length() < 20) {
                    double bp = b.kwhPro100km(), ep = pro100(e.kwh, e.km);
                    je.put(new JSONObject().put("bydId", b.id).put("panoptesId", e.id).put("start", b.startMs)
                            .put("bydKwhPro100km", Double.isNaN(bp) ? JSONObject.NULL : rund(bp, 1))
                            .put("panoptesKwhPro100km", Double.isNaN(ep) ? JSONObject.NULL : rund(ep, 1)));
                }
            }
            double bv = pro100(bKwh, bKm), ev = pro100(eKwh, eKm);
            v.put("bydKwhPro100km", Double.isNaN(bv) ? JSONObject.NULL : rund(bv, 1));
            v.put("panoptesKwhPro100km", Double.isNaN(ev) ? JSONObject.NULL : rund(ev, 1));
            v.put("abweichungProzent", Double.isNaN(bv) || Double.isNaN(ev) || Math.abs(bv) < 0.1
                    ? JSONObject.NULL : rund((ev - bv) / bv * 100.0, 0));
            v.put("fahrten", je);
        }
        o.put("vergleich", v);
        return o;
    }

    // ==================================================================== Datei (live, nur lesen)

    private static final Object SPERRE = new Object();
    private static long gelesenUm, gelesenStand = Long.MIN_VALUE;
    private static List<Fahrt> zwischen;

    /** Fahrten aus der ersten vorhandenen BYD-Datei, höchstens einmal je Minute neu gelesen. Null = still aus. */
    public static List<Fahrt> ausDatei() {
        for (String p : PFADE) {
            File f = new File(p);
            if (f.isFile() && f.canRead()) return ausDatei(f, System.currentTimeMillis());
        }
        return null;
    }

    static List<Fahrt> ausDatei(File f, long jetzt) {
        synchronized (SPERRE) {
            long stand = f.lastModified() ^ (f.length() << 20);
            if (zwischen != null && stand == gelesenStand && jetzt - gelesenUm < NEU_LESEN_MS) return zwischen;
            byte[] abbild = abbild(f);
            if (abbild == null) return zwischen;   // gerade nicht lesbar (BYD schreibt) → letzter Stand
            List<Fahrt> neu = lese(abbild);
            zwischen = neu;
            gelesenStand = stand;
            gelesenUm = jetzt;
            return neu;
        }
    }

    /**
     * Kopie der Datei in den Speicher. Liegt ein BYD-Journal daneben (BYD schreibt gerade) oder ändert sich die Datei
     * während des Lesens, gilt das Abbild nicht → null. Öffnet nur lesend, legt nichts an.
     */
    static byte[] abbild(File f) {
        File journal = new File(f.getPath() + "-journal");
        if (journal.exists() && journal.length() > 0) return null;
        long vorher = f.lastModified(), laenge = f.length();
        if (laenge <= 0 || laenge > SqliteNurLesen.MAX_BYTES) return null;
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) laenge);
            byte[] puffer = new byte[16 * 1024];
            int n;
            while ((n = in.read(puffer)) > 0) {
                out.write(puffer, 0, n);
                if (out.size() > SqliteNurLesen.MAX_BYTES) return null;
            }
            if (f.lastModified() != vorher || f.length() != laenge) return null;
            if (journal.exists() && journal.length() > 0) return null;
            return out.toByteArray();
        } catch (IOException | SecurityException e) {
            return null;
        }
    }

    /** Nur für Tests: Zwischenspeicher leeren. */
    static void vergessen() {
        synchronized (SPERRE) { zwischen = null; gelesenStand = Long.MIN_VALUE; gelesenUm = 0; }
    }
}
