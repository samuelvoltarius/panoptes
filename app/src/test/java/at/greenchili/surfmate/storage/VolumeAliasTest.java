package at.greenchili.surfmate.storage;

import static org.junit.Assert.assertEquals;

import at.greenchili.surfmate.trips.TripDatabaseTestZugang;

import org.junit.Test;

/** 0.1.11 (Alfreds Auto): Aliase wie /sdcard oder /storage/self/primary gleichsetzen; Präfix fürs Protokoll. */
public class VolumeAliasTest {

    @Test
    public void bekannteAliaseAufIntern() {
        assertEquals("/storage/emulated/0/Panoptes/trips/a.jsonl.gz",
                StorageManager.kanonischerPfad("/sdcard/Panoptes/trips/a.jsonl.gz"));
        assertEquals("/storage/emulated/0/Panoptes/trips/a.jsonl.gz",
                StorageManager.kanonischerPfad("/storage/self/primary/Panoptes/trips/a.jsonl.gz"));
        assertEquals("/storage/emulated/0/x", StorageManager.kanonischerPfad("/mnt/sdcard/x"));
    }

    @Test
    public void praefixOhneDateiname() {
        assertEquals("/storage/usbotg/SurfMate/trips",
                TripDatabaseTestZugang.praefix("/storage/usbotg/SurfMate/trips/1729.jsonl.gz"));
        assertEquals("/storage/C093-6A86/Panoptes/trips",
                TripDatabaseTestZugang.praefix("/storage/C093-6A86/Panoptes/trips/x.jsonl.gz"));
    }
}
