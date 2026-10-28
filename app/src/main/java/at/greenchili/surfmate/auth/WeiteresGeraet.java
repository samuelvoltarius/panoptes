package at.greenchili.surfmate.auth;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 0.1.25 „weiteres Gerät koppeln“ (KOPPLUNG_WEITERES_GERAET_VERTRAG.md): Das EV-Cockpit (Kopplung art=cockpit) holt beim
 * Auto einen QR v2 für eine NEUE Handy-Kopplung. Ablauf wie die BYD-Cloud-Übergabe:
 * <ol>
 *   <li>Cockpit: Anfrage mit Gerätename und seiner Prüfzahl → {@code r-<16 hex>}.</li>
 *   <li>Besitzer am Display oder in Telegram: erlauben/ablehnen – <b>nie automatisch</b>.</li>
 *   <li>Cockpit: Status; bei „erlaubt“ genau EINMAL der QR (Einmal-Code + Brücken-Block), danach „abgeholt“.</li>
 * </ol>
 * Der QR entsteht erst beim Abholen (der Code läuft ab da). Die neue Kopplung bekommt beim Einlösen die Standardrechte
 * (kein Aufsperren). Ohne eingeschaltete Brücke samt Zustimmung: 409 {@code bruecke_aus}.
 */
public final class WeiteresGeraet {

    public static final long GUELTIG_MS = 5 * 60_000L;
    public static final String REF_MUSTER = "r-[0-9a-f]{16}";
    static final int MAX_STUNDE_KOPPLUNG = 3;
    static final int MAX_TAG_AUTO = 6;

    /** Brücke bereit (an + Zustimmung) und QR erzeugen. */
    public interface QrQuelle {
        boolean brueckeBereit();

        /** Neuer Einmal-Code + Einmal-Zugang → QR-Text; null, wenn die Brücke inzwischen aus ist. */
        String qr(long jetzt) throws Exception;
    }

    /** Rückfrage (Display, Telegram) und Protokoll. */
    public interface Melder {
        void anfrage(String ref, String geraet, String pruefzahl);

        void entschieden(String ref, boolean erlaubt);

        void protokoll(String zeile);
    }

    public static final class Antwort {
        public final int status;
        public final JSONObject json;

        public Antwort(int status, JSONObject json) {
            this.status = status;
            this.json = json;
        }

