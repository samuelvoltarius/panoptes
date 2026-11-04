package at.greenchili.surfmate.surveillance;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public class SurveillanceAiTriggerPolicyContractTest {

    @Test
    public void aiRequiresCurrentNonBaselineEvidenceAndProximityUsesIt()
            throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/at/greenchili/surfmate/surveillance/SurveillanceEngineGpu.java");

        int salienceOverride = source.indexOf("Motion-salience override:");
        int finalGate = source.indexOf("if (aiAvailable && !aiRecentlyConfirmed)");
        int triggerDecision = source.indexOf("if (shouldSuppress)", finalGate);
        assertTrue(salienceOverride >= 0);
        assertTrue(finalGate > salienceOverride);
        assertTrue(finalGate >= 0);
        assertTrue(triggerDecision > finalGate);
        assertTrue(source.contains("triggerSpaceDets.add(baselineSpaceDets != null"));
        // 0.1.38.8 (Wächter-KI B1): the stamp is gated by trigger evidence AND the
        // multi-batch verdict (fail-open on a counting error).
        int stampGate = source.indexOf(
                "if (triggerEvidenceFound && (mehrbildBestaetigt || mehrbildFehler))");
        int stamp = source.indexOf(
                "lastAiConfirmationElapsedMs = detectionObservationElapsedMs", stampGate);
        assertTrue(stampGate >= 0);
        assertTrue(stamp > stampGate && stamp - stampGate < 200);
        assertTrue(source.indexOf("lastAiConfirmationElapsedMs = detectionObservationElapsedMs")
                == stamp);
        assertTrue(source.contains(
                "lastAiConfirmationElapsedMs >= firstMotionElapsedMs"));
        assertTrue(source.contains("if (baselineSpaceDets != null) {\n"
                + "                                    triggerSpaceDets.add"));
        assertTrue(source.contains(
                "DistanceEstimator.fromYoloDetections(\n"
                + "                                    pub.triggerDetections"));
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return at.greenchili.surfmate.testutil.Quelltext.lies(candidate);
            }
            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return at.greenchili.surfmate.testutil.Quelltext.lies(fromModule);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate " + relativePath);
    }
}
