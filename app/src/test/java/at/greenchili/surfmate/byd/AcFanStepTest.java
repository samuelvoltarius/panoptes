/*
 * Based on OverDrive's AcFanStepTest (PR #373, MIT License, Copyright (c) Yash Srivastava and
 * OverDrive contributors); behavioural tests of the step core added for Panoptes.
 */
package at.greenchili.surfmate.byd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** Lüfter eine Stufe ±: Grenzen 1 und 7, Klima aus, nur lokaler HAL-Weg. Kein echtes Fahrzeug. */
public class AcFanStepTest {

    private static final int AN = 1, AUS = 0;

    @BeforeClass
    public static void configureLogger() {
        at.greenchili.surfmate.logging.DaemonLogger.Config config =
                new at.greenchili.surfmate.logging.DaemonLogger.Config();
        config.enableConsoleLog = false;
        config.enableFileLog = false;
        config.enableStdoutLog = true;
        at.greenchili.surfmate.logging.DaemonLogger.configure(config);
    }

    /** Merkt sich jede Schreib-Anfrage statt sie an ein Auto zu schicken. */
    private static final class FakeWrite implements java.util.function.IntPredicate {
        final List<Integer> writes = new ArrayList<>();
        final boolean accept;
        FakeWrite(boolean accept) { this.accept = accept; }
        @Override public boolean test(int level) { writes.add(level); return accept; }
    }

    private static int step(int delta, int power, int level, FakeWrite write) {
        return BydDataCollector.stepAcFanLevel(delta, () -> power, () -> level, write);
    }

    @Test
    public void stepMovesOneLevelFromTheCurrentFan() {
        assertEquals(4, BydDataCollector.nextAcFanLevel(3, 1));
        assertEquals(2, BydDataCollector.nextAcFanLevel(3, -1));
        FakeWrite w = new FakeWrite(true);
        assertEquals(4, step(1, AN, 3, w));
        assertEquals(java.util.Collections.singletonList(4), w.writes);
    }

    @Test
    public void slowerAtOneStaysAtOneWithoutAWrite() {
        assertEquals(1, BydDataCollector.nextAcFanLevel(1, -1));
        assertEquals(1, BydDataCollector.nextAcFanLevel(2, -6));
        FakeWrite w = new FakeWrite(true);
        assertEquals(1, step(-1, AN, 1, w));
        assertTrue("an der Grenze 1 darf nichts geschrieben werden", w.writes.isEmpty());
        // 2 → 1 ist ein echter Schritt
        assertEquals(1, step(-1, AN, 2, w));
        assertEquals(java.util.Collections.singletonList(1), w.writes);
    }

    @Test
    public void fasterAtSevenStaysAtSevenWithoutAWrite() {
        assertEquals(7, BydDataCollector.nextAcFanLevel(7, 1));
        assertEquals(7, BydDataCollector.nextAcFanLevel(6, 6));
        FakeWrite w = new FakeWrite(true);
        assertEquals(7, step(1, AN, 7, w));
        assertTrue("an der Grenze 7 darf nichts geschrieben werden", w.writes.isEmpty());
        assertEquals(7, step(1, AN, 6, w));
        assertEquals(java.util.Collections.singletonList(7), w.writes);
    }

    @Test
    public void refusedWhileTheAcIsOffOrUnreadable() {
        FakeWrite w = new FakeWrite(true);
        assertEquals(BydVehicleData.UNAVAILABLE, step(1, AUS, 3, w));
        assertEquals(BydVehicleData.UNAVAILABLE, step(-1, AUS, 3, w));
        assertEquals(BydVehicleData.UNAVAILABLE, step(1, BydVehicleData.UNAVAILABLE, 3, w));
        assertTrue("bei Klima aus darf nie geschrieben werden", w.writes.isEmpty());
    }

    @Test
    public void stoppedOrUnreadableFanRefusesTheStep() {
        assertEquals(BydVehicleData.UNAVAILABLE, BydDataCollector.nextAcFanLevel(0, 1));
        assertEquals(BydVehicleData.UNAVAILABLE,
                BydDataCollector.nextAcFanLevel(BydVehicleData.UNAVAILABLE, 1));
        assertEquals(BydVehicleData.UNAVAILABLE, BydDataCollector.nextAcFanLevel(8, -1));
        FakeWrite w = new FakeWrite(true);
        assertEquals(BydVehicleData.UNAVAILABLE, step(1, AN, 0, w));
        assertEquals(BydVehicleData.UNAVAILABLE, step(1, AN, BydVehicleData.UNAVAILABLE, w));
        assertTrue(w.writes.isEmpty());
    }

    @Test
    public void failedWriteIsReportedAsFailure() {
        FakeWrite w = new FakeWrite(false);
        assertEquals(BydVehicleData.UNAVAILABLE, step(1, AN, 3, w));
        assertEquals(java.util.Collections.singletonList(4), w.writes);
    }

