package at.greenchili.surfmate.reichweite;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Live-Belegung des nächsten Ladestopps aus der Planer-Antwort {@code POST umkreis}
 * (Vertrag chargers-live v2). Rein, ohne Netz.
 *
 * <p>Abfrage um den STOPP (nie um die Position des Autos), Mittelpunkt auf 0,05° gerundet
 * ({@link #anfrage}); der Radius deckt die Rundung ab.
 */
public final class StoppBelegung {

    /** Höchstabstand Station ↔ Planstopp für die Zuordnung. */
    static final double ZUORDNUNG_KM = 0.15;
    /** Ausweich-Säulen höchstens so weit vom Stopp entfernt. */
    static final double AUSWEICH_KM = 5.0;
    /** Radius der Abfrage: 2 km um den Stopp + Rundungsversatz des Mittelpunkts (≤ ~3,4 km). */
    static final double RADIUS_KM = 6.0;

    public boolean gefunden;
    public boolean statusBekannt;
    public int frei, ladepunkte, belegt, defekt;
    public long alterS;
    /** Laut Live-Belegung voll oder außer Betrieb (nur bei bekanntem Status). */
    public boolean voll;
    public String ausweichName;
    public double ausweichKm = Double.NaN, ausweichKw;
    public int ausweichFrei;

    private StoppBelegung() {}

    /** Anfrage-Body für {@code POST umkreis} (Mittelpunkt gerundet, Koordinaten nur im Body). */
    public static JSONObject anfrage(double stoppLat, double stoppLon) {
        JSONObject b = new JSONObject();
        try {
            b.put("lat", Math.round(stoppLat * 20) / 20.0);
            b.put("lon", Math.round(stoppLon * 20) / 20.0);
            b.put("radius", RADIUS_KM);
            b.put("limit", 200);
        } catch (Exception ignored) {
        }
        return b;
    }

    /**
     * @param antwort  Antwort von umkreis oder null
     * @param minKw    gewünschte Mindestleistung der Ausweich-Säule (z. B. halbe Stopp-Leistung)
     */
    public static StoppBelegung auswerten(JSONObject antwort, double stoppLat, double stoppLon, double minKw) {
        StoppBelegung b = new StoppBelegung();
        JSONArray st = antwort == null ? null : antwort.optJSONArray("stationen");
        if (st == null) return b;
        JSONObject treffer = null;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < st.length(); i++) {
            JSONObject s = st.optJSONObject(i);
            if (s == null) continue;
            double d = RangeWatch.distKm(stoppLat, stoppLon, s.optDouble("lat", 0), s.optDouble("lon", 0));
            if (d < best) {
                best = d;
                treffer = s;
            }
        }
        if (treffer == null || best > ZUORDNUNG_KM) return b;
        b.gefunden = true;
        b.statusBekannt = treffer.optBoolean("status_bekannt", false);
        b.frei = treffer.optInt("frei", 0);
        b.ladepunkte = treffer.optInt("ladepunkte", 0);
        b.belegt = treffer.optInt("belegt_n", 0);
        b.defekt = treffer.optInt("defekt_n", 0);
        b.alterS = treffer.optLong("live_alter_s", 0);
        b.voll = b.statusBekannt && b.frei == 0;
        if (b.voll) {
            double bestA = Double.MAX_VALUE;
            for (int i = 0; i < st.length(); i++) {
                JSONObject s = st.optJSONObject(i);
                if (s == null || s == treffer) continue;
                if (!s.optBoolean("status_bekannt", false) || s.optInt("frei", 0) <= 0) continue;
                if (s.optDouble("kw", 0) < minKw) continue;
                double d = RangeWatch.distKm(stoppLat, stoppLon, s.optDouble("lat", 0), s.optDouble("lon", 0));
                if (d > AUSWEICH_KM || d >= bestA) continue;
                bestA = d;
                b.ausweichName = s.optString("name", "Ladesäule");
                b.ausweichKm = d;
                b.ausweichKw = s.optDouble("kw", 0);
                b.ausweichFrei = s.optInt("frei", 0);
            }
        }
        return b;
    }

    /** Für die Antwort von /api/navi/unterwegs ({@code naechsterStopp.belegung}). */
    public JSONObject alsJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("gefunden", gefunden);
            o.put("statusBekannt", statusBekannt);
            o.put("frei", frei);
            o.put("ladepunkte", ladepunkte);
            o.put("belegt", belegt);
            o.put("defekt", defekt);
            o.put("alterS", alterS);
            o.put("voll", voll);
            if (ausweichName != null) {
                JSONObject a = new JSONObject();
                a.put("name", ausweichName);
                a.put("km", Math.round(ausweichKm * 10) / 10.0);
                a.put("kw", ausweichKw);
                a.put("frei", ausweichFrei);
                o.put("ausweich", a);
            }
        } catch (Exception ignored) {
        }
        return o;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "Belegung[%s frei=%d/%d voll=%s]", gefunden, frei, ladepunkte, voll);
    }
}
