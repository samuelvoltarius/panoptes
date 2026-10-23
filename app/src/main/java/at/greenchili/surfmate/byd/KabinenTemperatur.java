package at.greenchili.surfmate.byd;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;

/**
 * Eine Stelle für die Frage „ist das eine gemessene Innentemperatur?“ (Koordination 23.10.2026, 0.1.16).
 *
 * <p>Beim Dolphin Surf ist {@code Ac.AC_TEMP_INSIDE} trotz des Namens der <b>Klima-Sollwert</b>, keine Messung:
 * Alfreds Test am 19.07.2026 – Klima aus 26, Kühlen 17, max. Heizen 33 binnen 15 s (+16 K), danach exakt stabil.
 * Der Wert folgt dem Drehregler, nicht der Luft. Die Cloud liefert {@code tempInCar = -129} (kein Fühler).
 *
 * <p>Für kein anderes Modell ist belegt, dass der Wert misst. Deshalb gilt er überall als Sollwert, bis ein
 * Provokationstest (Zustand ändern, Reaktion messen) für ein Modell das Gegenteil zeigt. Dann kommt dessen
 * Modell-ID in {@link #MESSUNG_BELEGT}. Eine erfundene Innentemperatur ist schlimmer als keine: Sie würde in HA,
 * in Automationen („Innen über 40 °C → Alarm“) und im Cockpit als echter Wert erscheinen.
 */
public final class KabinenTemperatur {

    private KabinenTemperatur() {}

    /** Modell-IDs (wie {@code vehicle.modelId}), bei denen AC_TEMP_INSIDE bzw. tempInCar nachweislich misst. */
    static final Set<String> MESSUNG_BELEGT = Collections.emptySet();

    /** Ist {@code Ac.AC_TEMP_INSIDE} bei diesem Modell eine Messung? Sonst ist es der Klima-Sollwert. */
    public static boolean acTempInsideIstMessung(String modellId) {
        return belegt(modellId);
    }

    /** Ist die Cloud-Angabe {@code tempInCar} bei diesem Modell eine Messung? */
    public static boolean cloudTempInCarIstMessung(String modellId) {
        return belegt(modellId);
    }

    private static boolean belegt(String modellId) {
        if (MESSUNG_BELEGT.isEmpty() || modellId == null) return false;
        return MESSUNG_BELEGT.contains(modellId.trim().toLowerCase(Locale.ROOT));
    }

    /** Aktuelle Modell-ID aus der Konfiguration, oder null. Nur gelesen, wenn überhaupt ein Modell belegt ist. */
    public static String aktuellesModell() {
        if (MESSUNG_BELEGT.isEmpty()) return null;
        try {
            org.json.JSONObject v = at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig()
                    .optJSONObject("vehicle");
            return v == null ? null : v.optString("modelId", null);
        } catch (Throwable t) {
            return null;
        }
    }
}
