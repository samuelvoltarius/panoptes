package at.greenchili.surfmate.updater;

/**
 * Hotfix 0.1.15.1, Entprellung des Starts nach einem Update: Nach einem In-App-Update starten zwei Wege die App mit
 * {@code post_update} – der BootReceiver ({@code MY_PACKAGE_REPLACED}) und {@code update-on-car.sh}
 * ({@code am start … post_update}). Beide landen im selben, frisch gestarteten App-Prozess. Ohne Entprellung würde
 * der zweite Aufruf {@link UpdateLifecycle#hardResetDaemons} erneut ausführen und die gerade gestarteten Daemons
 * wieder beenden (Doppelstart).
 *
 * <p>Regel: je Prozess höchstens ein Post-Update-Neustart der Daemons in {@link #FENSTER_MS}. Ein neues Update
 * ersetzt das APK und beendet den Prozess, die Sperre gilt also nie über ein Update hinaus.
 */
public final class NachUpdateStart {

    public static final long FENSTER_MS = 5 * 60_000L;

    private static long beansprucht = -1;

    private NachUpdateStart() {}

    /** true = dieser Aufruf darf den Post-Update-Neustart ausführen; false = lief in diesem Prozess gerade schon. */
    public static synchronized boolean beanspruchen(long jetztMs) {
        if (beansprucht >= 0 && jetztMs - beansprucht < FENSTER_MS) return false;
        beansprucht = jetztMs;
        return true;
    }

    /** Anspruch zurückgeben, wenn der Neustart doch nicht lief (z. B. anderer Start schon unterwegs). */
    public static synchronized void freigeben() {
        beansprucht = -1;
    }
}
