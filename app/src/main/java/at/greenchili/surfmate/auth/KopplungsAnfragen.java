package at.greenchili.surfmate.auth;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Kopplung per Anfrage + 1 Tipp (Vertrag KOPPLUNG_ANFRAGE.md, 21.10.2026).
 *
 * <p>Das Cockpit stellt ohne Login eine Anfrage {geraet, pruefzahl}; der Besitzer erlaubt sie per
 * Telegram, Panoptes-Web (angemeldet) oder am Auto-Display. Erst danach holt das Cockpit mit der
 * geheimen Anfrage-id <b>einmalig</b> seinen Kopplungs-JWT ab; erst dabei entsteht der Eintrag in
 * {@link Kopplungen} (widerrufbar wie beim Code-Weg).
 *
 * <ul>
 *   <li>Anfrage läuft nach {@link #GUELTIG_MS} ab; erlaubte Anfragen müssen binnen
 *       {@link #ABHOLEN_MS} nach dem Erlauben abgeholt werden.</li>
 *   <li>Höchstens {@link #MAX_OFFEN} offene Anfragen, je Absender {@link #MAX_JE_ABSENDER} in
 *       {@link #FENSTER_MS}.</li>
 *   <li>Entscheidung wirkt einmal: weitere Klicks → {@link Entscheid#SCHON_ENTSCHIEDEN}.</li>
 *   <li>Nur im Speicher: ein Neustart verwirft alles Offene.</li>
 * </ul>
 * Rein bis auf {@link #get()}; die Uhr kommt von außen.
 */
public final class KopplungsAnfragen {

    public static final long GUELTIG_MS = 2 * 60_000L;
    public static final long ABHOLEN_MS = 2 * 60_000L;
    public static final int MAX_OFFEN = 3;
    public static final int MAX_JE_ABSENDER = 5;
    public static final long FENSTER_MS = 10 * 60_000L;
    private static final SecureRandom RANDOM = new SecureRandom();

    public enum Status { WARTET, ERLAUBT, ABGEHOLT, ABGELEHNT, ABGELAUFEN }

    public enum Entscheid { OK, UNBEKANNT, SCHON_ENTSCHIEDEN, ABGELAUFEN }

    /** Erzeugt beim Abholen den Kopplungs-Eintrag + JWT (im Daemon: Kopplungen + AuthManager). */
    public interface Ausgeber {
        /** @return {kid, jwt} oder null, wenn die Kopplungsliste voll ist / Speichern scheitert. */
        String[] ausstellen(String geraet, String art, long nowMs);

        /** 0.1.19: Recht „aufsperren“ gleich beim Erlauben (nur Handy, nur mit Login gewählt). */
        default void aufsperrenErlauben(String kid) { }

        /**
         * 0.1.25: Zusatzrechte gleich beim Erlauben (nur Handy, nur mit Login gewählt): „aufsperren“ und/oder
         * „wallbox“ zu den Standardrechten. Ohne Überschreiben wie bisher nur {@link #aufsperrenErlauben}.
         */
        default void zusatzRechte(String kid, boolean aufsperren, boolean wallbox) {
            if (aufsperren) aufsperrenErlauben(kid);
        }

        /** 0.1.26.1: dazu {@code update} = Recht „update“ (am Display bzw. mit Login erlaubt). */
        default void zusatzRechte(String kid, boolean aufsperren, boolean wallbox, boolean update) {
            zusatzRechte(kid, aufsperren, wallbox);
        }
    }

    static final class Anfrage {
        /** id = Abhol-Geheimnis (nur Cockpit); ref = Griff für Erlauben/Ablehnen (Telegram, Web, Display). */
        final String id, ref, geraet, pruefzahl;
        String art;
        /** 0.1.19: beim Erlauben zusätzlich „aufsperren“ gewählt (nur Handy). */
        boolean aufsperren;
        /** 0.1.25: beim Erlauben zusätzlich „wallbox“ gewählt (nur Handy). */
        boolean wallbox;
        /** 0.1.26.1: beim Erlauben zusätzlich „update“ (am Display bzw. mit Login; Telegram nicht). */
        boolean update;
        final long angelegt;
        Status status = Status.WARTET;
        long entschieden;

        Anfrage(String id, String ref, String geraet, String pruefzahl, long angelegt) {
            this.id = id; this.ref = ref; this.geraet = geraet; this.pruefzahl = pruefzahl; this.angelegt = angelegt;
        }
    }

    /** Ergebnis von {@link #anlegen}. */
    public static final class Neu {
        public final String id;
        public final String grund;   // null = ok, sonst pruefzahl | zu_viele_offen | ratenlimit
        public final long gueltigBis;
        Neu(String id, String grund, long gueltigBis) { this.id = id; this.grund = grund; this.gueltigBis = gueltigBis; }
    }

    /** Ergebnis von {@link #abholen}. */
    public static final class Abholung {
        public final Status status;   // null = unbekannt
        public String jwt, kid;
        public long gueltigBis;
        Abholung(Status s) { status = s; }
    }

    /** Hört auf neue Anfragen und Entscheidungen (Telegram, Overlay). */
    public interface Beobachter {
        void neu(String ref, String geraet, String pruefzahl);
        /** Mit Geräte-Art (null | cockpit | homeassistant) – Texte je Art. */
        default void neu(String ref, String geraet, String pruefzahl, String art) { neu(ref, geraet, pruefzahl); }
        void entschieden(String ref, boolean erlaubt);
    }

    private final Map<String, Anfrage> anfragen = new LinkedHashMap<>();
    private final Map<String, ArrayDeque<Long>> jeAbsender = new HashMap<>();
    private final Ausgeber ausgeber;
    private volatile Beobachter beobachter;

    public KopplungsAnfragen(Ausgeber ausgeber) {
        this.ausgeber = ausgeber;
    }

    public void setBeobachter(Beobachter b) {
        beobachter = b;
    }

    /** POST /auth/kopplung/anfrage. */
    public Neu anlegen(String geraet, String pruefzahl, String absender, long now) {
        return anlegen(geraet, pruefzahl, null, absender, now);
    }

    /** Mit optionaler Geräte-Art ({@code cockpit} | {@code homeassistant}). */
    public Neu anlegen(String geraet, String pruefzahl, String art, String absender, long now) {
        Neu n;
        Anfrage a;
        synchronized (this) {
            aufraeumen(now);
            if (pruefzahl == null || !pruefzahl.matches("^[0-9]{4}$")) return new Neu(null, "pruefzahl", 0);
            String wer = absender == null || absender.isEmpty() ? "?" : absender;
            ArrayDeque<Long> q = jeAbsender.computeIfAbsent(wer, k -> new ArrayDeque<>());
            while (!q.isEmpty() && now - q.peekFirst() >= FENSTER_MS) q.pollFirst();
            if (q.size() >= MAX_JE_ABSENDER) return new Neu(null, "ratenlimit", 0);
            if (offen() >= MAX_OFFEN) return new Neu(null, "zu_viele_offen", 0);
            q.addLast(now);
            a = new Anfrage(neueId(16), neueId(8), Kopplungen.bereinigeName(geraet), pruefzahl, now);
            a.art = art != null && Kopplungen.ARTEN.contains(art) ? art : null;
            anfragen.put(a.id, a);
            n = new Neu(a.id, null, now + GUELTIG_MS);
        }
        Beobachter b = beobachter;
        if (b != null) {
            try { b.neu(a.ref, a.geraet, a.pruefzahl, a.art); } catch (Throwable ignored) { }
        }
        return n;
    }

    /** Erlauben/Ablehnen über den Griff {@code ref} (nur Login, Telegram-Besitzer, Auto-Display). */
    public Entscheid entscheiden(String ref, boolean erlauben, long now) {
        return entscheiden(ref, erlauben, false, now);
    }

    /**
     * 0.1.19 (Handy-Wunsch): {@code aufsperren} = beim Erlauben gleich das Recht „aufsperren“ geben. Wirkt nur bei
     * {@code erlauben} und einer Handy-Anfrage; sonst wie bisher (Handy-Standard ohne Aufsperren).
     */
    public Entscheid entscheiden(String ref, boolean erlauben, boolean aufsperren, long now) {
        return entscheiden(ref, erlauben, aufsperren, false, now);
    }

    /** 0.1.25: zusätzlich {@code wallbox} = Recht „wallbox“ gleich beim Erlauben (nur Handy). */
    public Entscheid entscheiden(String ref, boolean erlauben, boolean aufsperren, boolean wallbox, long now) {
        return entscheiden(ref, erlauben, aufsperren, wallbox, false, now);
    }

    /** 0.1.26.1: zusätzlich {@code update} = Recht „update“ gleich beim Erlauben (nur Handy). */
    public Entscheid entscheiden(String ref, boolean erlauben, boolean aufsperren, boolean wallbox, boolean update,
                                 long now) {
        synchronized (this) {
            aufraeumen(now);
            Anfrage a = null;
            for (Anfrage x : anfragen.values()) if (ref != null && ref.equals(x.ref)) { a = x; break; }
            if (a == null) return Entscheid.UNBEKANNT;
            if (a.status == Status.ABGELAUFEN) return Entscheid.ABGELAUFEN;
            if (a.status != Status.WARTET) return Entscheid.SCHON_ENTSCHIEDEN;
            a.status = erlauben ? Status.ERLAUBT : Status.ABGELEHNT;
            a.aufsperren = erlauben && aufsperren && Kopplungen.ART_HANDY.equals(a.art);
            a.wallbox = erlauben && wallbox && Kopplungen.ART_HANDY.equals(a.art);
            a.update = erlauben && update && Kopplungen.ART_HANDY.equals(a.art);
            a.entschieden = now;
        }
        Beobachter b = beobachter;
        if (b != null) {
            try { b.entschieden(ref, erlauben); } catch (Throwable ignored) { }
        }
        return Entscheid.OK;
    }

    /** GET /auth/kopplung/anfrage/{id}: JWT nur beim ersten Abruf nach dem Erlauben. */
    public synchronized Abholung abholen(String id, long now) {
        aufraeumen(now);
        Anfrage a = id == null ? null : anfragen.get(id);
        if (a == null) return new Abholung(null);
        Abholung r = new Abholung(a.status);
        r.gueltigBis = a.angelegt + GUELTIG_MS;
        if (a.status == Status.ERLAUBT) {
            String[] k = ausgeber.ausstellen(a.geraet, a.art, now);
            if (k == null) {
                // Kopplungsliste voll / nicht gespeichert: nicht erneut versuchen lassen
                a.status = Status.ABGELEHNT;
                return new Abholung(Status.ABGELEHNT);
            }
            a.status = Status.ABGEHOLT;
            if (a.aufsperren || a.wallbox || a.update) {
                try { ausgeber.zusatzRechte(k[0], a.aufsperren, a.wallbox, a.update); } catch (Throwable ignored) { }
            }
            r.kid = k[0];
            r.jwt = k[1];
        }
        return r;
    }

    /** GET /api/kopplung/anfragen: nur offene. */
    public synchronized JSONArray offeneListe(long now) {
        aufraeumen(now);
        JSONArray out = new JSONArray();
        for (Anfrage a : anfragen.values()) {
            if (a.status != Status.WARTET) continue;
            JSONObject o = new JSONObject();
            try {
                o.put("ref", a.ref);   // nie die Abhol-id
                o.put("geraet", a.geraet);
                o.put("pruefzahl", a.pruefzahl);
                if (a.art != null) o.put("art", a.art);
                o.put("angelegt", a.angelegt);
                o.put("gueltigBis", a.angelegt + GUELTIG_MS);
            } catch (Exception ignored) { }
            out.put(o);
        }
        return out;
    }

    private int offen() {
        int n = 0;
        for (Anfrage a : anfragen.values()) if (a.status == Status.WARTET) n++;
        return n;
    }

    /** Ablauf setzen; Endzustände nach 10 min vergessen (dann „unbekannt“). */
    private void aufraeumen(long now) {
        Iterator<Anfrage> it = anfragen.values().iterator();
        while (it.hasNext()) {
            Anfrage a = it.next();
            if (a.status == Status.WARTET && now - a.angelegt >= GUELTIG_MS) {
                a.status = Status.ABGELAUFEN;
                a.entschieden = now;
            } else if (a.status == Status.ERLAUBT && now - a.entschieden >= ABHOLEN_MS) {
                a.status = Status.ABGELAUFEN;
                a.entschieden = now;
            }
            if (a.status != Status.WARTET && a.status != Status.ERLAUBT && now - a.entschieden >= FENSTER_MS) {
                it.remove();
            }
        }
    }

    static String neueId(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    // ---------------------------------------------------------------- Laufzeit (Daemon)

    private static volatile KopplungsAnfragen instanz;

    public static KopplungsAnfragen get() {
        KopplungsAnfragen k = instanz;
        if (k == null) {
            synchronized (KopplungsAnfragen.class) {
                if (instanz == null) {
                    instanz = new KopplungsAnfragen(new Ausgeber() {
                        public String[] ausstellen(String geraet, String art, long now) {
                            String kid = Kopplungen.get().anlegen(geraet, art, now);
                            if (kid == null) return null;
                            String jwt = AuthManager.generateJwt(PairingCodes.SCOPE, kid);
                            return jwt == null ? null : new String[] {kid, jwt};
                        }
                        public void aufsperrenErlauben(String kid) {
                            zusatzRechte(kid, true, false);
                        }
                        public void zusatzRechte(String kid, boolean aufsperren, boolean wallbox) {
                            zusatzRechte(kid, aufsperren, wallbox, false);
                        }
                        public void zusatzRechte(String kid, boolean aufsperren, boolean wallbox, boolean update) {
                            // vom eben angelegten Stand aus (erste Handy-Kopplung hat „update“ schon)
                            java.util.List<String> r = new java.util.ArrayList<>(Kopplungen.get().rechte(kid));
                            if (aufsperren && !r.contains(Kopplungen.RECHT_AUFSPERREN)) r.add(Kopplungen.RECHT_AUFSPERREN);
                            if (wallbox && !r.contains(Kopplungen.RECHT_WALLBOX)) r.add(Kopplungen.RECHT_WALLBOX);
                            if (update && !r.contains(Kopplungen.RECHT_UPDATE)) r.add(Kopplungen.RECHT_UPDATE);
                            Kopplungen.get().rechteSetzen(kid, r);
                        }
                    });
                    instanz.setBeobachter(new KopplungsMelder());
                }
                k = instanz;
            }
        }
        return k;
    }
}
