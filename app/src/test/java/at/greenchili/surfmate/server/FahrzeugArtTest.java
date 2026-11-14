package at.greenchili.surfmate.server;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import at.greenchili.surfmate.testutil.RepoWurzel;

/**
 * 0.1.43.5 (Paket A, 14.11.): /api/models/art und die Panoptes-Fahrzeugseite zeigen dieselben Bilder wie der
 * Launcher – Rendering der fünf CC-BY-Modelle in der Lackfarbe bzw. das freigestellte Foto – mit derselben
 * Namensnennung.
 */
public class FahrzeugArtTest {

    private static Path wurzel() {
        Path p = java.nio.file.Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("app/src/main/assets/web"))) p = RepoWurzel.hoch(p);
        if (p == null) throw new IllegalStateException("Repo nicht gefunden");
        return p;
    }

    private static String lies(String rel) throws Exception {
        return new String(Files.readAllBytes(wurzel().resolve(rel)), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static byte[] bytes(String rel) throws Exception {
        return Files.readAllBytes(wurzel().resolve(rel));
    }

    private static JSONObject manifest() throws Exception {
        return new JSONObject(lies("app/src/main/assets/web/shared/models/manifest.json"));
    }

    @Test
    public void gleicheBilderUndNennungWieImLauncher() throws Exception {
        JSONObject m = manifest();
        JSONObject katalog = new JSONObject(lies("panoptes-launcher/src/main/assets/fahrzeug/katalog.json"));
        JSONObject modelle = katalog.getJSONObject("modelle");
        JSONObject fotos = katalog.getJSONObject("fotos");
        JSONArray liste = m.getJSONArray("models");
        int render = 0, foto = 0;
        for (int i = 0; i < liste.length(); i++) {
            String id = liste.getJSONObject(i).getString("id");
            FahrzeugArt.Wahl w = FahrzeugArt.waehle(m, id);
            if (modelle.has(id)) {
                render++;
                assertEquals(id, FahrzeugArt.RENDER, w.art);
                assertEquals(id, modelle.getJSONObject(id).getString("namensnennung"), w.nennung);
                for (String e : new String[] {"_grund.webp", "_lack.webp"}) {
                    assertArrayEquals(id + e + ": dieselbe Ebene wie im Launcher",
                            bytes("panoptes-launcher/src/main/assets/fahrzeug/" + id + e),
                            bytes("app/src/main/assets/web/shared/models/bilder/" + id + e));
                }
            } else if (fotos.has(id)) {
                foto++;
                assertEquals(id, FahrzeugArt.FOTO, w.art);
                assertEquals(id, fotos.getJSONObject(id).getString("namensnennung"), w.nennung);
                byte[] k = bytes("app/src/main/assets/web/shared/models/fotos/" + id + ".webp");
                assertEquals(id + ": WebP mit Alpha (freigestellt)", "VP8X", new String(k, 12, 4, StandardCharsets.US_ASCII));
                assertTrue(id + ": Alpha-Flag", (k[20] & 0x10) != 0);
            } else {
                assertNull(id + ": kein eigenes Bild → altes Kern-Bild", w);
            }
        }
        assertEquals(5, render);
        assertEquals(7, foto);
        assertEquals("nur die Ebenen der fünf Modelle", 10,
                wurzel().resolve("app/src/main/assets/web/shared/models/bilder").toFile().listFiles().length);
    }

    @Test
    public void schreibweisenUndAliase() throws Exception {
        JSONObject m = manifest();
        assertEquals("seagull", FahrzeugArt.waehle(m, "Dolphin Surf").id);
        assertEquals("seagull", FahrzeugArt.waehle(m, "dolphin-surf").id);
        assertEquals("seal-u-dmi", FahrzeugArt.waehle(m, "BYD Seal U DM-i").id);
        assertEquals("seal-u-dmi", FahrzeugArt.waehle(m, "song_plus_dmi").id);
        assertEquals("seal-u", FahrzeugArt.waehle(m, "Seal U").id);
        assertNull(FahrzeugArt.waehle(m, ""));
        assertNull(FahrzeugArt.waehle(m, null));
        assertNull(FahrzeugArt.waehle(m, "unbekannt"));
        // Lizenzregel wie im Werkzeug: NC/ND und ohne Urheber nie
        JSONObject f = new JSONObject("{\"models\":[{\"id\":\"x\",\"photo\":{\"author\":\"A\",\"license\":\"CC-BY-NC-4.0\","
                + "\"sourceUrl\":\"https://commons.wikimedia.org/x\"}},{\"id\":\"y\",\"photo\":{\"author\":\"\","
                + "\"license\":\"CC0-1.0\",\"sourceUrl\":\"https://commons.wikimedia.org/y\"}},{\"id\":\"z\",\"photo\":{"
                + "\"author\":\"Z\",\"license\":\"CC0-1.0\",\"sourceUrl\":\"https://commons.wikimedia.org/z\"}}]}");
        assertNull(FahrzeugArt.waehle(f, "x"));
        assertNull(FahrzeugArt.waehle(f, "y"));
        assertEquals("Foto: Z · CC0", FahrzeugArt.waehle(f, "z").nennung);
    }

    @Test
    public void lackWieImLauncher() {
        // schwarzer Grund (Alpha 200) + voller Lackanteil × Rot = Rot, Alpha bleibt; ohne Lackanteil unverändert
        int[] g = {0xC8000000, 0xFF102030};
        int[] l = {0xFFFFFFFF, 0xFF000000};
        FahrzeugArt.mische(g, l, 0xFF0000);
        assertEquals(0xC8FF0000, g[0]);
        assertEquals(0xFF102030, g[1]);
        assertEquals(Integer.valueOf(0xC9D1D6), FahrzeugArt.hex("#c9d1d6"));
        assertNull(FahrzeugArt.hex("rot"));
        // dieselbe Rechnung wie FahrzeugBild.mische (linear, 4096 Stufen)
        String launcher;
        try {
            launcher = lies("panoptes-launcher/src/main/java/at/greenchili/panoptes/launcher/FahrzeugBild.kt");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertTrue(launcher.contains("val r = zuSrgb(LIN[(g shr 16) and 0xff] + d * lr)"));
    }

    @Test
    public void verdrahtetInServerUndFahrzeugseite() throws Exception {
        String api = lies("app/src/main/java/at/greenchili/surfmate/server/ModelsApiHandler.java");
        int eigen = api.indexOf("if (!eigenesBildSenden(out, queryParam(path, \"id\"), queryParam(path, \"farbe\"))) {");
        assertTrue("eigenes Bild vor dem alten Kern-Bild", eigen > 0 && eigen < api.indexOf("handleGetArt(out, queryParam(path, \"id\"));"));
        assertTrue(api.contains("static final String NENNUNG_KOPF = \"X-Panoptes-Namensnennung\";"));
        assertTrue(api.contains("UnifiedConfigManager.getVehicle().optString(\"color\", \"\")"));
        String js = lies("app/src/main/assets/web/shared/vehicle-control.js");
        assertTrue(js.contains("resp.headers.get('X-Panoptes-Namensnennung')"));
        assertTrue(js.contains("(farbe ? '&farbe=' + encodeURIComponent(farbe) : '')"));
        assertTrue(js.contains("apply(VC.artIdFromSelected(selected), selected && selected.color ? selected.color : '');"));
        String html = lies("app/src/main/assets/web/local/vehicle-control.html");
        assertTrue(html.contains("id=\"vcLiteHeroNennung\""));
        assertTrue(lies("app/src/main/assets/web/shared/vehicle-control.css").contains(".vc-lite-hero-nennung {"));
        // Bitte um ein 3D-Modell → Installationsseite (E2E_01434b)
        assertEquals("https://byd.serveone.at/panoptes/#installieren", manifest().getString("modellBitteUrl"));
        assertNotNull(FahrzeugArt.waehle(manifest(), "seal"));
    }
}
