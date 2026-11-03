package at.greenchili.panoptes.launcher

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

class LauncherRefreshContractTest {
    @Test fun slowLocalhostDoesNotQueueUnboundedRefreshes() {
        val source = String(
            Files.readAllBytes(Paths.get("src/main/java/at/greenchili/panoptes/launcher/MainActivity.kt")),
            Charsets.UTF_8
        )
        assertTrue(source.contains("private val refreshInFlight = AtomicBoolean(false)"))
        assertTrue(source.contains("if (!refreshInFlight.compareAndSet(false, true)) return"))
        assertTrue(source.contains("finally {\n                refreshInFlight.set(false)"))
    }
}
