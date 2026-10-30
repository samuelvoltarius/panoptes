package at.greenchili.surfmate.surveillance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Wächter-Stopp: Schema der Koordination 21.10.2026 (guardStop). */
public class GuardStopTest {

    private static final long MIN = 60_000L;
    private static final long T0 = 1_800_000_000_000L;

    // ---------------------------------------------------------------- 12 V

    @Test
    public void v12IgnoresSettleTimeAndNeedsSamples() {
        Battery12vEvaluator e = new Battery12vEvaluator(Battery12vEvaluator.Profile.DILINK3);
        long park = T0;
        e.offer(11.0, park + 5 * MIN, park);   // Beruhigungszeit: zählt nicht
        assertEquals(0, e.sampleCount());
        e.offer(12.0, park + 21 * MIN, park);
        e.offer(12.0, park + 22 * MIN, park);
        assertEquals(Battery12vEvaluator.State.UNKNOWN, e.evaluate(12.2, 12.5, park + 22 * MIN));
    }

    @Test
    public void v12LowNeedsHalfAnHourAndResumesWithHysteresis() {
        Battery12vEvaluator e = new Battery12vEvaluator(Battery12vEvaluator.Profile.DILINK3);
        long park = T0;
        long t = park + 21 * MIN;
        for (int i = 0; i < 4; i++) e.offer(12.0, t + i * 90_000L, park);
        long now = t + 3 * 90_000L;
        assertEquals(Battery12vEvaluator.State.OK, e.evaluate(12.2, 12.5, now));   // wenige Minuten reichen nicht
        for (int i = 1; i <= 20; i++) {
            now += 90_000L;
            e.offer(12.1, now, park);
            e.evaluate(12.2, 12.5, now);
        }
        assertEquals(Battery12vEvaluator.State.LOW, e.state());
        // 12,3 V reicht nicht zum Wieder-Ein (Resume 12,5)
        long t2 = now + 20 * MIN;
        for (int i = 0; i < 4; i++) e.offer(12.3, t2 + i * 90_000L, park);
        assertEquals(Battery12vEvaluator.State.LOW, e.evaluate(12.2, 12.5, t2 + 3 * 90_000L));
        long t3 = t2 + 20 * MIN;
        for (int i = 0; i < 4; i++) e.offer(12.6, t3 + i * 90_000L, park);
        assertEquals(Battery12vEvaluator.State.OK, e.evaluate(12.2, 12.5, t3 + 3 * 90_000L));
    }

    @Test
    public void v12SingleDipDoesNotTriggerMedianDoes() {
        Battery12vEvaluator e = new Battery12vEvaluator(Battery12vEvaluator.Profile.DILINK3);
        long park = T0;
        long t = park + 21 * MIN;
        double[] v = {13.1, 11.5, 13.1, 13.0, 13.1};
        for (int i = 0; i < v.length; i++) e.offer(v[i], t + i * MIN, park);
        e.evaluate(12.2, 12.5, t + 4 * MIN);
        assertEquals(Battery12vEvaluator.State.OK, e.evaluate(12.2, 12.5, t + 4 * MIN));
        assertEquals(13.1, e.lastMedian(), 1e-9);
    }

    @Test
    public void v12DcDcChargingCountsAsHealthyAndOldReadingsAreIgnored() {
        Battery12vEvaluator e = new Battery12vEvaluator(Battery12vEvaluator.Profile.DILINK3);
        long park = T0;
        long t = park + 30 * MIN;
        for (int i = 0; i < 3; i++) e.offer(14.2, t + i * 2 * MIN, park);
        assertEquals(Battery12vEvaluator.State.OK, e.evaluate(12.2, 12.5, t + 4 * MIN));
        assertTrue(e.isCharging());
        // Gleicher Zeitstempel wie zuvor (z. B. aus JSON wiederhergestellt) zählt nicht doppelt.
        int n = e.sampleCount();
        e.offer(10.0, t + 4 * MIN, park);
        assertEquals(n, e.sampleCount());
        // Werte älter als das Fenster fallen weg -> unbekannt
        assertEquals(Battery12vEvaluator.State.UNKNOWN, e.evaluate(12.2, 12.5, t + 60 * MIN));
    }

    @Test
    public void v12ZeroDisablesRule() {
        Battery12vEvaluator e = new Battery12vEvaluator(Battery12vEvaluator.Profile.DEFAULT);
        assertEquals(Battery12vEvaluator.State.OK, e.evaluate(0, 12.5, T0));
    }

    // ---------------------------------------------------------------- Policy

    private static GuardStopPolicy.Inputs in(long now) {
        GuardStopPolicy.Inputs i = new GuardStopPolicy.Inputs();
        i.nowMs = now;
        i.isoDayOfWeek = 3;
        i.minuteOfDay = 12 * 60;
        i.parkedSinceMs = T0;
        i.socPercent = 60;
        i.v12 = Battery12vEvaluator.State.OK;
        return i;
    }

