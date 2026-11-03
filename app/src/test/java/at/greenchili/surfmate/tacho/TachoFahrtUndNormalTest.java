package at.greenchili.surfmate.tacho;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.genai.KiSperre;
import at.greenchili.surfmate.monitor.GearMonitor;
import at.greenchili.surfmate.server.ExternGuard;
import at.greenchili.surfmate.server.HttpServer;
import at.greenchili.surfmate.server.WebHandyGuard;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 0.1.37 Nacharbeit (Projektinhaber): „auch beim Fahren“ je App nach eigenem Haftungshinweis, Freigabe ohne Probe,
 * Knopf „Tacho wieder normal“ stoppt jede Projektion.
 */
public class TachoFahrtUndNormalTest {
    private static final String VIDEO = "com.google.android.youtube";
    private static final String NAVI = "com.waze";
    private static final int D = GearMonitor.GEAR_P + 3;

    private final TachoNaviApp.Stopper[] echt = TachoNaviApp.stopper;

    @After
    public void zurueck() {
        TachoNaviApp.stopper = echt;
    }

    private static JSONObject urteil(String status, String code) throws Exception {
        return new JSONObject().put("status", status).put("code", code);
    }

    @Test
    public void ohneHinweisKeinBeimFahren() throws Exception {
        assertEquals("fahrt_hinweis_noetig", TachoNaviApp.fahrtSperreSetzen(VIDEO, true, true, false));
        assertEquals("nicht_freigegeben", TachoNaviApp.fahrtSperreSetzen(VIDEO, true, false, true));
        assertEquals("ungueltiges_paket", TachoNaviApp.fahrtSperreSetzen("bad package", true, true, true));
        assertNull(TachoNaviApp.fahrtSperreSetzen(VIDEO, true, true, true));
        // Ausschalten geht immer, auch ohne Hinweis
        assertNull(TachoNaviApp.fahrtSperreSetzen(VIDEO, false, false, false));
        // Hinweis gilt nur in der aktuellen Textversion
        assertFalse(TachoNaviApp.fahrtHinweisBestaetigt(null));
        assertFalse(TachoNaviApp.fahrtHinweisBestaetigt(new JSONObject().put("version", "alt").put("zeit", 5L)));
        assertFalse(TachoNaviApp.fahrtHinweisBestaetigt(
                new JSONObject().put("version", TachoNaviApp.FAHRT_HINWEIS_VERSION)));
        assertTrue(TachoNaviApp.fahrtHinweisBestaetigt(
                new JSONObject().put("version", TachoNaviApp.FAHRT_HINWEIS_VERSION).put("zeit", 5L)));
        assertFalse(TachoNaviApp.fahrtHinweisBestaetigen("alt").optBoolean("ok"));
    }

    @Test
    public void standardWieBisherNaviJaAlleAnderenNein() throws Exception {
        JSONObject leer = new JSONObject();
        assertTrue(TachoNaviApp.fahrtErlaubt(NAVI, leer));
        assertFalse(TachoNaviApp.fahrtErlaubt(VIDEO, leer));
        assertFalse(TachoNaviApp.fahrtErlaubt("org.example.any", leer));
        assertFalse("Navi lässt sich abwählen", TachoNaviApp.fahrtErlaubt(NAVI, new JSONObject().put(NAVI, false)));
        assertTrue(TachoNaviApp.fahrtErlaubt(VIDEO, new JSONObject().put(VIDEO, true)));
    }

    @Test
    public void mitOptionLaeuftDieAppNachPNachDWeiterOhneOptionStopp() throws Exception {
        JSONObject ja = urteil("ja", "warnleuchten_frei");
        // mit Option
        assertFalse(TachoNaviApp.mussBeiGangStoppen(D, VIDEO, true, true));
        assertNull(TachoNaviApp.fahrtSperreAuswertung(VIDEO, ja, "gear_d", true, true, false, true));
        // ohne Option: Stopp wie bisher
        assertTrue(TachoNaviApp.mussBeiGangStoppen(D, VIDEO, true, false));
        assertEquals("nur_im_stand",
                TachoNaviApp.fahrtSperreAuswertung(VIDEO, ja, "gear_d", true, true, false, false));
        // Option ersetzt nie Freigabe oder Fähigkeit
        assertTrue(TachoNaviApp.mussBeiGangStoppen(D, VIDEO, false, true));
        assertEquals("nicht_freigegeben",
                TachoNaviApp.fahrtSperreAuswertung(VIDEO, ja, "gear_d", true, false, false, true));
        assertEquals("auf_diesem_auto_nicht_angeboten", TachoNaviApp.fahrtSperreAuswertung(
                VIDEO, urteil("nein", "dilink5_totwinkel_nicht_belegt"), "gear_d", true, true, false, true));
        // in P immer erlaubt
        assertFalse(TachoNaviApp.mussBeiGangStoppen(GearMonitor.GEAR_P, VIDEO, true, false));
    }

    @Test
    public void videoAppsSindGekennzeichnetAberNichtGesperrt() {
        assertTrue(TachoNaviApp.istVideo(VIDEO));
        assertTrue(TachoNaviApp.istVideo("com.netflix.mediaclient"));
        assertFalse(TachoNaviApp.istVideo(NAVI));
        assertNull(TachoNaviApp.fahrtSperreSetzen(VIDEO, true, true, true));
    }

