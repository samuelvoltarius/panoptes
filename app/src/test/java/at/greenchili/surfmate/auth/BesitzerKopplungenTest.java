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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 0.1.38.8 Besitzer-Handy: Vergabe, Bestand, Bündel, Weitergabe, Abgabe, Entfernen, gebundene Codes. */
public class BesitzerKopplungenTest {

    private final List<String> meldungen = new ArrayList<>();

    @Before
    public void melder() {
        Kopplungen.besitzerMelder = (name, wie) -> meldungen.add(wie + ":" + name);
    }

    @After
    public void aufraeumen() {
        Kopplungen.besitzerMelder = null;
        PairingCodes.reset();
        PcZugang.quelle = PcZugang.KONFIG;
        PcZugang.leeren();
    }

    @Test
    public void ersteDisplayKopplungWirdBesitzerZweiteNicht() {
        Kopplungen k = new Kopplungen(new KopplungenTest.Mem());
        String a = k.anlegen("Handy A", "handy", 1000L, true);
        String b = k.anlegen("Handy B", "handy", 2000L, true);
        String c = k.anlegen("Cockpit", "cockpit", 3000L, true);
        assertTrue(k.istBesitzer(a));
        assertFalse("nie zwei automatisch", k.istBesitzer(b));
        assertFalse("nur Handy", k.istBesitzer(c));
        assertEquals(Arrays.asList("erste:Handy A"), meldungen);
        // Code vom Cockpit („weiteres Gerät“) bzw. altes anlegen: nie Besitzer
        Kopplungen k2 = new Kopplungen(new KopplungenTest.Mem());
        assertFalse(k2.istBesitzer(k2.anlegen("X", "handy", 1L)));
        assertFalse(k2.istBesitzer(k2.anlegen("Y", "handy", 2L, false)));
    }

    @Test
    public void ohneBesitzerWirdDieNaechsteDisplayKopplungBesitzer() {
        Kopplungen k = new Kopplungen(new KopplungenTest.Mem());
        String a = k.anlegen("A", "handy", 1L, true);
        k.widerrufen(a);
        assertTrue(k.besitzerIds().isEmpty());
        assertTrue("nie aussperren", k.istBesitzer(k.anlegen("B", "handy", 2L, true)));
    }

    @Test
    public void besitzerHatLeseUndEinstellungsrechteNieFahrzeug() {
        Kopplungen k = new Kopplungen(new KopplungenTest.Mem());
        String a = k.anlegen("A", "handy", 1L, true);
        for (String r : Kopplungen.BESITZER_BUENDEL) assertTrue(r, k.hatRecht(a, r));
        for (String r : new String[]{Kopplungen.RECHT_AUFSPERREN, Kopplungen.RECHT_WALLBOX}) {
            assertFalse(r, k.hatRecht(a, r));
        }
        assertFalse(Kopplungen.BESITZER_BUENDEL.contains(Kopplungen.RECHT_KLIMA));
        assertFalse(Kopplungen.BESITZER_BUENDEL.contains(Kopplungen.RECHT_ZUSPERREN));
        assertTrue(k.einstellungenSchreiben(a));
        // nie automatisch: Kamera, Protokoll, Dateien, Tacho, Launcher – der Besitzer schaltet sie selbst ein
        for (String r : Kopplungen.BESITZER_SELBST) assertFalse(r, k.hatRecht(a, r));
        assertEquals(Kopplungen.BESITZER_OK, k.besitzerSelbstRechte(a, Arrays.asList("kamera", "protokoll")));
        assertTrue(k.hatRecht(a, Kopplungen.RECHT_KAMERA));
        assertTrue(k.hatRecht(a, Kopplungen.RECHT_KLIMA));   // Standard-Recht der Kopplung bleibt
        assertEquals(Kopplungen.BESITZER_UNBEKANNT, k.besitzerSelbstRechte(a, Arrays.asList("aufsperren")));
        assertFalse(k.hatRecht(a, Kopplungen.RECHT_AUFSPERREN));
        assertEquals(Kopplungen.BESITZER_OK, k.besitzerSelbstRechte(a, Arrays.asList("protokoll")));
        assertFalse(k.hatRecht(a, Kopplungen.RECHT_KAMERA));
        String b = k.anlegen("B", "handy", 2L);
        assertEquals("nur der Besitzer", Kopplungen.BESITZER_NICHT_ERLAUBT, k.besitzerSelbstRechte(b, Arrays.asList("kamera")));
    }

