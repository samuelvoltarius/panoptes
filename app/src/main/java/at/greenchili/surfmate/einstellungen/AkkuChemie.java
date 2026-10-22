package at.greenchili.surfmate.einstellungen;

import org.json.JSONObject;

import java.util.Iterator;

/**
 * Chemie der 12-V-Starterbatterie für die Standard-Schwellen des Wächter-Stopps.
 *
 * <p>0.1.11 (Koordination 22.10.2026, Regel 1 – keine Annahme über ein bestimmtes Auto):
 * <ol>
 *   <li>Einstellung {@code auto.akkuChemie} (lfp|blei|agm) → Quelle {@code nutzer}</li>
 *   <li>Fahrzeugprofil/Katalog: Feld {@code v12Chemie} des gewählten Modells, nur belegte Einträge → {@code profil}</li>
 *   <li>Heuristik: Ruhespannung ≥ 13,0 V nach ≥ 60 min Parken an mindestens 3 Tagen → {@code lfp} (vermutet),
 *       sonst {@code blei} (vermutet) → Quelle {@code vermutet}</li>
 * </ol>
 * Übergang (Koordination 22.10.): Solange die Ruhetage weder LFP (3 Tage ≥ 13,0 V) noch Blei (3 Tage &lt; 12,9 V)
 * belegen, zählen die bisherigen LFP-Hinweise weiter – aktueller Ruhe-Median ≥ 13,0 V oder Plattform DiLink 3 –,
 * damit niemand still von 12,8 auf 12,2 V fällt. Ein vom Nutzer gesetzter Schwellwert
 * ({@code guardStop.v12Below/v12Resume}) geht immer vor (GuardStopPolicy.Config.from).
 */
public final class AkkuChemie {

    public static final String LFP = "lfp";
    public static final String BLEI = "blei";
    public static final String AGM = "agm";
    public static final String AUTO = "auto";

    public static final String QUELLE_NUTZER = "nutzer";
    public static final String QUELLE_PROFIL = "profil";
    public static final String QUELLE_VERMUTET = "vermutet";

    /** Heuristik: so viele Tage mit Ruhespannung ≥ {@link #LFP_RUHE_V} → LFP vermutet. */
    public static final int LFP_TAGE = 3;
    public static final double LFP_RUHE_V = 13.0;
    /** Belegt Blei: so viele Ruhetage unter dieser Spannung (und keine LFP-Belege). */
    public static final double BLEI_RUHE_V = 12.9;
    /** Ruhe gilt erst nach so langer Standzeit. */
    public static final long RUHE_NACH_MS = 60 * 60_000L;

    /**
     * Schwellen als Daten (Koordination 22.10.): {stopp unter, wieder ab} in Volt je Chemie.
     * LFP 12,8 / 13,1 · Blei und AGM 12,2 / 12,5.
     */
    static final String SCHWELLEN_JSON = "{\"lfp\":[12.8,13.1],\"blei\":[12.2,12.5],\"agm\":[12.2,12.5]}";

    private AkkuChemie() {}

    /** Ergebnis mit Quelle. */
    public static final class Ergebnis {
        public final String chemie, quelle;
        /** Bei {@code vermutet}: ruhetage_lfp | ruhetage_blei | live_median | plattform | keine_hinweise; sonst leer. */
        public final String grund;

        Ergebnis(String chemie, String quelle) {
            this(chemie, quelle, "");
        }

        Ergebnis(String chemie, String quelle, String grund) {
            this.chemie = chemie;
            this.quelle = quelle;
            this.grund = grund;
        }
    }

    static boolean gueltig(String c) {
        return LFP.equals(c) || BLEI.equals(c) || AGM.equals(c);
    }

    /**
     * @param einstellung {@code auto.akkuChemie} (auto|lfp|blei|agm|null)
     * @param profil      {@code v12Chemie} aus dem Fahrzeugprofil/Katalog (null, wenn nicht belegt)
     * @param ruheTage    {Tag: Ruhespannung} (siehe {@link #ruheTagEintragen}); null = keine Messungen
     */
    public static Ergebnis ermitteln(String einstellung, String profil, JSONObject ruheTage) {
        return ermitteln(einstellung, profil, ruheTage, Double.NaN, false);
    }

