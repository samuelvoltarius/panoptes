package at.greenchili.surfmate.charging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Akkukapazität (0.1.10): Alfreds 44,1 (Nutzer-Altwert aus Overdrive) vs. Katalog Surf 43,2 / 30,08. */
public class KapazitaetPlausibelTest {

    private static JSONObject surfAusKatalog() throws Exception {
        JSONObject m = new JSONObject(new String(Files.readAllBytes(
                Paths.get("src/main/assets/web/shared/models/manifest.json")), StandardCharsets.UTF_8));
        JSONArray a = m.getJSONArray("models");
        for (int i = 0; i < a.length(); i++) {
            if ("seagull".equals(a.getJSONObject(i).getString("id"))) return a.getJSONObject(i);
        }
        throw new AssertionError("seagull fehlt im Katalog");
    }

    @Test
    public void alfredsFall() throws Exception {
        JSONObject surf = surfAusKatalog();
        JSONArray v = KapazitaetPlausibel.varianten(surf);
        assertEquals(2, v.length());
        JSONObject e = KapazitaetPlausibel.offen(44.1, "seagull", surf);
        assertEquals("akku_kapazitaet_pruefen", e.getString("id"));
        assertEquals(44.1, e.getDouble("eingetragen"), 1e-9);
        assertTrue(e.getJSONArray("katalog").toString().contains("43.2"));
        assertNull("Katalogwert passt", KapazitaetPlausibel.offen(43.2, "seagull", surf));
        assertNull("±1 %", KapazitaetPlausibel.offen(30.2, "seagull", surf));
        assertNull("ohne Varianten nichts zu prüfen", KapazitaetPlausibel.offen(44.1, "x", new JSONObject()));
        assertNull("nichts eingetragen", KapazitaetPlausibel.offen(0, "seagull", surf));
    }

    @Test
    public void quelleKlarBenannt() throws Exception {
        assertEquals("nutzer", KapazitaetPlausibel.quelle("user"));
        assertEquals("katalog", KapazitaetPlausibel.quelle("user_model"));
        assertEquals("auto", KapazitaetPlausibel.quelle("bms_ah"));
        assertEquals("unbekannt", KapazitaetPlausibel.quelle("unset"));
        assertFalse(KapazitaetPlausibel.passt(44.1, new JSONArray().put(new JSONObject().put("nominalKwh", 43.2))));
    }
}
