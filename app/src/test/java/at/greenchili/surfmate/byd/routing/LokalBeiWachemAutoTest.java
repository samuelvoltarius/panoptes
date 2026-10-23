package at.greenchili.surfmate.byd.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.byd.BydVehicleData;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 0.1.16.1 (Koordination 23.10.): Sitzheizung/-lüftung und alle übrigen Befehle mit lokalem UND Cloud-Zweig laufen
 * bei wachem/besetztem Auto nur lokal (localOnlyWhenAwake). Die Cloud (z. B. der Sammelbefehl VENTILATIONHEATING für
 * alle Sitzzonen + Lenkrad) gibt es nur bei nachweislich ausgeschaltetem Auto (CloudPowerGate). Sitzheizung prüft
 * die Wirkung (Echo); keine Wirkung = Fehlschlag.
 */
public class LokalBeiWachemAutoTest {

    static String lies(String rel) throws Exception {
        File f = new File(rel);
        if (!f.isFile()) f = new File("app/" + rel);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    static final String ROUTER = "src/main/java/at/greenchili/surfmate/byd/routing/VehicleCommandRouter.java";

    @Test
    public void sitzeUndAndereMitCloudZweigNurLokalBeiWachemAuto() {
        VehicleCommandRouter.VehicleCommand[] lokal = {
                new VehicleCommandRouter.SeatHeatCommand(1, 1, 0, 0, 0, 0, true),
                new VehicleCommandRouter.SeatVentCommand(2, 1, 0, 0, 0, 0, true),
                new VehicleCommandRouter.SteeringWheelHeatCommand(true),
                new VehicleCommandRouter.CloseAllWindowsCommand(),
                new VehicleCommandRouter.VentAllWindowsCommand(),
                new VehicleCommandRouter.TrunkOpenCommand(),
                new VehicleCommandRouter.TrunkCloseCommand(),
                new VehicleCommandRouter.ClimateOnCommand(22),
                new VehicleCommandRouter.ClimateOffCommand(),
        };
        for (VehicleCommandRouter.VehicleCommand c : lokal) {
            assertTrue(c.name(), c.localOnlyWhenAwake());
            assertTrue(c.name() + " hat lokalen Zweig", c.hasSdkPath());
        }
    }

    @Test
    public void jederBefehlMitBeidenZweigenIstAbgedeckt() throws Exception {
        // Audit: jede Klasse mit SDK_FIRST/CLOUD_FIRST, die auch lokal kann, muss localOnlyWhenAwake haben – außer
        // reinen Cloud-Konfigurationen ohne Fahrzeugaktion (Ladeplan, Smart-Charging; deren Cloud hinter dem Gate).
        String s = lies(ROUTER);
        Matcher m = Pattern.compile("\n    public static final class (\\w+) extends VehicleCommand").matcher(s);
        java.util.List<Integer> starts = new java.util.ArrayList<>();
        java.util.List<String> namen = new java.util.ArrayList<>();
        while (m.find()) { starts.add(m.start()); namen.add(m.group(1)); }
        starts.add(s.length());
        java.util.List<String> fehlt = new java.util.ArrayList<>();
        for (int i = 0; i < namen.size(); i++) {
            String b = s.substring(starts.get(i), starts.get(i + 1));
            boolean beide = b.contains("RoutePreference.SDK_FIRST; }") || b.contains("RoutePreference.CLOUD_FIRST; }");
            if (!beide) continue;
            if (namen.get(i).equals("ChargeScheduleCommand") || namen.get(i).equals("SmartChargingToggleCommand")) continue;
            if (!b.contains("public boolean localOnlyWhenAwake() { return true; }")) fehlt.add(namen.get(i));
        }
        assertEquals("[]", fehlt.toString());
    }

    @Test
    public void localOnlyGiltVorDerRoutenwahlUndCloudNurHinterDemGate() throws Exception {
        String s = lies(ROUTER);
        int ex = s.indexOf("CommandResult result = runLocalOnlyWhileAwake(cmd);");
        int pref = s.indexOf("RoutePreference pref = resolveEffectivePreference(cmd);", ex);
        assertTrue("vor jeder Routenwahl (auch routePolicy cloud_only)", ex > 0 && pref > ex);
        int lo = s.indexOf("private CommandResult runLocalOnlyWhileAwake(VehicleCommand cmd) {");
        String body = s.substring(lo, s.indexOf("\n    }\n", lo));
        assertFalse("bei wachem Auto nie die Cloud", body.contains("runCloudCall"));
        int cc = s.indexOf("private CloudCallResult runCloudCall(final VehicleCommand cmd) {");
        int gate = s.indexOf("CloudCallResult gated = checkCloudPowerGate(cmd);", cc);
        int disp = s.indexOf("executeViaCloud(", cc);
        assertTrue("jeder Cloud-Zweig zuerst durchs Gate", gate > cc && gate < disp);
    }

    @Test
    public void cloudBeiAutoAnVerbotenBeiAutoAusErlaubt() {
        long jetzt = 10_000_000L;
        // Auto an (ACC autoritativ an) → verboten, auch bei altem power_level 0
        assertFalse(CloudPowerGate.evaluate("seat-heat", 0, jetzt - 1000, true, true, jetzt).allowed);
        // power_level 2 (an) → verboten
        assertFalse(CloudPowerGate.evaluate("seat-heat", 2, jetzt - 1000, false, false, jetzt).allowed);
        // unbekannt → verboten (fail-closed)
        assertFalse(CloudPowerGate.evaluate("seat-heat", BydVehicleData.UNAVAILABLE, 0, false, false, jetzt).allowed);
        // veraltet → verboten
        assertFalse(CloudPowerGate.evaluate("steering-heat", 0, jetzt - CloudPowerGate.MAX_AGE_MS - 1, false, false,
                jetzt).allowed);
        // frisch aus → erlaubt
        assertTrue(CloudPowerGate.evaluate("seat-heat", 0, jetzt - 1000, true, false, jetzt).allowed);
        assertTrue(CloudPowerGate.evaluate("seat-vent", 0, jetzt - 1000, false, false, jetzt).allowed);
    }

    @Test
    public void sitzheizungPrueftDieWirkung() throws Exception {
        String c = lies("src/main/java/at/greenchili/surfmate/byd/BydDataCollector.java");
        int a = c.indexOf("public boolean setSeatHeating(int position, int level) {");
        String body = c.substring(a, c.indexOf("public boolean setSeatVentilation(", a));
        int setzen = body.indexOf("\"setSeatHeatingState\"");
        int echo = body.indexOf("warteAufSitzEcho(true, position, normalizedLevel - 1, SITZ_ECHO_MS)");
        assertTrue("Echo nach dem Setzen, auch ohne DiLink 5", setzen > 0 && echo > setzen);
        assertTrue("keine Wirkung = Fehlschlag", body.contains("if (echo == ECHO_AUS) {"));
        // Lüftung hatte schon Rücklesen; dort bleibt es
        int v = c.indexOf("public boolean setSeatVentilation(int position, int level) {");
        assertTrue(c.substring(v, v + 6000).contains("readSeatClimateNow(false, position)"));
    }
}
