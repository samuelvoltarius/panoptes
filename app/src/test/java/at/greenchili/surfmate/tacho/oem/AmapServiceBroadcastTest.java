package at.greenchili.surfmate.tacho.oem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;

import at.greenchili.surfmate.tacho.TachoFaehigkeit;

/** 0.1.44 (N2a): Vertrag des AmapService-Broadcast-Ausgangs fuer die BYD-Kombi-Navikarte. */
public class AmapServiceBroadcastTest {

    @Test
    public void fuehrungHatGenauDieVertragsfelder() {
        Map<String, Object> m = AmapServiceBroadcast.fuehrung(11, 350, 12_345, 900, 0, true, Locale.GERMAN);
        assertEquals(Arrays.asList("KEY_TYPE", "TYPE", "IS_BYD_MAP", "NEW_ICON", "SEG_REMAIN_DIS_AUTO",
                "ROUTE_REMAIN_DIS_AUTO", "ROUTE_REMAIN_TIME_AUTO", "ROUNG_ABOUT_NUM"), new ArrayList<>(m.keySet()));
        assertEquals(10001, m.get("KEY_TYPE"));
        assertEquals("TYPE 0 = echte Fuehrung, sonst verwirft AmapService", 0, m.get("TYPE"));
        assertEquals(Boolean.FALSE, m.get("IS_BYD_MAP"));
        assertEquals(3, m.get("NEW_ICON"));
        assertEquals("350 m", m.get("SEG_REMAIN_DIS_AUTO"));
        assertEquals("12,3 km", m.get("ROUTE_REMAIN_DIS_AUTO"));
        assertEquals("15 min", m.get("ROUTE_REMAIN_TIME_AUTO"));
        assertEquals(0, m.get("ROUNG_ABOUT_NUM"));

        Map<String, Object> kreis = AmapServiceBroadcast.fuehrung(26, 80, 500, 60, 3, true, Locale.ENGLISH);
        assertEquals(11, kreis.get("NEW_ICON"));
        assertEquals(3, kreis.get("ROUNG_ABOUT_NUM"));
        assertEquals("80 m", kreis.get("SEG_REMAIN_DIS_AUTO"));
        assertEquals("Ausfahrt nur im Kreisverkehr", 0,
                AmapServiceBroadcast.fuehrung(11, 80, 500, 60, 3, true, Locale.ENGLISH).get("ROUNG_ABOUT_NUM"));
    }

    @Test
    public void fremdNaviOhneRestwerte() {
        Map<String, Object> m = AmapServiceBroadcast.fuehrung(15, 1_250, 1_250, 0, 0, false, Locale.ENGLISH);
        assertFalse(m.containsKey("ROUTE_REMAIN_DIS_AUTO"));
        assertFalse(m.containsKey("ROUTE_REMAIN_TIME_AUTO"));
        assertEquals("1.3 km", m.get("SEG_REMAIN_DIS_AUTO"));
        assertEquals(2, m.get("NEW_ICON"));
    }

    @Test
    public void textNachSystemsprache() {
        assertEquals("43 m", AmapServiceBroadcast.entfernung(43, Locale.GERMAN));
        assertEquals("340 m", AmapServiceBroadcast.entfernung(347, Locale.FRENCH));
        assertEquals("1,0 km", AmapServiceBroadcast.entfernung(1_000, Locale.ITALIAN));
        assertEquals("2,5 km", AmapServiceBroadcast.entfernung(2_500, new Locale("nl")));
        assertEquals("2.5 km", AmapServiceBroadcast.entfernung(2_500, Locale.ENGLISH));
        assertEquals("120 km", AmapServiceBroadcast.entfernung(120_400, Locale.GERMAN));
        assertEquals("", AmapServiceBroadcast.entfernung(-1, Locale.GERMAN));
        assertEquals("1 min", AmapServiceBroadcast.dauer(1));
        assertEquals("0 min", AmapServiceBroadcast.dauer(0));
        assertEquals("59 min", AmapServiceBroadcast.dauer(59 * 60));
        assertEquals("1 h 05 min", AmapServiceBroadcast.dauer(65 * 60));
    }

    @Test
    public void endeMeldung() {
        Map<String, Object> e = AmapServiceBroadcast.ende();
        assertEquals(2, e.size());
        assertEquals(10019, e.get("KEY_TYPE"));
        assertEquals(9, e.get("EXTRA_STATE"));
    }

    @Test
    public void gaodeSymbolTabelle() {
        int[][] tabelle = {
                {15, 2}, {11, 3}, {10, 4}, {19, 4}, {21, 4}, {24, 4}, {38, 4},
                {9, 5}, {18, 5}, {20, 5}, {23, 5}, {37, 5},
                {16, 6}, {12, 7}, {14, 8}, {13, 19}, {26, 11}, {27, 12},
                {4, 15}, {5, 15}, {6, 15}, {8, 9}, {17, 9}, {22, 9}, {25, 9}, {0, 9}, {999, 9}, {-1, 9}};
        for (int[] z : tabelle) assertEquals("Valhalla " + z[0], z[1], AmapServiceBroadcast.gaodeSymbol(z[0]));
        for (int v = -2; v < 60; v++) assertTrue("nie 0", AmapServiceBroadcast.gaodeSymbol(v) > 0);
    }

