package at.greenchili.surfmate.byd.routing;

import at.greenchili.surfmate.byd.BydDataCollector;
import at.greenchili.surfmate.byd.BydVehicleData;

/**
 * Fail-closed gate for EVERY BYD cloud command the app sends (21.10.2026).
 *
 * <p>Incident 13.10.2026: a cloud "climate off" powered down the RUNNING car. So a command may
 * only take the cloud leg with fresh proof that the car is off — the same rule as the server-side
 * bridge gate {@code deploy/server/bridge/befehl_gate.py}:
 * <ul>
 *   <li>{@code powerLevel == 0}, read LIVE from the head unit in this process
 *       ({@link BydVehicleData#powerLevelAtMs} = time of the live head-unit reading; it keeps
 *       its age across the DiLink 5 IPC bridge and no snapshot is restored from disk),</li>
 *   <li>at most {@link #MAX_AGE_MS} old,</li>
 *   <li>and the daemon's authoritative ACC rail must not report "on".</li>
 * </ul>
 * Missing, stale or contradictory state blocks the cloud leg; the command then runs locally (if it
 * has a local leg) or not at all, with a message. Remote unlock is never allowed.
 */
public final class CloudPowerGate {

    public static final long MAX_AGE_MS = 180_000L;
    /** Tolerated clock skew for a reading stamped slightly in the future. */
    static final long FUTURE_SKEW_MS = 5_000L;

    public static final String REASON_UNLOCK_NEVER = "cloud_gate_unlock_never";
    public static final String REASON_CAR_ON = "cloud_gate_car_on";
    public static final String REASON_STATE_UNKNOWN = "cloud_gate_state_unknown";
    public static final String REASON_STATE_STALE = "cloud_gate_state_stale";

    private CloudPowerGate() {}

    public static final class Decision {
        public final boolean allowed;
        /** i18n key under {@code vehicle_control.*}; null when allowed. */
        public final String reasonKey;
        /** Age of the power reading in ms, or -1 when there was none. */
        public final long ageMs;

        private Decision(boolean allowed, String reasonKey, long ageMs) {
            this.allowed = allowed;
            this.reasonKey = reasonKey;
            this.ageMs = ageMs;
        }

        @Override
        public String toString() {
            return allowed ? "allowed(age=" + ageMs + "ms)" : "blocked(" + reasonKey + ", age=" + ageMs + "ms)";
        }
    }

    /** Pure decision, used by the router and by tests. */
    public static Decision evaluate(String commandName, int powerLevel, long powerLevelAtMs,
                                    boolean accAuthoritative, boolean accOn, long nowMs) {
        if (isUnlock(commandName)) return new Decision(false, REASON_UNLOCK_NEVER, -1L);
        if (accAuthoritative && accOn) return new Decision(false, REASON_CAR_ON, -1L);
        if (powerLevel == BydVehicleData.UNAVAILABLE || powerLevelAtMs <= 0L) {
            return new Decision(false, REASON_STATE_UNKNOWN, -1L);
        }
        long age = nowMs - powerLevelAtMs;
        if (age > MAX_AGE_MS || age < -FUTURE_SKEW_MS) {
            return new Decision(false, REASON_STATE_STALE, age);
        }
        if (powerLevel != 0) return new Decision(false, REASON_CAR_ON, age);
        return new Decision(true, null, age);
    }

    /** Decision against the live collector snapshot and ACC monitor of this process. */
    public static Decision evaluateLive(String commandName) {
        int level = BydVehicleData.UNAVAILABLE;
        long readAt = 0L;
        try {
            BydVehicleData d = BydDataCollector.getInstance().getData();
            if (d != null) {
                level = d.powerLevel;
                readAt = d.powerLevelAtMs;
            }
        } catch (Throwable ignored) {
            // unknown -> blocked below
        }
        boolean authoritative = false;
        boolean accOn = false;
        try {
            authoritative = at.greenchili.surfmate.monitor.AccMonitor.isAccStateAuthoritative();
            accOn = at.greenchili.surfmate.monitor.AccMonitor.isAccOn();
        } catch (Throwable ignored) {
            // the power reading alone still has to prove "off"
        }
        return evaluate(commandName, level, readAt, authoritative, accOn, System.currentTimeMillis());
    }

    static boolean isUnlock(String commandName) {
        return commandName != null && commandName.trim().equalsIgnoreCase("unlock");
    }
}
