package at.greenchili.surfmate.monitor;

import at.greenchili.surfmate.daemon.CameraDaemon;

import org.json.JSONObject;

/**
 * Battery Monitor - reads battery info from VehicleDataMonitor.
 *
 * This class provides battery data to the HTTP status API for HTML UI display.
 *
 * <p>It runs INSIDE the CameraDaemon process (HttpServer.sendStatus calls
 * {@link #getBatteryInfo()} on a daemon HTTP worker thread). VehicleDataMonitor —
 * the source of truth — is a singleton in that SAME process. Previously this
 * opened a TCP socket to 127.0.0.1:19977 (SurveillanceIpcServer, also in this
 * process) and sent GET_VEHICLE_DATA, i.e. a full TCP connect+handshake+teardown
 * just to call a same-process singleton whose handler is literally
 * {@code VehicleDataMonitor.getInstance().getAllData()}. We now call that
 * directly — no socket, no loopback stack, no IPC server thread.
 */
public class BatteryMonitor {

    private static final long BATTERY_FETCH_INTERVAL_MS = 30_000L;
    /**
     * 0.1.43.21: 150 s statt 60 s. Im Stand fragt Panoptes die Fahrzeugdaten nur etwa alle 90 s ab
     * ({@code VehicleDataMonitor}, „90-second parked poll“); mit 60 s galt ein ganz normaler Wert oft schon als
     * veraltet und die Anzeige zeigte „kein Wert“ (PRUEF_ADB_12V_20261124, v12AlterMs 76175).
     */
    static final long BATTERY_VOLTAGE_STALE_MS = 150_000L;

    /** Statuswerte im Feld {@code wert}: noch nie ein Messwert seit dem Kern-Start, frisch oder veraltet. */
    static final String WERT_NOCH_KEINER = "noch_kein_wert";
    static final String WERT_FRISCH = "frisch";
    static final String WERT_VERALTET = "veraltet";

    private static volatile double lastBatteryVoltage = 0.0;
    private static volatile String lastBatteryLevel = "UNKNOWN";
    private static volatile double lastBatterySoc = 0.0;
    /** Wall-clock time of the last valid voltage sample. */
    private static volatile long lastBatteryUpdate = 0;
    /** Wall-clock time of the last attempted vehicle-data poll. */
    private static volatile long lastBatteryPoll = 0;

    /**
     * Derive battery level from actual voltage when BYD API returns INVALID.
     * 12V automotive battery voltage ranges:
     * - < 11.8V: LOW (battery needs charging)
     * - 11.8V - 14.8V: NORMAL (healthy range, 14.4V+ when charging)
     * - > 14.8V: Overcharging warning
     */
    private static String deriveLevelFromVoltage(double voltage) {
        if (voltage <= 0) return "UNKNOWN";
        if (voltage < 11.8) return "LOW";
        if (voltage >= 11.8 && voltage <= 14.8) return "NORMAL";
        return "HIGH";  // Overcharging
    }

