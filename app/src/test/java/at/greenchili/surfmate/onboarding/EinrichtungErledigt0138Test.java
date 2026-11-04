package at.greenchili.surfmate.onboarding;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.server.EinrichtungApiHandler;
import at.greenchili.surfmate.server.EinrichtungApiHandler.Antwort;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Map;
import java.util.Set;

/**
 * 0.1.38.8 (Projektinhaber zur Liste „Noch einrichten": „vieles davon hab ich doch schon?"):
 * <ul>
 *   <li>Echte „erledigt"-Erkennung aus dem Config-Zustand für die prüfbaren neuen Schritte
 *       (extra_tacho, extra_abschaltbericht, extra_ladesaeule, extra_overdrive-Übernahme) → fällt aus „Noch einrichten".</li>
 *   <li>Was sich nicht prüfen lässt: „Erledigt / nicht nötig" ({@code POST /api/einrichtung/erledigt}) blendet es dauerhaft aus.</li>
 * </ul>
 */
public class EinrichtungErledigt0138Test {

    private static JSONObject anwenden(JSONObject root, Antwort a) throws Exception {
        assertEquals(a.body.toString(), 200, a.code);
        JSONObject r = new JSONObject(root.toString());
        for (Map.Entry<String, Map<String, Object>> s : a.schreiben.entrySet()) {
            JSONObject sec = r.optJSONObject(s.getKey());
            if (sec == null) { sec = new JSONObject(); r.put(s.getKey(), sec); }
            for (Map.Entry<String, Object> kv : s.getValue().entrySet()) sec.put(kv.getKey(), kv.getValue());
        }
        return r;
    }

    private static String status(JSONObject sicht, String id) throws Exception {
        JSONArray s = sicht.getJSONArray("schritte");
        for (int i = 0; i < s.length(); i++) if (id.equals(s.getJSONObject(i).getString("id"))) return s.getJSONObject(i).getString("status");
        throw new AssertionError(id);
    }

    private static JSONObject sicht(JSONObject root) throws Exception {
        return EinrichtungApiHandler.sicht(root, EinrichtungApiHandler.zustand(root), null, null);
    }

    private static Set<String> echt(JSONObject root) {
        return EinrichtungApiHandler.echtEingerichtet(root, null, false);
    }

    @Test
    public void tachoErkanntAmHinweisOderFreigabe() throws Exception {
        assertFalse(echt(new JSONObject()).contains("extra_tacho"));
        assertTrue(echt(new JSONObject().put("tachoNavi", new JSONObject().put("hinweisVerstanden", 1_700_000_000_000L)))
                .contains("extra_tacho"));
        assertTrue(echt(new JSONObject().put("tachoNavi", new JSONObject().put("freigaben", new JSONArray().put("com.x"))))
                .contains("extra_tacho"));
        assertFalse("Hinweis nie bestätigt, keine Freigabe",
                echt(new JSONObject().put("tachoNavi", new JSONObject().put("hinweisVerstanden", 0))).contains("extra_tacho"));
    }

    @Test
    public void abschaltberichtErkanntWennSchalterAngefasst() throws Exception {
        assertFalse("Standard an, aber Schlüssel erst nach Eingriff",
                echt(new JSONObject().put("telegram", new JSONObject().put("ownerChatId", 5))).contains("extra_abschaltbericht"));
        assertTrue(echt(new JSONObject().put("telegram", new JSONObject().put("abschaltBericht", true))).contains("extra_abschaltbericht"));
        assertTrue("auch aus zählt als angefasst",
                echt(new JSONObject().put("telegram", new JSONObject().put("abschaltBericht", false))).contains("extra_abschaltbericht"));
    }

    @Test
    public void ladesaeuleErkanntAmSchalterOderGemerkterSaeule() throws Exception {
        assertFalse(echt(new JSONObject()).contains("extra_ladesaeule"));
        assertTrue(echt(new JSONObject().put("laden", new JSONObject().put("saeuleNachschlagen", true))).contains("extra_ladesaeule"));
        assertTrue(echt(new JSONObject().put("ladesaeule", new JSONObject().put("gemerkt", new JSONArray().put(new JSONObject()))))
                .contains("extra_ladesaeule"));
    }

    @Test
    public void overdriveUebernahmeMarkerBlendetAus() throws Exception {
        assertFalse(echt(new JSONObject()).contains("extra_overdrive"));
        assertTrue(echt(new JSONObject().put("uebernahme", new JSONObject().put("overdriveErledigt", true))).contains("extra_overdrive"));
    }

    @Test
    public void displayUndLauncherUeberDaemonHaken() throws Exception {
        // Standard (kein Daemon-Haken) → beide offen/später; gesetzter Haken → erledigt.
        java.util.function.BooleanSupplier h = EinrichtungApiHandler.hintergrundErkennung;
        java.util.function.BooleanSupplier l = EinrichtungApiHandler.launcherErkennung;
        try {
            assertEquals("spaeter", status(sicht(new JSONObject()), "extra_display"));
            assertEquals("spaeter", status(sicht(new JSONObject()), "extra_launcher"));
            EinrichtungApiHandler.hintergrundErkennung = () -> true;
            EinrichtungApiHandler.launcherErkennung = () -> true;
            JSONObject s = sicht(new JSONObject());
            assertEquals("erledigt", status(s, "extra_display"));
            assertEquals("erledigt", status(s, "extra_launcher"));
        } finally {
            EinrichtungApiHandler.hintergrundErkennung = h;
            EinrichtungApiHandler.launcherErkennung = l;
        }
    }

    @Test
    public void sichtMachtErkanntenSchrittErledigt() throws Exception {
        JSONObject root = new JSONObject().put("laden", new JSONObject().put("saeuleNachschlagen", true));
        assertEquals("erledigt", status(sicht(root), "extra_ladesaeule"));
    }

    @Test
    public void manuellErledigtBlendetDauerhaftAus() throws Exception {
        JSONObject root = new JSONObject();
        assertEquals("spaeter", status(sicht(root), "extra_launcher"));
        JSONObject r = anwenden(root, EinrichtungApiHandler.erledigt(root, "extra_launcher"));
        JSONObject s = sicht(r);
        assertEquals("erledigt", status(s, "extra_launcher"));
        assertTrue("nicht mehr unter „Noch offen“", !s.getJSONArray("offen_spaeter").toString().contains("extra_launcher"));
    }

    @Test
    public void manuellErledigtNichtFuerPflichtOderUnbekannt() throws Exception {
        assertEquals(422, EinrichtungApiHandler.erledigt(new JSONObject(), "haftung").code);
        assertEquals(422, EinrichtungApiHandler.erledigt(new JSONObject(), "fertig").code);
        assertEquals(404, EinrichtungApiHandler.erledigt(new JSONObject(), "gibts_nicht").code);
    }
}
