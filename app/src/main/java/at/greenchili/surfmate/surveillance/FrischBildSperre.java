package at.greenchili.surfmate.surveillance;

/**
 * 0.1.43.22: Sperre für das erste Fahrt-Video nach Zündung an (DiLink 3, Rundumkamera neu geöffnet).
 *
 * <p>Grundlage: OverDrive PR #674 (MIT) und die Nachbesserung des Maintainers im Release-Commit d50b89966
 * (17.11.2026, {@code requireFreshFrameForNextStart}). Nach dem Neu-Öffnen liefert die Kamera auf DiLink 3
 * 5–8 s kein Bild. Ein in dieser Zeit geöffnetes {@code cam_*}-Video blieb leer bzw. trug geparkten Vorlauf.
 *
 * <p>Regel: Das erste {@code cam_*}-Video wartet, bis nach dem Scharfschalten mindestens {@code minBilder} Bilder
 * neu codiert wurden (ein vor dem Neu-Öffnen eingereihtes Bild wird einmal verbraucht, darum zwei). Nach
 * {@link #VERFALL_MS} ohne frisches Bild gibt die Sperre trotzdem frei, damit die Fahrt nie ganz ohne Aufnahme
 * bleibt. Rein (keine Android-Uhr), damit sie auf der JVM testbar ist.
 */
final class FrischBildSperre {

    /** Maintainer: FRESH_FRAME_BARRIER_TTL_MS. */
    static final long VERFALL_MS = 90_000L;
    /** Maintainer: zwei Bilder beweisen eine lebende Kamera. */
    static final int MIN_BILDER = 2;

    static final int AUS = 0;
    static final int WARTEN = 1;
    static final int FRISCH = 2;
    static final int VERFALLEN = 3;

    private FrischBildSperre() {}

    /**
     * @param gesetztMs  Zeitpunkt des Scharfschaltens (elapsedRealtime), 0 = keine Sperre
     * @param basisZahl  Zahl codierter Bilder beim Scharfschalten
     * @param minBilder  so viele Bilder müssen danach neu codiert sein (mindestens 1)
     * @param jetztMs    aktuelle Zeit (gleiche Uhr wie gesetztMs)
     * @param bildZahl   aktuelle Zahl codierter Bilder
     */
    static int pruefe(long gesetztMs, long basisZahl, int minBilder, long jetztMs, long bildZahl) {
        if (gesetztMs <= 0L) return AUS;
        if (bildZahl - basisZahl >= Math.max(1, minBilder)) return FRISCH;
        // Uhr rückwärts (sollte bei elapsedRealtime nicht vorkommen): nicht ewig sperren.
        if (jetztMs < gesetztMs || jetztMs - gesetztMs > VERFALL_MS) return VERFALLEN;
        return WARTEN;
    }

    /** Muss der Start noch warten? */
    static boolean sperrt(long gesetztMs, long basisZahl, int minBilder, long jetztMs, long bildZahl) {
        return pruefe(gesetztMs, basisZahl, minBilder, jetztMs, bildZahl) == WARTEN;
    }

    /** Gilt die Sperre für diese Ausgabedatei? Nur Fahrt-Videos ({@code cam_*}), keine Ereignisse. */
    static boolean betrifft(String dateiName) {
        return dateiName != null && dateiName.startsWith("cam_");
    }
}
