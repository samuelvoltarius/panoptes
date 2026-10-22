package at.greenchili.surfmate.uebernahme;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Kopiert Zeilen einer Tabelle aus einer (nur lesend geöffneten) Overdrive-Datenbank in die gleichnamige Panoptes-Tabelle
 * (Kern 5, Overdrive Stufe 2). Gemeinsamer Ursprung → gleiches Schema; übernommen werden nur Spalten, die es auf
 * BEIDEN Seiten gibt, abzüglich ausgelassener (z. B. ID) und mit festen Werten überschriebener (z. B. Preis = fehlt).
 *
 * <p>Idempotent: Dubletten über eine Schlüsselspalte (z. B. {@code start_time}) – ein zweiter Lauf fügt nichts mehr ein.
 * Abbrechbar zwischen Stapeln. Die Ziel-Schreibvorgänge laufen stapelweise über {@link Ziel}, damit die Panoptes-DB nie
 * lange gesperrt ist. Gibt nie Zeilenwerte aus.
 */
public final class VerlaufKopierer {

    private VerlaufKopierer() {}

    static final int STAPEL = 200;

    /** Panoptes-Seite. Laufzeit: über SocHistoryDatabase/TripDatabase (synchronisiert, je Stapel eine Transaktion). */
    public interface Ziel {
        /** Spaltennamen (groß) der Zieltabelle. */
        Set<String> spalten(String tabelle) throws Exception;
        /** Vorhandene Schlüsselwerte (für Dubletten). */
        Set<Long> schluessel(String tabelle, String spalte) throws Exception;
        /** Vorhandene Schlüssel mit einem Vergleichswert (z. B. Strecke, Energie); Wert null = unbekannt. */
        java.util.TreeMap<Long, List<Double>> schluesselMitWert(String tabelle, String spalte, String wert) throws Exception;
        /** Fügt die Zeilen in einer Transaktion ein; Rückgabe = Anzahl eingefügt. */
        int einfuegen(String tabelle, List<String> spalten, List<Object[]> zeilen) throws Exception;
    }

    /**
     * Fachliche Dublette (Koordination 22.10., 0.1.11): gleicher Beginn ±{@code fensterMs} UND ähnlicher Vergleichswert
     * (|a−b| ≤ max(abs, rel·max(|a|,|b|))); fehlt der Wert auf einer Seite, genügt die Zeit.
     */
    public static final class Toleranz {
        final long fensterMs;
        final String wertSpalte;
        final double rel, abs;
        public Toleranz(long fensterMs, String wertSpalte, double rel, double abs) {
            this.fensterMs = fensterMs; this.wertSpalte = wertSpalte == null ? null : wertSpalte.toUpperCase(Locale.ROOT);
            this.rel = rel; this.abs = abs;
        }

        boolean aehnlich(Double a, Double b) {
            if (a == null || b == null || a.isNaN() || b.isNaN()) return true;
            return Math.abs(a - b) <= Math.max(abs, rel * Math.max(Math.abs(a), Math.abs(b)));
        }

        boolean dublette(java.util.TreeMap<Long, List<Double>> bestand, long k, Double wert) {
            for (List<Double> l : bestand.subMap(k - fensterMs, true, k + fensterMs, true).values()) {
                for (Double w : l) if (aehnlich(wert, w)) return true;
            }
            return false;
        }
    }

    public static final class Ergebnis {
        public int gesamt, neu, dubletten, gefiltert, bestand;
        public long bestandVon, bestandBis;
        public long kleinster = Long.MAX_VALUE, groesster = Long.MIN_VALUE;
        public final List<Long> neueSchluessel = new ArrayList<>();
        public boolean abgebrochen;
    }

    /**
     * @param schluessel Spalte für Dubletten und Zeitraum (null = keine Dublettenprüfung)
     * @param aus        ausgelassene Spalten (groß)
     * @param setzen     feste Werte für Spalten (groß) – überschreiben den Overdrive-Wert
     * @param filter     nur Zeilen, für die das gilt (null = alle); bekommt Spaltenname (groß) → Wert
     * @param nurZaehlen Vorschau: nichts schreiben
     */
    public static Ergebnis kopiere(Connection quelle, String tabelle, Ziel ziel, String schluessel, Set<String> aus,
                                   Map<String, Object> setzen, Predicate<Map<String, Object>> filter,
                                   BooleanSupplier abbruch, boolean nurZaehlen) throws Exception {
        return kopiere(quelle, tabelle, ziel, schluessel, aus, setzen, filter, abbruch, nurZaehlen, null);
    }

