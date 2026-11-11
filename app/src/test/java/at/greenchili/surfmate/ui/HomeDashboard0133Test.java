package at.greenchili.surfmate.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.testutil.Quelltext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Regression guards for the 0.1.33 web-start-page closure work. */
public class HomeDashboard0133Test {

    @Test
    public void failedVehicleReadsStopPresentingOldState() throws Exception {
        String page = read("app/src/main/assets/web/local/index.html");
        assertTrue(page.contains("self.vehicleStateFailureCount++"));
        assertTrue(page.contains("if (self.vehicleStateFailureCount >= 2)"));
        assertTrue(page.contains("self.markVehicleStateUnavailable()"));
        assertTrue(page.contains("this.vstate = null"));
        assertTrue(page.contains("['sDoors', 'sWin', 'sClim', 'sTyre']"));
    }

    @Test
    public void generatedInsightIsShownOnlyThroughOptInEndpoint() throws Exception {
        String page = read("app/src/main/assets/web/local/index.html");
        assertTrue(page.contains("id=\"dashAiInsight\""));
        assertTrue(page.contains("status.genAiDashboardEnabled === true"));
        assertTrue(page.contains("/api/genai/insights/dashboard?lang="));
        assertTrue(page.contains("if (document.hidden || !this.aiInsightEnabled) return"));
        assertFalse(page.contains("/api/genai/insights/generate"));
    }

    @Test
    public void estimatedSessionEnergyHasVisibleApproximationMarker() throws Exception {
        String page = read("app/src/main/assets/web/local/index.html");
        assertTrue(page.contains("(c.sessionEnergyEstimated ? '~' : '')"));
    }

    @Test
    public void liveVehicleControlLabelsAreNotHydratedOver() throws Exception {
        String page = read("app/src/main/assets/web/local/vehicle-control.html");
        assertTrue(page.contains("id=\"lockStatus\""));
        assertTrue(page.contains("id=\"cloudStatusText\""));
        assertFalse(page.matches(
                "(?s).*id=\"(?:lockStatus|cloudStatusText)\"[^>]*data-i18n=.*"));
    }

    private static String read(String relative) throws Exception {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relative);
            if (Files.isRegularFile(candidate)) return Quelltext.lies(candidate);
            Path fromModule = current.resolve(relative.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) return Quelltext.lies(fromModule);
            current = at.greenchili.surfmate.testutil.RepoWurzel.hoch(current);
        }
        throw new AssertionError("Could not locate " + relative);
    }
}
