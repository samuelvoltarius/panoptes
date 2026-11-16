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

/**
 * 0.1.43.9 Fix-Paket F (E2E_01439): B2 eigenes Design erst nach Bestaetigung der Seite, App-Flaeche nur ueber dem
 * eigenen Design mit App-Kasten; B3 Takt an der Sichtbarkeit; B1 Karten-Snapshot darf Panoptes nicht mitreissen;
 * H1 vorinstallierte Navi-Apps in der App-Wahl; H2 kein „?:“.
 */
public class TachoAnsichtFixTest {

    private static final String AKT = "app/src/main/java/at/greenchili/surfmate/tacho/TachoAnsichtActivity.kt";
    private static final String SEITE = "app/src/main/assets/web/local/tacho-ansicht.html";
    private static final String JS = "app/src/main/assets/web/shared/tacho-designs.js";
    private static final String BILD = "app/src/main/java/at/greenchili/surfmate/tacho/TachoKarteBild.kt";

    private static String lies(String r) throws Exception { return TachoFaehigkeitTest.lies(r).replace("\r\n", "\n"); }

    private static JSONArray flaeche() {
        return new JSONArray().put(0).put(0).put(1920).put(720);
    }

    // ------------------------------------------------------------ B2: Bestaetigung der Seite

    @Test
    public void seitenStandNurMitEigenemDesign() {
        assertArrayEquals("Seite noch nicht bereit", new String[] {"", "0"}, TachoAnsicht.seitenStand(null));
        assertArrayEquals(new String[] {"", "0"}, TachoAnsicht.seitenStand("null"));
        assertArrayEquals(new String[] {"", "0"}, TachoAnsicht.seitenStand("kein json"));
        assertArrayEquals(new String[] {"", "0"}, TachoAnsicht.seitenStand("\"eigen\""));
        assertArrayEquals("Standard-Design statt eigenem", new String[] {"", "0"},
                TachoAnsicht.seitenStand("{\"design\":\"klar\",\"eigenStand\":\"\",\"app\":false}"));
        assertArrayEquals("eigenes Design ohne Kennung (verloren)", new String[] {"", "0"},
                TachoAnsicht.seitenStand("{\"design\":\"eigen\",\"eigenStand\":\"\",\"app\":true}"));
        assertArrayEquals("fremde Kennung", new String[] {"", "0"},
                TachoAnsicht.seitenStand("{\"design\":\"eigen\",\"eigenStand\":\"<x>\",\"app\":true}"));
        assertArrayEquals(new String[] {"1a2b3c", "1"},
                TachoAnsicht.seitenStand("{\"design\":\"eigen\",\"eigenStand\":\"1a2b3c\",\"app\":true}"));
        assertArrayEquals("Leiste fuer Kamera/Video: kein App-Kasten", new String[] {"1a2b3c", "0"},
                TachoAnsicht.seitenStand("{\"design\":\"eigen\",\"eigenStand\":\"1a2b3c\",\"app\":false}"));
    }

    /**
     * Stellt den Verlust aus E2E_01439 nach: Der Kern schickt das Design, die Seite ist noch nicht bereit bzw. baut
     * sich neu auf und zeigt das Standard-Design. Vorher hatte die Ansicht die Kennung trotzdem uebernommen, der Kern
     * schickte das Design nie wieder und legte die App ueber das Tempo des Standard-Designs.
     */
    @Test
    public void verlorenesDesignWirdErneutGeschicktUndKeineAppFlaeche() throws Exception {
        JSONObject e = new JSONArray(lies("app/src/test/resources/tacho-frei-app-faelle.json")).getJSONObject(0).getJSONObject("aus");
        assertNotNull("Fall mit App-Fenster", TachoAppFenster.appElement(e));
        String stand = TachoEigen.stand(e);
        assertFalse(stand.isEmpty());

        // Takt 1: Ansicht kennt nichts → Kern schickt das Design mit (Regel aus E1)
        JSONObject cmd1 = new JSONObject().put("cmd", "tachoAnsicht").put("flaeche", flaeche());
        assertFalse(stand.equals(cmd1.optString("eigenStand", "")));
        assertNull("ohne Bestaetigung keine App-Flaeche", TachoAnsicht.appFlaeche(cmd1, stand));

        // Seite war nicht bereit: tachoSetze gab nichts zurueck → Ansicht bestaetigt nichts
        String[] st = TachoAnsicht.seitenStand(null);
        JSONObject cmd2 = new JSONObject().put("cmd", "tachoAnsicht");
        if (!st[0].isEmpty()) cmd2.put("eigenStand", st[0]);
        if ("1".equals(st[1])) cmd2.put("flaeche", flaeche());
        assertFalse("Kern schickt das Design erneut", stand.equals(cmd2.optString("eigenStand", "")));
        assertNull("keine Lage → kein Rechteck, keine Flaeche ueber dem Standard-Design", TachoAnsicht.appFlaeche(cmd2, stand));
        // ohne Lage sperrt der Kern (ansicht_unbekannt) und nimmt die App weg; nur ueber dem App-Rechteck laeuft sie
        assertEquals("ansicht_unbekannt", TachoAppFenster.sperre("com.google.android.apps.maps", true, true, true, null,
                null, new int[] {0, 0, 10, 10}));

        // Seite zeigt das eigene Design und bestaetigt es → erst jetzt gilt die Lage
        st = TachoAnsicht.seitenStand(new JSONObject().put("design", "eigen").put("eigenStand", stand).put("app", true).toString());
        JSONObject cmd3 = new JSONObject().put("cmd", "tachoAnsicht").put("eigenStand", st[0]).put("flaeche", flaeche());
        assertNotNull(TachoAnsicht.appFlaeche(cmd3, stand));

        // Altes Design bestaetigt (Nutzer hat inzwischen neu gespeichert) → keine Flaeche
        assertNull(TachoAnsicht.appFlaeche(cmd3, stand + "0"));
        assertNull(TachoAnsicht.appFlaeche(cmd3, ""));
        assertNull(TachoAnsicht.appFlaeche(null, stand));
    }

