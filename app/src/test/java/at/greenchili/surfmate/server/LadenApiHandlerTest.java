package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** LADEN_VERTRAG.md §A4/A5: /api/laden, Kataloge, Übernahme aus dem Planer-Konto. */
public class LadenApiHandlerTest {

    /** Config im Speicher, Planer-Antworten vorgegeben. */
    static final class Schein implements LadenApiHandler.Umgebung {
        JSONObject root = new JSONObject();
        boolean extern, schreibenGeht = true;
        String[] kopf = {"X-Geraete-Token", "ab"};
        final Map<String, Object[]> planer = new HashMap<>();
        final List<String> abgefragt = new ArrayList<>();
        final List<String[]> koepfe = new ArrayList<>();
        int schreibvorgaenge;

        public JSONObject config() {
            try { return new JSONObject(root.toString()); } catch (Exception e) { throw new RuntimeException(e); }
        }
        public boolean schreibe(String abschnitt, Map<String, Object> werte) {
            if (!schreibenGeht) return false;
            schreibvorgaenge++;
            try {
                JSONObject s = root.optJSONObject(abschnitt);
                if (s == null) { s = new JSONObject(); root.put(abschnitt, s); }
                for (Map.Entry<String, Object> e : werte.entrySet()) s.put(e.getKey(), e.getValue());
            } catch (Exception e) { return false; }
            return true;
        }
        public boolean abschnittLeeren(String abschnitt) { root.remove(abschnitt); return true; }
        public boolean extern() { return extern; }
        public Object[] planerGet(String op, String[] k) { abgefragt.add(op); koepfe.add(k); return planer.get(op); }
        public String[] planerKopf() { return kopf; }
    }

    private static Object[] r(Schein s, String m, String p, String body) throws Exception {
        String q = p.contains("?") ? p.substring(p.indexOf('?') + 1) : "";
        String po = p.contains("?") ? p.substring(0, p.indexOf('?')) : p;
        return LadenApiHandler.bearbeite(m, po, q, body, s);
    }

    private static JSONObject j(Object[] r) { return (JSONObject) r[1]; }

    @Test
    public void get_standard_dannPut_autoIstQuelle() throws Exception {
        Schein s = new Schein();
        Object[] g = r(s, "GET", "/api/laden", null);
        assertEquals(200, g[0]);
        assertEquals("standard", j(g).getString("quelle"));
        String etag = j(g).getString("etag");

        Object[] p = r(s, "PUT", "/api/laden", "{\"etag\":\"" + etag + "\",\"laden\":{\"karten\":[4],\"netze\":[\"Lidl\"],\"nurOffen\":true,\"minKw\":50}}");
        assertEquals(j(p).toString(), 200, p[0]);
        JSONObject a = j(p);
        assertEquals("auto", a.getString("quelle"));
        assertFalse(a.isNull("stand"));
        assertEquals("[4]", a.getJSONObject("planer").getJSONArray("karten").toString());
        assertEquals(50, a.getJSONObject("planer").getJSONObject("filter").getInt("min_kw"));
        // zurückgelesen aus der „gespeicherten“ Config
        assertEquals(4, s.root.getJSONObject("laden").getJSONArray("karten").getJSONObject(0).getInt("id"));
    }

    @Test
    public void put_einFehler_nichtsGespeichert() throws Exception {
        Schein s = new Schein();
        Object[] p = r(s, "PUT", "/api/laden", "{\"laden\":{\"minKw\":50,\"umwegMaxMin\":99,\"stand\":\"x\",\"farbe\":1}}");
        assertEquals(400, p[0]);
        JSONObject f = j(p).getJSONObject("fehler");
        assertEquals("max 60", f.getString("laden.umwegMaxMin"));
        assertEquals("nurLesend", f.getString("laden.stand"));
        assertEquals("unbekannt", f.getString("laden.farbe"));
        assertEquals(0, s.schreibvorgaenge);
        assertFalse(s.root.has("laden"));
    }

    @Test
    public void put_veraltet_409_undKaputteAnfragen() throws Exception {
        Schein s = new Schein();
        assertEquals(409, r(s, "PUT", "/api/laden", "{\"etag\":\"l-alt\",\"laden\":{\"nurOffen\":true}}")[0]);
        assertEquals(400, r(s, "PUT", "/api/laden", "kein json")[0]);
        assertEquals(400, r(s, "PUT", "/api/laden", "{}")[0]);
        s.schreibenGeht = false;
        assertEquals(500, r(s, "PUT", "/api/laden", "{\"laden\":{\"nurOffen\":true}}")[0]);
        assertEquals(405, r(s, "DELETE", "/api/laden", null)[0]);
        assertNull(r(s, "GET", "/api/ladenx", null));
    }