    @Test
    public void bestandWirdEinmalUmgestellt() throws Exception {
        // 0.1.38.9: kein automatischer Besitzer mehr, sondern einmal die Frage „Welches Gerät ist dein Handy?“
        List<Boolean> fragen = new ArrayList<>();
        Kopplungen.besitzerFrageMelder = fragen::add;
        try {
            KopplungenTest.Mem m = new KopplungenTest.Mem();
            m.inhalt = new JSONArray()
                    .put(new JSONObject().put("id", "h2").put("name", "Alfreds iPhone").put("art", "handy").put("angelegt", 200L)
                            .put("zuletzt", 200L).put("rechte", new JSONArray().put("klima")).put("rechteStand", 3))
                    .put(new JSONObject().put("id", "h1").put("name", "Quiet Hub").put("art", "handy").put("angelegt", 100L)
                            .put("zuletzt", 100L).put("rechte", new JSONArray().put("klima").put("update")).put("rechteStand", 3))
                    .put(new JSONObject().put("id", "c1").put("name", "Cockpit").put("art", "cockpit").put("angelegt", 50L)
                            .put("zuletzt", 50L));
            Kopplungen k = new Kopplungen(m);
            assertTrue("kein automatischer Besitzer", k.besitzerIds().isEmpty());
            assertEquals("Rechte wie vorher", Arrays.asList("klima", "update"), k.rechte("h1"));
            assertTrue("Bestandsschutz Cockpit", k.einstellungenSchreiben("c1"));
            assertTrue("Bestandsschutz Zweit-Handy", k.einstellungenSchreiben("h2"));
            assertTrue(meldungen.isEmpty());
            assertEquals(Arrays.asList(false), fragen);
            JSONObject f = k.besitzerFrage();
            assertTrue(f.getBoolean("offen"));
            assertFalse(f.getBoolean("bestaetigen"));
            assertEquals("nur Handy-Kopplungen", 2, f.getJSONArray("geraete").length());
            assertEquals(200L, f.getJSONArray("geraete").getJSONObject(0).getLong("angelegt"));
            // gespeichert + nur einmal: neu laden fragt nicht noch einmal nach
            Kopplungen k2 = new Kopplungen(m);
            assertTrue(k2.besitzerFrageOffen());
            assertEquals(1, fragen.size());
            assertEquals(Kopplungen.FRAGE_UNBEKANNT, k2.besitzerFrageBeantworten("c1", false));
            assertEquals(Kopplungen.FRAGE_OK, k2.besitzerFrageBeantworten("h2", false));
            assertTrue(k2.istBesitzer("h2"));
            assertFalse(k2.istBesitzer("h1"));
            assertFalse(k2.besitzerFrageOffen());
            assertEquals(Arrays.asList("gewaehlt:Alfreds iPhone"), meldungen);
            assertFalse("bleibt beantwortet", new Kopplungen(m).besitzerFrageOffen());
            // neue Kopplungen ohne Recht: kein Schreibrecht
            String neu = k2.anlegen("HA neu", "homeassistant", 300L);
            assertFalse(k2.einstellungenSchreiben(neu));
            assertTrue(k2.rechteSetzen(neu, Arrays.asList(Kopplungen.RECHT_EINSTELLUNGEN)));
            assertTrue(k2.einstellungenSchreiben(neu));
        } finally {
            Kopplungen.besitzerFrageMelder = null;
        }
    }

    /** Stand nach 0.1.38.8: die älteste Handy-Kopplung (hier ein Relay) wurde automatisch Besitzer. */
    private static KopplungenTest.Mem nach01388() throws Exception {
        KopplungenTest.Mem m = new KopplungenTest.Mem();
        m.inhalt = new JSONArray()
                .put(new JSONObject().put("id", "relay").put("name", "Quiet Hub").put("art", "handy").put("angelegt", 100L)
                        .put("zuletzt", 100L).put("rechte", new JSONArray().put("klima").put("besitzer")).put("rechteStand", 3)
                        .put("einstellungenBestand", true).put("stand01388", 1))
                .put(new JSONObject().put("id", "iphone").put("name", "iPhone").put("art", "handy").put("angelegt", 200L)
                        .put("zuletzt", 200L).put("rechte", new JSONArray().put("klima")).put("rechteStand", 3)
                        .put("einstellungenBestand", true).put("stand01388", 1));
        return m;
    }

