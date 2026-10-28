package at.greenchili.surfmate.onboarding;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.einstellungen.Feld;
import at.greenchili.surfmate.onboarding.schritte.EinrichtungStand;
import at.greenchili.surfmate.server.EinrichtungApiHandler;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Collections;
import java.util.Set;

/**
 * 0.1.26 (Alfreds Auto, 0.1.24.1): „Noch offen“ zeigte Berechtigungen, Daten teilen, Blitzer, Telegram, Handy, Planer,
 * EV-Cockpit und Notzugang als „später“, obwohl alles eingerichtet war. Ursache: Der Bestand bekommt neue Schritte
 * pauschal als „später“ und der Kern hat nie am echten Zustand nachgesehen. „Später“ überdeckt nie mehr Erledigtes.
 */
public class EinrichtungEchterZustandTest {

    /** Bestand wie Alfreds Auto: Modell gewählt, Hinweise bestätigt, alles Weitere eingerichtet. */
    private static JSONObject bestandEingerichtet() throws Exception {
        return new JSONObject()
                .put("hinweise", new JSONObject().put("version", "alt"))
                .put("telegram", new JSONObject().put("ownerChatId", 12345L))
                .put("kopplungen", new JSONObject().put("geraete", new JSONArray()
                        .put(new JSONObject().put("id", "a").put("art", "handy"))
                        // wie am Auto: alte EV-Cockpit-Kopplung ohne „art“
                        .put(new JSONObject().put("id", "b").put("name", "EV-Cockpit home"))))
                .put("planner", new JSONObject().put("memberToken", new String(new char[32]).replace((char) 0, (char) 0x61)))   // 32 × „a“: gültiges Format, kein Schlüssel
                .put("notzugang", new JSONObject().put("an", true))
                .put("speedcam", new JSONObject().put("enabled", true));
    }

    @Test
    public void spaeterUeberdecktNieEchtEingerichtetes() throws Exception {
        JSONObject root = bestandEingerichtet();
        JSONObject z = EinrichtungApiHandler.zustand(root);
        assertEquals("Ausgangslage: Bestand bekommt pauschal „später“", "spaeter",
                z.getJSONObject("schritte").getString("extra_telegram"));
        JSONObject sicht = EinrichtungApiHandler.sicht(root, z, null, null);
        String offen = sicht.getJSONArray("offen_spaeter").toString();
        for (String id : new String[] {"extra_telegram", "extra_handy", "extra_cockpit", "extra_planer", "extra_notzugang", "blitzer"}) {
            assertEquals(id, "erledigt", status(sicht, id));
            assertFalse(id + " nicht unter „Noch offen“", offen.contains("\"" + id + "\""));
        }
    }

    @Test
    public void ohneEchtenZustandBleibtSpaeter() throws Exception {
        JSONObject root = new JSONObject()
                .put("hinweise", new JSONObject().put("version", "alt"))
                .put("telegram", new JSONObject().put("ownerChatId", 0))
                .put("notzugang", new JSONObject().put("an", false))
                .put("speedcam", new JSONObject().put("enabled", false))
                .put("planner", new JSONObject().put("memberToken", "kurz"));
        JSONObject sicht = EinrichtungApiHandler.sicht(root, EinrichtungApiHandler.zustand(root), null, null);
        for (String id : new String[] {"extra_telegram", "extra_handy", "extra_cockpit", "extra_planer", "extra_notzugang", "blitzer", "daten", "rechte"}) {
            assertEquals(id, "spaeter", status(sicht, id));
        }
    }

    @Test
    public void nurHomeAssistantIstKeinCockpit() throws Exception {
        JSONObject root = new JSONObject().put("kopplungen", new JSONObject().put("geraete", new JSONArray()
                .put(new JSONObject().put("id", "h").put("art", "homeassistant"))));
        Set<String> e = EinrichtungApiHandler.echtEingerichtet(root, null, false);
        assertFalse(e.contains("extra_cockpit"));
        assertFalse(e.contains("extra_handy"));
    }

