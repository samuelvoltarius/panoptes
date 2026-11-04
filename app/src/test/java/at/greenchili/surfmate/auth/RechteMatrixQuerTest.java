package at.greenchili.surfmate.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * 0.1.38.9 Integration: Besitzer-Migration (einmalige Frage) × PC über die Brücke ({@code rechteDerArt}) ×
 * {@link Kopplungen#NUR_DISPLAY_RECHTE}. Ein PC wird nie Besitzer, nie Teil der Frage und trägt nie Display-Rechte.
 */
public class RechteMatrixQuerTest {

    @Before
    public void auf() {
        PcZugang.quelle = new PcZugang.Quelle() {
            public boolean an() { return true; }
            public int tagesMb() { return PcZugang.TAGES_MB; }
        };
        PcZugang.leeren();
        Kopplungen.besitzerMelder = null;
    }

    @After
    public void ab() {
        PcZugang.quelle = PcZugang.KONFIG;
        PcZugang.leeren();
        Kopplungen.besitzerFrageMelder = null;
    }

    @Test
    public void keineArtAusserHandyTraegtBesitzer() {
        for (String art : new String[]{Kopplungen.ART_PC, "cockpit", "homeassistant", Kopplungen.ART_DIENST}) {
            assertFalse(art, Kopplungen.rechteDerArt(art).contains(Kopplungen.RECHT_BESITZER));
        }
        for (String r : Kopplungen.NUR_DISPLAY_RECHTE) assertFalse("PC nie " + r, Kopplungen.rechteDerArt(Kopplungen.ART_PC).contains(r));
        for (String r : Kopplungen.BESITZER_BUENDEL) assertFalse("Bündel nie " + r, Kopplungen.NUR_DISPLAY_RECHTE.contains(r));
    }

    @Test
    public void pcWebSitzungSchreibtNieBesitzerOderRechte() {
        // 7008e9a8: WebHandyGuard.PC_WEB_SCHREIBEN × Besitzer-Frage × Rechte-/Kopplungs-Wege
        for (String p : new String[]{"/api/kopplung/besitzer-frage", "/api/handy/v1/besitzer/rechte",
                "/api/handy/v1/kopplung/code", "/api/kopplung/entfernen/x", "/api/settings/unified",
                "/api/kopplung/besitzer-frage/", "/api/einstellungen/../kopplung/besitzer-frage"}) {
            assertTrue("PC nie POST " + p, at.greenchili.surfmate.server.WebHandyGuard.pcBlocks("POST", p));
        }
        assertFalse(at.greenchili.surfmate.server.WebHandyGuard.pcBlocks("GET", "/api/kopplung/besitzer-frage"));
    }

    @Test
    public void pcIstNieTeilDerBesitzerFrage() throws Exception {
        List<Boolean> fragen = new ArrayList<>();
        Kopplungen.besitzerFrageMelder = fragen::add;
        KopplungenTest.Mem m = new KopplungenTest.Mem();
        m.inhalt = new JSONArray()
                .put(new JSONObject().put("id", "h1").put("name", "Handy").put("art", "handy").put("angelegt", 100L)
                        .put("zuletzt", 100L).put("rechte", new JSONArray().put("klima")).put("rechteStand", 3));
        Kopplungen k = new Kopplungen(m);
        assertTrue(k.besitzerFrageOffen());
        // alles außer einstellungen (das darf ein PC tragen): einstellungenSchreiben prüft dann nur den Bestandsschutz
        List<String> alle = new ArrayList<>(Kopplungen.RECHTE);
        alle.remove(Kopplungen.RECHT_EINSTELLUNGEN);
        String pc = k.anlegenGebunden("PC", Kopplungen.ART_PC, alle, 200L, 0L);
        assertNotNull(pc);
        for (String r : Kopplungen.NUR_DISPLAY_RECHTE) assertFalse("PC nie " + r, k.hatRecht(pc, r));
        assertFalse(k.istBesitzer(pc));
        assertFalse("PC ohne Bestandsschutz", k.einstellungenSchreiben(pc));

        JSONObject f = k.besitzerFrage();
        JSONArray g = f.getJSONArray("geraete");
        assertEquals("nur das Handy", 1, g.length());
        assertEquals("h1", g.getJSONObject(0).getString("id"));
        assertEquals(Kopplungen.FRAGE_UNBEKANNT, k.besitzerFrageBeantworten(pc, false));
        assertFalse(k.istBesitzer(pc));

        // Neu laden: PC bekommt keine Frage-Marke und keinen Bestandsschutz
        Kopplungen k2 = new Kopplungen(m);
        assertFalse(k2.istBesitzer(pc));
        assertFalse(k2.einstellungenSchreiben(pc));
        assertEquals(Kopplungen.FRAGE_OK, k2.besitzerFrageBeantworten("h1", false));
        assertTrue(k2.istBesitzer("h1"));
        assertFalse(k2.istBesitzer(pc));
    }
}