        static Antwort fehler(int status, String grund) {
            try {
                return new Antwort(status, new JSONObject().put("ok", false).put("grund", grund));
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    static final class Offen {
        final String ref, kid, geraet, pruefzahl;
        final long ablauf;
        String status = "wartet";   // wartet | erlaubt | abgelehnt | abgelaufen | abgeholt

        Offen(String ref, String kid, String geraet, String pruefzahl, long ablauf) {
            this.ref = ref;
            this.kid = kid;
            this.geraet = geraet;
            this.pruefzahl = pruefzahl;
            this.ablauf = ablauf;
        }
    }

    private final LongSupplier uhr;
    private final SecureRandom zufall;
    private final QrQuelle quelle;
    private final Melder melder;
    private final Map<String, Offen> alle = new HashMap<>();
    private final Map<String, Deque<Long>> jeKopplung = new HashMap<>();
    private final Deque<Long> jeAuto = new ArrayDeque<>();

    public WeiteresGeraet(LongSupplier uhr, SecureRandom zufall, QrQuelle quelle, Melder melder) {
        this.uhr = uhr;
        this.zufall = zufall;
        this.quelle = quelle;
        this.melder = melder;
    }

    /** Schritt 1 (Cockpit, Kopplungs-JWT art=cockpit). */
    public synchronized Antwort anfrage(String kid, String kopplungsArt, JSONObject body) throws JSONException {
        if (kid == null || kid.isEmpty()) return Antwort.fehler(401, "kopplung");
        if (!"cockpit".equals(kopplungsArt)) return Antwort.fehler(403, "nur_cockpit");
        if (!"handy".equals(body.optString("art", ""))) return Antwort.fehler(400, "art");
        String pruefzahl = body.optString("pruefzahl", "");
        if (!pruefzahl.matches("[0-9]{4}")) return Antwort.fehler(400, "pruefzahl");
        if (!quelle.brueckeBereit()) return Antwort.fehler(409, "bruecke_aus");
        long jetzt = uhr.getAsLong();
        aufraeumen(jetzt);
        for (Offen o : alle.values()) {
            if ("wartet".equals(o.status) || "erlaubt".equals(o.status)) return Antwort.fehler(409, "laeuft_schon");
        }
        Deque<Long> k = jeKopplung.computeIfAbsent(kid, x -> new ArrayDeque<>());
        while (!k.isEmpty() && jetzt - k.peekFirst() >= 3_600_000L) k.pollFirst();
        while (!jeAuto.isEmpty() && jetzt - jeAuto.peekFirst() >= 86_400_000L) jeAuto.pollFirst();
        if (k.size() >= MAX_STUNDE_KOPPLUNG || jeAuto.size() >= MAX_TAG_AUTO) return Antwort.fehler(429, "ratenlimit");
        k.addLast(jetzt);
        jeAuto.addLast(jetzt);
        String name = body.optString("geraet", "").replaceAll("[\\p{Cntrl}]", " ").trim();
        if (name.length() > 40) name = name.substring(0, 40);
        if (name.isEmpty()) name = "EV-Cockpit";
        byte[] z = new byte[8];
        zufall.nextBytes(z);
        StringBuilder hex = new StringBuilder("r-");
        for (byte x : z) hex.append(String.format(java.util.Locale.ROOT, "%02x", x));
        Offen o = new Offen(hex.toString(), kid, name, pruefzahl, jetzt + GUELTIG_MS);
        alle.put(o.ref, o);
        melder.protokoll("weiteres-geraet " + o.ref + " anfrage kopplung=" + kid);
        melder.anfrage(o.ref, name, pruefzahl);
        return new Antwort(200, new JSONObject().put("ok", true).put("id", o.ref).put("gueltigBis", o.ablauf));
    }

    /** Offene Rückfragen für Display/Web (Name, Prüfzahl, Ablauf). */
    public synchronized JSONArray offene() throws JSONException {
        aufraeumen(uhr.getAsLong());
        JSONArray a = new JSONArray();
        for (Offen o : alle.values()) {
            if ("wartet".equals(o.status)) {
                a.put(new JSONObject().put("ref", o.ref).put("geraet", o.geraet).put("pruefzahl", o.pruefzahl)
                        .put("gueltigBis", o.ablauf));
            }
        }
        return a;
    }

    /** Schritt 2 (Besitzer, nie Kopplungs-JWT). */
    public synchronized Antwort entscheide(String ref, boolean erlauben) throws JSONException {
        aufraeumen(uhr.getAsLong());
        Offen o = ref == null ? null : alle.get(ref);
        if (o == null) return Antwort.fehler(404, "unbekannt");
        if ("abgelaufen".equals(o.status)) return Antwort.fehler(410, "abgelaufen");
        if (!"wartet".equals(o.status)) return Antwort.fehler(409, "schon_entschieden");
        o.status = erlauben ? "erlaubt" : "abgelehnt";
        melder.protokoll("weiteres-geraet " + ref + " " + o.status);
        melder.entschieden(ref, erlauben);
        return new Antwort(200, new JSONObject().put("ok", true).put("status", o.status));
    }

    /** Schritt 3 (Cockpit): Status; bei „erlaubt“ genau einmal der QR. */
    public synchronized Antwort status(String ref, String kid) throws JSONException {
        long jetzt = uhr.getAsLong();
        aufraeumen(jetzt);
        Offen o = ref == null ? null : alle.get(ref);
        if (o == null || !o.kid.equals(kid)) return Antwort.fehler(404, "unbekannt");
        JSONObject r = new JSONObject().put("ok", true).put("status", o.status);
        if ("erlaubt".equals(o.status)) {
            String qr;
            try {
                qr = quelle.qr(jetzt);
            } catch (Exception e) {
                qr = null;
            }
            if (qr == null) return Antwort.fehler(409, "bruecke_aus");
            if (qr.length() > KopplungsQr.MAX_LAENGE) return Antwort.fehler(500, "qr_zu_lang");
            o.status = "abgeholt";
            melder.protokoll("weiteres-geraet " + ref + " abgeholt");
            r.put("status", "erlaubt").put("qr", qr);
        }
        return new Antwort(200, r);
    }

    private void aufraeumen(long jetzt) {
        for (Iterator<Offen> it = alle.values().iterator(); it.hasNext(); ) {
            Offen o = it.next();
            if (("wartet".equals(o.status) || "erlaubt".equals(o.status)) && jetzt > o.ablauf) o.status = "abgelaufen";
            if (jetzt > o.ablauf + 30 * 60_000L) it.remove();
        }
    }
}
