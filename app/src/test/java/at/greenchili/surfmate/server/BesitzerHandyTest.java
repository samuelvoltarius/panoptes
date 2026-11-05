package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.auth.Kopplungen;
import at.greenchili.surfmate.einstellungen.Einstellungen;
import at.greenchili.surfmate.einstellungen.Feld;
import at.greenchili.surfmate.einstellungen.Katalog;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** 0.1.38.8 Besitzer-Handy: Einstellungs-Stufen, harte Ausnahmen, Besitzer-Endpunkte, Pfade. */
public class BesitzerHandyTest {

    static final class Mem implements Kopplungen.Speicher {
        JSONArray inhalt = new JSONArray();
        public JSONArray lade() { return inhalt; }
        public boolean speichere(JSONArray g) {
            try { inhalt = new JSONArray(g.toString()); } catch (Exception e) { return false; }
            return true;
        }
    }

    static final class Falsch implements BesitzerApiHandler.Wirkung {
        final List<String> meldungen = new ArrayList<>();
        final List<String> aufrufe = new ArrayList<>();
        @Override public String code(long now, String art, List<String> rechte) {
            aufrufe.add("code:" + art + ":" + rechte);
            return "ABCD2345";
        }
        @Override public JSONObject brueckeEinmal(long bis) { return new JSONObject(); }
        @Override public JSONArray anfragen(long now) { return new JSONArray(); }
        @Override public boolean entscheiden(String ref, boolean erlauben, long now) {
            aufrufe.add("entscheiden:" + ref + ":" + erlauben);
            return "r1".equals(ref);
        }
        @Override public boolean dienst(String name) { aufrufe.add("dienst:" + name); return true; }
        @Override public boolean waechter(boolean an) { aufrufe.add("waechter:" + an); return true; }
        @Override public void melde(String text) { meldungen.add(text); }
        /** 0.1.39: „Am PC anmelden“ am Auto an (sonst pc_aus, PcBrueckeTest) */
        @Override public boolean pcErlaubt() { return true; }
    }

    private Kopplungen k;
    private Falsch w;
    private String besitzer, zweit, cockpit;

    @Before
    public void auf() {
        Kopplungen.besitzerMelder = null;
        k = new Kopplungen(new Mem());
        besitzer = k.anlegen("Projektinhaber", "handy", 1L, true);
        zweit = k.anlegen("Zweit", "handy", 2L, true);
        cockpit = k.anlegen("Cockpit", "cockpit", 3L, true);
        BesitzerApiHandler.kopplungen = k;
        w = new Falsch();
        BesitzerApiHandler.wirkung = w;
        BesitzerApiHandler.ratenlimitLeeren();
    }

    @After
    public void ab() {
        BesitzerApiHandler.kopplungen = null;
        BesitzerApiHandler.wirkung = new BesitzerApiHandler.Wirkung() { };
        BesitzerApiHandler.ratenlimitLeeren();
    }

    private JSONObject post(String pfad, String kid, JSONObject body) throws Exception {
        // 0.1.39: heikle Pfade signiert (BesitzerSignaturTest prüft die Signatur selbst)
        return BesitzerApiHandler.antwort("POST", pfad,
                BesitzerSignaturHilfe.signiert(k, kid, pfad, body, 10_000L).toString(), kid, 10_000L);
    }

    // ------------------------------------------------------------------ Einstellungen

    @Test
    public void harteAusnahmenFest() {
        Set<String> nie = new TreeSet<>();
        for (Feld f : Katalog.felder(new Katalog.Kontext())) {
            if (Einstellungen.sperre(f, Einstellungen.Stufe.BESITZER) != null && !f.nurLesend) nie.add(f.key);
        }
        // Neue Zugangs-/Update-/Datenfelder bewusst einordnen: diese Liste ist die harte Sperre für das Besitzer-Handy.
        // 0.1.39: „Am PC anmelden“ + PC-Tagesgrenze sind Zugang (PLAN_BRUECKE_PC_0139)
        assertEquals(new TreeSet<>(Arrays.asList("bruecke.pcAnmeldung", "bruecke.pcTagesMB", "fahrzeugAktionen.annaeherung", "fahrzeugAktionen.aufsperren",
                "fahrzeugAktionen.autoFinden", "fahrzeugAktionen.licht", "fahrzeugAktionen.warnblinker",
                "ladekurve.url", "planner.memberToken", "update.kanal", "update.quelle",
                "update.repo", "update.token", "zaehlen.url")), nie);
    }

