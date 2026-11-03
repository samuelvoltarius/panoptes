package at.greenchili.surfmate.server;

import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Contract for the user-entered vehicle fallback used by unknown regional models. */
public class ManualVehicleProfileContractTest {

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    @Test
    public void systemPageOffersNameBatteryAndSocInAllCatalogs() throws Exception {
        String html = read("src/main/assets/web/local/system.html");
        String js = read("src/main/assets/web/shared/system.js");
        assertTrue(html.contains("id=\"manualVehicleCard\""));
        assertTrue(html.contains("id=\"manualVehicleName\""));
        assertTrue(html.contains("id=\"manualVehicleBattery\""));
        assertTrue(html.contains("id=\"manualVehicleSoc\""));
        assertTrue(js.contains("/api/system/manual-vehicle"));
        for (String lang : new String[]{"de", "en", "fr", "it", "nl"}) {
            JSONObject catalog = new JSONObject(read(
                    "src/main/assets/web/i18n/" + lang + ".json"));
            JSONObject system = catalog.getJSONObject("system");
            assertTrue(lang, system.getString("manual_vehicle_title").length() > 3);
            assertTrue(lang, system.getString("manual_vehicle_invalid").contains("100"));
        }
    }

    @Test
    public void backendKeepsManualSocOutOfVehicleMonitorAndLabelsFallback() throws Exception {
        String api = read("src/main/java/at/greenchili/surfmate/server/SystemApiHandler.java");
        String status = read("src/main/java/at/greenchili/surfmate/server/HttpServer.java");
        assertTrue(api.contains("batteryKwh must be between 5 and 120"));
        assertTrue(api.contains("soc must be between 0 and 100"));
        assertTrue(api.contains("vehicle.put(\"manualSoc\""));
        assertTrue(status.contains("SystemApiHandler.manualSocFallback()"));
        assertTrue(status.contains("soc.put(\"source\", \"manual\")"));
        assertTrue(status.contains("soc.put(\"source\", \"vehicle\")"));
    }
}
