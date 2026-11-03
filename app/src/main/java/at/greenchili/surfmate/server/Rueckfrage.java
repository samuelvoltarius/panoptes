package at.greenchili.surfmate.server;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * Rückfrage vor einer folgenreichen Aktion (0.1.37 Display-Werkzeuge): Erst {@link #frage} liefert eine Marke für
 * genau eine Aktion an genau einem Ziel, erst mit dieser Marke führt der Server sie aus. Ein einzelner Aufruf kann
 * also nie deaktivieren oder löschen; die Oberfläche zeigt dazwischen die Rückfrage.
 *
 * <p>Marken gelten {@link #GILT_MS}, nur einmal, nur für dieselbe Aktion und dasselbe Ziel. Nur im Speicher.
 */
public final class Rueckfrage {

    public static final long GILT_MS = 2 * 60 * 1000L;
    static final int MAX_OFFEN = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final class Offen {
        final String aktion, ziel;
        final long ab;

        Offen(String aktion, String ziel, long ab) {
            this.aktion = aktion;
            this.ziel = ziel;
            this.ab = ab;
        }
    }

    private final Map<String, Offen> offen = new HashMap<>();

    /** Neue Marke für {@code aktion} an {@code ziel}. */
    public synchronized String frage(String aktion, String ziel, long now) {
        aufraeumen(now);
        if (offen.size() >= MAX_OFFEN) {
            // älteste raus, nie unbegrenzt wachsen
            String aeltester = null;
            long min = Long.MAX_VALUE;
            for (Map.Entry<String, Offen> e : offen.entrySet()) {
                if (e.getValue().ab < min) {
                    min = e.getValue().ab;
                    aeltester = e.getKey();
                }
            }
            if (aeltester != null) offen.remove(aeltester);
        }
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x));
        String marke = sb.toString();
        offen.put(marke, new Offen(aktion, ziel, now));
        return marke;
    }

    /** true genau einmal, wenn die Marke zu Aktion und Ziel passt und noch gilt. Die Marke ist danach verbraucht. */
    public synchronized boolean bestaetigt(String marke, String aktion, String ziel, long now) {
        aufraeumen(now);
        if (marke == null || aktion == null || ziel == null) return false;
        Offen o = null;
        String treffer = null;
        for (Map.Entry<String, Offen> e : offen.entrySet()) {
            if (MessageDigest.isEqual(e.getKey().getBytes(StandardCharsets.US_ASCII),
                    marke.getBytes(StandardCharsets.US_ASCII))) {
                o = e.getValue();
                treffer = e.getKey();
            }
        }
        if (o == null) return false;
        offen.remove(treffer);
        return o.aktion.equals(aktion) && o.ziel.equals(ziel);
    }

    private void aufraeumen(long now) {
        for (Iterator<Map.Entry<String, Offen>> it = offen.entrySet().iterator(); it.hasNext(); ) {
            Offen o = it.next().getValue();
            if (now - o.ab > GILT_MS || now < o.ab) it.remove();
        }
    }
}
