package at.greenchili.surfmate.camera;

/** Test-Zugang zu den paketinternen Leerlauf-Helfern von {@link PanoramicCameraGpu} (0.1.38.11, K1). */
public final class PanoramicCameraGpuLeerlaufZugang {
    private PanoramicCameraGpuLeerlaufZugang() {}

    public static boolean gueltig(long seitMs, long jetztMs) {
        return PanoramicCameraGpu.leerlaufGueltig(seitMs, jetztMs);
    }

    public static long gueltigMs() {
        return PanoramicCameraGpu.OHNE_VERBRAUCHER_GUELTIG_MS;
    }
}
