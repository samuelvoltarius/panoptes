package at.greenchili.surfmate.appupdate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.surveillance.WaechterStandardHinweis;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

/**
 * 0.1.38.8 Wächter Standard an, Phase 3: der Daemon-Takt fragt einmal je versionCode (Entscheidung und Marker:
 * {@code WaechterStandardHinweisTest}). Hier der Ablauf im {@link AppUpdateDienst} mit einer Umgebung ohne Versand.
 * 0.1.43.13: {@code gefragt} zählt nur, was wirklich am Display stand; die Frage kommt nur im sicheren Stand.
 */
public class WaechterStandardDienstTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static JSONObject bestand(JSONObject surveillance) throws Exception {
        JSONObject root = new JSONObject().put("einrichtung",
                new JSONObject().put("art", "bestand").put("schritte", new JSONObject().put("fertig", "erledigt")));
        if (surveillance != null) root.put("surveillance", surveillance);
        return root;
    }

    private static JSONObject neu(String fertig) throws Exception {
        return new JSONObject().put("einrichtung",
                new JSONObject().put("art", "neu").put("schritte", new JSONObject().put("fertig", fertig)));
    }

    int vc = 70;
    long jetzt = 1_000_000L;
    JSONObject konfig;
    int gefragt;
    int gemeldet;
    /** 0.1.43.13: Fahrzeugwerte; Standard = steht in P. */
    String fahrsperre = null;
    Boolean gangP = Boolean.TRUE;
    double kmh = 0;
    boolean displayQuittiert = true;

    private AppUpdateDienst dienst(File basis, File dd) {
        AppUpdateDienst.Umgebung env = new AppUpdateDienst.Umgebung() {
            @Override public int installierterVc() { return vc; }
            @Override public String installierteVersion() { return "0.1.38.8"; }
            @Override public Object[] autoAus() { return new Object[]{true, null}; }
            @Override public double kmh() { return kmh; }
            @Override public Boolean accAus() { return null; }
            @Override public String config(String key) { return null; }
            @Override public File basis() { return basis; }
            @Override public File arbeitsordner() { return dd; }
            @Override public String installiertesZertifikat() { return null; }
            @Override public String skript(File apk) { return "#!/system/bin/sh\n"; }
            @Override public void starteAbgekoppelt(File s, File apk, String key, int v, String tag) { }
            @Override public long jetzt() { return jetzt; }
            @Override public JSONObject konfig() { return konfig; }
            @Override public String fahrsperre() { return fahrsperre; }
            @Override public Boolean gangP() { return gangP; }
            @Override public boolean zeigeWaechterFrage() {
                if (!displayQuittiert) return false;
                gefragt++;
                return true;
            }
            @Override public void meldeWaechterFrage() { gemeldet++; }
        };
        return new AppUpdateDienst(new AppUpdateDienst.Netz() {
            @Override public String text(String url) { return null; }
            @Override public long groesse(String url) { return -1; }
            @Override public void laden(String url, File ziel, long max, java.util.function.IntConsumer f) { }
        }, env);
    }

    @Test
    public void dienstFragtEinmalAuchNachNeustartUndNichtErneutAufDerselbenVersion() throws Exception {
        File basis = tmp.newFolder("surfmate");
        File dd = tmp.newFolder("deploy");
        konfig = bestand(null);
        dienst(basis, dd).waechterHinweisTakt(jetzt);
        assertEquals(1, gefragt);
        dienst(basis, dd).waechterHinweisTakt(jetzt + 10 * AppUpdateDienst.MELDUNG_TAKT_MS);
        assertEquals("nach Neustart nicht noch einmal", 1, gefragt);
        vc = 71;                                                  // nächste Version, Wächter immer noch ohne Wahl
        dienst(basis, dd).waechterHinweisTakt(jetzt + 20 * AppUpdateDienst.MELDUNG_TAKT_MS);
        assertEquals("je Version einmal", 2, gefragt);
    }

    @Test
    public void dienstFragtNichtBeiBewusstAusOderSchonEingerichtetUndSetztDenMarker() throws Exception {
        File basis = tmp.newFolder("surfmate");
        File dd = tmp.newFolder("deploy");
        konfig = bestand(new JSONObject().put("disabledByUser", true));
        dienst(basis, dd).waechterHinweisTakt(jetzt);
        assertEquals(0, gefragt);
        assertEquals("kein Nerven danach", 70, WaechterStandardHinweis.ladeMarker(basis));
        konfig = bestand(null);                                   // später ohne Wahl: auf dieser Version trotzdem nicht mehr
        dienst(basis, dd).waechterHinweisTakt(jetzt + 10 * AppUpdateDienst.MELDUNG_TAKT_MS);
        assertEquals(0, gefragt);
    }

    @Test
    public void dienstWartetOhneKonfigurationUndWaehrendDesAssistenten() throws Exception {
        File basis = tmp.newFolder("surfmate");
        File dd = tmp.newFolder("deploy");
        konfig = null;
        AppUpdateDienst d = dienst(basis, dd);
        d.waechterHinweisTakt(jetzt);
        assertEquals(0, gefragt);
        assertEquals("Marker bleibt offen", 0, WaechterStandardHinweis.ladeMarker(basis));
        konfig = neu("offen");
        d.waechterHinweisTakt(jetzt + 2 * AppUpdateDienst.MELDUNG_TAKT_MS);
        assertEquals(0, gefragt);
        assertEquals(0, WaechterStandardHinweis.ladeMarker(basis));
        konfig = bestand(null);                                   // erstes Öffnen: Bestand
        d.waechterHinweisTakt(jetzt + 4 * AppUpdateDienst.MELDUNG_TAKT_MS);
        assertEquals(1, gefragt);
    }

    // ------------------------------------------------------------- 0.1.43.13 Fahrtsperre (E2E 0.1.43.12, B1)

    @Test
    public void waehrendDerFahrtNichtGezeigtUndMarkerNichtVerbraucht() throws Exception {
        File basis = tmp.newFolder("surfmate");
        File dd = tmp.newFolder("deploy");
        konfig = bestand(null);
        fahrsperre = "not_park";
        gangP = Boolean.FALSE;
        kmh = 50;
        AppUpdateDienst d = dienst(basis, dd);
        d.waechterHinweisTakt(jetzt);
        d.waechterHinweisTakt(jetzt + 2 * AppUpdateDienst.MELDUNG_TAKT_MS);
        assertEquals("nicht am Display", 0, gefragt);
        assertEquals("auch nicht Telegram/Handy", 0, gemeldet);
        assertEquals("Marker offen", 0, WaechterStandardHinweis.ladeMarker(basis));
        // D, aber gerade 0 km/h (Ampel): auch nicht, Gang bekannt außerhalb P
        fahrsperre = null;
        kmh = 0;
        d.waechterHinweisTakt(jetzt + 4 * AppUpdateDienst.MELDUNG_TAKT_MS);
        assertEquals(0, gefragt);
        assertEquals(0, WaechterStandardHinweis.ladeMarker(basis));
        // beim nächsten Stillstand in P: jetzt
        gangP = Boolean.TRUE;
        d.waechterHinweisTakt(jetzt + 6 * AppUpdateDienst.MELDUNG_TAKT_MS);
        assertEquals("aufgeschoben, nicht verworfen", 1, gefragt);
        assertEquals(1, gemeldet);
        assertEquals(70, WaechterStandardHinweis.ladeMarker(basis));
    }

    @Test
    public void imStandInPGezeigt() throws Exception {
        File basis = tmp.newFolder("surfmate");
        File dd = tmp.newFolder("deploy");
        konfig = bestand(null);
        dienst(basis, dd).waechterHinweisTakt(jetzt);
        assertEquals(1, gefragt);
        assertEquals(1, gemeldet);
        assertEquals(70, WaechterStandardHinweis.ladeMarker(basis));
    }

    @Test
    public void gangUnbekanntUndNullKmhGezeigt() throws Exception {
        File basis = tmp.newFolder("surfmate");
        File dd = tmp.newFolder("deploy");
        konfig = bestand(null);
        gangP = null;
        kmh = 0;
        dienst(basis, dd).waechterHinweisTakt(jetzt);
        assertEquals(1, gefragt);
    }

    @Test
    public void unbekanntOderVeraltetNichtGezeigt() throws Exception {
        File basis = tmp.newFolder("surfmate");
        File dd = tmp.newFolder("deploy");
        konfig = bestand(null);
        AppUpdateDienst d = dienst(basis, dd);
        // Zustand unbekannt/veraltet (DrivingSafetyGuard), obwohl P und 0 km/h gemeldet werden
        fahrsperre = "state_unknown";
        d.waechterHinweisTakt(jetzt);
        fahrsperre = "gear_unknown";
        d.waechterHinweisTakt(jetzt + 2 * AppUpdateDienst.MELDUNG_TAKT_MS);
        fahrsperre = "speed_unknown";
        d.waechterHinweisTakt(jetzt + 4 * AppUpdateDienst.MELDUNG_TAKT_MS);
        // keine Sperre, aber Gang und Tempo unbekannt
        fahrsperre = null;
        gangP = null;
        kmh = Double.NaN;
        d.waechterHinweisTakt(jetzt + 6 * AppUpdateDienst.MELDUNG_TAKT_MS);
        assertEquals(0, gefragt);
        assertEquals(0, gemeldet);
        assertEquals(0, WaechterStandardHinweis.ladeMarker(basis));
    }

    @Test
    public void ohneDisplayQuittungMarkerOffenErstNachDreiVersuchenNurFern() throws Exception {
        File basis = tmp.newFolder("surfmate");
        File dd = tmp.newFolder("deploy");
        konfig = bestand(null);
        displayQuittiert = false;
        AppUpdateDienst d = dienst(basis, dd);
        d.waechterHinweisTakt(jetzt);
        d.waechterHinweisTakt(jetzt + 2 * AppUpdateDienst.MELDUNG_TAKT_MS);
        assertEquals(0, gemeldet);
        assertEquals("nicht gezeigt = nicht erledigt", 0, WaechterStandardHinweis.ladeMarker(basis));
        d.waechterHinweisTakt(jetzt + 4 * AppUpdateDienst.MELDUNG_TAKT_MS);
        assertEquals("nach " + AppUpdateDienst.WAECHTER_FRAGE_VERSUCHE + " Versuchen nur Telegram/Handy", 1, gemeldet);
        assertEquals(70, WaechterStandardHinweis.ladeMarker(basis));
        d.waechterHinweisTakt(jetzt + 6 * AppUpdateDienst.MELDUNG_TAKT_MS);
        assertEquals("kein Nerven danach", 1, gemeldet);
    }

    @Test
    public void standregelFailClosed() {
        assertTrue(WaechterStandardHinweis.standFuerFrage(null, Boolean.TRUE, 0));
        assertTrue(WaechterStandardHinweis.standFuerFrage(null, Boolean.TRUE, Double.NaN));
        assertTrue(WaechterStandardHinweis.standFuerFrage(null, null, 0.3));
        assertFalse(WaechterStandardHinweis.standFuerFrage(null, null, Double.NaN));
        assertFalse(WaechterStandardHinweis.standFuerFrage(null, Boolean.FALSE, 0));
        assertFalse(WaechterStandardHinweis.standFuerFrage(null, Boolean.TRUE, 8));
        assertFalse(WaechterStandardHinweis.standFuerFrage(null, null, 8));
        assertFalse(WaechterStandardHinweis.standFuerFrage("state_unknown", Boolean.TRUE, 0));
        assertFalse(WaechterStandardHinweis.standFuerFrage("moving", Boolean.TRUE, 0));
    }
}
