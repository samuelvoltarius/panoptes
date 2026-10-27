package at.greenchili.surfmate.mqtt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.wallbox.OpenWbAdapter;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 0.1.20.1 Regression am Auto (27.10.): Wallbox-Brücke auf einem Tailnet-Broker (nackte IP, Port 1883, Adapter bruecke)
 * „nicht_erreichbar“, obwohl die Verbindung „home“ zum selben Broker verbunden war. Ursache: der Wallbox-MQTT-Client
 * wählte immer direkt (OS-Routing = Tailscale-App des Autos, die offline war); „home“ wählt über die LocalAPI des
 * Panoptes-tailscaled. Geprüft mit dem echten Paho-Client gegen eine nachgebaute LocalAPI und einen Test-Broker.
 *
 * <p>Absichtlich NICHT 100.64.0.20: von einem Rechner im Tailnet wäre das Alfreds echter Broker. 100.64.0.99 ist ein
 * ungenutztes Tailnet-Ziel – ohne LocalAPI-Weg scheitert der Test, wie am Auto.
 */
public class WallboxBrueckeTailnetTest {

    static final String ID = "bf4839172db96045cdaeg0";   // Geräte-ID aus Alfreds Einrichtung
    static final String TAILNET_HOST = "100.64.0.99";

    private WiederverbindungTest.TestBroker broker;
    private TailnetDialTest.FakeLocalApi api;
    private int altPort;

    @After
    public void aufraeumen() throws Exception {
        if (api != null) api.stop();
        if (broker != null) broker.stop();
        if (altPort != 0) TailnetDial.localApiPort = altPort;
    }

    @Test
    public void brueckeAufTailnetBrokerLiestUeberDieLocalApi() throws Exception {
        broker = WiederverbindungTest.TestBroker.start(0);
        broker.gespeichert.put("wallbox/" + ID + "/zustand", new JSONObject().put("v", 1).put("erreichbar", true)
                .put("zeit_ms", System.currentTimeMillis()).put("laedt", false).put("status", "sleep")
                .put("strom_soll_a", 6).toString());
        broker.gespeichert.put("wallbox/" + ID + "/verfuegbar", "online");
        api = TailnetDialTest.FakeLocalApi.start(broker.port(), 101);
        altPort = TailnetDial.localApiPort;
        TailnetDial.localApiPort = api.port();

        List<String> topics = Arrays.asList("wallbox/" + ID + "/zustand", "wallbox/" + ID + "/verfuegbar");
        Map<String, String> w = OpenWbAdapter.PAHO.lies(TAILNET_HOST, OpenWbAdapter.PORT, "", "", topics,
                m -> m.keySet().containsAll(topics), 3_000L);

        assertEquals("online", w.get(topics.get(1)));
        assertEquals(6, new JSONObject(w.get(topics.get(0))).getInt("strom_soll_a"));
        assertTrue(api.anfragen.toString(), api.anfragen.get(0).contains("Dial-Host: " + TAILNET_HOST));
        assertTrue(api.anfragen.get(0).contains("Dial-Port: 1883"));
    }

    @Test
    public void alfredsAdresseIstEinTailnetZiel() {
        assertTrue(TailnetAnstoss.istTailnetZiel("100.64.0.20"));
    }
}
