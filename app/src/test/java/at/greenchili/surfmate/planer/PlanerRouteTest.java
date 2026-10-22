package at.greenchili.surfmate.planer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.testutil.Quelltext;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Koordination 0.1.9, Punkt 9: Route vom Planer anzeigen und nur per Tipp übernehmen (Vertrag §3/3a/3b). */
public class PlanerRouteTest {

    static final String PLAN = "{\"titel\":\"Salzburg → Wien\",\"ziel\":{\"lat\":48.2,\"lon\":16.37,\"name\":\"Wien\"},"
            + "\"km\":302.7,\"soc_ankunft\":18,\"stopps\":[{\"name\":\"Ionity Ansfelden\",\"lat\":48.2,\"lon\":14.3}],"
            + "\"erstellt\":\"2026-10-21T08:00:00Z\",\"schon_abgeholt\":false}";

    final List<String> calls = new ArrayList<>();
    final Map<String, String> abl = new HashMap<>();
    String[] token = {"X-Geraete-Token", "x"};
    boolean netzWeg;
    boolean neuGeladen;

    PlanerRoute route() {
        return new PlanerRoute(new PlanerRoute.Umgebung() {
            public Object[] http(String m, String op, String[] t, String body) {
                calls.add(m + " " + op + (body == null ? "" : " " + body));
                if (netzWeg) return null;
                try {
                    if (op.startsWith("meine-route") || op.startsWith("auto-abholen")) {
                        return new Object[]{200, new JSONObject().put("plan", new JSONObject(PLAN))};
                    }
                    return new Object[]{200, new JSONObject().put("ok", true)};
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            public String[] token() { return token; }
            public String autoId() { return "a-0123456789abcdef"; }
            public String angenommen() { return abl.getOrDefault("routeAngenommen", ""); }
            public String abgelehnt() { return abl.getOrDefault("routeAbgelehnt", ""); }
            public void merke(String k, String id) { abl.put(k, id); }
            public boolean unterwegsAn() { return false; }
            public void routeNeuLaden() { neuGeladen = true; }
        });
    }

    @Test
    public void ohnePlanerKeinAngebot() throws Exception {
        token = null;
        Object[] r = route().status(0);
        assertEquals(404, r[0]);
        assertEquals("kein_planer", ((JSONObject) r[1]).getString("grund"));
    }

    @Test
    public void zeigtRouteNurLesend() throws Exception {
        PlanerRoute p = route();
        JSONObject o = (JSONObject) p.status(1000)[1];
        assertTrue(o.getBoolean("vorhanden"));
        assertEquals("2026-10-21T08:00:00Z", o.getString("id"));
        assertEquals("Wien", o.getJSONObject("ziel").getString("name"));
        assertEquals(16.37, o.getJSONObject("ziel").getDouble("lng"), 1e-9);
        assertEquals("laden", o.getJSONArray("stopps").getJSONObject(0).getString("art"));
        assertEquals(18, o.getInt("ankunftAkku"));
        assertEquals(java.time.Instant.parse("2026-10-21T08:00:00Z").toEpochMilli(), o.getLong("erstellt"));
        assertTrue(o.isNull("status"));
        assertEquals("GET meine-route?auto=a-0123456789abcdef", calls.get(0));   // nie auto-abholen beim Anzeigen
        p.status(1000 + 29_000);
        assertEquals(1, calls.size());                                        // Zwischenspeicher 30 s
    }

    @Test
    public void annehmenNurPerTippUndMeldet() throws Exception {
        PlanerRoute p = route();
        assertEquals("anderer_stand", p.annehmen("2026-01-01T00:00:00Z", 0).getString("grund"));
        JSONObject r = p.annehmen("2026-10-21T08:00:00Z", 0);
        assertTrue(r.getBoolean("ok"));
        assertEquals("unterwegs_aus", r.getString("hinweis"));
        assertEquals("2026-10-21T08:00:00Z", abl.get("routeAngenommen"));
        assertTrue(neuGeladen);
        assertTrue(calls.contains("GET auto-abholen?auto=a-0123456789abcdef"));
        assertTrue(calls.stream().anyMatch(c -> c.startsWith("POST auto-status") && c.contains("\"angenommen\"")));
        assertEquals("angenommen", ((JSONObject) p.status(100_000)[1]).getString("status"));
    }

    @Test
    public void ablehnenBietetNichtMehrAn() throws Exception {
        PlanerRoute p = route();
        assertTrue(p.ablehnen("2026-10-21T08:00:00Z", 0).getBoolean("ok"));
        assertTrue(calls.stream().anyMatch(c -> c.startsWith("POST auto-status") && c.contains("\"abgelehnt\"")));
        assertFalse(((JSONObject) p.status(0)[1]).getBoolean("vorhanden"));
    }

    @Test
    public void netzWegEhrlichUndMitPause() throws Exception {
        netzWeg = true;
        PlanerRoute p = route();
        assertEquals("netz", ((JSONObject) p.status(0)[1]).getString("grund"));
        p.status(1000);
        assertEquals(1, calls.size());                                        // Backoff: kein zweiter Abruf
    }

    @Test
    public void rangeWatchUeberwachtNurAngenommene() throws Exception {
        String rw = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/reichweite/RangeWatchDienst.java"));
        assertTrue(rw.contains("!at.greenchili.surfmate.planer.PlanerRoute.istAngenommen(plan)"));
        String navi = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/NaviApiHandler.java"));
        assertTrue(navi.contains("\"/api/navi/planer-route/annehmen\""));
        assertTrue(navi.indexOf("AuthMiddleware.isExternRequest()") > 0);
    }
}
