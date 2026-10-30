package at.greenchili.surfmate.ha;

import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Verteiler für Push-Ereignisse an Home Assistant (SSE {@code /api/ha/v1/ereignisse}, HA_VERTRAG.md §4) und später
 * an den Webhook (Weg e). Nur Art + Zeit bzw. Zonenname – nie Koordinaten, nie Bilder.
 *
 * <p>Je Kopplung höchstens {@link #MAX_JE_KOPPLUNG} Streams, insgesamt {@link #MAX_GESAMT} (HTTP-Pool hat 32 Threads).
 */
public final class HaEreignisse {

    private HaEreignisse() {}

    public static final int MAX_JE_KOPPLUNG = 2;
    public static final int MAX_GESAMT = 6;
    static final int PUFFER = 100;

    /** Ein offener Stream. */
    public static final class Abo {
        public final String kid;   // null = Login
        final LinkedBlockingQueue<String[]> q = new LinkedBlockingQueue<>(PUFFER);
        volatile boolean zu;

        Abo(String kid) { this.kid = kid; }

        /** Nächstes Ereignis {event, data} oder null nach {@code ms}. */
        public String[] naechstes(long ms) throws InterruptedException {
            return q.poll(ms, TimeUnit.MILLISECONDS);
        }

        public boolean geschlossen() { return zu; }
    }

    private static final CopyOnWriteArrayList<Abo> ABOS = new CopyOnWriteArrayList<>();
    /** Zusätzliche Empfänger (Webhook, MQTT) – bekommen jedes Ereignis. */
    private static final Map<String, Empfaenger> EMPFAENGER = new ConcurrentHashMap<>();

    public interface Empfaenger {
        void ereignis(String event, JSONObject data);
    }

    /** null, wenn die Grenze erreicht ist (Handler antwortet 429). */
    public static synchronized Abo abonnieren(String kid) {
        int je = 0;
        for (Abo a : ABOS) if (kid == null ? a.kid == null : kid.equals(a.kid)) je++;
        if (je >= MAX_JE_KOPPLUNG || ABOS.size() >= MAX_GESAMT) return null;
        Abo a = new Abo(kid);
        ABOS.add(a);
        return a;
    }

    public static void abmelden(Abo a) {
        if (a == null) return;
        a.zu = true;
        ABOS.remove(a);
    }

    /** Widerruf: alle Streams dieser Kopplung sofort schließen (§4). */
    public static void schliessen(String kid) {
        if (kid == null) return;
        for (Abo a : ABOS) {
            if (kid.equals(a.kid)) {
                a.zu = true;
                a.q.offer(new String[]{null, null});
                ABOS.remove(a);
            }
        }
    }

    public static void empfaenger(String name, Empfaenger e) {
        if (e == null) EMPFAENGER.remove(name);
        else EMPFAENGER.put(name, e);
    }

    /** Ereignis an alle Streams und Empfänger. Volle Puffer verlieren das älteste Ereignis. */
    public static void melde(String event, JSONObject data) {
        String d = data == null ? "{}" : data.toString();
        for (Abo a : ABOS) {
            if (!a.q.offer(new String[]{event, d})) {
                a.q.poll();
                a.q.offer(new String[]{event, d});
            }
        }
        for (Empfaenger e : EMPFAENGER.values()) {
            try {
                e.ereignis(event, data);
            } catch (Throwable ignored) { }
        }
    }

    public static int anzahl() {
        return ABOS.size();
    }

    /** {@code event: ereignis} mit Art + Zeit (bewegung|person|erschuetterung). */
    public static void waechterEreignis(String art, long zeit) {
        waechterEreignis(art, zeit, null);
    }

    /**
     * 0.1.30 Kern Sicherheit: mit {@code ereignisId} (32 hex), damit ein Client mit Recht „standbild“ genau ein Bild
     * holen kann ({@code GET /api/waechter/standbild/<ereignisId>}). Kein Bild, kein Dateiname, kein Ort.
     */
    public static void waechterEreignis(String art, long zeit, String ereignisId) {
        try {
            JSONObject d = new JSONObject().put("art", art).put("zeit", zeit);
            if (ereignisId != null) d.put("ereignisId", ereignisId);
            melde("ereignis", d);
        } catch (Exception ignored) { }
    }

    /** Wächter-Meldung (NotificationBus-Kategorie + Daten) → Art, oder null, wenn kein Wächter-Ereignis. */
    public static String artAus(String kategorie, JSONObject data) {
        if (kategorie == null) return null;
        if (kategorie.equals("surveillance.motion") || kategorie.equals("surveillance.proximity")) {
            if (data != null && data.optInt("personCount", 0) > 0) return "person";
            return "bewegung";
        }
        return null;
    }
}
