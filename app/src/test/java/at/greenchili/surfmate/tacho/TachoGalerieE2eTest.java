package at.greenchili.surfmate.tacho;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/** 0.1.42 E2E-Nachbesserung Tacho-Galerie (E2E_0142.md): G1 Kartentext, G2 Minus auf der kW-Skala, G3 nur echte Dateien. */
public class TachoGalerieE2eTest {

    private static String lies(String r) throws Exception { return TachoFaehigkeitTest.lies(r).replace("\r\n", "\n"); }

    @Test
    public void g1KartenSagenKeinTachoStattAppStartFehler() throws Exception {
        String aus = lies("app/src/main/assets/web/shared/tacho-auswahl.js");
        assertFalse("Galerie nutzt den App-Start-Text nicht mehr", aus.contains("cluster_app_failed"));
        for (String code : new String[] {"single_os", "kein_projektionsdienst", "keine_shell_rechte", "warnleuchten_verdeckt"})
            assertTrue(code, aus.contains("'" + code + "'"));
        assertTrue(aus.contains("if (!e.waehlbar && e.grund) info.push(grundText(e.grund, true));"));
        assertTrue(aus.contains("return karte ? keinTacho : t('vehicle_control.tacho_auswahl_fehlgeschlagen', 'Das hat nicht geklappt.');"));
        assertTrue(lies("app/src/main/assets/web/local/display.html").contains("tacho-auswahl.js?v=3"));
        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject vc = new JSONObject(lies("app/src/main/assets/web/i18n/" + l + ".json")).getJSONObject("vehicle_control");
            String kein = vc.optString("tacho_auswahl_kein_tacho", "");
            assertTrue(l, kein.contains("Panoptes"));
            assertTrue(l, vc.optString("tacho_auswahl_fehlgeschlagen", "").length() > 3);
            assertFalse(l + ": nicht der App-Start-Text", kein.equals(vc.optString("cluster_app_failed")));
        }
    }

    @Test
    public void g2KwSkalaZeigtMinusBeiRekuperation() throws Exception {
        String gal = lies("app/src/main/assets/web/shared/tacho-galerie.js");
        assertTrue(gal.contains("H.text(c, (z < 0 ? '\\u2212' : '') + String(Math.abs(z)),"));
        assertFalse(gal.contains("H.text(c, String(Math.abs(z)),"));
        assertTrue(lies("app/src/main/assets/web/local/display.html").contains("tacho-galerie.js?v=2"));
    }

    @Test
    public void g3ImportNimmtNurEchteTachoDateien() throws Exception {
        assertNull("fremdes JSON", TachoEigen.pruefenText("{\"foo\":1}"));
        assertNull("leer", TachoEigen.pruefenText("{}"));
        JSONObject ohneKennung = TachoEigen.standard(); ohneKennung.remove("format");
        assertNull("ohne Kennung", TachoEigen.pruefenText(ohneKennung.toString()));
        JSONObject ohneVersion = TachoEigen.standard(); ohneVersion.remove("version");
        assertNull("ohne Version", TachoEigen.pruefenText(ohneVersion.toString()));
        assertNull("Version als Text", TachoEigen.pruefenText(TachoEigen.standard().put("version", "1").toString()));
        TachoEigen.Ergebnis gut = TachoEigen.pruefenText(TachoEigen.standard().put("basis", "tunnel").toString());
        assertNotNull("echte Datei", gut);
        assertEquals("tunnel", gut.eigen.getString("basis"));
        // G5 bleibt: unbekannte Schlüssel in einer echten Datei werden still verworfen
        TachoEigen.Ergebnis mit = TachoEigen.pruefenText(TachoEigen.standard().put("token", "x").toString());
        assertNotNull(mit);
        assertFalse(mit.eigen.has("token"));
        // Seite prüft ohne Kern genauso
        String ges = lies("app/src/main/assets/web/shared/tacho-gestalten.js");
        assertTrue(ges.contains("o.format !== R.FORMAT || (o.version !== R.VERSION && o.version !== R.VERSION_FREI)) return null;"));
        assertTrue(lies("app/src/main/assets/web/local/display.html").contains("tacho-gestalten.js?v=4"));
    }
}