    @Test
    public void zuruecksetzen_nurMitLogin() throws Exception {
        Schein s = new Schein();
        r(s, "PUT", "/api/laden", "{\"laden\":{\"nurOffen\":true}}");
        s.extern = true;
        assertEquals(403, r(s, "POST", "/api/laden/zuruecksetzen", null)[0]);
        assertEquals(403, r(s, "GET", "/api/laden/aus-konto", null)[0]);
        // das Cockpit darf lesen und pflegen
        assertEquals(200, r(s, "GET", "/api/laden", null)[0]);
        assertEquals(200, r(s, "PUT", "/api/laden", "{\"laden\":{\"umwegMaxMin\":5}}")[0]);
        s.extern = false;
        Object[] z = r(s, "POST", "/api/laden/zuruecksetzen", null);
        assertEquals(200, z[0]);
        assertEquals("standard", j(z).getString("quelle"));
    }

    @Test
    public void kataloge_durchgereicht_ohneSchluessel() throws Exception {
        Schein s = new Schein();
        s.planer.put("tarife", new Object[] {200, "{\"tarife\":[{\"tarif_id\":4}]}"});
        s.planer.put("betreiber?q=Lidl+Plus", new Object[] {200, "{\"betreiber\":[]}"});
        assertEquals(200, r(s, "GET", "/api/laden/tarife", null)[0]);
        assertEquals(200, r(s, "GET", "/api/laden/betreiber?q=Lidl%20Plus", null)[0]);
        assertEquals("betreiber?q=Lidl+Plus", s.abgefragt.get(1));
        assertNull(s.koepfe.get(0));   // öffentliche Kataloge: kein Schlüssel
        assertNull(s.koepfe.get(1));
        assertEquals(400, r(s, "GET", "/api/laden/betreiber?q=", null)[0]);
        assertEquals(503, r(s, "GET", "/api/laden/betreiber?q=xyz", null)[0]);   // kein Planer
        s.planer.put("tarife", new Object[] {504, "<html>"});
        assertEquals(502, r(s, "GET", "/api/laden/tarife", null)[0]);
    }

    @Test
    public void ausKonto_vorschauDannUebernahme() throws Exception {
        Schein s = new Schein();
        s.planer.put("meine-karten", new Object[] {200, "{\"ok\":true,\"karten\":[{\"tarif_id\":9,\"msp\":\"IONITY\",\"tarif\":\"Power\"},{\"tarif_id\":12,\"msp\":\"Lidl\",\"tarif\":\"Plus\"}]}"});
        s.planer.put("meine-ladenetze", new Object[] {200, "{\"netze\":[\"Lidl\"],\"modus\":\"bevorzugen\",\"umweg_max_min\":10,\"nur_offen\":true,\"gespeichert\":true}"});
        Object[] v = r(s, "GET", "/api/laden/aus-konto", null);
        assertEquals(200, v[0]);
        assertEquals("IONITY · Power", j(v).getJSONObject("konto").getJSONArray("karten").getJSONObject(0).getString("anzeige"));
        assertTrue(j(v).getJSONArray("aenderungen").length() >= 3);
        assertFalse(s.root.has("laden"));                                  // Vorschau schreibt nichts
        assertEquals("X-Geraete-Token", s.koepfe.get(0)[0]);

        assertEquals(400, r(s, "POST", "/api/laden/aus-konto", "{}")[0]);  // ohne Bestätigung nichts
        Object[] u = r(s, "POST", "/api/laden/aus-konto", "{\"bestaetigt\":true}");
        assertEquals(j(u).toString(), 200, u[0]);
        assertEquals("[9,12]", j(u).getJSONObject("planer").getJSONArray("karten").toString());
        assertTrue(j(u).getJSONObject("laden").getBoolean("nurOffen"));
    }

    @Test
    public void ausKonto_ehrlicheGruende() throws Exception {
        Schein s = new Schein();
        s.kopf = null;
        assertEquals("nicht_verbunden", j(r(s, "GET", "/api/laden/aus-konto", null)).getString("grund"));
        s.kopf = new String[] {"X-Geraete-Token", "ab"};
        assertEquals(503, r(s, "GET", "/api/laden/aus-konto", null)[0]);
        s.planer.put("meine-karten", new Object[] {401, "{}"});
        s.planer.put("meine-ladenetze", new Object[] {401, "{}"});
        Object[] k = r(s, "GET", "/api/laden/aus-konto", null);
        assertEquals(403, k[0]);
        assertEquals("kein_leserecht", j(k).getString("grund"));
        // Konto ohne gespeicherte Netze: nur Karten
        JSONObject l = LadenApiHandler.kontoAlsLaden(new JSONObject("{\"karten\":[]}"), new JSONObject("{\"gespeichert\":false,\"netze\":[\"X\"]}"));
        assertFalse(l.has("netze"));
    }

    @Test
    public void externGuard_ladenJa_uebernahmeNie() {
        assertTrue(ExternGuard.erlaubt("GET", "/api/laden"));
        assertTrue(ExternGuard.erlaubt("PUT", "/api/laden"));
        assertFalse(ExternGuard.erlaubt("POST", "/api/laden/zuruecksetzen"));
        assertFalse(ExternGuard.erlaubt("POST", "/api/laden/aus-konto"));
        assertFalse(ExternGuard.erlaubt("GET", "/api/laden/tarife"));
        assertFalse(ExternGuard.erlaubt("GET", "/api/uebernahme/overdrive"));
        assertFalse(ExternGuard.erlaubt("POST", "/api/uebernahme/overdrive"));
    }
}
