package at.greenchili.surfmate.ui.about

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleVersionInfoWebAssetTest {
    @Test
    fun webAboutRendersAllPublicFieldsWithoutVin() {
        val html = readRepositoryFile("app/src/main/assets/web/local/about.html")
        val server = readRepositoryFile("app/src/main/java/at/greenchili/surfmate/server/HttpServer.java")

        assertTrue(html.contains("fetch('/api/about/vehicle-info'"))
        for (key in listOf("firmware", "dsp", "mcu", "android", "securityPatch", "headUnit")) {
            assertTrue("Missing web About field: $key", html.contains("data-vehicle-info=\"$key\""))
        }
        assertFalse(html.contains("data-vehicle-info=\"vin\""))
        assertTrue(html.contains("data-i18n=\"about.vehicle_vin_private\""))
        assertTrue(server.contains("path.equals(\"/api/about/vehicle-info\")"))

        val authCheck = server.indexOf("AuthMiddleware.checkAuth(path")
        val requestRouting = server.indexOf("routeToHandlers(method", authCheck)
        assertTrue("Web About must remain behind HTTP authentication", authCheck >= 0)
        assertTrue(
            "Web About routing must happen only after authentication",
            requestRouting > authCheck
        )
    }

    /** 0.1.26.1: Am Auto blieb alles „—“, weil tSafe nicht definiert war (ReferenceError). */
    @Test
    fun helperIsDefinedAndMissingValuesSayNotAvailable() {
        val html = readRepositoryFile("app/src/main/assets/web/local/about.html")
        assertTrue(html.contains("tSafe("))
        assertTrue("tSafe muss definiert sein", html.contains("function tSafe(key, fallback)"))
        assertTrue("nie ewig warten", html.contains("abbruch.abort()"))
        val de = org.json.JSONObject(readRepositoryFile("app/src/main/assets/web/i18n/de.json"))
        assertTrue(de.getJSONObject("about").getString("vehicle_unavailable") == "nicht verfügbar")
        for (l in listOf("en", "fr", "it", "nl")) {
            val t = org.json.JSONObject(readRepositoryFile("app/src/main/assets/web/i18n/$l.json"))
                .getJSONObject("about").getString("vehicle_unavailable")
            assertTrue(l, t.isNotBlank())
        }
    }

    @Test
    fun vehicleInformationFollowsReleaseNewsAndPrecedesLegalInformation() {
        val html = readRepositoryFile("app/src/main/assets/web/local/about.html")

        val version = html.indexOf("id=\"pnVersion\"")
        val feedback = html.indexOf("id=\"fehlerMelden\"")
        val whatsNew = html.indexOf("id=\"neu\"")
        val vehicleInfo = html.indexOf("id=\"vehicleInfoSection\"")
        val notices = html.indexOf("id=\"hinweise\"")
        val backup = html.indexOf("id=\"backupMount\"")

        assertTrue("Missing current About sections", listOf(
            version, feedback, whatsNew, vehicleInfo, notices, backup
        ).all { it >= 0 })
        assertTrue("About sections must keep their user-facing priority",
            version < feedback && feedback < whatsNew && whatsNew < vehicleInfo &&
                vehicleInfo < notices && notices < backup)
    }

    private fun readRepositoryFile(relative: String): String {
        var current: Path? = Paths.get("").toAbsolutePath()
        while (current != null) {
            val direct = current.resolve(relative)
            if (Files.exists(direct)) {
                return at.greenchili.surfmate.testutil.Quelltext.lies(direct)
            }
            val fromModule = current.resolve(relative.removePrefix("app/"))
            if (Files.exists(fromModule)) {
                return at.greenchili.surfmate.testutil.Quelltext.lies(fromModule)
            }
            current = current.parent
        }
        throw AssertionError("Could not locate $relative")
    }
}
