package at.greenchili.surfmate.onboarding;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.server.EinrichtungApiHandler;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

/** 0.1.24: „Handy verbinden“ über die Brücke ist der Standardweg (ein PC-Browser kann die Brücke nicht, Kern 10); Tailscale-Schritte „Für Fortgeschrittene“ am Ende. */
public class EinrichtungHandyOhneTailscaleTest {

    @Test
    public void reihenfolgeHandyVorneTailscaleHinten() {
        List<String> s = EinrichtungApiHandler.SCHRITTE;
        int handy = s.indexOf("extra_handy");
        assertEquals("gleich nach Telegram", s.indexOf("extra_telegram") + 1, handy);
        for (String extra : new String[]{"extra_planer", "extra_cockpit", "extra_overdrive", "extra_gewohnheiten"}) {
            assertTrue(extra, s.indexOf(extra) < s.indexOf("extra_fernzugang"));
        }
        assertEquals("Tailscale-Schritte direkt vor dem Abschluss",
                s.subList(s.size() - 3, s.size()).toString(), "[extra_fernzugang, extra_notzugang, fertig]");
        assertFalse("nie Pflicht", EinrichtungApiHandler.PFLICHT.contains("extra_handy"));
    }

    @Test
    public void gruppeFortgeschrittenNurBeiTailscaleSchritten() throws Exception {
        JSONObject z = EinrichtungApiHandler.zustand(new JSONObject());
        JSONArray liste = EinrichtungApiHandler.sicht(new JSONObject(), z, null, null).getJSONArray("schritte");
        int fort = 0;
        for (int i = 0; i < liste.length(); i++) {
            JSONObject e = liste.getJSONObject(i);
            boolean tailscale = e.getString("id").equals("extra_fernzugang") || e.getString("id").equals("extra_notzugang");
            assertEquals(e.toString(), tailscale, "fortgeschritten".equals(e.optString("gruppe")));
            if (tailscale) fort++;
        }
        assertEquals(2, fort);
    }

    @Test
    public void bestandWirdNichtWiederGeoeffnetNeuerNutzerSiehtDenSchritt() throws Exception {
        JSONObject schritte = new JSONObject();
        for (String id : EinrichtungApiHandler.SCHRITTE) if (!"extra_handy".equals(id)) schritte.put(id, "erledigt");
        JSONObject root = new JSONObject().put("einrichtung", new JSONObject().put("version", 1).put("art", "neu")
                .put("schritte", schritte));
        JSONObject z = EinrichtungApiHandler.zustand(root);
        assertEquals("Bestand: unter „Noch offen“", "spaeter", z.getJSONObject("schritte").getString("extra_handy"));
        assertEquals("fertig", EinrichtungApiHandler.sicht(root, z, null, null).getString("aktuell"));
        assertEquals("offen", EinrichtungApiHandler.zustand(new JSONObject()).getJSONObject("schritte").getString("extra_handy"));
    }

    @Test
    public void texteSagenEhrlichWasOhneTailscaleNichtGeht() throws Exception {
        String de = at.greenchili.surfmate.server.WebHandyGuardTest.quelle("res/values-de/strings_einrichtung.xml");
        assertTrue(de.contains("name=\"einr_extra_handy_titel\""));
        assertTrue("PC nicht versprochen (Brücke nur über die App)", zeile(de, "einr_extra_handy_titel").endsWith(">Handy verbinden"));
        String handy = zeile(de, "einr_handy_zustimmung");
        // REGEL DAU (28.10.): keine Fachwörter außerhalb von „Für Fortgeschrittene“
        for (String k : new String[]{"einr_extra_handy_titel", "einr_handy_kurz", "einr_extra_telegram_text", "einr_handy_qr_knopf"}) {
            String z = zeile(de, k);
            for (String fach : new String[]{"Tailscale", "Brücke", "Noise", "Tailnet", "VPN", "IP-Adresse"}) {
                assertFalse(k + " ohne „" + fach + "“", z.contains(fach));
            }
        }
        assertTrue("übers Internet", handy.contains("übers Internet"));
        assertTrue("Telegram ohne Zusatzprogramm", zeile(de, "einr_extra_telegram_text").contains("übers Internet"));
        assertTrue(zeile(de, "einr_extra_fernzugang_text").contains("Fernwartung (adb)"));
        for (String l : new String[]{"", "-fr", "-it", "-nl"}) {
            String x = at.greenchili.surfmate.server.WebHandyGuardTest.quelle("res/values" + l + "/strings_einrichtung.xml");
            for (String k : new String[]{"einr_fortgeschritten", "einr_extra_handy_titel", "einr_handy_kurz", "einr_handy_qr_knopf"}) {
                assertTrue(l + " " + k, x.contains("name=\"" + k + "\""));
            }
        }
        String kt = at.greenchili.surfmate.server.WebHandyGuardTest.quelle(
                "java/at/greenchili/surfmate/onboarding/schritte/HandySchritt.kt");
        assertTrue("Knopf schaltet ein und zeigt den QR-Code", kt.contains("r.json.optBoolean(\"ok\", true)) qrHolen()") && kt.contains("EinrichtungApi.transport(\"POST\", \"/api/bruecke\","));
        assertTrue("Häkchen nie vorausgewählt", kt.contains("isChecked = false"));
    }

    private static String zeile(String xml, String key) {
        int i = xml.indexOf("name=\"" + key + "\"");
        return xml.substring(i, xml.indexOf("</string>", i));
    }
}
