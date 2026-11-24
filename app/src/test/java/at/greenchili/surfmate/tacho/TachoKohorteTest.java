package at.greenchili.surfmate.tacho;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

/** 0.1.43.20: Kohorten-Gate (P3) und Größen-Sperre (P2). */
public class TachoKohorteTest {

    private static TachoKohorte.Schluessel seal(String panel) {
        TachoKohorte.Schluessel s = new TachoKohorte.Schluessel();
        s.modell = "Seal";
        s.firmware = "GB-1.2.3";
        s.build = "13.1.32.2602030";
        s.api = 29;
        s.libBydCluster = "aa11";
        s.fissiond = "bb22";
        s.panel = panel;
        return s;
    }

    private static JSONObject liste(TachoKohorte.Schluessel s, String panel) throws Exception {
        JSONObject e = new JSONObject().put("name", "Test-Kohorte").put("modell", s.modell)
                .put("firmware", s.firmware).put("build", s.build).put("api", s.api)
                .put("libBydCluster", s.libBydCluster).put("fissiond", s.fissiond).put("panel", panel);
        return new JSONObject().put("version", 1).put("eintraege", new org.json.JSONArray().put(e));
    }

    @After
    public void aufraeumen() {
        TachoKohorte.quelle = new TachoKohorte.LiveQuelle();
        TachoKohorte.vergiss();
        TachoSicht.zuruecksetzenFuerTest();
    }

    @Test
    public void ausgelieferteListeIstLeerUndGueltig() throws Exception {
        JSONObject l = new JSONObject(TachoKohortenListe.JSON);
        assertEquals(1, l.getInt("version"));
        assertEquals("noch kein belegter Eintrag", 0, l.getJSONArray("eintraege").length());
        assertFalse(TachoKohorte.inListe(seal(""), l));
    }

    @Test
    public void nurExakterTrefferZaehlt() throws Exception {
        TachoKohorte.Schluessel s = seal("");
        JSONObject l = liste(s, "1920x720");
        assertTrue("Panel noch nicht gemessen: Eintrag gilt", TachoKohorte.inListe(s, l));
        assertTrue(TachoKohorte.inListe(seal("1920x720"), l));
        assertFalse("gemessenes Panel widerspricht", TachoKohorte.inListe(seal("1280x480"), l));
        TachoKohorte.Schluessel anders = seal("");
        anders.libBydCluster = "aa12";
        assertFalse("anderer Hash", TachoKohorte.inListe(anders, l));
        TachoKohorte.Schluessel fw = seal("");
        fw.firmware = "GB-1.2.4";
        assertFalse("andere Firmware", TachoKohorte.inListe(fw, l));
        TachoKohorte.Schluessel api = seal("");
        api.api = 33;
        assertFalse("andere API", TachoKohorte.inListe(api, l));
        assertFalse("Eintrag ohne Panel ist ungültig", TachoKohorte.inListe(s, liste(s, "")));
        TachoKohorte.Schluessel leer = new TachoKohorte.Schluessel();
        assertFalse("leere Werte passen nie", TachoKohorte.inListe(leer, liste(leer, "1920x720")));
    }

    @Test
    public void unbekannteHardwareBleibtAus() throws Exception {
        TachoKohorte.Urteil u = TachoKohorte.urteil(seal(""), new JSONObject(TachoKohortenListe.JSON),
                "", false, true);
        assertEquals(TachoKohorte.GESPERRT, u.status);
        assertEquals("hardware_unbekannt", u.code);
        assertFalse(u.oeffnenErlaubt());
        assertTrue("verständlicher Hinweis", u.grund.contains("Warnleuchten"));
    }

    @Test
    public void ersteMalNurImStandDannFrageDannFrei() throws Exception {
        TachoKohorte.Schluessel s = seal("");
        JSONObject l = liste(s, "1920x720");
        assertEquals("erst_im_stand_pruefen", TachoKohorte.urteil(s, l, "", false, false).code);
        TachoKohorte.Urteil probe = TachoKohorte.urteil(s, l, "", false, true);
        assertEquals(TachoKohorte.PROBE, probe.status);
        assertTrue(probe.oeffnenErlaubt());
        assertEquals(TachoKohorte.FREI, TachoKohorte.urteil(s, l, TachoSicht.JA, false, false).status);
        assertEquals("sicht_nein", TachoKohorte.urteil(s, l, TachoSicht.NEIN, false, true).code);
        assertEquals("sicht_unbekannt", TachoKohorte.urteil(s, l, TachoSicht.UNBEKANNT, false, true).code);
        // Tester-Freigabe ersetzt nur die Liste, nie die Frage
        TachoKohorte.Urteil tester = TachoKohorte.urteil(s, new JSONObject(TachoKohortenListe.JSON), "", true, true);
        assertEquals(TachoKohorte.PROBE, tester.status);
        assertEquals("sicht_nein", TachoKohorte.urteil(s, new JSONObject(TachoKohortenListe.JSON),
                TachoSicht.NEIN, true, true).code);
    }

