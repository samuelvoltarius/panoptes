package at.greenchili.surfmate.storage;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 0.1.11 (Kern 7): Rückfall-Aufnahmen kopieren → prüfen → löschen, echte Dateien. */
public class RueckfallVerschieberTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final long JETZT = 10_000_000L;

    private final List<String> neu = new ArrayList<>();
    private final List<String> weg = new ArrayList<>();

    private RueckfallVerschieber.Index index() {
        return new RueckfallVerschieber.Index() {
            @Override public void neu(File mp4) { neu.add(mp4.getName()); }
            @Override public void weg(String alterPfad) { weg.add(new File(alterPfad).getName()); }
        };
    }

    private static RueckfallVerschieber.Umgebung env(long frei) {
        return new RueckfallVerschieber.Umgebung() {
            @Override public long freiBytes(File z) { return frei; }
            @Override public boolean weiter() { return true; }
            @Override public long jetzt() { return JETZT; }
        };
    }

    private static File datei(File dir, String name, int groesse, long alter) throws Exception {
        File f = new File(dir, name);
        byte[] b = new byte[groesse];
        for (int i = 0; i < b.length; i++) b[i] = (byte) (name.hashCode() + i);
        Files.write(f.toPath(), b);
        assertTrue(f.setLastModified(JETZT - alter));
        return f;
    }

    @Test
    public void verschiebtMitBegleitdateienUndIndex() throws Exception {
        File q = tmp.newFolder("intern");
        File z = tmp.newFolder("usb");
        long alt = 3_600_000L;
        datei(q, "event_1.mp4", 5000, alt);
        datei(q, "event_1.json", 100, alt);
        datei(q, "event_1.jpg", 80, alt);
        datei(q, "thumb_event_1_a1_0.jpg", 50, alt);
        datei(q, "event_12.mp4", 3000, alt);
        datei(q, "event_12.srt", 30, alt);
        byte[] vorher = Files.readAllBytes(new File(q, "event_1.mp4").toPath());

        RueckfallVerschieber.Ergebnis e = RueckfallVerschieber.verschieben(q, z, ".mp4", index(), env(Long.MAX_VALUE / 2));
        assertEquals(e.toString(), 2, e.verschoben);
        assertEquals(0, e.fehler);
        assertNull(e.abbruch);
        assertEquals(0, q.listFiles().length);
        assertEquals(6, z.listFiles().length);
        assertArrayEquals(vorher, Files.readAllBytes(new File(z, "event_1.mp4").toPath()));
        assertTrue(new File(z, "thumb_event_1_a1_0.jpg").isFile());
        assertTrue(neu.contains("event_1.mp4") && neu.contains("event_12.mp4"));
        assertTrue(weg.contains("event_1.mp4") && weg.contains("event_12.mp4"));
    }

    @Test
    public void gruppierenTrenntAehnlicheNamen() throws Exception {
        File q = tmp.newFolder("g");
        datei(q, "cam_1.mp4", 1, 0);
        datei(q, "cam_1.json", 1, 0);
        datei(q, "cam_12.mp4", 1, 0);
        datei(q, "cam_12.json", 1, 0);
        datei(q, "cam_12.mp4.tmp", 1, 0);
        Map<String, List<File>> g = RueckfallVerschieber.gruppieren(q.listFiles(), ".mp4");
        assertEquals(2, g.get("cam_1").size());
        assertEquals(2, g.get("cam_12").size());
    }

    @Test
    public void frischeAufnahmeBleibt() throws Exception {
        File q = tmp.newFolder("intern2");
        File z = tmp.newFolder("usb2");
        datei(q, "cam_9.mp4", 100, 10_000L);   // 10 s alt: könnte noch geschrieben werden
        RueckfallVerschieber.Ergebnis e = RueckfallVerschieber.verschieben(q, z, ".mp4", index(), env(Long.MAX_VALUE / 2));
        assertEquals(1, e.uebersprungen);
        assertTrue(new File(q, "cam_9.mp4").isFile());
        assertEquals(0, z.listFiles().length);
    }

    @Test
    public void anderesZielWirdNieUeberschrieben() throws Exception {
        File q = tmp.newFolder("intern3");
        File z = tmp.newFolder("usb3");
        datei(q, "cam_5.mp4", 100, 3_600_000L);
        Files.write(new File(z, "cam_5.mp4").toPath(), new byte[] {9, 9, 9});
        RueckfallVerschieber.Ergebnis e = RueckfallVerschieber.verschieben(q, z, ".mp4", index(), env(Long.MAX_VALUE / 2));
        assertEquals(1, e.konflikte);
        assertTrue("Original bleibt", new File(q, "cam_5.mp4").isFile());
        assertEquals(3, new File(z, "cam_5.mp4").length());
        assertTrue(weg.isEmpty());
    }

    @Test
    public void gleichesZielGiltAlsKopiert() throws Exception {
        File q = tmp.newFolder("intern4");
        File z = tmp.newFolder("usb4");
        File f = datei(q, "cam_6.mp4", 100, 3_600_000L);
        Files.copy(f.toPath(), new File(z, "cam_6.mp4").toPath());
        RueckfallVerschieber.Ergebnis e = RueckfallVerschieber.verschieben(q, z, ".mp4", index(), env(Long.MAX_VALUE / 2));
        assertEquals(1, e.verschoben);
        assertFalse(f.exists());
        assertEquals(100, new File(z, "cam_6.mp4").length());
    }

    @Test
    public void keinPlatzBrichtAbOhneEtwasZuLoeschen() throws Exception {
        File q = tmp.newFolder("intern5");
        File z = tmp.newFolder("usb5");
        datei(q, "cam_7.mp4", 100, 3_600_000L);
        RueckfallVerschieber.Ergebnis e = RueckfallVerschieber.verschieben(q, z, ".mp4", index(),
                env(RueckfallVerschieber.RESERVE_BYTES + 50));
        assertEquals("kein_platz", e.abbruch);
        assertTrue(new File(q, "cam_7.mp4").isFile());
        assertEquals(0, z.listFiles().length);
    }

    @Test
    public void gleicherOrdnerWirdAbgelehnt() throws Exception {
        File q = tmp.newFolder("same");
        datei(q, "cam_8.mp4", 10, 3_600_000L);
        assertEquals("ziel_fehlt", RueckfallVerschieber.verschieben(q, q, ".mp4", index(), env(Long.MAX_VALUE / 2)).abbruch);
        assertTrue(new File(q, "cam_8.mp4").isFile());
    }
}
