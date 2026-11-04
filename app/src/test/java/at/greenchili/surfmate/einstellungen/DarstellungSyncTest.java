package at.greenchili.surfmate.einstellungen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 0.1.38.7 Darstellung-Sync (DARSTELLUNG_SYNC_BEFUND.md): "Für das Auto-Display" liegt im Kern und ist vom PC/Handy aus
 * änderbar, das Auto übernimmt es im Takt; "Nur für dieses Gerät" bleibt lokal.
 */
public class DarstellungSyncTest {

    private static Feld feld(String key) {
        for (Feld f : Katalog.felder(new Katalog.Kontext())) if (key.equals(f.key)) return f;
        return null;
    }

    private static String quelle(String rel) throws Exception {
        Path p = Paths.get(rel);
        if (!Files.exists(p)) p = Paths.get("..").resolve(rel);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    public void dreiAutoFelderSindImKernUndFernAenderbar() {
        for (String k : new String[]{"anzeige.darstellung", "anzeige.startAnsicht", "anzeige.thema",
                "anzeige.befehleAnsicht", "anzeige.fahrzeugAnsicht"}) {
            Feld f = feld(k);
            assertTrue(k, f != null);
            assertFalse(k + " darf aus der Ferne geändert werden", f.nurLesendExtern);
        }
        Feld t = feld("anzeige.thema");
        assertEquals("auto", t.standard);
        assertEquals(3, t.optionen.size());
        assertEquals("auto", t.optionen.get(0).wert);
        assertEquals("dark", t.optionen.get(1).wert);
        assertEquals("light", t.optionen.get(2).wert);
        Feld b = feld("anzeige.befehleAnsicht");
        assertEquals("automatisch", b.standard);
        assertEquals(3, b.optionen.size());
        Feld v = feld("anzeige.fahrzeugAnsicht");
        assertEquals("lite", v.standard);
        assertEquals(2, v.optionen.size());
    }

    @Test
    public void anzeigeStandLiefertGueltigeWerteOderStandards() throws Exception {
        JSONObject leer = at.greenchili.surfmate.server.EinstellungenApiHandler.anzeigeStand(new JSONObject());
        assertEquals("leicht", leer.getString("darstellung"));
        assertEquals("panoptes", leer.getString("startAnsicht"));
        assertEquals("auto", leer.getString("thema"));
        assertEquals("automatisch", leer.getString("befehleAnsicht"));
        assertEquals("lite", leer.getString("fahrzeugAnsicht"));
        JSONObject root = new JSONObject().put("anzeige", new JSONObject()
                .put("darstellung", "voll").put("startAnsicht", "cockpit").put("thema", "light")
                .put("befehleAnsicht", "kacheln").put("fahrzeugAnsicht", "immersive"));
        JSONObject a = at.greenchili.surfmate.server.EinstellungenApiHandler.anzeigeStand(root);
        assertEquals("voll", a.getString("darstellung"));
        assertEquals("cockpit", a.getString("startAnsicht"));
        assertEquals("light", a.getString("thema"));
        assertEquals("kacheln", a.getString("befehleAnsicht"));
        assertEquals("immersive", a.getString("fahrzeugAnsicht"));
        JSONObject muell = at.greenchili.surfmate.server.EinstellungenApiHandler.anzeigeStand(new JSONObject()
                .put("anzeige", new JSONObject().put("darstellung", "x").put("startAnsicht", "y").put("thema", "z")
                        .put("befehleAnsicht", "q").put("fahrzeugAnsicht", "w")));
        assertEquals("leicht", muell.getString("darstellung"));
        assertEquals("panoptes", muell.getString("startAnsicht"));
        assertEquals("auto", muell.getString("thema"));
        assertEquals("automatisch", muell.getString("befehleAnsicht"));
        assertEquals("lite", muell.getString("fahrzeugAnsicht"));
    }

    @Test
    public void autoTaktUndZielAuswahlSindImCode() throws Exception {
        String shell = quelle("app/src/main/assets/web/shared/app-shell.js");
        assertTrue(shell.contains("/api/einstellungen/anzeige"));
        assertTrue("alle 8 s", shell.contains("setInterval(anzeigeAbfragen, 8000)"));
        assertTrue("nur bei sichtbarer Seite", shell.contains("if (document.hidden || !window.fetch) return;"));
        assertTrue(shell.contains("function dstSetzen(v, ziel)"));
        assertTrue("am Handy/PC auch den Kern lesen", shell.contains("function dstKernLesen(allen)"));
        assertTrue("PC/Handy: Kern-Schreiben ändert den lokalen Spiegel nicht",
                shell.contains("if (dstDisplay()) { dstSpiegel(v); dstAnwenden(); }"));
        String home = quelle("app/src/main/assets/web/shared/home-layout.js");
        assertTrue(home.contains("function setzen(v, ziel)"));
        assertTrue(home.contains("function uebernehmen(v)"));
        assertTrue(home.contains("uebernehmen: uebernehmen"));
        assertTrue(shell.contains("azUebernehmen('befehleAnsicht', d.befehleAnsicht)"));
        assertTrue(shell.contains("azUebernehmen('fahrzeugAnsicht', d.fahrzeugAnsicht)"));
        assertTrue(shell.contains("window.PanoptesAnzeige = {"));
        String befehle = quelle("app/src/main/assets/web/shared/befehle-kacheln.js");
        assertTrue(befehle.contains("global.addEventListener('pn-befehleAnsicht'"));
        assertTrue("Display-Umschalter schreibt den Kern", befehle.contains("PanoptesAnzeige.setzenKern('befehleAnsicht', neu)"));
        String vc = quelle("app/src/main/assets/web/shared/vehicle-control.js");
        assertTrue(vc.contains("window.addEventListener('pn-fahrzeugAnsicht'"));
        assertTrue(vc.contains("switchViewMode: function(ausKern)"));
        assertTrue(vc.contains("PanoptesAnzeige.setzenKern('fahrzeugAnsicht'"));
        String theme = quelle("app/src/main/assets/web/shared/theme.js");
        assertTrue(theme.contains("kernAnwenden: function (v)"));
        assertTrue(theme.contains("kernSetzen: function (value)"));
        assertTrue(theme.contains("var FELD = 'anzeige.thema';"));
    }

    @Test
    public void seiteTrenntAutoDisplayVonDiesemGeraet() throws Exception {
        String html = quelle("app/src/main/assets/web/local/display.html");
        assertTrue(html.contains("id=\"ausAuto\""));
        assertTrue(html.contains("id=\"ausGeraet\" hidden"));
        assertTrue(html.contains("display.fuer_auto_titel"));
        assertTrue(html.contains("display.nur_geraet_titel"));
        assertTrue("Gerät-Block erscheint nur am Handy/PC", html.contains("if (!amAuto) {"));
        assertTrue(html.contains("darst.waehler(aa, 'auto')"));
        assertTrue(html.contains("darst.waehler(ab, 'geraet')"));
        assertTrue(html.contains("id=\"befehleWahlAuto\"") && html.contains("id=\"fahrzeugWahlAuto\""));
        assertTrue(html.contains("id=\"befehleWahl\"") && html.contains("id=\"fahrzeugWahl\""));
        // Sprache und UI-Merker bleiben "nur dieses Gerät" und sind so beschriftet (in allen fünf Sprachen)
        assertTrue(html.contains("id=\"nurGeraetSprache\"") && html.contains("display.nur_geraet_sprache"));
        for (String l : new String[]{"de", "en", "fr", "it", "nl"}) {
            assertTrue(l, quelle("app/src/main/assets/web/i18n/" + l + ".json").contains("\"nur_geraet_sprache\""));
        }
    }

    @Test
    public void launcherFolgtDemAutoThema() throws Exception {
        String api = quelle("app/src/main/java/at/greenchili/surfmate/server/LauncherApiHandler.java");
        assertTrue(api.contains("anzeige.thema dark|light) geht vor dem Web-Thema"));
        assertTrue(api.contains("modusPruefen(modusQuelle)"));
    }
}
