package at.greenchili.surfmate.onboarding;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.appupdate.UpdateRegel;
import at.greenchili.surfmate.einstellungen.Energie;
import at.greenchili.surfmate.einstellungen.Katalog;
import at.greenchili.surfmate.server.EinrichtungApiHandler;
import at.greenchili.surfmate.surveillance.ShockMonitor;
import at.greenchili.surfmate.surveillance.WaechterAusloeser;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Map;

/** Einrichtung Teil C (0.1.10): Pflichtantworten wirken, keine stillen Standards. */
public class EinrichtungTeilCTest {

    private static JSONObject anwenden(JSONObject root, EinrichtungApiHandler.Antwort a) throws Exception {
        assertEquals(a.body.toString(), 200, a.code);
        JSONObject r = new JSONObject(root.toString());
        for (Map.Entry<String, Map<String, Object>> s : a.schreiben.entrySet()) {
            JSONObject sec = r.optJSONObject(s.getKey());
            if (sec == null) { sec = new JSONObject(); r.put(s.getKey(), sec); }
            for (Map.Entry<String, Object> kv : s.getValue().entrySet()) sec.put(kv.getKey(), kv.getValue());
        }
        return r;
    }

    private static JSONObject neuinstallation() throws Exception {
        JSONObject root = new JSONObject();
        root.put("einrichtung", EinrichtungApiHandler.zustand(root));   // GET hält „neu“ fest
        return root;
    }

    @Test
    public void waechterAusKeineAufnahme() throws Exception {
        JSONObject root = anwenden(neuinstallation(), EinrichtungApiHandler.schritt(neuinstallation(), "waechter",
                new JSONObject().put("modus", "aus").put("ton", true),
                Katalog.felder(new Katalog.Kontext()), false, 1L));
        JSONObject sv = root.getJSONObject("surveillance");
        assertEquals("aus", sv.getString("triggerMode"));
        assertFalse("Wächter aus = nicht scharf", sv.getBoolean("surveillanceEnabled"));
        assertFalse(root.getJSONObject("recording").getBoolean("audioEnabled"));
        assertNull("kein Erschütterungs-Auslöser", ShockMonitor.empfindlichkeit(sv));
        WaechterAusloeser.Lage l = WaechterAusloeser.bestimme("aus", null, null, 0, 0, false);
        assertEquals(WaechterAusloeser.Urteil.UNTERDRUECKEN, WaechterAusloeser.urteil(l, true, true, true));
        // jede andere Wahl schaltet ihn an
        JSONObject an = anwenden(neuinstallation(), EinrichtungApiHandler.schritt(neuinstallation(), "waechter",
                new JSONObject().put("modus", "shock").put("ton", false),
                Katalog.felder(new Katalog.Kontext()), false, 1L));
        assertTrue(an.getJSONObject("surveillance").getBoolean("surveillanceEnabled"));
    }

    @Test
    public void sparsamBisBetriebBeantwortet() throws Exception {
        JSONObject root = neuinstallation();
        assertEquals(Energie.SPARSAM, Energie.profil(root));
        JSONObject sicht = EinrichtungApiHandler.sicht(root, EinrichtungApiHandler.zustand(root), null, null);
        assertTrue(sicht.getJSONArray("hinweise").toString().contains("einr.hinweis.sparsam_bis_betrieb"));
        assertTrue(sicht.getJSONArray("hinweise").toString().contains("einr.hinweis.keine_karte"));
        JSONObject nach = anwenden(root, EinrichtungApiHandler.schritt(root, "betrieb",
                new JSONObject().put("modus", "immer"), Katalog.felder(new Katalog.Kontext()), false, 1L));
        assertEquals(Energie.NORMAL, Energie.profil(nach));
        // ausdrückliches Profil gewinnt immer
        root.put("energie", new JSONObject().put("profil", "minimal"));
        assertEquals(Energie.MINIMAL, Energie.profil(root));
    }

    @Test
    public void bestandBleibtNormalUndKarteKeine() throws Exception {
        assertEquals("Alt-Installation ohne Einrichtung", Energie.NORMAL, Energie.profil(new JSONObject()));
        JSONObject bestand = new JSONObject().put("update", new JSONObject().put("pruefen", true));
        bestand.put("einrichtung", EinrichtungApiHandler.zustand(bestand));
        assertEquals(Energie.NORMAL, Energie.profil(bestand));
        // Karte ohne Wahl = keine, nichts wird still nachgeladen
        assertEquals("keine", Katalog.finde(Katalog.felder(new Katalog.Kontext()), "karte.quelle").standard);
    }

    @Test
    public void mobilfunkStandardAnUndAusHeisstNurWlan() {
        // Koordination 22.10.: Alfreds Entscheidung – nicht gesetzt = Mobilfunk erlaubt
        assertTrue(UpdateRegel.an(null));
        assertTrue(UpdateRegel.an("true"));
        assertFalse(UpdateRegel.an("false"));
        assertEquals(Boolean.TRUE, Katalog.finde(Katalog.felder(new Katalog.Kontext()), "update.mobilfunk").standard);
    }

    @Test
    public void haftungszeitpunktSichtbar() throws Exception {
        JSONObject root = new JSONObject();
        JSONObject r = anwenden(root, EinrichtungApiHandler.schritt(root, "haftung",
                new JSONObject().put("gelesen", true).put("version", Katalog.HINWEISE_VERSION),
                Katalog.felder(new Katalog.Kontext()), false, 1_800_000_000_000L));
        JSONObject sicht = EinrichtungApiHandler.sicht(r, EinrichtungApiHandler.zustand(r), null, null);
        assertEquals(1_800_000_000_000L, sicht.getJSONObject("werte").getJSONObject("haftung").getLong("zeit"));
        assertTrue(r.getJSONObject("hinweise").has("bestaetigtAm"));
    }
}
