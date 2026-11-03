package at.greenchili.surfmate.navmap.nav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.testutil.Quelltext;

import org.junit.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Ziel aus Google-Maps-, Waze- und geo:-Links. Testpunkte liegen absichtlich im offenen Pazifik bzw. Atlantik
 * (keine echten Orte), Namen sind Platzhalter.
 */
public class ZielLinkTest {

    private static final double LAT = -12.345678, LNG = -140.25;
    private static final double EPS = 1e-6;

    private static void punkt(String link, double lat, double lng, String quelle) {
        ZielLink.Ziel z = ZielLink.lesen(link);
        assertTrue(link + " → " + z.grund, z.ok());
        assertEquals(link, lat, z.lat, EPS);
        assertEquals(link, lng, z.lng, EPS);
        assertEquals(link, quelle, z.quelle);
    }

    @Test
    public void googleLangeLinks() {
        punkt("https://www.google.com/maps/place/Testpunkt/@-12.345678,-140.25,17z/data=!3m1!4b1!4m6!3d-12.345678!4d-140.25",
                LAT, LNG, "google");
        punkt("https://maps.google.com/?q=-12.345678,-140.25", LAT, LNG, "google");
        punkt("https://maps.google.de/maps?q=-12.345678%2C-140.25", LAT, LNG, "google");
        punkt("https://www.google.at/maps/@-12.345678,-140.25,15z", LAT, LNG, "google");
        punkt("https://www.google.com/maps/dir/?api=1&destination=-12.345678,-140.25", LAT, LNG, "google");
        assertEquals("Testpunkt", ZielLink.lesen(
                "https://www.google.com/maps/place/Testpunkt/@-12.345678,-140.25,17z").name);
        // Teilen-Text: Name davor, Link dahinter
        punkt("Testpunkt\nhttps://maps.google.com/?q=-12.345678,-140.25", LAT, LNG, "google");
    }

    @Test
    public void wazeLinks() {
        punkt("https://waze.com/ul?ll=-12.345678,-140.25&navigate=yes", LAT, LNG, "waze");
        punkt("https://www.waze.com/ul?ll=-12.345678%2C-140.25&navigate=yes&zoom=17", LAT, LNG, "waze");
        punkt("https://www.waze.com/live-map/directions?to=ll.-12.345678%2C-140.25", LAT, LNG, "waze");
        // Kurzform mit Geohash: offline gelesen, Genauigkeit der Zelle
        ZielLink.Ziel g = ZielLink.lesen("https://waze.com/ul/h" + "2v1q0ctsrq");
        assertTrue(g.grund, g.ok());
        assertEquals("waze", g.quelle);
        double[] p = ZielLink.geohash("2v1q0ctsrq");
        assertEquals(p[0], g.lat, EPS);
        assertTrue(Math.abs(g.lat) <= 90 && Math.abs(g.lng) <= 180);
        assertEquals("nur Suchtext", "kein_ort", ZielLink.lesen("https://waze.com/ul?q=Testpunkt").grund);
    }

    @Test
    public void geohashStimmt() {
        // Bekannte Referenz der Geohash-Beschreibung (Kodierung), kein Ziel: ezs42 ≈ 42.6 / -5.6
        double[] p = ZielLink.geohash("ezs42");
        assertEquals(42.6, p[0], 0.03);
        assertEquals(-5.6, p[1], 0.03);
        assertNull(ZielLink.geohash("ai"));   // a und i gibt es im Geohash nicht
    }

    @Test
    public void geoUris() {
        punkt("geo:-12.345678,-140.25", LAT, LNG, "geo");
        punkt("geo:-12.345678,-140.25;u=35", LAT, LNG, "geo");
        ZielLink.Ziel z = ZielLink.lesen("geo:0,0?q=-12.345678,-140.25(Testpunkt)");
        assertTrue(z.ok());
        assertEquals(LAT, z.lat, EPS);
        assertEquals("Testpunkt", z.name);
        punkt("geo:0,0?q=-12.345678%2C-140.25", LAT, LNG, "geo");
        assertEquals("geo:0,0 ohne Punkt ist nur eine Suche", "kein_ort", ZielLink.lesen("geo:0,0?q=Testpunkt").grund);
        assertEquals("kein_ort", ZielLink.lesen("geo:95,10").grund);
        ZielLink.Ziel html = ZielLink.lesen("geo:0,0?q=-12.345678,-140.25(Test<script>)");
        assertTrue("Punkt bleibt", html.ok());
        assertTrue("Name nie mit HTML", html.name == null || !html.name.contains("<"));
        assertEquals("Test", ZielLink.sauber("Te\u0007st<>"));
    }

    @Test
    public void kurzlinksWerdenErkanntAberNichtVonSelbstAbgerufen() {
        assertEquals("kurzlink", ZielLink.lesen("https://maps.app.goo.gl/AbCdEf123").grund);
        assertEquals("kurzlink", ZielLink.lesen("https://goo.gl/maps/AbCdEf123").grund);
        assertEquals("google", ZielLink.lesen("https://maps.app.goo.gl/AbCdEf123").quelle);
    }

