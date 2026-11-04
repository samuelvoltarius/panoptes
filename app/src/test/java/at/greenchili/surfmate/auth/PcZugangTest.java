package at.greenchili.surfmate.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/** 0.1.39 PC über die Brücke (PLAN_BRUECKE_PC_0139 P1): Schalter, Dauer, Höchstzahl, Rechte, Widerruf, Grenzen. */
public class PcZugangTest {

    static boolean an;
    static int mb = PcZugang.TAGES_MB;

    static void schalter(boolean ein) {
        an = ein;
        PcZugang.vergessen();
    }

    @Before
    public void auf() {
        an = false;
        mb = PcZugang.TAGES_MB;
        PcZugang.quelle = new PcZugang.Quelle() {
            public boolean an() { return an; }
            public int tagesMb() { return mb; }
        };
        PcZugang.leeren();
        Kopplungen.besitzerMelder = null;
    }

    @After
    public void ab() {
        PcZugang.quelle = PcZugang.KONFIG;
        PcZugang.leeren();
        PairingCodes.reset();
    }

    @Test
    public void schalterStandardAusUnlesbarHeisstAus() {
        PcZugang.quelle = new PcZugang.Quelle() {
            public boolean an() { throw new IllegalStateException("kaputt"); }
            public int tagesMb() { return 1; }
        };
        PcZugang.leeren();
        assertFalse("fail-closed", PcZugang.erlaubt(1L));
    }

    @Test
    public void pcKopplungNurBeiEingeschaltetemSchalter() {
        Kopplungen k = new Kopplungen(new KopplungenTest.Mem());
        assertNull("Schalter aus: kein PC", k.anlegenGebunden("PC", Kopplungen.ART_PC, Collections.singletonList("web"), 1L, 0L));
        schalter(true);
        String pc = k.anlegenGebunden("PC", Kopplungen.ART_PC, Arrays.asList("web", "besitzer", "update", "aufsperren",
                "signal", "klima", "zusperren"), 1L, 0L);
        assertNotNull(pc);
        assertTrue(k.istPc(pc));
        assertFalse(k.istHandy(pc));
        assertTrue(k.hatRecht(pc, Kopplungen.RECHT_WEB));
        for (String r : new String[]{"besitzer", "update", "aufsperren", "signal", "klima", "zusperren", "wallbox",
                "dateien", "tacho", "launcher"}) {
            assertFalse("PC nie " + r, k.hatRecht(pc, r));
        }
        assertFalse(k.istBesitzer(pc));
        assertTrue(k.pruefe(pc, 2L));
        schalter(false);
        assertFalse("Schalter aus: PC-Kopplung gilt nicht mehr", k.pruefe(pc, 3L));
    }

    @Test
    public void pcRechteNieBesitzerUpdateAufsperrenSignal() {
        for (String r : new String[]{Kopplungen.RECHT_BESITZER, Kopplungen.RECHT_UPDATE, Kopplungen.RECHT_AUFSPERREN,
                Kopplungen.RECHT_SIGNAL, Kopplungen.RECHT_KLIMA, Kopplungen.RECHT_ZUSPERREN, Kopplungen.RECHT_WALLBOX,
                Kopplungen.RECHT_DATEIEN, Kopplungen.RECHT_LAUNCHER, Kopplungen.RECHT_TACHO, Kopplungen.RECHT_PUSH}) {
            assertFalse(r, Kopplungen.PC_RECHTE.contains(r));
        }
    }

    @Test
    public void eineQuelleFuerRechteDerArtUndNieNurDisplayRechte() {
        assertEquals(Kopplungen.PC_RECHTE, Kopplungen.rechteDerArt(Kopplungen.ART_PC));
        assertEquals(Kopplungen.RECHTE, Kopplungen.rechteDerArt(Kopplungen.ART_HANDY));
        assertEquals(Kopplungen.HA_RECHTE, Kopplungen.rechteDerArt("homeassistant"));
        assertEquals(Kopplungen.COCKPIT_RECHTE, Kopplungen.rechteDerArt("cockpit"));
        for (String r : Kopplungen.NUR_DISPLAY_RECHTE) assertFalse("PC nie " + r, Kopplungen.PC_RECHTE.contains(r));
    }

    @Test
    public void nurHeuteLaeuftNach12StundenAb() {
        schalter(true);
        Kopplungen k = new Kopplungen(new KopplungenTest.Mem());
        long t0 = 1_000_000L;
        String pc = k.anlegenGebunden("PC", Kopplungen.ART_PC, Collections.singletonList("web"), t0,
                PcZugang.ablaufFuer(PcZugang.DAUER_HEUTE, t0));
        assertTrue(k.pruefe(pc, t0 + PcZugang.NUR_HEUTE_MS - 1));
        assertFalse(k.pruefe(pc, t0 + PcZugang.NUR_HEUTE_MS));
        JSONObject e = eintrag(k.alle(t0 + PcZugang.NUR_HEUTE_MS), pc);
        assertTrue(e.optBoolean("abgelaufen"));
        assertEquals(t0 + PcZugang.NUR_HEUTE_MS, e.optLong("ablaufFest"));
        assertEquals(0L, PcZugang.ablaufFuer(PcZugang.DAUER_30_TAGE, t0));
    }

