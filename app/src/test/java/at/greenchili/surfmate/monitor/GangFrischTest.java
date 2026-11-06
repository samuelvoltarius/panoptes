package at.greenchili.surfmate.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.byd.BydVehicleData;
import at.greenchili.surfmate.recording.RecordingModeManager;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 0.1.38.13 (Seal U DM-i: Handy zeigt „Gang R“, Auto aus, in P): Ein alter Gang-Wert gilt weder als Anzeige noch als
 * Fahrt. Bei Zündung aus stoppt der Poller und der letzte Wert blieb stehen. Die Sperren „nur im Stand“ bleiben fail-closed.
 */
public class GangFrischTest {

    @After
    public void zurueck() {
        GearMonitor.zuendungAn = () -> AccMonitor.isAccOn();
        GearMonitor.testZustand(false);
    }

    private static boolean tripFahrt(int gear) throws Exception {
        Method m = Class.forName("at.greenchili.surfmate.trips.TripDetector").getDeclaredMethod("isDrivingGear", int.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, gear);
    }

    @Test
    public void zuendungAusAlterWertIstKeinGangUndKeineFahrt() throws Exception {
        GearMonitor.testZustand(true);              // Poller lief schon (Fahrt mit Einparken in R)
        GearMonitor.zuendungAn = () -> false;       // jetzt Zündung aus, Poller gestoppt
        assertEquals(BydVehicleData.UNAVAILABLE, GearMonitor.gangFrisch());
        assertTrue(GearMonitor.gangVeraltet());
        assertFalse("Wächter hält das geparkte Auto nicht für fahrend", RecordingModeManager.isDrivingGear(GearMonitor.GEAR_R));
        assertFalse(RecordingModeManager.isDrivingGear(GearMonitor.GEAR_D));
        assertFalse("keine Phantomfahrt", tripFahrt(GearMonitor.GEAR_R));
        JSONObject f = GearMonitor.fehlerAngaben();
        assertTrue(f.isNull("frisch"));
        assertFalse(f.getBoolean("zuendung"));
    }

    @Test
    public void ohneGestartetenPollerBleibtEsWieBisher() throws Exception {
        GearMonitor.testZustand(false);             // andere Quelle / Tests: Verhalten unverändert
        GearMonitor.zuendungAn = () -> false;
        assertFalse(GearMonitor.gangVeraltet());
        assertTrue(RecordingModeManager.isDrivingGear(GearMonitor.GEAR_R));
        assertTrue(RecordingModeManager.isDrivingGear(GearMonitor.GEAR_N));
        assertFalse(RecordingModeManager.isDrivingGear(GearMonitor.GEAR_P));
        assertTrue(tripFahrt(GearMonitor.GEAR_D));
        assertFalse(tripFahrt(GearMonitor.GEAR_P));
    }

    @Test
    public void unbekannteRohwerteNieAlsR() {
        assertEquals("UNKNOWN(0)", GearMonitor.gearToString(0));
        assertEquals("UNKNOWN(255)", GearMonitor.gearToString(255));
        assertFalse(GearMonitor.isValidGearMode(0));
        assertFalse(GearMonitor.isValidGearMode(7));
    }

    private static String q(String rel) throws Exception {
        File f = new File(rel);
        if (!f.exists()) f = new File("app/" + rel);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    public void eineQuelleUndSperrenBleibenFailClosed() throws Exception {
        String l = q("src/main/java/at/greenchili/surfmate/server/LauncherApiHandler.java");
        assertTrue("Handy/Launcher: nur mit Zündung an und frisch",
                l.contains("if (accOn && gm != null && gm.isRunning()) {")
                        && l.contains("int g = at.greenchili.surfmate.monitor.GearMonitor.gangFrisch();")
                        && l.contains("if (gear == null && accOn && !gangPoller) {"));
        assertTrue(q("src/main/java/at/greenchili/surfmate/telenav/DeferredNavManager.kt")
                .contains("GearMonitor.gangFrisch() == GearMonitor.GEAR_R"));
        assertTrue(q("src/main/java/at/greenchili/surfmate/surveillance/GpuSurveillancePipeline.java")
                .contains("GearMonitor.gangFrisch() == at.greenchili.surfmate.monitor.GearMonitor.GEAR_R"));
        assertTrue(q("src/main/java/at/greenchili/surfmate/communication/VehicleCommunicationSafety.java")
                .contains("int g = GearMonitor.gangFrisch();"));
        // „nur im Stand“: unverändert, unbekannt = nicht P
        String guard = q("src/main/java/at/greenchili/surfmate/byd/routing/DrivingSafetyGuard.java");
        assertTrue(guard.contains("if (gear == GearReading.UNKNOWN) return \"gear_unknown\";"));
        assertFalse(guard.contains("gangFrisch") || guard.contains("gangVeraltet"));
        assertFalse(q("src/main/java/at/greenchili/surfmate/byd/Stillstand.java").contains("gangVeraltet"));
        // Log mit Rohwert und Modell; Fehler melden zeigt es in der Vorschau
        assertTrue(q("src/main/java/at/greenchili/surfmate/monitor/GearMonitor.java")
                .contains("+ \" (roh \" + gear + \", Modell \" + modell() + \")\");"));
        assertTrue(q("src/main/java/at/greenchili/surfmate/server/RueckmeldungApiHandler.java").contains("a.put(\"gang\", gangAngaben());"));
        assertTrue(q("src/main/assets/web/local/fehler-melden.html").contains("<span id=\"fmGang\">"));
        for (String s : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject r = new JSONObject(q("src/main/assets/web/i18n/" + s + ".json")).getJSONObject("rueckmeldung");
            assertFalse(s, r.optString("auto_gang").isEmpty() || r.optString("gang_alt").isEmpty());
        }
    }
}
