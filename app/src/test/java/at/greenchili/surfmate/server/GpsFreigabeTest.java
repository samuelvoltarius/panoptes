package at.greenchili.surfmate.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** 0.1.20 (Regel 2): /api/gps für Kopplungen nur mit ha.positionTeilen, und nie GPS einschalten. */
public class GpsFreigabeTest {

    @After
    public void zurueck() {
        GpsFreigabe.kopplungQuelle = AuthMiddleware::isExternRequest;
        GpsFreigabe.freigabeQuelle = HaApiHandler::positionErlaubt;
    }

    @Test
    public void fehlerZaehlenAlsKopplungOhneFreigabe() {
        GpsFreigabe.kopplungQuelle = () -> { throw new IllegalStateException("x"); };
        GpsFreigabe.freigabeQuelle = () -> { throw new IllegalStateException("x"); };
        assertTrue(GpsFreigabe.kopplung());
        assertFalse(GpsFreigabe.erlaubt());
        GpsFreigabe.kopplungQuelle = () -> false;
        GpsFreigabe.freigabeQuelle = () -> true;
        assertFalse(GpsFreigabe.kopplung());
        assertTrue(GpsFreigabe.erlaubt());
    }

    @Test
    public void sperreVorDemEinschaltenUndEinschaltenNieFuerKopplungen() throws Exception {
        String s = lies("app/src/main/java/at/greenchili/surfmate/server/GpsApiHandler.java");
        int m = s.indexOf("private static void sendGpsLocation(OutputStream out)");
        int sperre = s.indexOf("if (kopplung && !GpsFreigabe.erlaubt())", m);
        int start = s.indexOf("gps.start();", m);
        assertTrue(m > 0 && sperre > m && start > sperre);
        assertTrue(s.contains("if (!kopplung && !gps.isRunning()) {"));
        assertTrue(s.contains("position_nicht_freigegeben"));
        String ex = lies("app/src/main/java/at/greenchili/surfmate/server/ExternGuard.java");
        assertFalse("Starten bleibt für Kopplungen gesperrt", ExternGuard.erlaubt("POST", "/api/gps/start"));
        assertTrue(ex.contains("\"/api/gps\""));
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
