package at.greenchili.surfmate.gewohnheiten;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 0.1.22 Gewohnheiten (KONZEPT §2a „Echo abgrenzen“): Was Panoptes gerade selbst befohlen hat. Jeder Befehl trägt vor
 * dem Senden „Signal, Zielwert, gültig bis“ ein; eine Zustandsänderung, die eine offene Erwartung trifft, verbraucht
 * sie und zählt nicht als Bedienung am Auto (der Befehl ist schon mit seiner Herkunft mitgeschrieben).
 *
 * <p>Treffer: genau der Zielwert, oder – solange gültig – ein Wert in Befehlsrichtung (Panoptes schickt 22 °C, das
 * Auto meldet 21,5 °C bzw. 21).
 */
public final class Erwartungen {

    /** Lokaler SDK-Weg bzw. Cloud-Weg (KONZEPT §2a). */
    public static final long LOKAL_MS = 15_000L, CLOUD_MS = 120_000L;
    static final int MAX = 64;

    private static final class E {
        final String signal;
        final int ziel;
        final long bis;

        E(String signal, int ziel, long bis) {
            this.signal = signal;
            this.ziel = ziel;
            this.bis = bis;
        }
    }

    private final List<E> offen = new ArrayList<>();

    public synchronized void erwarte(String signal, int ziel, long bis) {
        if (signal == null) return;
        if (offen.size() >= MAX) offen.remove(0);
        offen.add(new E(signal, ziel, bis));
    }

    /** true, wenn die Änderung alt → neu eine offene Erwartung trifft (die dann verbraucht ist). */
    public synchronized boolean verbrauche(String signal, int alt, int neu, long jetzt) {
        E treffer = null;
        for (Iterator<E> it = offen.iterator(); it.hasNext(); ) {
            E e = it.next();
            if (e.bis < jetzt) {
                it.remove();
                continue;
            }
            if (treffer != null || !e.signal.equals(signal)) continue;
            if (e.ziel == neu || (e.ziel != alt && Integer.signum(neu - alt) == Integer.signum(e.ziel - alt))) treffer = e;
        }
        if (treffer == null) return false;
        offen.remove(treffer);
        return true;
    }

    public synchronized int anzahl() {
        return offen.size();
    }
}
