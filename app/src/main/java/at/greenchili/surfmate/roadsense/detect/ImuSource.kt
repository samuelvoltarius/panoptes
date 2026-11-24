package at.greenchili.surfmate.roadsense.detect

import android.hardware.Sensor
import android.hardware.SensorManager
import android.util.Log

/**
 * Selects the **real** inertial sensors on the head unit, not the stub.
 *
 * Findings F-001..F-004 (see dev/roadsense/05-FINDINGS.md): the BYD head unit
 * exposes TWO physical accelerometers and TWO gyros (Bosch smi130):
 *
 *   smi130-accel        type=accelerometer(1)  ← STUB: loops 5 frozen values, Z=0
 *   smi130-accel-iner   type=accelerometer(1)  ← REAL inertial sensor
 *   smi130-gyro         type=gyroscope(4)      ← stub
 *   smi130-gyro-iner    type=gyroscope(4)      ← real
 *
 * `SensorManager.getDefaultSensor(type)` returns the STUB, so we must enumerate
 * `getSensorList(type)` and pick the "-iner" instance by name. This is exactly
 * what the DIP app does (C2704d.java:679 hunts "-iner", skips "default").
 *
 * Fusion sensors (TYPE_LINEAR_ACCELERATION, TYPE_GRAVITY, rotation vector) are
 * derived from the *default* (stub) accel → poisoned (F-004). We never use them;
 * gravity removal is done ourselves in [GravityFrame].
 *
 * Pure selection logic — no listener registration here (that's RoadSenseService's
 * job). Kept separate so it's unit-testable and so the "did we get a real sensor"
 * decision lives in one place (feeds R-EXT-6 graceful fallback).
 */
object ImuSource {

    private const val TAG = "RoadSense/ImuSource"
    private const val INERTIAL_MARKER = "-iner"
    private const val STUB_NAME = "default"

    /** Result of resolving the real IMU. [accelerometer]/[gyroscope] are null
     *  when no usable sensor exists on this trim (→ feature self-disables). */
    data class Resolved(
        val accelerometer: Sensor?,
        val gyroscope: Sensor?,
        val accelIsInertial: Boolean,
        val gyroIsInertial: Boolean,
    ) {
        /** True only when we have a real (inertial) accelerometer — the minimum
         *  bar for detection. Gyro is used for rejection but is not strictly
         *  required to detect a vertical event. */
        val usableForDetection: Boolean get() = accelerometer != null && accelIsInertial
    }

    /**
     * Resolve the best accelerometer + gyroscope for detection.
     *
     * Selection order per type:
     *   1. an instance whose name contains "-iner" (the real inertial sensor),
     *   2. else the last instance whose name is not "default" (best-effort),
     *   3. else getDefaultSensor() (last resort — may be the stub).
     */
    fun resolve(sm: SensorManager): Resolved {
        logInventory(sm)
        val accel = pick(sm, Sensor.TYPE_ACCELEROMETER)
        val gyro = pick(sm, Sensor.TYPE_GYROSCOPE)
        val resolved = Resolved(
            accelerometer = accel,
            gyroscope = gyro,
            accelIsInertial = accel.isInertial(),
            gyroIsInertial = gyro.isInertial(),
        )
        Log.i(
            TAG,
            "resolved accel=${accel?.name ?: "none"} (inertial=${resolved.accelIsInertial}) " +
                "gyro=${gyro?.name ?: "none"} (inertial=${resolved.gyroIsInertial}) " +
                "usableForDetection=${resolved.usableForDetection}"
        )
        if (accel != null && !resolved.accelIsInertial) {
            // Loud warning: we fell back to a possibly-stub accelerometer. On the
            // BYD Seal this means readings may be the 5-value canned waveform.
            Log.w(TAG, "accelerometer is NOT the -iner sensor; readings may be a stub waveform")
        }
        return resolved
    }

