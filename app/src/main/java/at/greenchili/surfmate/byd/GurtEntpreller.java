package at.greenchili.surfmate.byd;

import java.util.ArrayDeque;

/**
 * 0.1.23 (Deploy-Beleg 28.10., Koordination): Der Beifahrer-Gurt-Callback {@code onSafetyBeltStatusChanged} flattert am
 * geparkten Auto bei leerem Sitz etwa alle 0,4 s zwischen buckled und unbuckled (9712 von 26 540 Logzeilen). Rein und
 * testbar; der Datensammler füttert ihn mit jedem Callback ({@link #roh}) und einem Nachlauf-Takt ({@link #takt}).
 *
 * <ul>
 *   <li><b>Zustand entprellt:</b> Ein Wert gilt erst, wenn er {@link #HALTE_MS} unverändert stand. Erst dann geht er an
 *   den Gurt-Tracker, an Automationen und an DiLink5 – ein flatternder Wert kommt dort nie an.</li>
 *   <li><b>Flattern erkannt:</b> mehr als {@link #FLATTER_WECHSEL} Wechsel in {@link #FENSTER_MS}. Dann eine Zeile
 *   „flattert“ und danach höchstens eine Zusammenfassung je {@link #ZUSAMMENFASSUNG_MS}; nach {@link #FENSTER_MS} ohne
 *   Wechsel „wieder ruhig“.</li>
 *   <li>Ohne Flattern wird jeder Wechsel wie bisher geloggt (höchstens drei je 10 s).</li>
 * </ul>
 */
final class GurtEntpreller {

    static final long HALTE_MS = 2_000L, FENSTER_MS = 10_000L, ZUSAMMENFASSUNG_MS = 60_000L;
    static final int FLATTER_WECHSEL = 3;
    static final int UNBEKANNT = BydVehicleData.UNAVAILABLE;

    /** Ergebnis eines Aufrufs: ggf. eine Logzeile und ggf. ein neu geltender Zustand. */
    static final class Ausgabe {
        final String logZeile;
        final int neuerZustand;

        Ausgabe(String logZeile, int neuerZustand) {
            this.logZeile = logZeile;
            this.neuerZustand = neuerZustand;
        }

        boolean zustandGeaendert() {
            return neuerZustand != UNBEKANNT;
        }
    }

    private int roh = UNBEKANNT, stabil = UNBEKANNT;
    private long rohSeit;
    private final ArrayDeque<Long> wechsel = new ArrayDeque<>();
    private boolean flattert;
    private int flatterWechsel;
    private long letzteZusammenfassung;

    /** Ein Callback-Wert (0/1). */
    synchronized Ausgabe roh(int zustand, long jetzt) {
        if (zustand != 0 && zustand != 1) return takt(jetzt);
        if (zustand == roh) return takt(jetzt);
        boolean ersterWert = roh == UNBEKANNT;
        roh = zustand;
        rohSeit = jetzt;
        String zeile = null;
        if (!ersterWert) {
            wechsel.addLast(jetzt);
            while (!wechsel.isEmpty() && jetzt - wechsel.peekFirst() > FENSTER_MS) wechsel.removeFirst();
        }
        if (flattert) {
            flatterWechsel++;
            if (jetzt - letzteZusammenfassung >= ZUSAMMENFASSUNG_MS) {
                zeile = "onSafetyBeltStatusChanged: front passenger flattert weiter: " + flatterWechsel + " Wechsel in "
                        + ((jetzt - letzteZusammenfassung) / 1000) + " s, zuletzt " + name(zustand) + ", gilt weiter " + name(stabil);
                letzteZusammenfassung = jetzt;
                flatterWechsel = 0;
            }
        } else if (wechsel.size() > FLATTER_WECHSEL) {
            flattert = true;
            letzteZusammenfassung = jetzt;
            flatterWechsel = 0;
            zeile = "onSafetyBeltStatusChanged: front passenger flattert (" + wechsel.size() + " Wechsel in "
                    + (FENSTER_MS / 1000) + " s) – nur noch eine Zusammenfassung je Minute, gilt weiter " + name(stabil);
        } else {
            zeile = "onSafetyBeltStatusChanged: front passenger=" + name(zustand);
        }
        Ausgabe t = takt(jetzt);
        return new Ausgabe(zeile != null ? zeile : t.logZeile, t.neuerZustand);
    }

    /** Nachlauf (ohne neuen Callback): Wert übernehmen, wenn er lange genug stand; Flattern beenden. */
    synchronized Ausgabe takt(long jetzt) {
        String zeile = null;
        if (flattert && jetzt - rohSeit >= FENSTER_MS) {
            flattert = false;
            wechsel.clear();
            zeile = "onSafetyBeltStatusChanged: front passenger wieder ruhig: " + name(roh)
                    + (flatterWechsel > 0 ? " (" + flatterWechsel + " Wechsel seit der letzten Zeile)" : "");
            flatterWechsel = 0;
        }
        int neu = UNBEKANNT;
        if (!flattert && roh != UNBEKANNT && roh != stabil && jetzt - rohSeit >= HALTE_MS) {
            stabil = roh;
            neu = stabil;
        }
        return new Ausgabe(zeile, neu);
    }

    synchronized int stabil() {
        return stabil;
    }

    synchronized boolean flattert() {
        return flattert;
    }

    private static String name(int z) {
        return z == 1 ? "buckled" : z == 0 ? "unbuckled" : "unbekannt";
    }
}
