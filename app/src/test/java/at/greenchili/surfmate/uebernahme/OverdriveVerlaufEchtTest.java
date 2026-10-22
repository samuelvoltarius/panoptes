package at.greenchili.surfmate.uebernahme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assume.assumeTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

/**
 * Nur auf Alfreds PC, ausdrücklich gestartet (Umgebungsvariable {@code OD_KOPIE} = Ordner der geschützten Kopie):
 * voller Import-Lauf gegen eine WEGWERF-Datenbank im Speicher mit dem Schema der Quelle. Prüft, dass alle Zeilen lesbar
 * sind (Typen, CLOBs). Gibt NUR Anzahlen aus, nie Werte. Arbeitskopie im selben geschützten Ordner, danach entfernt.
 * In CI/sonst: übersprungen.
 */
public class OverdriveVerlaufEchtTest {

    @Test
    public void echteKopie_allesLesbar_nurAnzahlen() throws Exception {
        String ordner = System.getenv("OD_KOPIE");
        assumeTrue(ordner != null && new File(ordner, OverdriveVerlauf.SOC).isFile());
        final File quelle = new File(ordner);
        final File arbeit = new File(quelle, "arbeit-probe");
        Class.forName("org.h2.Driver");
        final Connection soc = DriverManager.getConnection("jdbc:h2:mem:probe-soc-" + System.nanoTime(), "sa", "");
        final Connection trips = DriverManager.getConnection("jdbc:h2:mem:probe-trips-" + System.nanoTime(), "sa", "");
        schemaUebernehmen(new File(quelle, OverdriveVerlauf.SOC), soc, true);
        schemaUebernehmen(new File(quelle, OverdriveVerlauf.FAHRTEN), trips, false);
        OverdriveVerlauf v = new OverdriveVerlauf(new OverdriveVerlauf.Umgebung() {
            public File quellOrdner() { return quelle; }
            public File arbeitsOrdner() { return arbeit; }
            public long freiBytes(File o) { return quelle.getUsableSpace(); }
            public boolean steht() { return true; }
            public VerlaufKopierer.Ziel ladeZiel() { return VerlaufKopierer.jdbcZiel(soc); }
            public VerlaufKopierer.Ziel fahrtZiel() { return VerlaufKopierer.jdbcZiel(trips); }
            public int preiseNachOrtskreis(List<Long> l) { return 0; }
            public void ladeTageNeu(List<Long> l) { }
            public void fahrtSummenNeu(List<Long> l) { }
        });
        JSONObject r = v.laufSynchron();
        for (String k : new String[] {"ladungen", "socVerlauf", "socTage", "zuendung", "fahrten"}) {
            JSONObject x = r.optJSONObject(k);
            System.out.println("PROBE " + k + ": gesamt=" + (x == null ? "-" : x.optInt("gesamt")) + " neu=" + (x == null ? "-" : x.optInt("neu")));
        }
        System.out.println("PROBE ladekurven neu=" + r.optInt("ladekurven"));
        assertFalse(r.optBoolean("abgebrochen"));
        assertEquals(0, anzahl(soc, "SELECT COUNT(*) FROM CHARGING_SESSIONS WHERE ELECTRICITY_RATE <> -1"));
        String[] rest = arbeit.list();
        assertEquals("Arbeitskopie entfernt", 0, rest == null ? 0 : rest.length);
        //noinspection ResultOfMethodCallIgnored
        arbeit.delete();
    }

