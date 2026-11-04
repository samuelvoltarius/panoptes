package at.greenchili.surfmate.auth;

/**
 * 0.1.39 Panoptes am PC über die Brücke (ENTWURF_BRUECKE_PC, PLAN_BRUECKE_PC_0139): Kopplungen der Art {@code pc}.
 * <ul>
 *   <li>Schalter {@link #SCHALTER} (Katalog, Standard AUS, nur am Display/mit Login): aus = keine PC-Kopplung gilt,
 *       Ausschalten widerruft alle ({@code EinstellungenApiHandler.nachwirkungen}).</li>
 *   <li>Angelegt nur per Code vom Besitzer-Handy ({@code /api/handy/v1/kopplung/code {art:"pc"}}), höchstens
 *       {@link #MAX_PC} je Auto, Dauer 30 Tage gleitend oder „Nur heute“ ({@link #NUR_HEUTE_MS} fest).</li>
 *   <li>Über die Brücke höchstens {@link #MAX_JE_SEKUNDE} Anfragen je Sekunde und PC, Tagesgrenze
 *       {@link #TAGES_MB} (Schalter {@code bruecke.pcTagesMB}), Takt-Hinweis {@link #TAKT_S} s an die PC-Hülle.</li>
 * </ul>
 * Was eine PC-Kopplung darf, steht in {@link Kopplungen#PC_RECHTE} (nie besitzer, update, aufsperren, signal) und im
 * {@code ExternGuard}; die Web-Sitzung dahinter läuft durch {@code WebHandyGuard} wie die der Handy-App.
 * Zähler nur im Arbeitsspeicher.
 */
public final class PcZugang {

    private PcZugang() {}

    public static final String SCHALTER = "bruecke.pcAnmeldung";
    public static final String TAGES_MB_SCHLUESSEL = "bruecke.pcTagesMB";
    public static final int MAX_PC = 5;
    public static final long NUR_HEUTE_MS = 12 * 3600_000L;
    public static final String DAUER_HEUTE = "heute", DAUER_30_TAGE = "30tage";
    public static final int MAX_JE_SEKUNDE = 20;
    public static final int TAGES_MB = 100, TAGES_MB_MIN = 10, TAGES_MB_MAX = 1000;
    /** Abfragetakt über die Brücke (Messung 04.11.: Startseite fragt alle 5–6 s, über die Brücke reicht 10 s). */
    public static final int TAKT_S = 10;
    static final long CACHE_MS = 5_000L;

    /** Liest {schalter, tagesMB} aus der Konfiguration (im Test ersetzbar). */
    public interface Quelle {
        boolean an();
        int tagesMb();
    }

    /** Die Konfiguration des Autos (Abschnitt „bruecke“). */
    public static final Quelle KONFIG = new Quelle() {
        public boolean an() {
            org.json.JSONObject b = abschnitt();
            return b != null && b.optBoolean("pcAnmeldung", false);
        }
        public int tagesMb() {
            org.json.JSONObject b = abschnitt();
            int mb = b == null ? TAGES_MB : b.optInt("pcTagesMB", TAGES_MB);
            return Math.max(TAGES_MB_MIN, Math.min(TAGES_MB_MAX, mb));
        }
        private org.json.JSONObject abschnitt() {
            try {
                org.json.JSONObject all = at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig();
                return all == null ? null : all.optJSONObject("bruecke");
            } catch (Throwable t) {
                return null;
            }
        }
    };

    public static volatile Quelle quelle = KONFIG;

    private static boolean anGemerkt;
    private static int mbGemerkt = TAGES_MB;
    private static long gemerktUm = Long.MIN_VALUE;

    private static synchronized void auffrischen(long now) {
        if (gemerktUm != Long.MIN_VALUE && now - gemerktUm < CACHE_MS && now >= gemerktUm) return;
        Quelle q = quelle;
        anGemerkt = q.an();
        mbGemerkt = q.tagesMb();
        gemerktUm = now;
    }

    /** Schalter „Am PC anmelden“ an? (fail-closed: unlesbar = aus) */
    public static boolean erlaubt() {
        return erlaubt(System.currentTimeMillis());
    }

    static boolean erlaubt(long now) {
        try {
            auffrischen(now);
            synchronized (PcZugang.class) {
                return anGemerkt;
            }
        } catch (Throwable t) {
            return false;
        }
    }

