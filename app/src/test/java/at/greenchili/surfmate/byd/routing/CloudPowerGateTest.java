package at.greenchili.surfmate.byd.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.byd.BydVehicleData;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Fail-closed cloud gate: same rule as deploy/server/bridge/befehl_gate.py (21.10.2026). */
public class CloudPowerGateTest {

    private static final long NOW = 1_800_000_000_000L;

    private static CloudPowerGate.Decision eval(String cmd, int level, long at, boolean auth, boolean acc) {
        return CloudPowerGate.evaluate(cmd, level, at, auth, acc, NOW);
    }

    @Test
    public void freshOffAllows() {
        CloudPowerGate.Decision d = eval("climate-on", 0, NOW - 30_000L, true, false);
        assertTrue(d.allowed);
        assertEquals(30_000L, d.ageMs);
    }

    @Test
    public void exactlyMaxAgeStillAllowedOneMsMoreBlocks() {
        assertTrue(eval("lock", 0, NOW - CloudPowerGate.MAX_AGE_MS, false, false).allowed);
        CloudPowerGate.Decision d = eval("lock", 0, NOW - CloudPowerGate.MAX_AGE_MS - 1L, false, false);
        assertFalse(d.allowed);
        assertEquals(CloudPowerGate.REASON_STATE_STALE, d.reasonKey);
    }

    @Test
    public void unlockNeverEvenWhenOff() {
        CloudPowerGate.Decision d = eval("unlock", 0, NOW - 1_000L, true, false);
        assertFalse(d.allowed);
        assertEquals(CloudPowerGate.REASON_UNLOCK_NEVER, d.reasonKey);
        assertFalse(eval(" UNLOCK ", 0, NOW, true, false).allowed);
    }

    @Test
    public void unknownStateBlocks() {
        assertEquals(CloudPowerGate.REASON_STATE_UNKNOWN,
                eval("climate-off", BydVehicleData.UNAVAILABLE, NOW, false, false).reasonKey);
        // Value present but never read live (restored snapshot) -> no proof.
        assertEquals(CloudPowerGate.REASON_STATE_UNKNOWN,
                eval("climate-off", 0, 0L, false, false).reasonKey);
    }

    @Test
    public void carOnBlocks() {
        assertEquals(CloudPowerGate.REASON_CAR_ON, eval("seat-heat", 1, NOW, false, false).reasonKey);
        assertEquals(CloudPowerGate.REASON_CAR_ON, eval("seat-heat", 2, NOW, false, false).reasonKey);
        // Authoritative ACC "on" overrides a stale/contradicting power snapshot of 0.
        assertEquals(CloudPowerGate.REASON_CAR_ON, eval("flash", 0, NOW, true, true).reasonKey);
    }

    @Test
    public void nonAuthoritativeAccDoesNotOverrideFreshOff() {
        assertTrue(eval("find-car", 0, NOW - 5_000L, false, true).allowed);
    }

    @Test
    public void futureStampBeyondSkewBlocks() {
        assertTrue(eval("lock", 0, NOW + 4_000L, false, false).allowed);
        assertEquals(CloudPowerGate.REASON_STATE_STALE,
                eval("lock", 0, NOW + 60_000L, false, false).reasonKey);
    }

    @Test
    public void powerLevelSetterStampsAndUnavailableClearsStamp() throws Exception {
        BydVehicleData live = new BydVehicleData.Builder().powerLevel(0, NOW).build();
        assertEquals(NOW, live.powerLevelAtMs);
        BydVehicleData none = new BydVehicleData.Builder().powerLevel(BydVehicleData.UNAVAILABLE, NOW).build();
        assertEquals(0L, none.powerLevelAtMs);
    }

    @Test
    public void routerGatesEveryCloudLegAtEntryAndDispatch() throws Exception {
        String src = new String(Files.readAllBytes(Paths.get(
                "src/main/java/at/greenchili/surfmate/byd/routing/VehicleCommandRouter.java")),
                StandardCharsets.UTF_8);
        int fn = src.indexOf("private CloudCallResult runCloudCall(final VehicleCommand cmd) {");
        assertTrue(fn > 0);
        int gate = src.indexOf("checkCloudPowerGate(cmd)", fn);
        int lock = src.indexOf("synchronized (cloudLock)", fn);
        assertTrue("gate must run before the cloud lock", gate > fn && gate < lock);
        int late = src.indexOf("CloudPowerGate.evaluateLive(cmd.name())", lock);
        int dispatch = src.indexOf("return cmd.executeViaCloud(cloudClient, vin);", lock);
        assertTrue("gate must be rechecked right before dispatch", late > lock && late < dispatch);
        int seat = src.indexOf("cloudClient.setSeatClimate(", lock);
        assertTrue("seat cloud write must also be behind the late gate", late < seat);
        // Every caller maps GATED explicitly.
        int count = 0;
        for (int i = src.indexOf("CloudOutcomeKind.GATED"); i >= 0;
             i = src.indexOf("CloudOutcomeKind.GATED", i + 1)) count++;
        assertTrue("GATED handled in runCloudFirst, runSdkFirst, trunk, runCloudCall: " + count, count >= 5);
        assertTrue(src.contains("case GATED:"));
    }
}
