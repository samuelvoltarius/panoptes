package at.greenchili.surfmate.onboarding;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.einstellungen.Feld;
import at.greenchili.surfmate.einstellungen.Katalog;
import at.greenchili.surfmate.onboarding.schritte.EinrichtungStand;
import at.greenchili.surfmate.server.EinrichtungApiHandler;
import at.greenchili.surfmate.server.EinrichtungApiHandler.Antwort;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * 0.1.38.3 („Wizard und Kamera muss jederzeit auch danach wieder einstellbar sein“): Einrichtung noch einmal
 * durchgehen bzw. einen Schritt wieder öffnen, ohne dass Einstellungen verloren gehen; jeder beantwortete Schritt
 * überspringbar; „Später“ stellt den alten Stand her; „fertig“ räumt auf.
 */
public class EinrichtungWiederholenTest {

    private static final List<Feld> FELDER = Katalog.felder(new Katalog.Kontext());

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

    private static JSONObject sicht(JSONObject root) throws Exception {
        return EinrichtungApiHandler.sicht(root, EinrichtungApiHandler.zustand(root), null, null);
    }

    private static JSONObject eintrag(JSONObject sicht, String id) throws Exception {
        JSONArray s = sicht.getJSONArray("schritte");
        for (int i = 0; i < s.length(); i++) if (id.equals(s.getJSONObject(i).getString("id"))) return s.getJSONObject(i);
        throw new AssertionError(id);
    }

    /** Fertig eingerichtetes Auto (Antworten gespeichert), Telegram „später“. */
    private static JSONObject fertig() throws Exception {
        JSONObject schritte = new JSONObject();
        for (String id : EinrichtungApiHandler.SCHRITTE) schritte.put(id, "erledigt");
        schritte.put("extra_telegram", "spaeter").put("extra_overdrive", "entfaellt");
        return new JSONObject()
                .put("nativeShell", new JSONObject().put("locale", "de").put("localeGewaehlt", true))
                .put("hinweise", new JSONObject().put("version", Katalog.HINWEISE_VERSION))
                .put("surveillance", new JSONObject().put("triggerMode", "ai_private"))
                .put("karte", new JSONObject().put("quelle", "osm"))
                .put("einrichtung", new JSONObject().put("version", 1).put("art", "neu").put("neuZaehler", 2)
                        .put("schritte", schritte).put("gruende", new JSONObject())
                        .put("werte", new JSONObject()
                                .put("waechter", new JSONObject().put("modus", "ai_private").put("ton", false))
                                .put("karte", new JSONObject().put("quelle", "osm"))));
    }

    @Test
    public void durchgehenOeffnetAllesUndNichtsIstPflicht() throws Exception {
        JSONObject root0 = fertig();
        Antwort a = EinrichtungApiHandler.wiederholen(root0, null);
        assertEquals("nur der Einrichtungs-Zustand wird geschrieben, keine Einstellung", 1, a.schreiben.size());
        JSONObject root = anwenden(root0, a);
        assertEquals(3, root.getJSONObject("einrichtung").getInt("neuZaehler"));
        JSONObject s = sicht(root);
        assertTrue(s.getBoolean("wiederholung"));
        assertTrue("Pflicht beantwortet → Assistent gilt als abgeschlossen", s.getBoolean("abgeschlossen"));
        assertEquals("sprache", s.getString("aktuell"));
        for (String id : new String[] {"sprache", "haftung", "waechter", "updates", "kameras", "karte", "extra_telegram", "extra_display"}) {
            JSONObject e = eintrag(s, id);
            assertEquals(id, "offen", e.getString("status"));
            assertTrue(id + " im Ablauf", e.getBoolean("schnellstart"));
        }
        assertFalse("beantwortete Pflicht ist überspringbar", eintrag(s, "waechter").getBoolean("pflicht"));
        assertEquals("USB-Freigabe nicht noch einmal", "erledigt", eintrag(s, "usb_freigabe").getString("status"));
        assertEquals("entfallene bleiben weg", "entfaellt", eintrag(s, "extra_overdrive").getString("status"));
        assertEquals("ai_private", root.getJSONObject("surveillance").getString("triggerMode"));
        assertEquals("osm", root.getJSONObject("karte").getString("quelle"));
    }