    /**
     * Fetch battery info from SurveillanceIpcServer.
     */
    public static void fetchBatteryInfo() {
        long now = System.currentTimeMillis();
        lastBatteryPoll = now;
        try {
            // Direct same-process read — VehicleDataMonitor is the singleton the
            // SurveillanceIpcServer's GET_VEHICLE_DATA handler delegates to, and
            // we run in the same process, so skip the loopback socket entirely.
            JSONObject data =
                at.greenchili.surfmate.monitor.VehicleDataMonitor.getInstance().getAllData();

            if (data != null) {
                // Get battery power voltage (actual volts)
                JSONObject batteryPower = data.optJSONObject("batteryPower");
                if (batteryPower != null) {
                    double voltage = batteryPower.optDouble("voltageVolts", Double.NaN);
                    long observedAtMs = batteryPower.optLong("observedAtMs", 0L);
                    if (Double.isFinite(voltage) && voltage > 0.0
                            && observedAtMs > 0L) {
                        lastBatteryVoltage = voltage;
                        lastBatteryUpdate = observedAtMs;
                    }
                }

                // Get battery voltage level (LOW/NORMAL/INVALID)
                JSONObject batteryVoltage = data.optJSONObject("batteryVoltage");
                if (batteryVoltage != null) {
                    String apiLevel = batteryVoltage.optString("levelName", "UNKNOWN");
                    // If BYD API returns INVALID, derive level from actual voltage
                    if ("INVALID".equals(apiLevel) || "UNKNOWN".equals(apiLevel)) {
                        lastBatteryLevel = deriveLevelFromVoltage(lastBatteryVoltage);
                    } else {
                        lastBatteryLevel = apiLevel;
                    }
                } else {
                    // No API data, derive from voltage
                    lastBatteryLevel = deriveLevelFromVoltage(lastBatteryVoltage);
                }

                // Get battery SOC percentage
                JSONObject batterySoc = data.optJSONObject("batterySoc");
                if (batterySoc != null) {
                    lastBatterySoc = batterySoc.optDouble("socPercent", 0.0);
                }

                // 0.1.43.21: nach dem Kern-Start nicht „0.0V“ melden, sondern ehrlich „noch kein Wert“
                CameraDaemon.log(logZeile(lastBatteryVoltage, lastBatteryUpdate, lastBatteryLevel, lastBatterySoc));
            }
        } catch (Exception e) {
            CameraDaemon.log("Battery info fetch: " + e.getMessage());
        }
    }

    /**
     * Get battery info as JSON object.
     * Fetches fresh data if stale (> 30 seconds).
     */
    public static JSONObject getBatteryInfo() {
        long now = System.currentTimeMillis();
        if (now - lastBatteryPoll > BATTERY_FETCH_INTERVAL_MS) {
            fetchBatteryInfo();
            now = System.currentTimeMillis();
        }

        boolean voltageFresh = isVoltageFresh(
                lastBatteryVoltage, lastBatteryUpdate, now);
        long voltageAgeMs = lastBatteryUpdate > 0
                ? Math.max(0L, now - lastBatteryUpdate) : -1L;

        JSONObject battery = new JSONObject();
        try {
            battery.put("voltage",
                    voltageFresh ? lastBatteryVoltage : JSONObject.NULL);
            battery.put("available", voltageFresh);
            battery.put("isStale", !voltageFresh);
            battery.put("ageMs",
                    voltageAgeMs >= 0 ? voltageAgeMs : JSONObject.NULL);
            battery.put("level",
                    voltageFresh ? lastBatteryLevel : "UNKNOWN");
            battery.put("soc", lastBatterySoc);
            battery.put("lastUpdate", lastBatteryUpdate);
            battery.put("wert", wertStatus(voltageFresh, lastBatteryUpdate));
        } catch (Exception e) {
            // Ignore
        }
        return battery;
    }

    /** Feld {@code wert}: noch kein Wert (seit Kern-Start nie gemessen), frisch oder veraltet. */
    static String wertStatus(boolean frisch, long observedAtMs) {
        if (observedAtMs <= 0L) return WERT_NOCH_KEINER;
        return frisch ? WERT_FRISCH : WERT_VERALTET;
    }

    /** Protokollzeile nach einer Abfrage; ohne je einen Messwert „noch kein Wert“ statt 0.0 V. */
    static String logZeile(double volt, long observedAtMs, String level, double soc) {
        if (observedAtMs <= 0L || !(volt > 0.0)) {
            return "Battery: noch kein Wert" + (soc > 0.0 ? " (SOC: " + soc + "%)" : "");
        }
        return "Battery updated: " + volt + "V (" + level + "), SOC: " + soc + "%";
    }

    static boolean isVoltageFresh(
            double voltage, long observedAtMs, long nowMs) {
        return Double.isFinite(voltage)
                && voltage > 0.0
                && observedAtMs > 0L
                && nowMs >= observedAtMs
                && nowMs - observedAtMs <= BATTERY_VOLTAGE_STALE_MS;
    }
}
