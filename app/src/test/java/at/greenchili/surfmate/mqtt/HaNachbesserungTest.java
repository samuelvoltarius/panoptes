package at.greenchili.surfmate.mqtt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.testutil.Quelltext;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Paths;

/** 0.1.17 HA-Nachbesserungen (Koordination 27.10.2026): fan_mode 0, keine Scheininnentemperatur, Änderungszeiten. */
public class HaNachbesserungTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void klimaOhneFanmodeNullUndOhneInnentemperatur() throws Exception {
        String q = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/mqtt/VehicleControlCatalog.java"));
        assertTrue(q.contains("\"{% if (value | int(0)) >= 1 %}{{ value | int(0) }}{% else %}None{% endif %}\""));
        assertFalse("cabin_temp ist kein Fühler", q.contains("current_temperature_topic"));
    }

    @Test
    public void aenderungszeitNurBeiEchterAenderungUndUeberNeustart() throws Exception {
        File f = new File(tmp.getRoot(), "zw.json");
        ZustandsWechsel z = new ZustandsWechsel(f);
        JSONObject p = new JSONObject().put("unlocked", 0).put("plugged", 1);
        z.eintragen(p, 1_000L);
        assertFalse("erster Wert: Zeitpunkt unbekannt", p.has("lock_changed_at"));
        p = new JSONObject().put("unlocked", 1).put("plugged", 1);
        z.eintragen(p, 2_000_000L);
        assertEquals(2_000_000L, p.getLong("lock_changed_at_ms"));
        assertTrue(p.getString("lock_changed_at").startsWith("1970-01-01T"));
        assertFalse(p.has("plugged_changed_at"));
        // Neustart: gleicher Wert → alter Zeitpunkt bleibt
        ZustandsWechsel neu = new ZustandsWechsel(f);
        p = new JSONObject().put("unlocked", 1).put("plugged", 0);
        neu.eintragen(p, 9_000_000L);
        assertEquals(2_000_000L, p.getLong("lock_changed_at_ms"));
        assertEquals(9_000_000L, p.getLong("plugged_changed_at_ms"));
        // unbekannter Wert ändert nichts
        p = new JSONObject();
        neu.eintragen(p, 10_000_000L);
        assertFalse(p.has("lock_changed_at"));
    }

    @Test
    public void zeitfelderImKatalog() {
        assertTrue(TelemetryFieldCatalog.isDiscoverable("lock_changed_at"));
        assertTrue(TelemetryFieldCatalog.isDiscoverable("plugged_changed_at"));
        assertEquals("timestamp", TelemetryFieldCatalog.get("lock_changed_at").deviceClass);
        assertFalse("ms nur als Attribut/Rohwert", TelemetryFieldCatalog.isDiscoverable("lock_changed_at_ms"));
    }
}