    /**
     * Koordination 22.10. (0.1.11): Bestand nachgebildet wie Alfreds Panoptes-Fahrten 16.–21.10. – bis zu 17 Overdrive-Fahrten
     * ab 16.10., wie Panoptes sie erfasst hätte (+25 s, Strecke +2 %), plus eine uralte Fremdzeile. Nur Anzahlen.
     */
    @Test
    public void echteKopie_mitBestand17_fachlicheDubletten() throws Exception {
        String ordner = System.getenv("OD_KOPIE");
        assumeTrue(ordner != null && new File(ordner, OverdriveVerlauf.FAHRTEN).isFile());
        final File quelle = new File(ordner);
        final File arbeit = new File(quelle, "arbeit-probe2");
        Class.forName("org.h2.Driver");
        final Connection soc = DriverManager.getConnection("jdbc:h2:mem:probe2-soc-" + System.nanoTime(), "sa", "");
        final Connection trips = DriverManager.getConnection("jdbc:h2:mem:probe2-trips-" + System.nanoTime(), "sa", "");
        schemaUebernehmen(new File(quelle, OverdriveVerlauf.SOC), soc, true);
        schemaUebernehmen(new File(quelle, OverdriveVerlauf.FAHRTEN), trips, false);
        long ab16 = java.time.ZonedDateTime.of(2026, 10, 16, 0, 0, 0, 0, java.time.ZoneId.of("Europe/Vienna")).toInstant().toEpochMilli();
        String basis = new File(quelle, OverdriveVerlauf.FAHRTEN).getAbsolutePath().replace(".mv.db", "");
        int bestand = 0;
        try (Connection q = DriverManager.getConnection("jdbc:h2:file:" + basis + ";ACCESS_MODE_DATA=r;FILE_LOCK=NO;TRACE_LEVEL_FILE=0;IFEXISTS=TRUE", "sa", "");
             Statement st = q.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM TRIPS WHERE START_TIME >= " + ab16 + " ORDER BY START_TIME LIMIT 17")) {
            java.sql.ResultSetMetaData md = rs.getMetaData();
            java.util.List<String> spalten = new java.util.ArrayList<>();
            for (int i = 1; i <= md.getColumnCount(); i++) if (!"ID".equals(md.getColumnLabel(i))) spalten.add(md.getColumnLabel(i));
            java.util.List<Object[]> zeilen = new java.util.ArrayList<>();
            while (rs.next()) {
                Object[] z = new Object[spalten.size()];
                for (int i = 0; i < spalten.size(); i++) {
                    String c = spalten.get(i);
                    Object w = VerlaufKopierer.lesbar(rs.getObject(c));
                    if ("START_TIME".equals(c) || "END_TIME".equals(c)) w = ((Number) w).longValue() + 25_000L;
                    if ("DISTANCE_KM".equals(c)) w = ((Number) w).doubleValue() * 1.02;
                    z[i] = w;
                }
                zeilen.add(z);
            }
            bestand = zeilen.size();
            if (!zeilen.isEmpty()) {   // Fremdzeile (Fehlerbild): eine uralte zusätzliche Zeile im Bestand
                Object[] alt = zeilen.get(0).clone();
                alt[spalten.indexOf("START_TIME")] = 0L;
                alt[spalten.indexOf("END_TIME")] = 1000L;
                zeilen.add(alt);
            }
            VerlaufKopierer.einfuegen(trips, "TRIPS", spalten, zeilen);
        }
        OverdriveVerlauf v = new OverdriveVerlauf(new OverdriveVerlauf.Umgebung() {
            public File quellOrdner() { return quelle; }
            public File arbeitsOrdner() { return arbeit; }
            public long freiBytes(File o) { return quelle.getUsableSpace(); }
            public boolean steht() { return true; }
            public VerlaufKopierer.Ziel ladeZiel() { return VerlaufKopierer.jdbcZiel(soc); }
            public VerlaufKopierer.Ziel fahrtZiel() { return VerlaufKopierer.jdbcZiel(trips); }
            public int preiseNachOrtskreis(List<Long> l) { return 0; }
            public void ladeTageNeu(List<Long> l) { }
            public void fahrtSummenNeu(List<Long> l) { }
        });
        JSONObject vor = v.vorschau().getJSONObject("ergebnis").getJSONObject("fahrten");
        System.out.println("PROBE2 Bestand nachgebildet: " + bestand + " (+1 Fremdzeile)");
        System.out.println("PROBE2 Vorschau Fahrten: gesamt=" + vor.optInt("gesamt") + " neu=" + vor.optInt("neu")
                + " dubletten=" + vor.optInt("dubletten") + " panoptesBestand=" + vor.optInt("panoptesBestand"));
        JSONObject r = v.laufSynchron();
        System.out.println("PROBE2 Import Fahrten neu=" + r.getJSONObject("fahrten").optInt("neu") + ", Ladungen neu="
                + r.getJSONObject("ladungen").optInt("neu") + ", Ladekurven neu=" + r.optInt("ladekurven")
                + ", SoC neu=" + r.getJSONObject("socVerlauf").optInt("neu") + ", SoC-Tage neu=" + r.getJSONObject("socTage").optInt("neu"));
        JSONObject r2 = v.laufSynchron();
        System.out.println("PROBE2 zweiter Lauf neu: Fahrten=" + r2.getJSONObject("fahrten").optInt("neu") + " Ladungen="
                + r2.getJSONObject("ladungen").optInt("neu"));
        assertEquals(vor.optInt("gesamt") - bestand, vor.optInt("neu"));
        assertEquals(bestand, vor.optInt("dubletten"));
        String[] rest = arbeit.list();
        assertEquals(0, rest == null ? 0 : rest.length);
        //noinspection ResultOfMethodCallIgnored
        arbeit.delete();
    }

