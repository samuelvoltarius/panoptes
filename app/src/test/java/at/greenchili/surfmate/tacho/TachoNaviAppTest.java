package at.greenchili.surfmate.tacho;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Paketbezogene Fahrerdisplay-Freigabe und Rueckkehr-Garantie. */
public class TachoNaviAppTest {
    private static JSONObject urteil(String status, String code) throws Exception {
        return new JSONObject().put("status", status).put("code", code);
    }

    @Test
    public void beliebigesPaketKannEinzelnFreigegebenWerden() throws Exception {
        JSONObject f = new JSONObject().put("org.schabi.newpipe", 1234L);
        assertTrue(TachoNaviApp.istFreigegeben("org.schabi.newpipe", f));
        assertFalse(TachoNaviApp.istFreigegeben("com.waze", f));
        f.remove("org.schabi.newpipe");
        assertFalse(TachoNaviApp.istFreigegeben("org.schabi.newpipe", f));
    }

    @Test
    public void fehlgeschlagenerCommitLaesstKeineInMemoryFreigabeZurueck() throws Exception {
        String p = "org.example.any";
        JSONObject live = new JSONObject();
        assertFalse(TachoNaviApp.commitFreigabe(
                TachoFaehigkeit.JA, p, live, "", werte -> false));
        assertFalse(TachoNaviApp.istFreigegeben(p, live));
    }

    @Test
    public void frischeConfigProbeBestaetigungUndErsterStartOhneKuenstlichesUrteil() throws Exception {
        String p = "org.example.any";
        String anderes = "org.example.other";
        JSONObject live = new JSONObject();
        TachoFaehigkeit.Eingaben plattform = new TachoFaehigkeit.Eingaben();
        plattform.sdk = 29;
        plattform.uid = "2000";
        plattform.dienste = "1\tAutoContainer: [android.os.IAutoContainer]\n";

        plattform.warnleuchtenFrei = TachoNaviApp.warnleuchtenFrei(p, live);
        JSONObject vorProbe = TachoFaehigkeit.bewerte(plattform).getJSONObject("andereApps");
        assertEquals("nicht_freigegeben", TachoNaviApp.einschaltSperre(
                p, vorProbe, null, true, false));

        AtomicReference<Map<String, Object>> commit = new AtomicReference<>();
        assertTrue(TachoNaviApp.commitFreigabe(TachoFaehigkeit.JA, p, live, "", werte -> {
            commit.set(werte);
            return true;
        }));
        JSONObject gespeichert = (JSONObject) commit.get().get(TachoNaviApp.FREIGABEN);
        String gewaehltesPaket = String.valueOf(commit.get().get("paket"));
        plattform.warnleuchtenFrei = TachoNaviApp.warnleuchtenFrei(gewaehltesPaket, gespeichert);
        JSONObject nachProbe = TachoFaehigkeit.bewerte(plattform).getJSONObject("andereApps");
        assertEquals("ja", nachProbe.getString("status"));
        assertNull(TachoNaviApp.einschaltSperre(p, nachProbe, null, true,
                TachoNaviApp.istFreigegeben(p, gespeichert)));
        assertEquals("nicht_freigegeben", TachoNaviApp.einschaltSperre(
                anderes, nachProbe, null, true, TachoNaviApp.istFreigegeben(anderes, gespeichert)));
    }

    @Test
    public void ungueltigUndPanoptesBleibenAusgeschlossen() {
        assertTrue(TachoNaviApp.gueltigesFremdpaket("org.example.any_app"));
        assertFalse(TachoNaviApp.gueltigesFremdpaket("at.greenchili.surfmate"));
        assertFalse(TachoNaviApp.gueltigesFremdpaket(""));
        assertFalse(TachoNaviApp.gueltigesFremdpaket("bad package"));
        assertFalse(TachoNaviApp.gueltigesFremdpaket("../bad"));
    }

