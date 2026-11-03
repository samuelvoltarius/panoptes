package at.greenchili.surfmate.tacho;

/**
 * Reiner Besitzautomat fuer die spaetere Reihenfolge Layout -> Blinkerkamera -> Restore.
 * Er ist nicht mit Kamera- oder Projektionscode verbunden.
 */
public final class TachoMehrfachPrioritaet {
    public enum Anzeige { INSTRUMENTE, LAYOUT, KAMERA_LINKS, KAMERA_RECHTS }

    private Anzeige anzeige = Anzeige.INSTRUMENTE;
    private String layoutId;
    private long layoutGeneration;
    private String restoreLayoutId;
    private long restoreGeneration;

    public synchronized Anzeige anzeige() { return anzeige; }
    public synchronized String layoutId() { return layoutId; }
    public synchronized long layoutGeneration() { return layoutGeneration; }

    public synchronized void layoutAktiv(String id, long generation) {
        layoutId = id;
        layoutGeneration = generation;
        if (anzeige != Anzeige.KAMERA_LINKS && anzeige != Anzeige.KAMERA_RECHTS) {
            anzeige = id == null ? Anzeige.INSTRUMENTE : Anzeige.LAYOUT;
        }
    }

    public synchronized void kameraStart(boolean links) {
        restoreLayoutId = layoutId;
        restoreGeneration = layoutGeneration;
        anzeige = links ? Anzeige.KAMERA_LINKS : Anzeige.KAMERA_RECHTS;
    }

    /** Restore nur auf exakt denselben Besitzstand; jede Abweichung faellt auf Instrumente. */
    public synchronized void kameraEnde(boolean links, boolean overlayUndRestoreOk) {
        Anzeige erwartet = links ? Anzeige.KAMERA_LINKS : Anzeige.KAMERA_RECHTS;
        if (anzeige != erwartet || !overlayUndRestoreOk
                || restoreLayoutId == null
                || !restoreLayoutId.equals(layoutId)
                || restoreGeneration != layoutGeneration) {
            normaleInstrumente();
            return;
        }
        anzeige = Anzeige.LAYOUT;
        restoreLayoutId = null;
        restoreGeneration = 0L;
    }

    public synchronized void normaleInstrumente() {
        anzeige = Anzeige.INSTRUMENTE;
        layoutId = null;
        layoutGeneration = 0L;
        restoreLayoutId = null;
        restoreGeneration = 0L;
    }
}
