package at.greenchili.surfmate.wallbox;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 0.1.20.1: der Wallbox-MQTT-Client wählt wie die Panoptes-MQTT-Verbindung (Proxy → Tailnet-LocalAPI → direkt). */
public class WallboxMqttWegTest {

    @Test
    public void wegWieDieMqttVerbindung() {
        boolean tailnet = at.greenchili.surfmate.mqtt.TailnetAnstoss.istTailnetZiel("100.64.0.20");
        assertEquals("Alfreds Brücke am Auto: Tailnet-Broker, LocalAPI da", "tailnet", OpenWbAdapter.weg(false, tailnet, true));
        assertEquals("Proxy hat Vorrang", "proxy", OpenWbAdapter.weg(true, tailnet, true));
        assertEquals("ohne Panoptes-tailscaled wie bisher", "direkt", OpenWbAdapter.weg(false, tailnet, false));
        assertEquals("LAN-Broker wie bisher", "direkt", OpenWbAdapter.weg(false,
                at.greenchili.surfmate.mqtt.TailnetAnstoss.istTailnetZiel("192.168.1.94"), true));
    }
}
