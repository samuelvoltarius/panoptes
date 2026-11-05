package at.greenchili.surfmate.surveillance;

import java.util.Map;

/**
 * 0.1.38.11 (Befund Seal U: „Wächter ist an“ und daneben „Tiermodus ist aus“, bei Zündung an nicht scharf ohne Grund):
 * EIN Zustand des Wächters mit Grund, aus dem vorhandenen Status ({@code /api/surveillance/status}). Die Wächter-Seite
 * zeigt daraus genau eine Zeile, „Fehler melden“ schickt dieselbe Zeile mit. Rein, JVM-testbar.
 *
 * <p>Reihenfolge wie bisher auf der Seite: Ruhezone, Stopp (Batterie/Parkzeit/Ruhezeit), Start, sonst unterdrückt
 * (z. B. Camping), scharf, bei Zündung an planmäßig nicht scharf, bereit, aus.
 */
public final class WaechterGrund {

    public static final String RUHEZONE = "ruhezone";
    public static final String GESTOPPT = "gestoppt";
    public static final String STARTET = "startet";
    public static final String PAUSIERT = "pausiert";
    public static final String PASST_AUF = "passt_auf";
    public static final String ZUENDUNG_AN = "zuendung_an";
    public static final String BEREIT = "bereit";
    public static final String AUS = "aus";

    private WaechterGrund() {}

    /**
     * @param s          Status wie {@code CameraDaemon.getSurveillanceStatus()} (+ GuardStopMonitor-Felder)
     * @param zuendungAn Zündung an (ACC), null = unbekannt
     */
    public static String grund(Map<String, Object> s, Boolean zuendungAn) {
        boolean enabled = wahr(s.get("enabled"));
        if (wahr(s.get("safeZoneSuppressed")) || wahr(s.get("inSafeZone"))) return RUHEZONE;
        if (enabled && wahr(s.get("guardStopActive"))) return GESTOPPT;
        Object z = s.get("zustand");
        if (STARTET.equals(z)) return STARTET;
        if ("unterdrueckt".equals(z)) return PAUSIERT;
        if (enabled && wahr(s.get("active"))) return PASST_AUF;
        if (enabled && Boolean.TRUE.equals(zuendungAn)) return ZUENDUNG_AN;
        if (enabled) return BEREIT;
        return AUS;
    }

    /** Eine Zeile für „Fehler melden“, ohne Ort und ohne Kennungen. */
    public static String zeile(Map<String, Object> s, Boolean zuendungAn, Boolean verriegelt) {
        StringBuilder sb = new StringBuilder("WAECHTER_ZUSTAND grund=").append(grund(s, zuendungAn));
        Object r = s.get("guardStopReason");
        if (wahr(s.get("guardStopActive")) && r != null) sb.append(" stopp=").append(r);
        sb.append(" zustand=").append(s.get("zustand"));
        sb.append(" an=").append(wahr(s.get("enabled")));
        sb.append(" scharf=").append(wahr(s.get("active")));
        sb.append(" zuendung=").append(zuendungAn == null ? "unbekannt" : zuendungAn ? "an" : "aus");
        sb.append(" verriegelt=").append(verriegelt == null ? "unbekannt" : verriegelt ? "ja" : "nein");
        return sb.toString();
    }

    private static boolean wahr(Object o) {
        return Boolean.TRUE.equals(o);
    }
}