    @Test
    public void instanceStepUsesTheLocalHalSetterOnly() throws Exception {
        String source = read("app/src/main/java/at/greenchili/surfmate/byd/BydDataCollector.java");
        int start = source.indexOf("public int stepAcFanLevel(int delta)");
        int end = source.indexOf("public boolean setAcWindMode(", start);
        assertTrue(start >= 0 && end > start);
        String step = source.substring(start, end);
        assertTrue(step.contains("this::readAcPowerNow"));
        assertTrue(step.contains("this::readAcFanLevelNow"));
        assertTrue(step.contains("this::setAcFanLevel"));
        assertFalse(step.toLowerCase(java.util.Locale.ROOT).contains("cloud"));

        // setAcFanLevel behält den DiLink-3-Rückfall (generischer Feature-Write).
        int set = source.indexOf("public boolean setAcFanLevel(int level)");
        String setter = source.substring(set, source.indexOf("// ---- Lüfter eine Stufe", set));
        assertTrue(setter.contains("BydFeatureIds.AC_WIND_LEVEL_SET"));

        String router = read("app/src/main/java/at/greenchili/surfmate/byd/routing/VehicleCommandRouter.java");
        int r = router.indexOf("public static final class ClimateStepFanCommand extends VehicleCommand");
        assertTrue(r >= 0);
        String cmd = router.substring(r, Math.min(router.length(), r + 1200));
        assertTrue(cmd.contains("RoutePreference.SDK_ONLY"));
        assertTrue(cmd.contains("c.stepAcFanLevel(delta)"));
    }

    @Test
    public void climateEndpointAndKeyMappingExposeTheStep() throws Exception {
        String handler = read("app/src/main/java/at/greenchili/surfmate/server/VehicleControlApiHandler.java");
        assertTrue(handler.contains("case \"step_fan\": {"));
        assertTrue(handler.contains("new VehicleCommandRouter.ClimateStepFanCommand(delta)"));

        String keymap = read("app/src/main/assets/web/shared/key-mapping.js");
        assertTrue(keymap.contains("id: 'ac_fan_step',     i18n: 'keymap.act_ac_fan_step'"));
        assertTrue(keymap.contains("body: '{\"action\":\"step_fan\",\"delta\":${v}}'"));
        assertTrue(keymap.contains("{ v: '1', i18n: 'keymap.fan_faster' }, { v: '-1', i18n: 'keymap.fan_slower' }"));
        assertTrue(keymap.contains("ac_fan_step: 'climate'"));

        for (String lang : new String[]{"de", "en", "fr", "it", "nl"}) {
            org.json.JSONObject web = new org.json.JSONObject(read("app/src/main/assets/web/i18n/" + lang + ".json"));
            org.json.JSONObject km = web.getJSONObject("keymap");
            for (String k : new String[]{"act_ac_fan_step", "fan_faster", "fan_slower"}) {
                assertFalse(lang + ":" + k, km.optString(k).trim().isEmpty());
            }
        }
    }

    @Test
    public void automationOffersTheStepInTheClimateCategory() throws Exception {
        String actions = read("app/src/main/java/at/greenchili/surfmate/automation/action/Actions.java");
        int start = actions.indexOf("new Label(\"stepAcFan\", \"automation.step_ac_fan\")");
        assertTrue(start >= 0);
        String step = actions.substring(start, actions.indexOf("addAction(", start));
        assertTrue(step.contains("\"/api/vehicle/climate\""));
        assertTrue(step.contains("\"{\\\"action\\\":\\\"step_fan\\\",\\\"delta\\\":${delta}}\""));
        assertTrue(step.contains("new Label(\"1\", \"automation.step_faster\")"));
        assertTrue(step.contains("new Label(\"-1\", \"automation.step_slower\")"));

        String categories = read("app/src/main/java/at/greenchili/surfmate/automation/AutomationCategories.java");
        assertTrue(categories.contains("\"setAcFan\", \"stepAcFan\""));

        for (String lang : new String[]{"de", "en", "fr", "it", "nl"}) {
            org.json.JSONObject srv = new org.json.JSONObject(read("app/src/main/assets/server-i18n/" + lang + ".json"));
            org.json.JSONObject a = srv.getJSONObject("automation");
            for (String k : new String[]{"step_ac_fan", "step_ac_fan_description", "step_faster", "step_slower"}) {
                assertFalse(lang + ":" + k, a.optString(k).trim().isEmpty());
            }
        }
    }

    private static String read(String relativePath) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = at.greenchili.surfmate.testutil.RepoWurzel.hoch(current);
        }
        throw new AssertionError("Could not locate repository file: " + relativePath);
    }
}
