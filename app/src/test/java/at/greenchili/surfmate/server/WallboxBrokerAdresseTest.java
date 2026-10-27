package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import at.greenchili.surfmate.mqtt.MqttConnectionConfig;

import org.json.JSONObject;
import org.junit.Test;

/**
 * Befund am Auto 27.10.2026 (0.1.19.1): Die Panoptes-MQTT-Verbindung „home“ hatte die Adresse ohne {@code tcp://}
 * („100.64.0.20“). {@code brokerAus} verlangte {@code tcp://} und ließ sie still weg → die Wallbox-Brücke wurde nie
 * „von selbst gefunden“. Jetzt wird die Adresse aufgelöst wie die Verbindung selbst (getBrokerUri).
 */
public class WallboxBrokerAdresseTest {

    @Test
    public void nackteAdresseWieDieVerbindungSelbst() throws Exception {
        JSONObject b = WallboxApiHandler.brokerAus("100.64.0.20", 1883, "", "");
        assertEquals("100.64.0.20", b.getString("host"));
        assertEquals(1883, b.getInt("port"));
        assertFalse(b.has("fehler"));
        // dieselbe Auflösung wie MqttConnectionConfig
        MqttConnectionConfig c = new MqttConnectionConfig();
        c.brokerUrl = "100.64.0.20";
        c.port = 1883;
        assertEquals("tcp://100.64.0.20:1883", c.getBrokerUri());
    }

    @Test
    public void varianten() throws Exception {
        assertEquals(1884, WallboxApiHandler.brokerAus("tcp://broker.local:1884", 1883, "u", "p").getInt("port"));
        assertEquals("broker.local", WallboxApiHandler.brokerAus(" tcp://broker.local/ ", 1883, null, null).getString("host"));
        assertEquals(1999, WallboxApiHandler.brokerAus("broker.local", 1999, null, null).getInt("port"));
        assertEquals("10.0.2.2", WallboxApiHandler.brokerAus("mqtt://10.0.2.2:18830", 1883, null, null).getString("host"));
        assertEquals(18830, WallboxApiHandler.brokerAus("10.0.2.2:18830", 1883, null, null).getInt("port"));
        assertEquals("fd7a:115c::1", WallboxApiHandler.brokerAus("tcp://[fd7a:115c::1]:1883", 1883, null, null).getString("host"));
        assertEquals("u", WallboxApiHandler.brokerAus("tcp://h", 1883, "u", "p").getString("benutzer"));
    }

    @Test
    public void nichtNutzbarKommtMitGrundStattStill() throws Exception {
        assertEquals("nur_tcp", WallboxApiHandler.brokerAus("ssl://broker:8883", 1883, "u", "p").getString("fehler"));
        assertEquals("nur_tcp", WallboxApiHandler.brokerAus("wss://broker:443/mqtt", 1883, null, null).getString("fehler"));
        assertFalse("kein Passwort in einem Fehlereintrag", WallboxApiHandler.brokerAus("ssl://broker:8883", 1883, "u", "p").has("passwort"));
        assertEquals("adresse_unlesbar", WallboxApiHandler.brokerAus("", 1883, null, null).getString("fehler"));
        assertEquals("adresse_unlesbar", WallboxApiHandler.brokerAus(null, 1883, null, null).getString("fehler"));
    }
}
