package at.greenchili.surfmate.charging;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Ausdrücklicher Preis-Weg für abgeschlossene Ladungen (Kern 5, 0.1.10; Koordination 22.10.: Nachbepreisung, Ladenachfrage).
 * Nur vom Nutzer bzw. aus der Ladenachfrage ausgelöst – die automatische Regel „global bepreiste Sitzungen nie umpreisen“
 * bleibt unberührt. Mit Probelauf, Vorher-Abbild, Protokoll (alt → neu) und Rückgängig; vorher eine konsistente Sicherung.
 */
public final class LadePreis {

    private LadePreis() {}

    public static final int MAX_IDS = 200;
    public static final String QUELLE_NUTZER = "nutzer";

    /** Zugriff auf die Lade-DB (Laufzeit: SocHistoryDatabase; Test: Schein). */
    public interface Db {
        JSONObject snapshot(long id);
        boolean setzen(long id, double rate, String waehrung, String tarifId, String tarifName, String quelle, String nachfrage);
        JSONObject export() throws Exception;
    }

    /** Tarif nach Kennung (oder null). */
    public interface Tarife {
        TariffProfile tarif(String id);
    }

    /** Ablage der Protokolle (Laufzeit: Datei im Daemon-Ordner). */
    public interface Protokoll {
        boolean speichere(JSONObject eintrag);
        JSONObject lies(String id);
    }

