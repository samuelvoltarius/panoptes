package at.greenchili.panoptes.launcher

import org.json.JSONObject

data class LauncherSummary(
    val vehicleName: String?,
    val rangeKm: Double?,
    val socPct: Double?,
    val acc: String?,
    val gear: String?
)

data class LaunchableApp(val packageName: String, val label: String)
data class LauncherTraffic(val nearby: Int, val approaching: Int)
data class LauncherHistory(val lastTripKm: Double?, val lastChargeKwh: Double?)
data class LauncherAppearance(val theme: String, val material: String)

object LauncherContract {
    fun parseSummary(json: String): LauncherSummary {
        val root = JSONObject(json)
        val vehicle = root.optJSONObject("vehicle")
        val range = root.optJSONObject("range")
        val state = root.optJSONObject("state")
        return LauncherSummary(
            vehicle?.nullableString("name"),
            range?.nullableDouble("totalKm"),
            range?.nullableDouble("socPct"),
            state?.nullableString("acc"),
            state?.nullableString("gear")
        )
    }

    fun parseApps(json: String): List<LaunchableApp> {
        val root = JSONObject(json)
        if (!root.optBoolean("success", false)) return emptyList()
        val array = root.optJSONArray("apps") ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val pkg = item.optString("package").trim()
                if (pkg.isEmpty()) continue
                add(LaunchableApp(pkg, item.optString("label", pkg).ifBlank { pkg }))
            }
        }
    }

    fun parseTraffic(json: String): LauncherTraffic {
        val actors = JSONObject(json).optJSONArray("actors")
            ?: return LauncherTraffic(0, 0)
        var approaching = 0
        for (i in 0 until actors.length()) {
            if (actors.optJSONObject(i)?.optString("trend") == "approaching") approaching++
        }
        return LauncherTraffic(actors.length(), approaching)
    }

    fun parseHistory(json: String): LauncherHistory {
        val root = JSONObject(json)
        val trip = root.optJSONArray("trips")?.optJSONObject(0)
        val charge = root.optJSONArray("charging")?.optJSONObject(0)
        return LauncherHistory(
            trip?.nullableDouble("distanceKm"),
            charge?.nullableDouble("kwh")
        )
    }

    fun parseAppearance(json: String): LauncherAppearance {
        val root = JSONObject(json)
        val theme = root.optString("theme", "auto")
            .takeIf { it in setOf("auto", "polar", "glacier", "ember", "signal") } ?: "auto"
        val material = root.optString("material", "crystal")
            .takeIf { it == "crystal" || it == "smoke" } ?: "crystal"
        return LauncherAppearance(theme, material)
    }

    fun launchBody(packageName: String): String = JSONObject()
        .put("package", packageName)
        .put("mode", "full")
        .toString()

    fun launchSucceeded(json: String): Boolean {
        val root = JSONObject(json)
        return root.optBoolean("success", root.optBoolean("ok", false))
    }

    private fun JSONObject.nullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun JSONObject.nullableDouble(key: String): Double? =
        if (!has(key) || isNull(key)) null else optDouble(key).takeUnless { it.isNaN() }
}