    @Test
    public void rueckwegGehtImmerWarnleuchtenNie() {
        TachoKohorte.quelle = quelle(seal(""), "", false, true);
        TachoKohorte.vergiss();
        assertTrue(TachoKohorte.darfSenden(18));
        assertTrue(TachoKohorte.darfSenden(0));
        assertFalse("unbekannte Hardware: kein Öffnen", TachoKohorte.darfSenden(16));
        assertFalse(TachoKohorte.darfSenden(35));
        assertFalse(TachoKohorte.darfSenden(31));
        assertFalse("Warnleuchten nie", TachoKohorte.darfSenden(2));
        assertFalse(TachoKohorte.darfSenden(3));
        assertFalse("nichts anderes", TachoKohorte.darfSenden(36));
    }

    @Test
    public void zulassungVorDemOeffnenNutztDasselbeUrteil() throws Exception {
        TachoKohorte.quelle = quelle(seal(""), "", false, true);
        TachoKohorte.vergiss();
        assertFalse(TachoKohorte.zulassungMoeglich());
        TachoKohorte.quelle = quelle(seal(""), TachoSicht.JA, true, false);
        TachoKohorte.vergiss();
        assertTrue(TachoKohorte.zulassungMoeglich());
        String q = new String(java.nio.file.Files.readAllBytes(quelleController()), "UTF-8");
        int a = q.indexOf("public boolean requestOpen() {");
        String f = q.substring(a, q.indexOf("projHandler.post(() -> doOpenSequence(epoch));", a));
        assertTrue("Totwinkel/Karte: Gate vor jedem Zustandswechsel",
                f.indexOf("TachoKohorte.zulassungMoeglich()") < f.indexOf("projState = ST_OPENING;"));
        assertTrue(f.contains("TachoKohorte.zulassungMoeglich()"));
    }

    private static java.nio.file.Path quelleController() {
        java.nio.file.Path p = java.nio.file.Paths.get(
                "src/main/java/at/greenchili/surfmate/surveillance/ClusterProjectionController.java");
        return java.nio.file.Files.exists(p) ? p : java.nio.file.Paths.get("app").resolve(p);
    }

    @Test
    public void gateMitTesterFreigabeStartetDiePflichtfrage() throws Exception {
        TachoKohorte.Schluessel s = seal("");
        TachoKohorte.quelle = quelle(s, "", true, true);
        TachoKohorte.vergiss();
        TachoSicht.speicher = new TachoSichtTest.SpeicherAttrappe();
        try {
            assertTrue(TachoKohorte.darfSenden(16));
            assertTrue(TachoSicht.stand(s.id()).getBoolean("offen"));
        } finally {
            TachoSicht.speicher = TachoSichtTest.echterSpeicher();
        }
    }

    @Test
    public void testerKnopfNurBeiProjektionUndNichtInListe() throws Exception {
        JSONObject seal = new JSONObject().put("singleOs", false).put("shellRechte", true).put("autoContainer", true);
        assertTrue(TachoKohorte.testerAngebot(seal, false));
        assertFalse("steht in der Liste", TachoKohorte.testerAngebot(seal, true));
        JSONObject surf = new JSONObject().put("singleOs", true).put("shellRechte", true).put("autoContainer", true);
        assertFalse("Single-OS (Surf): kein Knopf", TachoKohorte.testerAngebot(surf, false));
        JSONObject di5 = new JSONObject().put("singleOs", true).put("sharedFission", true).put("shellRechte", true);
        assertTrue("DiLink-5-Fläche", TachoKohorte.testerAngebot(di5, false));
        JSONObject ohne = new JSONObject().put("singleOs", false).put("shellRechte", true);
        assertFalse("kein Projektionsdienst", TachoKohorte.testerAngebot(ohne, false));
        assertFalse("ohne Shell-Rechte", TachoKohorte.testerAngebot(
                new JSONObject().put("autoContainer", true), false));
        assertFalse(TachoKohorte.testerAngebot(null, false));
    }

