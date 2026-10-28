package at.greenchili.surfmate.byd;

/**
 * 0.1.26 (Projektinhaber, Koordination 28.10.): Der Klima-Sollwert ist am Surf KEINE Temperatur, sondern eine Stufe auf dem
 * Balken im Display (Foto 23.10., KERN_UEBERGABE): 17 Striche, 8 blau | Mitte | 8 rot. Interne Einheit ist die
 * <b>Stufe 1–17</b> (= Strich, wie Display und BYD-App):
 * <ul>
 *   <li>Stufe 1 = stärkstes Kühlen („Kühlen Stufe 8“) … Stufe 8 = „Kühlen Stufe 1“</li>
 *   <li>Stufe 9 = Mitte = „Neutral“ (heizt nicht)</li>
 *   <li>Stufe 10 … 17 = „Heizen Stufe 1 … 8“ – Vorheizen heißt mindestens Stufe 10</li>
 * </ul>
 * Umrechnung:
 * <ul>
 *   <li>lokal (SDK, bisher „°C“ 17–33): SDK = Stufe + 16 – belegt (Foto 23.10.)</li>
 *   <li>Cloud (OPENAIR/BOOKINGAIR {@code mainSettingTemp}): Roh = Stufe – <b>Annahme</b> (BYD-App zeigt den Rohwert
 *       als Stufe: Cloud-„27“ alt = Roh 13 = Anzeige 13). Bis 0.1.25 galt Roh = °C − 14, das war um 2 zu warm.
 *       Messung nach 0.1.26 mit Stufe 10 (Koordination); bis dahin zeigt der Cloud-Weg nur {@link #textOhneStufe}.</li>
 * </ul>
 * Darum nie „°C“ für diesen Wert. Die echte Innen-/Außentemperatur bleibt in °C.
 */
public final class KlimaStufe {

    public static final int STUFE_MIN = 1, STUFE_MITTE = 9, STUFE_MAX = 17;
    /** Mindeststufe fürs Vorheizen (= SDK 26). */
    public static final int HEIZEN_AB = 10;
    /** SDK-Skala (bisher „°C“): 17–33, Mitte 25. */
    public static final int SDK_MIN = 17, SDK_MITTE = 25, SDK_MAX = 33;
    static final int SDK_VERSATZ = 16;

    public static final String HEIZEN = "heizen", KUEHLEN = "kuehlen", NEUTRAL = "neutral";

    private KlimaStufe() {}

    // ------------------------------------------------------------------ Modell-Liste (Koordination 28.10.)

    /**
     * Modelle, bei denen die Stufen-Skala belegt ist (Foto 23.10.): Dolphin Surf, Modell-ID {@code seagull} bzw.
     * {@code dolphinsurf} (siehe VehicleArt). Weitere kommen erst nach einer Community-Meldung mit Foto dazu – in
     * der Konfiguration unter {@code klima.stufenModelle} (Liste von Modell-IDs) ergänzbar, ohne neue Version.
     * Alle anderen Modelle zeigen den Wert wie bisher in °C, und ihre Cloud-Umrechnung bleibt pyBYD (°C − 14).
     */
    static final java.util.Set<String> STUFEN_MODELLE = java.util.Collections.unmodifiableSet(
            new java.util.HashSet<>(java.util.Arrays.asList("seagull", "dolphinsurf")));

    /** Nur für Tests: erzwingt das Ergebnis von {@link #stufenModell()} (null = echte Konfiguration). */
    static volatile Boolean testStufenModell;

    /** Nur für Tests (andere Pakete): {@code true} = Surf, {@code false} = anderes Modell, {@code null} = echt. */
    public static void fuerTest(Boolean stufen) {
        testStufenModell = stufen;
    }
    private static volatile long geprueftAm;
    private static volatile boolean zuletzt;

    /** Ist die Stufen-Skala für das eingestellte Modell belegt? (60 s zwischengespeichert) */
    public static boolean stufenModell() {
        Boolean t = testStufenModell;
        if (t != null) return t;
        long jetzt = System.currentTimeMillis();
        if (jetzt - geprueftAm < 60_000L) return zuletzt;
        boolean r = false;
        try {
            r = istStufenModell(at.greenchili.surfmate.config.UnifiedConfigManager.getSelectedVehicleModelId(),
                    at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig().optJSONObject("klima"));
        } catch (Throwable ignored) { }
        zuletzt = r;
        geprueftAm = jetzt;
        return r;
    }

