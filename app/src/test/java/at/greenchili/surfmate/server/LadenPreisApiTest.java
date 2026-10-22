package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.charging.LadeNachfrage;
import at.greenchili.surfmate.charging.LadePreis;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** /api/laden/preis, /sicherung, /nachfrage: Rechte (Login vs. Kopplung), Ort merken, kein-preis, später. Synthetisch. */
public class LadenPreisApiTest {

    static final class Umg implements LadenApiHandler.Umgebung {
        boolean extern;
        final Map<Long, JSONObject> zeilen = new HashMap<>();
        final List<Object[]> orte = new ArrayList<>();
        final Map<Long, String> stand = new HashMap<>();

        Umg() throws Exception {
            zeilen.put(7L, new JSONObject().put("id", 7).put("start", LadeNachfrage.SEIT + 1000).put("ende", LadeNachfrage.SEIT + 9000)
                    .put("kwh", 20.0).put("dc", false).put("rate", -1.0).put("cost", -1.0).put("currency", "€").put("tariffId", "")
                    .put("tariffLabel", "").put("quelle", "").put("nachfrage", "").put("lat", 47.1).put("lng", 13.1));
        }
        public JSONObject config() {
            try { return new JSONObject().put("tripAnalytics", new JSONObject().put("currency", "€")); } catch (Exception e) { throw new RuntimeException(e); }
        }
        public boolean schreibe(String a, Map<String, Object> w) { return true; }
        public boolean abschnittLeeren(String a) { return true; }
        public boolean extern() { return extern; }
        public Object[] planerGet(String op, String[] k) { return null; }
        public String[] planerKopf() { return null; }
        public LadePreis.Db preisDb() {
            return new LadePreis.Db() {
                public JSONObject snapshot(long id) {
                    try { return zeilen.containsKey(id) ? new JSONObject(zeilen.get(id).toString()) : null; } catch (Exception e) { return null; }
                }
                public boolean setzen(long id, double rate, String w, String tid, String tn, String q, String n) {
                    try { zeilen.get(id).put("rate", rate).put("tariffLabel", tn).put("quelle", q).put("nachfrage", n); return true; }
                    catch (Exception e) { return false; }
                }
                public JSONObject export() throws Exception { return new JSONObject().put("sessions", new JSONArray()).put("daily", new JSONArray()); }
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
        public boolean nachfrageSetzen(long id, String s) { stand.put(id, s); return zeilen.containsKey(id); }
        public String ortMerken(String name, double lat, double lng, int r, double rate, boolean dc, String w, boolean gratis) {
            orte.add(new Object[] {name, lat, lng, r, rate, dc, gratis});
            return "neu1";
        }
    }

    private static Object[] r(Umg u, String m, String p, String body) throws Exception {
        return LadenApiHandler.bearbeite(m, p, "", body, u);
    }

    @Test
    public void preisUndSicherung_nurMitLogin() throws Exception {
        Umg u = new Umg();
        u.extern = true;
        assertEquals(403, r(u, "POST", "/api/laden/preis", "{\"id\":7,\"rate\":0.3}")[0]);
        assertEquals(403, r(u, "POST", "/api/laden/sicherung", "{}")[0]);
        assertEquals(403, r(u, "POST", "/api/laden/preis/rueckgaengig", "{}")[0]);
        assertFalse(ExternGuard.erlaubt("POST", "/api/laden/preis"));
        assertFalse(ExternGuard.erlaubt("POST", "/api/laden/sicherung"));
        u.extern = false;
        Object[] p = r(u, "POST", "/api/laden/preis", "{\"ids\":[7],\"rate\":0.29,\"dryRun\":true}");
        assertEquals(200, p[0]);
        assertEquals(-1, u.zeilen.get(7L).getDouble("rate"), 0);   // Probelauf
        assertEquals(405, r(u, "GET", "/api/laden/preis", null)[0]);
    }

    @Test
    public void nachfrage_cockpitDarfLesenUndBeantworten_ortMerken() throws Exception {
        Umg u = new Umg();
        u.extern = true;   // Cockpit über die Kopplung
        assertTrue(ExternGuard.erlaubt("GET", "/api/laden/nachfrage"));
        assertTrue(ExternGuard.erlaubt("POST", "/api/laden/nachfrage"));
        JSONObject l = (JSONObject) r(u, "GET", "/api/laden/nachfrage", null)[1];
        assertEquals(1, l.getInt("anzahl"));
        Object[] a = r(u, "POST", "/api/laden/nachfrage", "{\"id\":7,\"art\":\"zuhause_gratis\",\"ortMerken\":{\"name\":\"Daheim\",\"radiusM\":80}}");
        assertEquals(200, a[0]);
        assertEquals("neu1", ((JSONObject) a[1]).getString("ortMerken"));
        assertEquals(0, u.zeilen.get(7L).getDouble("rate"), 0);
        assertEquals("nutzer", u.zeilen.get(7L).getString("quelle"));
        Object[] ort = u.orte.get(0);
        assertEquals("Daheim", ort[0]);
        assertEquals(80, ort[3]);
        assertEquals(Boolean.TRUE, ort[6]);   // AC gratis
        assertEquals(0, ((JSONObject) r(u, "GET", "/api/laden/nachfrage", null)[1]).getInt("anzahl"));
    }

    @Test
    public void spaeter_keinPreis_fehler() throws Exception {
        Umg u = new Umg();
        assertEquals(200, r(u, "POST", "/api/laden/nachfrage/spaeter", "{\"id\":7}")[0]);
        assertEquals("spaeter", u.stand.get(7L));
        assertEquals(404, r(u, "POST", "/api/laden/nachfrage/spaeter", "{\"id\":8}")[0]);
        assertEquals(200, r(u, "POST", "/api/laden/nachfrage/kein-preis", "{\"id\":7}")[0]);
        assertEquals("fehlt", u.zeilen.get(7L).getString("quelle"));
        assertEquals("erledigt", u.zeilen.get(7L).getString("nachfrage"));
        assertEquals(400, r(u, "POST", "/api/laden/nachfrage", "{\"id\":7,\"art\":\"adhoc\"}")[0]);
        assertEquals(400, r(u, "POST", "/api/laden/nachfrage", "kaputt")[0]);
    }
}
