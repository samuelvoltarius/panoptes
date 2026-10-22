package at.greenchili.surfmate.charging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.einstellungen.Katalog;
import at.greenchili.surfmate.server.EinrichtungApiHandler;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Map;

/** 0.1.10 (TRIPKOSTEN_BEFUND): 0,01 €/kWh aus Overdrive → Hinweis, nie still korrigiert. */
public class PreisPlausibelTest {

    @Test
    public void alfredsFall() throws Exception {
        // 17:42: 33,1 km, 6,1 kWh × 0,01 = 0,061 € – der Wert ist unplausibel
        assertEquals(PreisPlausibel.ZU_NIEDRIG, PreisPlausibel.pruefe(0.01, "€"));
        assertEquals(PreisPlausibel.ZU_NIEDRIG, PreisPlausibel.pruefe(0.01, "EUR"));
        assertNull(PreisPlausibel.pruefe(0.30, "€"));
        assertNull("DC 0,39", PreisPlausibel.pruefe(0.39, "€"));
        assertEquals(PreisPlausibel.ZU_HOCH, PreisPlausibel.pruefe(30, "€"));   // ct statt €
        assertNull("nicht gesetzt", PreisPlausibel.pruefe(0, "€"));
        assertNull("unbekannte Währung: keine Meldung", PreisPlausibel.pruefe(0.01, "XYZ"));
        assertNull("NOK-Band", PreisPlausibel.pruefe(3.5, "NOK"));
    }

    @Test
    public void nochOffenOhneStillesKorrigieren() throws Exception {
        JSONObject root = new JSONObject()
                .put("tripAnalytics", new JSONObject().put("electricityRate", 0.01).put("currency", "€"))
                .put("chargingAnalytics", new JSONObject().put("dcRate", 0.39));   // Alfreds Config (Deploy-Befund)
        JSONArray o = PreisPlausibel.offen(root);
        assertEquals(1, o.length());
        assertEquals("ac", o.getJSONObject(0).getString("art"));
        assertEquals(0.01, o.getJSONObject(0).getDouble("wert"), 1e-9);
        assertEquals("[\"tripAnalytics.electricityRate\"]", o.getJSONObject(0).getJSONArray("einstellungen").toString());
        assertEquals("Wert unverändert", 0.01, root.getJSONObject("tripAnalytics").getDouble("electricityRate"), 1e-9);
        JSONObject sicht = EinrichtungApiHandler.sicht(root, EinrichtungApiHandler.zustand(root), null, null);
        assertTrue(sicht.getJSONArray("noch_offen").toString().contains("strompreis_pruefen"));
    }

    @Test
    public void gratisNurWennAusdruecklich() throws Exception {
        assertNull("bestätigt gratis", PreisPlausibel.pruefe(0, "€", true));
        assertEquals(PreisPlausibel.NULL_UNBESTAETIGT, PreisPlausibel.pruefe(0, "€", false));
        assertEquals(PreisPlausibel.ZU_NIEDRIG, PreisPlausibel.pruefe(0.01, "€", true));
        JSONObject importNull = new JSONObject()
                .put("tripAnalytics", new JSONObject().put("electricityRate", 0).put("currency", "€"));
        assertEquals("null_unbestaetigt", PreisPlausibel.offen(importNull).getJSONObject(0).getString("grund"));
        JSONObject gratis = new JSONObject().put("tripAnalytics", new JSONObject()
                .put("electricityRate", 0).put("currency", "€").put("rateGratis", true));
        assertEquals(0, PreisPlausibel.offen(gratis).length());
        assertEquals("nicht gesetzt = nichts", 0, PreisPlausibel.offen(new JSONObject()
                .put("tripAnalytics", new JSONObject().put("currency", "€"))).length());
        // Assistent: „Zuhause gratis“
        EinrichtungApiHandler.Antwort a = EinrichtungApiHandler.schritt(new JSONObject(), "strompreis",
                new JSONObject().put("gratis", true), Katalog.felder(new Katalog.Kontext()), false, 1L);
        assertEquals(200, a.code);
        assertEquals(0.0, (Double) a.schreiben.get("tripAnalytics").get("electricityRate"), 0);
        assertEquals(Boolean.TRUE, a.schreiben.get("tripAnalytics").get("rateGratis"));
    }

    @Test
    public void dcAusChargingAnalyticsWirdGeprueft() throws Exception {
        JSONObject root = new JSONObject()
                .put("tripAnalytics", new JSONObject().put("electricityRate", 0.30).put("currency", "€"))
                .put("chargingAnalytics", new JSONObject().put("dcRate", 0.0039));
        JSONArray o = PreisPlausibel.offen(root);
        assertEquals(1, o.length());
        assertEquals("dc", o.getJSONObject(0).getString("art"));
        assertEquals("[\"chargingAnalytics.dcRate\"]", o.getJSONObject(0).getJSONArray("einstellungen").toString());
    }

    @Test
    public void assistentFragtStrompreis() throws Exception {
        assertTrue(EinrichtungApiHandler.SCHRITTE.contains("strompreis"));
        assertTrue(!EinrichtungApiHandler.PFLICHT.contains("strompreis"));
        EinrichtungApiHandler.Antwort a = EinrichtungApiHandler.schritt(new JSONObject(), "strompreis",
                new JSONObject().put("acRate", 0.31).put("waehrung", "€"),
                Katalog.felder(new Katalog.Kontext()), false, 1L);
        assertEquals(200, a.code);
        Map<String, Object> t = a.schreiben.get("tripAnalytics");
        assertEquals(0.31, (Double) t.get("electricityRate"), 1e-9);
        assertEquals("€", t.get("currency"));
        assertEquals(422, EinrichtungApiHandler.schritt(new JSONObject(), "strompreis",
                new JSONObject(), Katalog.felder(new Katalog.Kontext()), false, 1L).code);
    }
}
