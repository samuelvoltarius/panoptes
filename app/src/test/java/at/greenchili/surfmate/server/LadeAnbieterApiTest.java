package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.charging.LadeAnbieter;
import at.greenchili.surfmate.charging.LadeKosten;
import at.greenchili.surfmate.charging.LadeNachfrage;
import at.greenchili.surfmate.charging.LadePreis;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 0.1.43.15: {@code /api/laden/anbieter} und die Ladenachfrage mit eigenem Anbieter – Anlegen (gespeichert und
 * zurückgelesen), Auswählen, Preis überschreiben, Ort merken, Kosten im Monatsbericht. Synthetisch, Testwerte.
 */
public class LadeAnbieterApiTest {

    static final class Umg implements LadenApiHandler.Umgebung {
        final JSONObject cfg;
        final Map<Long, JSONObject> zeilen = new HashMap<>();
        final List<Object[]> orte = new ArrayList<>();
        final List<String> geschriebeneAbschnitte = new ArrayList<>();
        boolean schreibenScheitert;

        Umg() throws Exception {
            // Bestand: Vorlieben und eine Ladekarte bleiben unberührt
            cfg = new JSONObject().put("tripAnalytics", new JSONObject().put("currency", "€"))
                    .put("laden", new JSONObject().put("karten", new JSONArray().put(new JSONObject().put("id", 4).put("anzeige", "Karte X")))
                            .put("nachfragen", true));
            // Mi 18.11., DC, 20,3 kWh, ohne bekannten Ort (wie Alfreds Fall), mit Position
            zeilen.put(9L, new JSONObject().put("id", 9).put("start", LadeNachfrage.SEIT + 1000).put("ende", LadeNachfrage.SEIT + 9000)
                    .put("kwh", 20.3).put("dc", true).put("rate", -1.0).put("cost", -1.0).put("currency", "€").put("tariffId", "")
                    .put("tariffLabel", "").put("quelle", "").put("nachfrage", "").put("lat", 47.1).put("lng", 13.1));
        }
        public JSONObject config() {
            try { return new JSONObject(cfg.toString()); } catch (Exception e) { throw new RuntimeException(e); }
        }
        public boolean schreibe(String a, Map<String, Object> w) {
            if (schreibenScheitert) return false;
            try {
                JSONObject s = cfg.optJSONObject(a);
                if (s == null) { s = new JSONObject(); cfg.put(a, s); }
                for (Map.Entry<String, Object> e : w.entrySet()) s.put(e.getKey(), e.getValue());
                geschriebeneAbschnitte.add(a);
                return true;
            } catch (Exception e) { return false; }
        }
        public boolean abschnittLeeren(String a) { cfg.remove(a); return true; }
        public boolean extern() { return false; }
        public Object[] planerGet(String op, String[] k) { return null; }
        public String[] planerKopf() { return null; }
        public LadePreis.Db preisDb() {
            return new LadePreis.Db() {
                public JSONObject snapshot(long id) {
                    try { return zeilen.containsKey(id) ? new JSONObject(zeilen.get(id).toString()) : null; } catch (Exception e) { return null; }
                }
                public boolean setzen(long id, double rate, String w, String tid, String tn, String q, String n) {
                    try {
                        JSONObject z = zeilen.get(id);
                        // wie SocHistoryDatabase.setChargingSessionPrice: cost = kWh × rate
                        z.put("rate", rate).put("cost", rate < 0 ? -1 : z.getDouble("kwh") * rate).put("currency", w)
                                .put("tariffId", tid).put("tariffLabel", tn).put("quelle", q).put("nachfrage", n);
                        return true;
                    } catch (Exception e) { return false; }
                }
                public JSONObject export() throws Exception { return new JSONObject(); }
            };
        }
        public LadePreis.Tarife tarife() { return id -> null; }
        public LadePreis.Protokoll protokoll() {
            return new LadePreis.Protokoll() {
                public boolean speichere(JSONObject e) { return true; }
                public JSONObject lies(String id) { return null; }
            };
        }
        public String standardWaehrung() { return "€"; }
        public JSONArray ladungenSeit(long seit) {
            JSONArray a = new JSONArray();
            for (JSONObject z : zeilen.values()) a.put(z);
            return a;
        }
        public boolean steht() { return true; }
        public String ortMerken(String name, double lat, double lng, int r, double rate, boolean dc, String w, boolean gratis) {
            orte.add(new Object[] {name, rate, dc, gratis});
            return "ort1";
        }
    }

    private static Object[] r(Umg u, String m, String p, String body) throws Exception {
        return LadenApiHandler.bearbeite(m, p, "", body, u);
    }

