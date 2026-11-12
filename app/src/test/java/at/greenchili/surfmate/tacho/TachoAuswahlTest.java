package at.greenchili.surfmate.tacho;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 0.1.37 „Was zeigt der Tacho?“: eine Auswahl aus Designs, Navi-Apps und freigegebenen Apps. Keine eigene Policy:
 * Starten nur ueber {@link TachoNaviApp#setze}, „normal“ nur ueber {@link TachoNaviApp#tachoNormal()}.
 */
public class TachoAuswahlTest {

    private static JSONObject app(String paket, boolean navi, boolean frei, boolean gespeichert, boolean fahrt)
            throws Exception {
        return new JSONObject().put("paket", paket).put("name", paket).put("installiert", true)
                .put("navi", navi).put("video", false).put("freigegeben", frei)
                .put("gespeichertFreigegeben", gespeichert).put("fahrt", fahrt);
    }

    private static JSONObject status(Object aktiv, boolean angeboten, JSONObject... apps) throws Exception {
        JSONArray a = new JSONArray();
        a.put(app(TachoAnsicht.PAKET, true, angeboten, true, true).put("panoptes", true));
        for (JSONObject x : apps) a.put(x);
        return new JSONObject().put("aktiv", aktiv).put("angeboten", angeboten)
                .put("grund", angeboten ? "" : "warnleuchten_unbekannt").put("hinweisVerstanden", true).put("apps", a);
    }

    private static List<String> ids(JSONObject l) {
        List<String> r = new ArrayList<>();
        JSONArray e = l.optJSONArray("eintraege");
        for (int i = 0; i < e.length(); i++) r.add(e.optJSONObject(i).optString("id"));
        return r;
    }

    @Test
    public void designsZuerstDannNaviUndFreigegebeneApps() throws Exception {
        JSONObject l = TachoAuswahl.liste(status(JSONObject.NULL, true,
                app("com.waze", true, false, false, true),                // Navi, nicht freigegeben: sichtbar, nicht waehlbar
                app("com.google.android.youtube", false, true, true, false), // freigegeben: sichtbar
                app("org.beliebig.app", false, false, false, false)),     // weder Navi noch frei: nicht hier
                "sport");
        // 0.1.42 (T2): alle 20 Designs zuerst, dann die Apps
        List<String> erwartet = new ArrayList<>();
        for (String d : TachoAnsicht.DESIGNS) erwartet.add("design:" + d);
        erwartet.add("app:com.waze");
        erwartet.add("app:com.google.android.youtube");
        assertEquals(erwartet, ids(l));
        int n = TachoAnsicht.DESIGNS.size();
        JSONArray e = l.getJSONArray("eintraege");
        assertTrue(e.getJSONObject(0).getBoolean("waehlbar"));
        assertFalse(e.getJSONObject(n).getBoolean("waehlbar"));
        assertEquals("nicht_freigegeben", e.getJSONObject(n).getString("grund"));
        assertTrue(e.getJSONObject(n + 1).getBoolean("waehlbar"));
        assertFalse(e.getJSONObject(n + 1).getBoolean("fahrt"));
        assertEquals("normal", l.getJSONObject("jetzt").getString("id"));
        assertEquals("normal", l.getJSONObject("normal").getString("id"));
        assertFalse("Panoptes selbst nie als App", ids(l).contains("app:" + TachoAnsicht.PAKET));
    }

    @Test
    public void aktuelleWahlUndSurfOhneAngebot() throws Exception {
        JSONObject l = TachoAuswahl.liste(status(TachoAnsicht.PAKET, true), "horizont");
        assertEquals("design:horizont", l.getJSONObject("jetzt").getString("id"));
        assertTrue(l.getJSONArray("eintraege").getJSONObject(3).getBoolean("gewaehlt"));
        l = TachoAuswahl.liste(status("com.waze", true, app("com.waze", true, true, true, true)), "klar");
        assertEquals("app:com.waze", l.getJSONObject("jetzt").getString("id"));
        // Surf / DiLink 5 / unbekannt: Plattform nicht angeboten → nichts waehlbar, Grund vom Kern
        l = TachoAuswahl.liste(status(JSONObject.NULL, false, app("com.waze", true, false, true, true)), "klar");
        JSONArray e = l.getJSONArray("eintraege");
        for (int i = 0; i < e.length(); i++) {
            assertFalse(e.getJSONObject(i).getBoolean("waehlbar"));
            assertEquals("warnleuchten_unbekannt", e.getJSONObject(i).getString("grund"));
        }
        assertFalse(l.getBoolean("angeboten"));
    }

    @Test
    public void nurGueltigeIds() {
        assertArrayEquals(new String[] {"normal", ""}, TachoAuswahl.ziel("normal"));
        assertArrayEquals(new String[] {"design", "route"}, TachoAuswahl.ziel("design:Route"));
        assertArrayEquals(new String[] {"app", "com.waze"}, TachoAuswahl.ziel("app:com.waze"));
        for (String bad : new String[] {null, "", "design:", "design:rot", "app:", "app:at.greenchili.surfmate",
                "app:../x", "app:com.waze;rm", "app:at.greenchili.surfmate.x", "irgendwas", "app:com..waze"}) {
            assertNull(bad, TachoAuswahl.ziel(bad));
        }
    }

    @Test
    public void startetNurUeberDieVorhandenePolicy() throws IOException {
        String src = TachoFaehigkeitTest.lies("app/src/main/java/at/greenchili/surfmate/tacho/TachoAuswahl.java");
        assertTrue(src.contains("TachoNaviApp.setze(true, z[1])"));
        assertTrue(src.contains("TachoNaviApp.setze(true, TachoAnsicht.PAKET)"));
        assertTrue(src.contains("TachoNaviApp.tachoNormal()"));
        for (String verboten : new String[] {"ClusterCast.start", "sendInfo", "AutoContainer", "setHorn", "setTurn",
                "LightDevice", "flash", "Alarm"}) {
            assertFalse("TachoAuswahl enthaelt " + verboten, src.contains(verboten));
        }
        String web = TachoFaehigkeitTest.lies("app/src/main/assets/web/shared/tacho-auswahl.js");
        assertTrue(web.contains("'/api/tacho/auswahl'"));
        assertFalse("Web startet nichts an der Auswahl vorbei", web.contains("/api/tacho/navi'"));
    }
}
