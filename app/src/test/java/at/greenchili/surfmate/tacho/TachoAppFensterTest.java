package at.greenchili.surfmate.tacho;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * 0.1.43.9 (T2) App-Fenster im Panoptes-Tacho: Format (Kern = Seite), Regeln, Lage in Pixeln, eigene Flaeche
 * (privates VirtualDisplay in der Ansicht, Start durch den Daemon), „Kann mein Tacho App-Fenster?“ und Verdrahtung.
 */
public class TachoAppFensterTest {

    private static final String JS = "app/src/main/assets/web/shared/tacho-designs.js";
    private static final String EDITOR = "app/src/main/assets/web/shared/tacho-gestalten.js";
    private static final String AKTIVITAET = "app/src/main/java/at/greenchili/surfmate/tacho/TachoAnsichtActivity.kt";
    private static final String START = "app/src/main/java/at/greenchili/surfmate/launcher/TachoAppFensterStart.java";
    private static final String[] SPRACHEN = {"de", "en", "fr", "it", "nl"};

    private static String lies(String r) throws Exception { return TachoFaehigkeitTest.lies(r).replace("\r\n", "\n"); }

    private static List<String> liste(JSONArray a) throws Exception {
        List<String> l = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) l.add(a.getString(i));
        return l;
    }

    // ------------------------------------------------------------ Format: Kern = Seite

    /** Die Faelle hat die Seite (node, PanoptesTacho.pruefeFrei) geprueft; der Kern muss genau dasselbe liefern. */
    @Test
    public void kernPrueftAppFensterWieDieOberflaeche() throws Exception {
        JSONArray f = new JSONArray(lies("app/src/test/resources/tacho-frei-app-faelle.json"));
        assertEquals(7, f.length());
        for (int i = 0; i < f.length(); i++) {
            JSONObject fall = f.getJSONObject(i);
            String name = fall.getString("name");
            TachoEigen.Ergebnis e = TachoEigen.pruefen(fall.getJSONObject("ein"));
            assertNotNull(name, e);
            assertTrue(name + "\nKern: " + e.eigen + "\nSeite: " + fall.getJSONObject("aus"),
                    TachoFreiTest.gleich(e.eigen, fall.getJSONObject("aus")));
            assertEquals(name, liste(fall.getJSONArray("warnungen")), e.warnungen);
        }
    }

    @Test
    public void appHoechstensEinmalMindestgroesseNieUeberTempo() throws Exception {
        JSONArray f = new JSONArray(lies("app/src/test/resources/tacho-frei-app-faelle.json"));
        JSONObject zuKlein = TachoAppFenster.appElement(f.getJSONObject(1).getJSONObject("aus"));
        assertTrue(zuKlein.getInt("b") >= TachoFrei.APP_MIN_BREITE && zuKlein.getInt("h") >= TachoFrei.APP_MIN_HOEHE);
        JSONArray zwei = f.getJSONObject(2).getJSONObject("aus").getJSONArray("elemente");
        int apps = 0;
        for (int i = 0; i < zwei.length(); i++) if ("app".equals(zwei.getJSONObject(i).getString("typ"))) apps++;
        assertEquals(1, apps);
        assertNull("ueber der Geschwindigkeit faellt es weg", TachoAppFenster.appElement(f.getJSONObject(3).getJSONObject("aus")));
        assertEquals(java.util.Collections.singletonList("app_tempo"), liste(f.getJSONObject(3).getJSONArray("warnungen")));
        assertEquals("ungueltiger Paketname wird leer", "", TachoAppFenster.appElement(f.getJSONObject(4).getJSONObject("aus")).getString("paket"));
        assertEquals("", TachoFrei.paket("com.x; rm -rf /"));
        assertEquals("", TachoFrei.paket("../data/app"));
        assertEquals("com.google.android.apps.maps", TachoFrei.paket(" com.google.android.apps.maps "));
        // Ein App-Fenster ist keine Information auf dem Bild: keine Kontrastpruefung
        assertFalse(TachoFrei.info("app"));
    }

    @Test
    public void dieselbenGrenzenInKernUndSeite() throws Exception {
        String js = lies(JS);
        assertTrue(js.contains("        APP_MIN_BREITE: " + TachoFrei.APP_MIN_BREITE + ","));
        assertTrue(js.contains("        APP_MIN_HOEHE: " + TachoFrei.APP_MIN_HOEHE + ","));
        assertTrue(js.contains("        APP_MAX_RAHMEN: " + TachoFrei.APP_MAX_RAHMEN + "\n"));
        assertTrue(TachoFrei.TYPEN.contains("app"));
        // Renderer: nur Rahmen und Zeichen, keine Woerter
        int a = js.indexOf("    function appZeichnen(c, e) {");
        String z = js.substring(a, js.indexOf("\n    }\n", a));
        assertFalse(z.contains("text("));
        assertFalse(z.contains("fillText"));
        assertTrue(js.contains("            case 'app': appZeichnen(c, e); return;"));
    }

    // ------------------------------------------------------------ Regeln

    private static final int[] APP = {100, 100, 500, 400};
    private static final int[] TEMPO = {1300, 200, 1600, 400};

    @Test
    public void imStandJedeFreigegebeneAppBeimFahrenNurNavi() {
        assertNull(TachoAppFenster.sperre("com.netflix", true, true, false, null, APP, TEMPO));
        assertEquals("nur_im_stand", TachoAppFenster.sperre("com.netflix", true, true, false, "moving", APP, TEMPO));
        assertEquals("unbekannter Zustand zaehlt als Fahrt", "nur_im_stand",
                TachoAppFenster.sperre("com.netflix", true, true, false, "state_unknown", APP, TEMPO));
        assertNull(TachoAppFenster.sperre("com.waze", true, true, true, "moving", APP, TEMPO));
        // Freigabe wie „App auf dem Tacho“
        assertEquals("nicht_freigegeben", TachoAppFenster.sperre("com.waze", true, false, true, null, APP, TEMPO));
        assertEquals("nicht_installiert", TachoAppFenster.sperre("com.waze", false, true, true, null, APP, TEMPO));
        assertEquals("keine_app", TachoAppFenster.sperre("", true, true, true, null, APP, TEMPO));
        assertEquals("ansicht_unbekannt", TachoAppFenster.sperre("com.waze", true, true, true, null, null, TEMPO));
    }

    @Test
    public void tempoWirdInPixelnNochEinmalGeprueft() {
        assertEquals("app_tempo", TachoAppFenster.sperre("com.waze", true, true, true, null, new int[] {1200, 100, 1400, 300}, TEMPO));
        assertEquals("ohne Tempo-Lage nie", "app_tempo", TachoAppFenster.sperre("com.waze", true, true, true, null, APP, null));
        assertFalse("Kante beruehrt", TachoAppFenster.ueberlappt(new int[] {0, 0, 1300, 200}, TEMPO));
    }

    @Test
    public void lageInPixelnWieDerRendererInnenAmRahmen() throws Exception {
        // Fahrerdisplay 1920 × 720: die Ansicht zeichnet im Mittelbereich (TachoAnsicht.inhalt)
        int[] f = TachoAnsicht.inhalt(1920, 720);
        JSONObject app = new JSONObject().put("x", 40).put("y", 60).put("b", 600).put("h", 330).put("rahmen", 6);
        int[] r = TachoAppFenster.rechteck(app, f);
        double s = Math.min((double) f[2] / TachoFrei.BREITE, (double) f[3] / TachoFrei.HOEHE);
        double ox = f[0] + (f[2] - TachoFrei.BREITE * s) / 2, oy = f[1] + (f[3] - TachoFrei.HOEHE * s) / 2;
        assertArrayEquals(new int[] {(int) Math.round(ox + 46 * s), (int) Math.round(oy + 66 * s),
                (int) Math.round(ox + 634 * s), (int) Math.round(oy + 384 * s)}, r);
        assertTrue("liegt im Mittelbereich", r[0] >= f[0] && r[1] >= f[1] && r[2] <= f[0] + f[2] && r[3] <= f[1] + f[3]);
        // gleiche Formel wie zeichneFrei in tacho-designs.js
        assertTrue(lies(JS).contains("var s = Math.min(w / E.BREITE, h / E.HOEHE), ox = (w - E.BREITE * s) / 2, oy = (h - E.HOEHE * s) / 2;"));
        assertNull(TachoAppFenster.rechteck(app, null));
        assertNull(TachoAppFenster.rechteck(app, new int[] {0, 0, 0, 10}));
        assertNull(TachoAppFenster.flaeche(new JSONArray().put(1).put(2).put(3)));
        assertNull(TachoAppFenster.flaeche(new JSONArray().put(0).put(0).put(-5).put(10)));
        assertArrayEquals(new int[] {480, 144, 960, 432}, TachoAppFenster.flaeche(new JSONArray().put(480).put(144).put(960).put(432)));
    }

    @Test
    public void ohneAktiveAnsichtOderOhneAppElementAusUndKeineFlaeche() throws Exception {
        JSONObject a = TachoAppFenster.schritt(false, null, null, 7);
        assertEquals(TachoAppFenster.AUS, a.getString("zustand"));
        assertFalse(a.has("flaeche"));
        JSONObject ohneApp = new JSONObject().put("elemente", new JSONArray().put(new JSONObject().put("typ", "zahl").put("wert", "tempo")));
        JSONObject b = TachoAppFenster.schritt(true, ohneApp, null, 7);
        assertEquals(TachoAppFenster.AUS, b.getString("zustand"));
        assertFalse(b.has("rechteck"));
    }

    // ------------------------------------------------------------ eigene Flaeche statt Freeform

    @Test
    public void eigeneFlaecheIstPrivatUndLiegtNurImKasten() throws Exception {
        String k = lies(AKTIVITAET);
        assertTrue("privates VirtualDisplay, keine Spiegelung",
                k.contains("android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)"));
        assertFalse(k.contains("VIRTUAL_DISPLAY_FLAG_PUBLIC"));
        assertFalse(k.contains("VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR"));
        assertFalse("keine Bildschirmaufnahme", k.contains("MediaProjection"));
        assertTrue("ueber der Seite, nur im Kasten", k.contains("neu.setZOrderMediaOverlay(true)"));
        assertTrue("Flaeche nur, wenn der Kern sie erlaubt", k.contains("f.optBoolean(\"flaeche\", false)"));
        assertTrue("Kennung an den Kern", k.contains("if (appDisplayId > 0) c.put(\"appDisplay\", appDisplayId)"));
        assertTrue("erst wegnehmen, dann Flaeche weg", k.contains("if (jetzt - appWegSeit >= APP_WEG_MS) appFlaecheWeg()"));
        assertTrue(k.contains("haupt.postDelayed({ if (!laeuft) appFlaecheWeg() }, APP_WEG_MS)"));
        assertTrue(k.contains("        appFlaecheWeg()\n        hintergrund.shutdownNow()"));
        String s = lies(START);
        assertTrue(s.contains("\"am start --user 0 --display \" + displayId + \" -n \" + komponente"));
        assertFalse("kein Freeform", s.contains("windowingMode"));
        assertFalse("nie Einstellungen schreiben", s.contains("settings put"));
        assertFalse("nie appops", s.contains("appops"));
        assertTrue("liegt sie nicht dort: weg", s.contains("weg(paket);"));
        String t = lies("app/src/main/java/at/greenchili/surfmate/tacho/TachoAppFenster.java");
        assertFalse("nie automatisch auf den ganzen Tacho", t.contains("ClusterCast.start("));
        assertFalse(t.contains("TachoNaviApp.setze("));
        // ClusterCast/ClusterFreeformWindow bleiben unberuehrt
        assertFalse(lies("app/src/main/java/at/greenchili/surfmate/launcher/ClusterFreeformWindow.java").contains("TachoAppFenster"));
    }

    // ------------------------------------------------------------ „Kann mein Tacho App-Fenster?“

    private static JSONObject apps(String status) throws Exception { return new JSONObject().put("status", status); }

    @Test
    public void faehigkeitNurBelegtesIstJa() throws Exception {
        TachoFaehigkeit.Eingaben e = new TachoFaehigkeit.Eingaben();
        assertEquals("nein", TachoFaehigkeit.appFenster(apps("nein"), e).getString("status"));
        assertEquals("unbekannt", TachoFaehigkeit.appFenster(apps("ja"), e).getString("status"));
        assertEquals("fenster_ungeprueft", TachoFaehigkeit.appFenster(apps("unbekannt"), e).getString("code"));
        e.appFensterBelegt = "ja";
        assertEquals("ja", TachoFaehigkeit.appFenster(apps("ja"), e).getString("status"));
        assertEquals("ohne andere Apps nie", "nein", TachoFaehigkeit.appFenster(apps("nein"), e).getString("status"));
        e.appFensterBelegt = "nein";
        JSONObject u = TachoFaehigkeit.appFenster(apps("ja"), e);
        assertEquals("fenster_abgelehnt", u.getString("code"));
        assertFalse(u.getString("grund").isEmpty());
    }

    @Test
    public void rueckmeldungUndTestseitenZeigenDieVierteFrage() throws Exception {
        JSONObject r = new JSONObject().put("appFenster", new JSONObject().put("status", "ja"));
        assertTrue(TachoRueckmeldung.text(r).contains("App-Fenster im Tacho: ja\n"));
        assertTrue(TachoRueckmeldung.text(new JSONObject()).contains("App-Fenster im Tacho: offen\n"));
        assertTrue(TachoFaehigkeit.alsText(new JSONObject().put("appFenster", new JSONObject().put("status", "nein")
                .put("grund", "x"))).contains("App-Fenster im Tacho: nein"));
        assertTrue(lies("app/src/main/assets/web/shared/tacho-faehigkeit.js").contains("['appFenster', t('testf.tacho_appfenster', 'Kann mein Tacho App-Fenster?')]"));
        assertTrue(lies("app/src/main/assets/web/local/testfunktionen.html").contains("['appFenster', t('testf.tacho_appfenster', 'Kann mein Tacho App-Fenster?')]"));
        String f = lies("app/src/main/java/at/greenchili/surfmate/tacho/TachoFaehigkeit.java");
        assertTrue("nur gelesen", f.contains("e.appFensterBelegt = TachoAppFenster.belegt();"));
        assertFalse(f.contains("settings put"));
    }

    // ------------------------------------------------------------ Verdrahtung

    @Test
    public void ansichtKernUndEndeSindVerdrahtet() throws Exception {
        String akt = lies(AKTIVITAET);
        assertTrue(akt.contains("seiteLage?.let { f -> c.put(\"flaeche\""));
        assertTrue(akt.contains("            seiteLageLesen()\n            appFolgen(a)\n"));
        String ans = lies("app/src/main/java/at/greenchili/surfmate/tacho/TachoAnsicht.java");
        // 0.1.43.9 (F, B2): Lage nur mit Bestaetigung der Seite (TachoAnsicht.appFlaeche)
        assertTrue(ans.contains("TachoAppFenster.schritt(aktiv, eigenDesign, appFlaeche(cmd, eigenKennung),"));
        assertTrue(ans.contains("cmd != null ? cmd.optInt(\"appDisplay\", -1) : -1));"));
        String tcp = lies("app/src/main/java/at/greenchili/surfmate/server/TcpCommandServer.java");
        int ende = tcp.indexOf("case \"tachoAnsichtEnde\":");
        assertTrue(tcp.indexOf("at.greenchili.surfmate.tacho.TachoAppFenster.ende();", ende) > ende);
        String api = lies("app/src/main/java/at/greenchili/surfmate/server/TachoApiHandler.java");
        assertTrue(api.contains("p.equals(\"/api/tacho/appfenster\") && method.equals(\"GET\")"));
    }

    @Test
    public void editorEs5UndTexteInFuenfSprachen() throws Exception {
        String ed = lies(EDITOR);
        int a = ed.indexOf("        // ---- 0.1.43.9 (T2) App-Fenster");
        String teil = ed.substring(a, ed.indexOf("        var SCHRIFT_N = {", a));
        for (String v : new String[] {"=>", "let ", "const ", "`"}) assertFalse("ES5: " + v, teil.contains(v));
        assertTrue(ed.contains("['n_app', 'App-Fenster', { typ: 'app', paket: '', b: 420, h: 260, rahmen: 4, akzent: '#35D0E6' }]"));
        assertTrue(ed.contains("if (x.typ === 'app') appPanel(x);"));
        String[] tg = {"n_app", "warn_app_einmal", "warn_app_groesse", "warn_app_tempo", "app_info", "app_wahl", "app_keine",
            "app_nur_stand", "app_freigeben", "app_kein_fenster", "app_ganz", "app_ganz_ok", "app_ganz_stand", "app_ganz_fehler",
            "app_ungeprueft", "app_rahmen", "app_rahmen_farbe"};
        JSONObject de = new JSONObject(lies("app/src/main/assets/web/i18n/de.json")).getJSONObject("tacho_gestalten");
        for (String sp : SPRACHEN) {
            JSONObject d = new JSONObject(lies("app/src/main/assets/web/i18n/" + sp + ".json"));
            for (String k : tg) {
                String v = d.getJSONObject("tacho_gestalten").optString(k);
                assertTrue(sp + " tacho_gestalten." + k, v.length() > 0);
                assertTrue(sp + " " + k + " im Editor", ed.contains("'tacho_gestalten." + k + "'") || ed.contains("['" + k + "', "));
                assertFalse(sp + " " + k, v.toLowerCase(java.util.Locale.ROOT).contains("erprobung")
                        || v.toLowerCase(java.util.Locale.ROOT).contains("testversion"));
            }
            assertTrue(sp, d.getJSONObject("testf").optString("tacho_appfenster").length() > 0);
        }
        // Rückfall-Texte im Editor = Deutsch in de.json
        for (String k : new String[] {"app_info", "app_kein_fenster", "app_ungeprueft", "app_freigeben"}) {
            assertTrue(k, ed.contains("'" + de.getString(k) + "'"));
        }
    }
}