    @Test
    public void ansichtUebernimmtKennungNurAusDerBestaetigung() throws Exception {
        String s = lies(SEITE);
        assertTrue("Seite meldet, was sie zeigt", s.contains("window.tachoSetze = function (d) { t.setze(d); return t.stand(); };"));
        String js = lies(JS);
        assertTrue(js.contains("eigenStand = anz.eigen && typeof d.eigenStand === 'string' ? d.eigenStand : '';"));
        assertTrue("App-Kasten nur im eigenen Design ohne Leiste", js.contains("if (e && e.version === E.VERSION_FREI && !anz.ansicht && e.elemente) {"));
        assertTrue(js.contains("return { design: anz.design, eigenStand: e ? eigenStand : '', app: app };"));

        String k = lies(AKT);
        assertTrue(k.contains("seite.evaluateJavascript(\"window.tachoSetze && window.tachoSetze($d)\") { s -> seiteBestaetigt(s) }"));
        assertTrue(k.contains("val st = TachoAnsicht.seitenStand(s)\n        eigenStand = st[0]\n        seiteApp = st[1] == \"1\""));
        assertTrue("Lage nur mit bestaetigtem App-Kasten",
                k.contains("if (seiteApp) seiteLage?.let { f -> c.put(\"flaeche\""));
        assertTrue("Flaeche nur mit bestaetigtem App-Kasten",
                k.contains("val soll = if (seiteApp && f != null && f.optBoolean(\"flaeche\", false)"));
        assertTrue("sofort unsichtbar", k.contains("alt.visibility = android.view.View.INVISIBLE"));
        assertTrue("VirtualDisplay bleibt ohne Bild, bis der Kern die App weggenommen hat",
                k.contains("try { appVd?.surface = null } catch (_: Throwable) { }"));
        assertFalse("nicht beim Verdecken freigeben", k.contains("override fun surfaceDestroyed(h: android.view.SurfaceHolder) { appVdWeg() }"));

        String a = lies("app/src/main/java/at/greenchili/surfmate/tacho/TachoAnsicht.java");
        assertTrue("Kern: Lage nur mit Bestaetigung", a.contains("TachoAppFenster.schritt(aktiv, eigenDesign, appFlaeche(cmd, eigenKennung),"));
    }

    // ------------------------------------------------------------ B3: Sichtbarkeit statt Fokus

    @Test
    public void taktAnDerSichtbarkeit() throws Exception {
        String k = lies(AKT);
        assertTrue(k.contains("override fun onStart() {\n        super.onStart()\n        if (laeuft) return\n        laeuft = true\n        takt()"));
        assertTrue(k.contains("override fun onStop() {\n        laeuft = false"));
        assertTrue("App weg erst, wenn die Ansicht nicht mehr sichtbar ist",
                k.contains("        kameraEnde()\n        super.onStop()"));
        assertFalse(k.contains("override fun onPause()"));
        assertFalse(k.contains("override fun onResume()"));
        // Regel „beim Losfahren nur Navi-Apps“ bleibt im Kern
        String t = lies("app/src/main/java/at/greenchili/surfmate/tacho/TachoAppFenster.java");
        assertTrue(t.contains("if (bewegungsGrund != null && !navi) return \"nur_im_stand\";"));
    }

    // ------------------------------------------------------------ B1: Snapshot reisst Panoptes nicht mit

