package at.greenchili.surfmate.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.logging.DaemonLogger;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Verlust im Stand (03.11.2026): Rechnung aus Zündung aus/an, Ladezeiten und kurze Parkzeiten bleiben draußen,
 * Wächter an/aus nur bei Beleg an beiden Enden. Dazu Spalte und Nachtrag in echter H2-Datenbank.
 */
public class StandverlustTest {

    @BeforeClass
    public static void leise() {
        DaemonLogger.configure(DaemonLogger.Config.defaults()
                .withConsoleLog(false).withFileLog(false).withStdoutLog(false));
    }

    private static final long H = 3_600_000L;
    private static final long T0 = 20_000L * 86_400_000L;

    private static Standverlust.Ereignis aus(long h, double soc, Double kwh, Boolean scharf) {
        return new Standverlust.Ereignis(T0 + h * H, true, soc, kwh, scharf);
    }

    private static Standverlust.Ereignis an(long h, double soc, Double kwh, Boolean scharf) {
        return new Standverlust.Ereignis(T0 + h * H, false, soc, kwh, scharf);
    }

    @Test
    public void waechterGruppeNurMitBelegAnBeidenEnden() {
        assertEquals(Standverlust.AN, Standverlust.waechter(true, true));
        assertEquals(Standverlust.AUS, Standverlust.waechter(false, false));
        assertEquals(Standverlust.UNBEKANNT, Standverlust.waechter(true, false));
        assertEquals(Standverlust.UNBEKANNT, Standverlust.waechter(null, true));
        assertEquals(Standverlust.UNBEKANNT, Standverlust.waechter(false, null));
    }

    @Test
    public void rechnungJeTagUndGruppen() throws Exception {
        List<Standverlust.Ereignis> e = Arrays.asList(
                aus(0, 80, 40.0, true), an(24, 78, 39.0, true),        // an: 2 %/Tag, 1 kWh/Tag
                aus(25, 78, 39.0, true), an(73, 74, 37.0, true),       // an: 4 % in 48 h
                aus(74, 74, 37.0, false), an(98, 73.5, 36.75, false),  // aus: 0,5 %/Tag
                aus(99, 73.5, null, true), an(111, 73, null, false),   // gemischt → unbekannt, ohne kWh
                aus(112, 73, 36.5, null), an(113, 72, 36.0, null),     // 1 h: zu kurz
                aus(114, 72, 36.0, true), an(130, 90, 45.0, true),     // geladen (Anstieg)
                aus(131, 90, 45.0, true), an(150, 89, 44.5, true),     // überlappt eine Ladung
                an(151, 89, 44.5, null),                               // an ohne aus davor
                aus(152, 88, 44.0, true));                             // steht gerade: keine Parkzeit
        List<long[]> ladungen = Collections.singletonList(new long[] {T0 + 140 * H, T0 + 141 * H});
        JSONObject r = Standverlust.berechne(e, ladungen);
        JSONObject g = r.getJSONObject("gruppen");

        JSONObject alle = g.getJSONObject("gesamt");
        assertEquals(4, alle.getInt("parkzeiten"));
        assertEquals(108.0, alle.getDouble("stunden"), 1e-9);
        // (2 + 4 + 0,5 + 0,5) % in 108 h = 1,56 %/Tag
        assertEquals(1.56, alle.getDouble("prozentProTag"), 1e-9);
        // kWh nur, wo belegt: (1 + 2 + 0,25) in 96 h = 0,8125 kWh/Tag
        assertEquals(0.8125, alle.getDouble("kwhProTag"), 0.001);

        JSONObject wach = g.getJSONObject(Standverlust.AN);
        assertEquals(2, wach.getInt("parkzeiten"));
        assertEquals(2.0, wach.getDouble("prozentProTag"), 1e-9);  // 6 % in 72 h
        assertEquals(1.0, wach.getDouble("kwhProTag"), 1e-9);

        JSONObject ohne = g.getJSONObject(Standverlust.AUS);
        assertEquals(1, ohne.getInt("parkzeiten"));
        assertEquals(0.5, ohne.getDouble("prozentProTag"), 1e-9);

        JSONObject unb = g.getJSONObject(Standverlust.UNBEKANNT);
        assertEquals(1, unb.getInt("parkzeiten"));
        assertEquals(1.0, unb.getDouble("prozentProTag"), 1e-9);  // 0,5 % in 12 h
        assertTrue("ohne kWh-Beleg kein Wert", unb.isNull("kwhProTag"));

        JSONArray liste = r.getJSONArray("parkzeiten");
        assertEquals(4, liste.length());
        assertEquals("neueste zuerst", Standverlust.UNBEKANNT, liste.getJSONObject(0).getString("waechter"));
        assertFalse("keine Orte", r.toString().contains("lat") || r.toString().contains("lon"));
    }

    @Test
    public void nichtsBelegtHeisstNull() throws Exception {
        JSONObject r = Standverlust.berechne(new ArrayList<>(), null);
        JSONObject g = r.getJSONObject("gruppen").getJSONObject("gesamt");
        assertEquals(0, g.getInt("parkzeiten"));
        assertTrue(g.isNull("prozentProTag"));
        assertTrue(g.isNull("kwhProTag"));
        assertEquals(0, Standverlust.berechne(null, null).getJSONArray("parkzeiten").length());
    }

