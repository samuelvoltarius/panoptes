package at.greenchili.surfmate.ladeplan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.mqtt.TelemetryFieldCatalog;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** 0.1.21 HA-Sensoren zum Ladeplan: nur lesend, über die Telemetrie-Linie (wie Camping). */
public class LadeplanTelemetrieTest {

    @Test
    public void ausNurPhase() throws Exception {
        JSONObject p = new JSONObject();
        LadeplanTelemetrie.eintragen(p, new JSONObject().put("zustand", new JSONObject().put("phase", "aus")));
        assertEquals("aus", p.getString("ladeplan_phase"));
        assertEquals(0, p.getInt("ladeplan_laedt"));
        assertFalse(p.has("ladeplan_kwh"));
        JSONObject leer = new JSONObject();
        LadeplanTelemetrie.eintragen(leer, null);
        assertEquals(0, leer.length());
    }

    @Test
    public void planMitVorschau() throws Exception {
        JSONObject v = new JSONObject().put("start", 1_793_210_400_000L).put("fertig", 1_793_215_800_000L)
                .put("kwh", 16.0).put("kosten", 3.2).put("ersparnis", 1.6).put("stunden", new JSONArray());
        JSONObject s = new JSONObject().put("zustand", new JSONObject().put("phase", "laedt")).put("vorschau", v);
        JSONObject p = new JSONObject();
        LadeplanTelemetrie.eintragen(p, s);
        assertEquals("laedt", p.getString("ladeplan_phase"));
        assertEquals(1, p.getInt("ladeplan_laedt"));
        assertTrue(p.getString("ladeplan_naechster_start").matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}[+-Z].*"));
        assertEquals(16.0, p.getDouble("ladeplan_kwh"), 1e-9);
        assertEquals(3.2, p.getDouble("ladeplan_kosten"), 1e-9);
        assertEquals(1.6, p.getDouble("ladeplan_ersparnis"), 1e-9);
        for (java.util.Iterator<String> it = p.keys(); it.hasNext(); ) { String k = it.next(); assertFalse("kein Börsenpreis in HA: " + k, k.contains("boerse")); }
    }

    @Test
    public void alleSchluesselImKatalogUndAuffindbar() {
        for (String k : LadeplanTelemetrie.SCHLUESSEL) {
            assertTrue(k, TelemetryFieldCatalog.isDiscoverable(k));
        }
    }
}
