package at.greenchili.surfmate.onboarding;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.einstellungen.Feld;
import at.greenchili.surfmate.einstellungen.Katalog;
import at.greenchili.surfmate.einstellungen.Laenderprofil;
import at.greenchili.surfmate.server.EinrichtungApiHandler;
import at.greenchili.surfmate.server.EinrichtungApiHandler.Antwort;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** 0.1.32 Schnellstart: wenige Pflichtschritte, Rest in „Noch einrichten“; der Bestand merkt davon nichts. */
public class EinrichtungSchnellstartTest {

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

    private static JSONObject schritt(JSONObject root, String id, JSONObject antwort) throws Exception {
        return anwenden(root, EinrichtungApiHandler.schritt(root, id, antwort, FELDER, false, 1_000L));
    }

    private static JSONObject sicht(JSONObject root) throws Exception {
        return EinrichtungApiHandler.sicht(root, EinrichtungApiHandler.zustand(root), null, null);
    }

    private static JSONObject eintrag(JSONObject sicht, String id) throws Exception {
        JSONArray s = sicht.getJSONArray("schritte");
        for (int i = 0; i < s.length(); i++) if (id.equals(s.getJSONObject(i).getString("id"))) return s.getJSONObject(i);
        throw new AssertionError(id);
    }

    private static String status(JSONObject sicht, String id) throws Exception {
        return eintrag(sicht, id).getString("status");
    }

    /** Stand eines 0.1.31-Autos: alle Antworten da, alter Assistent durchlaufen. */
    private static JSONObject bestand031() throws Exception {
        JSONObject schritte = new JSONObject();
        for (String id : EinrichtungApiHandler.SCHRITTE) schritte.put(id, "erledigt");
        schritte.put("extra_telegram", "spaeter").put("extra_cockpit", "spaeter").put("strompreis", "spaeter");
        return new JSONObject()
                .put("nativeShell", new JSONObject().put("locale", "de").put("localeGewaehlt", true))
                .put("hinweise", new JSONObject().put("version", Katalog.HINWEISE_VERSION))
                .put("vehicle", new JSONObject().put("modelId", "atto3").put("modelSource", "user"))
                .put("karte", new JSONObject().put("quelle", "community"))
                .put("surveillance", new JSONObject().put("operatingMode", "onAndOff").put("triggerMode", "ai_everywhere"))
                .put("einrichtung", new JSONObject().put("version", 1).put("art", "bestand").put("neuZaehler", 0)
                        .put("schritte", schritte).put("gruende", new JSONObject()).put("werte", new JSONObject()));
    }

    @Test
    public void pflichtBleibtNurDasWirklichNoetige() {
        assertEquals(new HashSet<>(Arrays.asList("sprache", "haftung", "usb_freigabe", "waechter", "updates")), EinrichtungApiHandler.PFLICHT);
        assertTrue("alle Pflichtschritte im Schnellstart", EinrichtungApiHandler.SCHNELLSTART.containsAll(EinrichtungApiHandler.PFLICHT));
        assertTrue("QR am Ende", EinrichtungApiHandler.SCHNELLSTART.contains("extra_handy"));
        assertTrue(EinrichtungApiHandler.SCHRITTE.containsAll(EinrichtungApiHandler.SCHNELLSTART));
    }

    @Test
    public void neueEinrichtungFragtNurDenSchnellstart() throws Exception {
        JSONObject s = sicht(new JSONObject());
        List<String> offen = new ArrayList<>();
        JSONArray l = s.getJSONArray("schritte");
        for (int i = 0; i < l.length(); i++) {
            JSONObject e = l.getJSONObject(i);
            assertEquals(e.toString(), EinrichtungApiHandler.SCHNELLSTART.contains(e.getString("id")), e.getBoolean("schnellstart"));
            if ("offen".equals(e.getString("status"))) offen.add(e.getString("id"));
        }
        assertEquals(Arrays.asList("sprache", "haftung", "autostart", "ueberlagerung", "rechte", "waechter", "updates", "extra_handy", "fertig"), offen);
        for (String id : new String[]{"auto", "kameras", "betrieb", "karte", "daten", "blitzer", "strompreis", "extra_telegram",
                "extra_planer", "extra_cockpit", "extra_fernzugang", "extra_notzugang"}) {
            assertEquals(id, "spaeter", status(s, id));
        }
    }

