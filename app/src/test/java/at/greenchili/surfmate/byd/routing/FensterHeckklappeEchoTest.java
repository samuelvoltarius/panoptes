package at.greenchili.surfmate.byd.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.byd.BydDataCollector;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;

/** 0.1.18 (Koordination): Echo für Fenster und Heckklappe wie bei Sitz/Klima – keine Wirkung = Fehlschlag. */
public class FensterHeckklappeEchoTest {

    static String lies(String rel) throws Exception {
        File f = new File(rel);
        if (!f.isFile()) f = new File("app/" + rel);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    public void echoOkAusUnbekannt() {
        AtomicInteger n = new AtomicInteger();
        // erreicht nach dem dritten Lesen
        assertEquals(BydDataCollector.ECHO_OK,
                BydDataCollector.warteAufEcho(() -> n.incrementAndGet() >= 3 ? 1 : 0, 1, 2_000, 10));
        // lesbar, aber nie erreicht
        assertEquals(BydDataCollector.ECHO_AUS, BydDataCollector.warteAufEcho(() -> 0, 1, 60, 10));
        // nie lesbar
        assertEquals(BydDataCollector.ECHO_UNBEKANNT, BydDataCollector.warteAufEcho(() -> -1, 1, 60, 10));
        // Leser wirft → wie nicht lesbar
        assertEquals(BydDataCollector.ECHO_UNBEKANNT, BydDataCollector.warteAufEcho(() -> {
            throw new IllegalStateException("HAL");
        }, 0, 60, 10));
    }

    @Test
    public void mitEchoMeldetFehlschlagNurBeiLesbarOhneWirkung() {
        assertFalse("nicht gesendet", VehicleCommandRouter.mitEcho("t", false, () -> 1, 1, 50));
        assertTrue("Wirkung da", VehicleCommandRouter.mitEcho("t", true, () -> 1, 1, 50));
        assertFalse("keine Wirkung", VehicleCommandRouter.mitEcho("trunk-open", true, () -> 0, 1, 50));
        assertTrue("nicht lesbar: HAL glauben", VehicleCommandRouter.mitEcho("t", true, () -> -1, 1, 50));
    }

    @Test
    public void sammelZustandDerFenster() {
        assertEquals(-1, invoke(0, 0));
        assertEquals(1, invoke(4, 0));
        assertEquals(0, invoke(0, 4));
        assertEquals(2, invoke(3, 1));
        assertEquals(0, invoke(0, 2));   // zwei nicht lesbar, zwei zu → zu
    }

    private static int invoke(int offen, int zu) {
        try {
            java.lang.reflect.Method m = BydDataCollector.class.getDeclaredMethod("sammelZustand", int.class, int.class);
            m.setAccessible(true);
            return (Integer) m.invoke(null, offen, zu);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void befehlePruefenDieWirkung() throws Exception {
        String r = lies("src/main/java/at/greenchili/surfmate/byd/routing/VehicleCommandRouter.java");
        assertTrue(r.contains("mitEcho(name(), c.setAllWindowsCommand(2), c::readAllSideWindowsOpenState, 0,"));
        assertTrue(r.contains("mitEcho(name(), c.setAllWindowsCommand(1), c::readAllSideWindowsOpenState, 1,"));
        assertTrue(r.contains("mitEcho(name(), c.moveAllSideWindowsToPercent(LOCAL_VENT_PERCENT),"));
        assertEquals("Heckklappe auf (neu + alt)", 2,
                r.split("mitEcho\\(name\\(\\), c.openTailgate\\(\\), c::readTrunkOpenState, 1,", -1).length - 1);
        assertTrue(r.contains("mitEcho(name(), c.closeTailgate(), c::readTrunkOpenState, 0,"));
        assertTrue(r.contains("mitEcho(name(), ok, () -> c.readWindowOpenState(fenster), sollZustand,"));
        // Schloss-Prüfung vor dem Öffnen bleibt
        assertTrue(r.contains("if (c.readDoorLockState() != BydDataCollector.DOOR_STATE_UNLOCK) return false;"));
        String k = lies("src/main/java/at/greenchili/surfmate/byd/BydDataCollector.java");
        assertTrue(k.contains("readDoorOpenState(BodyworkConstants.AREA_TRUNK,"));
    }
}
