package at.greenchili.surfmate.camping;

import org.json.JSONObject;

/**
 * Lernen aus „zu kalt / passt / zu warm“ (CAMPING_PLAN.md 2.1) – nur lokal im Auto, nie an einen Server.
 *
 * <p>Je Außentemperatur-Band (&lt; −5, −5…5, 5…12, &gt; 12 °C) eine Verschiebung in K, mit der die Heiztabelle
 * nachgeschlagen wird: „zu kalt“ −1,5 K (mehr heizen), „zu warm“ +1,5 K. Grenzen ±8 K, höchstens eine
 * Änderung je 30 min, gilt ab dem nächsten Zyklus.
 */
public final class HeizLernen {

    public static final String ZU_KALT = "zu_kalt", PASST = "passt", ZU_WARM = "zu_warm";
    public static final double SCHRITT_K = 1.5, GRENZE_K = 8.0;
    public static final long SPERRE_MS = 30 * 60_000L;

    private static final String[] BAENDER = {"unter_minus5", "minus5_bis_5", "5_bis_12", "ueber_12"};

    private final double[] verschiebung = new double[BAENDER.length];
    private final int[] passt = new int[BAENDER.length];
    private long letzteAenderungMs;

    public static int band(double aussenC) {
        if (Double.isNaN(aussenC)) return 1;
        if (aussenC < -5) return 0;
        if (aussenC < 5) return 1;
        if (aussenC <= 12) return 2;
        return 3;
    }

    public double verschiebungK(double aussenC) {
        return verschiebung[band(aussenC)];
    }

    /**
     * Rückmeldung verbuchen.
     *
     * @return true, wenn sich die Verschiebung geändert hat (bzw. „passt“ gezählt wurde); false bei Sperre
     *         oder unbekannter Art
     */
    public synchronized boolean rueckmeldung(String art, double aussenC, long jetztMs) {
        int b = band(aussenC);
        if (PASST.equals(art)) {
            passt[b]++;
            return true;
        }
        double d;
        if (ZU_KALT.equals(art)) d = -SCHRITT_K;
        else if (ZU_WARM.equals(art)) d = SCHRITT_K;
        else return false;
        if (letzteAenderungMs > 0 && jetztMs - letzteAenderungMs < SPERRE_MS && jetztMs >= letzteAenderungMs) {
            return false;
        }
        double neu = Math.max(-GRENZE_K, Math.min(GRENZE_K, verschiebung[b] + d));
        if (neu == verschiebung[b]) return false;
        verschiebung[b] = neu;
        letzteAenderungMs = jetztMs;
        return true;
    }

    public synchronized void zuruecksetzen() {
        java.util.Arrays.fill(verschiebung, 0);
        java.util.Arrays.fill(passt, 0);
        letzteAenderungMs = 0;
    }

    public synchronized JSONObject alsJson() {
        JSONObject o = new JSONObject();
        try {
            JSONObject v = new JSONObject(), p = new JSONObject();
            for (int i = 0; i < BAENDER.length; i++) {
                v.put(BAENDER[i], verschiebung[i]);
                p.put(BAENDER[i], passt[i]);
            }
            o.put("verschiebungK", v).put("passt", p).put("letzteAenderungMs", letzteAenderungMs);
        } catch (Exception ignored) { }
        return o;
    }

    public static HeizLernen aus(JSONObject o) {
        HeizLernen l = new HeizLernen();
        if (o == null) return l;
        JSONObject v = o.optJSONObject("verschiebungK"), p = o.optJSONObject("passt");
        for (int i = 0; i < BAENDER.length; i++) {
            if (v != null) {
                double d = v.optDouble(BAENDER[i], 0);
                l.verschiebung[i] = Double.isNaN(d) ? 0 : Math.max(-GRENZE_K, Math.min(GRENZE_K, d));
            }
            if (p != null) l.passt[i] = Math.max(0, p.optInt(BAENDER[i], 0));
        }
        l.letzteAenderungMs = o.optLong("letzteAenderungMs", 0);
        return l;
    }
}
