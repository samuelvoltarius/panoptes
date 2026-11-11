package at.greenchili.surfmate.ui;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Pins the web dashboard's authoritative power, gear and motion-state contract. */
public class DashboardDriveStateAssetTest {

    @Test
    public void heroExposesStableStateGearChargingAndBatteryReadouts() throws IOException {
        String page = readRepositoryFile("app/src/main/assets/web/local/index.html");

        assertTrue(page.contains("id=\"hStateDot\""));
        assertTrue(page.contains("id=\"hState\""));
        assertTrue(page.contains("id=\"hGear\""));
        assertTrue(page.contains("id=\"hCharge\""));
        assertTrue(page.contains("id=\"h12v\" hidden"));
        assertTrue(page.contains(".c-hero { display: grid; grid-template-columns: 180px minmax(0, 1fr)"));
    }

    @Test
    public void resolverUsesDaemonPowerGearChargingAndFreshGpsContracts()
            throws IOException {
        String page = readRepositoryFile("app/src/main/assets/web/local/index.html");
        String resolver = between(
                page,
                "renderState: function () {",
                "renderRange: function () {");

        assertTrue(resolver.contains("st.recordingStatus || {}"));
        assertTrue(resolver.contains("typeof st.acc === 'boolean'"));
        assertTrue(resolver.contains("rec.gear || st.gear"));
        assertTrue(resolver.contains("(!powerOn && gear !== 'P')"));
        assertTrue(resolver.contains("if (c.fault)"));
        assertTrue(resolver.contains("else if (c.charging)"));
        assertTrue(resolver.contains("else if (powerOn && /^(D|M|S|R)$/.test(gear))"));
        assertTrue(resolver.contains("else if (c.plugged)"));
        assertTrue(resolver.contains("if (gear)"));

        String handler = readRepositoryFile(
                "app/src/main/java/at/greenchili/surfmate/charging/ChargingApiHandler.java");
        assertTrue(handler.contains(
                "boolean hasLiveDetectorSample = after.observedAtMs > 0;"));

        int fault = resolver.indexOf("if (c.fault)");
        int charging = resolver.indexOf("else if (c.charging)");
        int driving = resolver.indexOf("else if (powerOn && /^(D|M|S|R)$/.test(gear))");
        int plugged = resolver.indexOf("else if (c.plugged)");
        assertTrue(fault >= 0 && charging > fault && driving > charging && plugged > driving);
    }

    @Test
    public void statusRefreshPromotesFreshStateIntoHero()
            throws IOException {
        String page = readRepositoryFile("app/src/main/assets/web/local/index.html");
        String refresh = between(
                page,
                "fromStatus: function (st) {",
                "renderState: function () {");

        assertTrue(refresh.contains("this.status = st;"));
        assertTrue(refresh.contains("this.renderState();"));
        assertTrue(refresh.contains("bat.available !== false && !bat.isStale && v > 0"));
        assertTrue(refresh.contains("var live = !(gps.isStale || gps.isCached || gps.isFixStale);"));
        assertTrue(refresh.contains("setText('hGps', (live ?"));
    }

    private static String between(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start + startMarker.length());
        assertTrue("missing start marker: " + startMarker, start >= 0);
        assertTrue("missing end marker: " + endMarker, end > start);
        return source.substring(start, end);
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return at.greenchili.surfmate.testutil.Quelltext.lies(candidate);
            }
            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return at.greenchili.surfmate.testutil.Quelltext.lies(fromModule);
            }
            current = at.greenchili.surfmate.testutil.RepoWurzel.hoch(current);
        }
        throw new AssertionError("Could not locate repository file: " + relativePath);
    }
}
