package at.greenchili.surfmate.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.logging.DaemonLogger;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * kWh und Betrag laut Säule (03.11.2026): Speichern ändert nur die neuen Spalten; Akku-Energie, gespeicherte Kosten
 * und Tagesrollup bleiben, die Summen bekommen die abgerechneten Werte zusätzlich. Synthetische Zahlen, kein Ort.
 */
public class SocHistoryDatabaseSaeuleTest {

    @BeforeClass
    public static void disableAndroidLogging() {
        DaemonLogger.configure(DaemonLogger.Config.defaults()
                .withConsoleLog(false)
                .withFileLog(false)
                .withStdoutLog(false));
    }

    private static final long TAG = 20_000L * 86_400_000L;

    @Test
    public void saeuleSpeichern_aendertNurNeueSpalten_summeAbgerechnet() throws Exception {
        Path journal = Files.createTempFile("charging-saeule", ".json");
        Files.deleteIfExists(journal);
        Class.forName("org.h2.Driver");
        SocHistoryDatabase database = new SocHistoryDatabase(journal.toFile());
        try (Connection c = DriverManager.getConnection(
                "jdbc:h2:mem:charging-saeule-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE charging_sessions (id BIGINT PRIMARY KEY, start_time BIGINT, end_time BIGINT,"
                    + " energy_added_kwh REAL, session_cost REAL, electricity_rate REAL, is_dc INTEGER,"
                    + " energy_source VARCHAR(32) DEFAULT '', energy_incomplete INTEGER DEFAULT 0,"
                    + " saeule_kwh DOUBLE DEFAULT NULL, saeule_betrag DOUBLE DEFAULT NULL)");
            st.execute("CREATE TABLE charging_daily (day_epoch BIGINT PRIMARY KEY, session_count INTEGER,"
                    + " energy_kwh REAL, cost REAL, dc_count INTEGER, ac_count INTEGER, range_gained_km INTEGER,"
                    + " incomplete_count INTEGER)");
            st.execute("CREATE TABLE soc_daily (day_epoch BIGINT, soh_percent REAL)");
            // DC 25,5 kWh zu 0,29 €/kWh (7,395 €), AC 10 kWh mit „Preis fehlt“, eine offene Ladung
            st.execute("INSERT INTO charging_sessions (id, start_time, end_time, energy_added_kwh, session_cost,"
                    + " electricity_rate, is_dc, energy_source) VALUES (1, " + (TAG + 1000) + ", " + (TAG + 3_600_000L)
                    + ", 25.5, 7.395, 0.29, 1, 'metered_counter')");
            st.execute("INSERT INTO charging_sessions (id, start_time, end_time, energy_added_kwh, session_cost,"
                    + " electricity_rate, is_dc, energy_source) VALUES (2, " + (TAG + 4_000_000L) + ", "
                    + (TAG + 7_000_000L) + ", 10, -1, -1, 0, 'metered_counter')");
            st.execute("INSERT INTO charging_sessions (id, start_time, end_time, energy_added_kwh)"
                    + " VALUES (3, " + (TAG + 8_000_000L) + ", NULL, 2)");
            st.execute("INSERT INTO charging_daily VALUES (" + TAG + ", 2, 35.5, 7.395, 1, 1, 0, 0)");
            setField(database, "connection", c);
            setField(database, "isInitialized", true);

            assertTrue(database.setChargingSaeule(1, 28.18, Double.NaN));
            assertFalse(database.setChargingSaeule(3, 5, Double.NaN));        // läuft noch: nie
            assertFalse(database.setChargingSaeule(99, 5, Double.NaN));       // gibt es nicht
            assertFalse(database.setChargingSaeule(1, -2, Double.NaN));       // ungültig
            try (ResultSet rs = st.executeQuery(
                    "SELECT energy_added_kwh, session_cost, saeule_kwh, saeule_betrag FROM charging_sessions WHERE id = 1")) {
                assertTrue(rs.next());
                assertEquals(25.5, rs.getDouble(1), 0.0001);
                assertEquals(7.395, rs.getDouble(2), 0.0001);
                assertEquals(28.18, rs.getDouble(3), 0.0001);
                rs.getDouble(4);
                assertTrue(rs.wasNull());
            }

            JSONObject sum = database.getChargingSummaryRangeStrict(TAG, TAG + 86_399_999L);
            assertEquals(35.5, sum.getDouble("periodEnergyKwh"), 0.001);      // Akku-Basis unverändert
            assertEquals(7.395, sum.getDouble("periodCost"), 0.001);
            // 28 (eingetragen) + 10 × 1,12 (AC-Vorschlag, ohne Konfig)
            assertEquals(28.18 + 11.2, sum.getDouble("periodEnergyAbgerechnetKwh"), 0.01);
            assertEquals(0.29 * 28.18, sum.getDouble("periodCostAbgerechnet"), 0.001);   // „Preis fehlt“ zählt nicht
            assertEquals(1, sum.getInt("periodAbgerechnetGeschaetzt"));
            JSONObject tag = sum.getJSONArray("daily").getJSONObject(0);
            assertEquals(39.38, tag.getDouble("energyAbgerechnet"), 0.01);

            // Betrag eintragen, dann wieder löschen
            assertTrue(database.setChargingSaeule(2, Double.NaN, 3.0));
            sum = database.getChargingSummaryRangeStrict(TAG, TAG + 86_399_999L);
            assertEquals(0.29 * 28.18 + 3.0, sum.getDouble("periodCostAbgerechnet"), 0.001);
            assertTrue(database.setChargingSaeule(1, Double.NaN, Double.NaN));
            sum = database.getChargingSummaryRangeStrict(TAG, TAG + 86_399_999L);
            assertEquals(25.5 * 1.08 + 11.2, sum.getDouble("periodEnergyAbgerechnetKwh"), 0.01);
            assertEquals(7.395 * 1.08 + 3.0, sum.getDouble("lifetimeCostAbgerechnet"), 0.001);
        } finally {
            Files.deleteIfExists(journal);
        }
    }