    @Test
    public void einschaltenBrauchtStartbarFreigabePUndFaehigkeit() throws Exception {
        String p = "org.example.any";
        assertEquals("nicht_installiert", TachoNaviApp.einschaltSperre(
                p, urteil("ja", "warnleuchten_frei"), null, false, true));
        assertEquals("nicht_freigegeben", TachoNaviApp.einschaltSperre(
                p, urteil("ja", "warnleuchten_frei"), null, true, false));
        assertEquals("nur_im_stand", TachoNaviApp.einschaltSperre(
                p, urteil("ja", "warnleuchten_frei"), "gear_unknown", true, true));
        assertEquals("auf_diesem_auto_nicht_angeboten", TachoNaviApp.einschaltSperre(
                p, urteil("unbekannt", "warnleuchten_ungeprueft"), null, true, true));
        assertNull(TachoNaviApp.einschaltSperre(
                p, urteil("ja", "warnleuchten_frei"), null, true, true));
    }

    @Test
    public void alteFreigabeWirdAufDiLink5AuchImZentralenStartgateGesperrt() throws Exception {
        String p = "org.example.any";
        JSONObject di5 = urteil("nein", "dilink5_totwinkel_nicht_belegt");
        assertEquals("auf_diesem_auto_nicht_angeboten",
                TachoNaviApp.fahrtSperreAuswertung(p, di5, null, true, true, false));
        assertEquals("auf_diesem_auto_nicht_angeboten",
                TachoNaviApp.fahrtSperreAuswertung(p, di5, null, true, false, true));
        assertNull(TachoNaviApp.fahrtSperreAuswertung(
                p, urteil("ja", "warnleuchten_frei"), null, true, true, false));
    }

    /** 0.1.36.1: Video/Spiele/unbekannte Apps nur in P, Navigation auch beim Fahren. */
    @Test
    public void gangPnachDStopptNichtNaviAppNaviLaeuftWeiter() {
        int p = at.greenchili.surfmate.monitor.GearMonitor.GEAR_P;
        int d = at.greenchili.surfmate.monitor.GearMonitor.GEAR_D;
        String video = "com.google.android.youtube";
        String unbekannt = "org.example.any";
        for (String nicht : new String[] {video, unbekannt, "org.schabi.newpipe"}) {
            assertTrue(nicht, TachoNaviApp.mussBeiGangStoppen(d, nicht, true));
            assertFalse("in P bleibt " + nicht, TachoNaviApp.mussBeiGangStoppen(p, nicht, true));
        }
        for (String navi : new String[] {"com.google.android.apps.maps", "com.waze", "net.osmand", "net.osmand.plus",
                "com.iternio.abrpapp", "com.here.app.maps", "com.sygic.aura", "com.generalmagic.magicearth"}) {
            assertTrue(navi, TachoNaviApp.istNavi(navi));
            assertFalse("Navi läuft weiter: " + navi, TachoNaviApp.mussBeiGangStoppen(d, navi, true));
            assertTrue("nicht freigegeben weg: " + navi, TachoNaviApp.mussBeiGangStoppen(d, navi, false));
        }
        assertTrue(TachoNaviApp.istNavi("at.greenchili.surfmate"));
        assertFalse(TachoNaviApp.istNavi(video));
        assertFalse(TachoNaviApp.istNavi(null));
        assertFalse("nichts aktiv", TachoNaviApp.mussBeiGangStoppen(d, null, true));
        // unbekannter Gang (nicht P) gilt wie Fahrt
        assertTrue(TachoNaviApp.mussBeiGangStoppen(0, video, true));
    }