    private static GuardStopPolicy.Config cfg(String json) throws Exception {
        return GuardStopPolicy.Config.from(new JSONObject(json));
    }

    @Test
    public void defaultsMatchCoordinationSchema() {
        GuardStopPolicy.Config c = GuardStopPolicy.Config.from(null);
        assertTrue(c.enabled);
        assertEquals(20, c.socBelow, 0);
        assertEquals(12.2, c.v12Below, 0);
        assertEquals(12.5, c.v12Resume, 0);
        assertEquals(24, c.maxParkHours, 0);
        assertTrue(c.useQuietZones);
        assertTrue(c.reenable);
        assertTrue(c.notify);
    }

    @Test
    public void socStopsAndResumesWithHysteresisOnlyWithReenable() throws Exception {
        GuardStopPolicy p = new GuardStopPolicy();
        GuardStopPolicy.Config c = cfg("{}");
        GuardStopPolicy.Inputs i = in(T0 + MIN);
        i.socPercent = 19;
        assertTrue(p.step(c, i));
        assertEquals("soc", p.reason());
        assertEquals(T0 + MIN, p.sinceMs());
        i.socPercent = 21;            // < 20 + 2
        assertFalse(p.step(c, i));
        assertTrue(p.isActive());
        i.socPercent = 22;
        assertTrue(p.step(c, i));
        assertFalse(p.isActive());

        GuardStopPolicy q = new GuardStopPolicy();
        GuardStopPolicy.Config noRe = cfg("{\"reenable\":false}");
        i.socPercent = 10;
        q.step(noRe, i);
        i.socPercent = 90;
        q.step(noRe, i);
        assertTrue("ohne reenable bis zur nächsten Fahrt", q.isActive());
        q.reset();
        assertFalse(q.isActive());
    }

    @Test
    public void unknownSocNeverTriggers() throws Exception {
        GuardStopPolicy p = new GuardStopPolicy();
        GuardStopPolicy.Inputs i = in(T0);
        i.socPercent = Double.NaN;
        assertFalse(p.step(cfg("{}"), i));
    }

    @Test
    public void parkHoursLatchUntilDrive() throws Exception {
        GuardStopPolicy p = new GuardStopPolicy();
        GuardStopPolicy.Config c = cfg("{\"maxParkHours\":2}");
        GuardStopPolicy.Inputs i = in(T0 + 119 * MIN);
        assertFalse(p.step(c, i));
        i.nowMs = T0 + 120 * MIN;
        assertTrue(p.step(c, i));
        assertEquals("park", p.reason());
        assertTrue(p.isActive());
        assertFalse(p.step(c, in(T0 + 500 * MIN)));
        assertTrue(p.isActive());
    }

    @Test
    public void v12OutranksWindow() throws Exception {
        GuardStopPolicy p = new GuardStopPolicy();
        GuardStopPolicy.Config c = cfg("{\"windows\":[{\"days\":[3],\"from\":\"11:00\",\"to\":\"13:00\"}]}");
        GuardStopPolicy.Inputs i = in(T0);
        p.step(c, i);
        assertEquals("window", p.reason());
        i.v12 = Battery12vEvaluator.State.LOW;
        assertTrue(p.step(c, i));
        assertEquals("v12", p.reason());
    }

    @Test
    public void windowAndQuietZoneReleaseThemselvesEvenWithoutReenable() throws Exception {
        GuardStopPolicy p = new GuardStopPolicy();
        GuardStopPolicy.Config c = cfg("{\"reenable\":false,\"windows\":[{\"days\":[3],\"from\":\"11:00\",\"to\":\"13:00\"}]}");
        GuardStopPolicy.Inputs i = in(T0);
        p.step(c, i);
        assertEquals("window", p.reason());
        i.minuteOfDay = 13 * 60;
        p.step(c, i);
        assertFalse(p.isActive());
        i.inQuietZone = true;
        p.step(c, i);
        assertEquals("quietzone", p.reason());
        i.inQuietZone = false;
        p.step(c, i);
        assertFalse(p.isActive());
        // useQuietZones aus -> Ruhezone zählt nicht
        i.inQuietZone = true;
        assertFalse(p.step(cfg("{\"useQuietZones\":false}"), i));
    }

    @Test
    public void disabledResetsAndZeroDisablesEachThreshold() throws Exception {
        GuardStopPolicy p = new GuardStopPolicy();
        GuardStopPolicy.Inputs i = in(T0 + 100 * 3_600_000L);
        i.socPercent = 5;
        i.v12 = Battery12vEvaluator.State.LOW;
        assertFalse(p.step(cfg("{\"socBelow\":0,\"v12Below\":0,\"maxParkHours\":0}"), i));
        p.step(cfg("{}"), i);
        assertTrue(p.isActive());
        assertTrue(p.step(cfg("{\"enabled\":false}"), i));
        assertFalse(p.isActive());
        assertNull(p.reason());
    }