    @Test
    public void automatischerBesitzerWirdBestaetigtOderKorrigiert() throws Exception {
        List<Boolean> fragen = new ArrayList<>();
        Kopplungen.besitzerFrageMelder = fragen::add;
        try {
            Kopplungen k = new Kopplungen(nach01388());
            assertTrue("bis zur Wahl bleibt der Zustand", k.istBesitzer("relay"));
            JSONObject f = k.besitzerFrage();
            assertTrue(f.getBoolean("offen") && f.getBoolean("bestaetigen"));
            assertEquals(Arrays.asList(true), fragen);
            assertEquals(Kopplungen.FRAGE_OK, k.besitzerFrageBeantworten("iphone", false));
            assertTrue(k.istBesitzer("iphone"));
            assertFalse("Relay verliert das Recht", k.istBesitzer("relay"));
            assertTrue("Standardrechte bleiben", k.rechte("relay").contains("klima"));
            // „Später“: nichts ändern, Frage zu
            Kopplungen k2 = new Kopplungen(nach01388());
            assertEquals(Kopplungen.FRAGE_OK, k2.besitzerFrageBeantworten(null, false));
            assertTrue(k2.istBesitzer("relay"));
            assertFalse(k2.besitzerFrageOffen());
            // „Keins davon“: automatisch gesetzter Besitzer weg, nächste Display-Kopplung wird Besitzer
            Kopplungen k3 = new Kopplungen(nach01388());
            assertEquals(Kopplungen.FRAGE_OK, k3.besitzerFrageBeantworten(null, true));
            assertTrue(k3.besitzerIds().isEmpty());
            assertTrue(k3.istBesitzer(k3.anlegen("Neues iPhone", "handy", 300L, true)));
        } finally {
            Kopplungen.besitzerFrageMelder = null;
        }
    }

    @Test
    public void ersteDisplayKopplungNachDemUpdateBeantwortetDieFrage() throws Exception {
        KopplungenTest.Mem m = new KopplungenTest.Mem();
        m.inhalt = new JSONArray().put(new JSONObject().put("id", "h1").put("name", "Relay").put("art", "handy")
                .put("angelegt", 1L).put("zuletzt", 1L).put("rechte", new JSONArray().put("klima")).put("rechteStand", 3));
        Kopplungen k = new Kopplungen(m);
        assertTrue(k.besitzerFrageOffen());
        String neu = k.anlegen("iPhone", "handy", 2L, true);
        assertTrue(k.istBesitzer(neu));
        assertFalse(k.besitzerFrageOffen());
        assertFalse(k.istBesitzer("h1"));
        assertFalse("per Code (nicht Display) nie Besitzer", k.istBesitzer(k.anlegen("X", "handy", 3L)));
    }

    @Test
    public void vonHandGesetzterBesitzerGiltAlsBestaetigt() throws Exception {
        // Alfreds Auto 04.11. 21:27: nach 0.1.38.8 per /api/kopplung/rechte (Login) besitzer auf „iPhone (Web-App)“ gesetzt,
        // Quiet Hub (älteste, aus der Migration) und iPhone #4 entzogen. 0.1.38.9 darf nicht noch einmal fragen.
        KopplungenTest.Mem m = new KopplungenTest.Mem();
        m.inhalt = new JSONArray()
                .put(new JSONObject().put("id", "hub").put("name", "Quiet Hub").put("art", "handy").put("angelegt", 100L)
                        .put("zuletzt", 100L).put("rechte", new JSONArray().put("klima")).put("rechteStand", 3)
                        .put("einstellungenBestand", true).put("stand01388", 1))
                .put(new JSONObject().put("id", "h4").put("name", "iPhone").put("art", "handy").put("angelegt", 200L)
                        .put("zuletzt", 200L).put("rechte", new JSONArray().put("klima")).put("rechteStand", 3)
                        .put("einstellungenBestand", true).put("stand01388", 1))
                .put(new JSONObject().put("id", "h7").put("name", "iPhone (Web-App)").put("art", "handy").put("angelegt", 300L)
                        .put("zuletzt", 300L).put("rechte", new JSONArray().put("klima").put("besitzer")).put("rechteStand", 3)
                        .put("einstellungenBestand", true).put("stand01388", 1));
        Kopplungen k = new Kopplungen(m);
        assertTrue(k.istBesitzer("h7"));
        assertFalse("keine überflüssige Frage", k.besitzerFrageOffen());
        assertFalse(k.besitzerFrage().getBoolean("bestaetigen"));
    }

