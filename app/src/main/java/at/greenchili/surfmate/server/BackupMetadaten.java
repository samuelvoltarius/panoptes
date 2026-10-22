package at.greenchili.surfmate.server;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 0.1.11 (Kern 7): Zusatz-Kategorie „metadaten“ im Backup-Export ({@code GET /api/backup/export?metadaten=true}).
 * Nur Metadaten, keine Medien: Aufnahmen-Index (alle Zeilen, auch nicht verfügbare), Park-/Wächter-Sitzungen und
 * Fahrten-Zähler. Zweck: update-pflicht sichert den Bestand VOR jedem Update und vergleicht danach – ein Verlust wie
 * beim 0.1.10-Ordnerumzug fällt so sofort auf. Wird beim Wiederherstellen NICHT eingespielt (nur Sicherung/Vergleich).
 * Bleibt auf dem Gerät des Nutzers bzw. in seiner Datei (Regel 2: geht an keinen Server).
 */
public final class BackupMetadaten {

    private BackupMetadaten() {}

    public static final int FORMAT = 1;
    /** Obergrenzen gegen riesige Dateien; „vollstaendig“ zeigt, ob sie gegriffen haben. */
    static final int MAX_AUFNAHMEN = 50_000;
    static final int MAX_PARKEN = 20_000;
    static final int SEITE = 500;

    /** Seitenweiser Leser (limit, offset) → Zeilen; leer = Ende. */
    public interface Seiten {
        List<JSONObject> lies(int limit, int offset) throws Exception;
    }

    /** Liest alle Seiten bis zur Obergrenze. Ergebnis[0] = Zeilen, vollständig wenn weniger als max. */
    static List<JSONObject> alle(Seiten q, int max, boolean[] vollstaendig) throws Exception {
        List<JSONObject> out = new ArrayList<>();
        vollstaendig[0] = true;
        if (q == null) return out;
        int off = 0;
        while (true) {
            List<JSONObject> s = q.lies(SEITE, off);
            if (s == null || s.isEmpty()) break;
            for (JSONObject o : s) {
                if (out.size() >= max) { vollstaendig[0] = false; return out; }
                out.add(o);
            }
            if (s.size() < SEITE) break;
            off += s.size();
        }
        return out;
    }

    /** Anzeige-/Abruf-Felder (URLs, formatierte Texte) weglassen – nur, was den Bestand beschreibt. */
    static JSONObject aufnahmeKompakt(JSONObject rec) throws org.json.JSONException {
        JSONObject o = new JSONObject();
        Iterator<String> it = rec.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (k.endsWith("Url") || k.endsWith("Formatted") || k.equals("date") || k.equals("time")) continue;
            o.put(k, rec.opt(k));
        }
        return o;
    }

    /**
     * Baut die Kategorie. Quellen dürfen null sein (Dienst nicht verfügbar) – dann steht {@code verfuegbar:false}
     * im jeweiligen Teil, nie eine erfundene 0.
     */
    public static JSONObject bauen(Seiten aufnahmen, Seiten parken, int fahrten, int fahrtenDateiFehlt, long jetztMs)
            throws org.json.JSONException {
        JSONObject m = new JSONObject();
        m.put("format", FORMAT);
        m.put("stand", jetztMs);
        m.put("hinweis", "nur Metadaten, keine Medien; wird beim Wiederherstellen nicht eingespielt");

        JSONObject a = new JSONObject();
        try {
            boolean[] voll = new boolean[1];
            List<JSONObject> rows = alle(aufnahmen, MAX_AUFNAHMEN, voll);
            JSONArray arr = new JSONArray();
            int verfuegbar = 0, wachter = 0;
            for (JSONObject r : rows) {
                if (r.optBoolean("available", true)) verfuegbar++;
                if ("sentry".equals(r.optString("type"))) wachter++;
                arr.put(aufnahmeKompakt(r));
            }
            a.put("verfuegbar", aufnahmen != null);
            a.put("anzahl", rows.size());
            a.put("anzahlVerfuegbar", verfuegbar);
            a.put("anzahlWaechter", wachter);
            a.put("vollstaendig", voll[0]);
            a.put("zeilen", arr);
        } catch (Exception e) {
            a.put("verfuegbar", false).put("fehler", String.valueOf(e.getMessage()));
        }
        m.put("aufnahmen", a);

        JSONObject p = new JSONObject();
        try {
            boolean[] voll = new boolean[1];
            List<JSONObject> rows = alle(parken, MAX_PARKEN, voll);
            JSONArray arr = new JSONArray();
            for (JSONObject r : rows) arr.put(r);
            p.put("verfuegbar", parken != null);
            p.put("anzahl", rows.size());
            p.put("vollstaendig", voll[0]);
            p.put("zeilen", arr);
        } catch (Exception e) {
            p.put("verfuegbar", false).put("fehler", String.valueOf(e.getMessage()));
        }
        m.put("parken", p);

        JSONObject f = new JSONObject();
        f.put("verfuegbar", fahrten >= 0);
        if (fahrten >= 0) {
            f.put("anzahl", fahrten);
            f.put("dateiFehlt", Math.max(0, fahrtenDateiFehlt));
        }
        m.put("fahrten", f);
        return m;
    }
}
