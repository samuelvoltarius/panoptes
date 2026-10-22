package at.greenchili.surfmate.surveillance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** guard/state + guard/sleep für den Server-Wächter S1 (Spark-Ausstieg-Sitzung, 21.10.2026). */
public class GuardStateReporterTest {

    private static final class Merker implements GuardStateReporter.Sender {
        final List<String[]> gesendet = new ArrayList<>();
        boolean verbunden = true;
        public boolean send(String topic, String payload, boolean retain, int qos) {
            if (!verbunden) return false;
            gesendet.add(new String[] {topic, payload, String.valueOf(retain), String.valueOf(qos)});
            return true;
        }
    }

    @Test
    public void stateIsRetainedAndOnlySentOnChangeOrHourly() throws Exception {
        Merker m = new Merker();
        GuardStateReporter r = new GuardStateReporter(m);
        long t0 = 1_800_000_000_000L;
        assertTrue(r.melde(true, false, null, t0));
        assertFalse("unverändert", r.melde(true, false, null, t0 + 10_000));
        assertTrue("Wächter-Stopp", r.melde(true, true, "v12", t0 + 20_000));
        assertTrue("stündliche Auffrischung", r.melde(true, true, "v12", t0 + 20_000 + GuardStateReporter.REFRESH_MS));
        assertEquals(3, m.gesendet.size());
        String[] erstes = m.gesendet.get(0);
        assertEquals("guard/state", erstes[0]);
        assertEquals("true", erstes[2]);   // retained
        JSONObject o = new JSONObject(m.gesendet.get(1)[1]);
        assertTrue(o.getBoolean("armed"));
        assertTrue(o.getBoolean("guardStopActive"));
        assertEquals("v12", o.getString("guardStopReason"));
        assertEquals((t0 + 20_000) / 1000, o.getLong("since"));
        // since bleibt bei der Auffrischung der Zeitpunkt der Änderung
        assertEquals((t0 + 20_000) / 1000, new JSONObject(m.gesendet.get(2)[1]).getLong("since"));
    }

    @Test
    public void notConnectedRetriesNextTick() {
        Merker m = new Merker();
        m.verbunden = false;
        GuardStateReporter r = new GuardStateReporter(m);
        assertFalse(r.melde(false, false, null, 1000L));
        m.verbunden = true;
        assertTrue(r.melde(false, false, null, 11_000L));
    }

    @Test
    public void payloadsCarryNoPosition() throws Exception {
        JSONObject s = GuardStateReporter.zustandJson(true, false, null, 1_000_000L);
        assertEquals(4, s.length());
        assertTrue(s.isNull("guardStopReason"));
        JSONObject z = GuardStateReporter.schlafJson("guardStop:soc", 2_000_000L);
        assertEquals(2, z.length());
        assertEquals("guardStop:soc", z.getString("reason"));
        assertEquals(2000, z.getLong("ts"));
        for (String k : new String[] {"lat", "lon", "latitude", "longitude", "position"}) {
            assertFalse(s.has(k));
            assertFalse(z.has(k));
        }
    }

    @Test
    public void sleepIsQos1NotRetainedAndWired() throws Exception {
        String src = new String(Files.readAllBytes(Paths.get(
                "src/main/java/at/greenchili/surfmate/surveillance/GuardStateReporter.java")), StandardCharsets.UTF_8);
        assertTrue(src.contains("s.send(TOPIC_SLEEP, schlafJson(grund, System.currentTimeMillis()).toString(), false, 1);"));
        String gs = new String(Files.readAllBytes(Paths.get(
                "src/main/java/at/greenchili/surfmate/surveillance/GuardStopMonitor.java")), StandardCharsets.UTF_8);
        assertTrue("vor dem Wächter-Stopp", gs.contains("GuardStateReporter.schlaf(\"guardStop:\" + policy.reason());"));
        String d = new String(Files.readAllBytes(Paths.get(
                "src/main/java/at/greenchili/surfmate/daemon/CameraDaemon.java")), StandardCharsets.UTF_8).replace("\r\n", "\n");
        int sleep = d.indexOf("GuardStateReporter.schlaf(\"parkTerminate\");");
        int term = d.indexOf("if (!parkTerminate(transitionGeneration)", sleep);
        assertTrue("guard/sleep direkt vor parkTerminate", sleep > 0 && term > sleep && term - sleep < 200);
        assertTrue(d.contains("GuardStateReporter.start();"));
    }
}