    static boolean istStufenModell(String modellId, org.json.JSONObject klimaKonfig) {
        if (modellId == null) return false;
        String m = modellId.trim().toLowerCase(java.util.Locale.ROOT);
        if (STUFEN_MODELLE.contains(m)) return true;
        org.json.JSONArray extra = klimaKonfig == null ? null : klimaKonfig.optJSONArray("stufenModelle");
        if (extra == null) return false;
        for (int i = 0; i < extra.length(); i++) {
            if (m.equals(extra.optString(i, "").trim().toLowerCase(java.util.Locale.ROOT))) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ Cloud (modellabhängig)

    /** Kleinster/größter SDK-Wert, den der Cloud-Weg ohne Klemmen darstellen kann. */
    public static int cloudSdkMin() { return stufenModell() ? SDK_MIN : 15; }
    public static int cloudSdkMax() { return stufenModell() ? SDK_MAX : 31; }

    /**
     * SDK-Wert → Cloud-Rohwert {@code mainSettingTemp}. Stufen-Modell: Roh = Stufe (17–33 → 1–17, Annahme).
     * Sonst wie bis 0.1.25 (pyBYD): Roh = °C − 14 bei 15–31. Außerhalb: −1.
     */
    public static int cloudRoh(double sdk) {
        if (Double.isNaN(sdk) || Double.isInfinite(sdk)) return -1;
        long w = Math.round(sdk);
        if (w < cloudSdkMin() || w > cloudSdkMax()) return -1;
        return stufenModell() ? (int) (w - SDK_VERSATZ) : (int) (w - 14);
    }

    /** Cloud-Rohwert → SDK-Wert (Gegenstück zu {@link #cloudRoh}), −1 außerhalb 1–17. */
    public static int sdkAusCloudRoh(int roh) {
        if (roh < 1 || roh > 17) return -1;
        return stufenModell() ? roh + SDK_VERSATZ : roh + 14;
    }

    // ------------------------------------------------------------------ Vorklimatisieren (Koordination 28.10.)

    /**
     * Stufe fürs Vorklimatisieren nach der Außentemperatur des Autos (statt fest 22): unter 15 °C Stufe 12
     * (Heizen 3), 15–24 °C Stufe 10 (Heizen 1), über 24 °C Stufe 6 (Kühlen 3), unbekannt Stufe 12.
     */
    public static int vorklimaStufe(double aussenC) {
        if (Double.isNaN(aussenC) || Double.isInfinite(aussenC) || aussenC < -60 || aussenC > 70) return 12;
        if (aussenC < 15) return 12;
        if (aussenC <= 24) return 10;
        return 6;
    }

    /** Wie {@link #vorklimaStufe}, als SDK-Wert. */
    public static int vorklimaSdk(double aussenC) {
        return zuSdk(vorklimaStufe(aussenC));
    }

    /** Vorklima-SDK-Wert aus der aktuellen Außentemperatur des Autos. */
    public static int vorklimaSdkJetzt() {
        double aussen = Double.NaN;
        try {
            BydVehicleData d = BydDataCollector.getInstance().getData();
            if (d != null) aussen = d.outsideTempC;
        } catch (Throwable ignored) { }
        return vorklimaSdk(aussen);
    }

    // ------------------------------------------------------------------ Umrechnung

    /** SDK-Wert (17–33, ganzzahlig gerundet) → Stufe 1–17, sonst −1. */
    public static int ausSdk(double sdk) {
        if (Double.isNaN(sdk) || Double.isInfinite(sdk)) return -1;
        long w = Math.round(sdk);
        return w < SDK_MIN || w > SDK_MAX ? -1 : (int) (w - SDK_VERSATZ);
    }

    /** Stufe 1–17 → SDK-Wert 17–33, sonst −1. */
    public static int zuSdk(int stufe) {
        return gueltig(stufe) ? stufe + SDK_VERSATZ : -1;
    }

    /** Stufe 1–17 → Cloud-Rohwert für {@code mainSettingTemp} (Annahme: Roh = Stufe), sonst −1. */
    public static int zuCloudRoh(int stufe) {
        return gueltig(stufe) ? stufe : -1;
    }

    public static boolean gueltig(int stufe) {
        return stufe >= STUFE_MIN && stufe <= STUFE_MAX;
    }

    /** Ganze SDK-Werte 17–33 (ohne Rundung) – für Eingaben, die exakt sein müssen. */
    public static boolean sdkGanzGueltig(double sdk) {
        return !Double.isNaN(sdk) && !Double.isInfinite(sdk) && sdk == Math.rint(sdk)
                && sdk >= SDK_MIN && sdk <= SDK_MAX;
    }

    /**
     * Sollwert aus einer API-Anfrage, abwärtskompatibel: {@code stufe} (ganze Zahl 1–17, bevorzugt) oder
     * {@code tempKey} (SDK-Skala, ganze Zahl 17–33). Fehlt beides: {@code standardSdk}. Ungültig: NaN.
     * Ergebnis immer in der SDK-Skala.
     */
    public static double sdkAusAnfrage(org.json.JSONObject req, String tempKey, double standardSdk) {
        if (req == null) return standardSdk;
        if (req.has("stufe")) {
            Object s = req.opt("stufe");
            if (!(s instanceof Integer) || !gueltig((Integer) s)) return Double.NaN;
            return zuSdk((Integer) s);
        }
        if (!req.has(tempKey)) return standardSdk;
        Object t = req.opt(tempKey);
        if (!(t instanceof Number)) return Double.NaN;
        double v = ((Number) t).doubleValue();
        return sdkGanzGueltig(v) ? v : Double.NaN;
    }

    // ------------------------------------------------------------------ Anzeige (Eingabe: Stufe 1–17)

    /** heizen | kuehlen | neutral, oder null außerhalb 1–17. */
    public static String art(int stufe) {
        if (!gueltig(stufe)) return null;
        return stufe > STUFE_MITTE ? HEIZEN : stufe < STUFE_MITTE ? KUEHLEN : NEUTRAL;
    }

    /** Zahl im Text („Heizen Stufe n“ / „Kühlen Stufe n“): 1–8, 0 bei Neutral, −1 außerhalb. */
    public static int textStufe(int stufe) {
        return gueltig(stufe) ? Math.abs(stufe - STUFE_MITTE) : -1;
    }

    /** Deutscher Text für Telegram/HA/Protokoll: „Heizen Stufe 4“, „Kühlen Stufe 2“, „Neutral“; außerhalb „unbekannt“. */
    public static String text(int stufe) {
        String a = art(stufe);
        if (a == null) return "unbekannt";
        if (NEUTRAL.equals(a)) return "Neutral";
        return (HEIZEN.equals(a) ? "Heizen" : "Kühlen") + " Stufe " + textStufe(stufe);
    }

    /** Für den Cloud-Weg, solange die Skala nicht belegt ist: nur „Heizen“, „Kühlen“ oder „Neutral“. */
    public static String textOhneStufe(int stufe) {
        String a = art(stufe);
        if (a == null) return "unbekannt";
        return HEIZEN.equals(a) ? "Heizen" : KUEHLEN.equals(a) ? "Kühlen" : "Neutral";
    }

    /** Englisch für die KI-Texte (genai/*): „Heating level 4“, „Cooling level 2“, „Neutral“. */
    public static String textEn(int stufe) {
        String a = art(stufe);
        if (a == null) return "unknown";
        if (NEUTRAL.equals(a)) return "Neutral";
        return (HEIZEN.equals(a) ? "Heating" : "Cooling") + " level " + textStufe(stufe);
    }

    /** Bequem: Text direkt aus einem SDK-Wert (17–33). */
    public static String textAusSdk(double sdk) {
        return text(ausSdk(sdk));
    }

    /**
     * Anzeige für Telegram/HA/Protokoll, modellabhängig: Stufen-Modell „Heizen Stufe 2“, sonst wie bisher „27 °C“.
     */
    public static String anzeige(double sdk) {
        if (stufenModell()) return textAusSdk(sdk);
        return Double.isNaN(sdk) ? "unbekannt" : Math.round(sdk) + " °C";
    }

    /** Wie {@link #anzeige}, englisch (KI-Texte). */
    public static String anzeigeEn(double sdk) {
        if (stufenModell()) return textEn(ausSdk(sdk));
        return Double.isNaN(sdk) ? "unknown" : Math.round(sdk) + " °C";
    }
}