    @Test
    public void stufenSchreibenRichtig() {
        Feld nurExtern = null, frei = null, nieExt = null;
        for (Feld f : Katalog.felder(new Katalog.Kontext())) {
            if (f.key.equals("surveillance.surveillanceEnabled")) nurExtern = f;
            if (f.key.equals("surveillance.keepDays")) frei = f;
            if (f.key.equals("update.quelle")) nieExt = f;
        }
        assertTrue("Wächter-Hauptschalter im Katalog", nurExtern != null && nurExtern.nurLesendExtern);
        assertNull(Einstellungen.sperre(nurExtern, Einstellungen.Stufe.BESITZER));
        assertEquals("nurLesendExtern", Einstellungen.sperre(nurExtern, Einstellungen.Stufe.KOPPLUNG));
        assertNull(Einstellungen.sperre(frei, Einstellungen.Stufe.KOPPLUNG));
        assertEquals("rechtFehlt", Einstellungen.sperre(frei, Einstellungen.Stufe.KEIN_RECHT));
        assertEquals("nurAmAuto", Einstellungen.sperre(nieExt, Einstellungen.Stufe.BESITZER));
        assertNull("Display/Login darf alles", Einstellungen.sperre(nieExt, Einstellungen.Stufe.LOGIN));
    }

    @Test
    public void stufeAusDerAnfrage() throws Exception {
        assertEquals(Einstellungen.Stufe.LOGIN, BesitzerMeldung.stufe(false, null, false, null, k));
        assertEquals(Einstellungen.Stufe.BESITZER, BesitzerMeldung.stufe(true, besitzer, false, null, k));
        assertEquals("neue Kopplung ohne Recht", Einstellungen.Stufe.KEIN_RECHT, BesitzerMeldung.stufe(true, zweit, false, null, k));
        assertEquals(Einstellungen.Stufe.KEIN_RECHT, BesitzerMeldung.stufe(true, cockpit, false, null, k));
        k.rechteSetzen(cockpit, Arrays.asList(Kopplungen.RECHT_EINSTELLUNGEN));
        assertEquals(Einstellungen.Stufe.KOPPLUNG, BesitzerMeldung.stufe(true, cockpit, false, null, k));
        assertEquals(Einstellungen.Stufe.KEIN_RECHT, BesitzerMeldung.stufe(false, null, true, zweit, k));
        assertEquals(Einstellungen.Stufe.BESITZER, BesitzerMeldung.stufe(false, null, true, besitzer, k));
        assertEquals("Kopplung unbekannt", Einstellungen.Stufe.KEIN_RECHT, BesitzerMeldung.stufe(true, null, false, null, k));
    }

    @Test
    public void besitzerSchreibtNurLesendExternAberNieNieExtern() throws Exception {
        List<Feld> felder = Katalog.felder(new Katalog.Kontext());
        JSONObject body = new JSONObject().put("werte", new JSONObject().put("surveillance.surveillanceEnabled", true)
                .put("update.quelle", "eigen").put("surveillance.keepDays", 20));
        final List<String> geschrieben = new ArrayList<>();
        Einstellungen.Ergebnis e = Einstellungen.aendern(felder, new JSONObject(), new Katalog.Kontext(), body,
                Einstellungen.Stufe.BESITZER, (sec, w) -> { geschrieben.add(sec + w.keySet()); return true; }, 1L);
        assertTrue(e.gespeichert.has("surveillance.surveillanceEnabled"));
        assertTrue(e.gespeichert.has("surveillance.keepDays"));
        assertEquals("nurAmAuto", e.fehler.optString("update.quelle"));
        Einstellungen.Ergebnis e2 = Einstellungen.aendern(felder, new JSONObject(), new Katalog.Kontext(), body,
                Einstellungen.Stufe.KEIN_RECHT, (sec, w) -> true, 1L);
        assertEquals(0, e2.gespeichert.length());
        assertEquals("rechtFehlt", e2.fehler.optString("surveillance.keepDays"));
        Einstellungen.Ergebnis e3 = Einstellungen.aendern(felder, new JSONObject(), new Katalog.Kontext(), body,
                true, (sec, w) -> true, 1L);
        assertEquals("alter Weg = KOPPLUNG", "nurLesendExtern", e3.fehler.optString("surveillance.surveillanceEnabled"));
        assertTrue(e3.gespeichert.has("surveillance.keepDays"));
        assertTrue(BesitzerMeldung.titel(felder, e.gespeichert).length() > 0);
        assertFalse("nie Werte in der Meldung", BesitzerMeldung.titel(felder, e.gespeichert).contains("20"));
    }

