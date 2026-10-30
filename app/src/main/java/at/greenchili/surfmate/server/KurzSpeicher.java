package at.greenchili.surfmate.server;

import java.util.concurrent.Callable;

/**
 * 0.1.33: Antwort kurz merken, damit mehrere Abrufer dieselbe Arbeit teilen. Rein, testbar.
 * Wer gleichzeitig fragt, wartet auf den einen Aufbau. Ein Fehler wird nicht gemerkt.
 */
final class KurzSpeicher<T> {

    private final long haltbarMs;
    private T wert;
    private long gebautMs;

    KurzSpeicher(long haltbarMs) {
        this.haltbarMs = haltbarMs;
    }

    synchronized T hol(long nowMs, Callable<T> bauen) throws Exception {
        long alter = nowMs - gebautMs;
        if (wert != null && alter >= 0 && alter < haltbarMs) return wert;
        wert = bauen.call();
        gebautMs = nowMs;
        return wert;
    }

    synchronized void leeren() {
        wert = null;
    }
}
