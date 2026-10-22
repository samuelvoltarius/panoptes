package at.greenchili.surfmate.automation.action;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Tacho/HUD-Aktionen nur bei erkannter Fähigkeit; Aufsperren nie im Katalog (21.10.2026). */
public class ActionsCapabilityFilterTest {

    @Test
    public void clusterAndHudActionsNeedTheCapability() {
        assertFalse(Actions.isOffered("clusterBrightness", false, true));
        assertFalse(Actions.isOffered("stopClusterCast", false, true));
        assertTrue(Actions.isOffered("clusterBrightness", true, false));
        assertFalse(Actions.isOffered("hudBrightness", true, false));
        assertFalse(Actions.isOffered("hudPower", true, false));
        assertTrue(Actions.isOffered("hudPower", false, true));
        assertTrue(Actions.isOffered("screenBrightness", false, false));
    }

    @Test
    public void unlockIsNeverOffered() {
        assertFalse(Actions.isOffered("unlock", true, true));
        assertTrue(Actions.isOffered("lock", false, false));
    }

    @Test
    public void clusterTargetsAreStrippedFromEnumsOnly() throws Exception {
        JSONObject action = new JSONObject()
                .put("id", "showCameraFeed")
                .put("variables", new JSONArray()
                        .put(new JSONObject().put("id", "target").put("type", "enum")
                                .put("options", new JSONArray()
                                        .put(new JSONObject().put("id", "main").put("label", "Hauptdisplay"))
                                        .put(new JSONObject().put("id", "cluster").put("label", "Tacho"))))
                        .put(new JSONObject().put("id", "display").put("type", "enum")
                                .put("options", new JSONArray()
                                        .put(new JSONObject().put("id", "0").put("label", "Hauptdisplay"))
                                        .put(new JSONObject().put("id", "1").put("label", "Tacho"))))
                        // Kein Enum: bleibt unangetastet, auch wenn eine id "cluster" heißt.
                        .put(new JSONObject().put("id", "cluster").put("type", "string")));
        Actions.stripClusterTargets(action, "Tacho");
        JSONArray vars = action.getJSONArray("variables");
        assertEquals(1, vars.getJSONObject(0).getJSONArray("options").length());
        assertEquals("main", vars.getJSONObject(0).getJSONArray("options").getJSONObject(0).getString("id"));
        assertEquals(1, vars.getJSONObject(1).getJSONArray("options").length());
        assertEquals("0", vars.getJSONObject(1).getJSONArray("options").getJSONObject(0).getString("id"));
        assertEquals(3, vars.length());
    }

    @Test
    public void catalogIdsInFilterStillExist() throws Exception {
        String src = new String(Files.readAllBytes(Paths.get(
                "src/main/java/at/greenchili/surfmate/automation/action/Actions.java")), StandardCharsets.UTF_8);
        for (String id : new String[] {"clusterBrightness", "stopClusterCast", "hudBrightness", "hudPower"}) {
            assertTrue(id, src.contains("new Label(\"" + id + "\""));
        }
        // 0.1.9: „unlock“ gibt es im Katalog gar nicht mehr (Aufsperren komplett entfernt)
        assertTrue(!src.contains("new Label(\"unlock\""));
        int section = src.indexOf("private JSONObject sectionJson(");
        assertTrue(src.indexOf("isOffered(action.getLabel().getId()", section) > section);
        assertTrue(src.indexOf("stripClusterTargets(opt, clusterText)", section) > section);
    }

    @Test
    public void appRailHidesProjectionWithoutCluster() throws Exception {
        String main = new String(Files.readAllBytes(Paths.get(
                "src/main/java/at/greenchili/surfmate/ui/MainActivity.kt")), StandardCharsets.UTF_8);
        assertTrue(main.contains(".allowsRailKey(item.key)"));
        assertTrue(main.contains("VehicleCapabilityCache.refreshIfUnknown"));
        String cache = new String(Files.readAllBytes(Paths.get(
                "src/main/java/at/greenchili/surfmate/ui/navigation/VehicleCapabilityCache.kt")), StandardCharsets.UTF_8);
        assertTrue(cache.contains("NavigationRailCatalog.PROJECTION -> cluster == true"));
    }
}
