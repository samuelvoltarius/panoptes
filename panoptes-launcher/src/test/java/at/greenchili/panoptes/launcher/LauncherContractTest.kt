package at.greenchili.panoptes.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherContractTest {
    @Test fun parsesSummaryWithoutInventingMissingValues() {
        val value = LauncherContract.parseSummary(
            """{"vehicle":{"name":"BYD Seal U DM-i"},"range":{"totalKm":512,"socPct":73.4},"state":{"acc":"on","gear":"D"}}"""
        )
        assertEquals("BYD Seal U DM-i", value.vehicleName)
        assertEquals(512.0, value.rangeKm!!, 0.0)
        assertEquals(73.4, value.socPct!!, 0.0)
        assertEquals("D", value.gear)

        val empty = LauncherContract.parseSummary("{}")
        assertNull(empty.vehicleName)
        assertNull(empty.rangeKm)
        assertNull(empty.socPct)
    }

    @Test fun filtersMalformedAppsAndUsesFullMode() {
        val apps = LauncherContract.parseApps(
            """{"success":true,"apps":[{"package":"com.example.nav","label":"Navi"},{"package":""}]}"""
        )
        assertEquals(listOf(LaunchableApp("com.example.nav", "Navi")), apps)
        val body = LauncherContract.launchBody("com.example.nav")
        assertTrue(body.contains("\"mode\":\"full\""))
        assertFalse(body.contains("split"))
    }

    @Test fun launchResultIsFailClosed() {
        assertTrue(LauncherContract.launchSucceeded("{\"success\":true}"))
        assertFalse(LauncherContract.launchSucceeded("{\"success\":false}"))
        assertFalse(LauncherContract.launchSucceeded("{}"))
    }

    @Test fun parsesReadOnlyGlanceEndpointsFailSoft() {
        val traffic = LauncherContract.parseTraffic(
            """{"actors":[{"trend":"approaching"},{"trend":"steady"}]}"""
        )
        assertEquals(2, traffic.nearby)
        assertEquals(1, traffic.approaching)

        val history = LauncherContract.parseHistory(
            """{"trips":[{"distanceKm":12.4}],"charging":[{"kwh":21.8}]}"""
        )
        assertEquals(12.4, history.lastTripKm!!, 0.0)
        assertEquals(21.8, history.lastChargeKwh!!, 0.0)

        assertEquals(LauncherTraffic(0, 0), LauncherContract.parseTraffic("{}"))
        assertEquals("auto", LauncherContract.parseAppearance("{\"theme\":\"invalid\"}").theme)
    }
}
