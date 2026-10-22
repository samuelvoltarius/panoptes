package at.greenchili.surfmate.server;

import static org.junit.Assert.assertSame;

import at.greenchili.surfmate.surveillance.GpuPipelineConfig;

import org.junit.Test;

public class StreamingApiHandlerBroadwayTest {

    @Test
    public void capsOnlyDilink5BroadwayProfilesAboveFifteenFps() {
        assertSame(
                GpuPipelineConfig.StreamingQuality.ULTRA_HIGH,
                StreamingApiHandler.capBroadwayQuality(
                        GpuPipelineConfig.StreamingQuality.MAX, true, true));
        assertSame(
                GpuPipelineConfig.StreamingQuality.MAX,
                StreamingApiHandler.capBroadwayQuality(
                        GpuPipelineConfig.StreamingQuality.MAX, false, true));
        assertSame(
                GpuPipelineConfig.StreamingQuality.MAX,
                StreamingApiHandler.capBroadwayQuality(
                        GpuPipelineConfig.StreamingQuality.MAX, true, false));
        assertSame(
                GpuPipelineConfig.StreamingQuality.HIGH,
                StreamingApiHandler.capBroadwayQuality(
                        GpuPipelineConfig.StreamingQuality.HIGH, true, true));
    }
}
