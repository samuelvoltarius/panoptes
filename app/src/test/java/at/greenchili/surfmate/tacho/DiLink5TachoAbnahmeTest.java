package at.greenchili.surfmate.tacho;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Fail-closed contract for the not-yet-approved DiLink-5 arbitrary-app path. */
public class DiLink5TachoAbnahmeTest {

    private static JSONObject platform() throws Exception {
        return new JSONObject()
                .put("dilink", "5")
                .put("dilinkSicher", true)
                .put("sharedFission", true)
                .put("shellRechte", true)
                .put("tachoDisplay", new JSONObject()
                        .put("id", 2).put("name", "shared_fission_bg_XDJAScreenProjection_0")
                        .put("groesse", "1920x720"));
    }

    @Test
    public void diagnosisSeparatesCapabilitiesFromMissingVehicleEvidence() throws Exception {
        JSONObject d = DiLink5TachoAbnahme.diagnose(platform());
        assertFalse(d.getBoolean("produktivFreigegeben"));
        assertFalse(d.getBoolean("probeStartbar"));
        assertEquals("nur_diagnose", d.getString("modus"));
        assertTrue(d.getJSONObject("capabilities").getBoolean("dilink5Erkannt"));
        assertTrue(d.getJSONObject("capabilities").getBoolean("sharedFissionGefunden"));

        JSONArray open = d.getJSONArray("offeneFahrzeugmessungen");
        assertEquals(8, open.length());
        String all = open.toString();
        for (String required : new String[] {"linker_blinker_kamera_vorrang",
                "linker_blinker_paket_restore", "linker_blinker_geometrie_restore",
                "rechter_blinker_kamera_vorrang", "rechter_blinker_paket_restore",
                "rechter_blinker_geometrie_restore", "fehler_fallback", "stop_acc_restore"}) {
            assertTrue(required, all.contains(required));
        }
    }

    @Test
    public void packageContractIsSpecificButCannotStartProjection() throws Exception {
        JSONObject d = DiLink5TachoAbnahme.messvertrag(platform(), "com.example.navigation");
        JSONObject probe = d.getJSONObject("paketProbe");
        assertEquals("com.example.navigation", probe.getString("paket"));
        assertTrue(probe.getBoolean("paketGueltig"));
        assertFalse(probe.getBoolean("startbar"));
        assertTrue(probe.getBoolean("schreibtNichts"));
        assertEquals(5, probe.getJSONArray("schritte").length());

        JSONObject invalid = DiLink5TachoAbnahme.messvertrag(platform(), "bad; am start");
        assertFalse(invalid.getJSONObject("paketProbe").getBoolean("paketGueltig"));
        assertFalse(invalid.getJSONObject("paketProbe").getBoolean("startbar"));
    }

    @Test
    public void capabilityReportNeverOverridesProductivePolicy() throws Exception {
        TachoFaehigkeit.Eingaben e = new TachoFaehigkeit.Eingaben();
        e.dilink5Gewaehlt = true;
        e.sdk = 31;
        e.uid = "2000";
        e.warnleuchtenFrei = "ja";
        e.displays = "mDisplayId=2\n mName=\"shared_fission_bg_XDJAScreenProjection_0\"\n 1920 x 720";

        JSONObject r = TachoFaehigkeit.bewerte(e);
        assertEquals("nein", r.getJSONObject("andereApps").getString("status"));
        assertEquals(DiLink5TachoAbnahme.BLOCK_CODE,
                r.getJSONObject("andereApps").getString("code"));
        assertFalse(r.getJSONObject("dilink5Abnahme").getBoolean("produktivFreigegeben"));
        String report = TachoFaehigkeit.alsText(r);
        assertTrue(report.contains("DiLink-5-Appfreigabe: gesperrt"));
        assertTrue(report.contains("linker_blinker_kamera_vorrang"));
        assertTrue(report.contains("fehler_fallback"));
    }

    @Test
    public void implementationAndEndpointContainNoProjectionStart() throws Exception {
        String source = TachoFaehigkeitTest.lies(
                "app/src/main/java/at/greenchili/surfmate/tacho/DiLink5TachoAbnahme.java");
        for (String forbidden : new String[] {"DiLink5ClusterCast.start", "startActivity(",
                "moveTaskToDisplay", "service call", "am start"}) {
            assertFalse(forbidden, source.contains(forbidden));
        }
        String api = TachoFaehigkeitTest.lies(
                "app/src/main/java/at/greenchili/surfmate/server/TachoApiHandler.java");
        int route = api.indexOf("p.equals(\"/api/tacho/dilink5-abnahme\")");
        int next = api.indexOf("p.equals(\"/api/tacho/navi\")", route);
        String block = api.substring(route, next);
        assertTrue(block.contains("bewegungsGrund()"));
        assertTrue(block.contains("DiLink5TachoAbnahme.messvertrag"));
        assertFalse(block.contains("TachoNaviApp.probe"));
        assertFalse(block.contains("DiLink5ClusterCast"));
    }

    @Test
    public void visibleBlockReasonExistsInAllFiveLanguages() throws Exception {
        for (String language : new String[] {"de", "en", "fr", "it", "nl"}) {
            String json = TachoFaehigkeitTest.lies(
                    "app/src/main/assets/web/i18n/" + language + ".json");
            JSONObject root = new JSONObject(json);
            String reason = root.getJSONObject("vehicle_control")
                    .getString("cluster_app_dilink5_blocked");
            assertFalse(language, reason.trim().isEmpty());
        }
    }
}