    @Test
    public void grafikPruefungUndAbsturzSperre() {
        assertFalse("GLES 2 wie im Emulator-Lauf 1", TachoKachelKarte.grafikReicht(0x20000, true));
        assertFalse("keine EGL-Konfiguration", TachoKachelKarte.grafikReicht(0x30000, false));
        assertTrue(TachoKachelKarte.grafikReicht(0x30002, true));

        long start = 1_700_000_000_000L;
        assertFalse("keine Marke", TachoKachelKarte.nachAbsturzGesperrt(0, start));
        assertTrue("Absturz in diesem Geraetestart", TachoKachelKarte.nachAbsturzGesperrt(start + 900, start));
        assertFalse("Absturz vor dem letzten Neustart zaehlt nicht", TachoKachelKarte.nachAbsturzGesperrt(start - 3_600_000, start));

        assertTrue("nativer/Grafik-Fehler sperrt", TachoKachelKarte.fehlerSperrt("Failed to choose ARGB config."));
        assertTrue(TachoKachelKarte.fehlerSperrt(null));
        assertFalse("offline gestartet heilt sich", TachoKachelKarte.fehlerSperrt("Failed to load style: network error"));
        assertFalse(TachoKachelKarte.fehlerSperrt("Unable to resolve host \"tiles\""));

        assertEquals("osm", TachoKachelKarte.quelleFuerSeite("osm", true));
        assertEquals("ohne Kacheln wie ohne Quelle (gedaempftes Zeichen)", at.greenchili.surfmate.einstellungen.Kartenquelle.KEINE,
                TachoKachelKarte.quelleFuerSeite("osm", false));
    }

    @Test
    public void snapshotNurNachPruefungMitMarke() throws Exception {
        String b = lies(BILD);
        int pruef = b.indexOf("if (!kachelnMoeglich(ctx)) return");
        int marke = b.indexOf("if (ersterLauf) markeSetzen(ctx)");
        int start = b.indexOf("s.start({ bild ->");
        int neu = b.indexOf("s = MapSnapshotter(");
        assertTrue("Pruefung vor dem ersten Snapshotter", pruef > 0 && pruef < neu);
        assertTrue("Marke vor dem Start", marke > 0 && marke < start);
        assertTrue("Marke weg nach dem ersten Bild", b.contains("if (ersterLauf) { ersterLauf = false; markeWeg(ctx) }"));
        assertTrue("Fehler sperrt fuer die Sitzung", b.contains("if (TachoKachelKarte.fehlerSperrt(meldung)) { fehlerInSitzung = true; stopp() }"));
        assertTrue("GLES 3 + EGL", b.contains("android.opengl.EGLExt.EGL_OPENGL_ES3_BIT_KHR"));
        assertFalse("die geteilte EGL-Anzeige nie beenden", b.contains("eglTerminate("));
        String k = lies(AKT);
        assertTrue(k.contains("else TachoKachelKarte.quelleFuerSeite(q, TachoKarteBild.kachelnMoeglich(this))"));
    }

    // ------------------------------------------------------------ H1, H2, Neuigkeiten

    @Test
    public void vorinstallierteNaviAppsOhneDoppel() throws Exception {
        assertEquals("Google Maps", TachoNaviApp.naviName("com.google.android.apps.maps"));
        assertEquals("x.y", TachoNaviApp.naviName("x.y"));
        // Alle Navi-Pakete schon in der Liste (so ruft der Test keine Shell auf): nichts doppelt
        JSONArray l = new JSONArray();
        for (String p : TachoNaviApp.naviPakete()) l.put(new JSONObject().put("package", p).put("label", "x"));
        int vorher = l.length();
        JSONArray r = TachoNaviApp.naviErgaenzt(l);
        assertEquals("kein Doppel", vorher, r.length());
        assertTrue(TachoNaviApp.naviPakete().contains("com.google.android.apps.maps"));
        String t = lies("app/src/main/java/at/greenchili/surfmate/tacho/TachoNaviApp.java");
        assertTrue(t.contains("JSONArray apps = naviErgaenzt(at.greenchili.surfmate.launcher.AppLauncher.listLaunchableApps());"));
        assertTrue("nur startbare", t.contains("if (!at.greenchili.surfmate.launcher.AppLauncher.isLaunchable(p)) continue;"));
    }

    @Test
    public void keinFragezeichenMitDoppelpunkt() throws Exception {
        assertTrue(lies("app/src/main/assets/web/shared/tacho-faehigkeit.js").contains("z[1] + (/\\?$/.test(z[1]) ? ' ' : ': ') + s"));
        assertTrue(lies("app/src/main/assets/web/local/testfunktionen.html").contains("esc(z[1]) + (/\\?$/.test(z[1]) ? ' ' : ': ') + esc(s)"));
    }

    @Test
    public void neuigkeitenDatenmengeInFuenfSprachen() throws Exception {
        JSONArray v = new JSONObject(lies("app/src/main/assets/web/shared/neuigkeiten.json")).getJSONArray("versionen");
        JSONObject n = null;
        for (int i = 0; i < v.length(); i++) if ("0.1.43.9".equals(v.getJSONObject(i).optString("version"))) n = v.getJSONObject(i);
        assertNotNull(n);
        String[][] erwartet = {{"de", "etwa 5 bis 15 MB pro Stunde"}, {"en", "about 5 to 15 MB per hour"},
                {"fr", "environ 5 à 15 Mo par heure"}, {"it", "circa da 5 a 15 MB all’ora"}, {"nl", "ongeveer 5 tot 15 MB per uur"}};
        for (String[] e : erwartet) {
            JSONArray p = n.getJSONArray(e[0]);
            assertEquals(e[0], 5, p.length());
            assertTrue(e[0], p.getString(0).contains(e[1]));
            assertFalse(p.toString().contains("Erprobung"));
            assertFalse(p.toString().contains("Testversion"));
        }
    }
}
