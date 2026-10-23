package at.greenchili.surfmate.byd.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.testutil.Quelltext;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;

/**
 * 0.1.16 Cloud-Klima (Koordination 23.10.2026): ausdrücklicher Nur-Cloud-Weg nur bei frischem „Auto aus“,
 * CLOSEAIR nur mit höchstens 60 s altem Nachweis, lokale Klimabefehle mit Wirkungsprüfung (Echo) in der
 * Reihenfolge start → Sollwert.
 */
public class CloudKlimaTest {

    private static final long NOW = 1_800_000_000_000L;

    @Test
    public void fernklimaBefehleSindNurCloud() {
        VehicleCommandRouter.ClimateRemoteOnCommand on = new VehicleCommandRouter.ClimateRemoteOnCommand(25, 10);
        assertEquals("climate-remote-on", on.name());
        assertFalse("keine lokale Seite", on.hasSdkPath());
        assertTrue(on.hasCloudPath());
        assertEquals(VehicleCommandRouter.RoutePreference.CLOUD_ONLY, on.defaultPreference());
        assertFalse(new VehicleCommandRouter.ClimateRemoteOnCommand(32, 10).hasCloudPath());
        assertFalse(new VehicleCommandRouter.ClimateRemoteOnCommand(14, 10).hasCloudPath());
        assertFalse(new VehicleCommandRouter.ClimateRemoteOnCommand(25, 12).hasCloudPath());
        VehicleCommandRouter.ClimateRemoteOffCommand off = new VehicleCommandRouter.ClimateRemoteOffCommand();
        assertFalse(off.hasSdkPath());
        assertEquals(VehicleCommandRouter.RoutePreference.CLOUD_ONLY, off.defaultPreference());
    }

    @Test
    public void closeairBrauchtFrischenNachweisUnter60s() {
        for (String n : new String[]{"climate-off", "climate-remote-off"}) {
            assertTrue(n, CloudPowerGate.evaluate(n, 0, NOW - 60_000L, true, false, NOW).allowed);
            CloudPowerGate.Decision d = CloudPowerGate.evaluate(n, 0, NOW - 61_000L, true, false, NOW);
            assertFalse(n, d.allowed);
            assertEquals(CloudPowerGate.REASON_STATE_STALE, d.reasonKey);
            assertFalse("Auto an → nie", CloudPowerGate.evaluate(n, 2, NOW, true, false, NOW).allowed);
        }
        assertTrue("OPENAIR weiter bis 180 s",
                CloudPowerGate.evaluate("climate-remote-on", 0, NOW - 170_000L, true, false, NOW).allowed);
        assertFalse(CloudPowerGate.evaluate("climate-remote-on", 2, NOW, true, false, NOW).allowed);
        assertFalse("ACC an", CloudPowerGate.evaluate("climate-remote-on", 0, NOW, true, true, NOW).allowed);
    }

    @Test
    public void apiLehntFernklimaOhneNachweisAb() throws Exception {
        // JVM-Test: keine Fahrzeugdaten → Zustand unbekannt → 409, nichts geht an die Cloud
        for (String body : new String[]{"{\"action\":\"power_on\",\"remote\":true,\"temp\":25}",
                "{\"action\":\"power_off\",\"remote\":true}"}) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            assertTrue(at.greenchili.surfmate.server.VehicleControlApiHandler.handle(
                    "POST", "/api/vehicle/climate", body, out));
            String r = new String(out.toByteArray(), StandardCharsets.UTF_8);
            assertTrue(r, r.contains("409"));
            JSONObject j = new JSONObject(r.substring(r.indexOf('{')));
            assertFalse(j.getBoolean("success"));
            assertEquals(CloudPowerGate.REASON_STATE_UNKNOWN, j.getString("reason"));
        }
    }

    @Test
    public void apiPrueftFernklimaEingaben() throws Exception {
        for (String body : new String[]{"{\"action\":\"power_on\",\"remote\":true,\"temp\":33}",
                "{\"action\":\"power_on\",\"remote\":true,\"temp\":25.5}",
                "{\"action\":\"power_on\",\"remote\":true,\"remoteDurationMinutes\":12}"}) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            at.greenchili.surfmate.server.VehicleControlApiHandler.handle("POST", "/api/vehicle/climate", body, out);
            String r = new String(out.toByteArray(), StandardCharsets.UTF_8);
            assertTrue(body + " → " + r, r.contains("\"success\":false"));
            assertFalse(body, r.contains("cloud_gate"));
        }
    }

    @Test
    public void lokaleReihenfolgeUndEcho() throws Exception {
        String q = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/byd/routing/VehicleCommandRouter.java"));
        int a = q.indexOf("public static final class ClimateOnCommand");
        String on = q.substring(a, q.indexOf("public static final class ClimateOffCommand", a));
        int start = on.indexOf("c.setAcPower(true)");
        int echo = on.indexOf("c.warteAufKlimaEcho(true, KLIMA_ECHO_MS)");
        int soll = on.indexOf("c.setAcTemperature(0, tempCelsius)");
        int sollEcho = on.indexOf("c.warteAufSollwertEcho(tempCelsius, SOLLWERT_ECHO_MS)");
        assertTrue("start → Echo → Sollwert → Echo", start > 0 && echo > start && soll > echo && sollEcho > soll);
        int b = q.indexOf("public static final class ClimateOffCommand");
        String off = q.substring(b, q.indexOf("KLIMA_ECHO_MS = ", b));
        assertTrue(off.indexOf("c.warteAufKlimaEcho(false, KLIMA_ECHO_MS)") > off.indexOf("c.setAcPower(false)"));
        assertTrue("bei Auto an nie Cloud", on.contains("public boolean localOnlyWhenAwake() { return true; }"));
        String col = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/byd/BydDataCollector.java"));
        assertTrue("Rückgabecode immer geloggt", col.contains("logger.info(\"AC \" + methodName + \"(0) Rückgabe=\" + result);"));
    }
}