    // ------------------------------------------------------------------ Besitzer-Endpunkte

    @Test
    public void nurBesitzerHandyMitBestaetigung() throws Exception {
        JSONObject ja = new JSONObject().put("bestaetigt", true).put("an", true);
        assertEquals("nur_besitzer", post(BesitzerApiHandler.WAECHTER, zweit, ja).getString("grund"));
        assertEquals("nur_handy", post(BesitzerApiHandler.WAECHTER, cockpit, ja).getString("grund"));
        assertEquals("nur_handy", post(BesitzerApiHandler.WAECHTER, null, ja).getString("grund"));
        assertEquals("bestaetigung", post(BesitzerApiHandler.WAECHTER, besitzer, new JSONObject().put("an", true)).getString("grund"));
        assertTrue(w.aufrufe.isEmpty());
        assertTrue(post(BesitzerApiHandler.WAECHTER, besitzer, ja).getBoolean("ok"));
        assertEquals(Arrays.asList("waechter:true"), w.aufrufe);
        assertEquals(1, w.meldungen.size());
    }

    @Test
    public void codeMitArtUndRechtenNieBesitzer() throws Exception {
        JSONObject r = post(BesitzerApiHandler.CODE, besitzer, new JSONObject().put("bestaetigt", true).put("art", "pc")
                .put("rechte", new JSONArray().put("web")));
        assertTrue(r.getBoolean("ok"));
        assertEquals("ABCD2345", r.getString("code"));
        assertEquals("pc", r.getString("art"));
        assertEquals(Arrays.asList("code:pc:[web]"), w.aufrufe);
        assertEquals(1, w.meldungen.size());
        assertEquals("recht", post(BesitzerApiHandler.CODE, besitzer, new JSONObject().put("bestaetigt", true).put("art", "handy")
                .put("rechte", new JSONArray().put("besitzer"))).getString("grund"));
        assertEquals("recht_nur_display", post(BesitzerApiHandler.CODE, besitzer, new JSONObject().put("bestaetigt", true).put("art", "cockpit")
                .put("rechte", new JSONArray().put("aufsperren"))).getString("grund"));
        assertEquals("art", post(BesitzerApiHandler.CODE, besitzer, new JSONObject().put("bestaetigt", true).put("art", "web"))
                .getString("grund"));
        assertTrue("Standard: nur lesen", post(BesitzerApiHandler.CODE, besitzer,
                new JSONObject().put("bestaetigt", true).put("art", "homeassistant")).getBoolean("ok"));
    }

    @Test
    public void dienstNurStartenKeineTunnel() throws Exception {
        for (String n : new String[]{"tailscale", "wireguard", "telegram", ""}) {
            assertEquals(n, "dienst", post(BesitzerApiHandler.DIENST, besitzer, new JSONObject().put("bestaetigt", true)
                    .put("name", n)).getString("grund"));
        }
        assertTrue(post(BesitzerApiHandler.DIENST, besitzer, new JSONObject().put("bestaetigt", true).put("name", "acc"))
                .getBoolean("ok"));
        assertEquals(Arrays.asList("dienst:acc"), w.aufrufe);
    }