    @Test
    public void modellNurExaktKeineNamensteile() {
        assertEquals(TachoKohorte.Modell.SEAL_EU, TachoKohorte.modell("Seal"));
        assertEquals(TachoKohorte.Modell.SEAL_EU, TachoKohorte.modell("BYD SEAL"));
        assertEquals(TachoKohorte.Modell.ANDERES, TachoKohorte.modell("Seal U DM-i"));
        assertEquals(TachoKohorte.Modell.ANDERES, TachoKohorte.modell("Sealion 7"));
        assertEquals(TachoKohorte.Modell.ATTO3, TachoKohorte.modell("Atto 3"));
        assertEquals(TachoKohorte.Modell.ATTO3, TachoKohorte.modell("ATTO-3"));
        assertEquals(TachoKohorte.Modell.ANDERES, TachoKohorte.modell("Atto 2"));
        assertEquals(TachoKohorte.Modell.ANDERES, TachoKohorte.modell("Song Plus DM-i"));
        assertEquals(TachoKohorte.Modell.ANDERES, TachoKohorte.modell(null));
    }

    @Test
    public void vorschlagNur31Seal29AttoNie30() {
        assertEquals(31, TachoKohorte.vorschlag("Seal", ""));
        assertEquals(31, TachoKohorte.vorschlag("Seal", "1920x720"));
        assertEquals("Panel widerspricht", 0, TachoKohorte.vorschlag("Seal", "1280x480"));
        assertEquals(29, TachoKohorte.vorschlag("Atto 3", ""));
        assertEquals(0, TachoKohorte.vorschlag("Atto 3", "1920x720"));
        assertEquals(0, TachoKohorte.vorschlag("Han EV", ""));
        assertEquals(0, TachoKohorte.vorschlag("Tang", ""));
        assertEquals(0, TachoKohorte.vorschlag("Seal U DM-i", ""));
        assertEquals("ADAS-Alternative nur Seal EU", 30, TachoKohorte.adasAlternative("Seal", ""));
        assertEquals(0, TachoKohorte.adasAlternative("Seal U", ""));
        assertEquals(0, TachoKohorte.adasAlternative("Seal", "1280x480"));
    }

    @Test
    public void geometrieSperre() {
        assertNull(TachoKohorte.sperrGrund(0, "Seal", "1280x480"));
        assertEquals("panel_1280x480", TachoKohorte.sperrGrund(30, "", "1280x480"));
        assertEquals("panel_1280x480", TachoKohorte.sperrGrund(31, "Seal", "1280x480"));
        assertEquals("modell_atto3", TachoKohorte.sperrGrund(30, "Atto 3", ""));
        assertEquals("panel_1920x720", TachoKohorte.sperrGrund(29, "", "1920x720"));
        assertEquals("modell_seal", TachoKohorte.sperrGrund(29, "Seal", ""));
        assertNull("unbekanntes Modell, nicht gemessen: Nutzerwahl gilt", TachoKohorte.sperrGrund(30, "Han", ""));
        assertNull(TachoKohorte.sperrGrund(31, "Seal", "1920x720"));
        assertEquals("profil_ungueltig", TachoKohorte.sperrGrund(2, "Seal", ""));
    }

    @Test
    public void profilNachSperreLiefertNullBeiWiderspruch() {
        TachoKohorte.quelle = quelle(seal("1280x480"), "", false, true);
        TachoKohorte.vergiss();
        assertEquals(0, TachoKohorte.profilNachSperre(31));
        assertEquals("Seal: kein 8,8-Zoll-Profil", 0, TachoKohorte.profilNachSperre(29));
        assertEquals(0, TachoKohorte.profilNachSperre(0));
        TachoKohorte.quelle = quelle(seal("1920x720"), "", false, true);
        TachoKohorte.vergiss();
        assertEquals(31, TachoKohorte.profilNachSperre(31));
        assertEquals(0, TachoKohorte.profilNachSperre(29));
    }

    @Test
    public void schluesselIdIstStabilUndOhnePanel() throws Exception {
        assertEquals(seal("").id(), seal("1920x720").id());
        assertEquals(16, seal("").id().length());
        TachoKohorte.Schluessel b = seal("");
        b.fissiond = "bb23";
        assertFalse(seal("").id().equals(b.id()));
        assertNotNull(seal("").alsJson().optString("libBydCluster"));
    }

    @Test
    public void dateiHashNurLesend() throws Exception {
        assertEquals("fehlt", TachoKohorte.dateiHash("/gibt/es/nicht/libBydCluster.so"));
        java.io.File f = java.io.File.createTempFile("kohorte", ".bin");
        try {
            java.nio.file.Files.write(f.toPath(), "abc".getBytes("UTF-8"));
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                    TachoKohorte.dateiHash(f.getPath()));
        } finally {
            f.delete();
        }
    }

    private static TachoKohorte.Quelle quelle(TachoKohorte.Schluessel s, String antwort, boolean tester,
                                             boolean stand) {
        return new TachoKohorte.Quelle() {
            @Override public TachoKohorte.Schluessel schluessel() { return s; }
            @Override public String antwort(String id) { return antwort; }
            @Override public boolean tester(String id) { return tester; }
            @Override public boolean imStand() { return stand; }
        };
    }
}
