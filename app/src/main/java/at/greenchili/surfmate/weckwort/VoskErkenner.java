package at.greenchili.surfmate.weckwort;

import org.vosk.LibVosk;
import org.vosk.Model;
import org.vosk.Recognizer;

/**
 * Dünne Hülle um Vosk (Apache-2.0) für die Weckwort-Messung. Läuft im Mess-Thread, ein Thread, kein Netz.
 * Erkennungstexte werden nur mit dem festen Messwort verglichen und nie geloggt oder gespeichert.
 */
final class VoskErkenner {

    private Model modell;
    private Recognizer erkenner;

    VoskErkenner(String modellPfad, int abtastrate, String grammatik) throws java.io.IOException {
        // -1: Vosk/Kaldi schreibt gar nichts ins Log (auch keine erkannten Wörter).
        LibVosk.vosk_set_log_level(-1);
        modell = new Model(modellPfad);
        try {
            erkenner = new Recognizer(modell, (float) abtastrate, grammatik);
        } catch (java.io.IOException | RuntimeException e) {
            modell.close();
            modell = null;
            throw e;
        }
    }

    /** true, wenn ein abgeschlossenes Ergebnis das Messwort ist. */
    boolean verarbeite(short[] puffer, int n) {
        if (erkenner == null) return false;
        if (erkenner.acceptWaveForm(puffer, n)) {
            return WeckwortMessRegeln.istMesswort(erkenner.getResult());
        }
        return false;
    }

    void schliessen() {
        try {
            if (erkenner != null) erkenner.close();
        } catch (Throwable ignored) {
        }
        try {
            if (modell != null) modell.close();
        } catch (Throwable ignored) {
        }
        erkenner = null;
        modell = null;
    }
}
