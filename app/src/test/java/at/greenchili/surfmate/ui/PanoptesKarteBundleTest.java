package at.greenchili.surfmate.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;

import org.junit.Test;

/**
 * Das gemeinsame Kartenmodul (Karten-Sitzung, Vertrag /tiles/lib/README.md) wird als
 * unveränderliche Kopie gebündelt. Weicht die Datei von der veröffentlichten Prüfsumme ab,
 * soll der Test (und damit die Prüfung vor jeder Übergabe) scheitern.
 */
public class PanoptesKarteBundleTest {

    private static final String DATEI = "src/main/assets/web/shared/vendor/panoptes-karte-1.1.1.js";
    private static final String SHA256 = "9956ae2f065758f3a7d6a7befd1a3bcf81ca0b351dd8313dacefad15a3e8aebf";

    @Test
    public void gebuendeltesModulHatVeroeffentlichtePruefsumme() throws Exception {
        File f = new File(DATEI);
        if (!f.exists()) f = new File("app/" + DATEI);
        assertTrue("Modul fehlt: " + DATEI, f.exists());
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f.toPath()));
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) hex.append(String.format("%02x", b));
        assertEquals(SHA256, hex.toString());
    }
}
