package at.greenchili.surfmate.tacho.oem

import com.google.flatbuffers.FlatBufferBuilder

/** Builds the exact BYD NaviInfo FlatBuffer accepted by AutoContainer message type 4. */
object NaviInfoPayload {
    @JvmStatic
    fun build(
        naviState: Int,
        nextRouteName: String = "",
        distanceMeters: Int = 0,
        turnIcon: Int = 0,
        remainingSeconds: Int = 0,
        remainingMeters: Int = 0,
        roundaboutExit: Int = 0,
    ): ByteArray {
        val b = FlatBufferBuilder(256)
        val routeName = b.createString(nextRouteName.take(120))
        val empty = b.createString("")
        val root = NaviInfo.createNaviInfo(
            b, naviState, routeName, distanceMeters, empty, turnIcon,
            remainingSeconds, remainingMeters, empty, empty, empty, empty, empty,
            empty, 0, 0, empty, roundaboutExit, 0
        )
        b.finish(root)
        return b.sizedByteArray()
    }
}
