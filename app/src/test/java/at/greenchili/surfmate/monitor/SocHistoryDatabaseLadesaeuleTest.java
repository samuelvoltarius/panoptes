package at.greenchili.surfmate.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.logging.DaemonLogger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Ladesäulen-Spalten (03.11.2026) gegen eine echte H2-Datenbank: alte Tabelle ohne die Spalten wird gelesen, ohne
 * Fehler; die Migration (auch die echte {@code createTables}) legt sie an, Altdaten und {@code place_label} bleiben;
 * Schreiben ändert nur die Säulen-Spalten. Testort Linz, synthetische Werte.
 */
public class SocHistoryDatabaseLadesaeuleTest {

    @BeforeClass
    public static void disableAndroidLogging() {
        DaemonLogger.configure(DaemonLogger.Config.defaults()
                .withConsoleLog(false)
                .withFileLog(false)
                .withStdoutLog(false));
    }

    private static final long TAG = 20_100L * 86_400_000L;
    private static final double LAT = 48.3059, LNG = 14.2862;

    /** Tabelle im Stand vor den Säulen-Spalten (wie eine Installation 0.1.35). */
    private static void alteTabelle(Statement st) throws Exception {
        st.execute("CREATE TABLE charging_sessions (id BIGINT AUTO_INCREMENT PRIMARY KEY, start_time BIGINT NOT NULL,"
                + " end_time BIGINT, start_soc REAL NOT NULL, end_soc REAL, energy_added_kwh REAL, peak_power_kw REAL,"
                + " avg_power_kw REAL DEFAULT -1, range_gained_km INTEGER DEFAULT -1, gun_state INTEGER DEFAULT -1,"
                + " is_dc INTEGER DEFAULT -1, electricity_rate REAL DEFAULT -1, currency VARCHAR(8) DEFAULT '',"
                + " session_cost REAL DEFAULT -1, time_to_full_min INTEGER DEFAULT -1, hv_temp_high REAL DEFAULT -999,"
                + " hv_temp_low REAL DEFAULT -999, hv_temp_avg REAL DEFAULT -999, start_lat DOUBLE DEFAULT 0,"
                + " start_lng DOUBLE DEFAULT 0, place_label VARCHAR(96) DEFAULT '', start_odometer_km INTEGER DEFAULT -1,"
                + " tariff_id VARCHAR(16) DEFAULT '', tariff_label VARCHAR(64) DEFAULT '', energy_source VARCHAR(32) DEFAULT '',"
                + " energy_soc_kwh DOUBLE DEFAULT NULL, energy_incomplete INTEGER DEFAULT 0, counter_energy_kwh DOUBLE DEFAULT NULL,"
                + " preis_quelle VARCHAR(16) DEFAULT '', nachfrage VARCHAR(12) DEFAULT '',"
                + " saeule_kwh DOUBLE DEFAULT NULL, saeule_betrag DOUBLE DEFAULT NULL)");
        st.execute("INSERT INTO charging_sessions (id, start_time, end_time, start_soc, end_soc, energy_added_kwh,"
                + " peak_power_kw, is_dc, electricity_rate, session_cost, start_lat, start_lng, place_label)"
                + " VALUES (1, " + (TAG + 1000) + ", " + (TAG + 3_600_000L) + ", 39, 98, 25.5, 45, 1, 0.29, 7.39, "
                + LAT + ", " + LNG + ", 'Innere Stadt, Linz')");
        st.execute("INSERT INTO charging_sessions (id, start_time, end_time, start_soc, energy_added_kwh, start_lat, start_lng)"
                + " VALUES (2, " + (TAG + 4_000_000L) + ", NULL, 50, 2, " + LAT + ", " + LNG + ")");   // läuft noch
        st.execute("INSERT INTO charging_sessions (id, start_time, end_time, start_soc, end_soc, energy_added_kwh, is_dc)"
                + " VALUES (3, " + (TAG + 5_000_000L) + ", " + (TAG + 6_000_000L) + ", 20, 40, 8, 0)");    // ohne Position
    }

    private static SocHistoryDatabase db(Connection c, Path journal) throws Exception {
        SocHistoryDatabase database = new SocHistoryDatabase(journal.toFile());
        setField(database, "connection", c);
        setField(database, "isInitialized", true);
        return database;
    }

