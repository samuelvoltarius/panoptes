package at.greenchili.surfmate.trips;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import at.greenchili.surfmate.testutil.Quelltext;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Paths;

/** 0.1.10.1: nach dem Ordner-Umzug gelöschte Fahrten – Pfad reparieren statt löschen, Werte nachtragen. */
public class FahrtWiederherstellenTest {

    @Test
    public void alfredsFahrt1742() throws Exception {
        TripRecord t = new TripRecord();
        t.distanceKm = 33.1;
        t.endTime = 2L;
        JSONObject req = new JSONObject().put("socStart", 85).put("socEnd", 71)
                .put("elecConStart", 4601.3).put("elecConEnd", 4607.3)
                .put("odometerStartKm", 27916.9).put("odometerEndKm", 27950.0);
        String g = TripApiHandler.wiederherstellen(t, req).toString();
        assertTrue(g, g.contains("soc") && g.contains("elecCon") && g.contains("odometer"));
        assertEquals(85, t.socStart, 0);
        assertEquals(6.0, t.getEnergyUsedKwh(), 1e-6);
        assertEquals("fehlt", t.rateSource);   // Kosten nicht erfunden, Anzeige rechnet nach
        assertEquals(6.0 / 33.1, t.energyPerKm, 1e-9);
    }

    @Test
    public void gemesseneWerteNieUeberschreiben() throws Exception {
        TripRecord t = new TripRecord();
        t.distanceKm = 10;
        t.socStart = 50; t.socEnd = 40;
        t.elecConStart = 1; t.elecConEnd = 2;
        String g = TripApiHandler.wiederherstellen(t, new JSONObject().put("socStart", 90).put("socEnd", 80)
                .put("elecConStart", 5).put("elecConEnd", 7)).toString();
        assertEquals("[]", g);
        assertEquals(50, t.socStart, 0);
        assertEquals(1, t.elecConStart, 0);
    }

    @Test
    public void unplausibelWirdAbgelehnt() throws Exception {
        try {
            TripApiHandler.wiederherstellen(new TripRecord(), new JSONObject().put("socStart", 120).put("socEnd", 10));
            fail();
        } catch (IllegalArgumentException e) {
            assertEquals("soc_ungueltig", e.getMessage());
        }
        TripRecord kurz = new TripRecord();
        kurz.distanceKm = 1;
        try {
            TripApiHandler.wiederherstellen(kurz, new JSONObject().put("elecConStart", 0).put("elecConEnd", 50));
            fail();
        } catch (IllegalArgumentException e) {
            assertEquals("elecCon_ungueltig", e.getMessage());
        }
    }

    @Test
    public void abgleichReparierPfadStattZuLoeschen() throws Exception {
        String q = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/trips/TripDatabase.java"));
        int auf = q.indexOf("AppOrdner.aufloesen(path)");
        int orphan = q.indexOf("missingFileOrphans.add(rs.getLong(\"id\"));", auf);
        assertTrue("Auflösen vor dem Löschkandidaten", auf > 0 && orphan > auf);
        assertTrue(q.contains("UPDATE trips SET telemetry_file_path=? WHERE id=?"));
        assertTrue(q.contains("AppOrdner.aufloesen(rs.getString(\"telemetry_file_path\"))"));
    }
}