    @Test
    public void kurzlinkNurPerHeadUndNurLocation() {
        List<String> abgerufen = new ArrayList<>();
        ZielLink.Ziel z = ZielLink.aufloesen("https://maps.app.goo.gl/AbCdEf123", url -> {
            abgerufen.add(url);
            return "https://www.google.com/maps/place/Testpunkt/@-12.345678,-140.25,17z";
        });
        assertTrue(z.grund, z.ok());
        assertEquals(LAT, z.lat, EPS);
        assertEquals("genau ein Abruf, nur der Kurzlink selbst", 1, abgerufen.size());
        assertEquals("https://maps.app.goo.gl/AbCdEf123", abgerufen.get(0));

        // Zwei Sprünge über Kurzlink-Hosts gehen, das lange Ziel wird nie abgerufen
        abgerufen.clear();
        ZielLink.Ziel zwei = ZielLink.aufloesen("https://goo.gl/maps/X1", url -> {
            abgerufen.add(url);
            return url.contains("goo.gl/maps") ? "https://maps.app.goo.gl/Y2" : "https://maps.google.com/?q=-12.345678,-140.25";
        });
        assertTrue(zwei.ok());
        assertEquals(2, abgerufen.size());

        // Weiterleitung auf fremden Host ohne Koordinaten: nicht folgen
        abgerufen.clear();
        ZielLink.Ziel fremd = ZielLink.aufloesen("https://maps.app.goo.gl/X", url -> {
            abgerufen.add(url);
            return "https://example.org/irgendwas";
        });
        assertEquals("nicht_aufloesbar", fremd.grund);
        assertEquals(1, abgerufen.size());

        // Endlosschleife begrenzt
        abgerufen.clear();
        ZielLink.Ziel schleife = ZielLink.aufloesen("https://maps.app.goo.gl/A", url -> {
            abgerufen.add(url);
            return "https://maps.app.goo.gl/A";
        });
        assertEquals("nicht_aufloesbar", schleife.grund);
        assertEquals(ZielLink.MAX_SPRUENGE, abgerufen.size());

        // http (ohne s) wird nie abgerufen
        abgerufen.clear();
        assertEquals("nicht_aufloesbar", ZielLink.aufloesen("http://maps.app.goo.gl/A", url -> {
            abgerufen.add(url);
            return null;
        }).grund);
        assertTrue(abgerufen.isEmpty());

        // Ein langer Link braucht keinen Abruf
        abgerufen.clear();
        assertTrue(ZielLink.aufloesen("https://maps.google.com/?q=-12.345678,-140.25", url -> {
            abgerufen.add(url);
            return null;
        }).ok());
        assertTrue(abgerufen.isEmpty());
    }

    @Test
    public void fremdeUndKaputteLinks() {
        assertEquals("unbekannter_link", ZielLink.lesen("https://example.org/?q=-12.3,-140.2").grund);
        assertEquals("unbekannter_link", ZielLink.lesen("https://www.google.com/search?q=-12.3,-140.2").grund);
        assertEquals("unbekannter_link", ZielLink.lesen("Testpunkt").grund);
        assertEquals("leer", ZielLink.lesen("  ").grund);
        assertEquals("leer", ZielLink.lesen(null).grund);
        assertEquals("kein_ort", ZielLink.lesen("https://maps.google.com/?q=Testpunkt").grund);
        StringBuilder lang = new StringBuilder("https://maps.google.com/?q=");
        while (lang.length() < 3000) lang.append('x');
        assertEquals("unbekannter_link", ZielLink.lesen(lang.toString()).grund);
    }

    @Test
    public void headOhneWeiterleitungUndOhneFremddaten() throws Exception {
        String s = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/navmap/nav/ZielLink.java"));
        assertTrue(s.contains("setRequestMethod(\"HEAD\")"));
        assertTrue(s.contains("setInstanceFollowRedirects(false)"));
        assertFalse("keine Sprache", s.contains("Accept-Language"));
        assertFalse("kein Referer", s.contains("setRequestProperty(\"Referer\""));
        assertFalse("kein Cookie", s.contains("setRequestProperty(\"Cookie\""));
        assertTrue("nur zwei Köpfe", s.split("setRequestProperty\\(", -1).length - 1 == 2);
        String n = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/NaviApiHandler.java"));
        int a = n.indexOf("/api/navi/link");
        assertTrue(a > 0);
        assertFalse("Link nie loggen", n.substring(a, a + 900).contains("logger."));
        // Web: Link wird im Auto gelesen, nie als Suchtext an den Planer; Kurzlink erst nach „Nachfragen“
        String w = Quelltext.lies(Paths.get("src/main/assets/web/local/navi.html"));
        assertTrue(w.contains("if (self.istLink(q)) { self.searchGen++; self.linkZiel(q, false); return; }"));
        assertTrue(w.contains("post('/api/navi/link', { link: q, aufloesen: !!aufloesen })"));
        assertTrue(w.contains("self.linkZiel(q, true)"));
    }

    @Test
    public void antwortDesEndpunkts() throws Exception {
        org.json.JSONObject ok = at.greenchili.surfmate.server.NaviApiHandler.linkAntwort(
                "https://maps.google.com/?q=-12.345678,-140.25", false, url -> null);
        assertTrue(ok.getBoolean("ok"));
        assertEquals(LAT, ok.getDouble("lat"), EPS);
        org.json.JSONObject kurz = at.greenchili.surfmate.server.NaviApiHandler.linkAntwort(
                "https://maps.app.goo.gl/X", false, url -> { throw new AssertionError("ohne Wunsch kein Abruf"); });
        assertEquals("kurzlink", kurz.getString("grund"));
    }
}
