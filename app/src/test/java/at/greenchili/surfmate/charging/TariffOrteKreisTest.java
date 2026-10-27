package at.greenchili.surfmate.charging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** 0.1.19 (ORTE_VERTRAG §5): TariffManager nimmt den Kreis aus der Ortsliste, Preis vom Tarif. */
public class TariffOrteKreisTest {

    private static TariffProfile tarif(String id, double lat, double lng, int r, double dc) throws Exception {
        return new TariffProfile(new JSONObject().put("id", id).put("label", id).put("lat", lat).put("lng", lng)
                .put("radiusM", r).put("dcRate", dc).put("currency", "€"));
    }

    private static JSONObject orte(boolean migriert, double lat, double lon, int r) throws Exception {
        JSONObject ort = new JSONObject().put("id", "o_1").put("name", "Lidl").put("lat", lat).put("lon", lon).put("r", r)
                .put("eigenschaften", new JSONObject().put("privat", false).put("ruhezone", false).put("zuhause", false)
                        .put("favorit", false).put("tarif", new JSONObject().put("dcRate", 0.29)))
                .put("verweise", new JSONObject().put("tarif", "t1"));
        JSONArray spiegel = new JSONArray().put(new JSONObject().put("id", "t1").put("lat", 47.38).put("lon", 15.09).put("r", 50));
        return new JSONObject().put("migriert", migriert).put("liste", new JSONArray().put(ort))
                .put("spiegel", new JSONObject().put("tarife", spiegel));
    }

    @Test
    public void kreisKommtAusDerOrtsliste() throws Exception {
        // Ort über orte auf 400 m vergrößert; der Spiegel in den Tarif steht noch aus
        List<TariffProfile> s = Arrays.asList(tarif("t1", 47.38, 15.09, 50, 0.29));
        Map<String, double[]> k = TariffManager.kreiseAusOrten(orte(true, 47.38, 15.09, 400), s);
        double lat300m = 47.38 + 300 / 111_195.0;
        assertEquals("t1", TariffManager.resolveInCircleSnapshot(s, lat300m, 15.09, 1, false, k).getId());
        assertNull("ohne Ortsliste wie bisher: 50 m", TariffManager.resolveInCircleSnapshot(s, lat300m, 15.09, 1, false,
                Collections.<String, double[]>emptyMap()));
    }

    @Test
    public void nichtMigriertOderOhneAbschnittLeer() throws Exception {
        List<TariffProfile> s = Arrays.asList(tarif("t1", 47.38, 15.09, 50, 0.29));
        assertTrue(TariffManager.kreiseAusOrten(orte(false, 47.38, 15.09, 400), s).isEmpty());
        assertTrue(TariffManager.kreiseAusOrten(null, s).isEmpty());
    }

    @Test
    public void kleinsterKreisGewinntMitOrtsRadius() throws Exception {
        List<TariffProfile> s = Arrays.asList(tarif("t1", 47.38, 15.09, 50, 0.29), tarif("t2", 47.38, 15.09, 200, 0.39));
        // Ort von t1 auf 1000 m: jetzt ist t2 (200 m) der kleinere Kreis
        Map<String, double[]> k = TariffManager.kreiseAusOrten(orte(true, 47.38, 15.09, 1000), s);
        assertEquals("t2", TariffManager.resolveInCircleSnapshot(s, 47.38, 15.09, 1, false, k).getId());
    }
}