    @Test
    public void keinStartEinerNichtNaviAppAusserhalbVonP() throws Exception {
        JSONObject ja = urteil("ja", "warnleuchten_frei");
        String video = "com.google.android.youtube";
        for (String grund : new String[] {"not_park", "moving", "gear_unknown", "state_unknown", "speed_unknown"}) {
            assertEquals(grund, "nur_im_stand",
                    TachoNaviApp.fahrtSperreAuswertung(video, ja, grund, true, true, false));
            assertNull("Navi darf: " + grund,
                    TachoNaviApp.fahrtSperreAuswertung("com.waze", ja, grund, true, true, false));
        }
        assertNull("in P darf jede freigegebene App",
                TachoNaviApp.fahrtSperreAuswertung(video, ja, null, true, true, false));
        assertEquals("Freigabe bleibt Pflicht", "nicht_freigegeben",
                TachoNaviApp.fahrtSperreAuswertung("com.waze", ja, null, true, false, false));
        assertEquals("Probe weiter nur in P", "nur_im_stand",
                TachoNaviApp.fahrtSperreAuswertung("com.waze", urteil("unbekannt", "warnleuchten_ungeprueft"),
                        "not_park", true, false, true));
    }

    /** Alle Startwege (ACC-Autostart, Tastenbelegung, Automation, Web) laufen durch fahrtSperre mit Fahrzustand. */
    @Test
    public void alleStartwegeNutzenDieFahrtSperreMitFahrzustand() throws IOException {
        String cast = TachoFaehigkeitTest.lies(
                "app/src/main/java/at/greenchili/surfmate/launcher/ClusterCast.java");
        String start = cast.substring(cast.indexOf("public static synchronized boolean start(String pkg)"));
        start = start.substring(0, start.indexOf("lastDrivingRefusal = \"\";"));
        assertTrue(start.contains("TachoNaviApp.fahrtSperre("));
        assertTrue(start.contains("drivingBlockReason()"));
        String policy = TachoFaehigkeitTest.lies(
                "app/src/main/java/at/greenchili/surfmate/tacho/TachoNaviApp.java");
        assertTrue(policy.contains("if (bewegungsGrund != null && !fahrt) return \"nur_im_stand\";"));
        assertTrue(policy.contains("istStartbar(p), istFreigegeben(p), p.equals(probeStartPaket), fahrtErlaubt(p));"));
        assertTrue(policy.contains("mussBeiGangStoppen(gang, p, p != null && istFreigegeben(p), p != null && fahrtErlaubt(p))"));
    }

    @Test
    public void probeNurStartbarUndEindeutigInP() throws Exception {
        String p = "org.example.any";
        assertEquals("nicht_installiert", TachoNaviApp.probeSperre(
                p, urteil("unbekannt", "warnleuchten_ungeprueft"), null, false));
        assertEquals("nur_im_stand", TachoNaviApp.probeSperre(
                p, urteil("unbekannt", "warnleuchten_ungeprueft"), "state_unknown", true));
        assertEquals("auf_diesem_auto_nicht_angeboten", TachoNaviApp.probeSperre(
                p, urteil("nein", "single_os"), null, true));
        assertNull(TachoNaviApp.probeSperre(
                p, urteil("unbekannt", "warnleuchten_ungeprueft"), null, true));
        assertTrue(TachoNaviApp.PROBE_MS <= 20_000L);
    }

    @Test
    public void gemeinsamePolicyWiderrufUndRueckkehrSindVerdrahtet() throws IOException {
        String policy = TachoFaehigkeitTest.lies(
                "app/src/main/java/at/greenchili/surfmate/tacho/TachoNaviApp.java");
        assertFalse(policy.contains("ERLAUBT"));
        assertTrue(policy.contains("AppLauncher.listLaunchableApps()"));
        assertTrue(policy.contains("AppLauncher.isLaunchable"));
        assertTrue(policy.contains("f.remove(p)"));
        assertTrue(policy.contains("commitFreigabe(frei, p, freigaben(), paket()"));
        assertTrue(policy.contains("widerrufen.add(p)"));
        assertTrue(policy.contains("stoppePaket(p)"));
        assertTrue(policy.contains("if (p != null && p.equals(aktiv))"));
        assertFalse(policy.contains("service call"));
        assertFalse(policy.contains("sendInfo"));

        String cast = TachoFaehigkeitTest.lies(
                "app/src/main/java/at/greenchili/surfmate/launcher/ClusterCast.java");
        String start = cast.substring(cast.indexOf("public static synchronized boolean start(String pkg)"));
        assertTrue(start.indexOf("TachoNaviApp.fahrtSperre(")
                < start.indexOf("DiLink5ClusterCast.start(pkg)"));
        String await = cast.substring(cast.indexOf("public static boolean startAndAwait("));
        assertTrue(await.indexOf("TachoNaviApp.fahrtSperre(")
                < await.indexOf("DiLink5ClusterCast.startAndAwait("));

        String daemon = TachoFaehigkeitTest.lies(
                "app/src/main/java/at/greenchili/surfmate/daemon/CameraDaemon.java");
        assertTrue(daemon.contains("TachoNaviApp.beiGangwechsel(gear)"));
        String acc = TachoFaehigkeitTest.lies(
                "app/src/main/java/at/greenchili/surfmate/monitor/AccMonitor.java");
        assertTrue(acc.contains("ClusterCast.stopForAccOff()"));
        assertTrue(acc.contains("TachoNaviApp.sollBeiAccStarten()"));
        assertTrue(policy.contains("return istAn() && fahrtSperre(p, null) == null"));
    }

