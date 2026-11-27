package at.greenchili.surfmate.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

/**
 * 0.1.43.22 (Upstream OverDrive #412): Der Request-Body reserviert vorab höchstens den Lese-Block (4096 Zeichen),
 * nicht die vom Client behauptete Content-Length (vor jeder Anmeldung).
 */
public class HttpBodySpeicherTest {

    @Test
    public void bodyPufferStartetHoechstensMitDemLeseBlock() throws Exception {
        String s = new String(Files.readAllBytes(
                new File("src/main/java/at/greenchili/surfmate/server/HttpServer.java").toPath()),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertFalse("keine Vorab-Reservierung der Content-Length", s.contains("new StringBuilder(contentLength)"));
        int chunk = s.indexOf("int CHUNK = Math.min(contentLength, 4096);");
        int sb = s.indexOf("StringBuilder sb = new StringBuilder(CHUNK);");
        int lesen = s.indexOf("int read = reader.read(bodyChars, 0, CHUNK);");
        assertTrue(chunk > 0 && chunk < sb && sb < lesen);
    }
}
