package at.greenchili.surfmate.byd;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 0.1.28 (P3 „Klima läuft wirklich?“): ehrlicher Dreizustand statt Zeitrechnung.
 *
 * <ul>
 *   <li><b>läuft / aus</b> nur mit Beleg: lokal vom Auto (Auto an, {@code acStartState} 1/0), oder aus der Cloud
 *       über {@code airRunState} – aber nur mit Werten, die gemessen sind ({@link #AIR_RUN_LAEUFT},
 *       {@link #AIR_RUN_AUS}; PLAN_0128 §4). Ungemessen oder älter als {@link #CLOUD_MAX_ALTER_MS}: kein Beleg.</li>
 *   <li>Sonst <b>unbekannt</b>. Hat Panoptes selbst per Cloud gestartet und läuft die Sitzung nach der Uhr noch,
 *       steht zusätzlich {@code befohlen}: die Oberfläche sagt dann „Gestartet – das Auto hat es noch nicht
 *       bestätigt“, nie „an“.</li>
 * </ul>
 * Reine Rechnung, liest nichts selbst (Tests ohne Auto).
 */
public final class KlimaLaeuft {

    public static final String LAEUFT = "laeuft", AUS = "aus", UNBEKANNT = "unbekannt";
    public static final String QUELLE_AUTO = "auto", QUELLE_CLOUD = "cloud";

    /**
     * Gemessene {@code airRunState}-Werte. <b>Leer = ungemessen</b> (Stand 29.10.2026). Erst nach einem Lauf aus
     * PLAN_0128 §4 mit Datum eintragen, z. B. {@code {1}} – nie raten.
     */
    static final int[] AIR_RUN_LAEUFT = {};
    static final int[] AIR_RUN_AUS = {};

    /** Cloud-Wert älter als das gilt nicht als Beleg (BYD liefert bei schlafendem Auto alte Stände). */
    public static final long CLOUD_MAX_ALTER_MS = 5 * 60_000L;

    private KlimaLaeuft() {}

    public static final class Ergebnis {
        public final String zustand;
        /** auto | cloud | null (kein Beleg). */
        public final String quelle;
        /** Panoptes hat per Cloud gestartet, die Sitzung läuft nach der Uhr noch – ohne Bestätigung. */
        public final boolean befohlen;

        Ergebnis(String zustand, String quelle, boolean befohlen) {
            this.zustand = zustand;
            this.quelle = quelle;
            this.befohlen = befohlen;
        }

        /** „Gestartet, aber nicht bestätigt“: so darf die Oberfläche es nur nennen, nie „an“. */
        public boolean unbestaetigt() {
            return befohlen && !LAEUFT.equals(zustand);
        }

        public JSONObject json() throws JSONException {
            return new JSONObject().put("zustand", zustand)
                    .put("quelle", quelle == null ? JSONObject.NULL : quelle)
                    .put("unbestaetigt", unbestaetigt());
        }

        @Override
        public String toString() {
            return zustand + (quelle == null ? "" : "/" + quelle) + (befohlen ? " (befohlen)" : "");
        }
    }

    /**
     * @param autoAn         Auto nachweislich an (lokale Klima-Werte gelten, z. B. power_level ≥ 2 oder DiLink 5)
     * @param acStartState   lokal 1 = an, 0 = aus, sonst unbekannt
     * @param airRunState    Cloud-Rohwert, −1 = nicht gemeldet
     * @param airRunAlterMs  Alter des Cloud-Werts, &lt; 0 = unbekannt
     * @param befohlen       Panoptes-Cloud-Sitzung nach der Uhr noch offen
     */
    public static Ergebnis bewerte(boolean autoAn, int acStartState, int airRunState, long airRunAlterMs,
                                   boolean befohlen) {
        if (autoAn && acStartState == 1) return new Ergebnis(LAEUFT, QUELLE_AUTO, befohlen);
        if (autoAn && acStartState == 0) return new Ergebnis(AUS, QUELLE_AUTO, befohlen);
        String c = ausCloud(airRunState, airRunAlterMs);
        if (c != null) return new Ergebnis(c, QUELLE_CLOUD, befohlen);
        return new Ergebnis(UNBEKANNT, null, befohlen);
    }

    /** Cloud-Wert → läuft/aus, nur gemessen und frisch; sonst null. */
    static String ausCloud(int airRunState, long alterMs) {
        return ausCloud(airRunState, alterMs, AIR_RUN_LAEUFT, AIR_RUN_AUS);
    }

    static String ausCloud(int airRunState, long alterMs, int[] laeuft, int[] aus) {
        if (airRunState < 0 || alterMs < 0 || alterMs > CLOUD_MAX_ALTER_MS) return null;
        if (enthaelt(laeuft, airRunState)) return LAEUFT;
        if (enthaelt(aus, airRunState)) return AUS;
        return null;
    }

    /** Sind die {@code airRunState}-Werte gemessen? (für die Anzeige „unbekannt“ statt eines Fehlers) */
    public static boolean cloudGemessen() {
        return AIR_RUN_LAEUFT.length > 0 && AIR_RUN_AUS.length > 0;
    }

    private static boolean enthaelt(int[] a, int w) {
        for (int x : a) if (x == w) return true;
        return false;
    }
}