    @Test
    public void datenTeilenAusEingeschalteterFreigabe() throws Exception {
        Feld f = new Feld("telemetrie.teilen", Feld.Typ.BOOL);
        f.gruppe = "datenschutz";
        Feld anders = new Feld("system.x", Feld.Typ.BOOL);
        JSONObject root = new JSONObject().put("telemetrie", new JSONObject().put("teilen", true))
                .put("system", new JSONObject().put("x", true));
        assertTrue(EinrichtungApiHandler.echtEingerichtet(root, Collections.singletonList(f), false).contains("daten"));
        assertFalse("nur die Gruppe „datenschutz“ zählt",
                EinrichtungApiHandler.echtEingerichtet(root, Collections.singletonList(anders), false).contains("daten"));
        // echter Katalog, Freigaben wie an Alfreds Auto (Zählen an, Planer-Zustand senden)
        JSONObject auto = new JSONObject().put("zaehlen", new JSONObject().put("an", true));
        assertTrue(EinrichtungApiHandler.echtEingerichtet(auto,
                at.greenchili.surfmate.einstellungen.Katalog.felder(new at.greenchili.surfmate.einstellungen.Katalog.Kontext()), false).contains("daten"));
        root.getJSONObject("telemetrie").put("teilen", false);
        assertFalse(EinrichtungApiHandler.echtEingerichtet(root, Collections.singletonList(f), false).contains("daten"));
    }

    @Test
    public void nurNichtPflichtUndNieZurueck() throws Exception {
        Set<String> e = EinrichtungApiHandler.echtEingerichtet(bestandEingerichtet(), null, true);
        for (String id : e) assertFalse(id, EinrichtungApiHandler.PFLICHT.contains(id));
        // ein ausdrücklich erledigter Schritt bleibt erledigt, auch wenn der echte Zustand gerade fehlt
        JSONObject root = new JSONObject().put("einrichtung", new JSONObject().put("art", "neu")
                .put("schritte", new JSONObject().put("extra_telegram", "erledigt")));
        assertEquals("erledigt", status(EinrichtungApiHandler.sicht(root, EinrichtungApiHandler.zustand(root), null, null), "extra_telegram"));
    }

    @Test
    public void overdriveSpaeterOhneDatenEntfaellt() throws Exception {
        JSONObject root = bestandEingerichtet();
        JSONObject z = EinrichtungApiHandler.zustand(root);
        assertEquals("spaeter", z.getJSONObject("schritte").getString("extra_overdrive"));
        JSONObject sicht = EinrichtungApiHandler.sicht(root, z, null, null);
        assertEquals("entfaellt", status(sicht, "extra_overdrive"));
        assertFalse(sicht.getJSONArray("offen_spaeter").toString().contains("extra_overdrive"));
    }

    @Test
    public void appMeldetRechteNurBeiOffenOderSpaeter() throws Exception {
        JSONObject root = bestandEingerichtet();
        JSONObject sicht = EinrichtungApiHandler.sicht(root, EinrichtungApiHandler.zustand(root), null, null);
        assertTrue(EinrichtungStand.Companion.ausJson(sicht).rechteNachmelden());
        JSONObject fertig = new JSONObject().put("schritte", new JSONArray()
                .put(new JSONObject().put("id", "rechte").put("status", "erledigt")));
        assertFalse(EinrichtungStand.Companion.ausJson(fertig).rechteNachmelden());
    }

    /** Kein roher Schlüssel mehr unter „Noch offen“: jeder Schritt hat einen Titel in allen 5 Sprachen. */
    @Test
    public void jederSchrittHatEinenTitelInAllenSprachen() throws Exception {
        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject sch = new JSONObject(at.greenchili.surfmate.server.WebHandyGuardTest.quelle("assets/web/i18n/" + l + ".json"))
                    .getJSONObject("einr").getJSONObject("schritt");
            for (String id : EinrichtungApiHandler.SCHRITTE) {
                assertFalse(l + " einr.schritt." + id, sch.optString(id, "").trim().isEmpty());
            }
        }
        String web = at.greenchili.surfmate.server.WebHandyGuardTest.quelle("assets/web/shared/einrichtung-web.js");
        for (String id : EinrichtungApiHandler.SCHRITTE) {
            assertTrue("deutscher Rückfall schritt." + id, web.contains("'schritt." + id + "'"));
        }
    }

    private static String status(JSONObject sicht, String id) throws Exception {
        JSONArray l = sicht.getJSONArray("schritte");
        for (int i = 0; i < l.length(); i++) if (id.equals(l.getJSONObject(i).getString("id"))) return l.getJSONObject(i).getString("status");
        return null;
    }
}
