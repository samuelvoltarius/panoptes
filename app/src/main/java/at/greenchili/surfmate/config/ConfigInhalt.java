package at.greenchili.surfmate.config;

/**
 * 0.1.20 (Tempo): Inhaltsvergleich der Config-Datei per CRC32, damit die 1-s-Revalidierung von UnifiedConfigManager
 * eine unveränderte Datei nicht jedes Mal neu parst (Messung am Auto: 299 KB etwa 1×/s). Rein, testbar.
 */
public final class ConfigInhalt {

    private ConfigInhalt() {}

    /** CRC32 der Bytes; nie 0 (0 = „unbekannt“ im Aufrufer). */
    public static long crc(byte[] bytes) {
        java.util.zip.CRC32 c = new java.util.zip.CRC32();
        c.update(bytes, 0, bytes.length);
        long v = c.getValue();
        return v == 0L ? 1L : v;
    }

    /** Gleich, wenn bekannt (≠ 0) und identisch. */
    public static boolean unveraendert(long jetzt, long gemerkt) {
        return gemerkt != 0L && jetzt == gemerkt;
    }
}