    @Test
    public void nachDemSchnellstartIstDerAssistentFertigUndDerRestBleibtFreiwillig() throws Exception {
        JSONObject r = new JSONObject();
        r = schritt(r, "sprache", new JSONObject().put("locale", "de"));
        r = schritt(r, "haftung", new JSONObject().put("gelesen", true).put("version", Katalog.HINWEISE_VERSION));
        assertEquals("autostart", sicht(r).getString("aktuell"));
        for (String id : new String[]{"autostart", "ueberlagerung", "rechte"}) {
            r = schritt(r, id, new JSONObject().put("geprueft", true).put("ok", true));
        }
        assertEquals("waechter", sicht(r).getString("aktuell"));
        assertFalse(sicht(r).getBoolean("abgeschlossen"));
        r = schritt(r, "waechter", new JSONObject().put("modus", "shock").put("ton", false).put("ereignisClip", false));
        assertEquals("updates", sicht(r).getString("aktuell"));
        assertFalse("ohne Update-Antwort nicht fertig", sicht(r).getBoolean("abgeschlossen"));
        r = schritt(r, "updates", new JSONObject().put("pruefen", false));
        assertFalse(r.getJSONObject("update").getBoolean("pruefen"));
        assertTrue(sicht(r).getBoolean("abgeschlossen"));
        assertEquals("extra_handy", sicht(r).getString("aktuell"));
        r = anwenden(r, EinrichtungApiHandler.spaeter(r, "extra_handy"));
        assertEquals("fertig", sicht(r).getString("aktuell"));
        // ein freiwilliger Punkt aus der Liste, danach steht weiter „fertig“ an
        r = schritt(r, "karte", new JSONObject().put("quelle", "osm"));
        assertEquals("erledigt", status(sicht(r), "karte"));
        assertEquals("fertig", sicht(r).getString("aktuell"));
    }

    @Test
    public void rechtlichePflichtBleibtOhneVorauswahlUndOhneSpaeter() throws Exception {
        for (String id : new String[]{"haftung", "waechter", "updates"}) {
            assertEquals(id, 422, EinrichtungApiHandler.spaeter(new JSONObject(), id).code);
            assertEquals(id, 422, EinrichtungApiHandler.schritt(new JSONObject(), id, new JSONObject(), FELDER, false, 1L).code);
        }
        // Freiwillige Schritte prüfen ihre Antwort weiter, wenn sie aufgerufen werden
        assertEquals(422, EinrichtungApiHandler.schritt(new JSONObject(), "karte", new JSONObject(), FELDER, false, 1L).code);
        assertEquals(422, EinrichtungApiHandler.schritt(new JSONObject(), "updates", new JSONObject(), FELDER, false, 1L).code);
        assertEquals(422, EinrichtungApiHandler.schritt(new JSONObject(), "betrieb", new JSONObject(), FELDER, false, 1L).code);
    }

    @Test
    public void schweizKiUeberallBleibtGesperrt() throws Exception {
        Katalog.Kontext ch = new Katalog.Kontext();
        ch.land = Laenderprofil.Land.SCHWEIZ;
        List<Feld> felder = Katalog.felder(ch);
        Feld f = Katalog.finde(felder, "surveillance.triggerMode");
        boolean gesperrt = false;
        for (Feld.Option o : f.optionen) if ("ai_everywhere".equals(o.wert)) gesperrt = o.gesperrt;
        assertTrue("(b) in CH gesperrt", gesperrt);
        assertEquals("Antwort bleibt Pflichtwahl", 422, EinrichtungApiHandler.schritt(new JSONObject(), "waechter",
                new JSONObject().put("ton", false), felder, false, 1L).code);
    }

    @Test
    public void bestand031VerliertNichtsUndWirdNichtGefragt() throws Exception {
        JSONObject root = bestand031();
        JSONObject vorher = new JSONObject(root.toString());
        JSONObject s = sicht(root);
        assertEquals("fertig", s.getString("aktuell"));
        assertTrue(s.getBoolean("abgeschlossen"));
        assertFalse(OnboardingConfigPolicy.assistentStarten(s.getString("aktuell")));
        JSONArray l = s.getJSONArray("schritte");
        JSONObject alt = vorher.getJSONObject("einrichtung").getJSONObject("schritte");
        for (int i = 0; i < l.length(); i++) {
            JSONObject e = l.getJSONObject(i);
            assertEquals(e.toString(), alt.getString(e.getString("id")), e.getString("status"));
        }
        assertEquals("Config unverändert", vorher.toString(), root.toString());
    }

    @Test
    public void bestandOhneGespeichertenZustandBehaeltAlleAntworten() throws Exception {
        JSONObject root = bestand031();
        root.remove("einrichtung");
        JSONObject s = sicht(root);
        assertEquals("fertig", s.getString("aktuell"));
        for (String id : new String[]{"sprache", "haftung", "auto", "waechter", "betrieb", "karte", "updates"}) {
            assertEquals(id, "erledigt", status(s, id));
        }
    }

