package at.greenchili.surfmate.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.testutil.Quelltext;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Paths;

/** USB/SD weg → intern aufnehmen mit Deckel und Hinweis, nie still (0.1.10, Koordination 22.10.). */
public class SpeicherRueckfallTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void deckelEinViertelDesInternenSpeichers() {
        long intern = 32L * 1024 * 1024 * 1024;   // 32 GB
        assertEquals(8L * 1024 * 1024 * 1024, StorageManager.rueckfallDeckelBytes(intern, 100));
        assertEquals("kleiner Speicher: mindestens MIN_LIMIT", 100L * 1024 * 1024,
                StorageManager.rueckfallDeckelBytes(200L * 1024 * 1024, 100));
        assertEquals("unlesbar: nicht schrumpfen", Long.MAX_VALUE, StorageManager.rueckfallDeckelBytes(0, 100));
    }

    @Test
    public void anzahlRueckfallAufnahmen() throws Exception {
        File d = tmp.newFolder("rec");
        new File(d, "a.mp4").createNewFile();
        new File(d, "b.mp4").createNewFile();
        new File(d, "a.json").createNewFile();
        assertEquals(2, StorageManager.videoAnzahl(d));
        assertEquals(0, StorageManager.videoAnzahl(null));
    }

    @Test
    public void verdrahtet() throws Exception {
        String s = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/storage/StorageManager.java"));
        // Prüftor und Reaper nutzen denselben Rückfall-Deckel
        assertEquals(2, s.split("rueckfallLimitBytes\\(configuredMb\\)", -1).length - 1);
        assertTrue(s.contains("\"speicher_rueckfall\"") && s.contains("\"rueckfall_aufnahmen\""));
        String e = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/EinrichtungApiHandler.java"));
        assertTrue(e.contains("rueckfallStatus()"));
    }
}
