package at.greenchili.panoptes.launcher

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class PanoptesClient(private val baseUrl: String = "http://127.0.0.1:8180") {
    fun summary(): LauncherSummary = LauncherContract.parseSummary(get("/api/launcher/v1/summary"))
    fun traffic(): LauncherTraffic = LauncherContract.parseTraffic(get("/api/launcher/v1/traffic"))
    fun history(): LauncherHistory = LauncherContract.parseHistory(get("/api/launcher/v1/history"))
    fun appearance(): LauncherAppearance = LauncherContract.parseAppearance(get("/api/launcher/v1/appearance"))
    fun apps(): List<LaunchableApp> = LauncherContract.parseApps(get("/api/launcher/v1/apps"))
    fun launch(packageName: String): Boolean = LauncherContract.launchSucceeded(
        request("POST", "/api/launcher/v1/apps/launch", LauncherContract.launchBody(packageName))
    )

    private fun get(path: String): String = request("GET", path, null)

    private fun request(method: String, path: String, body: String?): String {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 1800
            readTimeout = 2500
            useCaches = false
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        return try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() }.orEmpty()
            if (status !in 200..299) throw IllegalStateException("HTTP $status")
            text
        } finally {
            connection.disconnect()
        }
    }
}