    @Test
    public void alteTabelle_lesenOhneFehler_dannMigrationUndSchreiben() throws Exception {
        Path journal = Files.createTempFile("charging-station", ".json");
        Files.deleteIfExists(journal);
        Class.forName("org.h2.Driver");
        try (Connection c = DriverManager.getConnection(
                "jdbc:h2:mem:charging-station-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
             Statement st = c.createStatement()) {
            alteTabelle(st);
            st.execute("CREATE TABLE soc_daily (day_epoch BIGINT, soh_percent REAL)");
            SocHistoryDatabase database = db(c, journal);

            // 1) Lesen vor der Migration: Ladung kommt, Säulen-Felder sind null (kein Fehlerzweig)
            JSONObject row = database.getChargingSessionById(1);
            assertNotNull(row);
            assertEquals("Innere Stadt, Linz", row.getString("placeLabel"));
            assertTrue(row.isNull("stationName"));
            assertTrue(row.isNull("zuordnung"));
            assertTrue(row.has("preisQuelle"));

            // 2) Migration idempotent
            SocHistoryDatabase.stationSpaltenSichern(c);
            SocHistoryDatabase.stationSpaltenSichern(c);
            for (String s : new String[] {"STATION_ID", "STATION_NAME", "BETREIBER", "STATION_ART", "ZUORDNUNG",
                    "STATION_KANDIDATEN"}) {
                try (ResultSet rs = c.getMetaData().getColumns(null, null, "CHARGING_SESSIONS", s)) {
                    assertTrue(s, rs.next());
                }
            }

            // 3) Schreiben: nur abgeschlossene Ladungen, nur die neuen Spalten
            assertTrue(database.setChargingStation(1, "auto", "AT*TST*1", "Testmarkt Linz", "Testmarkt GmbH", "DC",
                    "[{\"id\":\"AT*TST*1\",\"abstandM\":30}]"));
            assertFalse(database.setChargingStation(2, "auto", "X", "X", "X", "DC", null));   // läuft noch
            assertFalse(database.setChargingStation(99, "auto", "X", "X", "X", "DC", null));
            try (ResultSet rs = st.executeQuery("SELECT place_label, energy_added_kwh, session_cost, electricity_rate,"
                    + " start_lat, zuordnung, betreiber FROM charging_sessions WHERE id = 1")) {
                assertTrue(rs.next());
                assertEquals("Innere Stadt, Linz", rs.getString(1));
                assertEquals(25.5, rs.getDouble(2), 1e-6);
                assertEquals(7.39, rs.getDouble(3), 1e-6);
                assertEquals(0.29, rs.getDouble(4), 1e-6);
                assertEquals(LAT, rs.getDouble(5), 1e-9);
                assertEquals("auto", rs.getString(6));
                assertEquals("Testmarkt GmbH", rs.getString(7));
            }
            row = database.getChargingSessionById(1);
            assertEquals("Testmarkt Linz", row.getString("stationName"));
            assertEquals("Testmarkt GmbH", row.getString("betreiber"));
            assertEquals("DC", row.getString("stationArt"));
            assertEquals("auto", row.getString("zuordnung"));
            assertEquals("Innere Stadt, Linz", row.getString("placeLabel"));   // Ortsname bleibt
            assertTrue(database.getChargingStationKandidaten(1).contains("abstandM"));

            // Kandidaten unverändert lassen (null), Nutzerwahl überschreibt die Säule
            assertTrue(database.setChargingStation(1, "nutzer", null, "Freier Name", "Freier Name", null, null));
            assertTrue(database.getChargingStationKandidaten(1).contains("AT*TST*1"));
            assertTrue(database.getChargingSessionById(1).isNull("stationId"));
        } finally {
            Files.deleteIfExists(journal);
        }
    }

    @Test
    public void offeneUndZeilen_nurAbgeschlossenMitPosition() throws Exception {
        Path journal = Files.createTempFile("charging-station-offen", ".json");
        Files.deleteIfExists(journal);
        Class.forName("org.h2.Driver");
        try (Connection c = DriverManager.getConnection(
                "jdbc:h2:mem:charging-station-offen-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
             Statement st = c.createStatement()) {
            alteTabelle(st);
            SocHistoryDatabase database = db(c, journal);
            // Schreiber/Leser legen fehlende Spalten selbst an
            List<SocHistoryDatabase.StationZeile> offen = database.getStationOffen(100);
            assertEquals(1, offen.size());
            assertEquals(1, offen.get(0).id);
            assertEquals(1, offen.get(0).isDc);
            assertEquals(45, offen.get(0).peakKw, 1e-6);
            assertNotNull(database.getStationZeileByStart(TAG + 1000));
            assertNull(database.getStationZeileByStart(TAG + 4_000_000L));   // läuft noch
            assertTrue(database.setChargingStation(1, "fehler", null, null, null, null, null));
            assertEquals(1, database.getStationOffen(100).size());           // „fehler“ darf der Knopf nachholen
            assertTrue(database.setChargingStation(1, "privat", null, null, null, null, null));
            assertEquals(0, database.getStationOffen(100).size());
        } finally {
            Files.deleteIfExists(journal);
        }
    }

    /** Die echte Migration in {@code createTables} auf einer alten Tabelle mit Daten. */
    @Test
    public void echteCreateTables_alteTabelle_spaltenDa_altdatenGleich() throws Exception {
        Path journal = Files.createTempFile("charging-station-ct", ".json");
        Files.deleteIfExists(journal);
        Class.forName("org.h2.Driver");
        try (Connection c = DriverManager.getConnection(
                "jdbc:h2:mem:charging-station-ct-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
             Statement st = c.createStatement()) {
            alteTabelle(st);
            SocHistoryDatabase database = db(c, journal);
            Method m = SocHistoryDatabase.class.getDeclaredMethod("createTables");
            m.setAccessible(true);
            m.invoke(database);
            try (ResultSet rs = c.getMetaData().getColumns(null, null, "CHARGING_SESSIONS", "ZUORDNUNG")) {
                assertTrue(rs.next());
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*), SUM(energy_added_kwh) FROM charging_sessions")) {
                assertTrue(rs.next());
                assertEquals(3, rs.getInt(1));
                assertEquals(35.5, rs.getDouble(2), 1e-6);
            }
            try (ResultSet rs = st.executeQuery("SELECT place_label, zuordnung, station_name FROM charging_sessions WHERE id = 1")) {
                assertTrue(rs.next());
                assertEquals("Innere Stadt, Linz", rs.getString(1));
                assertEquals("", rs.getString(2));
                assertNull(rs.getString(3));
            }
        } finally {
            Files.deleteIfExists(journal);
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = SocHistoryDatabase.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
