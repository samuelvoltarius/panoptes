package at.greenchili.surfmate.automation.condition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Verifies that automation polling consumes no periodic work while unreferenced. */
public class ConditionalPollerTest {

    @BeforeClass
    public static void configureLogger() {
        at.greenchili.surfmate.logging.DaemonLogger.Config config =
                new at.greenchili.surfmate.logging.DaemonLogger.Config();
        config.enableConsoleLog = false;
        config.enableFileLog = false;
        config.enableStdoutLog = true;
        at.greenchili.surfmate.logging.DaemonLogger.configure(config);
    }

    @Test
    public void taskExistsOnlyWhileReferenced() throws Exception {
        AtomicBoolean referenced = new AtomicBoolean(false);
        AtomicInteger runs = new AtomicInteger();
        ConditionalPoller poller = new ConditionalPoller(
                "lifecycle test", 20L, referenced::get, runs::incrementAndGet);

        poller.refresh();
        assertFalse(poller.isScheduledForTest());
        Thread.sleep(60L);
        assertEquals(0, runs.get());

        referenced.set(true);
        poller.refresh();
        assertTrue(poller.isScheduledForTest());
        awaitAtLeastOneRun(runs);

        referenced.set(false);
        poller.refresh();
        assertFalse(poller.isScheduledForTest());
        // Let a tick already in flight finish, then verify no periodic work remains.
        Thread.sleep(40L);
        int stableRuns = runs.get();
        Thread.sleep(80L);
        assertEquals(stableRuns, runs.get());
    }

    @Test
    public void repeatedRefreshKeepsTheExistingScheduledTask() {
        AtomicBoolean referenced = new AtomicBoolean(true);
        ConditionalPoller poller = new ConditionalPoller(
                "refresh idempotency test", 20L, referenced::get, () -> {});

        poller.refresh();
        long scheduledGeneration = poller.generationForTest();
        assertTrue(poller.isScheduledForTest());

        poller.refresh();
        poller.refresh();

        assertTrue(poller.isScheduledForTest());
        assertEquals(scheduledGeneration, poller.generationForTest());

        referenced.set(false);
        poller.refresh();
        assertFalse(poller.isScheduledForTest());
    }

    // ---- Upstream OverDrive #733 (MIT, Copyright (c) Yash Srivastava and OverDrive contributors)

    @Test
    public void blockedSignalDoesNotStarveAnotherSignal() throws Exception {
        AtomicBoolean referenced = new AtomicBoolean(true);
        CountDownLatch blockedStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocked = new CountDownLatch(1);
        AtomicInteger independentRuns = new AtomicInteger();
        ConditionalPoller blocked = new ConditionalPoller(
                "blocked isolation test", 20L, referenced::get, () -> {
                    blockedStarted.countDown();
                    try {
                        releaseBlocked.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }, false);
        ConditionalPoller independent = new ConditionalPoller(
                "independent isolation test", 20L, referenced::get,
                independentRuns::incrementAndGet, false);
        try {
            blocked.refresh();
            independent.refresh();
            assertTrue(blockedStarted.await(1, TimeUnit.SECONDS));
            long deadline = System.currentTimeMillis() + 1000L;
            while (independentRuns.get() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10L);
            }
            assertTrue("independent signal was starved by blocked signal",
                    independentRuns.get() > 0);
        } finally {
            releaseBlocked.countDown();
            referenced.set(false);
            blocked.refresh();
            independent.refresh();
        }
    }

    @Test
    public void failedFirstSampleDoesNotConsumeSilentBaseline() throws Exception {
        AtomicBoolean referenced = new AtomicBoolean(true);
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger silentRuns = new AtomicInteger();
        ConditionalPoller poller = new ConditionalPoller(
                "baseline retry test", 20L, referenced::get, () -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw new IllegalStateException("service not ready");
                    }
                    if (at.greenchili.surfmate.automation.Automations.isSilentSeedActive()) {
                        silentRuns.incrementAndGet();
                    }
                }, false);
        try {
            poller.refresh();
            long deadline = System.currentTimeMillis() + 1000L;
            while (attempts.get() < 4 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10L);
            }
            assertEquals(1, silentRuns.get());
        } finally {
            referenced.set(false);
            poller.refresh();
        }
    }

    @Test
    public void oneSignalNeverRunsOverlappingReads() throws Exception {
        AtomicBoolean referenced = new AtomicBoolean(true);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        ConditionalPoller poller = new ConditionalPoller(
                "overlap isolation test", 10L, referenced::get, () -> {
                    int current = active.incrementAndGet();
                    maximumActive.accumulateAndGet(current, Math::max);
                    try {
                        Thread.sleep(45L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        active.decrementAndGet();
                    }
                }, false);
        try {
            poller.refresh();
            Thread.sleep(180L);
            assertEquals(1, maximumActive.get());
        } finally {
            referenced.set(false);
            poller.refresh();
        }
    }

    @Test
    public void healthFailureAndRecoveryAreRecordedOnce() throws Exception {
        AtomicBoolean referenced = new AtomicBoolean(true);
        AtomicInteger attempts = new AtomicInteger();
        String name = "health test " + System.nanoTime();
        ConditionalPoller poller = new ConditionalPoller(
                name, 10L, referenced::get, () -> {
                    int n = attempts.incrementAndGet();
                    if (n >= 2 && n <= 4) throw new IllegalStateException("geheim");
                }, false);
        try {
            poller.refresh();
            long deadline = System.currentTimeMillis() + 2000L;
            while (attempts.get() < 7 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10L);
            }
        } finally {
            referenced.set(false);
            poller.refresh();
        }
        org.json.JSONArray h = at.greenchili.surfmate.automation.Automations.recentExecutions(100);
        int failed = 0, recovered = 0;
        for (int i = 0; i < h.length(); i++) {
            org.json.JSONObject e = h.getJSONObject(i);
            if (!("signal:" + name).equals(e.optString("automationId"))) continue;
            assertEquals("poller", e.getString("stage"));
            assertFalse(e.toString().contains("geheim"));
            if ("failed".equals(e.getString("status"))) failed++;
            if ("recovered".equals(e.getString("status"))) recovered++;
        }
        assertEquals(1, failed);
        assertEquals(1, recovered);
    }

    private static void awaitAtLeastOneRun(AtomicInteger runs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 1000L;
        while (runs.get() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        assertTrue("referenced poller did not run", runs.get() > 0);
    }
}
