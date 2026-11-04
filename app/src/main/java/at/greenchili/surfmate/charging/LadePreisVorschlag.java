package at.greenchili.surfmate.charging;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Preis-Vorschlag „~“ für eine Ladung an einer erkannten Säule (Vorschlag v2 + Ergänzung Projektinhaber 03.11.2026). Rein.
 *
 * <p>Nie automatisch, immer getrennt nach Stromart (AC/DC) und so weit möglich je Säule. Reihenfolge:
 * <ol>
 *   <li>{@code eigen}: der eigene Preis „Laut Säule“ (Betrag ÷ kWh) bzw. ein selbst gesetzter Preis je kWh bei
 *       demselben Betreiber, demselben LAND (0.1.38.6: ein „Lidl“-Preis aus AT gilt nie an DE-Säulen und umgekehrt) UND
 *       derselben Stromart, die jüngste Ladung zuerst. Ohne sicheres Land (siehe {@link LadeLand}) gibt es keinen
 *       eigenen Preis, weder für die Ladung noch aus ihr;</li>
 *   <li>{@code karte}: Preise eigener Ladekarten an dieser Säule (Planer {@code preis_meine});</li>
 *   <li>{@code planer}: ad hoc und Tarife des Betreibers an dieser Säule, mit Stand-Datum.</li>
 * </ol>
 * Passt die Säule nicht zur Stromart der Ladung oder hat sie für diese keinen Preis, kommt vom Planer nichts.
 * „Laut Säule“ bleibt die Wahrheit; übernommen wird nur auf Tipp.
 */
public final class LadePreisVorschlag {

    private LadePreisVorschlag() {}

    public static final String EIGEN = "eigen";
    public static final String KARTE = "karte";
    public static final String PLANER = "planer";

    /**
     * @param betreiber  Betreiber der zugeordneten Säule (leer = keiner bekannt)
     * @param land       Land der Säule bzw. der Ladung, zwei Buchstaben, null/leer = unbekannt (dann kein eigener Preis)
     * @param isDc       1 DC, 0 AC, sonst unbekannt
     * @param station    gespeicherter Kandidat ({@code art, tarife[]}) oder null
     * @param sitzungen  Ladungen wie {@code /api/charging} (mit {@code betreiber, isDc, saeuleKwh, saeuleBetrag,
     *                   preisQuelle, electricityRate})
     * @param ohneId     die Ladung selbst (zählt nicht als eigener Preis)
     */
    public static JSONArray vorschlaege(String betreiber, String land, int isDc, JSONObject station, JSONArray sitzungen,
                                        long ohneId) throws Exception {
        JSONArray a = new JSONArray();
        JSONObject eigen = eigenerPreis(betreiber, land, isDc, sitzungen, ohneId);
        if (eigen != null) a.put(eigen);
        if (station == null || !artPasst(station.optString("art", ""), isDc)) return a;
        JSONArray t = station.optJSONArray("tarife");
        // erst eigene Karten, dann ad hoc und Betreiber-Tarife
        for (int runde = 0; runde < 2; runde++) {
            for (int i = 0; t != null && i < t.length(); i++) {
                JSONObject x = t.optJSONObject(i);
                if (x == null) continue;
                boolean meine = x.optBoolean("meine");
                if ((runde == 0) != meine) continue;
                double p = x.optDouble("preisKwh", Double.NaN);
                if (!(p > 0) || p > 10) continue;
                JSONObject v = new JSONObject();
                v.put("quelle", meine ? KARTE : PLANER);
                v.put("name", x.optString("name", ""));
                v.put("preisKwh", runde4(p));
                v.put("adhoc", x.optBoolean("adhoc"));
                v.put("stand", x.isNull("stand") || x.optString("stand", "").isEmpty() ? JSONObject.NULL : x.optString("stand"));
                a.put(v);
            }
        }
        return a;
    }

    static boolean artPasst(String art, int isDc) {
        if (isDc == 1) return "DC".equals(art);
        if (isDc == 0) return "AC".equals(art);
        return "AC".equals(art) || "DC".equals(art);
    }