    /**
     * Koordination 22.10. (0.1.11): Alfreds Bestand nachgebildet – 63 Ladungen wie aus dem Umzug (gleiche Startzeit/Energie),
     * heute nachbepreist (preis_quelle nutzer), plus 1 weitere; 12 Fahrten wie aus dem Umzug + 5 Fahrten vom 21.10., die
     * Overdrive nicht kennt. Der Import darf NICHTS am Bestand ändern (Preise, preis_quelle). Nur Anzahlen/Fingerabdruck.
     */
    @Test
    public void echteKopie_bestand64Nutzerpreise_17Fahrten_nieUeberschreiben() throws Exception {
        String ordner = System.getenv("OD_KOPIE");
        assumeTrue(ordner != null && new File(ordner, OverdriveVerlauf.SOC).isFile());
        final File quelle = new File(ordner);
        final File arbeit = new File(quelle, "arbeit-probe3");
        Class.forName("org.h2.Driver");
        final Connection soc = DriverManager.getConnection("jdbc:h2:mem:probe3-soc-" + System.nanoTime(), "sa", "");
        final Connection trips = DriverManager.getConnection("jdbc:h2:mem:probe3-trips-" + System.nanoTime(), "sa", "");
        schemaUebernehmen(new File(quelle, OverdriveVerlauf.SOC), soc, true);
        schemaUebernehmen(new File(quelle, OverdriveVerlauf.FAHRTEN), trips, false);
        int ladeBestand = bestandKopieren(new File(quelle, OverdriveVerlauf.SOC), soc, "CHARGING_SESSIONS", "1=1", 0, 1, true, 1000);
        long ab16 = java.time.ZonedDateTime.of(2026, 10, 16, 0, 0, 0, 0, java.time.ZoneId.of("Europe/Vienna")).toInstant().toEpochMilli();
        int fahrtUmzug = bestandKopieren(new File(quelle, OverdriveVerlauf.FAHRTEN), trips, "TRIPS", "START_TIME >= " + ab16, 0, 0, false, 1000);
        long am21 = java.time.ZonedDateTime.of(2026, 10, 21, 8, 0, 0, 0, java.time.ZoneId.of("Europe/Vienna")).toInstant().toEpochMilli();
        int neu21 = bestandKopieren(new File(quelle, OverdriveVerlauf.FAHRTEN), trips, "TRIPS", "START_TIME >= " + ab16, am21, 0, false, 5);
        String vorher = fingerabdruck(soc);
        long fahrtenVorher = anzahl(trips, "SELECT COUNT(*) FROM TRIPS");
        OverdriveVerlauf v = new OverdriveVerlauf(new OverdriveVerlauf.Umgebung() {
            public File quellOrdner() { return quelle; }
            public File arbeitsOrdner() { return arbeit; }
            public long freiBytes(File o) { return quelle.getUsableSpace(); }
            public boolean steht() { return true; }
            public VerlaufKopierer.Ziel ladeZiel() { return VerlaufKopierer.jdbcZiel(soc); }
            public VerlaufKopierer.Ziel fahrtZiel() { return VerlaufKopierer.jdbcZiel(trips); }
            public int preiseNachOrtskreis(List<Long> l) { return 0; }
            public void ladeTageNeu(List<Long> l) { }
            public void fahrtSummenNeu(List<Long> l) { }
        });
        JSONObject r = v.laufSynchron();
        JSONObject l = r.getJSONObject("ladungen"), f = r.getJSONObject("fahrten");
        System.out.println("PROBE3 Bestand: Ladungen=" + ladeBestand + " (nutzer-bepreist "
                + anzahl(soc, "SELECT COUNT(*) FROM CHARGING_SESSIONS WHERE PREIS_QUELLE='nutzer'") + "), Fahrten="
                + fahrtenVorher + " (wie Umzug " + fahrtUmzug + ", 21.10. " + neu21 + ")");
        System.out.println("PROBE3 Ladungen: gesamt=" + l.optInt("gesamt") + " neu=" + l.optInt("neu") + " dubletten=" + l.optInt("dubletten")
                + " → nachher " + anzahl(soc, "SELECT COUNT(*) FROM CHARGING_SESSIONS"));
        System.out.println("PROBE3 Fahrten: gesamt=" + f.optInt("gesamt") + " neu=" + f.optInt("neu") + " dubletten=" + f.optInt("dubletten")
                + " → nachher " + anzahl(trips, "SELECT COUNT(*) FROM TRIPS"));
        System.out.println("PROBE3 Ladekurven neu=" + r.optInt("ladekurven"));
        String nachher = fingerabdruck(soc);
        System.out.println("PROBE3 Preise/preis_quelle des Bestands unverändert: " + vorher.equals(nachher));
        assertEquals(0, l.optInt("neu"));
        assertEquals(vorher, nachher);
        assertEquals(fahrtenVorher + f.optInt("neu"), anzahl(trips, "SELECT COUNT(*) FROM TRIPS"));
        String[] rest = arbeit.list();
        assertEquals(0, rest == null ? 0 : rest.length);
        //noinspection ResultOfMethodCallIgnored
        arbeit.delete();
    }

