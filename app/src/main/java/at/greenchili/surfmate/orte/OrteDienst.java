package at.greenchili.surfmate.orte;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * ORTE_VERTRAG v1 (0.1.11, Kern 7): Abgleich zwischen dem Abschnitt {@code orte} und den alten Speichern
 * (Ruhezonen-Datei, surveillance.privateZones, Tarif-Ortskreise). Favoriten bleiben in 0.1.11 unberührt (0.1.12).
 *
 * <p>Ablauf je Abgleich: noch nicht migriert → einmalig übernehmen; Spiegel-Merker fehlt (z. B. nach Wiederherstellung)
 * → orte in die alten Speicher schreiben; alte Speicher weichen vom letzten Spiegel ab (alter Schreibweg) → übernehmen.
 * Alte Einträge, die nur innerhalb der Zusammenführungs-Toleranz vom Ort abweichen, werden NICHT umgeschrieben – alte
 * Leser sehen nach der Migration exakt dasselbe.
 */
public final class OrteDienst {

    /** Zugriff auf die alten Speicher und den Abschnitt (Daemon: echte Speicher, Test: Attrappe). */
    public interface Speicher {
        /** Normalform siehe {@link Orte}. */
        JSONObject alteLesen() throws Exception;
        /** @return neue id oder null (voll) */
        String ruheAnlegen(String name, double lat, double lon, int r, boolean an) throws Exception;
        void ruheAendern(String id, String name, double lat, double lon, int r, boolean an) throws Exception;
        void ruheEntfernen(String id) throws Exception;
        void privatSetzen(JSONArray zonen) throws Exception;
        /** @return neue id oder null */
        String tarifAnlegen(JSONObject t) throws Exception;
        void tarifAendern(String id, JSONObject t) throws Exception;
        void tarifEntfernen(String id) throws Exception;
        JSONObject abschnittLesen() throws Exception;
        void abschnittSchreiben(JSONObject abschnitt) throws Exception;
    }

    /** Fachlicher Fehler für die API (422/409). */
    public static final class Fehler extends Exception {
        public final int status;
        public final JSONObject antwort;

        public Fehler(int status, JSONObject antwort) {
            super(antwort.optString("error"));
            this.status = status;
            this.antwort = antwort;
        }
    }

    private final Speicher sp;

    public OrteDienst(Speicher sp) {
        this.sp = sp;
    }

    /** Abgleich; gibt die aktuelle Ortsliste zurück. */
    public synchronized JSONArray abgleichen(long jetzt) throws Exception {
        JSONObject sec = sp.abschnittLesen();
        if (sec == null) sec = new JSONObject();
        JSONObject alt = sp.alteLesen();
        JSONArray liste = sec.optJSONArray("liste");
        if (!sec.optBoolean("migriert", false) || liste == null) {
            liste = Orte.migrieren(alt, jetzt);
            sec.put("version", Orte.VERSION).put("max", Orte.MAX_ORTE).put("migriert", true).put("migriertAm", jetzt)
                    .put("liste", liste).put("migration", zaehler(alt));
            spiegeln(liste, alt);   // ändert nichts, solange alles innerhalb der Toleranz liegt
            sec.put("spiegel", sp.alteLesen());
            sp.abschnittSchreiben(sec);
            return liste;
        }
        JSONObject spiegel = sec.optJSONObject("spiegel");
        boolean geaendert = false;
        if (spiegel == null) {
            spiegeln(liste, alt);
            geaendert = true;
        } else if (!Orte.gleich(normal(spiegel), normal(alt))) {
            Orte.aufnehmen(liste, spiegel, alt, jetzt);
            spiegeln(liste, alt);
            geaendert = true;
        }
        if (geaendert) {
            sec.put("liste", liste).put("spiegel", sp.alteLesen());
            sp.abschnittSchreiben(sec);
        }
        return liste;
    }

