package at.greenchili.surfmate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import at.greenchili.surfmate.testutil.RepoWurzel;

/**
 * 0.1.43.4 (13.11.): Modelle ohne gutes 3D-Modell zeigen in Panoptes dasselbe Foto wie im Launcher (eine
 * Quelle: models/manifest.json „photo“, nicht „top“), klein mit Namensnennung und der Bitte um ein 3D-Modell.
 */
public class FahrzeugFotoPanoptesTest {

    private static Path wurzel() {
        Path p = java.nio.file.Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("app/src/main/assets/web"))) p = RepoWurzel.hoch(p);
        if (p == null) throw new IllegalStateException("Repo nicht gefunden");
        return p;
    }

    private static String lies(String rel) throws Exception {
        return new String(Files.readAllBytes(wurzel().resolve(rel)), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    public void fotosAusDerEinenQuelleUndGleicheDateiWieImLauncher() throws Exception {
        JSONObject m = new JSONObject(lies("app/src/main/assets/web/shared/models/manifest.json"));
        JSONArray liste = m.getJSONArray("models");
        Set<String> mitFoto = new HashSet<>();
        for (int i = 0; i < liste.length(); i++) {
            JSONObject e = liste.getJSONObject(i);
            JSONObject f = e.optJSONObject("photo");
            if (f == null || e.optBoolean("top", false)) continue;
            String id = e.getString("id");
            mitFoto.add(id);
            assertTrue(id, f.getString("license").equals("CC-BY-SA-4.0") || f.getString("license").equals("CC0-1.0")
                    || f.getString("license").equals("CC-BY-4.0"));
            assertFalse(id, f.getString("author").isEmpty());
            assertTrue(id, f.getString("sourceUrl").startsWith("https://commons.wikimedia.org/"));
            byte[] app = Files.readAllBytes(wurzel().resolve("app/src/main/assets/web/shared/models/fotos/" + id + ".webp"));
            byte[] launcher = Files.readAllBytes(wurzel().resolve("panoptes-launcher/src/main/assets/fahrzeug/" + id + "_foto.webp"));
            assertTrue(id + ": dasselbe Foto in Panoptes und Launcher", java.util.Arrays.equals(app, launcher));
        }
        assertEquals(7, mitFoto.size());
        java.io.File[] dateien = wurzel().resolve("app/src/main/assets/web/shared/models/fotos").toFile().listFiles();
        assertEquals("nur Fotos aus dem Manifest", mitFoto.size(), dateien.length);
        assertTrue(m.getString("modellBitteUrl").startsWith("https://"));
    }

    @Test
    public void modulEs5UndVerdrahtet() throws Exception {
        String f = lies("app/src/main/assets/web/shared/fahrzeug-foto.js");
        for (String v : new String[] {"=>", "let ", "const ", "`"}) assertFalse("ES5: " + v, f.contains(v));
        assertTrue(f.contains("if (!f || e.top === true || !f.author || !LIZENZ[f.license]) return null;"));
        assertTrue(f.contains("' · bearbeitet'") && f.contains("fahrzeug_foto.bitte") && f.contains("rel = 'noopener'"));
        // Seitenleiste/Launcher-Seite (ev-card-3d.js): Foto statt GLB; Fahrzeugseite (vehicle-control.js): Foto statt Laden
        String ev = lies("app/src/main/assets/web/shared/ev-card-3d.js");
        assertTrue(ev.contains("if (modelEntry && modelEntry.photo && modelEntry.top !== true) {"));
        assertTrue(ev.indexOf("F.zeige(self.canvas.parentNode, e, manifest)") > 0);
        String vc = lies("app/src/main/assets/web/shared/vehicle-control.js");
        int foto = vc.indexOf("window.PanoptesFahrzeugFoto.eintrag(this.manifest, modelId)");
        assertTrue(foto > 0 && foto < vc.indexOf("this.ModelStore.ensureLoaded(modelId, this.manifest"));
        assertTrue(lies("app/src/main/assets/web/local/vehicle-control.html").contains("chain.push('../shared/fahrzeug-foto.js?v=2');"));
        assertTrue(lies("app/src/main/assets/web/local/launcher-fahrzeug.html").contains("../shared/fahrzeug-foto.js?v=2"));
        String sw = lies("app/src/main/assets/web/local/sw.js");
        assertTrue(sw.contains("'surfmate-3d-v8'") && sw.contains("'/shared/fahrzeug-foto.js'"));
    }

    @Test
    public void bitteInFuenfSprachenDuForm() throws Exception {
        String[][] soll = {{"de", "Hast du ein 3D-Modell"}, {"en", "Do you have a 3D model"}, {"fr", "Tu as un modèle 3D"},
                {"it", "Hai un modello 3D"}, {"nl", "Heb je een 3D-model"}};
        for (String[] s : soll) {
            JSONObject j = new JSONObject(lies("app/src/main/assets/web/i18n/" + s[0] + ".json"));
            assertTrue(s[0], j.getJSONObject("fahrzeug_foto").getString("bitte").startsWith(s[1]));
        }
    }
}