    /** Jüngster eigener Preis je kWh bei diesem Betreiber, in diesem Land und mit dieser Stromart, oder null. */
    static JSONObject eigenerPreis(String betreiber, String land, int isDc, JSONArray sitzungen, long ohneId) throws Exception {
        String b = norm(betreiber);
        String l = LadeLand.norm(land);
        if (b.isEmpty() || l == null || (isDc != 0 && isDc != 1)) return null;
        JSONObject best = null;
        long bestZeit = Long.MIN_VALUE;
        for (int i = 0; sitzungen != null && i < sitzungen.length(); i++) {
            JSONObject s = sitzungen.optJSONObject(i);
            if (s == null || s.optLong("id", -1) == ohneId) continue;
            if (!b.equals(norm(s.isNull("betreiber") ? "" : s.optString("betreiber", "")))) continue;
            if (s.isNull("isDc") || s.optBoolean("isDc") != (isDc == 1)) continue;
            // Land der Ladung: ausdrücklich oder aus der gespeicherten Position, sonst zählt sie nie (Bestand bleibt erhalten)
            if (!l.equals(LadeLand.von(s))) continue;
            double p = Double.NaN;
            String art = null;
            double kwh = s.isNull("saeuleKwh") ? Double.NaN : s.optDouble("saeuleKwh", Double.NaN);
            double betrag = s.isNull("saeuleBetrag") ? Double.NaN : s.optDouble("saeuleBetrag", Double.NaN);
            if (kwh > 0 && betrag >= 0) {
                p = betrag / kwh;
                art = "saeule";
            } else if ("nutzer".equals(s.optString("preisQuelle", "")) && !s.isNull("electricityRate")
                    && s.optDouble("electricityRate", -1) > 0) {
                p = s.optDouble("electricityRate");
                art = "preis";
            }
            if (!(p > 0) || p > 10) continue;
            long t = s.optLong("startTime", 0);
            if (best == null || t > bestZeit) {
                best = new JSONObject();
                best.put("quelle", EIGEN);
                best.put("name", betreiber.trim());
                best.put("preisKwh", runde4(p));
                best.put("herkunft", art);
                best.put("datum", t > 0 ? (Object) t : JSONObject.NULL);
                best.put("ladungId", s.optLong("id", -1));
                bestZeit = t;
            }
        }
        return best;
    }

    /**
     * „Günstigste in der Nähe“ (0.1.36): jede Säule der Umkreis-Antwort bekommt {@code preis_eigen}
     * ({@code preis_kwh, herkunft, datum}), wenn es bei diesem Betreiber UND dieser Stromart einen eigenen Preis
     * gibt (Regeln wie {@link #eigenerPreis}). Läuft nur im Auto auf der Antwort; nichts davon geht an den Planer.
     * Säulen ohne Betreiber, ohne Stromart AC/DC oder ohne sicheres Land (0.1.38.6) bleiben ohne. Liefert die Zahl der Säulen mit eigenem Preis.
     */
    public static int eigenePreiseEintragen(JSONObject antwort, JSONArray sitzungen) throws Exception {
        JSONArray st = antwort == null ? null : antwort.optJSONArray("stationen");
        if (st == null || sitzungen == null || sitzungen.length() == 0) return 0;
        java.util.Map<String, JSONObject> merk = new java.util.HashMap<>();
        int n = 0;
        for (int i = 0; i < st.length(); i++) {
            JSONObject s = st.optJSONObject(i);
            if (s == null) continue;
            s.remove("preis_eigen");   // nie vom Planer übernehmen
            String art = LadesaeuleZuordnung.art(s.optString("art", ""));
            String b = norm(s.isNull("betreiber") ? "" : s.optString("betreiber", ""));
            String land = LadeLand.von(s);
            if (art == null || b.isEmpty() || land == null) continue;
            String k = b + "|" + land + "|" + art;
            JSONObject e;
            if (merk.containsKey(k)) e = merk.get(k);
            else {
                e = eigenerPreis(b, land, "DC".equals(art) ? 1 : 0, sitzungen, Long.MIN_VALUE);
                merk.put(k, e);
            }
            if (e == null) continue;
            JSONObject p = new JSONObject();
            p.put("preis_kwh", e.getDouble("preisKwh"));
            p.put("herkunft", e.opt("herkunft"));
            p.put("datum", e.opt("datum"));
            s.put("preis_eigen", p);
            n++;
        }
        return n;
    }

    static String norm(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    static double runde4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
