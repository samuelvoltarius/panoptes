package at.greenchili.surfmate.storage;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/** Dateien Handy ↔ Auto: Pfad-Ausbruch, Symlinks, Größe, APK-Sperre, Teile in Reihenfolge, Löschen einzeln. */
public class DateiablageTest {

    private static final long FREI = 10L * 1024 * 1024 * 1024;   // 10 GB frei
    private Path wurzel;
    private File ordner;
    private Dateiablage a;
    private long now = 1_000_000L;

    @Before
    public void auf() throws Exception {
        wurzel = Files.createTempDirectory("dateiablage");
        ordner = wurzel.resolve("dateien").toFile();
        a = new Dateiablage(ordner);
    }

    @After
    public void ab() throws Exception {
        try (Stream<Path> s = Files.walk(wurzel)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private String hochladen(String name, byte[] inhalt) {
        Dateiablage.Antwort s = a.start(name, inhalt.length, FREI, now);
        assertTrue(s.grund, s.ok());
        int nr = 0;
        for (int off = 0; off < inhalt.length; off += Dateiablage.TEIL_BYTES) {
            int n = Math.min(Dateiablage.TEIL_BYTES, inhalt.length - off);
            byte[] teil = new byte[n];
            System.arraycopy(inhalt, off, teil, 0, n);
            Dateiablage.Antwort t = a.teil(s.wert, nr++, teil, now);
            assertTrue(t.grund, t.ok());
        }
        Dateiablage.Antwort f = a.fertig(s.wert, now);
        assertTrue(f.grund, f.ok());
        return f.wert;
    }

    private static byte[] lies(InputStream in) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        in.close();
        return o.toByteArray();
    }

    @Test
    public void namenOhneAusbruch() {
        for (String boese : new String[] {"../x.txt", "..", ".", "a/b.txt", "a\\b.txt", "/etc/passwd", ".versteckt",
                "x..y", "a\u0000b", "zeile\nneu", "", "   ", null, "C:\\x", "%2e%2e%2fx"}) {
            assertNull(String.valueOf(boese), Dateiablage.name(boese));
        }
        assertEquals("Urlaub 2026 (1).pdf", Dateiablage.name("Urlaub 2026 (1).pdf"));
        assertEquals("Übersicht_ä.txt", Dateiablage.name(" Übersicht_ä.txt "));
        StringBuilder lang = new StringBuilder();
        while (lang.length() < 121) lang.append('a');
        assertNull(Dateiablage.name(lang.toString()));
    }

    @Test
    public void hochladenHolenLoeschen() throws Exception {
        byte[] inhalt = new byte[Dateiablage.TEIL_BYTES * 2 + 17];
        for (int i = 0; i < inhalt.length; i++) inhalt[i] = (byte) i;
        assertEquals("musik.mp3", hochladen("musik.mp3", inhalt));
        assertEquals(1, a.liste().size());
        assertEquals(inhalt.length, a.liste().get(0).groesse);
        assertArrayEquals(inhalt, lies(a.oeffnen("musik.mp3")));
        // gleicher Name: neue Datei, nie überschreiben
        assertEquals("musik (2).mp3", hochladen("musik.mp3", "b".getBytes(StandardCharsets.UTF_8)));
        assertArrayEquals(inhalt, lies(a.oeffnen("musik.mp3")));
        assertTrue(a.loeschen("musik.mp3"));
        assertFalse("zweimal löschen", a.loeschen("musik.mp3"));
        assertEquals("nur genau eine Datei weg", 1, a.liste().size());
        assertFalse(a.loeschen("*"));
        assertFalse(a.loeschen(""));
        assertEquals(1, a.liste().size());
    }

    @Test
    public void groesseHoechstens200MB() {
        assertEquals("zu_gross", a.start("gross.bin", Dateiablage.MAX_BYTES + 1, FREI, now).grund);
        assertTrue(a.start("genau.bin", Dateiablage.MAX_BYTES, FREI, now).ok());
        assertEquals("leer", a.start("leer.bin", 0, FREI, now).grund);
        assertEquals("kein_platz", a.start("x.bin", 1000, Dateiablage.RESERVE_BYTES + 10, now).grund);
    }

    @Test
    public void angesagteGroesseWirdNieUeberschritten() {
        Dateiablage.Antwort s = a.start("klein.txt", 10, FREI, now);
        assertTrue(a.teil(s.wert, 0, new byte[8], now).ok());
        assertEquals("zu_gross", a.teil(s.wert, 1, new byte[8], now).grund);
        assertEquals("Upload verworfen", "unbekannt", a.fertig(s.wert, now).grund);
        assertTrue(a.liste().isEmpty());
        assertEquals("keine Reste", 0, ordner.listFiles().length);
    }

    @Test
    public void teileInFesterReihenfolgeUndGrenze() {
        Dateiablage.Antwort s = a.start("x.txt", 100, FREI, now);
        assertEquals("reihenfolge", a.teil(s.wert, 1, new byte[5], now).grund);
        assertEquals("teil_zu_gross", a.teil(s.wert, 0, new byte[Dateiablage.TEIL_BYTES + 1], now).grund);
        assertTrue(a.teil(s.wert, 0, new byte[50], now).ok());
        assertEquals("unvollstaendig", a.fertig(s.wert, now).grund);
        assertEquals("unbekannt", a.teil("../../etc", 0, new byte[1], now).grund);
        assertEquals("unbekannt", a.teil("0000000000000000", 0, new byte[1], now).grund);
        assertTrue(a.abbrechen(s.wert));
        assertTrue(a.liste().isEmpty());
    }

    @Test
    public void apkWirdNieAngenommen() {
        for (String n : new String[] {"spiel.apk", "SPIEL.APK", "x.apks", "x.xapk", "x.apkm", "x.aab"}) {
            assertEquals(n, "apk_gesperrt", a.start(n, 100, FREI, now).grund);
        }
        assertTrue(a.liste().isEmpty());
        assertTrue(a.start("apk-anleitung.pdf", 100, FREI, now).ok());
    }

    @Test
    public void halbfertigeTeileSindUnsichtbarUndVerfallen() throws Exception {
        Dateiablage.Antwort s = a.start("x.txt", 100, FREI, now);
        assertTrue(a.teil(s.wert, 0, new byte[10], now).ok());
        assertTrue("Teil nicht in der Liste", a.liste().isEmpty());
        assertNull("Teil nicht holbar", a.oeffnen(".teil-" + s.wert));
        assertFalse("Teil nicht löschbar über den Namen", a.loeschen(".teil-" + s.wert));
        a.aufraeumen(now + Dateiablage.UPLOAD_LEBEN_MS + 1);
        assertEquals("unbekannt", a.teil(s.wert, 1, new byte[1], now).grund);
        assertEquals(0, ordner.listFiles().length);
    }

    @Test
    public void hoechstensDreiGleichzeitig() {
        for (int i = 0; i < Dateiablage.MAX_UPLOADS; i++) assertTrue(a.start("d" + i + ".txt", 10, FREI, now).ok());
        assertEquals("zu_viele", a.start("d9.txt", 10, FREI, now).grund);
    }

    @Test
    public void symlinksWerdenNieGelesenOderGeloescht() throws Exception {
        Path draussen = wurzel.resolve("geheim.txt");
        Files.write(draussen, "geheim".getBytes(StandardCharsets.UTF_8));
        assertTrue(a.liste().isEmpty());   // legt den Ordner an
        try {
            Files.createSymbolicLink(ordner.toPath().resolve("link.txt"), draussen);
        } catch (Exception e) {
            Assume.assumeTrue("Symlinks hier nicht möglich (Windows ohne Recht)", false);
        }
        assertTrue("Symlink nicht in der Liste", a.liste().isEmpty());
        assertNull("Symlink nicht holbar", a.oeffnen("link.txt"));
        assertFalse("Symlink nicht löschbar", a.loeschen("link.txt"));
        assertTrue("Ziel unberührt", Files.exists(draussen));
        // fertige Datei landet nie über einen vorhandenen Symlink
        assertEquals("link (2).txt", hochladen("link.txt", "neu".getBytes(StandardCharsets.UTF_8)));
        assertEquals("geheim", new String(Files.readAllBytes(draussen), StandardCharsets.UTF_8));
    }

    @Test
    public void ordnerAlsSymlinkWirdAbgelehnt() throws Exception {
        Path ziel = Files.createDirectory(wurzel.resolve("woanders"));
        Path link = wurzel.resolve("dateien-link");
        try {
            Files.createSymbolicLink(link, ziel);
        } catch (Exception e) {
            Assume.assumeTrue("Symlinks hier nicht möglich (Windows ohne Recht)", false);
        }
        Dateiablage b = new Dateiablage(link.toFile());
        assertEquals("ordner", b.start("x.txt", 10, FREI, now).grund);
        assertTrue(b.liste().isEmpty());
        assertNull(b.oeffnen("x.txt"));
    }

    @Test
    public void unterordnerSindKeineDateien() throws Exception {
        assertTrue(a.liste().isEmpty());
        assertNotNull(Files.createDirectory(ordner.toPath().resolve("unter")));
        assertTrue(a.liste().isEmpty());
        assertNull(a.oeffnen("unter"));
        assertFalse(a.loeschen("unter"));
    }
}
