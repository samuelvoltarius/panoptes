package at.greenchili.surfmate.navmap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.navmap.nav.SearchResult;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * 0.1.38.10 (G4): Einstieg in die Panoptes-Führung aus navi.html. Der Auftrag trägt nur Ziel und Zwischenstopps (keine
 * Route, keine Position), die Bridge legt ihn als nicht navigierende Fahrt ab, die Activity startet erst nach dem Tipp.
 * Testorte künstlich (Ursprung 10°/20°).
 */
public class FuehrungTest {

    @Test
    public void auftragMitZielUndZwischenstoppsInReihenfolge() {
        Fuehrung.Auftrag a = Fuehrung.auftragLesen("{\"plan_id\":\"p-1\",\"ziel\":{\"name\":\"Ziel\",\"lat\":10.5,\"lon\":20.5},"
                + "\"etappen\":[{\"art\":\"zwischenziel\",\"name\":\"Z1\",\"lat\":10.1,\"lon\":20.1},"
                + "{\"art\":\"laden\",\"name\":\"L1\",\"lat\":10.2,\"lon\":20.2},"
                + "{\"art\":\"zwischenziel\",\"name\":\"Z2\",\"lat\":10.3,\"lng\":20.3}]}");
        assertNotNull(a);
        assertEquals(3, a.getStopps().size());
        assertEquals("Z1", a.getStopps().get(0).getLabel());
        assertEquals("lng statt lon geht auch", 20.3, a.getStopps().get(1).getLng(), 1e-9);
        assertEquals("Ziel zuletzt", "Ziel", a.getStopps().get(2).getLabel());
        assertEquals("p-1", a.getPlanId());
    }

    @Test
    public void ohneGueltigesZielKeinAuftrag() {
        assertNull(Fuehrung.auftragLesen(null));
        assertNull(Fuehrung.auftragLesen(""));
        assertNull(Fuehrung.auftragLesen("kein json"));
        assertNull(Fuehrung.auftragLesen("{\"etappen\":[]}"));
        assertNull(Fuehrung.auftragLesen("{\"ziel\":{\"name\":\"x\",\"lat\":95,\"lon\":20}}"));
        Fuehrung.Auftrag a = Fuehrung.auftragLesen("{\"ziel\":{\"lat\":10,\"lon\":20},\"etappen\":[{\"name\":\"kaputt\"}]}");
        assertNotNull(a);
        assertEquals("kaputte Etappe fällt weg", 1, a.getStopps().size());
        assertEquals("ohne Namen die Koordinate", "10.00000, 20.00000", a.getStopps().get(0).getLabel());
        assertNull(a.getPlanId());
    }

    private static String mitZwischen(int n) {
        StringBuilder et = new StringBuilder();
        for (int i = 0; i < n; i++) et.append(i == 0 ? "" : ",").append("{\"name\":\"Z").append(i).append("\",\"lat\":10,\"lon\":20}");
        return "{\"ziel\":{\"name\":\"Z\",\"lat\":11,\"lon\":21},\"etappen\":[" + et + "]}";
    }

    @Test
    public void hoechstensFuenfZwischenstoppsSonstUngueltig() {
        assertEquals("= ValhallaRouteClient.MAX_VIA (G3), wie navi.html und Planer", 5, Fuehrung.MAX_ZWISCHEN);
        Fuehrung.Auftrag a = Fuehrung.auftragLesen(mitZwischen(5));
        assertNotNull(a);
        assertEquals("5 Zwischenstopps + Ziel", 6, a.getStopps().size());
        assertNull("nicht still kürzen", Fuehrung.auftragLesen(mitZwischen(6)));
    }

    @Test
    public void verfuegbarOhneSchluesselUndOhneNetz() throws Exception {
        assertTrue(Fuehrung.verfuegbar());
        assertFalse("kein NavMapConfig (in G3 entfernt)", lies("src/main/java/at/greenchili/surfmate/navmap/Fuehrung.kt").contains("NavMapConfig"));
    }

    @Test
    public void standOhneKoordinaten() throws Exception {
        JSONObject leer = Fuehrung.standJson(null, true);
        assertTrue(leer.getBoolean("verfuegbar"));
        assertFalse(leer.getBoolean("aktiv"));
        JSONObject vorschau = Fuehrung.standJson(new NavTripStore.Trip(Arrays.asList(new SearchResult("Z", 10, 20)), false), false);
        assertFalse("nur geplant, nicht geführt", vorschau.getBoolean("aktiv"));
        JSONObject laeuft = Fuehrung.standJson(new NavTripStore.Trip(Arrays.asList(new SearchResult("A", 10, 20), new SearchResult("Ziel", 11, 21)), true), true);
        assertTrue(laeuft.getBoolean("aktiv"));
        assertEquals("Ziel", laeuft.getString("ziel_name"));
        assertEquals(2, laeuft.getInt("stopps"));
        assertFalse("keine Position im Stand", laeuft.toString().contains("lat") || laeuft.toString().contains("20.0"));
    }

    @Test
    public void bridgeLegtAbUndStartetNurDieVorschau() throws Exception {
        String wv = lies("src/main/java/at/greenchili/surfmate/ui/fragment/WebViewFragment.kt");
        int a = wv.indexOf("fun openNavigation(json: String?): String {");
        assertTrue(a > 0);
        String teil = wv.substring(a, wv.indexOf("fun fuehrungStand()", a));
        assertTrue(teil.contains("if (!F.verfuegbar()) return \"nicht_verfuegbar\""));
        assertTrue(teil.contains("F.ablegen(act.applicationContext, auftrag)"));
        assertTrue(teil.contains(".putExtra(F.EXTRA_NEU, auftrag != null)"));
        assertTrue("zurück zur laufenden Führung ohne neuen Auftrag", teil.contains("if (json.isNullOrBlank()) null"));
        assertTrue(wv.contains("fun fuehrungStand(): String {"));
        assertTrue(wv.contains("fun fuehrungBeenden(): String {"));
        String kt = lies("src/main/java/at/greenchili/surfmate/navmap/Fuehrung.kt");
        assertTrue("nicht navigierend ablegen: geführt wird erst nach dem Tipp", kt.contains("NavTripStore.save(context, a.stopps, false)"));
        String act = lies("src/main/java/at/greenchili/surfmate/navmap/RoadSenseMapActivity.kt");
        int n = act.indexOf("override fun onNewIntent(intent: android.content.Intent) {");
        assertTrue(n > 0);
        String hk = act.substring(n, act.indexOf("restoreTripIfAny()", n));
        assertTrue("erst lesen, dann laufende Führung beenden (stopGuidance leert den Store)",
                hk.indexOf("NavTripStore.load(applicationContext)") < hk.indexOf("if (navigating) stopGuidance()"));
        assertTrue(hk.contains("NavTripStore.save(applicationContext, neu.stops, false)"));
    }

    private static String lies(String pfad) throws Exception {
        File b = new File("src/main").isDirectory() ? new File(".") : new File("app");
        return new String(Files.readAllBytes(new File(b, pfad).toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