    @Test
    public void besitzerPerLoginSchliesstDieFrage() throws Exception {
        Kopplungen k = new Kopplungen(nach01388());
        assertTrue(k.besitzerFrageOffen());
        assertTrue(k.rechteSetzen("iphone", Arrays.asList("klima", "besitzer")));
        assertFalse("am Display/mit Login gewählt = bestätigt", k.besitzerFrageOffen());
        assertTrue(k.istBesitzer("iphone"));
        assertFalse("bleibt zu", new Kopplungen(nach01388Gespeichert(k)).besitzerFrageOffen());
        // anderes Recht setzen schließt die Frage nicht
        Kopplungen k2 = new Kopplungen(nach01388());
        assertTrue(k2.rechteSetzen("iphone", Arrays.asList("klima", "push")));
        assertTrue(k2.besitzerFrageOffen());
    }

    private static KopplungenTest.Mem nach01388Gespeichert(Kopplungen k) throws Exception {
        KopplungenTest.Mem m = new KopplungenTest.Mem();
        java.lang.reflect.Field f = Kopplungen.class.getDeclaredField("geraete");
        f.setAccessible(true);
        m.inhalt = new JSONArray(f.get(k).toString());
        return m;
    }

    @Test
    public void besitzerVomDisplayNach01388OhneFrage() throws Exception {
        KopplungenTest.Mem m = new KopplungenTest.Mem();
        m.inhalt = new JSONArray().put(new JSONObject().put("id", "h1").put("name", "iPhone").put("art", "handy")
                .put("angelegt", 1L).put("zuletzt", 1L).put("rechte", new JSONArray().put("klima").put("besitzer"))
                .put("rechteStand", 3).put("stand01388", 1));
        Kopplungen k = new Kopplungen(m);
        assertTrue(k.istBesitzer("h1"));
        assertFalse("nicht aus der Migration: keine Frage", k.besitzerFrageOffen());
    }

    @Test
    public void frageNurAmDisplayOderMitLogin() throws Exception {
        assertTrue(at.greenchili.surfmate.server.ExternGuard.blocks("GET", "/api/kopplung/besitzer-frage", "handy"));
        assertTrue(at.greenchili.surfmate.server.ExternGuard.blocks("POST", "/api/kopplung/besitzer-frage", "handy"));
        assertTrue(at.greenchili.surfmate.server.WebHandyGuard.blocks("POST", "/api/kopplung/besitzer-frage"));
        String e = at.greenchili.surfmate.testutil.Quelltext.lies(java.nio.file.Paths.get(
                "src/main/java/at/greenchili/surfmate/server/EinstellungenApiHandler.java"));
        int i = e.indexOf("pathOnly.equals(\"/api/kopplung/besitzer-frage\")");
        assertTrue(e.substring(i, i + 300).contains("AuthMiddleware.isExternRequest() || AuthMiddleware.isHandyWebRequest()"));
    }

    @Test
    public void bestandOhneHandyBleibtOhneBesitzer() throws Exception {
        KopplungenTest.Mem m = new KopplungenTest.Mem();
        m.inhalt = new JSONArray().put(new JSONObject().put("id", "c1").put("name", "C").put("art", "cockpit")
                .put("angelegt", 1L).put("zuletzt", 1L));
        Kopplungen k = new Kopplungen(m);
        assertTrue(k.besitzerIds().isEmpty());
        assertTrue(meldungen.isEmpty());
        assertTrue("erste Display-Kopplung danach", k.istBesitzer(k.anlegen("H", "handy", 2L, true)));
    }

