package at.greenchili.surfmate.trips;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.testutil.Quelltext;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Paths;

/** 0.1.11 (LADENACHFRAGE_VERTRAG §F): Fahrten nie mehr mit dem globalen Satz; ohne bepreiste Ladung „fehlt“. */
public class FahrtPreisFehltTest {

    @Test
    public void ohneBepreisteLadungFehlt() {
        TripAnalyticsManager.Satz s = TripAnalyticsManager.satz(null);
        assertEquals("fehlt", s.quelle);
        assertEquals(0, s.rate, 0);
        assertEquals("fehlt", TripAnalyticsManager.satz(new JSONObject()).quelle);
    }

    @Test
    public void bekannterPreisAuchGratis() throws Exception {
        TripAnalyticsManager.Satz t = TripAnalyticsManager.satz(new JSONObject().put("rate", 0.39).put("tariffLabel", "DC"));
        assertEquals("charge", t.quelle);
        assertEquals(0.39, t.rate, 1e-9);
        assertEquals("DC", t.label);
        TripAnalyticsManager.Satz g = TripAnalyticsManager.satz(new JSONObject().put("rate", 0.0));
        assertEquals("charge", g.quelle);
        assertEquals(0, g.rate, 0);
        assertEquals("gratis", g.label);
        assertEquals("Preis fehlt (-1) zählt nicht", "fehlt",
                TripAnalyticsManager.satz(new JSONObject().put("rate", -1)).quelle);
    }

    @Test
    public void anzeigeRechnetFehltNachOhneDbZuAendern() throws Exception {
        java.util.function.BiFunction<Long, Integer, JSONObject> vorher = TripApiHandler.ladepreisVor;
        try {
            TripRecord t = new TripRecord();
            t.startTime = 1_000L;
            t.rateSource = "fehlt";
            t.elecConStart = 100;
            t.elecConEnd = 106.1;   // 6,1 kWh gemessen
            long[] gefragt = new long[1];
            TripApiHandler.ladepreisVor = (vor, tage) -> {
                gefragt[0] = vor;
                try { return new JSONObject().put("rate", 0.0); } catch (Exception e) { throw new RuntimeException(e); }
            };
            assertTrue(TripApiHandler.nachrechnen(t));
            assertEquals(1_000L, gefragt[0]);
            assertEquals("nachgerechnet", t.rateSource);
            assertEquals("gratis", t.rateLabel);
            assertEquals(0, t.electricCost, 0);
            // bekannter Preis wird nie überschrieben
            TripRecord bekannt = new TripRecord();
            bekannt.rateSource = "charge";
            bekannt.electricityRate = 0.39;
            assertFalse(TripApiHandler.nachrechnen(bekannt));
            // nichts bekannt → bleibt fehlt
            TripApiHandler.ladepreisVor = (vor, tage) -> null;
            TripRecord leer = new TripRecord();
            leer.rateSource = "fehlt";
            assertFalse(TripApiHandler.nachrechnen(leer));
            assertEquals("fehlt", leer.rateSource);
        } finally {
            TripApiHandler.ladepreisVor = vorher;
        }
    }

    @Test
    public void globalerSatzNichtMehrFuerFahrten() throws Exception {
        String q = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/trips/TripAnalyticsManager.java"));
        assertFalse(q.contains("trip.electricityRate = config.getElectricityRate();"));
        assertFalse(q.contains("trip.rateSource = \"config\";"));
        assertTrue(q.contains("trip.rateSource = satz.quelle;"));
        String h = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/trips/TripApiHandler.java"));
        assertFalse("Anzeige füllt nie den globalen Satz nach", h.contains("trip.electricityRate = config.getElectricityRate();"));
    }
}