    /** Wie oben; mit {@code tol} fachliche Dubletten (±Zeit und ähnlicher Wert) statt gleichem Schlüssel. */
    public static Ergebnis kopiere(Connection quelle, String tabelle, Ziel ziel, String schluessel, Set<String> aus,
                                   Map<String, Object> setzen, Predicate<Map<String, Object>> filter,
                                   BooleanSupplier abbruch, boolean nurZaehlen, Toleranz tol) throws Exception {
        Ergebnis e = new Ergebnis();
        if (!tabelleDa(quelle, tabelle)) return e;
        Set<String> zielSpalten = ziel.spalten(tabelle);
        if (zielSpalten.isEmpty()) return e;
        String key = schluessel == null ? null : schluessel.toUpperCase(Locale.ROOT);
        java.util.TreeMap<Long, List<Double>> bestand = new java.util.TreeMap<>();
        if (key != null) {
            if (tol != null && tol.wertSpalte != null) {
                bestand = ziel.schluesselMitWert(tabelle, key, tol.wertSpalte);
            } else {
                for (Long k : ziel.schluessel(tabelle, key)) bestand.computeIfAbsent(k, x -> new ArrayList<>()).add(null);
            }
        }
        e.bestand = 0;
        for (List<Double> l : bestand.values()) e.bestand += l.size();
        if (!bestand.isEmpty()) { e.bestandVon = bestand.firstKey(); e.bestandBis = bestand.lastKey(); }
        final java.util.TreeMap<Long, List<Double>> best = bestand;
        Set<Long> gesehen = new HashSet<>();
        String sql = "SELECT * FROM \"" + tabelle.toUpperCase(Locale.ROOT) + "\"" + (key == null ? "" : " ORDER BY \"" + key + "\"");
        try (Statement st = quelle.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            List<String> quellSpalten = new ArrayList<>();
            for (int i = 1; i <= md.getColumnCount(); i++) quellSpalten.add(md.getColumnLabel(i).toUpperCase(Locale.ROOT));
            List<String> spalten = new ArrayList<>();
            for (String s : quellSpalten) if (zielSpalten.contains(s) && !aus.contains(s)) spalten.add(s);
            for (String s : setzen.keySet()) if (zielSpalten.contains(s) && !spalten.contains(s)) spalten.add(s);
            List<Object[]> stapel = new ArrayList<>();
            List<Long> stapelSchluessel = new ArrayList<>();
            while (rs.next()) {
                if (abbruch != null && abbruch.getAsBoolean()) { e.abgebrochen = true; break; }
                e.gesamt++;
                Map<String, Object> zeile = new LinkedHashMap<>();
                for (int i = 0; i < quellSpalten.size(); i++) zeile.put(quellSpalten.get(i), lesbar(rs.getObject(i + 1)));
                if (filter != null && !filter.test(zeile)) { e.gefiltert++; continue; }
                Long k = null;
                if (key != null) {
                    Object kv = zeile.get(key);
                    if (!(kv instanceof Number)) { e.gefiltert++; continue; }
                    k = ((Number) kv).longValue();
                    boolean dublette;
                    if (tol != null) {
                        Object wv = tol.wertSpalte == null ? null : zeile.get(tol.wertSpalte);
                        dublette = tol.dublette(best, k, wv instanceof Number ? ((Number) wv).doubleValue() : null);
                    } else {
                        dublette = best.containsKey(k);
                    }
                    if (dublette || !gesehen.add(k)) { e.dubletten++; continue; }
                    e.kleinster = Math.min(e.kleinster, k);
                    e.groesster = Math.max(e.groesster, k);
                }
                Object[] werte = new Object[spalten.size()];
                for (int i = 0; i < spalten.size(); i++) {
                    String s = spalten.get(i);
                    werte[i] = setzen.containsKey(s) ? setzen.get(s) : zeile.get(s);
                }
                e.neu++;
                if (nurZaehlen) continue;
                stapel.add(werte);
                if (k != null) stapelSchluessel.add(k);
                if (stapel.size() >= STAPEL) {
                    ziel.einfuegen(tabelle, spalten, stapel);
                    e.neueSchluessel.addAll(stapelSchluessel);
                    stapel.clear();
                    stapelSchluessel.clear();
                }
            }
            if (!nurZaehlen && !stapel.isEmpty()) {
                ziel.einfuegen(tabelle, spalten, stapel);
                e.neueSchluessel.addAll(stapelSchluessel);
            }
        }
        if (e.kleinster == Long.MAX_VALUE) { e.kleinster = 0; e.groesster = 0; }
        return e;
    }