    @Test
    public void spaeterStelltDenAltenStandHerUndFertigRaeumtAuf() throws Exception {
        JSONObject root = anwenden(fertig(), EinrichtungApiHandler.wiederholen(fertig(), null));
        root = anwenden(root, EinrichtungApiHandler.spaeter(root, "waechter"));
        assertEquals("erledigt", eintrag(sicht(root), "waechter").getString("status"));
        root = anwenden(root, EinrichtungApiHandler.spaeter(root, "extra_telegram"));
        assertEquals("spaeter", eintrag(sicht(root), "extra_telegram").getString("status"));
        root = anwenden(root, EinrichtungApiHandler.schritt(root, "fertig", new JSONObject().put("ok", true), FELDER, false, 5_000L));
        JSONObject s = sicht(root);
        assertFalse(s.getBoolean("wiederholung"));
        assertEquals("nicht durchgegangene Schritte zurück wie vorher", "erledigt", eintrag(s, "karte").getString("status"));
        assertFalse("0.1.38.5: Wächter ist kein Pflichtschritt mehr", eintrag(s, "waechter").getBoolean("pflicht"));
        assertEquals("fertig", s.getString("aktuell"));
    }

    @Test
    public void einzelnenSchrittOeffnen() throws Exception {
        JSONObject root = anwenden(fertig(), EinrichtungApiHandler.wiederholen(fertig(), "kameras"));
        JSONObject s = sicht(root);
        assertEquals("kameras", s.getString("aktuell"));
        assertEquals("offen", eintrag(s, "kameras").getString("status"));
        assertEquals("erledigt", eintrag(s, "karte").getString("status"));
        assertEquals("erledigt", eintrag(s, "waechter").getString("status"));
        root = anwenden(root, EinrichtungApiHandler.wiederholen(root, "extra_display"));
        assertTrue("freiwilliger Schritt kommt im Ablauf dran", eintrag(sicht(root), "extra_display").getBoolean("schnellstart"));
        assertEquals(404, EinrichtungApiHandler.wiederholen(root, "usb_freigabe").code);
        assertEquals(404, EinrichtungApiHandler.wiederholen(root, "gibts_nicht").code);
    }

    @Test
    public void pflichtOhneAntwortBleibtPflicht() throws Exception {
        // 0.1.38.5: Pflicht ist nur noch sprache/haftung – eine unbeantwortete Haftung bleibt auch beim Wiederholen Pflicht.
        JSONObject root = new JSONObject();
        root = anwenden(root, EinrichtungApiHandler.wiederholen(root, null));
        assertEquals(422, EinrichtungApiHandler.spaeter(root, "haftung").code);
        assertTrue(eintrag(sicht(root), "haftung").getBoolean("pflicht"));
    }

    @Test
    public void appBelegtNurBeimWiederholenVor() throws Exception {
        JSONObject root = anwenden(fertig(), EinrichtungApiHandler.wiederholen(fertig(), null));
        EinrichtungStand st = EinrichtungStand.Companion.ausJson(sicht(root));
        assertTrue(st.getWiederholung());
        assertEquals("ai_private", st.vorbelegung("waechter").getString("modus"));
        EinrichtungStand normal = EinrichtungStand.Companion.ausJson(sicht(fertig()));
        assertNull("Neueinrichtung ohne Vorauswahl", normal.vorbelegung("waechter"));
        String p = lies("app/src/main/java/at/greenchili/surfmate/onboarding/schritte/Pflichtschritte.kt");
        assertTrue(p.contains("override val spaeterZeigen: Boolean get() = a.stand().wiederholung"));
        String w = lies("app/src/main/assets/web/shared/einrichtung-web.js");
        assertTrue(w.contains("/api/einrichtung/durchgehen") && w.contains("/api/einrichtung/oeffnen"));
        assertTrue(w.contains("function alleSchritte("));
    }

    private static String lies(String rel) throws Exception {
        Path p = Paths.get(rel);
        if (!Files.exists(p)) p = Paths.get("..").resolve(rel);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