    @Test
    public void webUnterAutoNutztPaketbezogeneFreigabeUndWiderruf() throws IOException {
        String html = TachoFaehigkeitTest.lies(
                "app/src/main/assets/web/local/vehicle-control.html");
        String js = TachoFaehigkeitTest.lies(
                "app/src/main/assets/web/shared/tacho-appwahl.js");
        String api = TachoFaehigkeitTest.lies(
                "app/src/main/java/at/greenchili/surfmate/server/TachoApiHandler.java");
        assertTrue(html.contains("id=\"clusterAppApproval\""));
        assertTrue(html.contains("Tempo und Warnleuchten"));
        // 0.1.37: Freigabe ohne Probe, Sicherheitsknopf „Tacho wieder normal“
        assertFalse(js.contains("/api/tacho/navi/probe"));
        assertTrue(js.contains("{frei:'ja', paket:p}"));
        assertTrue(js.contains("/api/tacho/normal"));
        assertTrue(html.contains("id=\"clusterTachoNormal\""));
        assertTrue(js.contains("{frei:'nein', paket:selected()}"));
        assertTrue(js.contains("data.angeboten === false"));
        assertTrue(js.contains("cluster_app_dilink5_blocked"));
        assertTrue(api.contains("in.optString(\"paket\", \"\")"));
    }

    @Test
    public void totwinkelHatVorrangOhneAppOderGeometrieZuErsetzen() throws IOException {
        String cast = TachoFaehigkeitTest.lies(
                "app/src/main/java/at/greenchili/surfmate/launcher/ClusterCast.java");
        String gpu = TachoFaehigkeitTest.lies(
                "app/src/main/java/at/greenchili/surfmate/surveillance/GpuSurveillancePipeline.java");
        String controller = TachoFaehigkeitTest.lies(
                "app/src/main/java/at/greenchili/surfmate/surveillance/ClusterProjectionController.java");
        assertTrue(cast.contains("private static final String TOKEN = \"castapp\""));
        assertTrue(cast.contains("acquireSustained(TOKEN)"));
        assertTrue(controller.contains("if (sustainedHeld()) return;"));

        int showAt = gpu.indexOf("private void clusterShowWhenReady()");
        int readyAt = gpu.indexOf("public void onClusterProjectionReady()", showAt);
        String show = gpu.substring(showAt, readyAt);
        assertTrue(show.contains("placeOnLayerStackChecked("));
        assertTrue(show.contains("failOnceIfArmed("));
        assertFalse(show.contains("ClusterCast.start"));
        assertFalse(show.contains("ClusterCast.stop"));
        assertFalse(show.contains("ClusterCast.resize"));
        assertTrue(show.contains("forceClose(\"bs-overlay-failed\")"));

        assertTrue(gpu.contains("setBlindSpotVisible(false)"));
        assertTrue(gpu.contains("requestCloseLingered()"));
        assertTrue(cast.contains("freeformBoundsCopy()"));
    }
}