    public synchronized JSONObject anlegen(JSONObject wunsch, long jetzt) throws Exception {
        JSONArray liste = abgleichen(jetzt);
        if (liste.length() >= Orte.MAX_ORTE) throw new Fehler(409, new JSONObject().put("error", "zu_viele").put("max", Orte.MAX_ORTE));
        JSONObject ort = Orte.neuerOrt(wunsch.optString("name", "").trim(), wunsch.optDouble("lat", Double.NaN),
                wunsch.optDouble("lon", Double.NaN), wunsch.optDouble("r", Double.NaN), jetzt);
        ort.put("r", wunsch.optDouble("r", Double.NaN)).put("name", wunsch.optString("name", "").trim());
        uebernehmeEigenschaften(ort, wunsch.optJSONObject("eigenschaften"));
        JSONObject f = Orte.pruefen(ort);
        if (f != null) throw new Fehler(422, f);
        ort.put("r", (int) Math.round(ort.optDouble("r")));
        liste.put(ort);
        schreiben(liste);
        return ort;
    }

    public synchronized JSONObject aendern(String id, JSONObject wunsch, long jetzt) throws Exception {
        JSONArray liste = abgleichen(jetzt);
        int i = index(liste, id);
        if (i < 0) throw new Fehler(404, new JSONObject().put("error", "nicht_gefunden"));
        JSONObject ort = new JSONObject(liste.getJSONObject(i).toString());
        for (String k : new String[] {"name", "lat", "lon", "r"}) if (wunsch.has(k)) ort.put(k, wunsch.get(k));
        if (ort.has("name")) ort.put("name", ort.optString("name").trim());
        uebernehmeEigenschaften(ort, wunsch.optJSONObject("eigenschaften"));
        JSONObject f = Orte.pruefen(ort);
        if (f != null) throw new Fehler(422, f);
        ort.put("r", (int) Math.round(ort.optDouble("r"))).put("geaendert", jetzt);
        liste.put(i, ort);
        schreiben(liste);
        return ort;
    }

    /**
     * 0.1.12: Navi-Favoriten der App abgleichen ({@link OrteFavoriten}). Stand im Abschnitt unter {@code favoriten}:
     * {version, soll (was die App nach der letzten Antwort hat), gepostet (letzte gemeldete App-Liste)}.
     *
     * @return {version, aendern, liste?} – {@code liste} nur, wenn die App ihren Speicher ersetzen soll
     */
    public synchronized JSONObject favoritenAbgleichen(JSONArray app, long appVersion, long jetzt) throws Exception {
        JSONArray liste = abgleichen(jetzt);
        JSONObject sec = sp.abschnittLesen();
        JSONObject st = sec.optJSONObject("favoriten");
        if (st == null) st = new JSONObject().put("version", 0);
        long version = st.optLong("version", 0);
        JSONArray basis = appVersion == version && st.optJSONArray("soll") != null ? st.optJSONArray("soll")
                : st.optJSONArray("gepostet") != null ? st.optJSONArray("gepostet") : new JSONArray();
        int n = 0;
        if (!OrteFavoriten.gleich(basis, app)) n = OrteFavoriten.aufnehmen(liste, basis, app, jetzt);
        JSONArray soll = OrteFavoriten.soll(liste);
        boolean aendern = !OrteFavoriten.passend(soll, app);
        JSONArray neuStand = aendern ? soll : app;
        JSONArray altSoll = st.optJSONArray("soll");
        if (altSoll == null || !OrteFavoriten.gleich(altSoll, neuStand)) version++;
        st.put("version", version).put("soll", neuStand).put("gepostet", app);
        JSONObject alt = sp.alteLesen();
        spiegeln(liste, alt);   // Orte aus Favoriten haben keine anderen Eigenschaften – meist nichts zu tun
        sec.put("liste", liste).put("spiegel", sp.alteLesen()).put("favoriten", st);
        sp.abschnittSchreiben(sec);
        JSONObject r = new JSONObject().put("version", version).put("aendern", aendern).put("uebernommen", n);
        if (aendern) r.put("liste", soll);
        return r;
    }

