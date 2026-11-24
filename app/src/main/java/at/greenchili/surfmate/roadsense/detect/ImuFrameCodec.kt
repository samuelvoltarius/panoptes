package at.greenchili.surfmate.roadsense.detect

import org.json.JSONArray
import org.json.JSONObject

/**
 * Encodes/decodes batched IMU frames sent app-sidecar → daemon (D-023).
 *
 * ## Why batching is mandatory
 * The IMU runs at ~100 Hz on TWO sensors (accel + gyro) = ~200 samples/s. The
 * existing GPS sidecar writes one framed JSON line per fix at ~2 Hz; doing that
 * per IMU sample would be ~100× the syscalls and would dominate CPU on the shared
 * Adreno/CPU budget (must not regress camera frames — gate G-5). So the sidecar
 * accumulates ~[TARGET_BATCH_MS] of samples and emits ONE frame.
 *
 * ## Wire format (mirrors the project's newline-delimited JSON IPC to port 19977)
 * One JSON object per line, `command = "IMU_BATCH"`. Samples are packed as flat
 * arrays (not array-of-objects) to keep the payload compact: a 100 ms batch is
 * ~10 accel + ~10 gyro samples.
 *
 * ```
 * {"command":"IMU_BATCH",
 *  "a":[[tMs,ax,ay,az], ...],   // accel samples, m/s²
 *  "g":[[tMs,gx,gy,gz], ...]}   // gyro samples, rad/s
 * ```
 *
 * 0.1.43.20 (FIX_014320_IMU): optional `"a2"`/`"g2"` = Beschleunigung/Kreisel des ZWEITEN IMU-Chips (Surf:
 * icm40607 neben smi230), gleiche Form; `"gc":1` = `a` und `g` stammen vom selben Chip (gleiche Achsen). Ältere
 * Empfänger ignorieren die Felder, RoadSense liest weiter nur `a`/`g`.
 *
 * Each sample carries its own `tMs`, so batching adds at most ~[TARGET_BATCH_MS]
 * of delivery latency but ZERO timing error — downstream timing (GravityFrame,
 * EventDetector, GPS back-projection) all key off the per-sample tMs, not arrival
 * time. ~100 ms latency ≈ 1.7 m at 60 km/h, negligible vs the ≥30 m warning lead.
 *
 * Pure + Android-free (only org.json, which is in the platform) → unit-testable
 * on both sides with no sockets.
 */
object ImuFrameCodec {

    const val COMMAND = "IMU_BATCH"

    /** Target wall-clock span per batch. ~100 ms ⇒ ~10 samples/sensor at 100 Hz. */
    const val TARGET_BATCH_MS = 100L

    /** Hard cap on samples per sensor per frame (defensive against a stall-then-flush
     *  dumping a huge batch). At 100 Hz, 64 ≈ 640 ms — far above TARGET_BATCH_MS. */
    const val MAX_SAMPLES_PER_FRAME = 64

    private const val KEY_COMMAND = "command"
    private const val KEY_ACCEL = "a"
    private const val KEY_GYRO = "g"
    private const val KEY_ACCEL2 = "a2"
    private const val KEY_GYRO2 = "g2"
    private const val KEY_GLEICHER_CHIP = "gc"
    private const val KEY_BEREICH1 = "r1"

    /**
     * Encode a batch into a single newline-terminated wire line (the daemon's
     * IPC reader is line-oriented, like the GPS sidecar). Either list may be
     * empty but not both (caller shouldn't send empty frames).
     */
    fun encode(accel: List<ImuAccelSample>, gyro: List<ImuGyroSample>): String =
        encode(accel, gyro, emptyList(), emptyList(), false)

    /** 0.1.43.20: mit zweitem Chip ([accel2]/[gyro2], leer = keiner), Kennzeichen [gleicherChip], Messbereich [bereich1]. */
    @JvmOverloads
    fun encode(
        accel: List<ImuAccelSample>,
        gyro: List<ImuGyroSample>,
        accel2: List<ImuAccelSample>,
        gyro2: List<ImuGyroSample>,
        gleicherChip: Boolean,
        bereich1: Float = Float.NaN,
    ): String {
        val obj = JSONObject()
        obj.put(KEY_COMMAND, COMMAND)
        obj.put(KEY_ACCEL, accelArray(accel))
        obj.put(KEY_GYRO, gyroArray(gyro))
        if (accel2.isNotEmpty()) obj.put(KEY_ACCEL2, accelArray(accel2))
        if (gyro2.isNotEmpty()) obj.put(KEY_GYRO2, gyroArray(gyro2))
        if (gleicherChip) obj.put(KEY_GLEICHER_CHIP, 1)
        if (!bereich1.isNaN() && bereich1 > 0f) obj.put(KEY_BEREICH1, bereich1.toDouble())
        return obj.toString() + "\n"
    }