    @Test
    public void overnightWindowBelongsToStartDay() {
        JSONArray w = new JSONArray().put(new JSONObject(java.util.Map.of(
                "days", new JSONArray().put(5), "from", "22:00", "to", "06:00")));
        assertTrue(GuardStopPolicy.inAnyWindow(w, 5, 23 * 60));
        assertTrue(GuardStopPolicy.inAnyWindow(w, 6, 5 * 60));     // Sa früh = Fr-Fenster
        assertFalse(GuardStopPolicy.inAnyWindow(w, 6, 23 * 60));
        assertFalse(GuardStopPolicy.inAnyWindow(w, 5, 5 * 60));    // Fr früh gehört zu Do
        assertFalse(GuardStopPolicy.inAnyWindow(w, 5, 6 * 60));
        // Sonntag -> Montag Übergang
        JSONArray sun = new JSONArray().put(new JSONObject(java.util.Map.of(
                "days", new JSONArray().put(7), "from", "23:00", "to", "01:00")));
        assertTrue(GuardStopPolicy.inAnyWindow(sun, 1, 30));
    }

    @Test
    public void resumeBelowIsRepairedLikeTheConfigApi() throws Exception {
        assertEquals(12.5, cfg("{\"v12Below\":12.2,\"v12Resume\":12.0}").v12Resume, 1e-9);
    }

    // ---------------------------------------------------------------- Monitor

    private static final class FakeSurveillance implements GuardStopMonitor.Surveillance {
        boolean running = true;
        int stops, starts;
        public boolean isRunning() { return running; }
        public void stop() { stops++; running = false; }
        public void start() { starts++; running = true; }
    }

    @Test
    public void monitorStopsRunningSurveillanceAndRestartsOnRelease() throws Exception {
        FakeSurveillance s = new FakeSurveillance();
        GuardStopMonitor m = new GuardStopMonitor(s, Battery12vEvaluator.Profile.DEFAULT);
        GuardStopPolicy.Config c = cfg("{\"notify\":false}");
        GuardStopPolicy.Inputs i = in(T0);
        i.socPercent = 10;
        m.apply(c, i);
        assertTrue(m.isActiveForTest());
        assertEquals(1, s.stops);
        m.apply(c, i);
        assertEquals("kein zweites Stoppen", 1, s.stops);
        i.socPercent = 50;
        m.apply(c, i);
        assertFalse(m.isActiveForTest());
        assertEquals(1, s.starts);
    }

    @Test
    public void monitorDoesNotStartSurveillanceAfterDriveReset() throws Exception {
        FakeSurveillance s = new FakeSurveillance();
        GuardStopMonitor m = new GuardStopMonitor(s, Battery12vEvaluator.Profile.DEFAULT);
        GuardStopPolicy.Inputs i = in(T0);
        i.socPercent = 10;
        m.apply(cfg("{\"notify\":false}"), i);
        m.resetForDrive();
        assertFalse(m.isActiveForTest());
        assertEquals(0, s.starts);
    }

    @Test
    public void daemonWiresGuardStopIntoTheSingleArmingFunnelAndStatus() throws Exception {
        String d = new String(Files.readAllBytes(Paths.get(
                "src/main/java/at/greenchili/surfmate/daemon/CameraDaemon.java")), StandardCharsets.UTF_8);
        int fn = d.indexOf("private static boolean enableSurveillanceForAccGeneration(");
        int hook = d.indexOf("GuardStopMonitor.blocksArming()", fn);
        int arm = d.indexOf("pipeline.enableSurveillance()", fn);
        assertTrue(fn > 0 && hook > fn && hook < arm);
        // Das ist der einzige Ort, an dem die Pipeline scharf geschaltet wird.
        assertEquals(arm, d.lastIndexOf("pipeline.enableSurveillance()"));
        assertTrue(d.contains("GuardStopMonitor.putStatus(status)"));
        assertTrue(d.contains("GuardStopMonitor.onAccOn()"));
        assertTrue(d.contains("startGuardStopMonitor();"));
    }

    @Test
    public void oldGuardstopEndpointIsGoneSchemaRemains() throws Exception {
        // Nach bestätigtem 0.1.5 entfernt (Koordination 21.10.2026); Einstellungen nur über das Schema.
        String sys = new String(Files.readAllBytes(Paths.get(
                "src/main/java/at/greenchili/surfmate/server/SystemApiHandler.java")), StandardCharsets.UTF_8);
        assertFalse(sys.contains("path.equals(\"/api/system/guardstop\")"));
        String kat = new String(Files.readAllBytes(Paths.get(
                "src/main/java/at/greenchili/surfmate/einstellungen/Katalog.java")), StandardCharsets.UTF_8);
        assertTrue(kat.contains("\"guardStop.enabled\""));
    }
}
