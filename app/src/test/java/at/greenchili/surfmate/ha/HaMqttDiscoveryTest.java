package at.greenchili.surfmate.ha;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.ha.HaMqttDiscovery.Schritt;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

/** 0.1.43.21: keine MQTT-Discovery, wenn die HA-Integration gekoppelt ist; eigenes Topic einmal leeren. */
public class HaMqttDiscoveryTest {

    private static JSONArray kopplungen(String art, boolean abgelaufen) throws Exception {
        return new JSONArray().put(new JSONObject().put("id", "k1").put("name", "Graz Zuhause").put("art", art)
                .put("abgelaufen", abgelaufen));
    }

    @Test
    public void gekoppelt_keineDiscovery_einmalLeeren() throws Exception {
        boolean gekoppelt = HaMqttDiscovery.haGekoppelt(kopplungen("homeassistant", false));
        assertTrue(gekoppelt);
        boolean erlaubt = HaMqttDiscovery.senden(HaMqttDiscovery.AUTOMATISCH, gekoppelt);
        assertFalse(erlaubt);
        HaMqttDiscovery.Verlauf v = new HaMqttDiscovery.Verlauf();
        assertEquals(Schritt.LEEREN, v.schritt(erlaubt));
        assertEquals("nur einmal", Schritt.NICHTS, v.schritt(erlaubt));
        assertEquals(Schritt.NICHTS, v.schritt(erlaubt));
    }

    @Test
    public void leerenOhneVerbindungWirdWiederholt() {
        HaMqttDiscovery.Verlauf v = new HaMqttDiscovery.Verlauf();
        assertEquals(Schritt.LEEREN, v.schritt(false));
        v.leerenFehlgeschlagen();
        assertEquals(Schritt.LEEREN, v.schritt(false));
        assertEquals(Schritt.NICHTS, v.schritt(false));
    }

    @Test
    public void nichtGekoppelt_unveraendert() throws Exception {
        assertFalse(HaMqttDiscovery.haGekoppelt(new JSONArray()));
        assertFalse("Cockpit ist keine HA-Integration", HaMqttDiscovery.haGekoppelt(kopplungen("cockpit", false)));
        assertFalse("abgelaufen zählt nicht", HaMqttDiscovery.haGekoppelt(kopplungen("homeassistant", true)));
        assertFalse(HaMqttDiscovery.haGekoppelt(null));
        HaMqttDiscovery.Verlauf v = new HaMqttDiscovery.Verlauf();
        assertTrue(HaMqttDiscovery.senden(HaMqttDiscovery.AUTOMATISCH, false));
        assertEquals(Schritt.SENDEN, v.schritt(true));
        assertEquals(Schritt.SENDEN, v.schritt(true));
    }

    @Test
    public void kopplungSpaeterEntfernt_discoveryKommtWieder() {
        HaMqttDiscovery.Verlauf v = new HaMqttDiscovery.Verlauf();
        assertEquals(Schritt.SENDEN, v.schritt(HaMqttDiscovery.senden("automatisch", false)));
        assertEquals("gekoppelt", Schritt.LEEREN, v.schritt(HaMqttDiscovery.senden("automatisch", true)));
        assertEquals(Schritt.NICHTS, v.schritt(false));
        assertEquals("entfernt", Schritt.WIEDER_SENDEN, v.schritt(HaMqttDiscovery.senden("automatisch", false)));
        assertEquals(Schritt.SENDEN, v.schritt(true));
        assertEquals("wieder gekoppelt → wieder einmal leeren", Schritt.LEEREN, v.schritt(false));
        assertTrue(HaMqttDiscovery.Verlauf.neu(Schritt.LEEREN));
        assertTrue(HaMqttDiscovery.Verlauf.neu(Schritt.WIEDER_SENDEN));
        assertFalse(HaMqttDiscovery.Verlauf.neu(Schritt.SENDEN));
    }

    @Test
    public void schalterAnAusAutomatisch() throws Exception {
        assertTrue(HaMqttDiscovery.senden(HaMqttDiscovery.AN, true));
        assertFalse(HaMqttDiscovery.senden(HaMqttDiscovery.AUS, false));
        assertFalse("unbekannt = automatisch", HaMqttDiscovery.senden("quatsch", true));
        assertEquals(HaMqttDiscovery.AUTOMATISCH, HaMqttDiscovery.modus(null));
        assertEquals(HaMqttDiscovery.AUTOMATISCH, HaMqttDiscovery.modus(new JSONObject()));
        assertEquals(HaMqttDiscovery.AN, HaMqttDiscovery.modus(new JSONObject().put("ha",
                new JSONObject().put("mqttDiscovery", "an"))));
        assertEquals(HaMqttDiscovery.AUTOMATISCH, HaMqttDiscovery.modus(new JSONObject().put("ha",
                new JSONObject().put("mqttDiscovery", "xyz"))));
    }

    @Test
    public void protokollOhneNamen() {
        String z = HaMqttDiscovery.protokoll("automatisch", true, false);
        assertEquals("HA_MQTT_DISCOVERY aus modus=automatisch haGekoppelt=true", z);
    }

    @Test
    public void katalogUndTexteInFuenfSprachen() throws Exception {
        String k = lies("src/main/java/at/greenchili/surfmate/einstellungen/Katalog.java");
        assertTrue(k.contains("HaMqttDiscovery.SCHLUESSEL") && k.contains("HaMqttDiscovery.AUTOMATISCH;"));
        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject d = new JSONObject(lies("src/main/assets/server-i18n/" + l + ".json"));
            JSONObject f = d.getJSONObject("settings").getJSONObject("ha").getJSONObject("mqttDiscovery");
            for (String n : new String[] {"title", "help", "automatisch", "an", "aus"}) {
                assertFalse(l + ": " + n, f.optString(n, "").isEmpty());
            }
        }
    }

    @Test
    public void publisherLeertNurDasEigeneTopic() throws Exception {
        String p = lies("src/main/java/at/greenchili/surfmate/mqtt/MqttPublisherService.java");
        assertTrue(p.contains("if (discoveryErlaubt && !discoveryAnnounced) announceDiscovery(snapshot);"));
        assertTrue(p.contains("if (!removeDiscovery(config.discoveryPrefix)) discoveryVerlauf.leerenFehlgeschlagen();"));
        int a = p.indexOf("public synchronized boolean removeDiscovery");
        String rumpf = p.substring(a, p.indexOf("\n    }", a));
        assertTrue("nur deviceConfigTopic des eigenen Geräts",
                rumpf.contains("HomeAssistantDiscovery.deviceConfigTopic(discoveryPrefix, deviceId)"));
    }

    private static String lies(String rel) throws Exception {
        File f = new File(rel);
        if (!f.isFile()) f = new File("app/" + rel);
        return new String(Files.readAllBytes(f.toPath()), "UTF-8");
    }
}