    @Test
    public void bestandOhneKarteWirdNichtMehrGefragtSondernSteht_inDerListe() throws Exception {
        JSONObject root = bestand031();
        root.remove("einrichtung");
        root.remove("karte");
        JSONObject s = sicht(root);
        assertEquals("spaeter", status(s, "karte"));
        assertEquals("fertig", s.getString("aktuell"));
        assertFalse(OnboardingConfigPolicy.assistentStarten(s.getString("aktuell")));
    }

    @Test
    public void neueHaftungsfassungFragtDenBestandNurDanach() throws Exception {
        JSONObject root = bestand031();
        root.getJSONObject("einrichtung").getJSONObject("schritte").put("haftung", "offen");
        root.getJSONObject("hinweise").put("version", "2026-01-01");
        JSONObject s = sicht(root);
        assertEquals("haftung", s.getString("aktuell"));
        assertEquals("erledigt", status(s, "karte"));
    }

    @Test
    public void zwischenstandAusAlterVersionGehtKurzWeiter() throws Exception {
        JSONObject schritte = new JSONObject();
        for (String id : EinrichtungApiHandler.SCHRITTE) schritte.put(id, "offen");
        schritte.put("sprache", "erledigt").put("haftung", "erledigt").put("karte", "erledigt");
        JSONObject root = new JSONObject().put("einrichtung", new JSONObject().put("version", 1).put("art", "neu").put("schritte", schritte));
        JSONObject s = sicht(root);
        assertEquals("autostart", s.getString("aktuell"));
        assertEquals("offen", status(s, "updates"));
        assertEquals("spaeter", status(s, "betrieb"));
        assertEquals("erledigt", status(s, "karte"));
    }

    @Test
    public void updatesRufenBeiNeuerEinrichtungErstNachDerAntwortAb() throws Exception {
        JSONObject r = new JSONObject();
        r.put("einrichtung", EinrichtungApiHandler.zustand(r));
        assertTrue("neu, Frage offen: kein Abruf", EinrichtungApiHandler.updatesUnbeantwortet(r));
        JSONObject ja = schritt(r, "updates", new JSONObject().put("pruefen", true));
        assertFalse(EinrichtungApiHandler.updatesUnbeantwortet(ja));
        assertTrue(ja.getJSONObject("update").getBoolean("pruefen"));
        assertFalse("neue Einrichtung: Mobilfunk-Download aus", ja.getJSONObject("update").getBoolean("mobilfunk"));
        assertTrue("Einwilligung je Download am Hinweis", ja.getJSONObject("update").getBoolean("mobilfunkEinmal"));
        assertEquals("melden", ja.getJSONObject("update").getString("modus"));
        assertFalse("Bestand ohne Wert prüft wie bisher", EinrichtungApiHandler.updatesUnbeantwortet(bestand031()));
        assertFalse(EinrichtungApiHandler.updatesUnbeantwortet(null));
        assertFalse("kein Zustand: Daemon vor dem ersten GET", EinrichtungApiHandler.updatesUnbeantwortet(new JSONObject()));
    }

    @Test
    public void bestandBehaeltSeinenMobilfunkWert() throws Exception {
        // Bestand ohne Wert („nicht gesetzt = an“): „erneut starten“ und neu antworten ändert nichts
        JSONObject r = anwenden(bestand031(), EinrichtungApiHandler.neu(bestand031()));
        JSONObject u = schritt(r, "updates", new JSONObject().put("pruefen", true)).getJSONObject("update");
        assertFalse(u.has("mobilfunk"));
        assertFalse(u.has("mobilfunkEinmal"));
        // gesetzter Wert bleibt, auch auf einem frischen Stand
        JSONObject mitWert = new JSONObject().put("update", new JSONObject().put("mobilfunk", true));
        u = schritt(mitWert, "updates", new JSONObject().put("pruefen", true)).getJSONObject("update");
        assertTrue(u.getBoolean("mobilfunk"));
        assertFalse(u.has("mobilfunkEinmal"));
    }

    @Test
    public void erneutStartenLaesstDemBestandSeineUpdates() throws Exception {
        JSONObject root = bestand031();
        JSONObject r = anwenden(root, EinrichtungApiHandler.neu(root));
        JSONObject s = sicht(r);
        assertEquals("sprache", s.getString("aktuell"));
        assertEquals("offen", status(s, "waechter"));
        assertEquals("offen", status(s, "updates"));
        assertEquals("erledigt", status(s, "karte"));
        assertEquals("spaeter", status(s, "extra_telegram"));
        assertFalse(EinrichtungApiHandler.updatesUnbeantwortet(r));
    }
}
