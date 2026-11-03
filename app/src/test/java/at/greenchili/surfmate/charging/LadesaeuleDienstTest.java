package at.greenchili.surfmate.charging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.monitor.SocHistoryDatabase;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Laufzeit-Entscheidungen der Ladesäulen-Erkennung: Schalter aus = keine Anfrage, Datenschutz-Fälle, gemerkte Säule
 * ohne Anfrage, genau eine Anfrage je Ladeende, Nachtragen je Zelle. Testort Linz, synthetische Säulen.
 */
public class LadesaeuleDienstTest {

    static final double LAT = LadesaeuleZuordnungTest.LAT, LNG = LadesaeuleZuordnungTest.LNG;

    /** Zählt Anfragen und merkt sich die Bodies. */
    static final class ZaehlPlaner implements LadesaeuleDienst.Planer {
        final List<JSONObject> bodies = new ArrayList<>();
        final JSONObject antwort;

        ZaehlPlaner(JSONObject antwort) {
            this.antwort = antwort;
        }

        @Override
        public JSONObject umkreis(JSONObject body) {
            bodies.add(body);
            return antwort;
        }
    }

    static JSONObject antwortMitEinerDcSaeule() throws Exception {
        double[] p = LadesaeuleZuordnungTest.versatz(30, 0);
        return new JSONObject().put("stationen", new JSONArray().put(new JSONObject()
                .put("id", "AT*TST*1").put("name", "Testmarkt Linz").put("betreiber", "Testmarkt GmbH")
                .put("art", "DC").put("kw", 50).put("lat", p[0]).put("lon", p[1])));
    }

    static SocHistoryDatabase.StationZeile zeile(long id, double lat, double lng, int dc, String tarif, String zu) {
        return new SocHistoryDatabase.StationZeile(id, 1000 + id, lat, lng, 40, dc, tarif, zu);
    }

    static JSONObject cfg(Boolean schalter) throws Exception {
        JSONObject l = new JSONObject();
        if (schalter != null) l.put("saeuleNachschlagen", schalter);
        return new JSONObject().put("laden", l);
    }

    @Test
    public void schalterAus_keineAnfrage_auchNichtBeimNachtragen() throws Exception {
        ZaehlPlaner p = new ZaehlPlaner(antwortMitEinerDcSaeule());
        LadesaeuleDienst.Ergebnis e = LadesaeuleDienst.bearbeite(zeile(1, LAT, LNG, 1, "", ""), cfg(false), null, null, p);
        assertNull(e.zuordnung);
        assertFalse(e.angefragt);
        Map<Long, LadesaeuleDienst.Ergebnis> r = LadesaeuleDienst.nachtragen(
                Arrays.asList(zeile(1, LAT, LNG, 1, "", ""), zeile(2, LAT + 0.05, LNG, 1, "", "")), cfg(false), null, null, p, 10);
        assertTrue(r.isEmpty());
        assertEquals(0, p.bodies.size());
    }

    @Test
    public void schalterAbWerkAn() throws Exception {
        assertTrue(LadesaeuleDienst.schalterAn(cfg(null)));
        assertTrue(LadesaeuleDienst.schalterAn(new JSONObject()));
        assertTrue(LadesaeuleDienst.schalterAn(cfg(true)));
        assertFalse(LadesaeuleDienst.schalterAn(cfg(false)));
    }

    @Test
    public void ladeende_genauEineAnfrage_gerundet_auto() throws Exception {
        ZaehlPlaner p = new ZaehlPlaner(antwortMitEinerDcSaeule());
        LadesaeuleDienst.Ergebnis e = LadesaeuleDienst.bearbeite(zeile(1, LAT, LNG, 1, "", ""), cfg(null), null, null, p);
        assertEquals(1, p.bodies.size());
        JSONObject b = p.bodies.get(0);
        assertEquals("48.31", b.get("lat").toString());
        assertEquals("14.29", b.get("lon").toString());
        assertFalse(b.toString().contains(String.valueOf(LAT)));
        assertEquals(LadesaeuleZuordnung.AUTO, e.zuordnung);
        assertEquals("Testmarkt GmbH", e.betreiber);
        assertFalse(e.kandidatenJson.contains("lat"));
        // schon zugeordnet → keine zweite Anfrage
        LadesaeuleDienst.bearbeite(zeile(1, LAT, LNG, 1, "", "auto"), cfg(null), null, null, p);
        assertEquals(1, p.bodies.size());
    }

