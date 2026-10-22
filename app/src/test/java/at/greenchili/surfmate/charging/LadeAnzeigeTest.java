package at.greenchili.surfmate.charging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/** 0.1.11 (Kern 7): soc_estimate-Ladungen in der Anzeige mit dem heutigen Nennwert nachrechnen, sonst nichts ändern. */
public class LadeAnzeigeTest {

    /** Alfreds Ladung 21.10. 17:41: DC 22→85 %, 27,78 kWh (44,1 × 63 %), 10,84 € bei 0,39 €/kWh. */
    private static JSONObject ladung(String quelle, boolean offen) throws Exception {
        return new JSONObject().put("id", 7).put("inProgress", offen).put("startSoc", 22).put("endSoc", 85)
                .put("energyAdded", 27.78).put("energySource", quelle).put("cost", 10.84).put("electricityRate", 0.39)
                .put("preisQuelle", "standard");
    }

    @Test
    public void socSchaetzungWirdNachgerechnetPreisUndKostenBleiben() throws Exception {
        JSONObject o = ladung("soc_estimate", false);
        LadeAnzeige.nachrechnen(o, 43.2, 100);
        assertEquals(27.22, o.getDouble("energyAddedNachgerechnet"), 0.005);
        assertTrue(o.getBoolean("energyNachgerechnet"));
        assertEquals("akku_nennwert", o.getString("energyNachgerechnetGrund"));
        assertEquals("gespeicherte kWh unverändert", 27.78, o.getDouble("energyAdded"), 0);
        assertEquals(10.84, o.getDouble("cost"), 0);
        assertEquals("Rate bleibt die gespeicherte, keine Kosten ÷ neue kWh", 0.39, o.getDouble("electricityRate"), 0);
        assertEquals("standard", o.getString("preisQuelle"));
    }

    @Test
    public void gemesseneOderOffeneLadungUnveraendert() throws Exception {
        JSONObject m = ladung("metered_counter", false);
        LadeAnzeige.nachrechnen(m, 43.2, 100);
        assertFalse(m.has("energyAddedNachgerechnet"));
        JSONObject offen = ladung("soc_estimate", true);
        LadeAnzeige.nachrechnen(offen, 43.2, 100);
        assertFalse(offen.has("energyAddedNachgerechnet"));
    }

    @Test
    public void gleicherNennwertOderUnbekanntNichts() throws Exception {
        JSONObject o = ladung("soc_estimate", false);
        LadeAnzeige.nachrechnen(o, 44.1, 100);
        assertFalse("unter der Schwelle", o.has("energyAddedNachgerechnet"));
        JSONObject u = ladung("soc_estimate", false);
        LadeAnzeige.nachrechnen(u, 0, 100);
        assertFalse(u.has("energyAddedNachgerechnet"));
        JSONObject ohneSoc = ladung("soc_estimate", false).put("startSoc", JSONObject.NULL);
        LadeAnzeige.nachrechnen(ohneSoc, 43.2, 100);
        assertFalse(ohneSoc.has("energyAddedNachgerechnet"));
    }
}
