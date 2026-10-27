package at.greenchili.surfmate.wallbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

import java.net.ConnectException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

    /** 0.1.21 (Kaltstart 27.10. 22:06): ein offener, aber nicht tragender Proxy darf die Wallbox nicht festhalten. */
    @Test
    public void wegketteMitRueckfallUndZuletztErfolgreichemZuerst() throws Exception {
        assertEquals(Arrays.asList("proxy", "tailnet", "direkt"), OpenWbAdapter.wege(true, true, true, null));
        assertEquals(Arrays.asList("tailnet", "proxy", "direkt"), OpenWbAdapter.wege(true, true, true, "tailnet"));
        assertEquals(Arrays.asList("direkt"), OpenWbAdapter.wege(false, true, false, "tailnet"));

        String broker = "100.64.0.20:1883#" + System.nanoTime();
        List<String> versucht = new ArrayList<>();
        String r = OpenWbAdapter.ueberWege(broker, OpenWbAdapter.wege(true, true, true, null), w -> {
            versucht.add(w);
            if (!"tailnet".equals(w)) throw new ConnectException("Connection refused");
            return "ok";
        });
        assertEquals("ok", r);
        assertEquals(Arrays.asList("proxy", "tailnet"), versucht);
        assertEquals("tailnet", OpenWbAdapter.ZULETZT.get(broker));
        JSONObject v = OpenWbAdapter.verbindung();
        assertEquals("tailnet", v.getString("weg"));
        assertTrue(v.toString(), v.getJSONObject("wegFehler").getString("proxy").contains("Connection refused"));

        // alle Wege tot: ehrlich je Weg der Grund, weg = null
        versucht.clear();
        try {
            OpenWbAdapter.ueberWege(broker, OpenWbAdapter.wege(true, true, true, OpenWbAdapter.ZULETZT.get(broker)), w -> {
                versucht.add(w);
                throw new ConnectException(w + " tot");
            });
            fail();
        } catch (ConnectException erwartet) { }
        assertEquals(Arrays.asList("tailnet", "proxy", "direkt"), versucht);
        v = OpenWbAdapter.verbindung();
        assertEquals(JSONObject.NULL, v.get("weg"));
        assertEquals(3, v.getJSONObject("wegFehler").length());

        // Anmeldefehler = Broker erreicht: kein weiterer Weg
        versucht.clear();
        try {
            OpenWbAdapter.ueberWege(broker, OpenWbAdapter.wege(true, true, true, null), w -> {
                versucht.add(w);
                throw new org.eclipse.paho.client.mqttv3.MqttSecurityException(5);
            });
            fail();
        } catch (org.eclipse.paho.client.mqttv3.MqttSecurityException erwartet) { }
        assertEquals(Arrays.asList("proxy"), versucht);
    }
}
