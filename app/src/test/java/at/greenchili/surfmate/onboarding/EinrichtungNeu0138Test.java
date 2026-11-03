package at.greenchili.surfmate.onboarding;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.einstellungen.Katalog;
import at.greenchili.surfmate.server.EinrichtungApiHandler;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * 0.1.38: neue freiwillige Schritte (Display, Launcher, Tacho, Abschaltbericht, Ladesäule, Tiermodus, Assistenz).
 * Neuinstallation: nicht im Schnellstart, sondern „Noch einrichten“. Bestand (0.1.37 fertig): Assistent öffnet sich
 * nicht, die neuen Punkte stehen unter „Noch einrichten“, alte Antworten bleiben. Tacho entfällt bei Single-OS.
 */
public class EinrichtungNeu0138Test {

    private static final List<String> NEU = Arrays.asList("extra_display", "extra_launcher", "extra_tacho",
            "extra_abschaltbericht", "extra_ladesaeule", "extra_tiermodus", "extra_assistenz");

    private static String status(JSONObject sicht, String id) throws Exception {
        JSONArray s = sicht.getJSONArray("schritte");
        for (int i = 0; i < s.length(); i++) if (id.equals(s.getJSONObject(i).getString("id"))) return s.getJSONObject(i).getString("status");
        throw new AssertionError(id);
    }

    /** Ein 0.1.37-Auto: Einrichtung abgeschlossen, die neuen IDs gibt es im gespeicherten Zustand noch nicht. */
    private static JSONObject bestand0137() throws Exception {
        JSONObject schritte = new JSONObject();
        for (String id : EinrichtungApiHandler.SCHRITTE) if (!NEU.contains(id)) schritte.put(id, "erledigt");
        schritte.put("extra_telegram", "spaeter");
        return new JSONObject()
                .put("nativeShell", new JSONObject().put("locale", "de").put("localeGewaehlt", true))
                .put("hinweise", new JSONObject().put("version", Katalog.HINWEISE_VERSION))
                .put("vehicle", new JSONObject().put("modelId", "atto3").put("modelSource", "user"))
                .put("surveillance", new JSONObject().put("triggerMode", "ai_everywhere"))
                .put("einrichtung", new JSONObject().put("version", 1).put("art", "neu").put("neuZaehler", 0)
                        .put("schritte", schritte).put("gruende", new JSONObject())
                        .put("werte", new JSONObject().put("waechter", new JSONObject().put("modus", "ai_everywhere"))));
    }

    @Test
    public void neueSchritteSindFreiwilligUndVorDenFortgeschrittenen() {
        List<String> s = EinrichtungApiHandler.SCHRITTE;
        assertTrue(s.containsAll(NEU));
        for (String id : NEU) {
            assertFalse(id, EinrichtungApiHandler.PFLICHT.contains(id));
            assertFalse(id, EinrichtungApiHandler.SCHNELLSTART.contains(id));
            assertTrue(id, s.indexOf(id) < s.indexOf("extra_fernzugang"));
        }
    }

    @Test
    public void neuinstallationZeigtSieNurUnterNochEinrichten() throws Exception {
        JSONObject root = new JSONObject();
        JSONObject sicht = EinrichtungApiHandler.sicht(root, EinrichtungApiHandler.zustand(root), null, null);
        for (String id : NEU) assertEquals(id, "spaeter", status(sicht, id));
        assertFalse(NEU.contains(sicht.getString("aktuell")));
    }

    @Test
    public void bestandBleibtFertigUndBehaeltSeineAntworten() throws Exception {
        JSONObject root = bestand0137();
        JSONObject z = EinrichtungApiHandler.zustand(root);
        JSONObject sicht = EinrichtungApiHandler.sicht(root, z, null, null);
        assertTrue(sicht.getBoolean("abgeschlossen"));
        assertEquals("fertig", sicht.getString("aktuell"));
        assertEquals("erledigt", status(sicht, "fertig"));
        for (String id : NEU) assertEquals(id, "spaeter", status(sicht, id));
        assertEquals("spaeter", status(sicht, "extra_telegram"));
        assertEquals("erledigt", status(sicht, "waechter"));
        assertEquals("ai_everywhere", sicht.getJSONObject("werte").getJSONObject("waechter").getString("modus"));
    }

    @Test
    public void tachoNurBeiGeeignetenAutos() throws Exception {
        JSONObject root = new JSONObject();
        JSONObject single = EinrichtungApiHandler.sicht(root, EinrichtungApiHandler.zustand(root),
                new JSONObject().put("singleOs", true).put("cluster", false), null);
        assertEquals("entfaellt", status(single, "extra_tacho"));
        assertEquals("spaeter", status(single, "extra_launcher"));
        JSONObject dual = EinrichtungApiHandler.sicht(root, EinrichtungApiHandler.zustand(root),
                new JSONObject().put("singleOs", false).put("cluster", true), null);
        assertEquals("spaeter", status(dual, "extra_tacho"));
    }
}
