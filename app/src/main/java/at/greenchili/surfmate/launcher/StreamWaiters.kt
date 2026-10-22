package at.greenchili.surfmate.launcher

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Threads currently inside a command on ONE dadb connection, so closing that connection can
 * wake them (0.1.2 regression, 21.10.2026).
 *
 * dadb 1.2.8 `MessageQueue.take()`: one thread reads the socket under `readLock`, the others
 * park in `queueCond.await()` without a timeout. The reader calls `signalAll()` only after a
 * SUCCESSFUL read; when `read()` throws (socket closed, timeout, adbd hiccup) it just drops
 * `readLock`. Every other waiter then sleeps forever — closing the connection does not wake
 * them, and our deadline watchdog only closes. Each such waiter is a single-thread executor's
 * only thread, so every later command (daemon launches) queues behind it silently. That is
 * what left 0.1.2 with 0/3 daemons on 21.10. 01:53 (reader failure on gen=2 while the boot →
 * activity handoff was in flight).
 *
 * Fix: [closeAndWake] marks the connection closed and interrupts every registered waiter;
 * `await()` is interruptible, so they fail fast and free their executor.
 */
class StreamWaiters {
    private val threads: MutableSet<Thread> = ConcurrentHashMap.newKeySet()
    private val closed = AtomicBoolean(false)

    val isClosed: Boolean get() = closed.get()

    fun enter(t: Thread = Thread.currentThread()) { threads.add(t) }

    /**
     * Leave the command. If the connection was closed while we waited, the interrupt we may
     * have received was ours: clear it so the executor thread's NEXT task does not fail with a
     * stale "interrupted".
     */
    fun exit(t: Thread = Thread.currentThread()) {
        threads.remove(t)
        if (closed.get() && t === Thread.currentThread()) Thread.interrupted()
    }

    /** Mark closed and interrupt every waiter except the caller. Returns how many were woken. */
    fun closeAndWake(): Int {
        closed.set(true)
        var n = 0
        val self = Thread.currentThread()
        for (t in threads) {
            if (t !== self) {
                t.interrupt()
                n++
            }
        }
        return n
    }

    fun count(): Int = threads.size
}
