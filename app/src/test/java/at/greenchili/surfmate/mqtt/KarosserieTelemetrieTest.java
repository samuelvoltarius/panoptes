package at.greenchili.surfmate.mqtt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 0.1.15 (Kern 8, HA_BYD_DATEN_BEFUND.md): Verriegelung, Türen, Fenster, Klappen und „angesteckt“ als HA-
 * binary_sensor; nur bekannte Werte, stabile unique_id, nur lesend.
 */
public class KarosserieTelemetrieTest {

    private static JSONObject doors(int lf, int rf, int lr, int rr, int overall, String scope) throws Exception {
        return new JSONObject().put("lf", lf).put("rf", rf).put("lr", lr).put("rr", rr).put("overall", overall)
                .put("scope", scope);
    }

    @Test
    public void allesBekannt() throws Exception {
        JSONObject p = new JSONObject();
        KarosserieTelemetrie.eintragen(p, doors(1, 1, 2, 1, 2, "vehicle"),
                new JSONObject().put("lf", true).put("rf", false).put("lr", false).put("rr", false)
                        .put("hood", false).put("trunk", true).put("fuelCap", false),
                new JSONObject().put("lf", false).put("rf", true).put("lr", false).put("rr", false), 2);
        assertEquals("entriegelt (eine Tür offen) = an", 1, p.getInt("unlocked"));
        assertEquals(0, p.getInt("unlocked_lf"));
        assertEquals(1, p.getInt("unlocked_lr"));
        assertEquals(1, p.getInt("door_open_lf"));
        assertEquals(0, p.getInt("door_open_rf"));
        assertEquals(1, p.getInt("trunk_open"));
        assertEquals(0, p.getInt("hood_open"));
        assertEquals(1, p.getInt("window_open_rf"));
        assertEquals(0, p.getInt("window_open_lf"));
        assertEquals(1, p.getInt("plugged"));
        assertFalse("Tankdeckel nicht als Entität", p.toString().contains("fuel"));
    }

    @Test
    public void unbekanntWirdWeggelassen() throws Exception {
        JSONObject p = new JSONObject();
        // nur Fahrertür per OTA bekannt (scope driver_door), SDK 0 = INVALID, Rest -1
        KarosserieTelemetrie.eintragen(p, doors(1, -1, 0, -1, 1, "driver_door"), new JSONObject(),
                new JSONObject(), Integer.MIN_VALUE);
        assertFalse("Gesamt-Verriegelung nur bei scope vehicle", p.has("unlocked"));
        assertEquals(0, p.getInt("unlocked_lf"));
        assertFalse(p.has("unlocked_rf"));
        assertFalse("0 = INVALID", p.has("unlocked_lr"));
        assertFalse(p.has("door_open_lf"));
        assertFalse(p.has("window_open_lf"));
        assertFalse("Stecker unbekannt", p.has("plugged"));
        JSONObject leer = new JSONObject();
        KarosserieTelemetrie.eintragen(leer, null, null, null, 255);
        assertEquals(0, leer.length());
    }

    @Test
    public void stecker() throws Exception {
        for (int g = 0; g <= 5; g++) {
            JSONObject p = new JSONObject();
            KarosserieTelemetrie.eintragen(p, null, null, null, g);
            assertEquals("gun " + g, g >= 2 ? 1 : 0, p.getInt("plugged"));
        }
    }

    @Test
    public void katalogUndDiscovery() throws Exception {
        String[][] erwartet = {
                {"unlocked", "lock"}, {"unlocked_lf", "lock"}, {"door_open_rr", "door"}, {"trunk_open", "door"},
                {"hood_open", "door"}, {"window_open_lr", "window"}, {"plugged", "plug"}, {"climate_on", "running"}};
        for (String[] e : erwartet) {
            TelemetryFieldCatalog.Field f = TelemetryFieldCatalog.get(e[0]);
            assertTrue(e[0], f.isBinary());
            assertEquals(e[0], e[1], f.deviceClass);
            assertTrue(TelemetryFieldCatalog.isDiscoverable(e[0]));
        }
        assertEquals("kWh/100 km", TelemetryFieldCatalog.get("consumption_avg").unit);
        assertEquals("°C", TelemetryFieldCatalog.get("climate_setpoint").unit);

        JSONObject snap = new JSONObject().put("unlocked", 0).put("door_open_lf", 1).put("plugged", 1)
                .put("climate_on", 0).put("consumption_avg", 14.2);
        JSONObject b = new JSONObject(HomeAssistantDiscovery.buildBundle("byd-3eb0b7d3", null, "Dolphin Surf", "0.1.15",
                "surfmate/byd-3eb0b7d3", snap, null, false));
        JSONObject cmps = b.getJSONObject("cmps");
        JSONObject lock = cmps.getJSONObject("unlocked");
        assertEquals("binary_sensor", lock.getString("p"));
        assertEquals("lock", lock.getString("device_class"));
        assertEquals("stabile unique_id nach Schema node_key", "surfmate_byd-3eb0b7d3_unlocked", lock.getString("unique_id"));
        assertEquals("surfmate/byd-3eb0b7d3/unlocked", lock.getString("state_topic"));
        assertEquals("1", lock.getString("payload_on"));
        assertFalse("nur lesend: kein command_topic", lock.has("command_topic"));
        assertEquals("plug", cmps.getJSONObject("plugged").getString("device_class"));
        assertEquals("door", cmps.getJSONObject("door_open_lf").getString("device_class"));
        assertFalse("unbekannte Fenster nicht angekündigt", cmps.has("window_open_lf"));
    }

    @Test
    public void telemetrieOhneCloudAbruf() throws Exception {
        File f = new File("src/main/java/at/greenchili/surfmate/mqtt/MqttConnectionManager.java");
        if (!f.isFile()) f = new File("app/" + f.getPath());
        String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        assertTrue(s.contains("VehicleControlApiHandler.sperrStand(vd, false)"));
        File v = new File("src/main/java/at/greenchili/surfmate/server/VehicleControlApiHandler.java");
        if (!v.isFile()) v = new File("app/" + v.getPath());
        String api = new String(Files.readAllBytes(v.toPath()), StandardCharsets.UTF_8);
        assertTrue("Cloud-Auffrischen nur mit Schalter",
                api.contains("if (cloudAuffrischen) {") && api.contains("JSONObject doors = sperrStand(data, true);"));
    }
}