    /** Nach einer Änderung des Schalters sofort neu lesen. */
    public static synchronized void vergessen() {
        gemerktUm = Long.MIN_VALUE;
    }

    /** Gültig bis (ms) für eine neue PC-Kopplung; 0 = 30 Tage gleitend wie jede Kopplung. */
    public static long ablaufFuer(String dauer, long now) {
        return DAUER_HEUTE.equals(dauer) ? now + NUR_HEUTE_MS : 0L;
    }

    // ---------------------------------------------------------------- Grenzen über die Brücke (nur RAM)

    private static final java.util.Map<String, java.util.ArrayDeque<Long>> SEKUNDE = new java.util.HashMap<>();
    private static final java.util.Map<String, long[]> TAG = new java.util.HashMap<>();

    /** Noch eine Anfrage in dieser Sekunde erlaubt? Zählt sie mit. */
    public static synchronized boolean anfrageErlaubt(String kid, long now) {
        java.util.ArrayDeque<Long> d = SEKUNDE.get(kid);
        if (d == null) SEKUNDE.put(kid, d = new java.util.ArrayDeque<>());
        while (!d.isEmpty() && now - d.peekFirst() >= 1000L) d.pollFirst();
        if (d.size() >= MAX_JE_SEKUNDE) return false;
        d.addLast(now);
        return true;
    }

    /** Tagesgrenze dieses PCs erreicht? */
    public static boolean tagVoll(String kid, long now) {
        auffrischen(now);
        synchronized (PcZugang.class) {
            long[] t = TAG.get(kid);
            return t != null && t[0] == tag(now) && t[1] >= (long) mbGemerkt * 1024 * 1024;
        }
    }

    /** Bytes (Anfrage + Antwort) dieses PCs heute dazuzählen. */
    public static synchronized void verbrauchen(String kid, long bytes, long now) {
        long heute = tag(now);
        long[] t = TAG.get(kid);
        if (t == null || t[0] != heute) TAG.put(kid, t = new long[] {heute, 0L});
        t[1] += Math.max(0L, bytes);
    }

    /** Heute verbraucht (Bytes), für die Anzeige. */
    public static synchronized long heute(String kid, long now) {
        long[] t = TAG.get(kid);
        return t == null || t[0] != tag(now) ? 0L : t[1];
    }

    private static long tag(long now) {
        return Math.floorDiv(now, 86_400_000L);
    }

    // ---------------------------------------------------------------- abgelehnte Handshakes (nur RAM)

    /** Ab so vielen abgelehnten Brücken-Handshakes in {@link #HANDSHAKE_FENSTER_MS} geht eine Meldung an den Besitzer. */
    public static final int HANDSHAKE_GRENZE = 5;
    public static final long HANDSHAKE_FENSTER_MS = 3600_000L;
    private static final java.util.ArrayDeque<Long> HANDSHAKES = new java.util.ArrayDeque<>();
    private static long handshakeGemeldet = Long.MIN_VALUE;

    /**
     * Ein Handshake über die Brücke wurde abgelehnt (unbekannter Schlüssel, kaputte erste Nachricht). true genau dann,
     * wenn jetzt gemeldet werden soll: mindestens {@link #HANDSHAKE_GRENZE} in der letzten Stunde, höchstens eine Meldung je
     * Stunde. Zählt nur bei eingeschaltetem {@link #SCHALTER} (0.1.38.9 schlafend).
     */
    public static synchronized boolean handshakeAbgelehnt(long now) {
        if (!erlaubt(now)) return false;
        while (!HANDSHAKES.isEmpty() && now - HANDSHAKES.peekFirst() >= HANDSHAKE_FENSTER_MS) HANDSHAKES.pollFirst();
        HANDSHAKES.addLast(now);
        if (HANDSHAKES.size() < HANDSHAKE_GRENZE) return false;
        if (handshakeGemeldet != Long.MIN_VALUE && now - handshakeGemeldet < HANDSHAKE_FENSTER_MS) return false;
        handshakeGemeldet = now;
        return true;
    }

    /** Abgelehnte Handshakes in der letzten Stunde (für die Meldung). */
    public static synchronized int handshakesLetzteStunde() {
        return HANDSHAKES.size();
    }

    /** Nur Tests. */
    public static synchronized void leeren() {
        SEKUNDE.clear();
        TAG.clear();
        HANDSHAKES.clear();
        handshakeGemeldet = Long.MIN_VALUE;
        gemerktUm = Long.MIN_VALUE;
    }
}