    /** Entfernt den Ort und seine Spiegel in den alten Speichern (nur mit Bestätigung, prüft die API). */
    public synchronized void loeschen(String id, long jetzt) throws Exception {
        JSONArray liste = abgleichen(jetzt);
        int i = index(liste, id);
        if (i < 0) throw new Fehler(404, new JSONObject().put("error", "nicht_gefunden"));
        liste.remove(i);
        schreiben(liste);
    }

    private void schreiben(JSONArray liste) throws Exception {
        JSONObject sec = sp.abschnittLesen();
        spiegeln(liste, sp.alteLesen());
        sec.put("liste", liste).put("spiegel", sp.alteLesen());
        sp.abschnittSchreiben(sec);
    }

    private static void uebernehmeEigenschaften(JSONObject ort, JSONObject w) throws Exception {
        if (w == null) return;
        JSONObject e = Orte.eig(ort);
        for (String k : new String[] {"privat", "ruhezone", "zuhause", "favorit"}) {
            if (w.has(k)) {
                if (!(w.opt(k) instanceof Boolean)) throw new Fehler(422, new JSONObject().put("error", "eigenschaft").put("eigenschaft", k));
                e.put(k, w.getBoolean(k));
            }
        }
        if (w.has("tarif")) {
            if (w.isNull("tarif")) e.put("tarif", JSONObject.NULL);
            else if (w.opt("tarif") instanceof JSONObject) e.put("tarif", Orte.tarifEig(w.getJSONObject("tarif")));
            else throw new Fehler(422, new JSONObject().put("error", "eigenschaft").put("eigenschaft", "tarif"));
        }
    }

    private static int index(JSONArray liste, String id) {
        for (int i = 0; i < liste.length(); i++) if (id.equals(liste.optJSONObject(i).optString("id"))) return i;
        return -1;
    }

    /** Schreibt den Soll-Zustand in die alten Speicher; trägt neue Verweise in die Liste ein. */
    void spiegeln(JSONArray liste, JSONObject alt) throws Exception {
        JSONObject soll = Orte.soll(liste);
        // Ruhezonen
        java.util.Map<String, JSONObject> altRuhe = Orte.nachId(alt.optJSONArray("ruhe"));
        java.util.Set<String> bleiben = new java.util.HashSet<>();
        JSONArray sr = soll.getJSONArray("ruhe");
        for (int i = 0; i < sr.length(); i++) {
            JSONObject z = sr.getJSONObject(i);
            String id = z.optString("id", null);
            JSONObject a = id == null ? null : altRuhe.get(id);
            if (a != null) {
                bleiben.add(id);
                boolean gleich = Orte.nah(a, z.optDouble("lat"), z.optDouble("lon"), z.optDouble("r"))
                        && a.optString("name").equals(z.optString("name")) && a.optBoolean("an") == z.optBoolean("an");
                if (!gleich) sp.ruheAendern(id, z.optString("name"), z.optDouble("lat"), z.optDouble("lon"), z.optInt("r"), z.optBoolean("an"));
            } else {
                String neu = sp.ruheAnlegen(z.optString("name"), z.optDouble("lat"), z.optDouble("lon"), z.optInt("r"), z.optBoolean("an"));
                if (neu == null) throw new Fehler(409, new JSONObject().put("error", "ruhezonen_voll").put("max", 10));
                bleiben.add(neu);
                Orte.verweise(ortMitId(liste, z.optString("ort"))).put("ruhe", neu);
            }
        }
        for (String id : altRuhe.keySet()) if (!bleiben.contains(id)) sp.ruheEntfernen(id);
        // private Orte: nur schreiben, wenn sie sich (über die Toleranz hinaus) unterscheiden
        if (!passend(soll.getJSONArray("privat"), alt.optJSONArray("privat"))) {
            JSONArray z = new JSONArray();
            JSONArray sp2 = soll.getJSONArray("privat");
            for (int i = 0; i < sp2.length(); i++) {
                JSONObject p = sp2.getJSONObject(i);
                z.put(new JSONObject().put("lat", p.optDouble("lat")).put("lon", p.optDouble("lon")).put("radiusM", p.optInt("r")));
            }
            sp.privatSetzen(z);
        }
        // Tarif-Ortskreise
        java.util.Map<String, JSONObject> altTarif = Orte.nachId(alt.optJSONArray("tarife"));
        java.util.Set<String> tBleiben = new java.util.HashSet<>();
        JSONArray st = soll.getJSONArray("tarife");
        for (int i = 0; i < st.length(); i++) {
            JSONObject t = st.getJSONObject(i);
            String id = t.optString("id", null);
            JSONObject a = id == null ? null : altTarif.get(id);
            if (a != null) {
                tBleiben.add(id);
                JSONObject ohneOrt = new JSONObject(t.toString());
                ohneOrt.remove("ort");
                ohneOrt.remove("id");
                JSONObject aVgl = new JSONObject(a.toString());
                aVgl.remove("id");
                boolean geo = Orte.nah(a, t.optDouble("lat"), t.optDouble("lon"), t.optDouble("r"));
                aVgl.put("lat", t.optDouble("lat")).put("lon", t.optDouble("lon")).put("r", t.optInt("r"));
                if (!geo || !Orte.gleich(sortiert(aVgl), sortiert(ohneOrt))) sp.tarifAendern(id, t);
            } else {
                String neu = sp.tarifAnlegen(t);
                if (neu == null) throw new Fehler(422, new JSONObject().put("error", "tarif_nicht_angelegt"));
                tBleiben.add(neu);
                Orte.verweise(ortMitId(liste, t.optString("ort"))).put("tarif", neu);
            }
        }
        for (String id : altTarif.keySet()) if (!tBleiben.contains(id)) sp.tarifEntfernen(id);
    }

