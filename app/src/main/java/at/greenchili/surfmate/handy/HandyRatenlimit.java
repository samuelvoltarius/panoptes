package at.greenchili.surfmate.handy;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * 0.1.18 Handy-Vertrag §3: höchstens {@link #AKTIONEN_JE_MINUTE} Aktionen je Minute und Kopplung, Aufsperren
 * höchstens {@link #AUFSPERREN_JE_10_MIN} je 10 Minuten. Gleitendes Fenster, nur im Speicher.
 */
public final class HandyRatenlimit {

    public static final int AKTIONEN_JE_MINUTE = 6;
    public static final int AUFSPERREN_JE_10_MIN = 3;
    /** Koordination 27.10.: zusätzlich höchstens 6 Aufsperr-Versuche je Stunde. */
    public static final int AUFSPERREN_JE_STUNDE = 6;

    private final Map<String, Deque<Long>> aktionen = new HashMap<>();
    private final Map<String, Deque<Long>> aufsperren = new HashMap<>();

    /** true = erlaubt (und gezählt); false = Ratenlimit. */
    public synchronized boolean erlauben(String kopplungId, boolean istAufsperren, long jetztMs) {
        Deque<Long> a = aktionen.computeIfAbsent(kopplungId, k -> new ArrayDeque<>());
        kuerzen(a, jetztMs - 60_000L);
        if (a.size() >= AKTIONEN_JE_MINUTE) return false;
        if (istAufsperren) {
            Deque<Long> u = aufsperren.computeIfAbsent(kopplungId, k -> new ArrayDeque<>());
            kuerzen(u, jetztMs - 3_600_000L);
            if (u.size() >= AUFSPERREN_JE_STUNDE) return false;
            int in10 = 0;
            for (long t : u) if (t > jetztMs - 600_000L) in10++;
            if (in10 >= AUFSPERREN_JE_10_MIN) return false;
            u.addLast(jetztMs);
        }
        a.addLast(jetztMs);
        return true;
    }

    private static void kuerzen(Deque<Long> d, long vor) {
        while (!d.isEmpty() && d.peekFirst() <= vor) d.pollFirst();
    }
}
