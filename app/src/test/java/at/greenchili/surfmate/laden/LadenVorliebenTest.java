package at.greenchili.surfmate.laden;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.einstellungen.Einstellungen;
import at.greenchili.surfmate.einstellungen.Feld;
import at.greenchili.surfmate.einstellungen.Katalog;
import at.greenchili.surfmate.einstellungen.LadenKatalog;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** LADEN_VERTRAG.md Teil A: Gruppe „laden“, Abbildung auf den Plan-Request, Doppelung planner.reserve. */
public class LadenVorliebenTest {

    private static JSONObject gesetzt() throws Exception {
        return new JSONObject().put("laden", new JSONObject()
                .put("stand", "2026-10-22T08:00:00Z")
                .put("karten", new JSONArray().put(new JSONObject().put("id", 4).put("anzeige", "Fastned · Gold")).put(new JSONObject().put("id", 7)))
                .put("nurMeineKarten", true)
                .put("netze", new JSONArray().put(new JSONObject().put("cpo", "Lidl")))
                .put("netzeModus", "nur")
                .put("umwegMaxMin", 15)
                .put("nurOffen", true)
                .put("minKw", 50)
                .put("ankunftSocMin", 20));
    }

    @Test
    public void ohneStand_keineVorlieben_Uebergang() throws Exception {
        JSONObject root = new JSONObject().put("laden", new JSONObject().put("minKw", 50));
        assertNull(LadenVorlieben.stand(root));
        assertNull(LadenVorlieben.planerStueck(root));
        JSONObject a = LadenVorlieben.antwort(new JSONObject());
        assertEquals("standard", a.getString("quelle"));
        assertTrue(a.isNull("planer"));
        JSONObject l = a.getJSONObject("laden");
        assertEquals(0, l.getJSONArray("karten").length());
        assertEquals("bevorzugen", l.getString("netzeModus"));
        assertEquals(10, l.getInt("umwegMaxMin"));
        assertTrue(l.isNull("minKw"));
        assertTrue(l.isNull("modus"));
    }

    @Test
    public void planerStueck_vollstaendigUndNetzneutral() throws Exception {
        JSONObject p = LadenVorlieben.planerStueck(gesetzt());
        assertEquals("[4,7]", p.getJSONArray("karten").toString());
        assertEquals(1, p.getInt("nur_meine_karten"));
        assertEquals(20, p.getInt("soc_ziel_min"));
        assertFalse(p.has("soc_max"));   // nicht gesetzt = Planer-Standard, nicht mitsenden
        assertFalse(p.has("modus"));
        JSONObject f = p.getJSONObject("filter");
        assertEquals("[\"Lidl\"]", f.getJSONArray("bevorzugt").toString());
        assertEquals("nur", f.getString("netze_modus"));
        assertEquals(15, f.getInt("umweg_max_min"));
        assertTrue(f.getBoolean("nur_offen"));
        assertEquals(50, f.getInt("min_kw"));
        assertEquals(0, f.getJSONArray("ausschluss").length());
        assertFalse(f.has("stecker"));
        assertEquals("auto", p.getString("vorlieben_quelle"));
    }

    @Test
    public void leereListenUeberstimmenDasKonto() throws Exception {
        JSONObject root = new JSONObject().put("laden", new JSONObject().put("stand", "2026-10-22T08:00:00Z"));
        JSONObject p = LadenVorlieben.planerStueck(root);
        assertEquals(0, p.getJSONArray("karten").length());            // „keine Karten“, nicht Konto-Liste
        assertEquals(0, p.getJSONObject("filter").getJSONArray("bevorzugt").length());
    }

    @Test
    public void mische_anfrageMitWertGewinnt_leeresZaehltNicht() throws Exception {
        JSONObject stueck = LadenVorlieben.planerStueck(gesetzt());
        JSONObject anfrage = new JSONObject().put("soc_ziel_min", 12).put("soc_start", 60)
                .put("filter", new JSONObject().put("bevorzugt", new JSONArray()).put("min_kw", 150));
        JSONObject m = LadenVorlieben.mische(anfrage, stueck);
        assertEquals(12, m.getInt("soc_ziel_min"));                     // Formular gewinnt
        assertEquals(60, m.getInt("soc_start"));
        assertEquals("[4,7]", m.getJSONArray("karten").toString());
        assertEquals("[\"Lidl\"]", m.getJSONObject("filter").getJSONArray("bevorzugt").toString());   // leer → Auto
        assertEquals(150, m.getJSONObject("filter").getInt("min_kw"));
        assertEquals("nur", m.getJSONObject("filter").getString("netze_modus"));
        assertEquals("auto", m.getString("vorlieben_quelle"));
        // ohne Stück unverändert (kein vorlieben_quelle)
        assertFalse(LadenVorlieben.mische(anfrage, null).has("vorlieben_quelle"));
    }

    @Test
    public void reserve_eineQuelle_mitRueckfallAufAltwert() throws Exception {
        JSONObject alt = new JSONObject().put("planner", new JSONObject().put("reserve", 18));
        assertEquals(18.0, LadenVorlieben.ankunftReserve(alt), 0.001);
        assertEquals(18, LadenVorlieben.lies(alt).getInt("ankunftSocMin"));
        assertTrue(Double.isNaN(LadenVorlieben.ankunftReserve(new JSONObject().put("planner", new JSONObject().put("reserve", 0)))));
        JSONObject beide = new JSONObject(alt.toString()).put("laden", new JSONObject().put("ankunftSocMin", 25));
        assertEquals(25.0, LadenVorlieben.ankunftReserve(beide), 0.001);
        assertTrue(Double.isNaN(LadenVorlieben.ankunftReserve(new JSONObject())));
        // planner.reserve ist aus dem Schema verschwunden (Doppelung)
        assertNull(Katalog.finde(Katalog.felder(new Katalog.Kontext()), "planner.reserve"));
        assertTrue(Katalog.finde(Katalog.felder(new Katalog.Kontext()), "laden.ankunftSocMin") != null);
    }