    /** Eine DB-Zeile (SELECT … aus SocHistoryDatabase) in Vertragsform. */
    public static JSONObject zeile(ResultSet rs) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", rs.getLong("id"));
        o.put("start", rs.getLong("start_time"));
        long ende = rs.getLong("end_time");
        o.put("ende", rs.wasNull() ? JSONObject.NULL : (Object) ende);
        o.put("kwh", rs.getDouble("energy_added_kwh"));
        int dc = rs.getInt("is_dc");
        o.put("dc", dc == 1 ? Boolean.TRUE : dc == 0 ? (Object) Boolean.FALSE : JSONObject.NULL);
        // 0.1.11 (Koordination): -1 ist nur ein Platzhalter in der DB („Preis fehlt“) und nie ein Betrag –
        // nach außen null + preisFehlt. Leser mit optDouble("rate", -1) sehen weiterhin -1.
        double rate = rs.getDouble("electricity_rate");
        boolean rateFehlt = rs.wasNull() || !Double.isFinite(rate) || rate < 0;
        double cost = rs.getDouble("session_cost");
        boolean costFehlt = rs.wasNull() || !Double.isFinite(cost) || cost < 0;
        o.put("rate", rateFehlt ? JSONObject.NULL : (Object) rate);
        o.put("cost", costFehlt ? JSONObject.NULL : (Object) cost);
        if (rateFehlt || costFehlt) o.put("preisFehlt", true);
        o.put("currency", nn(rs.getString("currency")));
        o.put("tariffId", nn(rs.getString("tariff_id")));
        o.put("tariffLabel", nn(rs.getString("tariff_label")));
        o.put("quelle", nn(rs.getString("preis_quelle")));
        o.put("nachfrage", nn(rs.getString("nachfrage")));
        o.put("lat", rs.getDouble("start_lat"));
        o.put("lng", rs.getDouble("start_lng"));
        return o;
    }

    // ---------------------------------------------------------------- Preis setzen

    /**
     * {@code {ids:[…] | id, rate | tarifId | gratis:true | fehlt:true, currency?, dryRun?}} →
     * {Status, Antwort {ok, dryRun, protokollId?, ergebnisse:[{id, alt, neu, hinweis?}], fehler:{id: grund}}}.
     */
    public static Object[] preis(JSONObject req, Db db, Tarife tarife, Protokoll prot, String standardWaehrung, long jetzt)
            throws Exception {
        Set<Long> ids = new LinkedHashSet<>();
        JSONArray a = req.optJSONArray("ids");
        for (int i = 0; a != null && i < a.length(); i++) {
            long id = a.optLong(i, -1);
            if (id > 0) ids.add(id);
        }
        if (req.has("id") && req.optLong("id", -1) > 0) ids.add(req.optLong("id"));
        if (ids.isEmpty()) return fehler(400, "ids");
        if (ids.size() > MAX_IDS) return fehler(400, "max " + MAX_IDS);
        String quelle = req.optString("quelle", QUELLE_NUTZER);
        if (!QUELLE_NUTZER.equals(quelle)) return fehler(400, "quelle");

        int arten = (req.has("rate") ? 1 : 0) + (req.has("tarifId") ? 1 : 0)
                + (req.optBoolean("gratis", false) ? 1 : 0) + (req.optBoolean("fehlt", false) ? 1 : 0);
        if (arten != 1) return fehler(400, "genau_eins_von_rate_tarifId_gratis_fehlt");
        Double rate = null;
        boolean gratis = req.optBoolean("gratis", false);
        TariffProfile tarif = null;
        if (req.has("rate")) {
            Object r = req.opt("rate");
            if (!(r instanceof Number)) return fehler(400, "rate");
            double d = ((Number) r).doubleValue();
            if (!Double.isFinite(d) || d < 0 || d >= 100000) return fehler(400, "rate");
            if (d == 0) return fehler(400, "gratis_bestaetigen");   // 0 nur ausdrücklich als gratis
            rate = d;
        } else if (gratis) {
            rate = 0.0;
        } else if (req.optBoolean("fehlt", false)) {
            rate = -1.0;
        } else {
            tarif = tarife.tarif(req.optString("tarifId", ""));
            if (tarif == null) return fehler(404, "tarif");
        }
        String waehrungReq = req.optString("currency", "").trim();
        if (waehrungReq.length() > 8) return fehler(400, "currency");
        boolean dry = req.optBoolean("dryRun", false);
        String labelReq = req.optString("label", "").replaceAll("\\p{Cntrl}+", " ").trim();
        if (labelReq.length() > 60) labelReq = labelReq.substring(0, 60);

        JSONArray ergebnisse = new JSONArray();
        JSONObject fehler = new JSONObject();
        JSONArray protokoll = new JSONArray();
        for (long id : ids) {
            JSONObject alt = db.snapshot(id);
            if (alt == null) { fehler.put(String.valueOf(id), "unbekannt"); continue; }
            if (alt.isNull("ende")) { fehler.put(String.valueOf(id), "offen"); continue; }
            int dc = alt.isNull("dc") ? -1 : (alt.optBoolean("dc") ? 1 : 0);
            double r;
            String tid = "", tname = "", w;
            boolean g = gratis;
            if (tarif != null) {
                if (!tarif.hatPreis(dc)) { fehler.put(String.valueOf(id), "tarif_ohne_preis"); continue; }
                r = tarif.rateFor(dc);
                g = tarif.istGratis(dc);
                tid = tarif.getId();
                tname = tarif.getLabel();
                w = !waehrungReq.isEmpty() ? waehrungReq
                        : tarif.getCurrency() != null && !tarif.getCurrency().isEmpty() ? tarif.getCurrency() : standardWaehrung;
            } else {
                r = rate;
                tname = r < 0 ? "" : labelReq;
                w = !waehrungReq.isEmpty() ? waehrungReq
                        : !alt.optString("currency", "").isEmpty() ? alt.optString("currency") : standardWaehrung;
            }
            String quelleNeu = r < 0 ? "fehlt" : (tarif != null ? "tarif" : QUELLE_NUTZER);
            JSONObject neu = new JSONObject();
            double kwh = alt.optDouble("kwh", 0);
            neu.put("rate", r < 0 ? JSONObject.NULL : (Object) r);
            neu.put("cost", r < 0 ? JSONObject.NULL : (Object) (kwh > 0 ? Math.round(kwh * r * 10000.0) / 10000.0 : 0.0));
            if (r < 0) neu.put("preisFehlt", true);
            neu.put("currency", w == null ? "" : w);
            neu.put("tariffId", tid);
            neu.put("tariffLabel", tname);
            neu.put("quelle", quelleNeu);
            neu.put("nachfrage", "erledigt");
            JSONObject e = new JSONObject();
            e.put("id", id);
            e.put("alt", nurPreis(alt));
            e.put("neu", neu);
            String h = r < 0 ? null : PreisPlausibel.pruefe(r, w, g);
            if (h != null) e.put("hinweis", h);   // nur Hinweis, sperrt nicht (ausdrückliche Nutzerangabe)
            if (!dry) {
                if (!db.setzen(id, r, w, tid, tname, quelleNeu, "erledigt")) {
                    fehler.put(String.valueOf(id), "speichernFehlgeschlagen");
                    continue;
                }
                protokoll.put(new JSONObject().put("id", id).put("alt", nurPreis(alt)));
            }
            ergebnisse.put(e);
        }
        JSONObject o = new JSONObject();
        o.put("ok", fehler.length() == 0);
        o.put("dryRun", dry);
        if (!dry && protokoll.length() > 0) {
            String pid = "p-" + jetzt;
            JSONObject eintrag = new JSONObject().put("id", pid).put("zeit", jetzt).put("eintraege", protokoll);
            if (prot.speichere(eintrag)) o.put("protokollId", pid);
            else o.put("protokollFehler", true);   // Preis steht, aber Rückgängig fehlt: ehrlich melden
        }
        o.put("ergebnisse", ergebnisse);
        o.put("fehler", fehler);
        return new Object[] {200, o};
    }

    /** {@code {protokollId}} → setzt alle Sitzungen dieses Protokolls auf ihr Vorher-Abbild zurück. */
    public static Object[] rueckgaengig(JSONObject req, Db db, Protokoll prot) throws Exception {
        JSONObject e = prot.lies(req.optString("protokollId", ""));
        if (e == null) return fehler(404, "protokoll");
        JSONArray l = e.optJSONArray("eintraege");
        JSONArray zurueck = new JSONArray();
        JSONObject fehler = new JSONObject();
        for (int i = 0; l != null && i < l.length(); i++) {
            JSONObject x = l.getJSONObject(i);
            long id = x.getLong("id");
            JSONObject alt = x.getJSONObject("alt");
            double r = alt.optDouble("rate", -1);
            if (r < 0) r = -1;
            boolean ok = db.setzen(id, r, alt.optString("currency", ""), alt.optString("tariffId", ""),
                    alt.optString("tariffLabel", ""), alt.optString("quelle", ""), alt.optString("nachfrage", ""));
            if (ok) zurueck.put(id);
            else fehler.put(String.valueOf(id), "speichernFehlgeschlagen");
        }
        JSONObject o = new JSONObject();
        o.put("ok", fehler.length() == 0);
        o.put("zurueckgesetzt", zurueck);
        o.put("fehler", fehler);
        return new Object[] {200, o};
    }

    // ---------------------------------------------------------------- Sicherung

    /** Konsistenter JSON-Export (eine Lese-Transaktion) in {@code ordner}; Antwort mit Pfad, Anzahl, sha256. */
    public static Object[] sicherung(Db db, File ordner, long jetzt) throws Exception {
        JSONObject ex = db.export();
        ex.put("erstellt", jetzt);
        ex.put("version", 1);
        byte[] daten = ex.toString().getBytes(StandardCharsets.UTF_8);
        if (!ordner.isDirectory() && !ordner.mkdirs()) return fehler(500, "ordner");
        File f = new File(ordner, "lade-sicherung-" + jetzt + ".json");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(daten);
            out.getFD().sync();
        }
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("pfad", f.getAbsolutePath());
        o.put("sitzungen", ex.optJSONArray("sessions") == null ? 0 : ex.getJSONArray("sessions").length());
        o.put("tage", ex.optJSONArray("daily") == null ? 0 : ex.getJSONArray("daily").length());
        o.put("bytes", daten.length);
        o.put("sha256", sha256(daten));
        return new Object[] {200, o};
    }

    // ---------------------------------------------------------------- Hilfen

    static JSONObject nurPreis(JSONObject z) throws Exception {
        JSONObject p = new JSONObject();
        for (String k : new String[] {"rate", "cost", "currency", "tariffId", "tariffLabel", "quelle", "nachfrage"}) {
            p.put(k, z.has(k) ? z.get(k) : JSONObject.NULL);
        }
        // nie -1 als Betrag (ältere Protokolle bzw. Zeilen ohne Umwandlung)
        for (String k : new String[] {"rate", "cost"}) {
            Object v = p.opt(k);
            if (v instanceof Number && ((Number) v).doubleValue() < 0) { p.put(k, JSONObject.NULL); p.put("preisFehlt", true); }
        }
        return p;
    }

    static String sha256(byte[] b) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static String nn(String s) {
        return s == null ? "" : s;
    }

    private static Object[] fehler(int status, String grund) throws Exception {
        JSONObject o = new JSONObject();
        o.put("ok", false);
        o.put("grund", grund);
        return new Object[] {status, o};
    }
}
