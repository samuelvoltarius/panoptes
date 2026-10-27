package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 0.1.20 („die Farben stimmen nicht“): echte BYD-Lacke je Modell nur mit Quelle (byd.com/de), Hex als Näherung,
 * Modelle ohne belegte Liste ohne paints. Lesbar für Kopplungen über /api/ha/v1/zustand und /api/launcher/v1/summary.
 */
public class LackeTest {

    private static final Set<String> OHNE_BELEG = new HashSet<>(Arrays.asList("atto3", "han", "m6", "destroyer", "atto2", "seal-u"));

    @Test
    public void lackeNurMitQuelleUndSauberenWerten() throws Exception {
        JSONObject m = new JSONObject(lies("app/src/main/assets/web/shared/models/manifest.json"));
        assertTrue(m.optString("paintsHinweis").contains("Näherung"));
        JSONArray models = m.getJSONArray("models");
        int mit = 0;
        for (int i = 0; i < models.length(); i++) {
            JSONObject x = models.getJSONObject(i);
            JSONArray p = x.optJSONArray("paints");
            if (OHNE_BELEG.contains(x.getString("id"))) {
                assertNull(x.getString("id") + " hat keine belegte Liste", p);
                continue;
            }
            if (p == null) continue;
            mit++;
            assertTrue(x.getString("id"), x.getString("paintsQuelle").startsWith("https://www.byd.com/de/"));
            assertTrue(x.getString("paintsStand").matches("[0-9]{4}-[0-9]{2}"));
            Set<String> ids = new HashSet<>();
            for (int k = 0; k < p.length(); k++) {
                JSONObject l = p.getJSONObject(k);
                assertTrue(l.getString("id").matches("[a-z0-9-]{1,40}"));
                assertTrue("doppelt", ids.add(l.getString("id")));
                assertFalse(l.getString("name").isEmpty());
                assertTrue(l.isNull("hex") || l.getString("hex").matches("#[0-9A-F]{6}"));
            }
        }
        assertEquals(8, mit);
        JSONObject surf = null;
        for (int i = 0; i < models.length(); i++) if ("seagull".equals(models.getJSONObject(i).getString("id"))) surf = models.getJSONObject(i);
        assertEquals("BYD Dolphin Surf", surf.getString("nameEu"));
    }

    @Test
    public void lackFuerNurPassendeKennungDesModells() throws Exception {
        JSONObject m = new JSONObject(lies("app/src/main/assets/web/shared/models/manifest.json"));
        JSONObject l = ModelsApiHandler.lackFuer(m, "seagull", "lime-green");
        assertEquals("Lime Green", l.getString("name"));
        assertEquals("#A7AE3F", l.getString("hex"));
        assertNull("eigene Farbe", ModelsApiHandler.lackFuer(m, "seagull", ""));
        assertNull("fremder Lack", ModelsApiHandler.lackFuer(m, "seagull", "ruby-red"));
        assertNull("Modell ohne Liste", ModelsApiHandler.lackFuer(m, "han", "obsidian-black"));
        assertNull("Atto 2 nur teilweise belegt", ModelsApiHandler.lackFuer(m, "atto2", "skiing-white"));
        assertEquals("Tang laut Broschüre", "Mountain Grey", ModelsApiHandler.lackFuer(m, "tang", "mountain-grey").getString("name"));
        assertTrue("Weiß ohne verlässliches Hex", ModelsApiHandler.lackFuer(m, "atto3-evo", "skiing-white").isNull("hex"));
        // alte Farbwahl ohne paintId: nächster Ton, sonst nichts (bleibt eigene Farbe)
        JSONObject naechster = ModelsApiHandler.lackFuer(m, "seagull", "", "#A5AC40");
        assertEquals("lime-green", naechster.getString("id"));
        assertTrue(naechster.getBoolean("abgebildet"));
        assertNull("zu weit weg", ModelsApiHandler.lackFuer(m, "seagull", "", "#FF00FF"));
        assertNull("Modell ohne Liste", ModelsApiHandler.lackFuer(m, "han", "", "#1A1A1E"));
    }

    @Test
    public void verdrahtung() throws Exception {
        String ha = lies("app/src/main/java/at/greenchili/surfmate/server/HaApiHandler.java");
        assertTrue(ha.contains("g.put(\"modell\", aussehen != null ? aussehen.optString(\"name\") : JSONObject.NULL);"));
        assertTrue(ha.contains("g.put(\"lack\""));
        String models = lies("app/src/main/java/at/greenchili/surfmate/server/ModelsApiHandler.java");
        assertTrue(models.contains("patch.put(\"paintId\", paintId.matches(\"^[a-z0-9-]{1,40}$\") ? paintId : \"\");"));
        String vc = lies("app/src/main/assets/web/shared/vehicle-control.js");
        assertTrue(vc.contains("this._saveSelected({ color: hex, paintId: paintId || '' });"));
        assertTrue(vc.contains("this.colorPresets = this._lackListe(modelId) || this._beispielLacke;"));
        String ex = lies("app/src/main/java/at/greenchili/surfmate/server/ExternGuard.java");
        assertTrue("Lesezustand für Kopplungen", ex.contains("\"/api/ha/v1/zustand\"") && ex.contains("\"/api/launcher/v1/summary\""));
    }

    private static String lies(String rel) throws Exception {
        Path p = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (p != null) {
            for (Path c : new Path[] {p.resolve(rel), p.resolve(rel.replaceFirst("^app/", ""))}) {
                if (Files.isRegularFile(c)) return new String(Files.readAllBytes(c), "UTF-8");
            }
            p = p.getParent();
        }
        throw new java.io.FileNotFoundException(rel);
    }
}
