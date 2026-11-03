package at.greenchili.surfmate.trips;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Kurve Verbrauch gegen Außentemperatur (03.11.2026): fehlende Temperaturen werden nicht erfunden, alte Fahrten mit
 * 0 °C zählen als unbekannt, gewichteter Mittelwert je 5-°C-Topf. Dazu die neue Spalte in echter H2-Datenbank.
 */
public class VerbrauchTemperaturTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static TripRecord fahrt(double km, double kwh, int temp, int bekannt) {
        TripRecord t = new TripRecord();
        t.startTime = 1_000L;
        t.endTime = 2_000L;
        t.distanceKm = km;
        t.kwhStart = 50;
        t.kwhEnd = 50 - kwh;
        t.extTempC = temp;
        t.extTempBekannt = bekannt;
        return t;
    }

    @Test
    public void topfGrenzen() {
        assertEquals(-5, VerbrauchTemperatur.topf(-3));
        assertEquals(-5, VerbrauchTemperatur.topf(-5));
        assertEquals(-10, VerbrauchTemperatur.topf(-6));
        assertEquals(0, VerbrauchTemperatur.topf(0));
        assertEquals(5, VerbrauchTemperatur.topf(7));
        assertEquals(20, VerbrauchTemperatur.topf(24));
    }

    @Test
    public void bekanntNurMitMerkerOderAltMitWertUngleichNull() {
        assertTrue(fahrt(10, 1.5, 0, 1).hatAussenTemperatur());     // neu: echte 0 °C
        assertFalse(fahrt(10, 1.5, 0, 0).hatAussenTemperatur());    // neu: nicht lesbar
        assertFalse(fahrt(10, 1.5, 12, 0).hatAussenTemperatur());
        assertFalse(fahrt(10, 1.5, 0, -1).hatAussenTemperatur());   // alt: 0 hieß auch „unbekannt“
        assertTrue(fahrt(10, 1.5, -4, -1).hatAussenTemperatur());   // alt mit Wert
    }

    @Test
    public void kurveMitFehlendenWertenErfindetNichts() throws Exception {
        List<TripRecord> l = new ArrayList<>(Arrays.asList(
                fahrt(20, 4.0, -3, 1),     // -5 … 0: 20 kWh/100
                fahrt(10, 1.6, -1, -1),    // -5 … 0, alt mit Wert: 16 kWh/100
                fahrt(30, 4.5, 12, 1),     // 10 … 15: 15
                fahrt(10, 1.3, 14, 1),     // 10 … 15: 13
                fahrt(40, 6.0, 0, -1),     // alt, 0 → unbekannt, zählt nicht
                fahrt(25, 3.0, 8, 0),      // neu, nicht lesbar → zählt nicht
                fahrt(1.5, 0.3, 10, 1),    // zu kurz
                fahrt(15, 0.0, 10, 1)));   // keine Energie
        l.add(null);
        JSONObject k = VerbrauchTemperatur.berechne(l);
        assertEquals(4, k.getInt("fahrtenMitTemperatur"));
        assertEquals(2, k.getInt("fahrtenOhneTemperatur"));
        assertEquals(2, k.getInt("fahrtenZuKurz"));
        JSONArray p = k.getJSONArray("punkte");
        assertEquals("nur Töpfe mit Daten, kein Punkt bei 0 °C erfunden", 2, p.length());
        JSONObject kalt = p.getJSONObject(0);
        assertEquals(-5, kalt.getInt("von"));
        assertEquals(0, kalt.getInt("bis"));
        assertEquals(2, kalt.getInt("fahrten"));
        // gewichtet: (4,0 + 1,6) / (20 + 10) × 100 = 18,7 – nicht der Mittelwert 18,0
        assertEquals(18.7, kalt.getDouble("kwhPro100km"), 1e-9);
        JSONObject mild = p.getJSONObject(1);
        assertEquals(10, mild.getInt("von"));
        assertEquals(14.5, mild.getDouble("kwhPro100km"), 1e-9);
        assertEquals(40.0, mild.getDouble("km"), 1e-9);
        assertFalse("keine Orte in der Antwort", k.toString().contains("Lat") || k.toString().contains("Lon"));
    }

    @Test
    public void leereEingabe() throws Exception {
        JSONObject k = VerbrauchTemperatur.berechne(null);
        assertEquals(0, k.getJSONArray("punkte").length());
        assertEquals(0, k.getInt("fahrtenMitTemperatur"));
    }

    @Test
    public void spalteWirdGespeichertUndAltbestandBleibtUnbekannt() throws Exception {
        String url = "jdbc:h2:mem:temp-bekannt-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        TripDatabase db = new TripDatabase();
        db.initFuerTest(url);
        long neuMitNull = db.insertTrip(fahrt(10, 1.5, 0, 1));
        long neuOhne = db.insertTrip(fahrt(10, 1.5, 0, 0));
        assertTrue(db.getTrip(neuMitNull).hatAussenTemperatur());
        assertFalse(db.getTrip(neuOhne).hatAussenTemperatur());
        TripRecord u = db.getTrip(neuOhne);
        u.extTempC = 9; u.extTempBekannt = 1;
        assertTrue(db.updateTrip(u));
        assertEquals(1, db.getTrip(neuOhne).extTempBekannt);
        db.close();

        // Bestand vor der Spalte: Spalte entfernen, Zeile „alt“ ohne Merker, dann neu öffnen (Migration)
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             Statement st = c.createStatement()) {
            st.execute("ALTER TABLE trips DROP COLUMN ext_temp_bekannt");
            st.execute("UPDATE trips SET ext_temp_c = 0 WHERE id = " + neuMitNull);
        }
        TripDatabase neu = new TripDatabase();
        neu.initFuerTest(url);
        TripRecord alt = neu.getTrip(neuMitNull);
        assertEquals(-1, alt.extTempBekannt);
        assertFalse("alte 0 °C wird nicht als Messwert gezählt", alt.hatAussenTemperatur());
        TripRecord altMitWert = neu.getTrip(neuOhne);
        assertEquals(-1, altMitWert.extTempBekannt);
        assertTrue(altMitWert.hatAussenTemperatur());
        neu.close();
    }
}