    @Test
    public void schreibenUeberEinstellungen_setztStand_loeschtAltwert() throws Exception {
        JSONObject root = new JSONObject().put("planner", new JSONObject().put("reserve", 18));
        Map<String, Map<String, Object>> geschrieben = new HashMap<>();
        List<Feld> felder = Katalog.felder(new Katalog.Kontext());
        Einstellungen.Ergebnis e = Einstellungen.aendern(felder, root, new Katalog.Kontext(),
                new JSONObject().put("werte", new JSONObject().put("laden.ankunftSocMin", 22).put("laden.minKw", JSONObject.NULL)),
                true, (a, w) -> { geschrieben.computeIfAbsent(a, x -> new HashMap<>()).putAll(w); return true; }, 0L);
        assertTrue(e.fehler.toString(), e.ok());
        assertEquals(22, geschrieben.get("laden").get("ankunftSocMin"));
        assertTrue(geschrieben.get("laden").containsKey("stand"));
        assertEquals(JSONObject.NULL, geschrieben.get("laden").get("minKw"));
        assertEquals(JSONObject.NULL, geschrieben.get("planner").get("reserve"));
    }

    @Test
    public void pruefungen() throws Exception {
        Feld karten = Katalog.finde(LadenKatalog.felder(), "laden.karten");
        JSONArray k = (JSONArray) Einstellungen.pruefe(karten, new JSONArray("[4, {\"id\":7,\"anzeige\":\"  IONITY\\n Power \"}, 4]"));
        assertEquals(4, k.getJSONObject(0).getInt("id"));
        assertFalse(k.getJSONObject(0).has("anzeige"));
        assertEquals("IONITY Power", k.getJSONObject(1).getString("anzeige"));
        assertEquals(2, k.length());
        assertFehler(karten, new JSONArray("[0]"), "id");
        assertFehler(karten, new JSONArray("[1.5]"), "id");
        assertFehler(karten, new JSONArray("[\"4\"]"), "typ");
        JSONArray viele = new JSONArray();
        for (int i = 1; i <= 31; i++) viele.put(i);
        assertFehler(karten, viele, "max 30");
        assertEquals(null, Einstellungen.pruefe(karten, null));   // null = zurück auf leer

        Feld netze = Katalog.finde(LadenKatalog.felder(), "laden.netze");
        JSONArray n = (JSONArray) Einstellungen.pruefe(netze, new JSONArray("[\"Lidl\", {\"cpo\":\"lidl\"}, {\"cpo\":\"AT*SMA\",\"anzeige\":\"SMATRICS\"}]"));
        assertEquals(2, n.length());   // Groß/klein doppelt
        assertFehler(netze, new JSONArray("[\"   \"]"), "cpo");

        Feld st = Katalog.finde(LadenKatalog.felder(), "laden.stecker");
        assertEquals("[\"ccs\",\"nacs\"]", Einstellungen.pruefe(st, new JSONArray("[\"ccs\",\"nacs\",\"ccs\"]")).toString());
        assertNull(Einstellungen.pruefe(st, new JSONArray()));
        assertFehler(st, new JSONArray("[\"schuko\"]"), "option");

        Feld kw = Katalog.finde(LadenKatalog.felder(), "laden.minKw");
        assertNull(Einstellungen.pruefe(kw, null));
        assertFehler(kw, 351, "max 350");
        Feld modus = Katalog.finde(LadenKatalog.felder(), "laden.modus");
        assertNull(Einstellungen.pruefe(modus, null));
        assertFehler(modus, "teuer", "option");
        Feld nur = Katalog.finde(LadenKatalog.felder(), "laden.nurOffen");
        assertFehler(nur, null, "typ");
    }

    @Test
    public void gruppeImSchema_listenVerborgen() throws Exception {
        Katalog.Kontext k = new Katalog.Kontext();
        JSONObject schema = Einstellungen.schema(Katalog.felder(k), new JSONObject(), k, new Einstellungen.Texte() {
            public String text(String key) { return key; }
            public JSONObject unter(String p) { return new JSONObject(); }
        });
        JSONObject laden = null;
        JSONArray g = schema.getJSONArray("gruppen");
        for (int i = 0; i < g.length(); i++) if ("laden".equals(g.getJSONObject(i).getString("id"))) laden = g.getJSONObject(i);
        assertTrue(laden != null);
        assertEquals("fahren", laden.getString("bereich"));
        JSONArray f = laden.getJSONArray("felder");
        for (int i = 0; i < f.length(); i++) {
            JSONObject x = f.getJSONObject(i);
            boolean liste = "liste".equals(x.getString("typ")) || "laden.fahrzeug".equals(x.getString("key"));   // eigene Auswahl
            assertEquals(x.getString("key"), !liste, x.getBoolean("sichtbar"));
            assertFalse(x.getString("key"), x.getBoolean("nurLesendExtern"));   // Cockpit darf pflegen
        }
    }

    private static void assertFehler(Feld f, Object v, String grund) {
        try {
            Einstellungen.pruefe(f, v);
            throw new AssertionError("kein Fehler für " + v);
        } catch (IllegalArgumentException e) {
            assertEquals(grund, e.getMessage());
        }
    }
}