    @Test
    public void paketZielGezieltUndNurBekannte() throws Exception {
        assertEquals("com.example.amapservice", AmapServiceBroadcast.erkennePaket(p -> true));
        assertEquals("com.byd.amapservice", AmapServiceBroadcast.erkennePaket(p -> p.equals("com.byd.amapservice")));
        assertNull(AmapServiceBroadcast.erkennePaket(p -> false));
        assertNull(AmapServiceBroadcast.erkennePaket(p -> { throw new RuntimeException("x"); }));
        assertNull(AmapServiceBroadcast.erkennePaket(null));
        assertFalse("ohne Context nichts", AmapServiceBroadcast.androidSender(null).sende("com.example.amapservice", AmapServiceBroadcast.ende()));
        String q = read("app/src/main/java/at/greenchili/surfmate/tacho/oem/AmapServiceBroadcast.java");
        assertTrue(q.contains("i.setPackage(paket);"));
        assertTrue(q.contains("if (!PAKET_DILINK3.equals(paket) && !PAKET_DILINK5.equals(paket)) return false;"));
        assertTrue(q.contains("new android.content.Intent(AKTION)"));
        assertEquals("AUTONAVI_STANDARD_BROADCAST_SEND", AmapServiceBroadcast.AKTION);
    }

    @Test
    public void sperreSurfDiLink5UnbekanntUndSchalter() {
        String pkg = AmapServiceBroadcast.PAKET_DILINK3;
        assertEquals(OemNavigationBridge.AUSGANG_AMAP, OemNavigationBridge.ausgangFuerTest(dualOs(), pkg, false));
        assertEquals("ohne AmapService wie bisher AutoContainer",
                OemNavigationBridge.AUSGANG_AUTOCONTAINER, OemNavigationBridge.ausgangFuerTest(dualOs(), null, false));

        TachoFaehigkeit.Eingaben surf = dualOs();
        surf.singleOs = "1";
        surf.modell = "BYD Dolphin Surf";
        assertEquals(OemNavigationBridge.AUSGANG_KEIN, OemNavigationBridge.ausgangFuerTest(surf, pkg, false));
        assertEquals("Surf auch mit Schalter nie", OemNavigationBridge.AUSGANG_KEIN, OemNavigationBridge.ausgangFuerTest(surf, pkg, true));

        TachoFaehigkeit.Eingaben unbekannt = dualOs();
        unbekannt.singleOs = "";
        assertEquals(OemNavigationBridge.AUSGANG_KEIN, OemNavigationBridge.ausgangFuerTest(unbekannt, pkg, false));

        TachoFaehigkeit.Eingaben di5 = dualOs();
        di5.dilink5Gewaehlt = true;
        String pkg5 = AmapServiceBroadcast.PAKET_DILINK5;
        assertEquals("DiLink 5 bis zur Abnahme gesperrt", OemNavigationBridge.AUSGANG_KEIN, OemNavigationBridge.ausgangFuerTest(di5, pkg5, false));
        assertEquals("nach Abnahme reicht der Schalter", OemNavigationBridge.AUSGANG_AMAP, OemNavigationBridge.ausgangFuerTest(di5, pkg5, true));
        assertEquals("DiLink 5 ohne AmapService nichts", OemNavigationBridge.AUSGANG_KEIN, OemNavigationBridge.ausgangFuerTest(di5, null, true));
        assertEquals("ab Werk aus", "kombiNaviDiLink5", AmapServiceBroadcast.SCHALTER_DILINK5);
    }

    @Test
    public void nieTeststeuerungNieStrassennameUndWaechterBleibt() throws Exception {
        String b = read("app/src/main/java/at/greenchili/surfmate/tacho/oem/AmapServiceBroadcast.java");
        String br = read("app/src/main/java/at/greenchili/surfmate/tacho/oem/OemNavigationBridge.java");
        String c = read("app/src/main/java/at/greenchili/surfmate/navmap/oem/OemNavigationClient.kt");
        for (String q : new String[] {b, br, c}) {
            for (String verboten : new String[] {"DEBUG_CASE", "KILL_", "ROAD_NAME", "\"LAT\"", "\"LON\"", "sendOrderedBroadcast"})
                assertFalse(verboten, q.contains(verboten));
        }
        assertTrue(br.contains("STALE_MS = 12_000L"));
        String stop = br.substring(br.indexOf("private static void stopInternal("));
        assertTrue("Ende-Meldung bei Stopp/Waechter/Schalter aus", stop.contains("AmapServiceBroadcast.ende()"));
        String tick = br.substring(br.indexOf("private static void startTick()"), br.indexOf("private static int clamp("));
        assertTrue(tick.contains("stopInternal(\"stale\")"));
        assertTrue(tick.contains("stopInternal(\"disabled\")"));
        assertTrue(br.contains("optBoolean(\"oemTurnGuidance\", false)"));
        assertTrue(c.contains(".put(\"ohneRest\", true)"));
        assertTrue(c.contains("nav.optBoolean(\"externalNotificationGuidance\", false)"));
    }

    @Test
    public void hinweisEinfachInFuenfSprachen() throws Exception {
        String html = read("app/src/main/assets/web/local/road-sense.html");
        assertTrue(html.contains("data-i18n=\"road_sense.oem_turn_guidance_einfach\""));
        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            org.json.JSONObject rs = new org.json.JSONObject(read("app/src/main/assets/web/i18n/" + l + ".json"))
                    .getJSONObject("road_sense");
            assertTrue(l, rs.getString("oem_turn_guidance_einfach").length() > 20);
        }
        assertTrue(read("docs/OEM_NAVIGATIONSPFEILE.md").contains("Abnahme durch Community-Tester"));
    }

    private static TachoFaehigkeit.Eingaben dualOs() {
        TachoFaehigkeit.Eingaben e = new TachoFaehigkeit.Eingaben();
        e.singleOs = "0";
        e.sdk = 29;
        e.dienste = "0\tAutoContainer: [android.os.IAutoContainer]\n";
        e.uid = "2000";
        return e;
    }

    private static String read(String relative) throws Exception {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            current = at.greenchili.surfmate.testutil.RepoWurzel.hoch(current);
        }
        throw new IllegalStateException("missing " + relative);
    }
}
