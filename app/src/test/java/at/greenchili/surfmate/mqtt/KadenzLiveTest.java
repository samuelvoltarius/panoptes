package at.greenchili.surfmate.mqtt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/** Deploy 21.10.: Kadenz-Felder live ohne Neuaufbau; neue Verbindungen mit 120 s Herzschlag im Stand. */
public class KadenzLiveTest {

    @Test
    public void nurKadenzFelderOhneNeuaufbau() throws Exception {
        assertTrue(MqttConnectionManager.nurKadenz(new JSONObject().put("parkedIntervalSeconds", 60)));
        assertTrue(MqttConnectionManager.nurKadenz(new JSONObject()
                .put("minIntervalSeconds", 5).put("maxIntervalSeconds", 300).put("changeOnly", true)));
        assertFalse(MqttConnectionManager.nurKadenz(new JSONObject().put("brokerUrl", "tcp://x")));
        assertFalse(MqttConnectionManager.nurKadenz(new JSONObject()
                .put("parkedIntervalSeconds", 60).put("enabled", false)));
        assertFalse(MqttConnectionManager.nurKadenz(new JSONObject()));
    }

    @Test
    public void bestandBehaeltSeinenWert() {
        // fromJson ohne das Feld (Bestand) bleibt 0 = „wie maxIntervalSeconds“; nur addConnection setzt 120.
        MqttConnectionConfig alt = MqttConnectionConfig.fromJson(new JSONObject());
        assertEquals(0, alt.parkedIntervalSeconds);
        assertEquals(120, MqttConnectionManager.NEU_PARKED_INTERVAL_S);
    }
}
