package at.greenchili.surfmate.surveillance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

/** 0.1.19 (ORTE_VERTRAG §5): die Geofence-Prüfung liest Ruhezonen aus der Ortsliste. */
public class SafeLocationOrteTest {

    private static JSONObject ort(String name, boolean ruhe, boolean zuhause) throws Exception {
        return new JSONObject().put("id", "o_" + name).put("name", name).put("lat", 47.8).put("lon", 13.05).put("r", 80)
                .put("eigenschaften", new JSONObject().put("privat", false).put("ruhezone", ruhe).put("zuhause", zuhause)
                        .put("favorit", false).put("tarif", JSONObject.NULL))
                .put("verweise", new JSONObject());
    }

    @Test
    public void ruhezoneUndZuhauseWieDerSpiegel() throws Exception {
        JSONObject orte = new JSONObject().put("migriert", true).put("liste", new JSONArray()
                .put(ort("Zuhause", true, true)).put(ort("Oma", false, true)).put(ort("Lidl", false, false)));
        List<SafeLocation> z = SafeLocationManager.ausOrten(orte, Collections.<SafeLocation>emptyList());
        assertEquals("Lidl ohne Ruhe/Zuhause zählt nicht", 2, z.size());
        assertEquals("Zuhause", z.get(0).getName());
        assertTrue(z.get(0).isEnabled());
        assertFalse("nur zuhause: benannt, unterdrückt nicht", z.get(1).isEnabled());
        assertEquals(80, z.get(0).getRadiusMeters());
    }

    @Test
    public void nichtMigriertNimmtDieDatei() throws Exception {
        assertNull(SafeLocationManager.ausOrten(new JSONObject().put("migriert", false), Collections.<SafeLocation>emptyList()));
        assertNull(SafeLocationManager.ausOrten(null, Collections.<SafeLocation>emptyList()));
    }
}
