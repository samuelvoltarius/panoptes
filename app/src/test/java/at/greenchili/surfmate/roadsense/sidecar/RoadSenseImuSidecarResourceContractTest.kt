package at.greenchili.surfmate.roadsense.sidecar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class RoadSenseImuSidecarResourceContractTest {

    @Test
    fun relaxedModeKeepsServiceResidentWithoutStreamingSensors() {
        val source = readSource()
        val slowBranch = source.substringAfter("if (rate == ImuRate.SLOW)")
            .substringBefore("val activeAccel")
        val sensorCallback = source.substringAfter(
            "override fun onSensorChanged(event: SensorEvent)"
        ).substringBefore("override fun onAccuracyChanged")

        assertTrue(slowBranch.contains("closeIpcSocket()"))
        assertTrue(slowBranch.contains("registeredRate = ImuRate.SLOW"))
        assertTrue(slowBranch.contains("return"))
        // SLOW-Nachzügler werden weiter ignoriert; streamen dürfen nur FAST (RoadSense) und
        // PARK (Wächter-Erschütterung, nur Beschleunigung).
        assertTrue(sensorCallback.contains("rate != ImuRate.FAST && rate != ImuRate.PARK) return"))
        assertFalse(source.contains("SensorManager.SENSOR_DELAY_NORMAL"))
    }

    private fun readSource(): String {
        val current = Paths.get("").toAbsolutePath()
        val candidates = listOf(
            current.resolve(
                "src/main/java/at/greenchili/surfmate/roadsense/sidecar/" +
                    "RoadSenseImuSidecarService.kt"
            ),
            current.resolve(
                "app/src/main/java/at/greenchili/surfmate/roadsense/sidecar/" +
                    "RoadSenseImuSidecarService.kt"
            ),
        )
        val path: Path = candidates.firstOrNull(Files::exists)
            ?: error("Could not locate RoadSenseImuSidecarService.kt")
        return String(Files.readAllBytes(path), StandardCharsets.UTF_8)
    }
}
