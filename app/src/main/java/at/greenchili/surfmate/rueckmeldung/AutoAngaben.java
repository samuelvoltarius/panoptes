package at.greenchili.surfmate.rueckmeldung;

import at.greenchili.surfmate.config.VehicleModelSelection;
import at.greenchili.surfmate.planer.FahrzeugWahl;

import org.json.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Modell und DiLink für „Anonym mitzählen“ und „Fehler melden“ (0.1.31). Nur diese beiden Angaben, nur aus der
 * Fahrzeugwahl und der Plattform-Erkennung. Nichts raten: unbekanntes Modell = {@link #UNBEKANNT}, unklare
 * DiLink-Stufe = null.
 */
public final class AutoAngaben {

    private AutoAngaben() {}

    public static final String UNBEKANNT = "unbekannt";

    /** Feste Liste (ids aus models/manifest.json). „seagull“ heißt in Europa Dolphin Surf. */
    static final Map<String, String> NAMEN;
    static {
        Map<String, String> m = new HashMap<>();
        m.put("seal", "Seal");
        m.put("sealion7", "Sealion 7");
        m.put("shark", "Shark");
        m.put("seal-u", "Seal U");
        m.put("seal-u-dmi", "Seal U DM-i");
        m.put("seal6-gt", "Seal 6 GT");
        m.put("seal6-dmi-touring", "Seal 6 DM-i Touring");
        m.put("dolphin", "Dolphin");
        m.put("atto3", "Atto 3");
        m.put("atto3-evo", "Atto 3 Evo");
        m.put("atto2", "Atto 2");
        m.put("han", "Han");
        m.put("tang", "Tang");
        m.put("m6", "M6");
        m.put("seagull", "Dolphin Surf");
        m.put("destroyer", "Destroyer 05");
        NAMEN = Collections.unmodifiableMap(m);
    }

    /**
     * Modellname des Autos: nur echte Wahl, nie der Anzeige-Rückfall (Regel 1). 0.1.32.1: auch Quelle „legacy“ (Wahl aus
     * der Zeit vor der Herkunfts-Angabe), außer „seal“: das schrieben alte Neuinstallationen bei jedem Auto.
     */
    public static String modell(JSONObject root) {
        String id = FahrzeugWahl.erkanntesModell(root);
        if (id == null) id = altWahl(root);
        String name = id == null ? null : NAMEN.get(id);
        return name == null ? UNBEKANNT : name;
    }

    private static String altWahl(JSONObject root) {
        JSONObject v = root == null ? null : root.optJSONObject("vehicle");
        if (v == null) return null;
        String canonical = VehicleModelSelection.canonicalModelId(v.optString("modelId", ""));
        String raw = canonical == null ? "" : canonical.toLowerCase(java.util.Locale.ROOT);
        if (raw.isEmpty() || "seal".equals(raw)) return null;
        return VehicleModelSelection.SOURCE_LEGACY.equals(
                VehicleModelSelection.normalizeSource(v.optString("modelSource", ""), raw)) ? raw : null;
    }

    /** Längste Firmware-Angabe, die mitgeht. */
    static final int FIRMWARE_MAX = 64;
    /** Eine Fahrgestellnummer (17 Zeichen, ohne I, O, Q) darf nie als „Firmware“ durchgehen. */
    private static final java.util.regex.Pattern FIN = java.util.regex.Pattern.compile("[A-HJ-NPR-Z0-9]{17}");

    /**
     * Rein: Firmware des Head-Units als kurzer Text aus der Build-Anzeige (zuerst {@code display}, sonst {@code incremental}).
     * Nur Buchstaben, Ziffern und „._+-“ und Leerzeichen, höchstens {@link #FIRMWARE_MAX} Zeichen; alles andere fällt weg.
     * Sieht der Rest wie eine Fahrgestellnummer aus oder ist er leer/zu kurz, gibt es null (nichts raten, nichts Persönliches).
     */
    static String firmware(String display, String incremental) {
        String roh = display != null && !display.trim().isEmpty() ? display : incremental;
        if (roh == null) return null;
        String s = roh.replaceAll("[^A-Za-z0-9._+ -]", "").replaceAll(" {2,}", " ").trim();
        if (s.length() > FIRMWARE_MAX) s = s.substring(0, FIRMWARE_MAX).trim();
        if (s.length() < 3 || FIN.matcher(s.toUpperCase(java.util.Locale.ROOT)).matches()) return null;
        return s;
    }

    public static String firmware() {
        try {
            return firmware(android.os.Build.DISPLAY, android.os.Build.VERSION.INCREMENTAL);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Rein: 5 und 4 aus der gewählten Plattform; bis Android 10 ist es DiLink 3; sonst nicht sicher bekannt. */
    static Integer dilink(boolean fuenf, boolean vier, int sdk) {
        if (fuenf) return 5;
        if (vier) return 4;
        return sdk > 0 && sdk <= 29 ? 3 : null;
    }

    public static Integer dilink() {
        try {
            return dilink(at.greenchili.surfmate.camera.dilink5.DiLink5Platform.isSelected(),
                    at.greenchili.surfmate.camera.dilink5.DiLink5Platform.isDiLink4Selected(),
                    android.os.Build.VERSION.SDK_INT);
        } catch (Throwable t) {
            return null;
        }
    }
}
