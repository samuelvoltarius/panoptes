package at.greenchili.surfmate.recording;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 0.1.43.20 (FIX_014320_CLIP): Halt für einen laufenden Ereignis-Clip in {@link ManualClipService}. Rein und ohne
 * Android testbar.
 *
 * <p>Der Export-Thread meldet {@link #beginnen()} und {@link #beenden()}. Ein Abbau der Kamera (Zündung aus, Schalten
 * nach P, Aufnahme-Spur aus) ruft {@link #vorAbbauSichern(long)}: Der Nachlauf endet sofort ({@link #vorzeitig()}),
 * der Thread sichert, was im Ring ist, und erst danach geht der Abbau weiter, höchstens {@link #ABBAU_WARTEN_MS}.
 */
public final class EreignisHalt {

    /** Höchstens so lange wartet ein Abbau auf den Export des Ereignis-Clips. */
    public static final long ABBAU_WARTEN_MS = 8_000L;

    private volatile boolean laeuft;
    private final AtomicBoolean vorzeitig = new AtomicBoolean(false);
    private final Object signal = new Object();

    public boolean laeuft() {
        return laeuft;
    }

    /** Vom Export-Thread abgefragt: Nachlauf jetzt beenden. */
    public AtomicBoolean vorzeitig() {
        return vorzeitig;
    }

    public void beginnen() {
        synchronized (signal) {
            vorzeitig.set(false);
            laeuft = true;
        }
    }

    public void beenden() {
        synchronized (signal) {
            laeuft = false;
            vorzeitig.set(false);
            signal.notifyAll();
        }
    }

    /** @return true, wenn kein Ereignis-Clip (mehr) aussteht */
    public boolean vorAbbauSichern(long maxWarteMs) {
        if (!laeuft) return true;
        vorzeitig.set(true);
        long bis = System.nanoTime() + Math.max(0L, maxWarteMs) * 1_000_000L;
        synchronized (signal) {
            while (laeuft && System.nanoTime() < bis) {
                try {
                    signal.wait(100L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return !laeuft;
    }

    /**
     * Ende des Ereignis-Clips nach dem Nachlauf. Vollständig angekommen → gewünschtes Ende. Sonst (Abbau steht an,
     * Encoder nicht mehr frisch oder Frist um) bis zum letzten Bild im Ring, sofern mindestens {@code minSpanUs} ab
     * Start da ist; sonst {@link Long#MIN_VALUE} (nichts zu sichern).
     */
    public static long ende(long gewuenschtesEndeUs, long letztePtsUs, long startPtsUs, long minSpanUs) {
        if (letztePtsUs >= gewuenschtesEndeUs) return gewuenschtesEndeUs;
        if (letztePtsUs == Long.MIN_VALUE || letztePtsUs - startPtsUs < minSpanUs) return Long.MIN_VALUE;
        return letztePtsUs;
    }
}