    @Test
    public void hoechstensFuenfPcs() {
        schalter(true);
        Kopplungen k = new Kopplungen(new KopplungenTest.Mem());
        for (int i = 0; i < PcZugang.MAX_PC; i++) {
            assertNotNull(k.anlegenGebunden("PC" + i, Kopplungen.ART_PC, Collections.singletonList("web"), i + 1L, 0L));
        }
        assertNull(k.anlegenGebunden("PC6", Kopplungen.ART_PC, Collections.singletonList("web"), 9L, 0L));
        assertNotNull("Handys zählen nicht mit", k.anlegenGebunden("H", Kopplungen.ART_HANDY, Collections.<String>emptyList(), 9L, 0L));
    }

    @Test
    public void ausschaltenWiderruftAllePcsHandysBleiben() {
        schalter(true);
        Kopplungen k = new Kopplungen(new KopplungenTest.Mem());
        String h = k.anlegen("Handy", "handy", 1L, true);
        String a = k.anlegenGebunden("A", Kopplungen.ART_PC, Collections.singletonList("web"), 2L, 0L);
        String b = k.anlegenGebunden("B", Kopplungen.ART_PC, Collections.singletonList("web"), 3L, 0L);
        assertEquals(2, k.pcAlleWiderrufen());
        assertTrue(k.pcIds().isEmpty());
        assertTrue(k.pruefe(h, 4L));
        assertFalse(k.pruefe(a, 4L));
        assertFalse(k.pruefe(b, 4L));
    }

    @Test
    public void besitzerMeldetPcJederzeitAbAndereNicht() {
        schalter(true);
        Kopplungen k = new Kopplungen(new KopplungenTest.Mem());
        String besitzer = k.anlegen("Projektinhaber", "handy", 1L, true);
        String zweit = k.anlegen("Zweit", "handy", 2L, true);
        String pc = k.anlegenGebunden("PC", Kopplungen.ART_PC, Collections.singletonList("web"), 3L, 0L);
        String cockpit = k.anlegen("Cockpit", "cockpit", 4L, true);
        assertEquals(Kopplungen.BESITZER_NICHT_ERLAUBT, k.pcEntfernen(zweit, pc));
        assertEquals("nur PCs", Kopplungen.BESITZER_NICHT_ERLAUBT, k.pcEntfernen(besitzer, cockpit));
        assertEquals(Kopplungen.BESITZER_UNBEKANNT, k.pcEntfernen(besitzer, "gibtsnicht"));
        assertEquals(Kopplungen.BESITZER_OK, k.pcEntfernen(besitzer, pc));
        assertFalse(k.pruefe(pc, 5L));
        assertTrue(k.pruefe(besitzer, 5L));
    }

    @Test
    public void pcDarfWebSitzungNurMitRechtWeb() {
        schalter(true);
        Kopplungen k = new Kopplungen(new KopplungenTest.Mem());
        String mit = k.anlegenGebunden("A", Kopplungen.ART_PC, Collections.singletonList("web"), 1L, 0L);
        String ohne = k.anlegenGebunden("B", Kopplungen.ART_PC, Collections.<String>emptyList(), 2L, 0L);
        assertTrue(k.darfWebSitzung(mit));
        assertFalse(k.darfWebSitzung(ohne));
    }

    @Test
    public void codeTraegtDauerBisZumEinloesen() {
        String code = PairingCodes.createGebunden(1L, Kopplungen.ART_PC, Collections.singletonList("web"), PcZugang.NUR_HEUTE_MS);
        assertEquals(PairingCodes.HANDY, PairingCodes.einloesen(code, 2L));
        Object[] b = PairingCodes.letzteBindung();
        assertEquals(Kopplungen.ART_PC, b[0]);
        assertEquals(PcZugang.NUR_HEUTE_MS, ((Long) b[2]).longValue());
        // ein Display-Code danach trägt keine Dauer
        String c2 = PairingCodes.create(3L);
        assertEquals(PairingCodes.BESITZER, PairingCodes.einloesen(c2, 4L));
        assertNull(PairingCodes.letzteBindung());
    }

    @Test
    public void zwanzigAnfragenJeSekundeUndTagesgrenze() {
        for (int i = 0; i < PcZugang.MAX_JE_SEKUNDE; i++) assertTrue(PcZugang.anfrageErlaubt("pc1", 1000L + i));
        assertFalse(PcZugang.anfrageErlaubt("pc1", 1500L));
        assertTrue("anderer PC eigenes Fenster", PcZugang.anfrageErlaubt("pc2", 1500L));
        assertTrue("nächste Sekunde", PcZugang.anfrageErlaubt("pc1", 2001L));
        mb = PcZugang.TAGES_MB_MIN;
        PcZugang.vergessen();
        long t = 5L * 86_400_000L;
        assertFalse(PcZugang.tagVoll("pc1", t));
        PcZugang.verbrauchen("pc1", PcZugang.TAGES_MB_MIN * 1024L * 1024L, t);
        assertTrue(PcZugang.tagVoll("pc1", t + 1));
        assertFalse("neuer Tag", PcZugang.tagVoll("pc1", t + 86_400_000L));
        assertEquals(0L, PcZugang.heute("pc1", t + 86_400_000L));
    }

    private static JSONObject eintrag(JSONArray l, String id) {
        for (int i = 0; i < l.length(); i++) if (id.equals(l.optJSONObject(i).optString("id"))) return l.optJSONObject(i);
        return null;
    }
}
