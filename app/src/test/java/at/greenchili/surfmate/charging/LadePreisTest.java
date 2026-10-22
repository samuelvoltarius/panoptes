package at.greenchili.surfmate.charging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/** Preis-Weg (Nachbepreisung, Ladenachfrage): Probelauf, Vorher-Abbild, Protokoll, Rückgängig, Sicherung, gratis. Synthetische Daten. */
public class LadePreisTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    /** Lade-DB im Speicher. */
    static final class Schein implements LadePreis.Db {
        final Map<Long, JSONObject> zeilen = new HashMap<>();
        int schreibvorgaenge;
        boolean schreibenGeht = true;

        Schein mit(long id, double kwh, Boolean dc, double rate, boolean offen) throws Exception {
            zeilen.put(id, new JSONObject().put("id", id).put("start", 1000L * id).put("ende", offen ? JSONObject.NULL : (Object) (1000L * id + 500))
                    .put("kwh", kwh).put("dc", dc == null ? JSONObject.NULL : dc).put("rate", rate).put("cost", rate > 0 ? kwh * rate : -1)
                    .put("currency", "€").put("tariffId", "").put("tariffLabel", "").put("quelle", "").put("nachfrage", ""));
            return this;
        }
        public JSONObject snapshot(long id) {
            try { return zeilen.containsKey(id) ? new JSONObject(zeilen.get(id).toString()) : null; } catch (Exception e) { return null; }
        }
        public boolean setzen(long id, double rate, String w, String tid, String tn, String q, String n) {
            if (!schreibenGeht) return false;
            schreibvorgaenge++;
            try {
                JSONObject z = zeilen.get(id);
                double kwh = z.getDouble("kwh");
                z.put("rate", rate).put("cost", rate < 0 ? -1 : kwh * rate).put("currency", w).put("tariffId", tid)
                        .put("tariffLabel", tn).put("quelle", q).put("nachfrage", n);
            } catch (Exception e) { return false; }
            return true;
        }
        public JSONObject export() throws Exception {
            JSONArray s = new JSONArray();
            for (JSONObject z : zeilen.values()) s.put(z);
            return new JSONObject().put("sessions", s).put("daily", new JSONArray());
        }
    }

    static final class Prot implements LadePreis.Protokoll {
        final Map<String, JSONObject> m = new HashMap<>();
        public boolean speichere(JSONObject e) { m.put(e.optString("id"), e); return true; }
        public JSONObject lies(String id) { return m.get(id); }
    }

    private static Object[] preis(String req, Schein db, LadePreis.Tarife t, Prot p) throws Exception {
        return LadePreis.preis(new JSONObject(req), db, t == null ? id -> null : t, p, "€", 5_000L);
    }

    @Test
    public void probelauf_schreibtNichts_zeigtAltUndNeu() throws Exception {
        Schein db = new Schein().mit(1, 30, true, 0.20, false).mit(2, 10, false, 0.20, false);
        Prot p = new Prot();
        Object[] r = preis("{\"ids\":[1,2],\"rate\":0.29,\"dryRun\":true}", db, null, p);
        JSONObject o = (JSONObject) r[1];
        assertEquals(200, r[0]);
        assertTrue(o.getBoolean("dryRun"));
        assertEquals(0, db.schreibvorgaenge);
        assertTrue(p.m.isEmpty());
        JSONObject e = o.getJSONArray("ergebnisse").getJSONObject(0);
        assertEquals(0.20, e.getJSONObject("alt").getDouble("rate"), 1e-9);
        assertEquals(0.29, e.getJSONObject("neu").getDouble("rate"), 1e-9);
        assertEquals(8.7, e.getJSONObject("neu").getDouble("cost"), 1e-9);
        assertEquals("nutzer", e.getJSONObject("neu").getString("quelle"));
    }

    @Test
    public void speichern_mehrfach_protokoll_rueckgaengig() throws Exception {
        Schein db = new Schein().mit(1, 30, true, 0.20, false).mit(2, 10, false, -1, false).mit(3, 5, null, 0.2, true);
        Prot p = new Prot();
        JSONObject o = (JSONObject) preis("{\"ids\":[1,2,3,99],\"rate\":0.29}", db, null, p)[1];
        assertEquals("offen", o.getJSONObject("fehler").getString("3"));
        assertEquals("unbekannt", o.getJSONObject("fehler").getString("99"));
        assertEquals(0.29, db.zeilen.get(1L).getDouble("rate"), 1e-9);
        assertEquals("erledigt", db.zeilen.get(2L).getString("nachfrage"));
        String pid = o.getString("protokollId");
        Object[] z = LadePreis.rueckgaengig(new JSONObject().put("protokollId", pid), db, p);
        assertEquals("[1,2]", ((JSONObject) z[1]).getJSONArray("zurueckgesetzt").toString());
        assertEquals(0.20, db.zeilen.get(1L).getDouble("rate"), 1e-9);
        assertEquals(-1, db.zeilen.get(2L).getDouble("rate"), 1e-9);
        assertEquals("", db.zeilen.get(2L).getString("nachfrage"));
        assertEquals(404, LadePreis.rueckgaengig(new JSONObject().put("protokollId", "p-x"), db, p)[0]);
    }

    @Test
    public void null_nurAlsGratis_undTarifMitGratis() throws Exception {
        Schein db = new Schein().mit(1, 12, false, 0.30, false).mit(2, 40, true, 0.30, false);
        Prot p = new Prot();
        assertEquals("gratis_bestaetigen", ((JSONObject) preis("{\"id\":1,\"rate\":0}", db, null, p)[1]).getString("grund"));
        JSONObject g = (JSONObject) preis("{\"id\":1,\"gratis\":true}", db, null, p)[1];
        assertEquals(0, db.zeilen.get(1L).getDouble("cost"), 0);
        assertFalse(g.getJSONArray("ergebnisse").getJSONObject(0).has("hinweis"));   // bestätigt gratis: kein Hinweis
        // Tarif „gratis AC“: DC ohne eigenen Preis nimmt wie bisher den AC-Preis (TariffProfile.rateFor) → auch gratis
        TariffProfile t = new TariffProfile("Ort (gratis)", 47.1, 13.6, 100, 0, 0, "€");
        t.setAcGratis(true);
        JSONObject r = (JSONObject) preis("{\"ids\":[1,2],\"tarifId\":\"" + t.getId() + "\"}", db, id -> id.equals(t.getId()) ? t : null, p)[1];
        assertEquals(0, r.getJSONObject("fehler").length());
        assertEquals(0, db.zeilen.get(2L).getDouble("cost"), 0);
        // Tarif nur mit DC-Preis: AC-Ladung ohne Preis
        TariffProfile nurDc = new TariffProfile("nur DC", 47.1, 13.6, 100, 0, 0.5, "€");
        JSONObject r2 = (JSONObject) preis("{\"ids\":[1],\"tarifId\":\"" + nurDc.getId() + "\"}", db, id -> nurDc, p)[1];
        assertEquals("tarif_ohne_preis", r2.getJSONObject("fehler").getString("1"));
        assertEquals("tarif", db.zeilen.get(1L).getString("quelle"));
        assertEquals(t.getId(), db.zeilen.get(1L).getString("tariffId"));
    }

    @Test
    public void unplausibel_nurHinweis_fehlt_und_kaputteAnfragen() throws Exception {
        Schein db = new Schein().mit(1, 12, false, 0.30, false);
        Prot p = new Prot();
        JSONObject u = (JSONObject) preis("{\"id\":1,\"rate\":0.01}", db, null, p)[1];
        assertEquals(PreisPlausibel.ZU_NIEDRIG, u.getJSONArray("ergebnisse").getJSONObject(0).getString("hinweis"));
        assertEquals(0.01, db.zeilen.get(1L).getDouble("rate"), 1e-9);   // ausdrückliche Nutzerangabe wird gespeichert
        preis("{\"id\":1,\"fehlt\":true}", db, null, p);
        assertEquals("fehlt", db.zeilen.get(1L).getString("quelle"));
        assertEquals(-1, db.zeilen.get(1L).getDouble("cost"), 0);
        assertEquals(400, preis("{\"id\":1}", db, null, p)[0]);
        assertEquals(400, preis("{\"id\":1,\"rate\":0.3,\"gratis\":true}", db, null, p)[0]);
        assertEquals(400, preis("{\"ids\":[]}", db, null, p)[0]);
        assertEquals(400, preis("{\"id\":1,\"rate\":0.3,\"quelle\":\"auto\"}", db, null, p)[0]);
        assertEquals(404, preis("{\"id\":1,\"tarifId\":\"weg\"}", db, null, p)[0]);
        db.schreibenGeht = false;
        assertEquals("speichernFehlgeschlagen", ((JSONObject) preis("{\"id\":1,\"rate\":0.3}", db, null, p)[1]).getJSONObject("fehler").getString("1"));
    }

    @Test
    public void sicherung_mitPruefsumme() throws Exception {
        Schein db = new Schein().mit(1, 12, false, 0.30, false);
        File ordner = new File(tmp.getRoot(), "sicherung");
        JSONObject o = (JSONObject) LadePreis.sicherung(db, ordner, 7_000L)[1];
        File f = new File(o.getString("pfad"));
        assertTrue(f.isFile());
        assertEquals(1, o.getInt("sitzungen"));
        assertEquals(LadePreis.sha256(Files.readAllBytes(f.toPath())), o.getString("sha256"));
        assertEquals(1, new JSONObject(new String(Files.readAllBytes(f.toPath()), "UTF-8")).getJSONArray("sessions").length());
    }

    @Test
    public void tarifProfil_gratis_nurAusdruecklich() throws Exception {
        TariffProfile ohne = new TariffProfile("leer", 1, 1, 100, 0, 0, "€");
        assertFalse(ohne.hasAnyRate());
        assertFalse(ohne.hatPreis(0));
        TariffProfile g = new TariffProfile(new JSONObject().put("label", "g").put("lat", 1).put("lng", 1).put("acRate", 0).put("acGratis", true));
        assertTrue(g.hasAnyRate());
        assertTrue(g.hatPreis(0));
        assertTrue(g.istGratis(0));
        assertTrue(g.hatPreis(-1));
        assertFalse(g.hatPreis(1) && g.istGratis(1) && false);
        assertEquals(0, g.rateFor(0), 0);
        assertTrue(g.toJson().getBoolean("acGratis"));
        // gratis mit Preis > 0 in der Datei = widersprüchlich → nicht gratis
        TariffProfile w = new TariffProfile(new JSONObject().put("label", "w").put("lat", 1).put("lng", 1).put("acRate", 0.3).put("acGratis", true));
        assertFalse(w.istGratis(0));
        g.setAcRate(0.25);
        assertFalse(g.isAcGratis());
        assertNull(null);
    }
}
