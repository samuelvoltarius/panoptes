package at.greenchili.surfmate.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 0.1.2-Regression (21.10.2026, 0/3 Daemons): reproduces the dadb 1.2.8 waiter hang with the
 * REAL dadb.MessageQueue and proves StreamWaiters frees the stuck thread.
 * (Java on purpose: the class is Kotlin-internal, Java can subclass it.)
 */
public class DadbWaiterHangTest {

    private static final Object CLOSED = new Object();

    /** Fake transport: readMessage() blocks until a message or a "socket close". */
    private static final class FakeQueue extends dadb.MessageQueue<int[]> {
        final LinkedBlockingQueue<Object> wire = new LinkedBlockingQueue<>();
        @Override protected int[] readMessage() {
            try {
                Object m = wire.take();
                if (m == CLOSED) throw new RuntimeException(new IOException("Socket closed"));
                return (int[]) m;
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        @Override protected int getLocalId(int[] m) { return m[0]; }
        @Override protected int getCommand(int[] m) { return m[1]; }
        @Override protected boolean isCloseCommand(int[] m) { return false; }
    }

    private static Thread taker(FakeQueue q, int id, StreamWaiters w, CountDownLatch done) {
        Thread t = new Thread(() -> {
            if (w != null) w.enter(Thread.currentThread());
            try {
                q.take(id, 1);
            } catch (Throwable ignored) {
            } finally {
                if (w != null) w.exit(Thread.currentThread());
                done.countDown();
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void waitParked(Thread... ts) throws InterruptedException {
        long until = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < until) {
            boolean all = true;
            for (Thread t : ts) {
                Thread.State s = t.getState();
                if (s != Thread.State.WAITING && s != Thread.State.TIMED_WAITING) all = false;
            }
            if (all) return;
            Thread.sleep(10);
        }
    }

    @Test
    public void readerFailureLeavesOtherWaitersParkedForever_dadbBug() throws Exception {
        FakeQueue q = new FakeQueue();
        q.startListening(1);
        q.startListening(2);
        CountDownLatch done = new CountDownLatch(2);
        Thread a = taker(q, 1, null, done);
        Thread b = taker(q, 2, null, done);
        waitParked(a, b);            // one reads (wire.take), one awaits the condition
        q.wire.put(CLOSED);          // the socket dies under the reader
        assertFalse("dadb wakes nobody: one thread must still be stuck", done.await(1, TimeUnit.SECONDS));
        assertEquals(1, done.getCount());
        a.interrupt();
        b.interrupt();
    }

    @Test
    public void closeAndWakeFreesTheStuckWaiter() throws Exception {
        FakeQueue q = new FakeQueue();
        q.startListening(1);
        q.startListening(2);
        StreamWaiters w = new StreamWaiters();
        CountDownLatch done = new CountDownLatch(2);
        Thread a = taker(q, 1, w, done);
        Thread b = taker(q, 2, w, done);
        waitParked(a, b);
        q.wire.put(CLOSED);
        Thread.sleep(200);
        w.closeAndWake();            // what invalidateConnection / the watchdog now do
        assertTrue("both commands must return", done.await(2, TimeUnit.SECONDS));
        assertTrue(w.isClosed());
        assertEquals(0, w.count());
    }

    @Test
    public void exitClearsOurInterruptSoTheExecutorThreadStaysUsable() {
        StreamWaiters w = new StreamWaiters();
        w.enter(Thread.currentThread());
        w.closeAndWake();
        Thread.currentThread().interrupt();   // the wake landing on this thread
        w.exit(Thread.currentThread());
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test
    public void executorWiresTheFix() throws Exception {
        String src = new String(Files.readAllBytes(Paths.get(
                "src/main/java/at/greenchili/surfmate/launcher/AdbShellExecutor.kt")), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        assertTrue(src.contains("conn.waiters.enter()"));
        assertTrue(src.contains("conn.waiters.exit()"));
        assertTrue("invalidation and watchdog must wake waiters", src.contains("closeAndWake(conn)"));
        assertTrue("caller interrupt must not invalidate the shared connection",
                src.contains("isCallerInterrupt(e)"));
        int s = src.indexOf("fun shutdown()");
        String shutdown = src.substring(s, src.indexOf("private fun getOrCreateAdbKeyPair", s));
        assertFalse("shutdown must not interrupt in-flight commands", shutdown.contains("executor.shutdownNow()"));
    }
}
