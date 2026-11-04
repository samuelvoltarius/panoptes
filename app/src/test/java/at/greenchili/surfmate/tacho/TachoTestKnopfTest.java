package at.greenchili.surfmate.tacho;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;

/**
 * 0.1.38.1: „Was kann mein Tacho?“ auch als Knopf auf der Display-Seite; keine Beta-/Probe-Texte mehr (REGEL 50+);
 * Rückmeldung weiter nur nach Vorschau und Klick auf „Jetzt senden“ (Einwilligung je Sendung).
 */
public class TachoTestKnopfTest {

    private static final String JS = "app/src/main/assets/web/shared/tacho-faehigkeit.js";

    @Test
    public void knopfAufDerDisplaySeite() throws IOException {
        String d = TachoFaehigkeitTest.lies("app/src/main/assets/web/local/display.html");
        assertTrue(d.contains("<section id=\"tachoFaehigkeit\""));
        assertTrue(d.contains("../shared/tacho-faehigkeit.js"));
    }

    @Test
    public void sendenNurNachVorschauUndEinwilligung() throws IOException {
        String js = TachoFaehigkeitTest.lies(JS);
        String senden = "fetch('/api/tacho/rueckmeldung'";
        assertEquals("genau ein Sendeweg", js.indexOf(senden), js.lastIndexOf(senden));
        int ja = js.indexOf("ja.onclick = function");
        assertTrue("Senden nur im Klick auf „Jetzt senden“", ja > 0 && js.indexOf(senden) > ja);
        assertTrue(js.contains("einwilligung: true, text: pre.textContent"));
        // „Ergebnis senden“ zeigt nur die Vorschau, schickt nichts
        int s = js.indexOf("senden.onclick = function");
        String vorschau = js.substring(s, js.indexOf("};", s));
        assertFalse(vorschau.contains("fetch("));
        assertTrue(vorschau.contains("pre.textContent = text"));
        // nur lesend: kein Start, keine Freigabe, nichts an den Tacho
        for (String verboten : new String[] {"/api/tacho/navi", "/api/tacho/auswahl", "/api/tacho/normal", "sendInfo",
                "AutoContainer", "flash", "Alarm"}) {
            assertFalse(verboten, js.contains(verboten));
        }
        assertTrue(js.contains("fetch('/api/tacho/faehigkeit', { cache: 'no-store' })"));
    }

    @Test
    public void keineBetaUndKeineProbeMehr() throws Exception {
        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject i = new JSONObject(TachoFaehigkeitTest.lies("app/src/main/assets/web/i18n/" + l + ".json"));
            JSONObject t = i.getJSONObject("testf");
            for (String k : new String[] {"tacho_title", "navi_title", "navi_na"}) {
                String v = t.getString(k).toLowerCase(java.util.Locale.ROOT);
                for (String w : new String[] {"beta", "bêta", "probe", "trial", "essai", "prova", "proef"}) {
                    assertFalse(l + " " + k + ": " + v, v.contains(w));
                }
            }
            for (String weg : new String[] {"navi_probe", "navi_frage", "navi_ja", "navi_nein"}) {
                assertFalse(l + " " + weg, t.has(weg));
            }
            JSONObject vc = i.getJSONObject("vehicle_control");
            for (String weg : new String[] {"cluster_app_probe", "cluster_app_probe_needed", "cluster_app_question",
                    "cluster_app_yes", "cluster_app_no", "cluster_app_watch"}) {
                assertFalse(l + " " + weg, vc.has(weg));
            }
        }
        String tf = TachoFaehigkeitTest.lies("app/src/main/assets/web/local/testfunktionen.html");
        assertFalse(tf.contains("(Beta)"));
        assertFalse(tf.contains("und die Probe"));
    }
}
