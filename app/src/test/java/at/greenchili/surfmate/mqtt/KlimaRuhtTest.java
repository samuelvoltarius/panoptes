package at.greenchili.surfmate.mqtt;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.byd.BydVehicleData;
import at.greenchili.surfmate.testutil.Quelltext;

import org.junit.Test;

import java.nio.file.Paths;

/** 0.1.16 (Deploy-Test 23.10.): MQTT-Klimawerte dürfen nach dem Ausschalten nicht auf „an“ stehen bleiben. */
public class KlimaRuhtTest {

    private static BydVehicleData mitPower(int p) {
        return new BydVehicleData.Builder().powerLevel(p, System.currentTimeMillis()).build();
    }

    @Test
    public void klimaRuhtOhneAutoAn() {
        assertTrue(MqttConnectionManager.klimaRuht(mitPower(0), false));
        assertTrue(MqttConnectionManager.klimaRuht(mitPower(1), false));
        assertFalse(MqttConnectionManager.klimaRuht(mitPower(2), false));
        assertTrue("unbekannt = ruht", MqttConnectionManager.klimaRuht(new BydVehicleData.Builder().build(), false));
        assertFalse("DiLink 5 eigene Quelle", MqttConnectionManager.klimaRuht(mitPower(0), true));
    }

    @Test
    public void payloadNutztDieRegel() throws Exception {
        String q = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/mqtt/MqttConnectionManager.java"));
        assertTrue(q.contains("payload.put(\"ac_on\", klimaRuht ? 0 : vd.acStartState)"));
        assertTrue(q.contains("payload.put(\"ac_fan\", klimaRuht ? 0 : vd.acFanLevel)"));
        assertTrue(q.contains("payload.put(\"climate_on\", klimaRuht ? 0 : vd.acStartState)"));
    }
}