    /** CLOB/BLOB in Text bzw. Bytes, damit sie die Verbindung überleben. */
    static Object lesbar(Object o) throws Exception {
        if (o instanceof java.sql.Clob) {
            java.sql.Clob c = (java.sql.Clob) o;
            return c.getSubString(1, (int) Math.min(Integer.MAX_VALUE, c.length()));
        }
        if (o instanceof java.sql.Blob) {
            java.sql.Blob b = (java.sql.Blob) o;
            return b.getBytes(1, (int) Math.min(Integer.MAX_VALUE, b.length()));
        }
        return o;
    }

    static boolean tabelleDa(Connection c, String tabelle) throws Exception {
        DatabaseMetaData md = c.getMetaData();
        try (ResultSet rs = md.getTables(null, null, tabelle.toUpperCase(Locale.ROOT), new String[] {"TABLE"})) {
            return rs.next();
        }
    }

    // ---------------------------------------------------------------- JDBC-Ziel (Laufzeit-Bausteine und Tests)

    /** Spalten einer Tabelle über JDBC-Metadaten (groß). */
    public static Set<String> spalten(Connection c, String tabelle) throws Exception {
        Set<String> s = new LinkedHashSet<>();
        try (ResultSet rs = c.getMetaData().getColumns(null, null, tabelle.toUpperCase(Locale.ROOT), "%")) {
            while (rs.next()) s.add(rs.getString("COLUMN_NAME").toUpperCase(Locale.ROOT));
        }
        return s;
    }

    public static java.util.TreeMap<Long, List<Double>> schluesselMitWert(Connection c, String tabelle, String spalte, String wert)
            throws Exception {
        java.util.TreeMap<Long, List<Double>> m = new java.util.TreeMap<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT \"" + spalte + "\", \"" + wert + "\" FROM \"" + tabelle.toUpperCase(Locale.ROOT) + "\"")) {
            while (rs.next()) {
                long k = rs.getLong(1);
                if (rs.wasNull()) continue;
                double w = rs.getDouble(2);
                m.computeIfAbsent(k, x -> new ArrayList<>()).add(rs.wasNull() ? null : w);
            }
        }
        return m;
    }

    public static Set<Long> schluessel(Connection c, String tabelle, String spalte) throws Exception {
        Set<Long> s = new HashSet<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT \"" + spalte + "\" FROM \"" + tabelle.toUpperCase(Locale.ROOT) + "\"")) {
            while (rs.next()) {
                long v = rs.getLong(1);
                if (!rs.wasNull()) s.add(v);
            }
        }
        return s;
    }

    /** INSERT der Zeilen (ohne eigene Transaktionssteuerung – der Aufrufer klammert). */
    public static int einfuegen(Connection c, String tabelle, List<String> spalten, List<Object[]> zeilen) throws Exception {
        StringBuilder sql = new StringBuilder("INSERT INTO \"").append(tabelle.toUpperCase(Locale.ROOT)).append("\" (");
        for (int i = 0; i < spalten.size(); i++) sql.append(i == 0 ? "" : ",").append('"').append(spalten.get(i)).append('"');
        sql.append(") VALUES (");
        for (int i = 0; i < spalten.size(); i++) sql.append(i == 0 ? "?" : ",?");
        sql.append(")");
        int n = 0;
        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (Object[] z : zeilen) {
                for (int i = 0; i < z.length; i++) ps.setObject(i + 1, z[i]);
                ps.addBatch();
            }
            for (int r : ps.executeBatch()) n += Math.max(0, r);
        }
        return n;
    }

    /** Einfaches Ziel über eine Verbindung, je Stapel eine Transaktion (Tests; die Laufzeit synchronisiert zusätzlich). */
    public static Ziel jdbcZiel(final Connection c) {
        return new Ziel() {
            public Set<String> spalten(String t) throws Exception { return VerlaufKopierer.spalten(c, t); }
            public Set<Long> schluessel(String t, String s) throws Exception { return VerlaufKopierer.schluessel(c, t, s); }
            public java.util.TreeMap<Long, List<Double>> schluesselMitWert(String t, String s, String w) throws Exception {
                return VerlaufKopierer.schluesselMitWert(c, t, s, w);
            }
            public int einfuegen(String t, List<String> s, List<Object[]> z) throws Exception {
                boolean auto = c.getAutoCommit();
                c.setAutoCommit(false);
                try {
                    int n = VerlaufKopierer.einfuegen(c, t, s, z);
                    c.commit();
                    return n;
                } catch (Exception ex) {
                    c.rollback();
                    throw ex;
                } finally {
                    c.setAutoCommit(auto);
                }
            }
        };
    }
}
