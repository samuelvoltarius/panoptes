package at.greenchili.surfmate.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;

/**
 * 0.1.38.10 Recht „laden“ (Koordination 05.11.): eigenes Recht für Laden im Auto aus der Web-Sitzung, Standard AUS,
 * nur das Display vergibt es. Bestand mit „web“ konnte das Laden in der Weboberfläche bisher ohne Recht starten und
 * behält es einmalig (Rechte-Stand 4); wer kein „web“ hat, bekommt es nicht. Die Hebung schreibt nichts anderes um.
 */
public class KopplungenLadenRechtTest {

    static JSONObject handy(String id, long angelegt, int stand, String... rechte) throws Exception {
        JSONObject g = new JSONObject().put("id", id).put("name", id).put("art", "handy").put("angelegt", angelegt)
                .put("zuletzt", angelegt).put("rechte", new JSONArray(Arrays.asList(rechte)));
        return stand > 0 ? g.put("rechteStand", stand) : g;
    }

    @Test
    public void neueKopplungenHabenEsNicht() {
        Kopplungen k = new Kopplungen(new KopplungenTest.Mem());
        for (String art : new String[]{"handy", "cockpit", "homeassistant", "dienst", "web"}) {
            String id = k.anlegen("x", art, 1L);
            assertFalse(art, k.hatRecht(id, Kopplungen.RECHT_LADEN));
        }
        assertFalse(Kopplungen.HANDY_STANDARD.contains(Kopplungen.RECHT_LADEN));
        assertFalse(Kopplungen.BESITZER_BUENDEL.contains(Kopplungen.RECHT_LADEN));
        assertTrue(Kopplungen.NUR_DISPLAY_RECHTE.contains(Kopplungen.RECHT_LADEN));
        for (String art : new String[]{Kopplungen.ART_PC, "cockpit", "homeassistant", Kopplungen.ART_DIENST}) {
            assertFalse(art, Kopplungen.rechteDerArt(art).contains(Kopplungen.RECHT_LADEN));
        }
    }

    @Test
    public void bestandMitWebBehaeltDasLaden() throws Exception {
        KopplungenTest.Mem mem = new KopplungenTest.Mem();
        mem.inhalt = new JSONArray()
                .put(handy("mitWeb", 1L, 3, "klima", "zusperren", "web", "route"))
                .put(handy("ohneWeb", 2L, 3, "klima", "push"))
                .put(handy("alt", 3L, 1, "klima", "web"))
                .put(new JSONObject().put("id", "c1").put("name", "Cockpit").put("art", "cockpit").put("angelegt", 1L)
                        .put("zuletzt", 1L).put("rechte", new JSONArray().put("kamera")));
        Kopplungen k = new Kopplungen(mem);
        assertTrue(k.hatRecht("mitWeb", Kopplungen.RECHT_LADEN));
        assertTrue(k.hatRecht("alt", Kopplungen.RECHT_LADEN));
        assertFalse(k.hatRecht("ohneWeb", Kopplungen.RECHT_LADEN));
        assertFalse(k.hatRecht("c1", Kopplungen.RECHT_LADEN));
        // festgeschrieben auf Stand 4, sonst nichts dazu
        JSONObject g = mem.inhalt.getJSONObject(0);
        assertEquals(4, g.getInt("rechteStand"));
        assertEquals(Arrays.asList("klima", "zusperren", "web", "route", "laden"), k.rechte("mitWeb"));
        assertEquals(Arrays.asList("klima", "push"), k.rechte("ohneWeb"));
    }

    @Test
    public void abgeschaltetesUpdateKommtBeimHebenNichtWieder() throws Exception {
        // die älteste Handy-Kopplung hat „update“ unter Stand 3 bewusst abgeschaltet
        KopplungenTest.Mem mem = new KopplungenTest.Mem();
        mem.inhalt = new JSONArray().put(handy("erstes", 1L, 3, "klima", "web"));
        Kopplungen k = new Kopplungen(mem);
        assertFalse(k.hatRecht("erstes", Kopplungen.RECHT_UPDATE));
        assertTrue(k.hatRecht("erstes", Kopplungen.RECHT_LADEN));
        // unter Stand 3 (vor 0.1.26.1) bekommt die älteste es wie bisher
        mem.inhalt = new JSONArray().put(handy("erstes", 1L, 2, "klima", "web"));
        k = new Kopplungen(mem);
        assertTrue(k.hatRecht("erstes", Kopplungen.RECHT_UPDATE));
    }

    @Test
    public void einmalig() throws Exception {
        // nach der Hebung abgeschaltet: bleibt aus, auch nach dem nächsten Laden
        KopplungenTest.Mem mem = new KopplungenTest.Mem();
        mem.inhalt = new JSONArray().put(handy("h", 1L, 3, "klima", "web"));
        Kopplungen k = new Kopplungen(mem);
        assertTrue(k.rechteSetzen("h", Arrays.asList("klima", "web")));
        assertFalse(new Kopplungen(mem).hatRecht("h", Kopplungen.RECHT_LADEN));
    }

    @Test
    public void besitzerCodeVergibtEsNie() throws Exception {
        Kopplungen k = new Kopplungen(new KopplungenTest.Mem());
        String h = k.anlegen("Handy", "handy", 1L);
        assertEquals(Arrays.asList(Kopplungen.RECHT_LADEN),
                k.neueDisplayRechte(h, Arrays.asList(Kopplungen.RECHT_LADEN, Kopplungen.RECHT_WEB)));
    }
}
