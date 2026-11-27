package at.greenchili.surfmate.surveillance;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Paths;

import org.junit.Test;

/** Guards against a frame-by-frame queue reset starving secondary cameras. */
public class SurveillanceAiQueueFairnessContractTest {

    @Test
    public void staggeredMotionSchedulingRetainsPendingQuadrants() throws IOException {
        String source = readSource();
        int schedulingStart = source.indexOf(
                "// Staggered YOLO: queue active quadrants for AI detection.");
        int schedulingEnd = source.indexOf(
                "// No motion detected on this frame", schedulingStart);
        assertTrue("Missing staggered AI scheduler", schedulingStart >= 0);
        assertTrue("Missing end of motion branch", schedulingEnd > schedulingStart);

        String scheduler = source.substring(schedulingStart, schedulingEnd);
        assertFalse("Frame-by-frame reset would starve non-primary cameras",
                scheduler.contains("aiQuadrantQueueClear()"));
        assertTrue(scheduler.contains("aiQuadrantQueueAdd(bestQ)"));
        assertTrue(scheduler.contains("aiQuadrantQueueAdd(q)"));
        assertTrue(scheduler.contains("runAiOnQuadrant(smallRgbFrame, aiQuadrantQueuePoll())"));
    }

    @Test
    public void queueRemainsBoundedDeduplicatedAndClearedAtSessionReset()
            throws IOException {
        String source = readSource();
        assertTrue(source.contains("if ((aiQuadrantQueueMask & bit) != 0) return;"));
        assertTrue(source.contains("aiQuadrantQueue.addLast(q);"));
        assertTrue(source.contains("aiQuadrantQueueMask &= ~(1 << q)"));
        assertTrue(source.contains("private void aiQuadrantQueueClear()"));
        assertTrue(source.contains("aiQuadrantQueueClear();"));
    }

    // Panoptes: Pfad über die Modul-Wurzel (RepoWurzelTest verbietet eigene Suche nach oben), CRLF normalisiert.
    private static String readSource() throws IOException {
        return at.greenchili.surfmate.testutil.Quelltext.lies(Paths.get(
                "src/main/java/at/greenchili/surfmate/surveillance/SurveillanceEngineGpu.java"));
    }
}
