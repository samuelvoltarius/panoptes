package at.greenchili.surfmate.tacho.oem

/** Valhalla maneuver type to BYD cluster Navigation.qml icon id. */
object OemTurnIcon {
    @JvmStatic
    fun fromValhalla(type: Int): Int = when (type) {
        4, 5, 6 -> 15                 // destination
        9, 18, 20, 23, 37 -> 5       // bear/exit/merge right
        10, 19, 21, 24, 38 -> 4      // bear/exit/merge left
        11 -> 3                       // right
        15 -> 2                       // left
        12 -> 7                       // sharp right
        16 -> 6                       // sharp left
        13 -> 19                      // U-turn right (0 is a firmware no-op)
        14 -> 8                       // U-turn left
        26 -> 11                      // enter roundabout; exit number is a separate field
        27 -> 20                      // leave roundabout / continue
        17, 22, 25 -> 20              // ramp straight / continue / fork
        else -> 9                     // straight; never invent a turn direction
    }

    @JvmStatic
    fun roundaboutExit(type: Int, exit: Int): Int =
        if (type == 26 && exit in 1..10) exit else 0
}