    /**
     * @param liveMedian aktueller Ruhe-Median der 12-V-Auswertung (NaN = unbekannt bzw. lädt)
     * @param diLink3    Plattform DiLink 3 (früherer Hinweis auf LFP, nur noch Übergang)
     */
    public static Ergebnis ermitteln(String einstellung, String profil, JSONObject ruheTage,
                                     double liveMedian, boolean diLink3) {
        if (gueltig(einstellung)) return new Ergebnis(einstellung, QUELLE_NUTZER);
        if (gueltig(profil)) return new Ergebnis(profil, QUELLE_PROFIL);
        if (tageAb(ruheTage, LFP_RUHE_V) >= LFP_TAGE) return new Ergebnis(LFP, QUELLE_VERMUTET, "ruhetage_lfp");
        if (tageUnter(ruheTage, BLEI_RUHE_V) >= LFP_TAGE) return new Ergebnis(BLEI, QUELLE_VERMUTET, "ruhetage_blei");
        // Übergang: keine belegten Ruhetage – bisherige LFP-Hinweise gelten weiter (nie still auf 12,2 V)
        if (!Double.isNaN(liveMedian) && liveMedian >= LFP_RUHE_V) return new Ergebnis(LFP, QUELLE_VERMUTET, "live_median");
        if (diLink3) return new Ergebnis(LFP, QUELLE_VERMUTET, "plattform");
        return new Ergebnis(BLEI, QUELLE_VERMUTET, "keine_hinweise");
    }

    /** Anzahl Tage, deren Ruhespannung unter {@code grenze} lag. */
    public static int tageUnter(JSONObject tage, double grenze) {
        if (tage == null) return 0;
        int n = 0;
        Iterator<String> it = tage.keys();
        while (it.hasNext()) {
            double v = tage.optDouble(it.next(), Double.NaN);
            if (!Double.isNaN(v) && v < grenze) n++;
        }
        return n;
    }

    /** {stopp unter, wieder ab} in Volt. Unbekannte Chemie = Blei. */
    public static double[] v12Standard(String chemie) {
        try {
            JSONObject t = new JSONObject(SCHWELLEN_JSON);
            org.json.JSONArray a = t.optJSONArray(gueltig(chemie) ? chemie : BLEI);
            return new double[] {a.getDouble(0), a.getDouble(1)};
        } catch (Exception e) {
            return new double[] {12.2, 12.5};
        }
    }

    // ------------------------------------------------------------------ Ruhetage

    /** Anzahl Tage, deren Ruhespannung mindestens {@code grenze} war. */
    public static int tageAb(JSONObject tage, double grenze) {
        if (tage == null) return 0;
        int n = 0;
        Iterator<String> it = tage.keys();
        while (it.hasNext()) if (tage.optDouble(it.next(), 0) >= grenze) n++;
        return n;
    }

    /**
     * Trägt eine Ruhemessung ein (je Tag der KLEINSTE Wert – ein Tag zählt nur als „≥ 13,0 V“, wenn die Ruhe den
     * ganzen Tag so hoch war) und behält höchstens {@code behalten} Tage (die neuesten).
     *
     * @return true, wenn sich etwas geändert hat (dann speichern)
     */
    public static boolean ruheTagEintragen(JSONObject tage, String tag, double volt, int behalten) throws Exception {
        if (!(volt > 5 && volt < 20)) return false;
        double alt = tage.optDouble(tag, Double.NaN);
        if (!Double.isNaN(alt) && alt <= volt) return false;
        tage.put(tag, Math.round(volt * 100) / 100.0);
        while (tage.length() > behalten) {
            String aeltester = null;
            Iterator<String> it = tage.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (aeltester == null || k.compareTo(aeltester) < 0) aeltester = k;
            }
            tage.remove(aeltester);
        }
        return true;
    }

    // ------------------------------------------------------------------ Laufzeit

    /** Laufzeit im Daemon (Chemie + Quelle). */
    public static Ergebnis aktuellMitQuelle(JSONObject root) {
        String e = null, profil = null;
        try {
            JSONObject a = root == null ? null : root.optJSONObject("auto");
            if (a != null) e = a.optString("akkuChemie", null);
        } catch (Throwable ignored) {
        }
        try {
            JSONObject v = root == null ? null : root.optJSONObject("vehicle");
            String mid = v == null ? "" : v.optString("modelId", "");
            JSONObject m = at.greenchili.surfmate.server.ModelsApiHandler.modellEintrag(mid);
            if (m != null) profil = m.optString("v12Chemie", null);
        } catch (Throwable ignored) {
        }
        JSONObject tage = null;
        double median = Double.NaN;
        boolean di3 = false;
        try {
            tage = at.greenchili.surfmate.surveillance.GuardStopMonitor.ruheTage();
            median = at.greenchili.surfmate.surveillance.GuardStopMonitor.ruheMedian12v();
            di3 = at.greenchili.surfmate.surveillance.GuardStopMonitor.istDiLink3();
        } catch (Throwable ignored) {
        }
        return ermitteln(e, profil, tage, median, di3);
    }

    /** Laufzeit im Daemon (nur Chemie, wie bisher). */
    public static String aktuell(JSONObject root) {
        return aktuellMitQuelle(root).chemie;
    }
}
