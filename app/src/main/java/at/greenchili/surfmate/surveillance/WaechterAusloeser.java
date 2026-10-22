package at.greenchili.surfmate.surveillance;

import at.greenchili.surfmate.einstellungen.Laenderprofil;
import at.greenchili.surfmate.einstellungen.TriggerModus;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Wächter-Auslöser a/b/c im Erkennungs-Loop (Koordination 21.10.2026).
 *
 * <p>Letzte Prüfung vor „RECORDING TRIGGERED“ in SurveillanceEngineGpu:
 * <ul>
 *   <li>{@link Lage#kiErlaubt} false (Modus a, außerhalb der Privat-Orte bei c, Schweiz bei b ohne
 *       Ort) → Bewegungs-/KI-Auslösung unterdrückt.</li>
 *   <li>{@link Lage#kiPflicht} (b, c und nicht gesetzt = wie b) → nur nach KI-Bestätigung (neues
 *       Objekt aus der Klassenliste in dieser Sequenz). Fail-open: läuft die KI nicht (Modell nicht
 *       geladen, Fehler), löst Bewegung wie bisher aus, mit einmaliger Warnung und
 *       Nachladeversuch – so geht kein Ereignis verloren (Koordination 21.10.2026).</li>
 * </ul>
 * Die Lage wird höchstens alle {@link #REFRESH_MS} aus Config und GPS neu bestimmt.
 */
public final class WaechterAusloeser {

    static final long REFRESH_MS = 5_000L;

    public static final class Lage {
        public final boolean kiErlaubt;
        public final boolean kiPflicht;
        public final String wirksam;
        Lage(boolean kiErlaubt, boolean kiPflicht, String wirksam) {
            this.kiErlaubt = kiErlaubt;
            this.kiPflicht = kiPflicht;
            this.wirksam = wirksam;
        }
    }

    private static volatile Lage cache = new Lage(true, false, TriggerModus.AI_EVERYWHERE);
    private static volatile long cacheAt;

    private WaechterAusloeser() {}

    /** Was der Loop mit einer Sequenz tun soll. */
    public enum Urteil { WIE_BISHER, UNTERDRUECKEN, FAIL_OPEN_KI_AUS }

    /**
     * Rein: Urteil für eine Bewegungssequenz.
     *
     * @param kiVerfuegbar Modell geladen und vom Nutzer eingeschaltet
     * @param kiGewollt    Nutzer will KI (aiEnabled, Klassenliste nicht leer) – sonst ist „KI aus“
     *                     eine bewusste Wahl und kein Fehler
     */
    public static Urteil urteil(Lage l, boolean kiVerfuegbar, boolean kiGewollt, boolean kiBestaetigt) {
        if (!l.kiErlaubt) return Urteil.UNTERDRUECKEN;
        if (!l.kiPflicht) return Urteil.WIE_BISHER;
        if (kiVerfuegbar) return kiBestaetigt ? Urteil.WIE_BISHER : Urteil.UNTERDRUECKEN;
        return kiGewollt ? Urteil.FAIL_OPEN_KI_AUS : Urteil.WIE_BISHER;
    }

    /** Reine Bestimmung (Tests). */
    public static Lage bestimme(String gesetzt, Laenderprofil.Land land, JSONArray zonen,
                                double lat, double lon, boolean fix) {
        boolean erlaubt = TriggerModus.kiErlaubt(gesetzt, land, zonen, lat, lon, fix);
        // KI-Bestätigung nur in den KI-Modi b/c; nicht gewählt, a und „aus“ lösen nie über Bewegung/KI aus
        boolean pflicht = TriggerModus.kiModus(gesetzt);
        String wirksam = TriggerModus.wirksam(gesetzt, land, zonen != null && zonen.length() > 0);
        return new Lage(erlaubt, pflicht, wirksam);
    }

    /** Aktuelle Lage im Daemon (gecacht). */
    public static Lage lage(long nowMs) {
        if (nowMs - cacheAt < REFRESH_MS && nowMs >= cacheAt) return cache;
        Lage l;
        try {
            JSONObject s = at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig()
                    .optJSONObject("surveillance");
            String gesetzt = s == null ? null : s.optString("triggerMode", null);
            JSONArray zonen = s == null ? null : s.optJSONArray("privateZones");
            // 0.1.12 (ORTE_VERTRAG §5): private Orte aus der Ortsliste lesen; noch nicht migriert → alter Schlüssel
            try {
                JSONArray orte = at.greenchili.surfmate.orte.OrteDaemon.listeOhneAbgleich();
                if (orte != null) zonen = at.greenchili.surfmate.orte.Orte.privatZonen(orte);
            } catch (Throwable ignored) {
            }
            at.greenchili.surfmate.monitor.GpsMonitor g = at.greenchili.surfmate.monitor.GpsMonitor.getInstance();
            long age = nowMs - g.getLastUpdate();
            boolean fix = g.getLastUpdate() > 0 && age >= 0 && age < 10 * 60_000L;
            double lat = g.getLatitude(), lon = g.getLongitude();
            l = bestimme(gesetzt, Laenderprofil.land(lat, lon, fix), zonen, lat, lon, fix);
        } catch (Throwable t) {
            // Config/GPS unlesbar: bisheriges Verhalten beibehalten, keine neue Sperre erfinden.
            l = cache;
        }
        cache = l;
        cacheAt = nowMs;
        return l;
    }
}