    // ── 0.1.43.20 (FIX_014320_IMU): zweiter IMU-Chip ───────────────────────────
    //
    // Der Surf hat zwei echte Bausteine: smi230 (-iner, Hauptsensor) und icm40607 (dumpsys sensorservice,
    // Inventur 20.11.). Der zweite dient nur der Plausibilität (zwei Chips sehen einen echten Stoß beide). Er
    // muss von einem ANDEREN Chip sein: auf dem Seal ist der gleichnamige Nicht-iner-Sensor eine Attrappe
    // (smi130-accel, eingefrorene Werte), die darf nie mitreden.

    /** Zweiter Chip: Beschleunigung und Kreisel (je null = nicht vorhanden). */
    data class Zweit(val accelerometer: Sensor?, val gyroscope: Sensor?)

    /** Chip-Kennung = Name bis zum ersten „-“ (smi230-accel-iner → smi230), klein. */
    fun chip(name: String?): String = name?.substringBefore('-')?.trim()?.lowercase() ?: ""

    /**
     * Rein: Name eines Sensors aus [namen] auf einem anderen Chip als [primaer]; „-iner“ zuerst, nie „default“
     * und nie ein Name ohne Chip-Kennung. Null = kein zweiter Chip (bisheriges Verhalten).
     */
    fun zweitName(namen: List<String>, primaer: String?): String? {
        val pc = chip(primaer)
        if (pc.isEmpty()) return null
        val kandidaten = namen.filter { n ->
            !n.equals(STUB_NAME, ignoreCase = true) && n.contains('-') && chip(n).isNotEmpty() && chip(n) != pc
        }
        return kandidaten.firstOrNull { it.contains(INERTIAL_MARKER, ignoreCase = true) } ?: kandidaten.firstOrNull()
    }

    /** 0.1.43.20: zweiten Chip zu [primaer] auflösen (Beschleunigung nach Chip des Haupt-Beschleunigungssensors). */
    fun resolveZweit(sm: SensorManager, primaer: Resolved): Zweit {
        val pa = primaer.accelerometer?.name
        val accelListe = sm.getSensorList(Sensor.TYPE_ACCELEROMETER)
        val gyroListe = sm.getSensorList(Sensor.TYPE_GYROSCOPE)
        val an = zweitName(accelListe.map { it.name }, pa)
        val gn = zweitName(gyroListe.map { it.name }, pa)
        val zweit = Zweit(
            accelerometer = accelListe.firstOrNull { it.name == an },
            gyroscope = gyroListe.firstOrNull { it.name == gn },
        )
        Log.i(TAG, "zweiter Chip: accel=${zweit.accelerometer?.name ?: "none"} gyro=${zweit.gyroscope?.name ?: "none"}")
        return zweit
    }

    /** 0.1.43.20: stammen Haupt-Beschleunigung und Haupt-Kreisel vom selben Chip (gleiche Achsen)? */
    fun gleicherChip(primaer: Resolved): Boolean {
        val a = chip(primaer.accelerometer?.name)
        return a.isNotEmpty() && a == chip(primaer.gyroscope?.name)
    }

    private fun pick(sm: SensorManager, type: Int): Sensor? {
        val list = sm.getSensorList(type)
        if (list.isEmpty()) return sm.getDefaultSensor(type)
        return list.firstOrNull { it.name.contains(INERTIAL_MARKER, ignoreCase = true) }
            ?: list.lastOrNull { !it.name.equals(STUB_NAME, ignoreCase = true) }
            ?: sm.getDefaultSensor(type)
    }

    private fun Sensor?.isInertial(): Boolean =
        this != null && name.contains(INERTIAL_MARKER, ignoreCase = true)

    /** One-time inventory log so we can audit any trim's sensor table from logcat. */
    private fun logInventory(sm: SensorManager) {
        for (s in sm.getSensorList(Sensor.TYPE_ALL)) {
            Log.d(
                TAG,
                "sensor type=${s.type} name=${s.name} vendor=${s.vendor} " +
                    "minDelay=${s.minDelay}us res=${s.resolution} maxRange=${s.maximumRange}"
            )
        }
    }
}
