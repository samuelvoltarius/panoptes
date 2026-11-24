package at.greenchili.surfmate.charging;

import org.json.JSONObject;

import java.util.Locale;

/**
 * 0.1.43.20 „Ersparnis gegenüber Benzin bzw. Diesel“ (Konkurrenz-Check 24.11., Vorbild Yoldaş; Projektinhaber „bau alles andere ein“).
 *
 * <p>Rein: Stromkosten (aus {@link LadeKosten}, also dieselbe Rechnung wie „Ø Kosten je 100 km“) gegen Liter × Spritpreis
 * eines Vergleichsautos. Verbrauch und Spritpreis stellt der Nutzer ein (Abschnitt {@code ersparnis}, NICHT {@code laden},
 * sonst kippt {@code laden.stand} und der Planer bekäme Vorlieben). Keine Live-Preise, kein Netz.
 *
 * <ul>
 *   <li>Standard: Benzin 6,5 l/100 km, Diesel 5,5 l/100 km.</li>
 *   <li>Spritpreis-Richtwert nur für Euro (Benzin 1,65, Diesel 1,60 je Liter); andere Währungen ohne Richtwert,
 *       dann zeigt die Seite „Spritpreis eintragen“ statt einer erfundenen Zahl.</li>
 *   <li>Ohne km (unter 1 km) oder ohne Spritpreis gibt es keine Ersparnis ({@code null}), nie 0.</li>
 *   <li>Fehlen Strompreise ({@code vollstaendig=false}), ist die Ersparnis zu hoch – das wird mitgeliefert.</li>
 * </ul>
 */
public final class Ersparnis {

    private Ersparnis() {}

    public static final String ABSCHNITT = "ersparnis";
    public static final String BENZIN = "benzin", DIESEL = "diesel";
    public static final double VERBRAUCH_BENZIN = 6.5, VERBRAUCH_DIESEL = 5.5;
    public static final double RICHTWERT_BENZIN_EUR = 1.65, RICHTWERT_DIESEL_EUR = 1.60;
    /** Stand der Richtwerte (für die Anzeige „Richtwert, Stand …“). */
    public static final String RICHTWERT_STAND = "2026-11";
    public static final double VERBRAUCH_MIN = 2, VERBRAUCH_MAX = 25;
    /** Je Liter in der Landeswährung: 0,10 … 1000 (auch HUF/CZK/NOK/SEK). */
    public static final double PREIS_MIN = 0.1, PREIS_MAX = 1000;

    /** Die wirksamen Vergleichswerte aus dem Abschnitt {@code ersparnis} plus Richtwerte. */
    public static JSONObject vergleich(JSONObject abschnitt, String waehrung) throws Exception {
        JSONObject a = abschnitt == null ? new JSONObject() : abschnitt;
        String k = DIESEL.equals(a.optString("kraftstoff", "")) ? DIESEL : BENZIN;
        double v = a.optDouble("verbrauch", Double.NaN);
        boolean vEigen = Double.isFinite(v) && v >= VERBRAUCH_MIN && v <= VERBRAUCH_MAX;
        if (!vEigen) v = DIESEL.equals(k) ? VERBRAUCH_DIESEL : VERBRAUCH_BENZIN;
        double p = a.optDouble("preis", Double.NaN);
        boolean pEigen = Double.isFinite(p) && p >= PREIS_MIN && p <= PREIS_MAX;
        boolean euro = istEuro(waehrung);
        JSONObject o = new JSONObject();
        o.put("kraftstoff", k);
        o.put("verbrauch", v);
        o.put("verbrauch_eigen", vEigen);
        if (pEigen) {
            o.put("preis", p);
            o.put("preis_richtwert", false);
            long stand = a.optLong("preisStand", 0);
            o.put("preis_stand", stand > 0 ? (Object) stand : JSONObject.NULL);
        } else if (euro) {
            o.put("preis", DIESEL.equals(k) ? RICHTWERT_DIESEL_EUR : RICHTWERT_BENZIN_EUR);
            o.put("preis_richtwert", true);
            o.put("preis_stand", JSONObject.NULL);
            o.put("richtwert_stand", RICHTWERT_STAND);
        } else {
            o.put("preis", JSONObject.NULL);
            o.put("preis_richtwert", false);
            o.put("preis_stand", JSONObject.NULL);
        }
        o.put("waehrung", waehrung == null || waehrung.isEmpty() ? JSONObject.NULL : waehrung);
        return o;
    }