    @Test
    public void kostenAendern_giltAlsAbgerechnet_keinZuschlag_fahrtpreisGleich() throws Exception {
        Path journal = Files.createTempFile("charging-kosten-aendern", ".json");
        Files.deleteIfExists(journal);
        Class.forName("org.h2.Driver");
        SocHistoryDatabase database = new SocHistoryDatabase(journal.toFile());
        long ende = System.currentTimeMillis() - 3_600_000L;
        long tag = (ende / 86_400_000L) * 86_400_000L;
        try (Connection c = DriverManager.getConnection(
                "jdbc:h2:mem:charging-kosten-aendern-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE charging_sessions (id BIGINT PRIMARY KEY, start_time BIGINT, end_time BIGINT,"
                    + " energy_added_kwh REAL, session_cost REAL, electricity_rate REAL, is_dc INTEGER,"
                    + " currency VARCHAR(8) DEFAULT '', tariff_id VARCHAR(16) DEFAULT '', tariff_label VARCHAR(64) DEFAULT '',"
                    + " preis_quelle VARCHAR(16) DEFAULT '', peak_power_kw REAL DEFAULT -1, range_gained_km INTEGER DEFAULT -1,"
                    + " energy_source VARCHAR(32) DEFAULT '', energy_incomplete INTEGER DEFAULT 0,"
                    + " post_commit_tariff_applied INTEGER DEFAULT 0, post_commit_soh_applied INTEGER DEFAULT 1,"
                    + " saeule_kwh DOUBLE DEFAULT NULL, saeule_betrag DOUBLE DEFAULT NULL)");
            st.execute("CREATE TABLE charging_daily (day_epoch BIGINT PRIMARY KEY, session_count INTEGER,"
                    + " energy_kwh REAL, cost REAL, dc_count INTEGER, ac_count INTEGER, peak_power_kw REAL,"
                    + " soh_at_day REAL, range_gained_km INTEGER, incomplete_count INTEGER)");
            st.execute("CREATE TABLE soc_daily (day_epoch BIGINT, soh_percent REAL)");
            st.execute("INSERT INTO charging_sessions (id, start_time, end_time, energy_added_kwh, session_cost,"
                    + " electricity_rate, is_dc, tariff_id, preis_quelle) VALUES (1, " + (ende - 1_800_000L) + ", " + ende
                    + ", 25.5, 7.395, 0.29, 1, 't1', 'tarif')");
            setField(database, "connection", c);
            setField(database, "isInitialized", true);

            // ohne Eintrag: DC-Zuschlag 8 % – auf Ladung und Fahrtpreis gleich
            JSONObject l = database.getLastChargeRateStrict(60);
            assertEquals(0.29, l.getDouble("rate"), 1e-6);
            assertEquals(0.29 * 1.08, l.getDouble("rateAbgerechnet"), 1e-6);

            // „Kosten ändern“ auf 8,12 € = abgerechneter Betrag: kein Zuschlag mehr
            assertTrue(database.updateChargingSessionCost(1, 8.17));
            try (ResultSet rs = st.executeQuery("SELECT session_cost, saeule_betrag FROM charging_sessions WHERE id = 1")) {
                assertTrue(rs.next());
                assertEquals(8.17, rs.getDouble(1), 1e-6);
                assertEquals(8.17, rs.getDouble(2), 1e-6);
            }
            JSONObject sum = database.getChargingSummaryRangeStrict(tag, tag + 86_399_999L);
            assertEquals(8.17, sum.getDouble("periodCostAbgerechnet"), 1e-6);
            assertEquals(8.17, sum.getDouble("periodCost"), 1e-6);
            // Fahrtpreis: genau Betrag ÷ Akku-kWh, wie die Ladung
            assertEquals(8.17 / 25.5, database.getLastChargeRateStrict(60).getDouble("rateAbgerechnet"), 1e-6);

            // Zurücksetzen (-1) löscht den Betrag wieder
            assertTrue(database.updateChargingSessionCost(1, -1));
            try (ResultSet rs = st.executeQuery("SELECT saeule_betrag FROM charging_sessions WHERE id = 1")) {
                assertTrue(rs.next());
                rs.getDouble(1);
                assertTrue(rs.wasNull());
            }
        } finally {
            Files.deleteIfExists(journal);
        }
    }

