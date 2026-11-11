package at.greenchili.surfmate.surveillance;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import at.greenchili.surfmate.monitor.GearMonitor;

public class BsOverlayFailureTestHookTest {
    private static final long NOW = 1_000_000L;

    private static final class FakeAccess
            implements BsOverlayFailureTestHook.MarkerAccess {
        BsOverlayFailureTestHook.Marker marker;
        boolean deleteResult = true;
        int reads;
        int deletes;

        FakeAccess(BsOverlayFailureTestHook.Marker marker) {
            this.marker = marker;
        }

        @Override public BsOverlayFailureTestHook.Marker read() {
            reads++;
            return marker;
        }

        @Override public boolean delete() {
            deletes++;
            return deleteResult;
        }
    }

    private static BsOverlayFailureTestHook.Marker validMarker() {
        return new BsOverlayFailureTestHook.Marker(true, true, false,
                BsOverlayFailureTestHook.SHELL_UID,
                BsOverlayFailureTestHook.PRIVATE_MODE,
                NOW - 1_000L,
                BsOverlayFailureTestHook.MARKER_CONTENT);
    }

    private static BsOverlayFailureTestHook.Gates validGates() {
        return new BsOverlayFailureTestHook.Gates(true, true, true,
                true, true, GearMonitor.GEAR_P);
    }

    private static boolean consume(BsOverlayFailureTestHook.Gates gates,
                                   FakeAccess access) {
        return BsOverlayFailureTestHook.consume(gates, access, NOW);
    }

    @Test
    public void validMarkerInParkIsConsumedExactlyOnce() {
        FakeAccess access = new FakeAccess(validMarker());
        assertTrue(consume(validGates(), access));
        assertTrue(access.reads == 1 && access.deletes == 1);

        access.marker = BsOverlayFailureTestHook.Marker.absent();
        assertFalse(consume(validGates(), access));
        assertTrue(access.reads == 2 && access.deletes == 1);
    }

    @Test
    public void disabledBuildNeverTouchesMarker() {
        FakeAccess access = new FakeAccess(validMarker());
        BsOverlayFailureTestHook.Gates gates = new BsOverlayFailureTestHook.Gates(
                false, true, true, true, true, GearMonitor.GEAR_P);
        assertFalse(consume(gates, access));
        assertTrue(access.reads == 0 && access.deletes == 0);
    }

    @Test
    public void everyRuntimeGateFailsClosedAndConsumesObservedMarker() {
        BsOverlayFailureTestHook.Gates[] blocked = {
                new BsOverlayFailureTestHook.Gates(true, false, true, true, true,
                        GearMonitor.GEAR_P),
                new BsOverlayFailureTestHook.Gates(true, true, false, true, true,
                        GearMonitor.GEAR_P),
                new BsOverlayFailureTestHook.Gates(true, true, true, false, true,
                        GearMonitor.GEAR_P),
                new BsOverlayFailureTestHook.Gates(true, true, true, true, false,
                        GearMonitor.GEAR_P),
                new BsOverlayFailureTestHook.Gates(true, true, true, true, true,
                        GearMonitor.GEAR_D),
                new BsOverlayFailureTestHook.Gates(true, true, true, true, true,
                        at.greenchili.surfmate.byd.BydVehicleData.UNAVAILABLE)
        };
        for (BsOverlayFailureTestHook.Gates gates : blocked) {
            FakeAccess access = new FakeAccess(validMarker());
            assertFalse(consume(gates, access));
            assertTrue(access.reads == 1 && access.deletes == 1);
        }
    }

