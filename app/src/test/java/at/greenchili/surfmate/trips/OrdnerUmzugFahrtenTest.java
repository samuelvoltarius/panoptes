package at.greenchili.surfmate.trips;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.storage.AppOrdner;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;

/**
 * 0.1.10.2 (Alfreds Auto 22.10.): Bestand mit Fahrten + Umbenennung SurfMate → Panoptes + Abgleich = 0 Verluste,
 * zweiter Lauf idempotent. Echte H2-Datenbank (Speicher), echte Dateien.
 */
public class OrdnerUmzugFahrtenTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void umbenennungUndAbgleichVerlierenKeineFahrt() throws Exception {
        File vol = tmp.newFolder("usb");
        File tripsAlt = new File(vol, "SurfMate/trips");
        assertTrue(tripsAlt.mkdirs());
        TripDatabase db = new TripDatabase();
        db.initFuerTest("jdbc:h2:mem:umzug" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");

        long[] ids = new long[5];
        for (int i = 0; i < 5; i++) {
            File f = new File(tripsAlt, (1_000L + i) + ".jsonl.gz");
            Files.write(f.toPath(), new byte[] {1, 2, 3});
            TripRecord t = new TripRecord();
            t.startTime = 1_000L + i;
            t.endTime = 2_000L + i;
            t.distanceKm = 10 + i;
            t.socStart = 80; t.socEnd = 70;
            t.telemetryFilePath = f.getPath();
            ids[i] = db.insertTrip(t);
            assertTrue(ids[i] > 0);
        }

        assertEquals(AppOrdner.Ergebnis.UMBENANNT, AppOrdner.umziehen(vol.getPath(), AppOrdner.ECHT));

        // Start nach dem Update: Pfade in EINER Transaktion reparieren, vor jedem Abgleich; zweiter Lauf: nichts mehr
        assertEquals(5, db.pfadeReparierenFuerTest());
        assertEquals(0, db.pfadeReparierenFuerTest());
        // Abgleich wie im Daemon (Volume verfügbar): KEINE Fahrt darf verschwinden
        assertEquals(0, db.deleteRowsWithMissingFiles(p -> true));
        assertEquals(0, db.deleteRowsWithMissingFiles(p -> true));   // zweiter Lauf: idempotent
        for (long id : ids) {
            TripRecord t = db.getTrip(id);
            assertNotNull("Fahrt " + id + " verloren", t);
            assertTrue(t.telemetryFilePath, t.telemetryFilePath.contains(File.separator + "Panoptes" + File.separator)
                    || t.telemetryFilePath.contains("/Panoptes/"));
            assertTrue(new File(t.telemetryFilePath).isFile());
            assertEquals("Messwerte unverändert", 80, t.socStart, 0);
        }
        assertEquals(5, db.getTripCount());
    }

    @Test
    public void alterPfadOhneDateiWirdNieGeloescht() throws Exception {
        File vol = tmp.newFolder("intern");
        TripDatabase db = new TripDatabase();
        db.initFuerTest("jdbc:h2:mem:alt" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        TripRecord t = new TripRecord();
        t.startTime = 5L; t.endTime = 6L; t.distanceKm = 1;
        t.telemetryFilePath = new File(vol, "SurfMate/trips/5.jsonl.gz").getPath().replace(File.separatorChar, '/');
        long id = db.insertTrip(t);
        assertEquals(0, db.deleteRowsWithMissingFiles(p -> true));
        assertNotNull(db.getTrip(id));
    }

    private TripDatabase dbMitFahrten(File dir, int n, long[] ids) throws Exception {
        TripDatabase db = new TripDatabase();
        db.initFuerTest("jdbc:h2:mem:abgleich" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        for (int i = 0; i < n; i++) {
            File f = new File(dir, (100L + i) + ".jsonl.gz");
            Files.write(f.toPath(), new byte[] {1});
            TripRecord t = new TripRecord();
            t.startTime = 100L + i; t.endTime = 200L + i; t.distanceKm = 1;
            t.telemetryFilePath = f.getPath();
            ids[i] = db.insertTrip(t);
        }
        return db;
    }

    @Test
    public void usbNichtEingehaengtLoeschtNichts() throws Exception {
        File usb = tmp.newFolder("usbweg", "Panoptes", "trips");
        long[] ids = new long[10];
        TripDatabase db = dbMitFahrten(usb, 10, ids);
        for (File f : usb.listFiles()) assertTrue(f.delete());   // USB abgezogen: Dateien „weg“
        assertEquals(0, db.deleteRowsWithMissingFiles(p -> false));
        assertEquals(10, db.getTripCount());
    }

    @Test
    public void vieleFehlendeAufEinmalSindSpeicherproblem() throws Exception {
        File dir = tmp.newFolder("intern2", "Panoptes", "trips");
        long[] ids = new long[20];
        TripDatabase db = dbMitFahrten(dir, 20, ids);
        File[] fs = dir.listFiles();
        for (int i = 0; i < 4; i++) assertTrue(fs[i].delete());   // 4 > 3 → Verdacht: 0.1.11 nur markieren
        assertEquals(4, db.abgleichFehlendeDateien(p -> true));
        assertEquals(20, db.getTripCount());
        assertEquals(4, db.getAnzahlDateiFehlt());
    }

    @Test
    public void einzelneFehlendeDateiWirdNurMarkiert() throws Exception {
        File dir = tmp.newFolder("intern3", "Panoptes", "trips");
        long[] ids = new long[20];
        TripDatabase db = dbMitFahrten(dir, 20, ids);
        assertTrue(new File(db.getTrip(ids[0]).telemetryFilePath).delete());
        assertEquals(1, db.abgleichFehlendeDateien(p -> true));
        assertEquals("0.1.11: nie löschen", 20, db.getTripCount());
        assertTrue(db.getTrip(ids[0]).dateiFehltSeit > 0);
        assertEquals("zweiter Lauf: nichts Neues", 0, db.abgleichFehlendeDateien(p -> true));
    }

    @Test
    public void regelGrenzen() {
        assertTrue(TripDatabase.fehlendeLoeschenErlaubt(0, 0));
        assertTrue(TripDatabase.fehlendeLoeschenErlaubt(1, 5));
        assertTrue(TripDatabase.fehlendeLoeschenErlaubt(3, 15));
        assertFalse(TripDatabase.fehlendeLoeschenErlaubt(4, 100));    // > 3
        assertFalse(TripDatabase.fehlendeLoeschenErlaubt(2, 5));      // 40 % > 20 %
        assertFalse(TripDatabase.fehlendeLoeschenErlaubt(1, 0));
    }
}
