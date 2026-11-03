package at.greenchili.surfmate.navmap.oem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class ExternalNavigationNotificationParserTest {
    @Test fun rejectsPackagesOutsideAllowList() {
        assertNull(parse("example.navigation", "In 500 m turn right"))
    }

    @Test fun rejectsIncompleteOrDecorativeNotifications() {
        assertNull(parse("com.waze", "Turn right"))
        assertNull(parse("com.waze", "500 m to destination"))
    }

    @Test fun parsesCompactGoogleDistanceAndRightTurn() {
        val frame = parse("com.google.android.apps.maps", "In 500m, turn right")
        assertEquals(11, frame!!.maneuverType)
        assertEquals(500, frame.distanceMeters)
    }

    @Test fun parsesGermanLeftTurn() {
        val frame = parse("com.google.android.apps.maps", "In 1,2 km links abbiegen")
        assertEquals(15, frame!!.maneuverType)
        assertEquals(1_200, frame.distanceMeters)
    }

    @Test fun parsesFrenchRoundaboutExit() {
        val frame = parse("com.google.android.apps.maps", "Dans 300 m, au rond-point prendre la 2e sortie")
        assertNotNull(frame)
        assertEquals(26, frame!!.maneuverType)
        assertEquals(2, frame.roundaboutExit)
    }

    @Test fun parsesWazeImperialDistance() {
        val frame = parse("com.waze", "In 0.5 mi turn left")
        assertEquals(15, frame!!.maneuverType)
        assertEquals(805, frame.distanceMeters)
    }

    private fun parse(packageName: String, text: String) =
        ExternalNavigationNotificationParser.parse(packageName, listOf(text))
}