    static boolean istEuro(String w) {
        if (w == null) return true;   // ohne eingestellte Währung zeigt Panoptes „€“
        String x = w.trim().toUpperCase(Locale.ROOT);
        return x.isEmpty() || "EUR".equals(x) || "€".equals(x);
    }

    /**
     * Ein Vergleich: {@code km} gefahren, {@code strom} = Stromkosten dafür (NaN = unbekannt).
     * Felder sind {@code null}, wenn sie sich nicht ehrlich rechnen lassen.
     */
    public static JSONObject rechne(double km, double strom, boolean stromVollstaendig, JSONObject vergleich) throws Exception {
        JSONObject o = new JSONObject();
        boolean mitKm = Double.isFinite(km) && km >= 1;
        double v = vergleich == null ? Double.NaN : vergleich.optDouble("verbrauch", Double.NaN);
        double p = vergleich == null || vergleich.isNull("preis") ? Double.NaN : vergleich.optDouble("preis", Double.NaN);
        boolean mitStrom = Double.isFinite(strom) && strom >= 0;
        o.put("km", mitKm ? (Object) runde(km, 1) : JSONObject.NULL);
        o.put("strom_kosten", mitStrom ? (Object) runde(strom, 2) : JSONObject.NULL);
        o.put("strom_vollstaendig", mitStrom && stromVollstaendig);
        if (!mitKm || !Double.isFinite(v) || v <= 0) {
            o.put("liter", JSONObject.NULL);
            o.put("sprit_kosten", JSONObject.NULL);
            o.put("ersparnis", JSONObject.NULL);
            o.put("ersparnis_je_100km", JSONObject.NULL);
            o.put("grund", mitKm ? "verbrauch" : "zu_wenig_km");
            return o;
        }
        double liter = km * v / 100.0;
        o.put("liter", runde(liter, 1));
        if (!Double.isFinite(p) || p <= 0) {
            o.put("sprit_kosten", JSONObject.NULL);
            o.put("ersparnis", JSONObject.NULL);
            o.put("ersparnis_je_100km", JSONObject.NULL);
            o.put("grund", "preis_fehlt");
            return o;
        }
        double sprit = liter * p;
        o.put("sprit_kosten", runde(sprit, 2));
        if (!mitStrom) {
            o.put("ersparnis", JSONObject.NULL);
            o.put("ersparnis_je_100km", JSONObject.NULL);
            o.put("grund", "strom_fehlt");
            return o;
        }
        double e = sprit - strom;
        o.put("ersparnis", runde(e, 2));
        o.put("ersparnis_je_100km", runde(e / km * 100.0, 2));
        o.put("grund", stromVollstaendig ? JSONObject.NULL : "strom_unvollstaendig");
        return o;
    }