    private fun accelArray(accel: List<ImuAccelSample>): JSONArray {
        val a = JSONArray()
        for (s in accel) {
            a.put(JSONArray().put(s.tMs).put(s.ax.toDouble()).put(s.ay.toDouble()).put(s.az.toDouble()))
        }
        return a
    }

    private fun gyroArray(gyro: List<ImuGyroSample>): JSONArray {
        val g = JSONArray()
        for (s in gyro) {
            g.put(JSONArray().put(s.tMs).put(s.gx.toDouble()).put(s.gy.toDouble()).put(s.gz.toDouble()))
        }
        return g
    }

    /** Decoded frame: the two sample lists pulled off the wire. */
    data class Decoded(
        val accel: List<ImuAccelSample>,
        val gyro: List<ImuGyroSample>,
        /** 0.1.43.20: zweiter IMU-Chip (leer = nicht vorhanden / ältere App). */
        val accel2: List<ImuAccelSample> = emptyList(),
        val gyro2: List<ImuGyroSample> = emptyList(),
        /** 0.1.43.20: [accel] und [gyro] vom selben Chip (gleiche Achsen). */
        val gleicherChip: Boolean = false,
        /** 0.1.43.20: Messbereich der Haupt-Beschleunigung (m/s², NaN = unbekannt) – für die harte Grenze. */
        val bereich1: Double = Double.NaN,
    )

    /**
     * Decode one wire line back into samples. Returns null if [line] is not an
     * IMU_BATCH (the daemon's IPC server multiplexes many command types on the
     * same port, so a non-match just means "not for us"). Malformed entries are
     * skipped defensively rather than throwing — a corrupt sample must not stall
     * the whole stream.
     */
    fun decode(line: String): Decoded? {
        val obj = try { JSONObject(line) } catch (_: Throwable) { return null }
        return decode(obj)
    }

    /**
     * Decode from an ALREADY-PARSED JSONObject. The IPC server parses the wire
     * line once to read the command; feeding that same object here avoids a
     * redundant toString()+re-parse of the (nested-array) batch ~10×/sec on the
     * IPC reader thread that shares the SDM665 with the camera pipeline.
     */
    fun decode(obj: JSONObject): Decoded? {
        if (obj.optString(KEY_COMMAND) != COMMAND) return null
        return Decoded(
            accel = accelListe(obj.optJSONArray(KEY_ACCEL)),
            gyro = gyroListe(obj.optJSONArray(KEY_GYRO)),
            accel2 = accelListe(obj.optJSONArray(KEY_ACCEL2)),
            gyro2 = gyroListe(obj.optJSONArray(KEY_GYRO2)),
            gleicherChip = obj.optInt(KEY_GLEICHER_CHIP, 0) == 1,
            bereich1 = obj.optDouble(KEY_BEREICH1, Double.NaN),
        )
    }

    private fun accelListe(quelle: JSONArray?): List<ImuAccelSample> {
        val accel = ArrayList<ImuAccelSample>()
        quelle?.let { arr ->
            // Enforce the per-sensor cap HERE (audit detection #11): the daemon
            // ingest consumes untrusted local-IPC bytes, and the sidecar-side cap in
            // RoadSenseImuSidecarService is no defence against a malformed/oversized
            // or hostile IMU_BATCH line. Clamp the loop (and pre-size the list) so a
            // huge array can't allocate unbounded or run the 100 Hz pipeline
            // thousands of times synchronously on the IPC reader thread.
            val n = minOf(arr.length(), MAX_SAMPLES_PER_FRAME)
            accel.ensureCapacity(n)
            for (i in 0 until n) {
                val row = arr.optJSONArray(i) ?: continue
                if (row.length() < 4) continue
                accel.add(
                    ImuAccelSample(
                        tMs = row.optLong(0),
                        ax = row.optDouble(1).toFloat(),
                        ay = row.optDouble(2).toFloat(),
                        az = row.optDouble(3).toFloat(),
                    )
                )
            }
        }
        return accel
    }

    private fun gyroListe(quelle: JSONArray?): List<ImuGyroSample> {
        val gyro = ArrayList<ImuGyroSample>()
        quelle?.let { arr ->
            val n = minOf(arr.length(), MAX_SAMPLES_PER_FRAME)
            gyro.ensureCapacity(n)
            for (i in 0 until n) {
                val row = arr.optJSONArray(i) ?: continue
                if (row.length() < 4) continue
                gyro.add(
                    ImuGyroSample(
                        tMs = row.optLong(0),
                        gx = row.optDouble(1).toFloat(),
                        gy = row.optDouble(2).toFloat(),
                        gz = row.optDouble(3).toFloat(),
                    )
                )
            }
        }
        return gyro
    }
}
