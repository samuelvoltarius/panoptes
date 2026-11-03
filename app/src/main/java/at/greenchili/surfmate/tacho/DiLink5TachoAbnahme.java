package at.greenchili.surfmate.tacho;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.regex.Pattern;

/**
 * Read-only safety record for arbitrary-app projection on a DiLink 5 cluster.
 *
 * <p>This class deliberately cannot approve or launch an app. It separates detected platform
 * capabilities from evidence that can only be collected in a parked real vehicle. The productive
 * admission remains in {@link TachoFaehigkeit} and stays fail-closed until that evidence is reviewed
 * and implemented as a separate, explicit policy change.
 */
public final class DiLink5TachoAbnahme {

    public static final String BLOCK_CODE = "dilink5_totwinkel_nicht_belegt";
    private static final Pattern PACKAGE_NAME = Pattern.compile(
            "[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+");

    private DiLink5TachoAbnahme() {}

    /** Platform diagnosis plus the physical measurements which still block approval. */
    public static JSONObject diagnose(JSONObject platform) throws Exception {
        JSONObject p = platform == null ? new JSONObject() : platform;
        JSONObject out = new JSONObject();
        out.put("code", BLOCK_CODE);
        out.put("produktivFreigegeben", false);
        out.put("probeStartbar", false);
        out.put("modus", "nur_diagnose");

        JSONObject capabilities = new JSONObject();
        capabilities.put("dilink5Erkannt", "5".equals(p.optString("dilink"))
                && p.optBoolean("dilinkSicher"));
        capabilities.put("sharedFissionGefunden", p.optBoolean("sharedFission"));
        capabilities.put("shellRechte", p.optBoolean("shellRechte"));
        Object display = p.opt("tachoDisplay");
        capabilities.put("tachoDisplay", display instanceof JSONObject ? display : JSONObject.NULL);
        out.put("capabilities", capabilities);

        JSONArray blockers = new JSONArray();
        blockers.put(measurement("linker_blinker_kamera_vorrang",
                "Linke Totwinkelkamera liegt sichtbar ueber der Test-App; Tempo und Warnungen bleiben sichtbar."));
        blockers.put(measurement("linker_blinker_paket_restore",
                "Nach Ende des linken Blinkers kehrt dasselbe App-Paket zurueck."));
        blockers.put(measurement("linker_blinker_geometrie_restore",
                "Nach Ende des linken Blinkers stimmen Display, Position und Groesse exakt mit vorher ueberein."));
        blockers.put(measurement("rechter_blinker_kamera_vorrang",
                "Rechte Totwinkelkamera liegt sichtbar ueber der Test-App; Tempo und Warnungen bleiben sichtbar."));
        blockers.put(measurement("rechter_blinker_paket_restore",
                "Nach Ende des rechten Blinkers kehrt dasselbe App-Paket zurueck."));
        blockers.put(measurement("rechter_blinker_geometrie_restore",
                "Nach Ende des rechten Blinkers stimmen Display, Position und Groesse exakt mit vorher ueberein."));
        blockers.put(measurement("fehler_fallback",
                "Bei erzwungenem Layer-/Restore-Fehler bleibt die OEM-Tachoanzeige sichtbar und die Fremd-App aus."));
        blockers.put(measurement("stop_acc_restore",
                "Stop und ACC aus entfernen Task und Marker; die OEM-Tachoanzeige bleibt ohne Restzustand."));
        out.put("offeneFahrzeugmessungen", blockers);
        return out;
    }

    /**
     * Package-specific, non-executing test contract. It is documentation-as-data, not a projection
     * probe: {@code startbar} always remains false while the vehicle evidence is missing.
     */
    public static JSONObject messvertrag(JSONObject platform, String packageName) throws Exception {
        String pkg = packageName == null ? "" : packageName.trim();
        JSONObject out = diagnose(platform);
        JSONObject probe = new JSONObject();
        probe.put("paket", pkg);
        probe.put("paketGueltig", PACKAGE_NAME.matcher(pkg).matches());
        probe.put("startbar", false);
        probe.put("nurImStand", true);
        probe.put("schreibtNichts", true);

        JSONArray sequence = new JSONArray();
        sequence.put(step(1, "vorher", "Paket, Display-ID, Position und Groesse protokollieren."));
        sequence.put(step(2, "blinker_links", "Kameravorrang pruefen; danach Paket und Geometrie vergleichen."));
        sequence.put(step(3, "blinker_rechts", "Kameravorrang pruefen; danach Paket und Geometrie vergleichen."));
        sequence.put(step(4, "fehler_fallback", "Layer-/Restore-Fehler erzwingen und sicheren OEM-Fallback pruefen."));
        sequence.put(step(5, "stop_acc", "Stop und ACC aus; Task, Marker und OEM-Restore pruefen."));
        probe.put("schritte", sequence);
        out.put("paketProbe", probe);
        return out;
    }

    private static JSONObject measurement(String code, String description) throws Exception {
        return new JSONObject()
                .put("code", code)
                .put("status", "am_fahrzeug_zu_messen")
                .put("beschreibung", description);
    }

    private static JSONObject step(int number, String code, String description) throws Exception {
        return new JSONObject()
                .put("nr", number)
                .put("code", code)
                .put("beschreibung", description);
    }
}
