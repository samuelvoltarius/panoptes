package at.greenchili.surfmate.tacho.oem;

import com.google.flatbuffers.FlatBufferBuilder;
import com.google.flatbuffers.Table;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * FlatBuffers wire contract used by BYD's OEM AMap bridge for
 * {@code AutoContainer.sendInfo2(4, payload)}.
 *
 * <p>Field order is taken from the MIT-licensed DashCast implementation, which in turn
 * documents the schema-generated class from the OEM bridge. This class contains no OEM
 * executable code or assets; it is only the public wire layout.</p>
 */
public final class NaviInfo extends Table {
    public static NaviInfo getRootAsNaviInfo(ByteBuffer bb) {
        bb.order(ByteOrder.LITTLE_ENDIAN);
        return new NaviInfo().__assign(bb.getInt(bb.position()) + bb.position(), bb);
    }

    public NaviInfo __assign(int pos, ByteBuffer buffer) {
        __reset(pos, buffer);
        return this;
    }

    public int naviState() { int o = __offset(4); return o != 0 ? bb.getInt(o + bb_pos) : 0; }
    public String nextRouteName() { int o = __offset(6); return o != 0 ? __string(o + bb_pos) : null; }
    public int curToSegmentDist() { int o = __offset(8); return o != 0 ? bb.getInt(o + bb_pos) : 0; }
    public int nextTurnIcon() { int o = __offset(12); return o != 0 ? bb.getInt(o + bb_pos) : 0; }
    public int routeRemainTime() { int o = __offset(14); return o != 0 ? bb.getInt(o + bb_pos) : 0; }
    public int routeRemainDist() { int o = __offset(16); return o != 0 ? bb.getInt(o + bb_pos) : 0; }
    public int roungAboutNum() { int o = __offset(36); return o != 0 ? bb.getInt(o + bb_pos) : 0; }

    public static int createNaviInfo(FlatBufferBuilder b,
            int naviState, int nextRouteName, int curToSegmentDist, int forwardState,
            int nextTurnIcon, int routeRemainTime, int routeRemainDist, int eta,
            int exitName, int exitDirection, int remainDistanceText, int remainTimeText,
            int segmentDistanceText, int nextNextTurnIcon, int nextToSegmentDist,
            int nextNextRouteName, int roundAboutNum, int nextRoundAboutNum) {
        b.startTable(18);
        b.addInt(17, nextRoundAboutNum, 0);
        b.addInt(16, roundAboutNum, 0);
        b.addOffset(15, nextNextRouteName, 0);
        b.addInt(14, nextToSegmentDist, 0);
        b.addInt(13, nextNextTurnIcon, 0);
        b.addOffset(12, segmentDistanceText, 0);
        b.addOffset(11, remainTimeText, 0);
        b.addOffset(10, remainDistanceText, 0);
        b.addOffset(9, exitDirection, 0);
        b.addOffset(8, exitName, 0);
        b.addOffset(7, eta, 0);
        b.addInt(6, routeRemainDist, 0);
        b.addInt(5, routeRemainTime, 0);
        b.addInt(4, nextTurnIcon, 0);
        b.addOffset(3, forwardState, 0);
        b.addInt(2, curToSegmentDist, 0);
        b.addOffset(1, nextRouteName, 0);
        b.addInt(0, naviState, 0);
        return b.endTable();
    }
}
