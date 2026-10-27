package at.greenchili.surfmate.auth;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 0.1.21 Web-Anmeldung per Telegram statt Zugangscode (Koordination 27.10., PWA_KONZEPT §2.5).
 *
 * <p>Die Login-Seite stellt ohne Login eine Anfrage; Panoptes schickt dem Telegram-Besitzer eine Nachricht mit
 * Prüfzahl und [Erlauben] [Ablehnen]. Erst nach dem Erlauben holt die Login-Seite mit ihrem geheimen Abhol-Schlüssel
 * <b>einmalig</b> eine Web-Sitzung ab. Diese Sitzung hat <b>keine Sicherheitsrechte</b> (wie Stufe B der Handy-App:
 * kein Aufsperren, keine PIN/Kopplungen/Netz/Updates – WebHandyGuard) und hängt an einer eigenen, jederzeit
 * widerrufbaren Kopplung der Art {@code web}.
 *
 * <ul>
 *   <li>Anfrage gilt {@link #GUELTIG_MS}; erlaubt muss sie binnen {@link #ABHOLEN_MS} abgeholt werden.</li>
 *   <li>Höchstens {@link #MAX_OFFEN} offen; je Absender {@link #MAX_JE_ABSENDER} in {@link #FENSTER_MS};
 *       je Tag {@link #MAX_TAG} insgesamt.</li>
 *   <li>Entscheidung wirkt einmal. Nur im Speicher.</li>
 * </ul>
 */
public final class TelegramAnmeldung {

    public static final long GUELTIG_MS = 2 * 60_000L;
    public static final long ABHOLEN_MS = 2 * 60_000L;
    public static final int MAX_OFFEN = 2;
    public static final int MAX_JE_ABSENDER = 3;
    public static final long FENSTER_MS = 10 * 60_000L;
    public static final int MAX_TAG = 10;
    private static final SecureRandom ZUFALL = new SecureRandom();

    public enum Status { WARTET, ERLAUBT, ABGEHOLT, ABGELEHNT, ABGELAUFEN }

    public enum Entscheid { OK, UNBEKANNT, SCHON_ENTSCHIEDEN, ABGELAUFEN }

    /** Erzeugt beim Abholen die Kopplung (Art web, nur Recht „web“) + Web-JWT. */
    public interface Ausgeber {
        /** @return {kid, jwt} oder null. */
        String[] ausstellen(long nowMs);
    }

    /** Meldet neue Anfragen (Telegram) und schreibt das Protokoll (nur ref/Grund, nie Schlüssel). */
    public interface Melder {
        void neu(String ref, String pruefzahl, String absender);

        void protokoll(String zeile);
    }

    static final class Anfrage {
        final String abholId, ref, pruefzahl;
        final long angelegt;
        Status status = Status.WARTET;
        long entschieden;

        Anfrage(String abholId, String ref, String pruefzahl, long angelegt) {
            this.abholId = abholId;
            this.ref = ref;
            this.pruefzahl = pruefzahl;
            this.angelegt = angelegt;
        }
    }

    /** Ergebnis von {@link #anfrage}. */
    public static final class Neu {
        public final String abholId, pruefzahl, grund;
        public final long gueltigBis;

        Neu(String abholId, String pruefzahl, String grund, long gueltigBis) {
            this.abholId = abholId;
            this.pruefzahl = pruefzahl;
            this.grund = grund;
            this.gueltigBis = gueltigBis;
        }
    }

    /** Ergebnis von {@link #abholen}. */
    public static final class Abholung {
        public final Status status;   // null = unbekannt
        public String jwt;

        Abholung(Status s) {
            status = s;
        }
    }

    private final Map<String, Anfrage> anfragen = new LinkedHashMap<>();
    private final Map<String, ArrayDeque<Long>> jeAbsender = new HashMap<>();
    private final ArrayDeque<Long> jeTag = new ArrayDeque<>();
    private final Ausgeber ausgeber;
    private final Melder melder;

    public TelegramAnmeldung(Ausgeber ausgeber, Melder melder) {
        this.ausgeber = ausgeber;
        this.melder = melder;
    }

    /** POST /auth/telegram/anfrage (ohne Login). */
    public Neu anfrage(String absender, long now) {
        Anfrage a;
        synchronized (this) {
            aufraeumen(now);
            String wer = absender == null || absender.isEmpty() ? "?" : absender;
            ArrayDeque<Long> q = jeAbsender.computeIfAbsent(wer, k -> new ArrayDeque<>());
            while (!q.isEmpty() && now - q.peekFirst() >= FENSTER_MS) q.pollFirst();
            while (!jeTag.isEmpty() && now - jeTag.peekFirst() >= 86_400_000L) jeTag.pollFirst();
            if (q.size() >= MAX_JE_ABSENDER || jeTag.size() >= MAX_TAG) return new Neu(null, null, "ratenlimit", 0);
            if (offen() >= MAX_OFFEN) return new Neu(null, null, "zu_viele_offen", 0);
            q.addLast(now);
            jeTag.addLast(now);
            a = new Anfrage(id(32), hex(8), String.format(java.util.Locale.ROOT, "%04d", ZUFALL.nextInt(10_000)), now);
            anfragen.put(a.abholId, a);
        }
        melder.protokoll("telegram-anmeldung " + a.ref + " anfrage");
        melder.neu(a.ref, a.pruefzahl, absender);
        return new Neu(a.abholId, a.pruefzahl, null, now + GUELTIG_MS);
    }

    /** Erlauben/Ablehnen über {@code ref} (Telegram-Besitzer oder Panoptes-Web mit vollem Login). */
    public Entscheid entscheiden(String ref, boolean erlauben, long now) {
        synchronized (this) {
            aufraeumen(now);
            Anfrage a = null;
            for (Anfrage x : anfragen.values()) if (ref != null && ref.equals(x.ref)) a = x;
            if (a == null) return Entscheid.UNBEKANNT;
            if (a.status == Status.ABGELAUFEN) return Entscheid.ABGELAUFEN;
            if (a.status != Status.WARTET) return Entscheid.SCHON_ENTSCHIEDEN;
            a.status = erlauben ? Status.ERLAUBT : Status.ABGELEHNT;
            a.entschieden = now;
        }
        melder.protokoll("telegram-anmeldung " + ref + (erlauben ? " erlaubt" : " abgelehnt"));
        return Entscheid.OK;
    }

    /** GET /auth/telegram/abholen (ohne Login, mit Abhol-Schlüssel): Status; bei ERLAUBT einmalig die Sitzung. */
    public Abholung abholen(String abholId, long now) {
        Anfrage a;
        synchronized (this) {
            aufraeumen(now);
            a = abholId == null ? null : anfragen.get(abholId);
            if (a == null) return new Abholung(null);
            if (a.status != Status.ERLAUBT) return new Abholung(a.status);
            a.status = Status.ABGEHOLT;   // einmal – auch wenn das Ausstellen scheitert
        }
        String[] r = ausgeber.ausstellen(now);
        if (r == null) {
            melder.protokoll("telegram-anmeldung " + a.ref + " ausstellen_fehlgeschlagen");
            return new Abholung(Status.ABGELAUFEN);
        }
        melder.protokoll("telegram-anmeldung " + a.ref + " abgeholt kopplung=" + r[0]);
        Abholung ab = new Abholung(Status.ERLAUBT);
        ab.jwt = r[1];
        return ab;
    }

    private int offen() {
        int n = 0;
        for (Anfrage a : anfragen.values()) if (a.status == Status.WARTET || a.status == Status.ERLAUBT) n++;
        return n;
    }

    private void aufraeumen(long now) {
        for (Iterator<Anfrage> it = anfragen.values().iterator(); it.hasNext(); ) {
            Anfrage a = it.next();
            if (a.status == Status.WARTET && now - a.angelegt > GUELTIG_MS) a.status = Status.ABGELAUFEN;
            if (a.status == Status.ERLAUBT && now - a.entschieden > ABHOLEN_MS) a.status = Status.ABGELAUFEN;
            if (now - a.angelegt > GUELTIG_MS + ABHOLEN_MS + 60_000L) it.remove();
        }
    }

    private static String id(int bytes) {
        byte[] b = new byte[bytes];
        ZUFALL.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String hex(int bytes) {
        byte[] b = new byte[bytes];
        ZUFALL.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    // ------------------------------------------------------------------ Laufzeit

    private static volatile TelegramAnmeldung instanz;

    public static TelegramAnmeldung get() {
        return instanz;
    }

    public static synchronized void setzen(TelegramAnmeldung t) {
        instanz = t;
    }
}