    @Test
    public void kiAutomationKopplungUndHandyWebKoennenDieOptionNichtSetzen() throws Exception {
        Method erlaubt = HttpServer.class.getDeclaredMethod("isAutomationAllowed", String.class);
        erlaubt.setAccessible(true);
        for (String p : new String[]{"/api/tacho/navi/fahrt", "/api/tacho/navi/fahrt/hinweis", "/api/tacho/normal"}) {
            assertFalse("Automation/KI " + p, (Boolean) erlaubt.invoke(null, p));
            assertTrue("Kopplung " + p, ExternGuard.blocks("PUT", p));
            assertTrue("Kopplung handy " + p, ExternGuard.blocks("PUT", p, "handy"));
        }
        assertTrue(WebHandyGuard.blocks("PUT", "/api/tacho/navi/fahrt"));
        assertTrue(WebHandyGuard.blocks("PUT", "/api/tacho/navi/fahrt/hinweis"));
        for (String typ : new String[]{"api", "tachofahrt", "clustercast", "openapp"}) {
            assertFalse(typ, KiSperre.erlaubterTyp(typ));
        }
        String api = TachoFaehigkeitTest.lies("app/src/main/java/at/greenchili/surfmate/server/TachoApiHandler.java");
        assertTrue("Kopplungen vor jeder Tacho-Route abgewiesen",
                api.indexOf("AuthMiddleware.isExternRequest()") < api.indexOf("/api/tacho/navi/fahrt"));
        String log = TachoFaehigkeitTest.lies("app/src/main/java/at/greenchili/surfmate/server/BefehlsLog.java");
        assertTrue(log.contains("\"/api/tacho/navi/fahrt\", \"/api/tacho/normal\""));
        String app = TachoFaehigkeitTest.lies("app/src/main/java/at/greenchili/surfmate/tacho/TachoNaviApp.java");
        assertTrue(app.contains("BefehlsLog.ereignis(\"tacho.fahrt.\" + was"));
    }

    @Test
    public void freigabeOhneProbeAberMitHinweisUndFaehigkeit() throws Exception {
        String p = "org.example.any";
        JSONObject angeboten = urteil("unbekannt", "warnleuchten_ungeprueft");
        assertNull(TachoNaviApp.freigabeSperre(p, angeboten, true, true));
        assertNull(TachoNaviApp.freigabeSperre(p, urteil("ja", "warnleuchten_frei"), true, true));
        assertEquals("hinweis_noetig", TachoNaviApp.freigabeSperre(p, angeboten, true, false));
        assertEquals("nicht_installiert", TachoNaviApp.freigabeSperre(p, angeboten, false, true));
        assertEquals("auf_diesem_auto_nicht_angeboten",
                TachoNaviApp.freigabeSperre(p, urteil("nein", "single_os"), true, true));
        assertEquals("auf_diesem_auto_nicht_angeboten",
                TachoNaviApp.freigabeSperre(p, urteil("nein", "dilink5_totwinkel_nicht_belegt"), true, true));
        assertEquals("auf_diesem_auto_nicht_angeboten", TachoNaviApp.freigabeSperre(p, null, true, true));
        String app = TachoFaehigkeitTest.lies("app/src/main/java/at/greenchili/surfmate/tacho/TachoNaviApp.java");
        assertFalse("Freigabe verlangt keine Probe mehr", app.contains("\"probe_noetig\""));
    }

    @Test
    public void tachoWiederNormalStopptJedeProjektionAuchWennEineScheitert() throws Exception {
        AtomicInteger aufrufe = new AtomicInteger();
        TachoNaviApp.stopper = new TachoNaviApp.Stopper[]{
                () -> { aufrufe.incrementAndGet(); return true; },
                () -> { aufrufe.incrementAndGet(); return true; },
                () -> { aufrufe.incrementAndGet(); return true; }};
        assertTrue(TachoNaviApp.tachoNormal().getBoolean("success"));
        assertEquals(3, aufrufe.get());

        aufrufe.set(0);
        TachoNaviApp.stopper = new TachoNaviApp.Stopper[]{
                () -> { aufrufe.incrementAndGet(); throw new IllegalStateException("weg"); },
                () -> { aufrufe.incrementAndGet(); return false; },
                () -> { aufrufe.incrementAndGet(); return true; }};
        assertFalse(TachoNaviApp.tachoNormal().getBoolean("success"));
        assertEquals("jede Stufe läuft", 3, aufrufe.get());
    }

    @Test
    public void echteStoppsDeckenAppSpiegelUndKarteAb() throws IOException {
        String app = TachoFaehigkeitTest.lies("app/src/main/java/at/greenchili/surfmate/tacho/TachoNaviApp.java");
        assertTrue(app.contains("ClusterCast.stop()"));
        assertTrue(app.contains("ClusterMirrorController.getInstance().stop()"));
        assertTrue(app.contains("ClusterMapProjector.stop()"));
        String web = TachoFaehigkeitTest.lies("app/src/main/assets/web/shared/tacho-appwahl.js");
        String home = TachoFaehigkeitTest.lies("app/src/main/assets/web/local/index.html");
        assertTrue(web.contains("'/api/tacho/normal', 'POST'"));
        assertTrue(home.contains("fetch('/api/tacho/normal'"));
        assertFalse("Sicherheitsknopf ohne Rückfrage", home.contains("Home.command(this,'/api/tacho/normal'"));
    }
}
