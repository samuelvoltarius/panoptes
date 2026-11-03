package at.greenchili.surfmate.charging;

import org.json.JSONObject;

/**
 * Was die Säule abrechnet, nicht nur was im Akku ankommt (Projektinhaber über die Koordination, 03.11.2026; Anlass: DC-Ladung
 * 25,5 kWh im Akku, ca. 28 kWh abgerechnet). Panoptes misst die Energie am Akku (Zähler, Integral oder SoC × Kapazität,
 * siehe {@link SessionEnergyResolver}); die Säule zählt davor, also mit Ladeverlust.
 *
 * <p>Rein und nur für Anzeige und Statistik: Gespeicherte Werte ({@code energy_added_kwh}, {@code session_cost},
 * Tagesrollup) bleiben unverändert. Reihenfolge:
 * <ol>
 *   <li>Hat der Nutzer die kWh laut Säule (und optional den Betrag) eingetragen, gelten diese.</li>
 *   <li>Sonst Akku-kWh × (1 + Zuschlag), Zuschlag je AC und DC einstellbar ({@code laden.verlustAc},
 *       {@code laden.verlustDc}, Prozent), mit Vorschlagswerten. Das Ergebnis ist geschätzt („~“).</li>
 * </ol>
 * Kosten ohne eingetragenen Betrag: gespeicherte Kosten × abgerechnete kWh ÷ Akku-kWh, also derselbe Preis je kWh,
 * nur auf die größere Menge. „Preis fehlt“ bleibt „Preis fehlt“, außer der Nutzer trägt einen Betrag ein.
 */
public final class LadeAbrechnung {

    private LadeAbrechnung() {}

    /** Vorschlag Ladeverlust AC in Prozent (Bordlader, Kabel, Kühlung; bei kleiner Leistung eher mehr). */
    public static final int VORSCHLAG_AC = 12;
    /** Vorschlag Ladeverlust DC in Prozent (Zähler der Säule bis Akku). */
    public static final int VORSCHLAG_DC = 8;
    public static final int MAX_PROZENT = 40;
    /** Obergrenze für eine eingetragene Menge bzw. einen Betrag (Tippfehler abfangen, nicht Physik prüfen). */
    public static final double MAX_KWH = SessionEnergyResolver.MAX_SESSION_KWH;
    public static final double MAX_BETRAG = 10_000.0;

    public static final String QUELLE_SAEULE = "saeule";
    public static final String QUELLE_ZUSCHLAG = "zuschlag";

    /** Zuschlag in Prozent für diese Ladeart; {@code isDc} null = unbekannt → AC-Wert. */
    public static int zuschlagProzent(JSONObject cfg, Boolean isDc) {
        boolean dc = Boolean.TRUE.equals(isDc);
        JSONObject l = cfg == null ? null : cfg.optJSONObject("laden");
        String key = dc ? "verlustDc" : "verlustAc";
        int std = dc ? VORSCHLAG_DC : VORSCHLAG_AC;
        if (l == null || !l.has(key) || l.isNull(key)) return std;
        double d = l.optDouble(key, Double.NaN);
        if (!Double.isFinite(d)) return std;
        return (int) Math.round(Math.max(0, Math.min(MAX_PROZENT, d)));
    }

    /** Gültige eingetragene Menge (kWh) oder NaN. */
    public static double gueltigeKwh(double v) {
        return Double.isFinite(v) && v > 0 && v <= MAX_KWH ? v : Double.NaN;
    }

    /** Gültiger eingetragener Betrag (0 = gratis) oder NaN. */
    public static double gueltigerBetrag(double v) {
        return Double.isFinite(v) && v >= 0 && v <= MAX_BETRAG ? v : Double.NaN;
    }

    /** Ergebnis für eine Ladung. */
    public static final class Ergebnis {
        /** Abgerechnete kWh (eingetragen oder geschätzt), NaN ohne Akku-Energie und ohne Eintrag. */
        public final double kwh;
        public final boolean kwhGeschaetzt;
        /** Kosten zur abgerechneten Menge, NaN = Preis unbekannt. */
        public final double kosten;
        public final boolean kostenGeschaetzt;
        /** {@link #QUELLE_SAEULE} oder {@link #QUELLE_ZUSCHLAG}, "" ohne Ergebnis. */
        public final String quelle;
        /** Verwendeter Zuschlag in Prozent (bei {@link #QUELLE_SAEULE}: der tatsächliche Unterschied, gerundet). */
        public final double verlustProzent;

        Ergebnis(double kwh, boolean kwhGeschaetzt, double kosten, boolean kostenGeschaetzt, String quelle,
                 double verlustProzent) {
            this.kwh = kwh;
            this.kwhGeschaetzt = kwhGeschaetzt;
            this.kosten = kosten;
            this.kostenGeschaetzt = kostenGeschaetzt;
            this.quelle = quelle;
            this.verlustProzent = verlustProzent;
        }
    }

