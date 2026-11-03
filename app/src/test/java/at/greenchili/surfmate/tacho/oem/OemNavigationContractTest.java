package at.greenchili.surfmate.tacho.oem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import at.greenchili.surfmate.tacho.TachoFaehigkeit;

public class OemNavigationContractTest {
    @Test
    public void valhallaMappingUsesFirmwareIdsAndNeverZero() {
        assertEquals(2, OemTurnIcon.fromValhalla(15));
        assertEquals(3, OemTurnIcon.fromValhalla(11));
        assertEquals(8, OemTurnIcon.fromValhalla(14));
        assertEquals(19, OemTurnIcon.fromValhalla(13));
        assertEquals(15, OemTurnIcon.fromValhalla(4));
        assertEquals(11, OemTurnIcon.fromValhalla(26));
        assertEquals(9, OemTurnIcon.fromValhalla(999));
        assertEquals(7, OemTurnIcon.roundaboutExit(26, 7));
        assertEquals(0, OemTurnIcon.roundaboutExit(11, 7));
        assertEquals(0, OemTurnIcon.roundaboutExit(26, 11));
    }

    @Test
    public void payloadRoundTripsOemFieldsAndClearUsesRealSentinels() {
        byte[] frame = NaviInfoPayload.build(1, "", 125, 3, 900, 12_345, 0);
        NaviInfo f = NaviInfo.getRootAsNaviInfo(ByteBuffer.wrap(frame));
        assertEquals(1, f.naviState());
        assertEquals(125, f.curToSegmentDist());
        assertEquals(3, f.nextTurnIcon());
        assertEquals(900, f.routeRemainTime());
        assertEquals(12_345, f.routeRemainDist());

        byte[] clear = NaviInfoPayload.build(9, "", -1, -1, -1, -1, 0);
        NaviInfo c = NaviInfo.getRootAsNaviInfo(ByteBuffer.wrap(clear));
        assertEquals(9, c.naviState());
        assertEquals(-1, c.curToSegmentDist());
        assertEquals(-1, c.nextTurnIcon());
        assertEquals(-1, c.routeRemainTime());
        assertEquals(-1, c.routeRemainDist());
    }

    @Test
    public void capabilityIsFailClosedForSurfDi5UnknownAndNonShell() {
        TachoFaehigkeit.Eingaben seal = eligible();
        assertTrue(OemNavigationBridge.isSupportedForTest(seal));

        TachoFaehigkeit.Eingaben surf = eligible();
        surf.singleOs = "1";
        surf.modell = "BYD Dolphin Surf";
        assertFalse(OemNavigationBridge.isSupportedForTest(surf));

        TachoFaehigkeit.Eingaben di5 = eligible();
        di5.dilink5Gewaehlt = true;
        assertFalse(OemNavigationBridge.isSupportedForTest(di5));

        TachoFaehigkeit.Eingaben noShell = eligible();
        noShell.uid = "10123";
        assertFalse(OemNavigationBridge.isSupportedForTest(noShell));

        TachoFaehigkeit.Eingaben unknown = eligible();
        unknown.dienste = "";
        assertFalse(OemNavigationBridge.isSupportedForTest(unknown));

        TachoFaehigkeit.Eingaben unknownOs = eligible();
        unknownOs.singleOs = "";
        assertFalse(OemNavigationBridge.isSupportedForTest(unknownOs));
    }

    @Test
    public void sourceWiresOptInLifecycleAndNoCoordinates() throws Exception {
        String activity = read("app/src/main/java/at/greenchili/surfmate/navmap/RoadSenseMapActivity.kt");
        String client = read("app/src/main/java/at/greenchili/surfmate/navmap/oem/OemNavigationClient.kt");
        String bridge = read("app/src/main/java/at/greenchili/surfmate/tacho/oem/OemNavigationBridge.java");
        String transport = read("app/src/main/java/at/greenchili/surfmate/tacho/oem/OemNavigationTransport.java");
        assertTrue(activity.contains("OemNavigationClient.publish(state)"));
        assertTrue(activity.contains("OemNavigationClient.stop()"));
        assertTrue(client.contains("oemTurnGuidance"));
        assertFalse(client.contains(".put(\"lat\""));
        assertFalse(client.contains(".put(\"lng\""));
        assertTrue(bridge.contains("singleOs"));
        assertTrue(bridge.contains("STALE_MS = 12_000L"));
        assertTrue(transport.contains("sendInfo(5, 0, \"\")"));
        assertTrue(transport.contains("sendInfo2(4, payload)"));
    }

    private static TachoFaehigkeit.Eingaben eligible() {
        TachoFaehigkeit.Eingaben e = new TachoFaehigkeit.Eingaben();
        e.singleOs = "0";
        e.sdk = 29;
        e.dienste = "0\tAutoContainer: [android.os.IAutoContainer]\n";
        e.uid = "2000";
        return e;
    }

    private static String read(String relative) throws Exception {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new IllegalStateException("missing " + relative);
    }
}