    private static JSONObject ortMitId(JSONArray liste, String id) {
        for (int i = 0; i < liste.length(); i++) if (id.equals(liste.optJSONObject(i).optString("id"))) return liste.optJSONObject(i);
        throw new IllegalStateException("ort " + id);
    }

    /** Jede Soll-Zone hat genau eine alte Zone innerhalb der Toleranz (und umgekehrt gleich viele). */
    static boolean passend(JSONArray soll, JSONArray alt) {
        int n = alt == null ? 0 : alt.length();
        if (soll.length() != n) return false;
        boolean[] belegt = new boolean[n];
        for (int i = 0; i < soll.length(); i++) {
            JSONObject s = soll.optJSONObject(i);
            boolean ok = false;
            for (int j = 0; j < n; j++) {
                if (!belegt[j] && Orte.nah(alt.optJSONObject(j), s.optDouble("lat"), s.optDouble("lon"), s.optDouble("r"))) {
                    belegt[j] = true;
                    ok = true;
                    break;
                }
            }
            if (!ok) return false;
        }
        return true;
    }

    static JSONObject sortiert(JSONObject o) throws Exception {
        List<String> k = new ArrayList<>();
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) k.add(it.next());
        java.util.Collections.sort(k);
        JSONObject s = new JSONObject();
        for (String x : k) s.put(x, o.get(x));
        return s;
    }

    /** Vergleichsform (Schlüsselreihenfolge egal). */
    static JSONObject normal(JSONObject alt) throws Exception {
        JSONObject n = new JSONObject();
        for (String art : new String[] {"ruhe", "privat", "tarife"}) {
            JSONArray a = alt.optJSONArray(art), b = new JSONArray();
            for (int i = 0; a != null && i < a.length(); i++) b.put(sortiert(a.getJSONObject(i)));
            n.put(art, b);
        }
        return n;
    }

    private static JSONObject zaehler(JSONObject alt) throws Exception {
        return new JSONObject()
                .put("ruhezonen", alt.optJSONArray("ruhe") == null ? 0 : alt.getJSONArray("ruhe").length())
                .put("privat", alt.optJSONArray("privat") == null ? 0 : alt.getJSONArray("privat").length())
                .put("tarife", alt.optJSONArray("tarife") == null ? 0 : alt.getJSONArray("tarife").length())
                .put("favoriten", "0.1.12");
    }
}
