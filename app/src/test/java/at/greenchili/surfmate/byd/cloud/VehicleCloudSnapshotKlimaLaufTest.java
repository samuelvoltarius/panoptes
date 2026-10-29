package at.greenchili.surfmate.byd.cloud;

import static org.junit.Assert.assertEquals;

import org.json.JSONObject;
import org.junit.Test;

/** 0.1.28 (P3): airRunState wird roh übernommen (vehicleInfo, statusNow oder hvac), fehlt er: −1. */
public class VehicleCloudSnapshotKlimaLaufTest {

    @Test
    public void airRunStateRohOderFehlend() throws Exception {
        assertEquals(1, VehicleCloudSnapshot.fromVehicleInfo(new JSONObject().put("airRunState", 1), null).build().airRunState);
        assertEquals(2, VehicleCloudSnapshot.fromVehicleInfo(new JSONObject(),
                new JSONObject().put("statusNow", new JSONObject().put("airRunState", "2"))).build().airRunState);
        assertEquals(-1, VehicleCloudSnapshot.fromVehicleInfo(new JSONObject().put("socPercent", 50), null).build().airRunState);
    }
}
