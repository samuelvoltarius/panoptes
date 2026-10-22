package at.greenchili.surfmate.uebernahme;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Overdrive Stufe 2: nur lesen, Arbeitskopie entfernt, idempotent, Panoptes-Vorrang, kein Overdrive-Preis. Synthetisch. */
public class OverdriveVerlaufTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    static final long T0 = 1_780_000_000_000L;

    /** Overdrive-ähnliche Quelldateien (H2-Datei, gleiche Tabellennamen, synthetische Werte). */
    private File quellen() throws Exception {
        File q = tmp.newFolder("tmp");
        Class.forName("org.h2.Driver");
        try (Connection c = DriverManager.getConnection("jdbc:h2:file:" + new File(q, "overdrive_soc_h2").getAbsolutePath(), "sa", "");
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE CHARGING_SESSIONS (ID BIGINT AUTO_INCREMENT PRIMARY KEY, START_TIME BIGINT, END_TIME BIGINT,"
                    + " ENERGY_ADDED_KWH REAL, ELECTRICITY_RATE REAL, SESSION_COST REAL, TARIFF_ID VARCHAR, TARIFF_LABEL VARCHAR,"
                    + " IS_DC INTEGER, START_LAT DOUBLE PRECISION, START_LNG DOUBLE PRECISION, NUR_OVERDRIVE INTEGER)");
            for (int i = 0; i < 5; i++) {
                st.execute("INSERT INTO CHARGING_SESSIONS (START_TIME, END_TIME, ENERGY_ADDED_KWH, ELECTRICITY_RATE, SESSION_COST,"
                        + " TARIFF_ID, TARIFF_LABEL, IS_DC, START_LAT, START_LNG, NUR_OVERDRIVE) VALUES ("
                        + (T0 + i * 86_400_000L) + "," + (T0 + i * 86_400_000L + 3_600_000L) + ", 20, 0.01, 0.2, '', '', 1, 47.0, 13.0, 7)");
            }
            st.execute("CREATE TABLE CHARGING_POWER_SAMPLES (ID BIGINT AUTO_INCREMENT, SESSION_START_TIME BIGINT, T BIGINT, POWER_KW REAL)");
            for (int i = 0; i < 5; i++) {
                st.execute("INSERT INTO CHARGING_POWER_SAMPLES (SESSION_START_TIME, T, POWER_KW) VALUES (" + (T0 + i * 86_400_000L) + ", 1, 50)");
            }
            st.execute("CREATE TABLE SOC_HISTORY (ID BIGINT AUTO_INCREMENT, TIMESTAMP BIGINT, SOC_PERCENT REAL)");
            for (int i = 0; i < 10; i++) st.execute("INSERT INTO SOC_HISTORY (TIMESTAMP, SOC_PERCENT) VALUES (" + (T0 + i * 3_600_000L) + ", 50)");
        }
        try (Connection c = DriverManager.getConnection("jdbc:h2:file:" + new File(q, "overdrive_trips_h2").getAbsolutePath(), "sa", "");
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE TRIPS (ID BIGINT AUTO_INCREMENT, START_TIME BIGINT, END_TIME BIGINT, DISTANCE_KM REAL,"
                    + " TRIP_COST REAL, ELECTRICITY_RATE REAL, RATE_SOURCE VARCHAR, TELEMETRY_FILE_PATH VARCHAR, ROUTE_ID BIGINT)");
            for (int i = 0; i < 4; i++) {
                st.execute("INSERT INTO TRIPS (START_TIME, END_TIME, DISTANCE_KM, TRIP_COST, ELECTRICITY_RATE, RATE_SOURCE, TELEMETRY_FILE_PATH, ROUTE_ID)"
                        + " VALUES (" + (T0 + i * 7_200_000L) + "," + (T0 + i * 7_200_000L + 1_800_000L) + ", 12, 0.02, 0.01, 'config', '/data/local/tmp/x', 3)");
            }
        }
        return q;
    }

    /** Panoptes-ähnliches Ziel (Spalten wie in Panoptes, inkl. preis_quelle/nachfrage). */
    private static Connection[] ziele(long panoptesErsteLadung) throws Exception {
        Connection soc = DriverManager.getConnection("jdbc:h2:mem:ziel-soc-" + System.nanoTime(), "sa", "");
        Connection trips = DriverManager.getConnection("jdbc:h2:mem:ziel-trips-" + System.nanoTime(), "sa", "");
        try (Statement st = soc.createStatement()) {
            st.execute("CREATE TABLE charging_sessions (id BIGINT AUTO_INCREMENT PRIMARY KEY, start_time BIGINT UNIQUE, end_time BIGINT,"
                    + " energy_added_kwh REAL, electricity_rate REAL DEFAULT -1, session_cost REAL DEFAULT -1, tariff_id VARCHAR DEFAULT '',"
                    + " tariff_label VARCHAR DEFAULT '', is_dc INTEGER, start_lat DOUBLE PRECISION, start_lng DOUBLE PRECISION,"
                    + " preis_quelle VARCHAR(16) DEFAULT '', nachfrage VARCHAR(12) DEFAULT '', post_commit_tariff_applied INTEGER DEFAULT 0)");
            st.execute("CREATE TABLE charging_power_samples (id BIGINT AUTO_INCREMENT, session_start_time BIGINT, t BIGINT, power_kw REAL)");
            st.execute("CREATE TABLE soc_history (id BIGINT AUTO_INCREMENT, timestamp BIGINT, soc_percent REAL)");
            if (panoptesErsteLadung > 0) {
                st.execute("INSERT INTO charging_sessions (start_time, end_time, energy_added_kwh, electricity_rate, preis_quelle) VALUES ("
                        + panoptesErsteLadung + "," + (panoptesErsteLadung + 1000) + ", 10, 0.29, 'tarif')");
            }
        }
        try (Statement st = trips.createStatement()) {
            st.execute("CREATE TABLE trips (id BIGINT AUTO_INCREMENT, start_time BIGINT, end_time BIGINT, distance_km REAL,"
                    + " trip_cost REAL, electricity_rate REAL, rate_source VARCHAR, telemetry_file_path VARCHAR, route_id BIGINT,"
                    + " size_bytes BIGINT DEFAULT 0, rate_label VARCHAR DEFAULT '', electric_cost REAL DEFAULT 0)");
        }
        return new Connection[] {soc, trips};
    }

    static class Umg implements OverdriveVerlauf.Umgebung {
        File quelle, arbeit;
        Connection soc, trips;
        boolean steht = true;
        long frei = Long.MAX_VALUE;
        final List<Long> bepreist = new ArrayList<>(), tage = new ArrayList<>(), summen = new ArrayList<>();
        public File quellOrdner() { return quelle; }
        public File arbeitsOrdner() { return arbeit; }
        public long freiBytes(File o) { return frei; }
        public boolean steht() { return steht; }
        public VerlaufKopierer.Ziel ladeZiel() { return VerlaufKopierer.jdbcZiel(soc); }
        public VerlaufKopierer.Ziel fahrtZiel() { return VerlaufKopierer.jdbcZiel(trips); }
        public int preiseNachOrtskreis(List<Long> l) { bepreist.addAll(l); return 0; }
        public void ladeTageNeu(List<Long> l) { tage.addAll(l); }
        public void fahrtSummenNeu(List<Long> l) { summen.addAll(l); }
    }

    private Umg umg(long panoptesErste) throws Exception {
        Umg u = new Umg();
        u.quelle = quellen();
        u.arbeit = tmp.newFolder("arbeit");
        Connection[] z = ziele(panoptesErste);
        u.soc = z[0];
        u.trips = z[1];
        return u;
    }

    private static long anzahl(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static byte[] sha(File f) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f.toPath()));
    }

    @Test
    public void import_ohneOverdrivePreis_idempotent_originalUnberuehrt_kopieWeg() throws Exception {
        Umg u = umg(0);
        File orig = new File(u.quelle, OverdriveVerlauf.SOC);
        byte[] vorher = sha(orig);
        OverdriveVerlauf v = new OverdriveVerlauf(u);
        JSONObject r = v.laufSynchron();
        assertEquals(5, r.getJSONObject("ladungen").getInt("neu"));
        assertEquals(5, r.getInt("ladekurven"));
        assertEquals(10, r.getJSONObject("socVerlauf").getInt("neu"));
        assertEquals(4, r.getJSONObject("fahrten").getInt("neu"));
        assertEquals(5, anzahl(u.soc, "SELECT COUNT(*) FROM charging_sessions WHERE electricity_rate = -1 AND session_cost = -1 AND preis_quelle = 'fehlt'"));
        assertEquals(0, anzahl(u.soc, "SELECT COUNT(*) FROM charging_sessions WHERE electricity_rate = 0.01"));
        assertEquals(4, anzahl(u.trips, "SELECT COUNT(*) FROM trips WHERE telemetry_file_path = '"
                + at.greenchili.surfmate.trips.TripDatabase.IMPORTED_PATH + "' AND route_id IS NULL AND rate_source = 'fehlt' AND trip_cost = 0"));
        assertEquals(5, u.bepreist.size());
        assertEquals(5, u.tage.size());
        assertEquals(4, u.summen.size());
        assertArrayEquals("Original nie verändert", vorher, sha(orig));
        assertEquals("Arbeitskopie entfernt", 0, u.arbeit.listFiles().length);
        assertEquals("fertig", v.stand().getString("phase"));
        // zweiter Lauf: keine Dubletten
        JSONObject r2 = v.laufSynchron();
        assertEquals(0, r2.getJSONObject("ladungen").getInt("neu"));
        assertEquals(5, r2.getJSONObject("ladungen").getInt("dubletten"));
        assertEquals(0, r2.getJSONObject("fahrten").getInt("neu"));
        assertEquals(0, r2.getInt("ladekurven"));
        assertEquals(5, anzahl(u.soc, "SELECT COUNT(*) FROM charging_sessions"));
    }

    /**
     * 0.1.11 (Befund am Auto: „416 Panoptes-Vorrang“ bei nur 17 Panoptes-Fahrten): Eine einzelne frühe Panoptes-Zeile darf
     * den Verlauf nicht mehr blockieren. Dublette = Beginn ±60 s UND ähnliche Energie bzw. Strecke.
     */
    @Test
    public void fachlicheDubletten_frueheZeileBlockiertNichts_vorschauSchreibtNichts() throws Exception {
        Umg u = umg(0);
        try (Statement st = u.soc.createStatement()) {
            // dieselbe dritte Ladung, von Panoptes 30 s später und mit 20,5 statt 20 kWh erfasst → Dublette
            st.execute("INSERT INTO charging_sessions (start_time, end_time, energy_added_kwh, preis_quelle) VALUES ("
                    + (T0 + 2 * 86_400_000L + 30_000L) + "," + (T0 + 2 * 86_400_000L + 3_000_000L) + ", 20.5, 'tarif')");
            // frühe, fremde Panoptes-Zeile (z. B. aus dem Umzug) – darf nichts verwerfen
            st.execute("INSERT INTO charging_sessions (start_time, end_time, energy_added_kwh, preis_quelle) VALUES ("
                    + (T0 - 100L * 86_400_000L) + "," + (T0 - 100L * 86_400_000L + 1000) + ", 5, 'tarif')");
            // gleiche Startzeit wie Ladung 4, aber ganz andere Energie → keine Dublette
            st.execute("INSERT INTO charging_sessions (start_time, end_time, energy_added_kwh, preis_quelle) VALUES ("
                    + (T0 + 3 * 86_400_000L + 10_000L) + "," + (T0 + 3 * 86_400_000L + 20_000L) + ", 2, 'tarif')");
        }
        try (Statement st = u.trips.createStatement()) {
            st.execute("INSERT INTO trips (start_time, end_time, distance_km) VALUES (" + (T0 + 7_200_000L - 20_000L) + ", "
                    + (T0 + 7_200_000L + 1_000_000L) + ", 12.2)");   // Fahrt 2: −20 s, 12,2 statt 12 km → Dublette
            st.execute("INSERT INTO trips (start_time, end_time, distance_km) VALUES (1000, 2000, 3)");   // uralt, fremd
        }
        OverdriveVerlauf v = new OverdriveVerlauf(u);
        JSONObject p = v.vorschau().getJSONObject("ergebnis");
        assertEquals(4, p.getJSONObject("ladungen").getInt("neu"));
        assertEquals(1, p.getJSONObject("ladungen").getInt("dubletten"));
        assertEquals(3, p.getJSONObject("ladungen").getInt("panoptesBestand"));
        assertEquals(3, p.getJSONObject("fahrten").getInt("neu"));
        assertEquals(1, p.getJSONObject("fahrten").getInt("dubletten"));
        assertEquals(3, anzahl(u.soc, "SELECT COUNT(*) FROM charging_sessions"));   // Vorschau schreibt nichts
        assertEquals(0, u.arbeit.listFiles().length);
        assertFalse("keine Positionen in der Vorschau", v.vorschau().toString().contains("47.0"));
        v.laufSynchron();
        assertEquals(7, anzahl(u.soc, "SELECT COUNT(*) FROM charging_sessions"));
        assertEquals(5, anzahl(u.trips, "SELECT COUNT(*) FROM trips"));
        JSONObject zweiter = v.laufSynchron();   // idempotent
        assertEquals(0, zweiter.getJSONObject("ladungen").getInt("neu"));
        assertEquals(0, zweiter.getJSONObject("fahrten").getInt("neu"));
    }

    @Test
    public void startbedingungen() throws Exception {
        Umg u = umg(0);
        OverdriveVerlauf v = new OverdriveVerlauf(u);
        u.steht = false;
        assertEquals("nur_im_stand", v.starten().getString("grund"));
        u.steht = true;
        u.frei = 10;
        JSONObject f = v.starten();
        assertEquals("zu_wenig_speicher", f.getString("grund"));
        assertFalse(v.vorschau().getBoolean("speicherOk"));
        u.quelle = tmp.newFolder("leer");
        assertFalse(v.vorschau().getBoolean("vorhanden"));
        assertEquals("keine_daten", v.starten().getString("grund"));
    }

    @Test
    public void abbruch_raeumtAuf() throws Exception {
        Umg u = umg(0);
        OverdriveVerlauf v = new OverdriveVerlauf(u);
        v.abbrechen();
        // abbrechen() vor laufSynchron wird dort zurückgesetzt – daher Abbruch über einen Ziel-Wrapper simulieren
        final OverdriveVerlauf[] ref = {v};
        Umg w = new Umg() {
            @Override public VerlaufKopierer.Ziel ladeZiel() {
                VerlaufKopierer.Ziel z = VerlaufKopierer.jdbcZiel(soc);
                return new VerlaufKopierer.Ziel() {
                    public java.util.Set<String> spalten(String t) throws Exception { ref[0].abbrechen(); return z.spalten(t); }
                    public java.util.Set<Long> schluessel(String t, String s) throws Exception { return z.schluessel(t, s); }
                    public java.util.TreeMap<Long, List<Double>> schluesselMitWert(String t, String s, String wv) throws Exception {
                        return z.schluesselMitWert(t, s, wv);
                    }
                    public int einfuegen(String t, List<String> s, List<Object[]> zl) throws Exception { return z.einfuegen(t, s, zl); }
                };
            }
        };
        w.quelle = u.quelle; w.arbeit = u.arbeit; w.soc = u.soc; w.trips = u.trips;
        OverdriveVerlauf v2 = new OverdriveVerlauf(w);
        ref[0] = v2;
        JSONObject r = v2.laufSynchron();
        assertTrue(r.getBoolean("abgebrochen"));
        assertEquals("abgebrochen", v2.stand().getString("phase"));
        assertEquals(0, w.arbeit.listFiles().length);
        // Fortsetzen: alle übrigen Zeilen kommen, keine Dubletten (Grenze aus dem ersten Lauf)
        JSONObject weiter = new OverdriveVerlauf(u).laufSynchron();
        assertFalse(weiter.getBoolean("abgebrochen"));
        assertEquals(5, anzahl(u.soc, "SELECT COUNT(*) FROM charging_sessions"));
        assertEquals(4, anzahl(u.trips, "SELECT COUNT(*) FROM trips"));
    }
}