    @Test
    public void offeneLadungZaehltBisJetzt() {
        assertTrue(Standverlust.ueberlappt(T0, T0 + 10 * H, Collections.singletonList(new long[] {T0 + 5 * H, 0})));
        assertFalse(Standverlust.ueberlappt(T0, T0 + 10 * H, Collections.singletonList(new long[] {T0 + 11 * H, 0})));
        assertFalse(Standverlust.ueberlappt(T0, T0 + 10 * H, Collections.singletonList(new long[] {T0 - 5 * H, T0})));
    }

    @Test
    public void datenbankMerkerUndAuswertung() throws Exception {
        Path journal = Files.createTempFile("standverlust", ".json");
        Files.deleteIfExists(journal);
        Class.forName("org.h2.Driver");
        SocHistoryDatabase db = new SocHistoryDatabase(journal.toFile());
        try (Connection c = DriverManager.getConnection(
                "jdbc:h2:mem:standverlust-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
             Statement st = c.createStatement()) {
            // Bestand wie vor dem 03.11.2026: ohne Spalte waechter_scharf
            st.execute("CREATE TABLE acc_events (id IDENTITY PRIMARY KEY, timestamp BIGINT NOT NULL,"
                    + " event_type VARCHAR(8) NOT NULL, soc_percent REAL, remaining_kwh REAL, voltage_v REAL,"
                    + " range_km INTEGER)");
            st.execute("CREATE TABLE charging_sessions (id BIGINT PRIMARY KEY, start_time BIGINT, end_time BIGINT)");
            st.execute("INSERT INTO acc_events (timestamp, event_type, soc_percent, remaining_kwh) VALUES ("
                    + T0 + ", 'OFF', 80, 40)");
            st.execute("ALTER TABLE acc_events ADD COLUMN IF NOT EXISTS waechter_scharf INTEGER DEFAULT NULL");
            setField(db, "connection", c);
            setField(db, "isInitialized", true);

            assertFalse("zu altes Aus wird nicht markiert", db.markiereLetztesAusWaechter(true, T0 + 1));
            assertTrue(db.markiereLetztesAusWaechter(true, T0 - 60_000L));
            db.recordAccEvent("ON", null, Boolean.TRUE);
            try (ResultSet rs = st.executeQuery("SELECT event_type, waechter_scharf FROM acc_events ORDER BY id")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(2));
                assertTrue(rs.next());
                assertEquals("ON", rs.getString(1));
                assertEquals(1, rs.getInt(2));
            }
            // Zeitstempel des „an“ für die Rechnung auf 24 h nach dem Abstellen setzen, Akku 78 %
            st.execute("UPDATE acc_events SET timestamp = " + (T0 + 24 * H) + ", soc_percent = 78, remaining_kwh = 39"
                    + " WHERE event_type = 'ON'");
            db.recordAccEvent("OFF", null);                          // alter Weg: Merker bleibt leer
            try (ResultSet rs = st.executeQuery("SELECT waechter_scharf FROM acc_events WHERE event_type = 'OFF'"
                    + " ORDER BY id DESC LIMIT 1")) {
                assertTrue(rs.next());
                rs.getInt(1);
                assertTrue(rs.wasNull());
            }
            assertEquals(T0 + 24 * H, db.letztesAccAnVor(T0 + 25 * H));
            assertEquals(-1, db.letztesAccAnVor(T0));

            JSONObject r = db.getStandverlust(T0 - H);
            JSONObject wach = r.getJSONObject("gruppen").getJSONObject(Standverlust.AN);
            assertEquals(1, wach.getInt("parkzeiten"));
            assertEquals(2.0, wach.getDouble("prozentProTag"), 1e-9);
            assertEquals(1.0, wach.getDouble("kwhProTag"), 1e-9);
        } finally {
            Files.deleteIfExists(journal);
        }
    }

    @Test
    public void zuendungsZeileAuchOhneMerkerSpalte() throws Exception {
        Path journal = Files.createTempFile("standverlust-alt", ".json");
        Files.deleteIfExists(journal);
        Class.forName("org.h2.Driver");
        SocHistoryDatabase db = new SocHistoryDatabase(journal.toFile());
        try (Connection c = DriverManager.getConnection(
                "jdbc:h2:mem:standverlust-alt-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE acc_events (id IDENTITY PRIMARY KEY, timestamp BIGINT NOT NULL,"
                    + " event_type VARCHAR(8) NOT NULL, soc_percent REAL, remaining_kwh REAL, voltage_v REAL,"
                    + " range_km INTEGER)");
            setField(db, "connection", c);
            setField(db, "isInitialized", true);
            db.recordAccEvent("ON", null, Boolean.TRUE);
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM acc_events WHERE event_type = 'ON'")) {
                assertTrue(rs.next());
                assertEquals("Zündungs-Zeile geht nie verloren", 1, rs.getInt(1));
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
