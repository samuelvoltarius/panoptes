package at.greenchili.surfmate.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;

/**
 * 0.1.38 Recht „launcher“ (Koordination, abweichend vom Entwurf): Standard AUS, auch für Handy; Cockpit und Handy
 * bekommen es nur per Häkchen unter Gekoppelte Geräte, bestehende Kopplungen nie automatisch, HA und Dienste nie.
 */
public class KopplungenLauncherRechtTest {

    private static Kopplungen neu() {
        return new Kopplungen(new KopplungenTest.Mem());
    }

    @Test
    public void neueKopplungenHabenEsNicht() {
        Kopplungen k = neu();
        for (String art : new String[] {"handy", "cockpit", "homeassistant", "dienst", "web"}) {
            String id = k.anlegen("x", art, 1L);
            assertFalse(art, k.hatRecht(id, Kopplungen.RECHT_LAUNCHER));
        }
    }

    @Test
    public void nurPerHaekchenFuerHandyUndCockpit() {
        Kopplungen k = neu();
        String handy = k.anlegen("Handy", "handy", 1L);
        assertTrue(k.rechteSetzen(handy, Arrays.asList("launcher", "klima")));
        assertTrue(k.hatRecht(handy, Kopplungen.RECHT_LAUNCHER));
        String cockpit = k.anlegen("Cockpit", "cockpit", 1L);
        assertTrue(k.rechteSetzen(cockpit, Arrays.asList("launcher", "klima")));
        assertEquals(Arrays.asList("launcher"), k.rechte(cockpit));
        for (String art : new String[] {"homeassistant", "dienst"}) {
            String id = k.anlegen("x", art, 1L);
            assertTrue(k.rechteSetzen(id, Arrays.asList("launcher")));
            assertFalse(art, k.hatRecht(id, Kopplungen.RECHT_LAUNCHER));
        }
    }

    @Test
    public void bestandBekommtEsNicht() throws Exception {
        KopplungenTest.Mem mem = new KopplungenTest.Mem();
        mem.inhalt = new JSONArray()
                // altes Handy ohne Rechte-Feld (bekommt beim Lesen nur die alten Standard-Rechte)
                .put(new JSONObject().put("id", "h1").put("name", "Handy").put("art", "handy").put("angelegt", 1L).put("zuletzt", 1L))
                // Handy mit Rechte-Stand 1
                .put(new JSONObject().put("id", "h2").put("name", "Handy 2").put("art", "handy").put("angelegt", 2L)
                        .put("zuletzt", 2L).put("rechte", new JSONArray().put("klima").put("web")).put("rechteStand", 1))
                .put(new JSONObject().put("id", "c1").put("name", "Cockpit").put("art", "cockpit").put("angelegt", 1L)
                        .put("zuletzt", 1L).put("rechte", new JSONArray().put("kamera")));
        Kopplungen k = new Kopplungen(mem);
        assertFalse(k.hatRecht("h1", Kopplungen.RECHT_LAUNCHER));
        assertFalse(k.hatRecht("h2", Kopplungen.RECHT_LAUNCHER));
        assertFalse(k.hatRecht("c1", Kopplungen.RECHT_LAUNCHER));
        assertTrue(k.hatRecht("c1", Kopplungen.RECHT_KAMERA));
    }
}