    @Test
    public void malformedStaleFutureAndUntrustedMarkersNeverInject() {
        BsOverlayFailureTestHook.Marker[] blocked = {
                new BsOverlayFailureTestHook.Marker(true, false, false,
                        BsOverlayFailureTestHook.SHELL_UID,
                        BsOverlayFailureTestHook.PRIVATE_MODE, NOW, ""),
                new BsOverlayFailureTestHook.Marker(true, true, true,
                        BsOverlayFailureTestHook.SHELL_UID,
                        BsOverlayFailureTestHook.PRIVATE_MODE, NOW,
                        BsOverlayFailureTestHook.MARKER_CONTENT),
                new BsOverlayFailureTestHook.Marker(true, true, false, 1000,
                        BsOverlayFailureTestHook.PRIVATE_MODE, NOW,
                        BsOverlayFailureTestHook.MARKER_CONTENT),
                new BsOverlayFailureTestHook.Marker(true, true, false,
                        BsOverlayFailureTestHook.SHELL_UID, 0644, NOW,
                        BsOverlayFailureTestHook.MARKER_CONTENT),
                new BsOverlayFailureTestHook.Marker(true, true, false,
                        BsOverlayFailureTestHook.SHELL_UID,
                        BsOverlayFailureTestHook.PRIVATE_MODE,
                        NOW - BsOverlayFailureTestHook.MAX_AGE_MS - 1L,
                        BsOverlayFailureTestHook.MARKER_CONTENT),
                new BsOverlayFailureTestHook.Marker(true, true, false,
                        BsOverlayFailureTestHook.SHELL_UID,
                        BsOverlayFailureTestHook.PRIVATE_MODE, NOW + 1L,
                        BsOverlayFailureTestHook.MARKER_CONTENT),
                new BsOverlayFailureTestHook.Marker(true, true, false,
                        BsOverlayFailureTestHook.SHELL_UID,
                        BsOverlayFailureTestHook.PRIVATE_MODE, NOW, "wrong\n")
        };
        for (BsOverlayFailureTestHook.Marker marker : blocked) {
            FakeAccess access = new FakeAccess(marker);
            assertFalse(consume(validGates(), access));
            assertTrue(access.deletes == 1);
        }
    }

    @Test
    public void deleteMustSucceedBeforeInjection() {
        FakeAccess access = new FakeAccess(validMarker());
        access.deleteResult = false;
        assertFalse(consume(validGates(), access));
        assertTrue(access.deletes == 1);
    }

    @Test
    public void sourceContractsCheckedPlacementAndBuildFlag() throws Exception {
        String layer = read("app/src/main/java/at/greenchili/surfmate/surveillance/BsNativeLayer.java");
        String pipeline = read("app/src/main/java/at/greenchili/surfmate/surveillance/GpuSurveillancePipeline.java");
        String gradle = read("app/build.gradle.kts");

        assertTrue(layer.contains("boolean placeOnLayerStackChecked("));
        assertTrue(layer.contains("if (applied) {\n            layerStack = stack;\n            shown = true;"));
        assertTrue(layer.contains("private static boolean applyGeometry("));
        assertTrue(layer.contains("return false;\n        }\n    }\n\n    private static boolean applyVisibility"));

        int hook = pipeline.indexOf(".failOnceIfArmed(");
        int checked = pipeline.indexOf("placeOnLayerStackChecked(", hook);
        int fail = pipeline.indexOf("forceClose(\"bs-overlay-failed\")", checked);
        assertTrue(hook >= 0 && checked > hook && fail > checked);
        assertFalse(pipeline.substring(hook, fail).contains("layer.setGeometry("));

        assertTrue(gradle.contains("\"PHYSICAL_SAFETY_TEST_HOOKS\""));
        assertTrue(gradle.contains("project.findProperty(\"panoptesPhysicalSafetyTestHooks\")"));
        assertTrue(gradle.contains("== \"true\") \"true\" else \"false\""));
    }

    private static String read(String relative) throws Exception {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8)
                        .replace("\r\n", "\n");
            }
            current = at.greenchili.surfmate.testutil.RepoWurzel.hoch(current);
        }
        throw new IllegalStateException("missing " + relative);
    }
}
