package at.greenchili.surfmate.byd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.mqtt.HomeAssistantDiscovery;
import at.greenchili.surfmate.mqtt.TelemetryFieldCatalog;
import at.greenchili.surfmate.testutil.Quelltext;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Paths;

/**
 * 0.1.16 (Koordination 23.10.2026): AC_TEMP_INSIDE bzw. tempInCar ist ohne Beleg der Klima-Sollwert, nie eine
 * Innentemperatur – nicht in HA/MQTT, nicht in Automationen, nicht in REST/Web.
 */
public class KabinenTemperaturTest {

    @Test
    public void ohneBelegIstNichtsEineMessung() {
        assertTrue("noch kein Modell belegt", KabinenTemperatur.MESSUNG_BELEGT.isEmpty());
        assertFalse(KabinenTemperatur.acTempInsideIstMessung("dolphin_surf"));
        assertFalse(KabinenTemperatur.acTempInsideIstMessung(null));
        assertFalse(KabinenTemperatur.cloudTempInCarIstMessung("seal"));
    }

    @Test
    public void sollwertIstEigenesFeldUndKeineKabinentemperatur() {
        long jetzt = System.currentTimeMillis();
        BydVehicleData d = new BydVehicleData.Builder().klimaSollwertC(33, jetzt).build();
        assertTrue(d.hasFreshKlimaSollwert());
        assertEquals(33.0, d.klimaSollwertC, 0.0);
        assertTrue(Double.isNaN(d.insideTempC));
        assertTrue(Double.isNaN(d.insideTempCelsius));
        assertFalse(d.hasFreshCabinTemperature());
        // wird bei toBuilder mitgetragen
        BydVehicleData weiter = d.toBuilder().build();
        assertEquals(33.0, weiter.klimaSollwertC, 0.0);
        assertEquals(jetzt, weiter.klimaSollwertReadAt);
    }

    @Test
    public void alterSollwertIstNichtFrisch() {
        long alt = System.currentTimeMillis() - BydVehicleData.CABIN_TEMP_MAX_AGE_MS - 1L;
        assertFalse(new BydVehicleData.Builder().klimaSollwertC(25, alt).build().hasFreshKlimaSollwert());
    }

    @Test
    public void innentemperaturEntitaetenSindAusgemustert() {
        for (String k : new String[]{"inside_temp", "cabin_temp"}) {
            assertTrue(k, TelemetryFieldCatalog.istAusgemustert(k));
            assertFalse(k + " nicht mehr normal angekündigt", TelemetryFieldCatalog.isDiscoverable(k));
        }
        assertFalse(TelemetryFieldCatalog.istAusgemustert("ext_temp"));
        assertTrue(TelemetryFieldCatalog.isDiscoverable("ext_temp"));
    }

    @Test
    public void discoveryZeigtAlteEntitaetAlsNichtVerfuegbar() throws Exception {
        JSONObject snap = new JSONObject().put("soc", 64).put("inside_temp", JSONObject.NULL)
                .put("cabin_temp", JSONObject.NULL);
        JSONObject bundle = new JSONObject(HomeAssistantDiscovery.buildBundle(
                "abc", null, "Dolphin Surf", "0.1.16", "panoptes/x", snap, null, false));
        JSONObject cmps = bundle.getJSONObject("cmps");
        for (String k : new String[]{"inside_temp", "cabin_temp"}) {
            JSONObject c = cmps.getJSONObject(k);
            assertEquals("gleiche unique_id wie bisher", "surfmate_abc_" + k, c.getString("unique_id"));
            assertTrue(c.getString("name").contains("entfernt"));
            JSONArray av = c.getJSONArray("availability");
            assertEquals(1, av.length());
            assertEquals("panoptes/x/ausgemustert", av.getJSONObject(0).getString("topic"));
        }
        assertTrue(cmps.has("soc"));
    }

    @Test
    public void collectorSchreibtAcTempInsideNurAlsSollwert() throws Exception {
        String q = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/byd/BydDataCollector.java"));
        int start = q.indexOf("private boolean collectCabinTemperature(");
        int ende = q.indexOf("\n    }\n", start);
        String m = q.substring(start, ende);
        int pruefung = m.indexOf("KabinenTemperatur.acTempInsideIstMessung(");
        int sollwert = m.indexOf("b.klimaSollwertC(raw");
        int innen = m.indexOf("b.insideTempC(raw");
        assertTrue(pruefung > 0 && sollwert > pruefung && innen > sollwert);
        assertTrue("Cloud-tempInCar nur bei Beleg",
                q.contains("KabinenTemperatur.cloudTempInCarIstMessung(KabinenTemperatur.aktuellesModell())"));
    }
}
