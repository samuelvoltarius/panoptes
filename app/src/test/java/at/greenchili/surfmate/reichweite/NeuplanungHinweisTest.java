package at.greenchili.surfmate.reichweite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** 0.1.19: Neuplanung unterwegs im Auto anzeigen – dezent in der Navi-Seite, kein Band/Ton (A+). */
public class NeuplanungHinweisTest {

    private static PlanAbweichung.Befund befund(boolean neu) throws Exception {
        return PlanAbweichung.auswerten(new JSONObject().put("ok", true).put("neu_planen", neu).put("abweichung_pp", -6)
                .put("gruende", new JSONArray().put("6 Prozentpunkte unter Plan"))
                .put("neu_plan_anfrage", new JSONObject().put("start", new JSONArray().put(48.4).put(14.835))
                        .put("ziel", new JSONArray().put(48.2).put(16.37))
                        .put("via", new JSONArray().put(new JSONObject().put("name", "Linz").put("lat", 48.3).put("lon", 14.3)))));
    }

    private static JSONObject ziel() throws Exception {
        return new JSONObject().put("name", "Wien").put("lat", 48.2).put("lon", 16.37);
    }

    @Test
    public void hinweisNurBeiNeuPlanenUndOhneSperre() throws Exception {
        JSONObject h = Neuplanung.hinweis(befund(true), true, ziel());
        assertNotNull(h);
        assertEquals("Wien", h.getJSONObject("ziel").getString("name"));
        assertEquals(16.37, h.getJSONObject("ziel").getDouble("lon"), 0);
        assertEquals("Linz", h.getJSONArray("via").getJSONObject(0).getString("name"));
        assertEquals("6 Prozentpunkte unter Plan", h.getJSONArray("gruende").getString(0));
        assertNull("im Plan", Neuplanung.hinweis(befund(false), true, ziel()));
        assertNull("nach „Nicht jetzt“ gesperrt", Neuplanung.hinweis(befund(true), false, ziel()));
        assertNull("ohne Ziel", Neuplanung.hinweis(befund(true), true, new JSONObject().put("name", "x")));
        assertNull(Neuplanung.hinweis(null, true, ziel()));
        assertNull("Planer nicht erreichbar", Neuplanung.hinweis(PlanAbweichung.auswerten(null), true, ziel()));
    }

    @Test
    public void vorschlagAusDerAbweichungNieImBand() {
        Neuplanung.Vorschlag v = new Neuplanung.Vorschlag();
        v.quelle = Neuplanung.QUELLE_ABWEICHUNG;
        assertNull(Neuplanung.fuersBand(v));
        Neuplanung.Vorschlag lokal = new Neuplanung.Vorschlag();
        assertSame(lokal, Neuplanung.fuersBand(lokal));
        assertNull(Neuplanung.fuersBand(null));
    }

    @Test
    public void naviSeiteZeigtHinweisMitKnopfOhneTonUndOhneOverlay() throws Exception {
        String navi = lies("app/src/main/assets/web/local/navi.html");
        int a = navi.indexOf("neu: function (h)");
        int e = navi.indexOf("route: function ()");
        assertTrue(a > 0 && e > a);
        String teil = navi.substring(a, navi.indexOf("N.choose(", a) + 200);
        assertTrue(teil.contains("data-uw=\"neu\""));
        assertTrue(teil.contains("/api/navi/unterwegs/ablehnen") || navi.contains("post('/api/navi/unterwegs/ablehnen'"));
        assertFalse("kein Ton", teil.contains("speak") || teil.contains("Audio"));
        assertTrue("Zwischenziele gehen mit", navi.contains("if (dest.via && dest.via.length) body.via = dest.via;"));
        String dienst = lies("app/src/main/java/at/greenchili/surfmate/reichweite/RangeWatchDienst.java");
        assertTrue(dienst.contains("bandZeigen(now, planner, l, Neuplanung.fuersBand(vs));"));
        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject n = new JSONObject(lies("app/src/main/assets/web/i18n/" + l + ".json")).getJSONObject("navi");
            for (String k : new String[] {"uw_neu", "uw_neu_btn", "uw_neu_weg"}) assertFalse(l + " " + k, n.optString(k).isEmpty());
        }
    }

    private static String lies(String rel) throws Exception {
        Path p = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (p != null) {
            for (Path c : new Path[] {p.resolve(rel), p.resolve(rel.replaceFirst("^app/", ""))}) {
                if (Files.isRegularFile(c)) return new String(Files.readAllBytes(c), "UTF-8");
            }
            p = p.getParent();
        }
        throw new java.io.FileNotFoundException(rel);
    }
}