    /**
     * Zeilen der Quelle (read-only) in den Bestand kopieren, höchstens {@code max}. {@code neuerStart > 0}: Startzeiten dorthin
     * (je +1 h); {@code extra}: zusätzliche Kopien der letzten Zeile (+30 Tage); {@code nutzerPreis}: wie heute nachbepreist.
     */
    private static int bestandKopieren(File mvdb, Connection ziel, String tabelle, String wo, long neuerStart, int extra,
                                       boolean nutzerPreis, int max) throws Exception {
        String basis = mvdb.getAbsolutePath().replace(".mv.db", "");
        try (Connection q = DriverManager.getConnection("jdbc:h2:file:" + basis + ";ACCESS_MODE_DATA=r;FILE_LOCK=NO;TRACE_LEVEL_FILE=0;IFEXISTS=TRUE", "sa", "");
             Statement st = q.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM " + tabelle + " WHERE " + wo + " ORDER BY START_TIME LIMIT " + max)) {
            java.sql.ResultSetMetaData md = rs.getMetaData();
            java.util.List<String> sp = new java.util.ArrayList<>();
            for (int i = 1; i <= md.getColumnCount(); i++) if (!"ID".equals(md.getColumnLabel(i))) sp.add(md.getColumnLabel(i));
            if (nutzerPreis) { sp.add("PREIS_QUELLE"); sp.add("NACHFRAGE"); }
            java.util.List<Object[]> zeilen = new java.util.ArrayList<>();
            int n = 0;
            while (rs.next()) {
                long start = rs.getLong("START_TIME");
                Object[] z = new Object[sp.size()];
                for (int i = 0; i < sp.size(); i++) {
                    String c = sp.get(i);
                    if ("PREIS_QUELLE".equals(c)) { z[i] = "nutzer"; continue; }
                    if ("NACHFRAGE".equals(c)) { z[i] = "erledigt"; continue; }
                    Object w = VerlaufKopierer.lesbar(rs.getObject(c));
                    if (neuerStart > 0 && ("START_TIME".equals(c) || "END_TIME".equals(c))) {
                        w = ((Number) w).longValue() - start + neuerStart + n * 3_600_000L;
                    }
                    if (nutzerPreis && "ELECTRICITY_RATE".equals(c)) w = (n % 3 == 0) ? 0.0 : (n % 3 == 1 ? 0.29 : 0.59);
                    if (nutzerPreis && "TARIFF_LABEL".equals(c)) w = n % 3 == 0 ? "zuhause" : (n % 3 == 1 ? "Lidl Plus" : "SMATRICS");
                    z[i] = w;
                }
                zeilen.add(z);
                n++;
            }
            for (int k = 0; k < extra && !zeilen.isEmpty(); k++) {
                Object[] z = zeilen.get(zeilen.size() - 1).clone();
                int si = sp.indexOf("START_TIME"), ei = sp.indexOf("END_TIME");
                z[si] = ((Number) z[si]).longValue() + 30L * 86_400_000L;
                z[ei] = ((Number) z[ei]).longValue() + 30L * 86_400_000L;
                zeilen.add(z);
            }
            VerlaufKopierer.einfuegen(ziel, tabelle, sp, zeilen);
            if (nutzerPreis) {
                try (Statement u = ziel.createStatement()) {
                    u.execute("UPDATE CHARGING_SESSIONS SET SESSION_COST = ELECTRICITY_RATE * ENERGY_ADDED_KWH");
                }
            }
            return zeilen.size();
        }
    }