    @Test
    public void weitergebenNurVomBesitzerAbgebenNieDerLetzte() {
        Kopplungen k = new Kopplungen(new KopplungenTest.Mem());
        String a = k.anlegen("A", "handy", 1L, true);
        String b = k.anlegen("B", "handy", 2L, true);
        String c = k.anlegen("C", "cockpit", 3L, true);
        assertEquals(Kopplungen.BESITZER_NICHT_ERLAUBT, k.besitzerWeitergeben(b, b));
        assertEquals(Kopplungen.BESITZER_LETZTER, k.besitzerAbgeben(a));
        assertEquals(Kopplungen.BESITZER_UNBEKANNT, k.besitzerWeitergeben(a, c));
        assertEquals(Kopplungen.BESITZER_OK, k.besitzerWeitergeben(a, b));
        assertTrue(k.istBesitzer(b));
        assertTrue(meldungen.contains("weitergegeben:B"));
        assertEquals(Kopplungen.BESITZER_OK, k.besitzerAbgeben(a));
        assertFalse(k.istBesitzer(a));
        assertEquals(Kopplungen.BESITZER_LETZTER, k.besitzerAbgeben(b));
    }

    @Test
    public void entfernenNurJungeFremdeKeinBesitzer() {
        Kopplungen k = new Kopplungen(new KopplungenTest.Mem());
        long t = 10L * Kopplungen.JUNG_MS;
        String a = k.anlegen("A", "handy", t, true);
        String alt = k.anlegen("Alt", "cockpit", t);
        String jung = k.anlegen("Jung", "handy", t + Kopplungen.JUNG_MS);
        long jetzt = t + Kopplungen.JUNG_MS + 1000;
        assertEquals(Kopplungen.BESITZER_NICHT_ERLAUBT, k.jungEntfernen(a, a, jetzt));
        assertEquals("älter als 24 h", Kopplungen.BESITZER_NICHT_ERLAUBT, k.jungEntfernen(a, alt, jetzt));
        assertEquals("nur der Besitzer", Kopplungen.BESITZER_NICHT_ERLAUBT, k.jungEntfernen(jung, alt, jetzt));
        assertEquals(Kopplungen.BESITZER_OK, k.jungEntfernen(a, jung, jetzt));
        assertNull(k.art(jung));
        assertEquals(Kopplungen.BESITZER_UNBEKANNT, k.jungEntfernen(a, "gibtsnicht", jetzt));
    }

    @Test
    public void gebundeneKopplungNurRechteDerArtNieBesitzer() {
        // 0.1.39: Art pc nur mit eingeschaltetem „Am PC anmelden“ (PcZugangTest)
        PcZugang.quelle = new PcZugang.Quelle() {
            public boolean an() { return true; }
            public int tagesMb() { return PcZugang.TAGES_MB; }
        };
        PcZugang.leeren();
        Kopplungen k = new Kopplungen(new KopplungenTest.Mem());
        String pc = k.anlegenGebunden("PC", Kopplungen.ART_PC, Arrays.asList("web", "einstellungen", "aufsperren", "besitzer"), 1L);
        assertEquals("pc", k.art(pc));
        assertEquals(Arrays.asList("web", "einstellungen"), k.rechte(pc));
        assertFalse(k.istBesitzer(pc));
        String h = k.anlegenGebunden("H", "handy", Arrays.asList("besitzer", "klima"), 2L);
        assertTrue("klima nur am Display", k.rechte(h).isEmpty());
        assertFalse("auch die erste Handy-Kopplung per Code nie Besitzer", k.istBesitzer(h));
        assertNull("nie Art web", k.anlegenGebunden("W", "web", Arrays.asList("web"), 3L));
        assertNotNull(Kopplungen.PC_RECHTE);
        assertFalse(Kopplungen.PC_RECHTE.contains(Kopplungen.RECHT_BESITZER));
    }

    @Test
    public void codeVomHandyTraegtArtUndRechte() {
        String c = PairingCodes.createGebunden(1000L, "pc", Arrays.asList("web"));
        assertEquals(PairingCodes.HANDY, PairingCodes.einloesen(c, 2000L));
        Object[] b = PairingCodes.letzteBindung();
        assertEquals("pc", b[0]);
        assertEquals(Arrays.asList("web"), b[1]);
        assertEquals("einmal", PairingCodes.UNGUELTIG, PairingCodes.einloesen(c, 2000L));
        String d = PairingCodes.create(3000L);
        assertEquals("Display-Code ohne Bindung", PairingCodes.BESITZER, PairingCodes.einloesen(d, 4000L));
        assertNull(PairingCodes.letzteBindung());
        String e = PairingCodes.createGebunden(5000L, "cockpit", null);
        assertEquals("10 min", PairingCodes.UNGUELTIG, PairingCodes.einloesen(e, 5000L + PairingCodes.TTL_MS + 1));
    }
}