    @Test
    public void datenschutz_privatZuhauseRuhezoneOhnePosition_keineAnfrage() throws Exception {
        ZaehlPlaner p = new ZaehlPlaner(antwortMitEinerDcSaeule());
        JSONArray orte = new JSONArray()
                .put(new JSONObject().put("lat", LAT).put("lon", LNG).put("r", 100)
                        .put("eigenschaften", new JSONObject().put("zuhause", true)));
        assertEquals(LadesaeuleZuordnung.PRIVAT,
                LadesaeuleDienst.bearbeite(zeile(1, LAT, LNG, 0, "", ""), cfg(null), orte, null, p).zuordnung);
        JSONArray privat = new JSONArray()
                .put(new JSONObject().put("lat", LAT).put("lon", LNG).put("r", 100)
                        .put("eigenschaften", new JSONObject().put("privat", true)));
        assertEquals(LadesaeuleZuordnung.PRIVAT,
                LadesaeuleDienst.bearbeite(zeile(2, LAT, LNG, 0, "", ""), cfg(null), privat, null, p).zuordnung);
        JSONArray ruhe = new JSONArray().put(new JSONObject().put("lat", LAT).put("lon", LNG).put("r", 150));
        assertEquals(LadesaeuleZuordnung.PRIVAT,
                LadesaeuleDienst.bearbeite(zeile(3, LAT, LNG, 0, "", ""), cfg(null), null, ruhe, p).zuordnung);
        assertNull(LadesaeuleDienst.bearbeite(zeile(4, 0, 0, 1, "", ""), cfg(null), null, null, p).zuordnung);
        assertEquals(0, p.bodies.size());
    }

    @Test
    public void gemerkteSaeule_gewinntOhneAnfrage() throws Exception {
        ZaehlPlaner p = new ZaehlPlaner(antwortMitEinerDcSaeule());
        JSONObject c = cfg(null).put("ladesaeule", new JSONObject().put("gemerkt",
                LadesaeuleDienst.merke(null, "t9", "AT*TST*9", "Gemerkte Säule", "Gemerkt GmbH", "DC")));
        LadesaeuleDienst.Ergebnis e = LadesaeuleDienst.bearbeite(zeile(1, LAT, LNG, 1, "t9", ""), c, null, null, p);
        assertEquals(LadesaeuleZuordnung.GEMERKT, e.zuordnung);
        assertEquals("Gemerkt GmbH", e.betreiber);
        assertEquals(0, p.bodies.size());
        // gemerkt gewinnt auch bei ausgeschaltetem Nachschlagen (kein Netz nötig)
        c.getJSONObject("laden").put("saeuleNachschlagen", false);
        assertEquals(LadesaeuleZuordnung.GEMERKT,
                LadesaeuleDienst.bearbeite(zeile(2, LAT, LNG, 1, "t9", ""), c, null, null, p).zuordnung);
        assertEquals(0, p.bodies.size());
    }

    @Test
    public void planerNichtErreichbar_fehler_nurEinVersuch() throws Exception {
        ZaehlPlaner p = new ZaehlPlaner(null);
        LadesaeuleDienst.Ergebnis e = LadesaeuleDienst.bearbeite(zeile(1, LAT, LNG, 1, "", ""), cfg(null), null, null, p);
        assertEquals(LadesaeuleZuordnung.FEHLER, e.zuordnung);
        assertTrue(e.angefragt);
        assertEquals(1, p.bodies.size());
    }

    @Test
    public void nachtragen_eineAnfrageJeZelle_privatOhneAnfrage_hoechstensMax() throws Exception {
        ZaehlPlaner p = new ZaehlPlaner(antwortMitEinerDcSaeule());
        JSONArray orte = new JSONArray().put(new JSONObject().put("lat", LAT + 0.1).put("lon", LNG).put("r", 100)
                .put("eigenschaften", new JSONObject().put("privat", true)));
        List<SocHistoryDatabase.StationZeile> offen = Arrays.asList(
                zeile(1, LAT, LNG, 1, "", ""),
                zeile(2, LAT + 0.0005, LNG, 1, "", ""),
                zeile(3, LAT, LNG + 0.0005, 1, "", "fehler"),
                zeile(4, LAT + 0.05, LNG, 1, "", ""),
                zeile(5, LAT + 0.1, LNG, 1, "", ""));
        Map<Long, LadesaeuleDienst.Ergebnis> r = LadesaeuleDienst.nachtragen(offen, cfg(null), orte, null, p, 10);
        assertEquals(2, p.bodies.size());
        assertEquals(LadesaeuleZuordnung.AUTO, r.get(1L).zuordnung);
        assertEquals(LadesaeuleZuordnung.PRIVAT, r.get(5L).zuordnung);
        assertEquals(LadesaeuleZuordnung.KEINE, r.get(4L).zuordnung);   // andere Zelle, Säule zu weit weg
        ZaehlPlaner q = new ZaehlPlaner(antwortMitEinerDcSaeule());
        r = LadesaeuleDienst.nachtragen(offen, cfg(null), orte, null, q, 1);
        assertEquals(1, q.bodies.size());
        assertNull(r.get(4L));   // bleibt offen für den nächsten Tipp
    }

    @Test
    public void merke_ersetztGleichenTarif_ohneKoordinaten() throws Exception {
        JSONArray a = LadesaeuleDienst.merke(null, "t1", "A", "Säule A", "Betr A", "AC");
        a = LadesaeuleDienst.merke(a, "t2", "B", "Säule B", "Betr B", "DC");
        a = LadesaeuleDienst.merke(a, "t1", "C", "Säule C", "Betr C", "AC");
        assertEquals(2, a.length());
        assertEquals("C", a.getJSONObject(1).getString("id"));
        assertFalse(a.toString().contains("lat"));
    }
}