    private static String fingerabdruck(Connection soc) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (Statement st = soc.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT START_TIME, ELECTRICITY_RATE, SESSION_COST, PREIS_QUELLE, TARIFF_LABEL, NACHFRAGE FROM CHARGING_SESSIONS"
                        + " WHERE PREIS_QUELLE = 'nutzer' ORDER BY START_TIME")) {
            while (rs.next()) for (int i = 1; i <= 6; i++) sb.append(rs.getString(i)).append('|');
        }
        return Integer.toHexString(sb.toString().hashCode()) + ":" + sb.length();
    }

    /** DDL der Quelle (read-only, SCRIPT NODATA) in die Wegwerf-DB; Panoptes-Zusatzspalten ergänzen. */
    private static void schemaUebernehmen(File mvdb, Connection ziel, boolean lade) throws Exception {
        String basis = mvdb.getAbsolutePath().substring(0, mvdb.getAbsolutePath().length() - ".mv.db".length());
        try (Connection q = DriverManager.getConnection("jdbc:h2:file:" + basis
                + ";ACCESS_MODE_DATA=r;FILE_LOCK=NO;TRACE_LEVEL_FILE=0;IFEXISTS=TRUE", "sa", "");
             Statement st = q.createStatement(); ResultSet rs = st.executeQuery("SCRIPT NODATA");
             Statement z = ziel.createStatement()) {
            while (rs.next()) {
                String sql = rs.getString(1);
                if (sql.startsWith("CREATE CACHED TABLE") || sql.startsWith("CREATE MEMORY TABLE")) z.execute(sql);
            }
            if (lade) {
                z.execute("ALTER TABLE CHARGING_SESSIONS ADD COLUMN IF NOT EXISTS PREIS_QUELLE VARCHAR(16) DEFAULT ''");
                z.execute("ALTER TABLE CHARGING_SESSIONS ADD COLUMN IF NOT EXISTS NACHFRAGE VARCHAR(12) DEFAULT ''");
            }
        }
    }

    private static long anzahl(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
