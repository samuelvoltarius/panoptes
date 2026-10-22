package at.greenchili.surfmate.mqtt;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

/** power_level mit Zeitstempel für Bridge (befehl_gate.py) und Cockpit (Kern 21.10.2026). */
public class PowerLevelFreshnessTest {

    @Test
    public void powerLevelAndTimestampAlwaysTravelTogether() throws Exception {
        JSONObject snap = new JSONObject().put("power_level", 0).put("power_level_ts", 1792540829L)
                .put("soc", 80);
        Set<String> keys = new HashSet<>();
        keys.add("power_level_ts");
        MqttPublisherService.addPowerLevelPair(keys, snap);
        assertTrue(keys.contains("power_level"));

        Set<String> keys2 = new HashSet<>();
        keys2.add("power_level");
        MqttPublisherService.addPowerLevelPair(keys2, snap);
        assertTrue(keys2.contains("power_level_ts"));

        Set<String> other = new HashSet<>();
        other.add("soc");
        MqttPublisherService.addPowerLevelPair(other, snap);
        assertFalse(other.contains("power_level"));
    }

    @Test
    public void timestampCountsAsChangeButPowerLevelStaysABareNumber() throws Exception {
        assertFalse("jede neue Live-Lesung muss als Änderung zählen",
                TelemetryFieldCatalog.EXCLUDED.contains("power_level_ts"));
        String m = new String(Files.readAllBytes(Paths.get(
                "src/main/java/at/greenchili/surfmate/mqtt/MqttConnectionManager.java")),
                StandardCharsets.UTF_8);
        assertTrue(m.contains("payload.put(\"power_level\", vd.powerLevel);"));
        assertTrue(m.contains("payload.put(\"power_level_ts\", vd.powerLevelAtMs / 1000);"));
        assertTrue(m.contains("AccMonitor.isAccStateAuthoritative()"));
    }
}