    @Test
    public void ladungsListe_liestPreisQuelleMit() throws Exception {
        // 03.11.2026: preis_quelle fehlte in der Spaltenliste → preisQuelle war für jede Zeile null
        Path journal = Files.createTempFile("charging-preisquelle", ".json");
        Files.deleteIfExists(journal);
        Class.forName("org.h2.Driver");
        SocHistoryDatabase database = new SocHistoryDatabase(journal.toFile());
        long start = System.currentTimeMillis() - 7_200_000L;
        try (Connection c = DriverManager.getConnection(
                "jdbc:h2:mem:charging-preisquelle-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE charging_sessions (id BIGINT PRIMARY KEY, start_time BIGINT, end_time BIGINT,"
                    + " start_soc REAL, end_soc REAL, energy_added_kwh REAL, peak_power_kw REAL, avg_power_kw REAL,"
                    + " range_gained_km INTEGER, gun_state INTEGER, is_dc INTEGER, electricity_rate REAL, currency VARCHAR,"
                    + " session_cost REAL, time_to_full_min INTEGER, hv_temp_high REAL, hv_temp_low REAL, hv_temp_avg REAL,"
                    + " start_lat DOUBLE, start_lng DOUBLE, place_label VARCHAR, start_odometer_km INTEGER,"
                    + " tariff_id VARCHAR, tariff_label VARCHAR, energy_source VARCHAR, energy_soc_kwh DOUBLE,"
                    + " energy_incomplete INTEGER, counter_energy_kwh DOUBLE, preis_quelle VARCHAR(16) DEFAULT '',"
                    + " nachfrage VARCHAR(12) DEFAULT '', saeule_kwh DOUBLE DEFAULT NULL, saeule_betrag DOUBLE DEFAULT NULL)");
            st.execute("INSERT INTO charging_sessions (id, start_time, end_time, start_soc, end_soc, energy_added_kwh,"
                    + " is_dc, electricity_rate, currency, session_cost, tariff_id, tariff_label, energy_source,"
                    + " energy_incomplete, preis_quelle, nachfrage) VALUES (1, " + start + ", " + (start + 2_940_000L)
                    + ", 39, 98, 25.488, 1, 0.29, '€', 7.39152, '', 'ad hoc', 'soc_estimate', 0, 'nutzer', 'erledigt')");
            setField(database, "connection", c);
            setField(database, "isInitialized", true);
            JSONObject z = database.getChargingSessionsV2RangeStrict(0L, Long.MAX_VALUE, 10, 0).getJSONObject(0);
            assertEquals("nutzer", z.getString("preisQuelle"));
            assertEquals("erledigt", z.getString("nachfrage"));
            assertEquals(7.39, z.getDouble("cost"), 0.01);
            // und LadeKosten zählt sie als bepreist
            JSONObject k = at.greenchili.surfmate.charging.LadeKosten.kosten(
                    new org.json.JSONArray().put(z), 160, "monat", 0, 9);
            assertEquals(0, k.getInt("ladungen_ohne_preis"));
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
