package at.greenchili.surfmate.server;

final class RecordingReconcilePolicy {
    private RecordingReconcilePolicy() {}

    static boolean shouldDeleteMissingRow(boolean rootAvailable,
                                          boolean scanComplete,
                                          boolean rowDiscovered) {
        return rootAvailable && scanComplete && !rowDiscovered;
    }
}