    /**
     * Zeitraum aus der {@link LadeKosten#kosten}-Antwort (abgerechnete Kosten wie die Kachel „Ø Kosten“).
     * Hat der Zeitraum keine Ladung mit Preis (z. B. Monatsanfang, geladen wurde letzten Monat), rechnet er mit dem
     * Ø-Strompreis je km aus {@code gesamt} ({@code basis = "durchschnitt"}); fehlt auch der, bleibt Strom unbekannt (nie 0).
     */
    public static JSONObject ausKosten(JSONObject kosten, JSONObject gesamt, JSONObject vergleich) throws Exception {
        double km = kosten.optDouble("km", 0);
        boolean abg = kosten.has("kosten_summe_abgerechnet");
        double strom = kosten.optDouble(abg ? "kosten_summe_abgerechnet" : "kosten_summe", Double.NaN);
        boolean voll = kosten.optBoolean(abg ? "vollstaendig_abgerechnet" : "vollstaendig", false);
        int n = kosten.optInt("ladungen", 0);
        int ohne = kosten.optInt(abg ? "ladungen_ohne_preis_abgerechnet" : "ladungen_ohne_preis", 0);
        String basis = "zeitraum";
        if (n == 0 || ohne >= n) {
            strom = Double.NaN;
            basis = null;
            if (gesamt != null) {
                boolean ga = gesamt.has("kosten_je_100km_abgerechnet");
                String f = ga ? "kosten_je_100km_abgerechnet" : "kosten_je_100km";
                int gn = gesamt.optInt("ladungen", 0);
                int gohne = gesamt.optInt(ga ? "ladungen_ohne_preis_abgerechnet" : "ladungen_ohne_preis", 0);
                double k100 = gesamt.isNull(f) ? Double.NaN : gesamt.optDouble(f, Double.NaN);
                if (gn > gohne && Double.isFinite(k100) && k100 >= 0 && Double.isFinite(km)) {
                    strom = km * k100 / 100.0;
                    voll = gesamt.optBoolean(ga ? "vollstaendig_abgerechnet" : "vollstaendig", false);
                    basis = "durchschnitt";
                }
            }
        }
        JSONObject o = rechne(km, strom, voll, vergleich);
        o.put("zeitraum", kosten.opt("zeitraum"));
        o.put("von", kosten.opt("von"));
        o.put("bis", kosten.opt("bis"));
        o.put("ladungen", n);
        o.put("basis", basis == null ? JSONObject.NULL : basis);
        return o;
    }

    /**
     * Neue Vergleichswerte prüfen; {@code null} = ungültig (Grund in {@code fehler[0]}).
     * Ein neuer Preis bekommt {@code preisStand = jetzt}; {@code preis:null} löscht ihn (zurück zum Richtwert).
     */
    public static java.util.Map<String, Object> pruefe(JSONObject req, JSONObject alt, long jetzt, String[] fehler) {
        java.util.Map<String, Object> w = new java.util.LinkedHashMap<>();
        JSONObject a = alt == null ? new JSONObject() : alt;
        if (req.has("kraftstoff")) {
            String k = req.optString("kraftstoff", "");
            if (!BENZIN.equals(k) && !DIESEL.equals(k)) { fehler[0] = "kraftstoff"; return null; }
            w.put("kraftstoff", k);
        }
        if (req.has("verbrauch")) {
            if (req.isNull("verbrauch")) w.put("verbrauch", JSONObject.NULL);
            else {
                double v = req.optDouble("verbrauch", Double.NaN);
                if (!Double.isFinite(v) || v < VERBRAUCH_MIN || v > VERBRAUCH_MAX) { fehler[0] = "verbrauch"; return null; }
                w.put("verbrauch", runde(v, 1));
            }
        }
        if (req.has("preis")) {
            if (req.isNull("preis")) {
                w.put("preis", JSONObject.NULL);
                w.put("preisStand", JSONObject.NULL);
            } else {
                double p = req.optDouble("preis", Double.NaN);
                if (!Double.isFinite(p) || p < PREIS_MIN || p > PREIS_MAX) { fehler[0] = "preis"; return null; }
                p = runde(p, 3);
                w.put("preis", p);
                double vorher = a.optDouble("preis", Double.NaN);
                if (!(Double.isFinite(vorher) && Math.abs(vorher - p) < 1e-9) || a.optLong("preisStand", 0) <= 0) {
                    w.put("preisStand", jetzt);
                }
            }
        }
        if (w.isEmpty()) { fehler[0] = "leer"; return null; }
        return w;
    }

    static double runde(double v, int stellen) {
        double f = Math.pow(10, stellen);
        return Math.round(v * f) / f;
    }
}
