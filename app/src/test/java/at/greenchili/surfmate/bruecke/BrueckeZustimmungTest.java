package at.greenchili.surfmate.bruecke;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.server.WebHandyGuardTest;

import org.json.JSONObject;
import org.junit.Test;

/** 0.1.24 Zustimmung zur Brücke (28.10.): Häkchen vor dem Einschalten, Zeit + Textversion, zurücknehmbar. */
public class BrueckeZustimmungTest {

    static final class Speicher implements Zustimmung.Platte {
        JSONObject z;
        public JSONObject lade() { return z; }
        public boolean speichere(JSONObject neu) { z = neu; return true; }
        public boolean loesche() { z = null; return true; }
    }

    @Test
    public void setzenLesenZuruecknehmen() throws Exception {
        Speicher sp = new Speicher();
        Zustimmung z = new Zustimmung(sp);
        assertFalse(z.erteilt());
        assertNull(z.lese());
        assertTrue(z.setzen(1_800_000_000_000L, "einrichtung"));
        assertTrue(z.erteilt());
        assertEquals(1_800_000_000_000L, z.lese().getLong("zeit"));
        assertEquals(Zustimmung.TEXT_VERSION, z.lese().getInt("version"));
        assertEquals("einrichtung", z.lese().getString("quelle"));
        assertTrue("Alfreds Wort über die Koordination", z.setzen(1L, "koordination 28.10."));
        assertEquals("koordination 28.10.", z.lese().getString("quelle"));
        assertFalse("Quelle ohne Sonderzeichen", z.setzen(1L, "<script>"));
        assertFalse(z.setzen(1L, ""));
        z.zuruecknehmen();
        assertFalse(z.erteilt());
    }

    @Test
    public void neuerSatzVerlangtNeueZustimmung() throws Exception {
        Speicher sp = new Speicher();
        sp.z = new JSONObject().put("zeit", 5L).put("version", Zustimmung.TEXT_VERSION - 1).put("quelle", "web");
        Zustimmung z = new Zustimmung(sp);
        assertFalse(z.erteilt());
        JSONObject st = new JSONObject();
        z.inStatus(st, true);
        assertTrue("Bestand mit Brücke an: nachfragen", st.getBoolean("zustimmungFehlt"));
        assertEquals(Zustimmung.TEXT_VERSION, st.getInt("zustimmungVersion"));
        z.inStatus(st, false);
        assertFalse("Brücke aus: nichts nachfragen", st.getBoolean("zustimmungFehlt"));
    }

    @Test
    public void bestandWirdNachgefragtNichtAbgeschaltet() throws Exception {
        java.lang.reflect.Method m = at.greenchili.surfmate.server.EinrichtungApiHandler.class
                .getDeclaredMethod("brueckeZustimmungOffen", boolean.class, boolean.class);
        m.setAccessible(true);
        JSONObject e = (JSONObject) m.invoke(null, true, false);
        assertEquals("bruecke_zustimmung", e.getString("id"));
        assertEquals("system.html#kopplung", e.getString("ziel"));
        assertNull(m.invoke(null, true, true));
        assertNull(m.invoke(null, false, false));
        String h = WebHandyGuardTest.quelle("java/at/greenchili/surfmate/server/BrueckeApiHandler.java");
        assertTrue("ohne Häkchen 409", h.contains("r.put(\"grund\", \"zustimmung\")") && h.contains("sendJson(out, 409"));
        assertTrue("aus = Zustimmung weg", h.contains("e = b.ausschalten();\n                zs.zuruecknehmen();")
                || h.contains("e = b.ausschalten();\r\n                zs.zuruecknehmen();"));
        int i = h.indexOf("p.equals(\"/api/bruecke/zustimmung\")) {");
        assertTrue("Nachreichen schaltet nichts ab", i > 0 && !h.substring(i, h.indexOf("return true;", i)).contains("ausschalten"));
        assertTrue("nur Login/lokal", h.contains("if (AuthMiddleware.isExternRequest())"));
    }

    @Test
    public void hakenAmDisplayUndImWeb() throws Exception {
        String kt = WebHandyGuardTest.quelle("java/at/greenchili/surfmate/onboarding/schritte/WeitereSchritte.kt");
        assertTrue("nie vorausgewählt", kt.contains("isChecked = false\n                a.grosserHaken(this)")
                || kt.contains("isChecked = false\r\n                a.grosserHaken(this)"));
        assertTrue("Knopf erst mit Haken", kt.contains("knopf.isEnabled = false") && kt.contains("knopf.isEnabled = an"));
        assertTrue(kt.contains("put(\"zustimmung\", true).put(\"quelle\", \"einrichtung\")"));
        String html = WebHandyGuardTest.quelle("assets/web/local/system.html");
        assertTrue(html.contains("id=\"brueckeZustimmung\""));
        assertTrue("Link Datenschutz 8b", html.contains("https://byd.serveone.at/datenschutz#bruecke") && html.contains("https://byd.serveone.at/en/datenschutz#bruecke"));
        assertTrue(WebHandyGuardTest.quelle("res/values-de/strings_einrichtung.xml").contains("byd.serveone.at/datenschutz</string>"));
        assertTrue(html.contains("{ ein: true, zustimmung: z.checked, quelle: 'web' }"));
        for (String l : new String[]{"", "-de", "-fr", "-it", "-nl"}) {
            String x = WebHandyGuardTest.quelle("res/values" + l + "/strings_einrichtung.xml");
            assertTrue(l, x.contains("name=\"einr_handy_zustimmung\""));
        }
        String de = WebHandyGuardTest.quelle("res/values-de/strings_einrichtung.xml");
        assertTrue(de.contains("Ich möchte mein Handy übers Internet verbinden. Dafür läuft verschlüsselter Verkehr über "
                + "unseren Server in Deutschland. Der Server sieht keine Fahrzeugdaten."));
    }
}
