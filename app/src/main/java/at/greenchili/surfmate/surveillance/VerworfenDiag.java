package at.greenchili.surfmate.surveillance;

/**
 * 0.1.29 (Befund 29.10. 14:21, Projektinhaber von hinten nicht erkannt): Warum eine Kachel mit viel Bewegung KEIN
 * „Motion started“ ergab, stand bisher nur im logcat (native LOGI) oder auf DEBUG. Diese Zeile kommt ins
 * Daemon-Log, gedrosselt je Kachel, damit der nächste Fall belegbar ist. Rein, testbar.
 */
final class VerworfenDiag {

    private VerworfenDiag() {}

    /** Ab so vielen aktiven Blöcken (von 70) ist eine verworfene Kachel einer Logzeile wert. */
    static final int MIN_AKTIV = 4;
    /** Höchstens eine Zeile je Kachel in diesem Abstand. */
    static final long ABSTAND_MS = 5_000L;

    /**
     * Grund in der Reihenfolge der nativen Stufen (motion_pipeline_v2.cpp processQuadrant):
     * Lichtwechsel → Fläche > 25 % → nicht bestätigt → zu klein → Zone → Alarmschwelle → nur Vorbeigehen.
     */
    static String grund(boolean helligkeit, int salienceState, int aktiv, int bestaetigt, int komponente,
                        float schwerpunktZeile, int minKomponente, int zoneZeile, int alarmBloecke) {
        if (helligkeit) return "Lichtwechsel";
        if (salienceState == 1) return "Fläche > 25 %, als Lichtblitz gewertet";
        if (aktiv * 4 > 70 && bestaetigt == 0) return "Fläche > 25 %, als Lichtblitz gewertet";
        if (bestaetigt == 0) return "noch nicht bestätigt";
        if (komponente < minKomponente) return "zu klein";
        if (zoneZeile > 0 && schwerpunktZeile < zoneZeile) {
            return String.format(java.util.Locale.ROOT, "Zone (Schwerpunkt Zeile %.1f < %d, gilt als zu weit weg)",
                    schwerpunktZeile, zoneZeile);
        }
        if (bestaetigt < alarmBloecke) return "unter Alarmschwelle";
        return "nur Vorbeigehen";
    }

    static boolean loggen(int aktiv, boolean bewegung, long jetztMs, long letzteMs) {
        return !bewegung && aktiv >= MIN_AKTIV && jetztMs - letzteMs >= ABSTAND_MS;
    }
}
