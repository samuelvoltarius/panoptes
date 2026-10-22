package at.greenchili.surfmate.charging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * 0.1.11 (Kern 7, Koordination): cost = -1 / rate = -1 ist in der DB nur der Platzhalter „Preis fehlt“
 * (Alfreds Auto: Ladungen 867, 1319). Er darf nirgends als Betrag erscheinen oder in Summen eingehen.
 */
public class PreisPlatzhalterTest {

    @Test
    public void vertragsformAusEchterZeileZeigtNullUndPreisFehlt() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:platzhalter" + System.nanoTime(), "sa", "");
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE t (id BIGINT, start_time BIGINT, end_time BIGINT, energy_added_kwh DOUBLE, is_dc INT,"
                    + " electricity_rate DOUBLE, session_cost DOUBLE, currency VARCHAR(8), tariff_id VARCHAR(32),"
                    + " tariff_label VARCHAR(32), preis_quelle VARCHAR(16), nachfrage VARCHAR(16), start_lat DOUBLE, start_lng DOUBLE)");
            st.execute("INSERT INTO t VALUES (867, 1, 2, 20.5, 1, -1, -1, '€', '', '', 'fehlt', 'offen', 0, 0)");
            st.execute("INSERT INTO t VALUES (868, 3, 4, 10, 0, 0.29, 2.9, '€', '', '', 'nutzer', '', 0, 0)");
            try (ResultSet rs = st.executeQuery("SELECT * FROM t ORDER BY id")) {
                assertTrue(rs.next());
                JSONObject z = LadePreis.zeile(rs);
                assertTrue(z.isNull("cost"));
                assertTrue(z.isNull("rate"));
                assertTrue(z.getBoolean("preisFehlt"));
                assertEquals("Leser mit Rückfall sehen weiter -1", -1, z.optDouble("rate", -1), 0);
                assertTrue(rs.next());
                JSONObject z2 = LadePreis.zeile(rs);
                assertEquals(2.9, z2.getDouble("cost"), 1e-9);
                assertFalse(z2.has("preisFehlt"));
            }
        }
    }

    @Test
    public void preisAntwortAltUndNeuOhneMinusEins() throws Exception {
        LadePreisTest.Schein db = new LadePreisTest.Schein().mit(1, 12, false, -1, false);
        LadePreisTest.Prot p = new LadePreisTest.Prot();
        // vorher „fehlt“ (-1) → Nutzerpreis: alt zeigt null, nie -1
        JSONObject o = (JSONObject) LadePreis.preis(new JSONObject("{\"id\":1,\"rate\":0.3}"), db, id -> null, p, "€", 5L)[1];
        JSONObject alt = o.getJSONArray("ergebnisse").getJSONObject(0).getJSONObject("alt");
        assertTrue(alt.isNull("cost"));
        assertTrue(alt.isNull("rate"));
        assertTrue(alt.getBoolean("preisFehlt"));
        // zurück auf „fehlt“: neu zeigt null, gespeichert bleibt der Platzhalter
        JSONObject o2 = (JSONObject) LadePreis.preis(new JSONObject("{\"id\":1,\"fehlt\":true}"), db, id -> null, p, "€", 6L)[1];
        JSONObject neu = o2.getJSONArray("ergebnisse").getJSONObject(0).getJSONObject("neu");
        assertTrue(neu.isNull("cost"));
        assertTrue(neu.getBoolean("preisFehlt"));
        assertEquals(-1, db.zeilen.get(1L).getDouble("cost"), 0);
        // Rückgängig aus einem Protokoll mit null-Rate setzt wieder „fehlt“ (-1), nicht 0
        String pid = o.getString("protokollId");
        LadePreis.rueckgaengig(new JSONObject().put("protokollId", pid), db, p);
        assertEquals(-1, db.zeilen.get(1L).getDouble("rate"), 0);
    }

    @Test
    public void summenZaehlenPlatzhalterNicht() throws Exception {
        JSONArray a = new JSONArray()
                .put(sitzung(1, 20, -1, "fehlt"))
                .put(sitzung(2, 20, JSONObject.NULL, "fehlt"))
                .put(sitzung(3, 10, 2.9, "nutzer"));
        JSONObject k = LadeKosten.kosten(a, 100, LadeKosten.GESAMT, 0, Long.MAX_VALUE);
        String t = k.toString();
        assertFalse(t, t.contains("-1"));
        assertFalse(t, t.contains("-0.") || t.contains("-2"));
        JSONObject l = LadeKosten.ladungen(a, 0, 10, java.util.TimeZone.getTimeZone("Europe/Vienna"));
        String tl = l.toString();
        assertFalse(tl, tl.contains("\"kosten\":-1"));
    }

    private static JSONObject sitzung(long id, double kwh, Object cost, String quelle) throws Exception {
        return new JSONObject().put("id", id).put("startTime", id * 3_600_000L).put("endTime", id * 3_600_000L + 1000)
                .put("inProgress", false).put("energyAdded", kwh).put("cost", cost)
                .put("electricityRate", cost instanceof Number ? ((Number) cost).doubleValue() / kwh : JSONObject.NULL)
                .put("currency", "€").put("preisQuelle", quelle).put("tariffId", JSONObject.NULL)
                .put("tariffLabel", JSONObject.NULL).put("isDc", true).put("lat", 0).put("lng", 0);
    }
}
