package at.greenchili.surfmate.tacho.oem;

import android.os.IBinder;

import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import at.greenchili.surfmate.logging.DaemonLogger;

/** Bounded direct Binder transport for BYD AutoContainer navigation messages. */
final class OemNavigationTransport {
    private static final DaemonLogger logger = DaemonLogger.getInstance("OemNavigationTransport");
    private static final long TIMEOUT_MS = 1500L;
    private static final String[] SERVICES = {"AutoContainer", "auto_container"};
    private static final ExecutorService binder = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "OemNavigationBinder");
        t.setDaemon(true);
        return t;
    });
    private static volatile boolean poisoned;

    private OemNavigationTransport() {}

    static boolean enableNavigationMode() {
        return bounded(() -> sendInfo(5, 0, ""));
    }

    static boolean sendNavigationPayload(byte[] payload) {
        if (payload == null || payload.length == 0 || payload.length > 4096) return false;
        return bounded(() -> sendInfo2(4, payload));
    }

    private static boolean bounded(Callable<Result> op) {
        if (poisoned) return false;
        Future<Result> f;
        try {
            f = binder.submit(op);
            Result result = f.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return result == Result.ACCEPTED;
        } catch (java.util.concurrent.TimeoutException timeout) {
            // A synchronous vendor Binder may never return. Keep one isolated daemon thread
            // at most and refuse every later write until the daemon restarts.
            poisoned = true;
            logger.warn("AutoContainer navigation Binder timed out; channel disabled until restart");
            return false;
        } catch (Throwable failure) {
            logger.warn("AutoContainer navigation transport failed: " + failure.getMessage());
            return false;
        }
    }

    private static Result sendInfo(int type, int info, String text) {
        Endpoint endpoint = resolve();
        if (endpoint == null) return Result.UNAVAILABLE;
        try {
            Method method = endpoint.iface.getClass().getMethod(
                    "sendInfo", int.class, int.class, String.class);
            Object result = method.invoke(endpoint.iface, type, info, text);
            return accepted(result) ? Result.ACCEPTED : Result.REJECTED;
        } catch (Throwable ambiguous) {
            logger.warn("AutoContainer sendInfo completed ambiguously: "
                    + ambiguous.getClass().getSimpleName());
            return Result.AMBIGUOUS;
        }
    }

    private static Result sendInfo2(int type, byte[] payload) {
        Endpoint endpoint = resolve();
        if (endpoint == null) return Result.UNAVAILABLE;
        try {
            Method method = endpoint.iface.getClass().getMethod(
                    "sendInfo2", int.class, byte[].class);
            Object result = method.invoke(endpoint.iface, type, payload);
            return accepted(result) ? Result.ACCEPTED : Result.REJECTED;
        } catch (Throwable ambiguous) {
            logger.warn("AutoContainer sendInfo2 completed ambiguously: "
                    + ambiguous.getClass().getSimpleName());
            return Result.AMBIGUOUS;
        }
    }

    private static Endpoint resolve() {
        for (String service : SERVICES) {
            try {
                Class<?> sm = Class.forName("android.os.ServiceManager");
                Object raw = sm.getMethod("getService", String.class).invoke(null, service);
                if (!(raw instanceof IBinder)) continue;
                Class<?> stub = Class.forName("android.os.IAutoContainer$Stub");
                Object iface = stub.getMethod("asInterface", IBinder.class).invoke(null, raw);
                if (iface != null) return new Endpoint(iface);
            } catch (Throwable unavailable) {
                // Try the firmware alias. No command has been dispatched yet.
            }
        }
        return null;
    }

    private static boolean accepted(Object result) {
        return result == null || (result instanceof Number && ((Number) result).intValue() == 0);
    }

    private enum Result { ACCEPTED, REJECTED, AMBIGUOUS, UNAVAILABLE }
    private static final class Endpoint { final Object iface; Endpoint(Object iface) { this.iface = iface; } }
}
