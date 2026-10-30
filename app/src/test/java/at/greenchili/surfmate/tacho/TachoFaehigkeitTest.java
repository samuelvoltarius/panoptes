package at.greenchili.surfmate.tacho;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Kern Tacho 0.1.30 Teil 1: Fähigkeitstest mit Schein-Daten (Surf, Seal, DiLink 4, DiLink 5). */
public class TachoFaehigkeitTest {

    private static final String DIENSTE_MIT_AC =
            "Found 3 services:\n0\tautoservice: [android.gui.BYDAutoServer]\n"
                    + "1\tAutoContainer: [android.os.IAutoContainer]\n2\tdisplay: [android.hardware.display.IDisplayManager]\n";
    private static final String DIENSTE_OHNE_AC =
            "Found 1 services:\n0\tautoservice: [android.gui.BYDAutoServer]\n";
    private static final String NUR_HAUPTSCHIRM =
            "  mDisplayId=0\n    mName=\"Integrierter Bildschirm\"\n  DisplayDeviceInfo{\"x\": 1280 x 720, 60.0 fps}\n";

    /** Alfreds Surf am 30.10. (TACHO_AUTO_CHECK.md): Single-OS, AutoContainer gelistet, XDJA da. */
    private static TachoFaehigkeit.Eingaben surf() {
        TachoFaehigkeit.Eingaben e = new TachoFaehigkeit.Eingaben();
        e.singleOs = "1\n";
        e.fissionMode = "cell";
        e.modell = "BYD Dolphin Surf";
        e.sdk = 29;
        e.displays = NUR_HAUPTSCHIRM;
        e.dienste = DIENSTE_MIT_AC;
        e.xdjaInstalliert = true;
        e.xdjaAutoDisplay = true;
        e.amapInstalliert = true;
        e.uid = "2000\n";
        return e;
    }

    /** Seal EU DiLink 3, Dual-OS (DashCast getestet). */
    private static TachoFaehigkeit.Eingaben seal() {
        TachoFaehigkeit.Eingaben e = new TachoFaehigkeit.Eingaben();
        e.singleOs = "0";
        e.modell = "BYD Seal";
        e.sdk = 29;
        e.displays = NUR_HAUPTSCHIRM;   // die Fläche entsteht erst beim Öffnen der Projektion
        e.dienste = DIENSTE_MIT_AC;
        e.uid = "2000";
        return e;
    }

    @Test
    public void surfMeldetDreimalNeinMitGrundUndEhrlichemHinweis() throws Exception {
        JSONObject r = TachoFaehigkeit.bewerte(surf());
        for (String k : new String[] {"totwinkel", "karte", "andereApps"}) {
            assertEquals(k, "nein", r.getJSONObject(k).getString("status"));
            assertEquals(k, "single_os", r.getJSONObject(k).getString("code"));
            assertFalse(r.getJSONObject(k).getString("grund").isEmpty());
        }
        assertTrue(r.getString("hinweis").contains("ohne Gegenstück"));
        assertFalse(r.getJSONObject("plattform").getBoolean("autoContainerNative"));
        assertEquals("3", r.getJSONObject("plattform").getString("dilink"));
        assertTrue(r.getJSONObject("plattform").getBoolean("xdjaAutoDisplay"));
    }

    @Test
    public void sealDualOsMeldetTotwinkelUndKarteJaAberAndereAppsErstNachProbe() throws Exception {
        JSONObject r = TachoFaehigkeit.bewerte(seal());
        assertEquals("ja", r.getJSONObject("totwinkel").getString("status"));
        assertTrue(r.getJSONObject("totwinkel").getBoolean("beta"));
        assertEquals("ja", r.getJSONObject("karte").getString("status"));
        assertEquals("unbekannt", r.getJSONObject("andereApps").getString("status"));
        assertEquals("warnleuchten_ungeprueft", r.getJSONObject("andereApps").getString("code"));
        assertFalse(r.has("hinweis"));
    }

    @Test
    public void andereAppsFolgtDerWarnleuchtenProbe() throws Exception {
        TachoFaehigkeit.Eingaben e = seal();
        e.warnleuchtenFrei = "ja";
        assertEquals("ja", TachoFaehigkeit.bewerte(e).getJSONObject("andereApps").getString("status"));
        e.warnleuchtenFrei = "nein";
        JSONObject nein = TachoFaehigkeit.bewerte(e).getJSONObject("andereApps");
        assertEquals("nein", nein.getString("status"));
        assertEquals("warnleuchten_verdeckt", nein.getString("code"));
    }

    @Test
    public void diLink5MitGeteilterFlaecheWirdErkannt() throws Exception {
        TachoFaehigkeit.Eingaben e = new TachoFaehigkeit.Eingaben();
        e.singleOs = "";
        e.sdk = 31;
        e.displays = NUR_HAUPTSCHIRM
                + "  mDisplayId=2\n    mName=\"shared_fission_bg_XDJAScreenProjection_0\"\n"
                + "  DisplayDeviceInfo{\"x\": 1920 x 720, 60.0 fps}\n";
        e.dienste = DIENSTE_OHNE_AC;   // DiLink 5 startet den Dienst erst bei Bedarf
        e.uid = "2000";
        JSONObject r = TachoFaehigkeit.bewerte(e);
        JSONObject p = r.getJSONObject("plattform");
        assertEquals("5", p.getString("dilink"));
        assertTrue(p.getBoolean("sharedFission"));
        assertEquals(2, p.getJSONObject("tachoDisplay").getInt("id"));
        assertEquals("1920x720", p.getJSONObject("tachoDisplay").getString("groesse"));
        assertEquals("ja", r.getJSONObject("karte").getString("status"));
    }

