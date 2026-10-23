package at.greenchili.surfmate.mqtt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 0.1.16: Beim DC-Laden am Surf gibt es oft nur die Schätzung (ringEstimator). charge_power bleibt der Messwert,
 * die Schätzung kommt getrennt als charge_power_estimated mit Qualität und Quelle (MQTT + HA-Discovery).
 */
public class LadeleistungMqttTest {

    @Test
    public void schaetzungGetrenntVomMesswert() {
        // Befund Camping-Test 23.10.: DC, 37,1 kW geschätzt, kein Messwert → bisher charge_power 0
        LadeleistungMqtt s = LadeleistungMqtt.aus(true, false, true, 37.1, "ringEstimator");
        assertEquals(0.0, s.gemessenKw, 0);
        assertEquals(37.1, s.geschaetztKw, 1e-9);
        assertEquals("estimated", s.qualitaet);
        assertEquals("ringEstimator", s.quelle);

        LadeleistungMqtt m = LadeleistungMqtt.aus(true, false, false, 7.2, "sdkChargePower");
        assertEquals(7.2, m.gemessenKw, 1e-9);           // echter Wert bleibt
        assertEquals(0.0, m.geschaetztKw, 0);
        assertEquals("measured", m.qualitaet);
    }

    @Test
    public void nichtLadenV2lOderUnplausibel() {
        for (LadeleistungMqtt x : new LadeleistungMqtt[]{
                LadeleistungMqtt.aus(false, false, true, 37.1, "ringEstimator"),
                LadeleistungMqtt.aus(true, true, false, 3.0, "sdk"),          // V2L entlädt
                LadeleistungMqtt.aus(true, false, true, Double.NaN, "ringEstimator"),
                LadeleistungMqtt.aus(true, false, false, 0.05, "sdk"),
                LadeleistungMqtt.aus(true, false, false, 600, "sdk")}) {
            assertEquals(0.0, x.gemessenKw, 0);
            assertEquals(0.0, x.geschaetztKw, 0);
            assertEquals("none", x.qualitaet);
            assertEquals("none", x.quelle);
        }
        assertEquals("none", LadeleistungMqtt.aus(true, false, true, 11, null).quelle);
        assertTrue(LadeleistungMqtt.plausibel(350));            // 350-kW-DC ist echt
    }

    @Test
    public void imKatalogUndImPayload() throws Exception {
        TelemetryFieldCatalog.Field e = TelemetryFieldCatalog.get("charge_power_estimated");
        assertNotNull(e);
        assertTrue(TelemetryFieldCatalog.isDiscoverable("charge_power_estimated"));
        assertEquals("kW", e.unit);
        assertTrue(TelemetryFieldCatalog.isDiscoverable("charge_power_quality"));
        assertTrue(TelemetryFieldCatalog.isDiscoverable("charge_power_source"));
        File f = new File("src/main/java/at/greenchili/surfmate/mqtt/MqttConnectionManager.java");
        if (!f.isFile()) f = new File("app/" + f.getPath());
        String k = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        int a = k.indexOf("payload.put(\"charge_power\", chargeKw);");
        assertTrue(a > 0);
        String danach = k.substring(a, a + 900);
        assertTrue(danach.contains("payload.put(\"charge_power_estimated\", leistung.geschaetztKw);"));
        assertTrue(danach.contains("payload.put(\"charge_power_quality\", leistung.qualitaet);"));
        assertTrue(danach.contains("payload.put(\"charge_power_source\", leistung.quelle);"));
        // charge_power selbst nimmt weiter nie eine Schätzung
        assertFalse(k.substring(k.lastIndexOf("double chargeKw = 0;", a), a).contains("isEstimated)"));
    }
}
