package at.greenchili.surfmate.camera;

import org.json.JSONObject;

/**
 * Ehrlicher Kamera-fps-Status für Diagnose, Stats-Zeile und Einstellungen (Koordination 0.1.9):
 * angefordert / vom HAL geliefert (gemessen) / verarbeitet / HAL setzbar / Software-Drossel aktiv.
 * Von der Kamera geschrieben, von überall gelesen; nur volatile Felder, keine Abhängigkeiten.
 */
public final class KameraFpsStatus {

    private KameraFpsStatus() {}

    static volatile int angefordert;
    static volatile double geliefert = -1;
    static volatile double verarbeitet = -1;
    /** null = unbekannt (Kamera noch nicht offen oder Variante ohne setCameraFps-Rückmeldung). */
    static volatile Boolean halSetzbar;
    static volatile boolean drosselAktiv;
    static volatile String weg = "";
    static volatile long stand;

    static void setze(int angefordert, double geliefert, double verarbeitet, Boolean halSetzbar,
                      boolean drosselAktiv, String weg) {
        KameraFpsStatus.angefordert = angefordert;
        KameraFpsStatus.geliefert = geliefert;
        KameraFpsStatus.verarbeitet = verarbeitet;
        KameraFpsStatus.halSetzbar = halSetzbar;
        KameraFpsStatus.drosselAktiv = drosselAktiv;
        KameraFpsStatus.weg = weg == null ? "" : weg;
        KameraFpsStatus.stand = System.currentTimeMillis();
    }

    /** Höchste Rate, die der HAL dieses Autos nachweislich liefert (0 = unbekannt). */
    public static int halMaxFps() {
        double g = geliefert;
        return g > 0 ? (int) Math.round(g) : 0;
    }

    public static JSONObject alsJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("angefordert", angefordert);
            o.put("geliefert", geliefert < 0 ? JSONObject.NULL : Math.round(geliefert * 10) / 10.0);
            o.put("verarbeitet", verarbeitet < 0 ? JSONObject.NULL : Math.round(verarbeitet * 10) / 10.0);
            o.put("halSetzbar", halSetzbar == null ? JSONObject.NULL : halSetzbar);
            o.put("drosselAktiv", drosselAktiv);
            o.put("weg", weg);
            o.put("stand", stand > 0 ? stand : JSONObject.NULL);
        } catch (Exception ignored) {
        }
        return o;
    }
}
