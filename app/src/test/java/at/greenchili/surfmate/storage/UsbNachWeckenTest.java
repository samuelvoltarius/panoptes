package at.greenchili.surfmate.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 0.1.25 USB (WAECHTER_1849_ANALYSE §4): erster sm mount nach dem Wecken 20 s, USB-Pfad nach Erfolg erzwingen. */
public class UsbNachWeckenTest {

    @Test
    public void ersterMountNachDemWeckenDarfZwanzigSekunden() {
        long weck = 1_000_000L;
        assertEquals(20_000, StorageManager.smMountTimeoutMs(weck + 9_000, weck, 0));
        assertEquals("nach einem Erfolg wieder 8 s", 8_000, StorageManager.smMountTimeoutMs(weck + 30_000, weck, weck + 20_000));
        assertEquals("nach 2 min wieder 8 s", 8_000, StorageManager.smMountTimeoutMs(weck + 121_000, weck, 0));
    }

    @Test
    public void verdrahtung() throws Exception {
        String s = lies("app/src/main/java/at/greenchili/surfmate/storage/StorageManager.java");
        assertTrue(s.contains("long mountTimeoutMs = smMountTimeoutMs(System.currentTimeMillis(), weckZeitMs, letzterMountErfolgMs);"));
        assertFalse("kein Rückfall auf intern nach erfolgreichem Einhängen", s.contains("? usbRecordingsDir : rUsb.dir;"));
        assertTrue(s.contains("java.io.File newRecordingsDir = usbRecordingsDir;"));
        assertTrue("Pfad wird nachgezogen", s.contains("if (watchUsb && usbPfadNachziehen && isUsbLikelyMounted())"));
        int acc = s.indexOf("ACC ON: force-remounting USB drive");
        assertTrue("kein zweiter sm mount parallel", s.lastIndexOf("mountLock.isLocked() && !mountLock.isHeldByCurrentThread()", acc) > 0
                && acc - s.lastIndexOf("mountLock.isLocked() && !mountLock.isHeldByCurrentThread()", acc) < 1500);
    }

    private static String lies(String rel) throws Exception {
        java.nio.file.Path p = java.nio.file.Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (p != null) {
            for (java.nio.file.Path c : new java.nio.file.Path[] {p.resolve(rel), p.resolve(rel.replaceFirst("^app/", ""))}) {
                if (java.nio.file.Files.isRegularFile(c)) return new String(java.nio.file.Files.readAllBytes(c), "UTF-8");
            }
            p = p.getParent();
        }
        throw new java.io.FileNotFoundException(rel);
    }
}