    @Test
    public void echtesDisplayInfoFormatWirdGelesen() throws Exception {
        String d = "  mBaseDisplayInfo=DisplayInfo{\"Built-in Screen\", uniqueId \"local:0\", displayId 0, "
                + "FLAG_SECURE, real 1280 x 720, largest app 1280 x 720}\n"
                + "  mBaseDisplayInfo=DisplayInfo{\"fission_bg_xdjaVirtualSurface\", uniqueId \"virtual:x\", "
                + "displayId 1, FLAG_PRESENTATION, real 1920 x 720, largest app 1920 x 720}\n";
        JSONObject t = TachoFaehigkeit.tachoDisplay(d);
        assertEquals(1, t.getInt("id"));
        assertEquals("1920x720", t.getString("groesse"));
        assertEquals(null, TachoFaehigkeit.tachoDisplay(NUR_HAUPTSCHIRM));
    }

    @Test
    public void diLink4OhneProjektionsdienstMeldetNein() throws Exception {
        TachoFaehigkeit.Eingaben e = seal();
        e.sdk = 30;
        e.dienste = DIENSTE_OHNE_AC;
        JSONObject r = TachoFaehigkeit.bewerte(e);
        assertEquals("4", r.getJSONObject("plattform").getString("dilink"));
        assertFalse(r.getJSONObject("plattform").getBoolean("dilinkSicher"));
        assertEquals("kein_projektionsdienst", r.getJSONObject("totwinkel").getString("code"));
    }

    @Test
    public void ohneShellRechteNein() throws Exception {
        TachoFaehigkeit.Eingaben e = seal();
        e.uid = "10123";
        assertEquals("keine_shell_rechte",
                TachoFaehigkeit.bewerte(e).getJSONObject("andereApps").getString("code"));
    }

    @Test
    public void leereEingabenSindNieJa() throws Exception {
        JSONObject r = TachoFaehigkeit.bewerte(new TachoFaehigkeit.Eingaben());
        for (String k : new String[] {"totwinkel", "karte", "andereApps"}) {
            assertEquals(k, "nein", r.getJSONObject(k).getString("status"));
        }
        assertNotNull(TachoFaehigkeit.bewerte(null));
    }

    @Test
    public void textEnthaeltUrteileAberKeineKennungOderOrt() throws Exception {
        TachoFaehigkeit.Eingaben e = seal();
        e.clusterFirmware = "dilink3.0_6125f_ivi_cluster_mp0712_dev.202601160";
        String text = TachoFaehigkeit.alsText(TachoFaehigkeit.bewerte(e));
        assertTrue(text.contains("Totwinkel am Tacho: ja"));
        assertTrue(text.contains("Andere Apps am Tacho: unbekannt"));
        assertTrue(text.contains("BYD Seal"));
        String klein = text.toLowerCase();
        for (String verboten : new String[] {"vin", "lat", "lon", "gps", "konto", "uid", "serial"}) {
            assertFalse(verboten, klein.contains(verboten));
        }
    }

    @Test
    public void serviceListWirdNachNamenGeprueftNichtNachTeilstring() {
        assertTrue(TachoFaehigkeit.enthaeltDienst(DIENSTE_MIT_AC, "AutoContainer"));
        assertFalse(TachoFaehigkeit.enthaeltDienst("0\tMyAutoContainerProxy: [x]\n", "AutoContainer"));
    }

    /** Teil 1 darf keinen einzigen Schreibweg enthalten: kein service call, kein am, kein Binden. */
    @Test
    public void erhebungIstNurLesend() throws IOException {
        String src = lies("app/src/main/java/at/greenchili/surfmate/tacho/TachoFaehigkeit.java");
        String erhebung = src.substring(src.indexOf("public static Eingaben erhebe()"));
        for (String verboten : new String[] {"service call", "\"am ", "am start", "startservice",
                "bindService", "sendBroadcast", "startActivity", "setprop", "settings put",
                "ClusterCast", "ClusterProjectionController", "sendInfo"}) {
            assertFalse(verboten, erhebung.contains(verboten));
        }
    }

    static String lies(String relativ) throws IOException {
        Path cur = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (cur != null) {
            Path c = cur.resolve(relativ);
            if (Files.isRegularFile(c)) return at.greenchili.surfmate.testutil.Quelltext.lies(c);
            Path m = cur.resolve(relativ.replaceFirst("^app/", ""));
            if (Files.isRegularFile(m)) return at.greenchili.surfmate.testutil.Quelltext.lies(m);
            cur = cur.getParent();
        }
        throw new AssertionError("nicht gefunden: " + relativ);
    }
}
