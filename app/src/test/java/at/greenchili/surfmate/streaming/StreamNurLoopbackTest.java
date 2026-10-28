package at.greenchili.surfmate.streaming;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** 0.1.25: Der H.264-Stream-Server (8987) lauscht nur auf 127.0.0.1; die Brücke meldet v2/LIVE in den Fähigkeiten. */
public class StreamNurLoopbackTest {

    static String lies(String pfad) throws Exception {
        java.io.File f = new java.io.File(pfad);
        if (!f.isFile()) f = new java.io.File("app/" + pfad);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void nurLoopback() throws Exception {
        assertEquals("127.0.0.1", WebSocketStreamServer.LOOPBACK);
        String k = lies("src/main/java/at/greenchili/surfmate/streaming/WebSocketStreamServer.java");
        assertFalse("kein Binden an alle Schnittstellen", k.contains("new InetSocketAddress(PORT)") || k.contains("new InetSocketAddress(port)"));
        assertTrue(k.contains("new InetSocketAddress(LOOPBACK, PORT)") && k.contains("new InetSocketAddress(LOOPBACK, port)"));
    }

    @Test
    public void faehigkeitenMeldenBrueckeV2() throws Exception {
        String k = lies("src/main/java/at/greenchili/surfmate/server/SystemApiHandler.java");
        assertTrue(k.contains("c.put(\"bruecke\", new JSONObject().put(\"innen\", 2).put(\"live\", true).put(\"medienKanal\", true));"));
    }
}
