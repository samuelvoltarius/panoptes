package at.greenchili.surfmate.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** SD/USB-Dateimanager: kein Pfad-Ausbruch, keine Symlinks, keine Dashcam-Ordner, nur echte Wechselspeicher. */
public class WechselspeicherTest {

    private Path tmp, usb, geheim;
    private Wechselspeicher w;

    @Before
    public void auf() throws Exception {
        tmp = Files.createTempDirectory("wechsel");
        usb = Files.createDirectories(tmp.resolve("usb"));
        geheim = Files.createDirectories(tmp.resolve("geheim"));
        Files.write(geheim.resolve("passwort.txt"), "x".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(usb.resolve("Musik"));
        Files.write(usb.resolve("Musik/lied.mp3"), new byte[]{1, 2, 3});
        Files.createDirectories(usb.resolve("Panoptes/recordings"));
        Files.write(usb.resolve("Panoptes/recordings/clip.mp4"), new byte[]{9});
        Files.createDirectories(usb.resolve("Recorder/Normal"));
        Files.write(usb.resolve("Recorder/Normal/byd.mp4"), new byte[]{9});
        Files.createDirectories(usb.resolve("DCIM/100MEDIA"));
        Files.write(usb.resolve("DCIM/100MEDIA/cam.mp4"), new byte[]{9});
        Files.createDirectories(usb.resolve("DCIM/Fotos"));
        Map<String, File> m = new LinkedHashMap<>();
        m.put(Wechselspeicher.USB, usb.toFile());
        w = new Wechselspeicher(m);
    }

    @After
    public void ab() throws Exception {
        try (Stream<Path> s = Files.walk(tmp)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    @Test
    public void nurEchteWechselspeicherWurzeln() {
        assertTrue(Wechselspeicher.istWechselWurzel("/storage/1A2B-3C4D"));
        assertTrue(Wechselspeicher.istWechselWurzel("/mnt/media_rw/1A2B-3C4D/"));
        for (String p : new String[]{null, "", "/storage/emulated", "/storage/emulated/0", "/storage/self",
                "/storage/self/primary", "/sdcard", "/data/local/tmp", "/system", "/storage/", "/storage/..",
                "/storage/1A2B/../emulated", "/storage/.hidden", "/mnt/media_rw/", "/proc/self/root"}) {
            assertFalse(String.valueOf(p), Wechselspeicher.istWechselWurzel(p));
        }
    }

    @Test
    public void pfadAusbruchIstUnmoeglich() {
        String[] boese = {"..", "../geheim", "../geheim/passwort.txt", "Musik/../../geheim/passwort.txt",
                "/../geheim", "Musik/..", "./Musik", "Musik/./lied.mp3", "Musik\\..\\..\\geheim",
                "Musik//lied.mp3", "Musik/\u0000lied.mp3", ".hidden", "Musik/.x", "C:\\Windows",
                "Musik/lied.mp3:stream", "%2e%2e/geheim"};
        for (String b : boese) {
            assertNull(b, w.liste(Wechselspeicher.USB, b));
            assertEquals(b, -1, w.groesse(Wechselspeicher.USB, b));
            assertNull(b, w.oeffnen(Wechselspeicher.USB, b));
            assertEquals(b, "nicht_erlaubt", w.loeschen(Wechselspeicher.USB, b));
        }
        assertTrue(Files.exists(geheim.resolve("passwort.txt")));
        assertNull("unbekannter Speicher", w.liste("intern", ""));
        assertNull(w.liste(null, ""));
    }

    @Test
    public void symlinksWerdenNieVerfolgt() throws Exception {
        Path link = usb.resolve("abkuerzung");
        Path dateiLink = usb.resolve("Musik/link.txt");
        try {
            Files.createSymbolicLink(link, geheim);
            Files.createSymbolicLink(dateiLink, geheim.resolve("passwort.txt"));
        } catch (Exception e) {
            Assume.assumeNoException("Symlinks hier nicht möglich", e);
        }
        assertNull(w.liste(Wechselspeicher.USB, "abkuerzung"));
        assertNull(w.oeffnen(Wechselspeicher.USB, "abkuerzung/passwort.txt"));
        assertNull(w.oeffnen(Wechselspeicher.USB, "Musik/link.txt"));
        assertEquals("nicht_erlaubt", w.loeschen(Wechselspeicher.USB, "Musik/link.txt"));
        for (Wechselspeicher.Eintrag e : w.liste(Wechselspeicher.USB, "")) assertFalse(e.name, e.name.equals("abkuerzung"));
        for (Wechselspeicher.Eintrag e : w.liste(Wechselspeicher.USB, "Musik")) assertFalse(e.name.equals("link.txt"));
        // Wurzel selbst als Symlink: gesperrt
        Path wurzelLink = tmp.resolve("wurzellink");
        Files.createSymbolicLink(wurzelLink, usb);
        Map<String, File> m = new LinkedHashMap<>();
        m.put(Wechselspeicher.SD, wurzelLink.toFile());
        assertNull(new Wechselspeicher(m).liste(Wechselspeicher.SD, ""));
        assertTrue(Files.exists(geheim.resolve("passwort.txt")));
    }

    @Test
    public void dashcamUndPanoptesOrdnerSindAusgeschlossen() throws Exception {
        List<Wechselspeicher.Eintrag> l = w.liste(Wechselspeicher.USB, "");
        assertNotNull(l);
        for (Wechselspeicher.Eintrag e : l) {
            assertFalse(e.name, e.name.equalsIgnoreCase("Panoptes") || e.name.equalsIgnoreCase("Recorder"));
        }
        assertNull(w.liste(Wechselspeicher.USB, "Panoptes"));
        assertNull(w.liste(Wechselspeicher.USB, "panoptes/recordings"));
        assertNull(w.oeffnen(Wechselspeicher.USB, "Panoptes/recordings/clip.mp4"));
        assertNull(w.oeffnen(Wechselspeicher.USB, "Recorder/Normal/byd.mp4"));
        assertEquals("nicht_erlaubt", w.loeschen(Wechselspeicher.USB, "Recorder/Normal/byd.mp4"));
        assertNull(w.oeffnen(Wechselspeicher.USB, "DCIM/100MEDIA/cam.mp4"));
        for (Wechselspeicher.Eintrag e : w.liste(Wechselspeicher.USB, "DCIM")) assertFalse(e.name.equals("100MEDIA"));
        assertEquals("nicht_erlaubt", w.kopiereHinein(Wechselspeicher.USB, "Panoptes", "a.txt", leer(), 0));
        assertTrue(Files.exists(usb.resolve("Recorder/Normal/byd.mp4")));
    }

    @Test
    public void lesenLoeschenKopieren() throws Exception {
        List<Wechselspeicher.Eintrag> l = w.liste(Wechselspeicher.USB, "Musik");
        assertEquals(1, l.size());
        assertEquals("lied.mp3", l.get(0).name);
        assertEquals(3, w.groesse(Wechselspeicher.USB, "Musik/lied.mp3"));
        try (InputStream in = w.oeffnen(Wechselspeicher.USB, "/Musik/lied.mp3/")) {
            assertNotNull(in);
        }
        byte[] inhalt = "hallo".getBytes(StandardCharsets.UTF_8);
        assertNull(w.kopiereHinein(Wechselspeicher.USB, "Musik", "neu.txt", new ByteArrayInputStream(inhalt), inhalt.length));
        assertEquals("schon_da", w.kopiereHinein(Wechselspeicher.USB, "Musik", "neu.txt",
                new ByteArrayInputStream(inhalt), inhalt.length));
        assertEquals("apk_gesperrt", w.kopiereHinein(Wechselspeicher.USB, "Musik", "x.apk", leer(), 0));
        assertEquals("name_ungueltig", w.kopiereHinein(Wechselspeicher.USB, "Musik", "../x.txt", leer(), 0));
        assertEquals("nicht_erlaubt", w.kopiereHinein(Wechselspeicher.USB, "../geheim", "x.txt", leer(), 0));
        assertNull(w.loeschen(Wechselspeicher.USB, "Musik/neu.txt"));
        // Ordner werden nie gelöscht (keine Sammel-Löschung)
        assertEquals("nicht_erlaubt", w.loeschen(Wechselspeicher.USB, "Musik"));
        assertEquals("nicht_erlaubt", w.loeschen(Wechselspeicher.USB, ""));
        assertTrue(Files.isDirectory(usb.resolve("Musik")));
        assertEquals(Collections.singletonList(Wechselspeicher.USB), w.speicher());
    }

    private static InputStream leer() {
        return new ByteArrayInputStream(new byte[0]);
    }
}
