package at.greenchili.surfmate.camera.dilink5;

import org.json.JSONObject;

/**
 * K2 (0.1.38.11): Kamera-Eingang (default / dilink4 / dilink5) aus belegten Signalen ERKENNEN - die eine Stelle dafür.
 *
 * <p>Die Erkennung schaltet NIE selbst um. Sie liefert nur einen Vorschlag mit Sicherheit; umgestellt wird ausschließlich
 * über den bestehenden Weg „Speichern und neu starten“ (DiLink5Platform.stageConfiguredMode), nach einem Tipp des Nutzers.
 *
 * <p>Belegte Signale (sonst wird nichts geraten):
 * <ul>
 *   <li>{@code /system/lib64/libais_test_util.so} = QCarCam-Werkzeug nur auf DiLink-5-Firmware
 *       ({@link DiLink5Platform#hasDiLink5CameraHardware()}) → dilink5, sicher.</li>
 *   <li>Android ≤ 10 (SDK ≤ 29) = DiLink 3 (Testauto Dolphin Surf, Android 10) → default; „sicher“ nur zusammen mit dem
 *       gewählten Modell Dolphin Surf (seagull), sonst „vermutlich“.</li>
 *   <li>Android ≥ 11 ohne libais → dilink4, „vermutlich“ (DiLink 4 liefert die Kameras über die BYD-HAL, nicht über
 *       QCarCam). Gewähltes Modell mit DiLink-5-Profil (Sealion 7) ohne libais widerspricht sich → unbekannt.</li>
 * </ul>
 */
public final class AutoArtErkennung {

    public static final String SICHER = "sicher";
    public static final String VERMUTLICH = "vermutlich";
    public static final String UNBEKANNT = "unbekannt";

    static final String MODELL_SURF = "seagull";
    static final String MODELL_DILINK5 = "sealion7";

    private AutoArtErkennung() {}

    /** Ergebnis der Erkennung. {@code modus} ist null, wenn nichts belegt ist. */
    public static final class Ergebnis {
        public final String modus;
        public final Integer dilink;
        public final String sicherheit;
        public final String grund;

        Ergebnis(String modus, Integer dilink, String sicherheit, String grund) {
            this.modus = modus;
            this.dilink = dilink;
            this.sicherheit = sicherheit;
            this.grund = grund;
        }
    }

    /**
     * Rein (testbar): Erkennung aus den Signalen.
     *
     * @param libais   {@code libais_test_util.so} vorhanden
     * @param sdk      Android-SDK (0 = unbekannt)
     * @param modellId gewähltes Modell (FahrzeugWahl-ID, z. B. „seagull“) oder null
     */
    public static Ergebnis erkennen(boolean libais, int sdk, String modellId) {
        String m = modellId == null ? "" : modellId.trim().toLowerCase(java.util.Locale.ROOT);
        if (libais) return new Ergebnis("dilink5", 5, SICHER, "libais");
        if (sdk <= 0) return new Ergebnis(null, null, UNBEKANNT, "android_unbekannt");
        if (sdk <= 29) {
            return MODELL_SURF.equals(m)
                    ? new Ergebnis("default", 3, SICHER, "android10_surf")
                    : new Ergebnis("default", 3, VERMUTLICH, "android10");
        }
        if (MODELL_DILINK5.equals(m)) return new Ergebnis(null, null, UNBEKANNT, "widerspruch_modell");
        return new Ergebnis("dilink4", 4, VERMUTLICH, "android11_ohne_libais");
    }

    /**
     * Rein: Status für Oberfläche und Fehler melden. {@code vorschlag} nur, wenn die Erkennung etwas weiß und der
     * gespeicherte Modus nicht dazu passt - dann zeigt die Oberfläche den Hinweis mit Ein-Tipp-Knopf.
     */
    public static JSONObject status(Ergebnis e, String gespeichert) {
        JSONObject o = new JSONObject();
        String g = DiLink5Platform.normalizeConfiguredMode(gespeichert);
        if (g == null) g = "default";
        boolean weiss = e.modus != null && !UNBEKANNT.equals(e.sicherheit);
        boolean passt = !weiss || e.modus.equals(g);
        try {
            o.put("erkannt", e.modus == null ? JSONObject.NULL : e.modus);
            o.put("dilink", e.dilink == null ? JSONObject.NULL : e.dilink);
            o.put("sicherheit", e.sicherheit);
            o.put("grund", e.grund);
            o.put("gespeichert", g);
            o.put("passt", passt);
            o.put("vorschlag", passt ? JSONObject.NULL : e.modus);
        } catch (Exception ignored) {
        }
        return o;
    }

    /** Kurzform für „Fehler melden“: „dilink4/vermutlich“, „unbekannt/unbekannt“ (keine weiteren Angaben). */
    public static String kurz(Ergebnis e) {
        return (e.modus == null ? UNBEKANNT : e.modus) + "/" + e.sicherheit;
    }

    /** Erkennung am Gerät (liest nur, schreibt nichts). */
    public static Ergebnis jetzt() {
        String modell = null;
        try {
            modell = at.greenchili.surfmate.planer.FahrzeugWahl.erkanntesModell(
                    at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig());
        } catch (Throwable ignored) {
        }
        int sdk = 0;
        try {
            sdk = android.os.Build.VERSION.SDK_INT;
        } catch (Throwable ignored) {
        }
        return erkennen(DiLink5Platform.hasDiLink5CameraHardware(), sdk, modell);
    }

    /** Status für den gespeicherten Modus ({@code camera.cameraMode}, sonst der laufende). */
    public static JSONObject statusJetzt() {
        String gespeichert = null;
        try {
            JSONObject cam = at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig().optJSONObject("camera");
            if (cam != null && cam.has("cameraMode") && !cam.isNull("cameraMode")) {
                gespeichert = cam.optString("cameraMode", null);
            }
        } catch (Throwable ignored) {
        }
        if (gespeichert == null) gespeichert = DiLink5Platform.currentActiveMode();
        return status(jetzt(), gespeichert);
    }
}
