package at.greenchili.surfmate.config;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** 0.1.20 Tempo: unveränderte Config wird bei abgelaufener 1-s-Frist per CRC32 bestätigt statt neu geparst. */
public class ConfigInhaltTest {

    @Test
    public void gleicheBytesGleich_gleichLangeAenderungErkannt() {
        byte[] a = "{\"recording\":{\"targetFps\":15}}".getBytes(StandardCharsets.UTF_8);
        byte[] b = "{\"recording\":{\"targetFps\":30}}".getBytes(StandardCharsets.UTF_8);   // gleiche Länge
        assertTrue(ConfigInhalt.unveraendert(ConfigInhalt.crc(a), ConfigInhalt.crc(a.clone())));
        assertNotEquals(ConfigInhalt.crc(a), ConfigInhalt.crc(b));
        assertFalse(ConfigInhalt.unveraendert(ConfigInhalt.crc(b), ConfigInhalt.crc(a)));
        assertFalse("unbekannt (0) gilt nie als gleich", ConfigInhalt.unveraendert(ConfigInhalt.crc(a), 0L));
        assertNotEquals(0L, ConfigInhalt.crc(new byte[0]));
    }

    @Test
    public void ladenPrueftZuerstDieFristDannDieBytesUndStempeltNurPassendeMtime() throws Exception {
        String s = lies("app/src/main/java/at/greenchili/surfmate/config/UnifiedConfigManager.kt");
        int load = s.indexOf("fun loadConfig(): JSONObject");
        int fresh = s.indexOf("if (isCacheFresh(configFile))", load);
        int crc = s.indexOf("if (revalidateByContent(configFile))", load);
        int voll = s.indexOf("loadConfigUnderFileLock(configFile)", load);
        assertTrue(load > 0 && fresh > load && crc > fresh && voll > crc);
        int m = s.indexOf("private fun revalidateByContent(");
        String r = s.substring(m, s.indexOf("\n    }", m));
        assertTrue("nur zum gestempelten mtime", r.contains("contentCrcMtime.get() != stamped"));
        assertTrue("gleiche Sekunde bleibt Neuladen", r.contains("(System.currentTimeMillis() / 1000L) <= (stamped / 1000L)"));
        assertTrue(r.contains("lastFreshnessCheckNanos.set(System.nanoTime())"));
        assertTrue(s.contains("lastModified.get() == vorher[1]"));
    }

    private static String lies(String rel) throws Exception {
        Path p = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (p != null) {
            for (Path c : new Path[] {p.resolve(rel), p.resolve(rel.replaceFirst("^app/", ""))}) {
                if (Files.isRegularFile(c)) return new String(Files.readAllBytes(c), "UTF-8");
            }
            p = p.getParent();
        }
        throw new java.io.FileNotFoundException(rel);
    }
}