    @Test
    public void anlegen_speichertEigenenAbschnitt_undLiestZurueck() throws Exception {
        Umg u = new Umg();
        JSONObject leer = (JSONObject) r(u, "GET", "/api/laden/anbieter", null)[1];
        assertEquals(0, leer.getJSONArray("anbieter").length());

        Object[] a = r(u, "POST", "/api/laden/anbieter", "{\"name\":\"Testanbieter\",\"preisDc\":0.5,\"karte\":false}");
        assertEquals(200, a[0]);
        JSONObject o = (JSONObject) a[1];
        assertTrue(o.getBoolean("neu"));
        assertEquals("a1", o.getJSONObject("anbieter").getString("id"));
        assertEquals("[\"ladeanbieter\"]", new JSONArray(u.geschriebeneAbschnitte).toString());
        // Vorlieben (Planer-Karten) unverändert
        assertEquals(4, u.cfg.getJSONObject("laden").getJSONArray("karten").getJSONObject(0).getInt("id"));
        assertFalse(u.cfg.getJSONObject("laden").has("anbieter"));
        assertEquals(1, ((JSONObject) r(u, "GET", "/api/laden/anbieter", null)[1]).getJSONArray("anbieter").length());

        assertEquals(400, r(u, "POST", "/api/laden/anbieter", "{\"name\":\"\",\"preisDc\":0.5}")[0]);
        assertEquals(400, r(u, "POST", "/api/laden/anbieter", "{\"name\":\"Ohne Preis\"}")[0]);
        assertEquals(400, r(u, "POST", "/api/laden/anbieter", "kaputt")[0]);
        assertEquals(405, r(u, "DELETE", "/api/laden/anbieter", null)[0]);
        u.schreibenScheitert = true;
        assertEquals(500, r(u, "POST", "/api/laden/anbieter", "{\"name\":\"Neu\",\"preisAc\":0.3}")[0]);
        // „Vorlieben zurücksetzen“ leert nur „laden“, die Anbieter bleiben
        u.schreibenScheitert = false;
        assertEquals(200, r(u, "POST", "/api/laden/zuruecksetzen", "{}")[0]);
        assertEquals(1, ((JSONObject) r(u, "GET", "/api/laden/anbieter", null)[1]).getJSONArray("anbieter").length());
    }

    @Test
    public void auswaehlen_ueberschreiben_ortMerken_undKostenImMonatsbericht() throws Exception {
        Umg u = new Umg();
        r(u, "POST", "/api/laden/anbieter", "{\"name\":\"Testanbieter\",\"preisAc\":0.39,\"preisDc\":0.5}");
        JSONObject e = ((JSONObject) r(u, "GET", "/api/laden/nachfrage", null)[1]).getJSONArray("offen").getJSONObject(0);
        JSONObject wahl = e.getJSONArray("auswahl").getJSONObject(0);
        assertEquals("anbieter", wahl.getString("art"));
        assertEquals(0.5, wahl.getDouble("preisKwh"), 1e-9);
        assertEquals("karte", e.getJSONArray("auswahl").getJSONObject(1).getString("art"));   // Ladekarte weiter da

        // Preis vom Anbieter auf 0.52 überschrieben, Ort merken
        Object[] a = r(u, "POST", "/api/laden/nachfrage",
                "{\"id\":9,\"art\":\"anbieter\",\"anbieterId\":\"a1\",\"preisKwh\":0.52,\"ortMerken\":{\"name\":\"Testanbieter\"}}");
        assertEquals(200, a[0]);
        JSONObject z = u.zeilen.get(9L);
        assertEquals(0.52, z.getDouble("rate"), 1e-9);
        assertEquals("Testanbieter", z.getString("tariffLabel"));
        assertEquals("nutzer", z.getString("quelle"));
        assertEquals("erledigt", z.getString("nachfrage"));
        assertEquals("ort1", ((JSONObject) a[1]).getString("ortMerken"));
        assertEquals("Testanbieter", u.orte.get(0)[0]);
        assertEquals(0.52, (Double) u.orte.get(0)[1], 1e-9);
        assertEquals(Boolean.TRUE, u.orte.get(0)[2]);   // DC-Preis für den Ortskreis
        assertEquals(0, ((JSONObject) r(u, "GET", "/api/laden/nachfrage", null)[1]).getInt("anzahl"));

        // Monatsbericht/Kosten: dieselbe Ladung in Sitzungsform (chargingRowToJson) → 20,3 kWh × 0,52 = 10,56 €
        JSONObject s = new JSONObject().put("energyAdded", z.getDouble("kwh")).put("cost", z.getDouble("cost"))
                .put("electricityRate", z.getDouble("rate")).put("currency", z.getString("currency"))
                .put("tariffId", z.getString("tariffId")).put("preisQuelle", z.getString("quelle"));
        JSONObject k = LadeKosten.kosten(new JSONArray().put(s), 100, LadeKosten.MONAT, 0, 1);
        assertEquals(10.56, k.getDouble("kosten_summe"), 0.001);
        assertEquals(10.56, k.getDouble("kosten_je_100km"), 0.001);
        assertEquals(0, k.getInt("ladungen_ohne_preis"));
        assertTrue(k.getBoolean("vollstaendig"));
    }

    @Test
    public void unbekannterAnbieter_wirdAbgelehnt_undGuardsErlaubenCockpit() throws Exception {
        Umg u = new Umg();
        assertEquals(400, r(u, "POST", "/api/laden/nachfrage", "{\"id\":9,\"art\":\"anbieter\",\"anbieterId\":\"a7\",\"preisKwh\":0.5}")[0]);
        assertEquals(-1.0, u.zeilen.get(9L).getDouble("rate"), 0);
        assertTrue(ExternGuard.erlaubt("GET", "/api/laden/anbieter"));
        assertTrue(ExternGuard.erlaubt("POST", "/api/laden/anbieter"));
        assertTrue(WebHandyGuard.PC_WEB_SCHREIBEN.contains("/api/laden/anbieter"));
        assertEquals(LadeAnbieter.ABSCHNITT, "ladeanbieter");
    }
}
