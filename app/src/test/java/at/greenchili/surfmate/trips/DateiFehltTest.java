package at.greenchili.surfmate.trips;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * 0.1.11 (Kern 7): „Datei fehlt“ markieren statt Fahrt löschen. Echte H2-Datenbank (Speicher), echte Dateien.
 * Pflichttest der Koordination: Bestand + Migration + Abgleich = 0 Verluste.
 */
public class DateiFehltTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static TripRecord fahrt(long start, String pfad) {
        TripRecord t = new TripRecord();
        t.startTime = start;
        t.endTime = start + 1_000L;
        t.distanceKm = 12.5;
        t.socStart = 80; t.socEnd = 74;
        t.telemetryFilePath = pfad;
        return t;
    }

    @Test
    public void bestandOhneSpalteMigrationUndAbgleichVerlierenNichts() throws Exception {
        File dir = tmp.newFolder("intern", "Panoptes", "trips");
        String url = "jdbc:h2:mem:bestand" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        TripDatabase alt = new TripDatabase();
        alt.initFuerTest(url);
        int n = 0;
        long[] ids = new long[30];
        for (int i = 0; i < 20; i++) {                       // 20 mit Datei, davon 5 später weg
            File f = new File(dir, (1_000L + i) + ".jsonl.gz");
            Files.write(f.toPath(), new byte[] {1, 2, 3, 4});
            ids[n++] = alt.insertTrip(fahrt(1_000L + i * 10_000L, f.getPath()));
        }
        for (int i = 0; i < 6; i++) {                        // wie Kern 5s Overdrive-Import: Pfad null
            ids[n++] = alt.insertTrip(fahrt(900_000L + i * 10_000L, null));
        }
        for (int i = 0; i < 4; i++) {                        // importiert (Wiederherstellung): Sentinel
            ids[n++] = alt.insertTrip(fahrt(2_000_000L + i * 10_000L, TripDatabase.IMPORTED_PATH));
        }
        assertEquals(30, alt.getTripCount());
        // Bestand wie 0.1.10.2: Spalte gibt es noch nicht
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             Statement st = c.createStatement()) {
            st.execute("ALTER TABLE trips DROP COLUMN datei_fehlt_seit");
        }
        alt.close();

        File[] fs = dir.listFiles();
        for (int i = 0; i < 5; i++) assertTrue(fs[i].delete());

        TripDatabase neu = new TripDatabase();
        neu.initFuerTest(url);                               // Migration: Spalte kommt dazu
        assertEquals(30, neu.getTripCount());
        int geaendert = neu.abgleichFehlendeDateien(p -> true);
        assertEquals("5 Datei weg + 6 ohne Pfad markiert", 11, geaendert);
        assertEquals("0 Verluste", 30, neu.getTripCount());
        assertEquals(11, neu.getAnzahlDateiFehlt());
        for (int i = 0; i < n; i++) {
            TripRecord t = neu.getTrip(ids[i]);
            assertNotNull("Fahrt " + ids[i] + " verloren", t);
            assertEquals("Messwerte unverändert", 80, t.socStart, 0);
            assertEquals(12.5, t.distanceKm, 0);
        }
        assertEquals("zweiter Lauf idempotent", 0, neu.abgleichFehlendeDateien(p -> true));
        assertEquals(30, neu.getTripCount());
    }

    @Test
    public void markierteZaehlenNichtZurGroesseUndWiederDaHebtAuf() throws Exception {
        File dir = tmp.newFolder("intern2", "Panoptes", "trips");
        TripDatabase db = new TripDatabase();
        db.initFuerTest("jdbc:h2:mem:groesse" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        File[] f = new File[10];
        long[] ids = new long[10];
        for (int i = 0; i < 10; i++) {
            f[i] = new File(dir, (100L + i) + ".jsonl.gz");
            Files.write(f[i].toPath(), new byte[100]);
            TripRecord t = fahrt(100_000L * (i + 1), f[i].getPath());
            t.sizeBytes = 100;
            ids[i] = db.insertTrip(t);
        }
        assertEquals(1_000L, db.getTotalSizeBytes());
        byte[] inhalt = Files.readAllBytes(f[3].toPath());
        assertTrue(f[3].delete());
        assertEquals(1, db.abgleichFehlendeDateien(p -> true));
        assertEquals("Speicherlimit konvergiert", 900L, db.getTotalSizeBytes());
        assertTrue(db.isBackfillComplete());

        JSONObject j = db.getTrip(ids[3]).toJson();
        assertTrue(j.getBoolean("dateiFehlt"));
        assertTrue(j.getLong("dateiFehltSeit") > 0);
        assertFalse(db.getTrip(ids[4]).toJson().getBoolean("dateiFehlt"));

        Files.write(f[3].toPath(), inhalt);                   // Datenträger wieder da
        assertEquals(1, db.abgleichFehlendeDateien(p -> true));
        assertEquals(0, db.getAnzahlDateiFehlt());
        assertEquals(1_000L, db.getTotalSizeBytes());
        assertFalse(db.getTrip(ids[3]).toJson().getBoolean("dateiFehlt"));
    }

    @Test
    public void datentraegerFehltMarkiertNichts() throws Exception {
        File usb = tmp.newFolder("usb", "Panoptes", "trips");
        TripDatabase db = new TripDatabase();
        db.initFuerTest("jdbc:h2:mem:usb" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        for (int i = 0; i < 5; i++) {
            File f = new File(usb, i + ".jsonl.gz");
            Files.write(f.toPath(), new byte[] {1});
            db.insertTrip(fahrt(10_000L * (i + 1), f.getPath()));
            assertTrue(f.delete());
        }
        db.insertTrip(fahrt(900_000L, null));
        assertEquals(0, db.abgleichFehlendeDateien(p -> false));
        assertEquals(0, db.getAnzahlDateiFehlt());
        assertEquals(6, db.getTripCount());
    }

    @Test
    public void speicherReaperEntferntNurDenVerlauf() throws Exception {
        File dir = tmp.newFolder("intern3", "Panoptes", "trips");
        TripDatabase db = new TripDatabase();
        db.initFuerTest("jdbc:h2:mem:reaper" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        File f = new File(dir, "7.jsonl.gz");
        Files.write(f.toPath(), new byte[50]);
        TripRecord t = fahrt(70_000L, f.getPath());
        t.sizeBytes = 50;
        long id = db.insertTrip(t);
        assertTrue(f.delete());                               // wie ensureSpace: Datei weg …
        assertEquals(1, db.markiereVerlaufEntfernt(f.getAbsolutePath()));   // … Zeile bleibt
        assertEquals(0, db.markiereVerlaufEntfernt(f.getAbsolutePath()));
        assertEquals(1, db.getTripCount());
        assertEquals(0L, db.getTotalSizeBytes());
        TripRecord r = db.getTrip(id);
        assertEquals(TripDatabase.GRUND_SPEICHER, r.dateiFehltGrund);
        assertEquals("speicher", r.toJson().getString("dateiFehltGrund"));
        assertEquals("Abgleich danach: nichts Neues", 0, db.abgleichFehlendeDateien(p -> true));
        assertEquals(TripDatabase.GRUND_SPEICHER, db.getTrip(id).dateiFehltGrund);
    }

    @Test
    public void alterAufrufNameLoeschtAuchNichtMehr() throws Exception {
        TripDatabase db = new TripDatabase();
        db.initFuerTest("jdbc:h2:mem:altname" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        db.insertTrip(fahrt(5_000L, null));
        db.insertTrip(fahrt(50_000L, new File(tmp.getRoot(), "weg.jsonl.gz").getPath()));
        assertEquals(2, db.deleteRowsWithMissingFiles(p -> true));
        assertEquals(0, db.deleteRowsWithMissingFiles());
        assertEquals(2, db.getTripCount());
    }
}
