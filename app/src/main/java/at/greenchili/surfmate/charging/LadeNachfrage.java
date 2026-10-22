package at.greenchili.surfmate.charging;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * „Wo hast du geladen?“ (LADENACHFRAGE_VERTRAG.md, Kern 5, 0.1.10; Koordination: freigegeben 22.10.).
 *
 * <ul>
 *   <li>Beim Schließen einer Ladung ohne passenden Tarif: kein stiller Ersatzwert mehr ({@link #ohneTarif}). AC-Ladungen
 *       bekommen {@code laden.acStandard}, wenn gesetzt (z. B. 0 = gratis); sonst „Preis fehlt“ und die Nachfrage.</li>
 *   <li>Herkunft und Nachfrage-Stand werden aus den gespeicherten Feldern abgeleitet ({@link #quelle}, {@link #offen});
 *       nur der ausdrückliche Preis-Weg ({@link LadePreis}) schreibt {@code preis_quelle}/{@code nachfrage}.</li>
 *   <li>Vorschläge nur aus Daten im Auto: eigene Ortskreise in der Nähe, Stopps der zuletzt angenommenen Planer-Route.</li>
 * </ul>
 * Nie während der Fahrt anzeigen ({@code jetztZeigen}), kein Ton, kein Popup – das regelt die Oberfläche.
 */
public final class LadeNachfrage {

    private LadeNachfrage() {}

    /** Ab hier gilt die Nachfrage (Panoptes-Zeit am Auto; ältere Ladungen sind Overdrive-Zeit bzw. Bestand). */
    public static final long SEIT = 1792454400000L;   // 2026-10-20T00:00:00Z (LadeNachfrageTest prüft das Datum)
    public static final String STANDARD_LABEL = "Standard (AC)";
    static final int NAEHE_M = 300;

    /** Ergebnis für eine Ladung ohne Tarif: Satz (-1 = Preis fehlt), gratis, Anzeigename. */
    public static final class Entscheid {
        public final double rate;
        public final boolean gratis;
        public final String label;
        Entscheid(double rate, boolean gratis, String label) { this.rate = rate; this.gratis = gratis; this.label = label; }
    }

    public static boolean nachfragenAn(JSONObject cfg) {
        JSONObject l = cfg == null ? null : cfg.optJSONObject("laden");
        return l == null || !l.has("nachfragen") || l.isNull("nachfragen") || l.optBoolean("nachfragen", true);
    }

    /** {@code laden.acStandard} (≥ 0) oder NaN, wenn nicht gesetzt. */
    public static double acStandard(JSONObject cfg) {
        JSONObject l = cfg == null ? null : cfg.optJSONObject("laden");
        if (l == null || !l.has("acStandard") || l.isNull("acStandard")) return Double.NaN;
        double d = l.optDouble("acStandard", Double.NaN);
        return Double.isFinite(d) && d >= 0 ? d : Double.NaN;
    }

    /** Preis einer Ladung, für die kein Tarif passt. {@code globalRate} = bisheriger Ersatzwert. */
    public static Entscheid ohneTarif(JSONObject cfg, int isDc, double globalRate) {
        if (!nachfragenAn(cfg)) return new Entscheid(globalRate, false, "");   // abgeschaltet: altes Verhalten
        double std = acStandard(cfg);
        if (isDc != 1 && !Double.isNaN(std)) return new Entscheid(std, std == 0, STANDARD_LABEL);
        return new Entscheid(-1, false, "");
    }

    /** Herkunft des Preises einer gespeicherten Ladung. */
    public static String quelle(JSONObject z) {
        String q = z.optString("quelle", "");
        if (!q.isEmpty()) return q;
        if (!z.optString("tariffId", "").isEmpty()) return "tarif";
        if (z.optDouble("rate", -1) < 0) return "fehlt";
        if (STANDARD_LABEL.equals(z.optString("tariffLabel", ""))) return "standard";
        return "ersatz";
    }

    /** Gehört diese Ladung in die Nachfrage? (abgeschlossen, seit {@link #SEIT}, Preis fehlt bzw. Ersatzwert, nicht beantwortet) */
    public static boolean offen(JSONObject z, JSONObject cfg) {
        if (!nachfragenAn(cfg)) return false;
        if (z.isNull("ende") || z.optLong("start", 0) < SEIT) return false;
        String n = z.optString("nachfrage", "");
        if ("erledigt".equals(n)) return false;
        String q = quelle(z);
        return "fehlt".equals(q) || "ersatz".equals(q);
    }

    /**
     * Antwort für {@code GET /api/laden/nachfrage}. Ohne Koordinaten (auch lokal), der Ort nur als Name.
     *
     * @param zeilen    Ladungen (LadePreis.zeile), neueste zuerst
     * @param tarife    Ortskreise ({@link TariffProfile#toJson()})
     * @param stopps    Stopps der angenommenen Planer-Route ({name, lat, lng, cpo?}) oder null
     * @param steht     Auto steht (Gang P, keine Fahrt)
     */
    public static JSONObject liste(JSONArray zeilen, JSONObject cfg, JSONArray tarife, JSONArray stopps, boolean steht)
            throws Exception {
        JSONArray offen = new JSONArray();
        int spaeter = 0;
        for (int i = 0; zeilen != null && i < zeilen.length(); i++) {
            JSONObject z = zeilen.optJSONObject(i);
            if (z == null || !offen(z, cfg)) continue;
            if ("spaeter".equals(z.optString("nachfrage"))) spaeter++;
            offen.put(eintrag(z, cfg, tarife, stopps));
        }
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("an", nachfragenAn(cfg));
        o.put("anzahl", offen.length());
        o.put("spaeter", spaeter);
        o.put("jetztZeigen", steht && offen.length() > spaeter);
        o.put("offen", offen);
        return o;
    }

    static JSONObject eintrag(JSONObject z, JSONObject cfg, JSONArray tarife, JSONArray stopps) throws Exception {
        JSONObject e = new JSONObject();
        e.put("id", z.getLong("id"));
        e.put("start", z.optLong("start"));
        e.put("ende", z.opt("ende"));
        e.put("kwh", Math.round(z.optDouble("kwh", 0) * 100) / 100.0);
        e.put("dc", z.opt("dc"));
        e.put("quelle", quelle(z));
        e.put("spaeter", "spaeter".equals(z.optString("nachfrage")));
        if ("ersatz".equals(quelle(z))) e.put("bisherSatz", z.optDouble("rate"));   // alter Ersatzwert, zur Info
        double lat = z.optDouble("lat", 0), lng = z.optDouble("lng", 0);
        boolean pos = !(lat == 0 && lng == 0);
        JSONArray vorschlaege = new JSONArray();
        String ortName = null;
        if (pos) {
            for (int i = 0; tarife != null && i < tarife.length(); i++) {
                JSONObject t = tarife.optJSONObject(i);
                if (t == null) continue;
                double d = meter(lat, lng, t.optDouble("lat"), t.optDouble("lng"));
                if (d > t.optInt("radiusM", 50) + NAEHE_M) continue;
                TariffProfile p = new TariffProfile(t);
                int dc = z.isNull("dc") ? -1 : (z.optBoolean("dc") ? 1 : 0);
                JSONObject v = new JSONObject().put("art", "ortskreis").put("tarifId", p.getId()).put("name", p.getLabel());
                v.put("preisKwh", p.hatPreis(dc) ? (Object) p.rateFor(dc) : JSONObject.NULL);
                if (p.istGratis(dc)) v.put("gratis", true);
                vorschlaege.put(v);
                if (ortName == null) ortName = p.getLabel();
            }
            for (int i = 0; stopps != null && i < stopps.length(); i++) {
                JSONObject s = stopps.optJSONObject(i);
                if (s == null) continue;
                double d = meter(lat, lng, s.optDouble("lat"), s.has("lng") ? s.optDouble("lng") : s.optDouble("lon"));
                if (d > NAEHE_M) continue;
                vorschlaege.put(new JSONObject().put("art", "planer_stopp").put("name", s.optString("name", ""))
                        .put("cpo", s.optString("cpo", "")).put("preisKwh", JSONObject.NULL));
                if (ortName == null) ortName = s.optString("name", "");
            }
        }
        e.put("ort", ortName == null || ortName.isEmpty() ? new JSONObject().put("bekannt", false)
                : new JSONObject().put("bekannt", true).put("name", ortName));
        e.put("vorschlaege", vorschlaege);
        e.put("auswahl", auswahl(cfg));
        return e;
    }

    /** Ladekarten aus der Gruppe „laden“, ad hoc, zuhause, zuhause (gratis). Kartenpreise kennt das Auto nicht (Planer). */
    static JSONArray auswahl(JSONObject cfg) throws Exception {
        JSONArray a = new JSONArray();
        JSONObject l = cfg == null ? null : cfg.optJSONObject("laden");
        JSONArray karten = l == null ? null : l.optJSONArray("karten");
        for (int i = 0; karten != null && i < karten.length(); i++) {
            JSONObject k = karten.optJSONObject(i);
            if (k == null) continue;
            a.put(new JSONObject().put("art", "karte").put("id", k.optInt("id")).put("anzeige", k.optString("anzeige", ""))
                    .put("preisKwh", JSONObject.NULL));
        }
        a.put(new JSONObject().put("art", "adhoc").put("preisKwh", JSONObject.NULL));
        JSONObject t = cfg == null ? null : cfg.optJSONObject("tripAnalytics");
        double heim = t == null ? -1 : t.optDouble("electricityRate", -1);
        boolean heimGratis = t != null && t.optBoolean("rateGratis", false);
        a.put(new JSONObject().put("art", "zuhause").put("preisKwh", heim > 0 ? (Object) heim : JSONObject.NULL));
        a.put(new JSONObject().put("art", "zuhause_gratis").put("preisKwh", 0).put("vorgeschlagen", heimGratis));
        return a;
    }

    /**
     * {@code POST /api/laden/nachfrage} → Anfrage für {@link LadePreis#preis}: {@code {id, art, preisKwh?, karteId?,
     * waehrung?}}. Rückgabe null + Fehlergrund in {@code grund[0]}.
     */
    public static JSONObject alsPreisAnfrage(JSONObject req, JSONObject cfg, String[] grund) throws Exception {
        long id = req.optLong("id", -1);
        if (id <= 0) { grund[0] = "id"; return null; }
        String art = req.optString("art", "");
        JSONObject p = new JSONObject().put("id", id).put("quelle", LadePreis.QUELLE_NUTZER);
        if (req.has("waehrung")) p.put("currency", req.optString("waehrung"));
        switch (art) {
            case "zuhause_gratis":
                p.put("gratis", true);
                p.put("label", "zuhause");
                return p;
            case "karte":
            case "adhoc":
            case "zuhause":
            case "ortskreis": {
                Object pr = req.opt("preisKwh");
                if (!(pr instanceof Number)) { grund[0] = "preisKwh"; return null; }
                double d = ((Number) pr).doubleValue();
                if (!Double.isFinite(d) || d < 0) { grund[0] = "preisKwh"; return null; }
                if (d == 0) p.put("gratis", true); else p.put("rate", d);
                p.put("label", label(art, req, cfg));
                return p;
            }
            default:
                grund[0] = "art";
                return null;
        }
    }

    static String label(String art, JSONObject req, JSONObject cfg) {
        if ("karte".equals(art)) {
            int kid = req.optInt("karteId", -1);
            JSONObject l = cfg == null ? null : cfg.optJSONObject("laden");
            JSONArray karten = l == null ? null : l.optJSONArray("karten");
            for (int i = 0; karten != null && i < karten.length(); i++) {
                JSONObject k = karten.optJSONObject(i);
                if (k != null && k.optInt("id") == kid) return k.optString("anzeige", "Ladekarte");
            }
            return "Ladekarte";
        }
        if ("zuhause".equals(art)) return "zuhause";
        if ("ortskreis".equals(art)) return req.optString("name", "");
        return "ad hoc";
    }

    /** Übernommene Ladungen vor {@link #SEIT} ohne Preis (Overdrive-Zeit): keine Nachfrage, aber über den Preis-Weg bepreisbar. */
    public static int ohnePreisVorSeit(JSONArray zeilen) {
        int n = 0;
        for (int i = 0; zeilen != null && i < zeilen.length(); i++) {
            JSONObject z = zeilen.optJSONObject(i);
            if (z != null && !z.isNull("ende") && z.optLong("start", 0) < SEIT && "fehlt".equals(quelle(z))) n++;
        }
        return n;
    }

    /** Eintrag für „Noch offen“ zu {@link #ohnePreisVorSeit}, null bei 0. */
    public static JSONObject nochOffenAlt(int anzahl) throws Exception {
        if (anzahl <= 0) return null;
        return new JSONObject().put("id", "uebernommene_ladungen_ohne_preis")
                .put("textKey", "einr.hinweis.uebernommene_ladungen_ohne_preis").put("anzahl", anzahl).put("ziel", "/api/laden/preis");
    }

    /** Eintrag für „Noch offen“ (Einrichtung), null bei 0. */
    public static JSONObject nochOffen(int anzahl) throws Exception {
        if (anzahl <= 0) return null;
        return new JSONObject().put("id", "ladungen_ohne_preis").put("textKey", "einr.hinweis.ladungen_ohne_preis")
                .put("anzahl", anzahl).put("ziel", "/api/laden/nachfrage");
    }

    static double meter(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371000, p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = Math.toRadians(lat2 - lat1), dl = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