    @Test
    public void weitergebenAbgebenEntfernen() throws Exception {
        JSONObject ja = new JSONObject().put("bestaetigt", true);
        assertEquals("letzter_besitzer", post(BesitzerApiHandler.ABGEBEN, besitzer, ja).getString("grund"));
        assertTrue(post(BesitzerApiHandler.WEITERGEBEN, besitzer, new JSONObject(ja.toString()).put("id", zweit)).getBoolean("ok"));
        assertTrue(k.istBesitzer(zweit));
        assertTrue(post(BesitzerApiHandler.ABGEBEN, besitzer, ja).getBoolean("ok"));
        assertFalse(k.istBesitzer(besitzer));
        // zweit ist jetzt Besitzer: entfernt das neue Cockpit (jünger 24 h), nie sich selbst
        assertEquals("nur_neue", post(BesitzerApiHandler.ENTFERNEN, zweit, new JSONObject(ja.toString()).put("id", zweit))
                .getString("grund"));
        assertTrue(post(BesitzerApiHandler.ENTFERNEN, zweit, new JSONObject(ja.toString()).put("id", cockpit)).getBoolean("ok"));
        assertNull(k.art(cockpit));
        assertTrue(w.meldungen.size() >= 3);
    }

    @Test
    public void anfragenEntscheidenUndListe() throws Exception {
        assertTrue(post(BesitzerApiHandler.ENTSCHEIDEN, besitzer, new JSONObject().put("bestaetigt", true).put("ref", "r1")
                .put("erlauben", true)).getBoolean("ok"));
        assertEquals("unbekannt", post(BesitzerApiHandler.ENTSCHEIDEN, besitzer, new JSONObject().put("bestaetigt", true)
                .put("ref", "x").put("erlauben", true)).getString("grund"));
        JSONObject l = BesitzerApiHandler.antwort("GET", BesitzerApiHandler.LISTE, null, besitzer, 1L);
        assertEquals(3, l.getJSONArray("geraete").length());
        assertEquals(besitzer, l.getString("ich"));
        assertEquals("nur_get", BesitzerApiHandler.antwort("POST", BesitzerApiHandler.LISTE, "{}", besitzer, 1L).getString("grund"));
    }

    @Test
    public void eigeneRechteNurAusDerSelbstListe() throws Exception {
        JSONObject r = post(BesitzerApiHandler.SELBST, besitzer, new JSONObject().put("bestaetigt", true)
                .put("rechte", new JSONArray().put("kamera").put("dateien")));
        assertTrue(r.getBoolean("ok"));
        assertTrue(k.hatRecht(besitzer, Kopplungen.RECHT_DATEIEN));
        assertEquals("recht_nur_display", post(BesitzerApiHandler.SELBST, besitzer, new JSONObject().put("bestaetigt", true)
                .put("rechte", new JSONArray().put("wallbox"))).getString("grund"));
        assertEquals("nur_besitzer", post(BesitzerApiHandler.SELBST, zweit, new JSONObject().put("bestaetigt", true)
                .put("rechte", new JSONArray().put("kamera"))).getString("grund"));
        assertEquals(1, w.meldungen.size());
    }

