package at.greenchili.surfmate.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * Kern 5 / Kern 4 (0.1.10.1): Fahrten nehmen den Preis der jüngsten Ladung mit BEKANNTEM Preis – Tarif, Nutzer, AC-Standard,
 * auch gratis; „fehlt“ und „ersatz“ (globaler Satz) zählen nicht. Synthetische Daten in H2 im Speicher.
 */
public class LetzterLadepreisTest {

    private static SocHistoryDatabase db(String... zeilen) throws Exception {
        Class.forName("org.h2.Driver");
        SocHistoryDatabase d = new SocHistoryDatabase(File.createTempFile("journal", ".json"));
        Connection c = DriverManager.getConnection("jdbc:h2:mem:letzter-preis-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TABLE charging_sessions (start_time BIGINT, end_time BIGINT, electricity_rate REAL, currency VARCHAR,"
                    + " tariff_id VARCHAR DEFAULT '', tariff_label VARCHAR DEFAULT '', is_dc INTEGER, preis_quelle VARCHAR(16) DEFAULT '')");
            for (String z : zeilen) st.execute("INSERT INTO charging_sessions VALUES (" + z + ")");
        }
        setze(d, "connection", c);
        setze(d, "isInitialized", true);
        return d;
    }

    private static String zeile(long minutenAlt, double rate, String tarif, String label, String quelle) {
        long ende = System.currentTimeMillis() - minutenAlt * 60_000L;
        return (ende - 3_600_000L) + ", " + ende + ", " + rate + ", '€', '" + tarif + "', '" + label + "', 0, '" + quelle + "'";
    }

    private static void setze(Object o, String name, Object wert) throws Exception {
        Field f = SocHistoryDatabase.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, wert);
    }

    @Test
    public void gratisZaehlt_fehltUndErsatzNicht() throws Exception {
        JSONObject r = db(zeile(10, -1, "", "", "fehlt"), zeile(20, 0.30, "", "", "ersatz"), zeile(30, 0, "", "zuhause", "nutzer"))
                .getLastChargeRateStrict(60);
        assertEquals(0, r.getDouble("rate"), 0);
        assertTrue(r.getBoolean("gratis"));
        assertEquals("nutzer", r.getString("quelle"));
    }

    @Test
    public void vorZeitpunkt_ladungNachDerFahrtWirdIgnoriert() throws Exception {
        SocHistoryDatabase d = db(zeile(10, 0.59, "t2", "SMATRICS", "tarif"), zeile(300, 0.29, "t1", "Lidl", "tarif"));
        long fahrtBeginn = System.currentTimeMillis() - 100 * 60_000L;   // zwischen den beiden Ladungen
        JSONObject r = d.getLastChargeRateVor(fahrtBeginn, 60);
        assertEquals(0.29, r.getDouble("rate"), 1e-6);                     // die spätere SMATRICS-Ladung zählt nicht
        assertNull(d.getLastChargeRateVor(System.currentTimeMillis() - 400 * 60_000L, 60));
        assertNull("Alter ab Fahrtbeginn", d.getLastChargeRateVor(fahrtBeginn + 61L * 86_400_000L, 60));
    }

    @Test
    public void altzeilen_tarifEigen_oderStandard_globaleNicht() throws Exception {
        assertNull(db(zeile(10, 0.30, "", "", "")).getLastChargeRateStrict(60));             // global (alt) → fehlt
        JSONObject t = db(zeile(10, 0.30, "", "", ""), zeile(20, 0.29, "t1", "Lidl", "")).getLastChargeRateStrict(60);
        assertEquals(0.29, t.getDouble("rate"), 1e-6);
        assertEquals("tarif", t.getString("quelle"));
        JSONObject s = db(zeile(10, 0, "", at.greenchili.surfmate.charging.LadeNachfrage.STANDARD_LABEL, "")).getLastChargeRateStrict(60);
        assertEquals("standard", s.getString("quelle"));
        assertTrue(s.getBoolean("gratis"));
        assertNull("älter als 60 Tage", db(zeile(61 * 24 * 60, 0.29, "t1", "Lidl", "tarif")).getLastChargeRateStrict(60));
    }
}