    /**
     * @param akkuKwh     gespeicherte Energie in den Akku
     * @param akkuKosten  gespeicherte Kosten (&lt; 0 oder NaN = Preis fehlt)
     * @param saeuleKwh   eingetragen laut Säule, NaN = nicht eingetragen
     * @param saeuleBetrag eingetragen laut Säule, NaN = nicht eingetragen
     */
    public static Ergebnis berechne(double akkuKwh, double akkuKosten, Boolean isDc, double saeuleKwh,
                                    double saeuleBetrag, JSONObject cfg) {
        double akku = Double.isFinite(akkuKwh) && akkuKwh > 0 ? akkuKwh : Double.NaN;
        double sKwh = gueltigeKwh(saeuleKwh);
        double sBetrag = gueltigerBetrag(saeuleBetrag);
        double kwh;
        boolean kwhGeschaetzt;
        String quelle;
        double verlust;
        if (!Double.isNaN(sKwh)) {
            kwh = sKwh;
            kwhGeschaetzt = false;
            quelle = QUELLE_SAEULE;
            verlust = Double.isNaN(akku) ? Double.NaN : Math.round((sKwh / akku - 1) * 1000) / 10.0;
        } else if (!Double.isNaN(akku)) {
            int z = zuschlagProzent(cfg, isDc);
            kwh = akku * (1 + z / 100.0);
            kwhGeschaetzt = true;
            quelle = QUELLE_ZUSCHLAG;
            verlust = z;
        } else {
            kwh = Double.NaN;
            kwhGeschaetzt = false;
            quelle = "";
            verlust = Double.NaN;
        }
        double kosten;
        boolean kostenGeschaetzt;
        if (!Double.isNaN(sBetrag)) {
            kosten = sBetrag;
            kostenGeschaetzt = false;
            if (quelle.isEmpty()) quelle = QUELLE_SAEULE;
        } else if (Double.isFinite(akkuKosten) && akkuKosten >= 0 && !Double.isNaN(akku) && !Double.isNaN(kwh)) {
            kosten = akkuKosten * kwh / akku;
            kostenGeschaetzt = kwhGeschaetzt;
        } else {
            kosten = Double.NaN;
            kostenGeschaetzt = false;
        }
        return new Ergebnis(kwh, kwhGeschaetzt, kosten, kostenGeschaetzt, quelle, verlust);
    }

    /**
     * Preis je Akku-kWh nach derselben Regel (für Fahrtkosten: die Fahrt verbraucht Akku-kWh, bezahlt wurde die
     * abgerechnete Menge). {@code rate} = gespeicherter Preis je kWh der Ladung. Ohne Akku-Menge: rate × (1 + Zuschlag),
     * außer es ist ein Betrag eingetragen (dann ohne Menge nicht umrechenbar: rate unverändert). NaN bei ungültiger rate.
     */
    public static double preisJeAkkuKwh(double rate, double akkuKwh, Boolean isDc, double saeuleKwh,
                                        double saeuleBetrag, JSONObject cfg) {
        if (!Double.isFinite(rate) || rate < 0) return Double.NaN;
        if (Double.isFinite(akkuKwh) && akkuKwh > 0) {
            Ergebnis e = berechne(akkuKwh, rate * akkuKwh, isDc, saeuleKwh, saeuleBetrag, cfg);
            return Double.isNaN(e.kosten) ? rate : e.kosten / akkuKwh;
        }
        if (!Double.isNaN(gueltigerBetrag(saeuleBetrag))) return rate;
        double sk = gueltigeKwh(saeuleKwh);
        if (!Double.isNaN(sk)) return rate;   // Menge laut Säule, aber Akku-Menge unbekannt: nicht umrechenbar
        return rate * (1 + zuschlagProzent(cfg, isDc) / 100.0);
    }

    /**
     * Ergänzt eine Sitzung (JSON wie /api/charging) um die abgerechneten Werte. Ändert keine vorhandenen Felder.
     * Felder: {@code kwhAkku}, {@code kwhAbgerechnet}, {@code kwhAbgerechnetGeschaetzt}, {@code kostenAbgerechnet},
     * {@code kostenAbgerechnetGeschaetzt}, {@code abrechnungQuelle}, {@code ladeverlustProzent}, {@code saeuleKwh},
     * {@code saeuleBetrag}.
     */
    public static void anreichern(JSONObject o, double saeuleKwh, double saeuleBetrag, JSONObject cfg) {
        try {
            if (o == null) return;
            double akku = o.isNull("energyAdded") ? Double.NaN : o.optDouble("energyAdded", Double.NaN);
            double kosten = o.isNull("cost") ? Double.NaN : o.optDouble("cost", Double.NaN);
            if (o.optBoolean("preisFehlt", false)) kosten = Double.NaN;
            Boolean dc = o.isNull("isDc") || !o.has("isDc") ? null : o.optBoolean("isDc");
            Ergebnis e = berechne(akku, kosten, dc, saeuleKwh, saeuleBetrag, cfg);
            o.put("kwhAkku", Double.isFinite(akku) && akku > 0 ? (Object) runde(akku, 100) : JSONObject.NULL);
            o.put("kwhAbgerechnet", Double.isNaN(e.kwh) ? JSONObject.NULL : (Object) runde(e.kwh, 100));
            o.put("kwhAbgerechnetGeschaetzt", e.kwhGeschaetzt);
            o.put("kostenAbgerechnet", Double.isNaN(e.kosten) ? JSONObject.NULL : (Object) runde(e.kosten, 100));
            o.put("kostenAbgerechnetGeschaetzt", e.kostenGeschaetzt);
            o.put("abrechnungQuelle", e.quelle);
            o.put("ladeverlustProzent", Double.isNaN(e.verlustProzent) ? JSONObject.NULL : (Object) e.verlustProzent);
            double sk = gueltigeKwh(saeuleKwh), sb = gueltigerBetrag(saeuleBetrag);
            o.put("saeuleKwh", Double.isNaN(sk) ? JSONObject.NULL : (Object) sk);
            o.put("saeuleBetrag", Double.isNaN(sb) ? JSONObject.NULL : (Object) sb);
        } catch (Exception ignored) {
            // Anzeige-Zusatz: ein Fehler darf die Sitzung nie verschlucken
        }
    }

    private static double runde(double v, double f) {
        return Math.round(v * f) / f;
    }
}
