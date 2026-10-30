package at.greenchili.surfmate.tacho;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;

/** Kern Tacho 0.1.30 Teil 2: Navi-App am Tacho – Regeln und Rückkehr-Garantie. */
public class TachoNaviAppTest {

    private static JSONObject urteil(String status, String code) throws Exception {
        return new JSONObject().put("status", status).put("code", code);
    }

    @Test
    public void erlaubtListeEnthaeltNurNaviAppsUndKeinVideo() {
        assertTrue(TachoNaviApp.istErlaubt("com.google.android.apps.maps"));
        assertTrue(TachoNaviApp.istErlaubt("com.waze"));
        assertTrue(TachoNaviApp.istErlaubt("com.iternio.abrpapp"));
        assertTrue(TachoNaviApp.istErlaubt("net.osmand"));
        for (String p : TachoNaviApp.erlaubtePakete()) {
            String k = p.toLowerCase();
            for (String video : new String[] {"youtube", "netflix", "tube", "video", "tv", "player", "spotify"}) {
                assertFalse(p, k.contains(video));
            }
        }
        assertFalse(TachoNaviApp.istErlaubt("com.google.android.youtube"));
        assertFalse(TachoNaviApp.istErlaubt(null));
        assertFalse(TachoNaviApp.istErlaubt(""));
    }

    @Test
    public void videoUndFremdeAppsNurInP() {
        assertNull(TachoNaviApp.fahrtSperre("org.schabi.newpipe", null));
        assertEquals("nur_in_p", TachoNaviApp.fahrtSperre("org.schabi.newpipe", "not_park"));
        assertEquals("nur_in_p", TachoNaviApp.fahrtSperre("org.schabi.newpipe", "gear_unknown"));
        assertEquals("nur_in_p", TachoNaviApp.fahrtSperre(null, "moving"));
        // Navi-Apps dürfen während der Fahrt am Tacho bleiben
        assertNull(TachoNaviApp.fahrtSperre("com.waze", "not_park"));
    }

    @Test
    public void einschaltenNurImStandUndNurNachBestaetigterProbe() throws Exception {
        String maps = "com.google.android.apps.maps";
        assertEquals("nicht_erlaubt",
                TachoNaviApp.einschaltSperre("org.schabi.newpipe", urteil("ja", "warnleuchten_frei"), null));
        assertEquals("nur_im_stand",
                TachoNaviApp.einschaltSperre(maps, urteil("ja", "warnleuchten_frei"), "not_park"));
        assertEquals("auf_diesem_auto_nicht_angeboten",
                TachoNaviApp.einschaltSperre(maps, urteil("unbekannt", "warnleuchten_ungeprueft"), null));
        assertEquals("auf_diesem_auto_nicht_angeboten",
                TachoNaviApp.einschaltSperre(maps, urteil("nein", "single_os"), null));
        assertEquals("auf_diesem_auto_nicht_angeboten", TachoNaviApp.einschaltSperre(maps, null, null));
        assertNull(TachoNaviApp.einschaltSperre(maps, urteil("ja", "warnleuchten_frei"), null));
    }

    @Test
    public void probeNurWoDerWegTechnischDaIst() throws Exception {
        String waze = "com.waze";
        assertNull(TachoNaviApp.probeSperre(waze, urteil("unbekannt", "warnleuchten_ungeprueft"), null));
        assertNull(TachoNaviApp.probeSperre(waze, urteil("nein", "warnleuchten_verdeckt"), null));
        assertEquals("auf_diesem_auto_nicht_angeboten",
                TachoNaviApp.probeSperre(waze, urteil("nein", "single_os"), null));
        assertEquals("nur_im_stand",
                TachoNaviApp.probeSperre(waze, urteil("unbekannt", "warnleuchten_ungeprueft"), "speed_unknown"));
    }

    @Test
    public void probeDauertHoechstens20Sekunden() {
        assertTrue(TachoNaviApp.PROBE_MS <= 20_000L);
    }

    /**
     * Rückkehr-Garantie: kein eigener Projektionsweg; Start/Ende nur über ClusterCast, dessen Controller
     * die Tacho-Anzeigen bei Zündung aus, SIGTERM und Neustart (Boot-Sweep) zurückholt.
     */
    @Test
    public void rueckkehrGarantieUndFahrtSperreSindVerdrahtet() throws IOException {
        String navi = TachoFaehigkeitTest.lies("app/src/main/java/at/greenchili/surfmate/tacho/TachoNaviApp.java");
        assertFalse(navi.contains("service call"));
        assertFalse(navi.contains("sendInfo"));
        assertFalse(navi.contains("am start"));

        String cast = TachoFaehigkeitTest.lies("app/src/main/java/at/greenchili/surfmate/launcher/ClusterCast.java");
        String start = cast.substring(cast.indexOf("public static synchronized boolean start(String pkg)"));
        int sperre = start.indexOf("TachoNaviApp.fahrtSperre(");
        int dl5 = start.indexOf("DiLink5ClusterCast.start(pkg)");
        assertTrue("fahrtSperre vor jedem Start", sperre > 0 && sperre < dl5);
        String await = cast.substring(cast.indexOf("public static boolean startAndAwait("));
        assertTrue(await.indexOf("TachoNaviApp.fahrtSperre(") < await.indexOf("DiLink5ClusterCast.startAndAwait("));
        assertTrue(cast.contains("public static void reparentStrandedCastAtBoot()"));

        String daemon = TachoFaehigkeitTest.lies("app/src/main/java/at/greenchili/surfmate/daemon/CameraDaemon.java");
        assertTrue(daemon.contains("TachoNaviApp.beiGangwechsel(gear)"));

        String acc = TachoFaehigkeitTest.lies("app/src/main/java/at/greenchili/surfmate/monitor/AccMonitor.java");
        assertTrue(acc.contains("ClusterCast.stopForAccOff()"));
        assertTrue(acc.contains("TachoNaviApp.sollBeiAccStarten()"));
    }
}