    @Test
    public void fahrzeugRechteNiePerCodeOderSelbst() throws Exception {
        for (String r : new String[]{"signal", "aufsperren", "zusperren", "klima", "wallbox"}) {
            assertEquals(r, "recht_nur_display", post(BesitzerApiHandler.CODE, besitzer, new JSONObject().put("bestaetigt", true)
                    .put("art", "handy").put("rechte", new JSONArray().put("web").put(r))).getString("grund"));
        }
        assertEquals("recht_nur_display", post(BesitzerApiHandler.CODE, besitzer, new JSONObject().put("bestaetigt", true)
                .put("art", "homeassistant").put("rechte", new JSONArray().put("geraete_steuern"))).getString("grund"));
        assertTrue("normale Rechte gehen weiter", post(BesitzerApiHandler.CODE, besitzer, new JSONObject().put("bestaetigt", true)
                .put("art", "handy").put("rechte", new JSONArray().put("web").put("route").put("push"))).getBoolean("ok"));
        assertTrue(post(BesitzerApiHandler.CODE, besitzer, new JSONObject().put("bestaetigt", true)
                .put("art", "homeassistant").put("rechte", new JSONArray().put("geraete"))).getBoolean("ok"));
        BesitzerApiHandler.ratenlimitLeeren();
        assertEquals("recht_nur_display", post(BesitzerApiHandler.SELBST, besitzer, new JSONObject().put("bestaetigt", true)
                .put("rechte", new JSONArray().put("aufsperren"))).getString("grund"));
        assertEquals("recht_nur_display", post(BesitzerApiHandler.SELBST, besitzer, new JSONObject().put("bestaetigt", true)
                .put("rechte", new JSONArray().put("signal"))).getString("grund"));
        // zweite Sperre im Modell: auch ein direkter Aufruf legt keine Fahrzeug-Rechte an
        String h = k.anlegenGebunden("H", "handy", Arrays.asList("signal", "aufsperren", "klima", "web"), 5L);
        assertEquals(Arrays.asList("web"), k.rechte(h));
        assertEquals(Arrays.asList("aufsperren", "signal"), k.neueDisplayRechte(h, Arrays.asList("web", "aufsperren", "signal")));
        assertTrue("Entziehen geht immer", k.neueDisplayRechte(besitzer, Arrays.asList("web")).isEmpty());
        // Rechte setzen mit Login: Fahrzeug-Rechte neu nur direkt am Autobildschirm
        String e = at.greenchili.surfmate.testutil.Quelltext.lies(java.nio.file.Paths.get(
                "src/main/java/at/greenchili/surfmate/server/EinstellungenApiHandler.java"));
        int i = e.indexOf("pathOnly.equals(\"/api/kopplung/rechte\")");
        assertTrue(e.substring(i, i + 1200).contains("!AuthMiddleware.isDirektAmAuto()"));
        assertTrue(e.contains("if ((aufsperren || wallbox) && !AuthMiddleware.isDirektAmAuto()) {"));
    }

    @Test
    public void bestaetigungMitSchluesselUndFertigemText() throws Exception {
        assertEquals("Direkt", Einstellungen.bestaetigungFertig("Direkt", "x.y", null));
        JSONObject t = new JSONObject().put("title", "Titel").put("intro", "Einleitung").put("a.label", "Option A");
        assertEquals("Titel\n\nEinleitung", Einstellungen.bestaetigungFertig("x.y", "x.y", t));
        assertNull(Einstellungen.bestaetigungFertig("x.y", "x.y", new JSONObject().put("a.label", "A")));
    }

    @Test
    public void ratenlimit() throws Exception {
        JSONObject ja = new JSONObject().put("bestaetigt", true).put("an", false);
        for (int i = 0; i < BesitzerApiHandler.MAX_JE_MINUTE; i++) assertTrue(post(BesitzerApiHandler.WAECHTER, besitzer, ja).getBoolean("ok"));
        assertEquals("zu_oft", post(BesitzerApiHandler.WAECHTER, besitzer, ja).getString("grund"));
    }

    @Test
    public void pfadeNurFuerHandy() {
        Set<String> alle = new HashSet<>(BesitzerApiHandler.SCHREIBEN);
        for (String p : alle) {
            assertFalse(p, ExternGuard.blocks("POST", p, "handy"));
            for (String art : new String[]{"cockpit", "homeassistant", "dienst", "web", "pc"}) {
                assertTrue(art + p, ExternGuard.blocks("POST", p, art));
            }
            assertTrue("Web-Sitzung nie", WebHandyGuard.blocks("POST", p));
        }
        for (String p : BesitzerApiHandler.LESEN) {
            assertFalse(p, ExternGuard.blocks("GET", p, "handy"));
            assertTrue(p, ExternGuard.blocks("GET", p, "cockpit"));
        }
        // harte Ausnahmen bleiben für jede Kopplung zu
        for (String p : new String[]{"/api/kopplung/rechte", "/api/kopplung/widerrufen", "/api/system/tailscale",
                "/api/notzugang", "/api/wireguard", "/api/bruecke", "/api/kopplung/telegram-liste", "/api/system/daemon"}) {
            assertTrue(p, ExternGuard.blocks("POST", p, "handy"));
        }
    }
}
