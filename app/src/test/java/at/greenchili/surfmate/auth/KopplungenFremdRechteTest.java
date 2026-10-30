package at.greenchili.surfmate.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * 0.1.30 Kern Sicherheit: „kamera“ und „standbild“ auch für Cockpit, Home Assistant und Dienste – Standard aus,
 * nie automatisch gesetzt, keine Handy-Rechte für diese Arten.
 */
public class KopplungenFremdRechteTest {

    private static Kopplungen neu() {
        return new Kopplungen(new KopplungenTest.Mem());
    }

    @Test
    public void cockpitHomeAssistantUndDienstStartenOhneRechte() {
        Kopplungen k = neu();
        for (String art : new String[] {"cockpit", "homeassistant", "dienst"}) {
            String id = k.anlegen("x", art, 1L);
            assertTrue(art, k.rechte(id).isEmpty());
            assertFalse(art, k.hatRecht(id, Kopplungen.RECHT_KAMERA));
            assertFalse(art, k.hatRecht(id, Kopplungen.RECHT_STANDBILD));
        }
    }

    @Test
    public void nurFremdRechteSetzbarNieHandyRechte() {
        Kopplungen k = neu();
        String id = k.anlegen("Cockpit", "cockpit", 1L);
        assertTrue(k.rechteSetzen(id, Arrays.asList("kamera", "klima", "aufsperren", "web", "update")));
        assertEquals(Arrays.asList("kamera"), k.rechte(id));
        assertTrue(k.hatRecht(id, Kopplungen.RECHT_KAMERA));
        assertFalse(k.hatRecht(id, Kopplungen.RECHT_AUFSPERREN));
        assertFalse(k.hatRecht(id, Kopplungen.RECHT_WEB));
        assertFalse(k.darfWebSitzung(id));
    }

    @Test
    public void dienstBekommtStandbild() {
        Kopplungen k = neu();
        String id = k.anlegen("Xaventra", "dienst", 1L);
        assertEquals(Kopplungen.ART_DIENST, k.art(id));
        assertTrue(k.rechteSetzen(id, Arrays.asList("standbild")));
        assertTrue(k.hatRecht(id, Kopplungen.RECHT_STANDBILD));
        assertFalse(k.hatRecht(id, Kopplungen.RECHT_KAMERA));
    }

    @Test
    public void webUndAlteKopplungenTragenKeineRechte() {
        Kopplungen k = neu();
        String web = k.anlegen("Browser", "web", 1L);
        assertFalse(k.rechteSetzen(web, Arrays.asList("kamera")));
        assertFalse(k.hatRecht(web, Kopplungen.RECHT_KAMERA));
        String alt = k.anlegen("alt", null, 1L);
        assertFalse(k.rechteSetzen(alt, Arrays.asList("kamera")));
        assertFalse(k.hatRecht(alt, Kopplungen.RECHT_KAMERA));
    }

    /** Umstellung: eine bestehende Cockpit-Kopplung (ohne Rechte-Feld) bekommt NICHTS automatisch. */
    @Test
    public void bestandWirdNichtAutomatischFreigeschaltet() throws Exception {
        KopplungenTest.Mem mem = new KopplungenTest.Mem();
        mem.inhalt = new JSONArray().put(new JSONObject().put("id", "c1").put("name", "Cockpit")
                .put("art", "cockpit").put("angelegt", 1L).put("zuletzt", 1L));
        Kopplungen k = new Kopplungen(mem);
        assertTrue(k.rechte("c1").isEmpty());
        assertFalse(k.hatRecht("c1", Kopplungen.RECHT_KAMERA));
        JSONObject inListe = k.alle(2L).getJSONObject(0);
        assertEquals(0, inListe.getJSONArray("rechte").length());
    }

    @Test
    public void handyStandardUnveraendertOhneKameraUndStandbild() {
        Kopplungen k = neu();
        String id = k.anlegen("Handy", "handy", 1L);
        List<String> r = k.rechte(id);
        assertFalse(r.contains(Kopplungen.RECHT_KAMERA));
        assertFalse(r.contains(Kopplungen.RECHT_STANDBILD));
        assertTrue(r.contains(Kopplungen.RECHT_KLIMA));
    }
}
