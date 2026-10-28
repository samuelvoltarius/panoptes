package at.greenchili.surfmate.orte;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * 0.1.19 (ORTE_VERTRAG §5, Leser-Umstellung): SafeLocationManager und TariffManager lesen die Ortsliste. Prüft: gleiches
 * Ergebnis wie der Spiegel; Änderungen über orte wirken; was ein alter Weg schreibt, wirkt sofort (vor dem Abgleich).
 */
public class OrteLeserTest {

    private static OrteTest.Alt bestand() throws Exception {
        return new OrteTest.Alt()
                .ruhe("a1", "Zuhause", 48.30941, 14.29001, 80, true)
                .ruhe("a2", "Büro", 48.29000, 14.27500, 60, false)
                .tarif("t_home", "Hausstrom", 48.30941, 14.29001, 80, 0.0, null)
                .tarif("t_lidl", "Lidl Leoben", 47.38000, 15.09000, 50, null, 0.29);
    }

    private static Map<String, JSONObject> nachId(JSONArray a) {
        Map<String, JSONObject> m = new HashMap<>();
        for (int i = 0; i < a.length(); i++) m.put(a.optJSONObject(i).optString("id"), a.optJSONObject(i));
        return m;
    }

    private static JSONArray spiegel(OrteTest.Alt alt, String art) throws Exception {
        return alt.abschnittLesen().getJSONObject("spiegel").getJSONArray(art);
    }

    @Test
    public void nachMigrationSehenDieLeserDasselbeWieDieDatei() throws Exception {
        OrteTest.Alt alt = bestand();
        new OrteDienst(alt).abgleichen(1000);
        JSONArray l = alt.abschnittLesen().getJSONArray("liste");
        Map<String, JSONObject> rz = nachId(Orte.ruheZonen(l, alt.ruhe, spiegel(alt, "ruhe")));
        assertEquals(2, rz.size());
        assertTrue(rz.get("a1").getBoolean("an"));
        assertFalse("Büro war aus", rz.get("a2").getBoolean("an"));
        assertEquals(80, rz.get("a1").getInt("r"));
        Map<String, double[]> k = Orte.tarifKreise(l, alt.tarife, spiegel(alt, "tarife"));
        assertEquals(2, k.size());
        assertEquals(47.38, k.get("t_lidl")[0], 1e-9);
        assertEquals(50, k.get("t_lidl")[2], 1e-9);
    }

    @Test
    public void aenderungUeberOrteWirktFuerBeideLeser() throws Exception {
        OrteTest.Alt alt = bestand();
        OrteDienst d = new OrteDienst(alt);
        JSONArray l = d.abgleichen(1000);
        String id = null;
        for (int i = 0; i < l.length(); i++) if ("Lidl Leoben".equals(l.getJSONObject(i).getString("name"))) id = l.getJSONObject(i).getString("id");
        d.aendern(id, new JSONObject().put("r", 120), 2000);
        l = alt.abschnittLesen().getJSONArray("liste");
        assertEquals(120, Orte.tarifKreise(l, alt.tarife, spiegel(alt, "tarife")).get("t_lidl")[2], 1e-9);
    }

    @Test
    public void alterSchreibwegWirktSofortVorDemAbgleich() throws Exception {
        OrteTest.Alt alt = bestand();
        new OrteDienst(alt).abgleichen(1000);
        JSONArray l = alt.abschnittLesen().getJSONArray("liste");
        JSONArray sp = spiegel(alt, "ruhe");
        // alte Seite: Zuhause ausgeschaltet, Büro gelöscht, neue Zone angelegt – noch kein Abgleich
        alt.ruheAendern("a1", "Zuhause", 48.30941, 14.29001, 80, false);
        alt.ruheEntfernen("a2");
        alt.ruhe("a9", "Oma", 48.0, 14.0, 100, true);
        Map<String, JSONObject> rz = nachId(Orte.ruheZonen(l, alt.ruhe, sp));
        assertFalse("Zuhause sofort aus", rz.get("a1").getBoolean("an"));
        assertNull("Büro sofort weg", rz.get("a2"));
        assertTrue("Oma sofort da", rz.get("a9").getBoolean("an"));
        // Tarif-Dialog verschiebt Lidl: bis zum Abgleich gilt der eigene Kreis des Tarifs
        alt.tarifAendern("t_lidl", new JSONObject().put("label", "Lidl Leoben").put("lat", 47.5).put("lon", 15.0).put("r", 50)
                .put("acRate", JSONObject.NULL).put("dcRate", 0.29).put("acGratis", false).put("dcGratis", false).put("waehrung", "€"));
        Map<String, double[]> k = Orte.tarifKreise(l, alt.tarife, spiegel(alt, "tarife"));
        assertFalse(k.containsKey("t_lidl"));
        assertTrue(k.containsKey("t_home"));
    }

    @Test
    public void ohneOrtsverweisNimmtDerLeserDieDateiZone() throws Exception {
        JSONArray alt = new JSONArray().put(new JSONObject().put("id", "x1").put("name", "Neu").put("lat", 1.0)
                .put("lon", 2.0).put("r", 50).put("an", true));
        JSONArray rz = Orte.ruheZonen(new JSONArray(), alt, null);
        assertEquals(1, rz.length());
        assertEquals("x1", rz.getJSONObject(0).getString("id"));
    }
}
