package at.greenchili.surfmate.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.rueckmeldung.FehlerMelden;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 0.1.38.11 M1 („die Menüleiste fehlt“; Surf 05.11. 15:23 „altes Design“): Seiten-Ebene des Assistenten mit Kopfzeile
 * und Zurück-Weg, „Einrichtung unterbrechen“ immer sichtbar, nach Neuaufbau nie die alte Oberfläche, Inhalt nicht unter
 * der Leiste (721–768 px), WebView/Breite/Dichte in „Fehler melden“.
 */
public class AssistentEbeneM1Test {

    @Test
    public void nachNeuaufbauNieDieAlteOberflaeche() throws Exception {
        assertTrue(NeuaufbauZiel.INSTANCE.zurWeb(true, true));
        assertFalse("frischer Start beginnt ohnehin beim Startziel", NeuaufbauZiel.INSTANCE.zurWeb(false, true));
        assertFalse("Neuaufbau ohne alte Oberfläche bleibt, wo er ist", NeuaufbauZiel.INSTANCE.zurWeb(true, false));
        String a = lies("app/src/main/java/at/greenchili/surfmate/ui/MainActivity.kt");
        int nav = a.indexOf("setupNavigation(savedInstanceState)\n");
        if (nav < 0) nav = a.indexOf("setupNavigation(savedInstanceState)\r\n");
        int ziel = a.indexOf("NeuaufbauZiel.zurWeb(savedInstanceState != null, alteOberflaecheImStapel())");
        assertTrue("direkt nach dem Aufbau der Navigation", nav > 0 && ziel > nav && ziel - nav < 600);
        // 0.1.39 A1: dashboardFragment ist gelöscht, der Neuaufbau prüft jedes Ziel (AltDesignRausTest)
        assertTrue("verwaiste Ebene nach Neuaufbau weg", a.contains("AssistentAnbindung.verwaisteEbeneEntfernen(this)"));
    }

    @Test
    public void seitenEbeneHatKopfzeileZurueckTasteUndUnterbrechen() throws Exception {
        String e = lies("app/src/main/java/at/greenchili/surfmate/onboarding/schritte/AssistentAnbindung.kt");
        assertTrue(e.contains("R.string.einr_ebene_titel"));
        assertTrue(e.contains("R.string.einr_ebene_menue"));
        assertTrue(e.contains("R.string.einr_unterbrechen"));
        assertTrue("Zurück-Taste schließt die Ebene", e.contains("OnBackPressedCallback(true)")
                && e.contains("handleOnBackPressed() { schliesseWeb() }"));
        assertTrue("Rückruf beim Schließen weg", e.contains("zurueckRueckruf?.remove()"));
        // unterbrechen markiert nichts als erledigt: nur beenden() (= Assistent.beenden(false))
        int u = e.indexOf("override fun unterbrechen()");
        String un = e.substring(u, e.indexOf("\n    }", u));
        assertTrue(un.contains("beenden()"));
        assertFalse(un.contains("onboardingComplete"));
        assertTrue(un.contains("zeigeWebSeite(EINRICHTUNG_SEITE)"));
        assertTrue(e.contains("EINRICHTUNG_SEITE = \"/system.html#einrichtung\""));
        assertTrue("Karte gibt es", lies("app/src/main/assets/web/local/system.html").contains("id=\"einrichtung\""));
    }

    @Test
    public void assistentZeigtUnterbrechenImmer() throws Exception {
        String a = lies("app/src/main/java/at/greenchili/surfmate/onboarding/schritte/Assistent.kt");
        assertTrue(a.contains("knopf(texte.res(R.string.einr_unterbrechen)) { host.unterbrechen() }"));
        // nicht an „Später“/Pflicht gebunden: der Knopf sitzt im Kopf, nicht in knoepfeAktualisieren
        int k = a.indexOf("private fun knoepfeAktualisieren()");
        String kn = a.substring(k, a.indexOf("\n    }", k));
        assertFalse(kn.contains("unterbrechen"));
    }

    @Test
    public void texteInFuenfSprachen() throws Exception {
        for (String d : new String[]{"values", "values-de", "values-fr", "values-it", "values-nl"}) {
            String x = lies("app/src/main/res/" + d + "/strings_einrichtung.xml");
            for (String k : new String[]{"einr_ebene_titel", "einr_ebene_titel_ohne", "einr_ebene_menue", "einr_unterbrechen",
                    "einr_unterbrochen"}) {
                assertTrue(d + " " + k, x.contains("name=\"" + k + "\""));
            }
        }
        for (String l : new String[]{"de", "en", "fr", "it", "nl"}) {
            JSONObject j = new JSONObject(lies("app/src/main/assets/web/i18n/" + l + ".json"));
            assertFalse(l, j.getJSONObject("rueckmeldung").optString("auto_anzeige").isEmpty());
        }
    }

    @Test
    public void inhaltNichtUnterDerLeisteZwischen721Und768() throws Exception {
        String c = lies("app/src/main/assets/web/shared/app-shell.css");
        int m = c.indexOf("@media (min-width: 721px) and (max-width: 768px)");
        assertTrue(m > 0);
        assertTrue(c.substring(m, m + 300).contains("html[data-pn-shell] .main-content { margin-left: var(--sidebar-width); }"));
    }

    @Test
    public void fehlerMeldenNimmtNurGepruefteAnzeigeAngaben() throws Exception {
        JSONObject a = FehlerMelden.anzeige(new JSONObject().put("webview", 74).put("breite", 1280).put("dpr", 1.333333)
                .put("userAgent", "Mozilla/5.0 …").put("id", "x"));
        assertEquals(74, a.getInt("webview"));
        assertEquals(1280, a.getInt("breite"));
        assertEquals(1.33, a.getDouble("dpr"), 1e-9);
        assertEquals("nur die drei Felder", 3, a.length());
        assertNull(FehlerMelden.anzeige(new JSONObject().put("webview", 5000).put("breite", 5).put("dpr", 99)));
        assertNull(FehlerMelden.anzeige(null));
        JSONObject k = FehlerMelden.koerper("Leiste fehlt", "0.1.38.11", 3, "Seal U", "de", null, null, false, null, a);
        assertEquals(74, k.getJSONObject("anzeige").getInt("webview"));
        assertFalse("Tacho-Test ohne Anzeige", FehlerMelden.koerper("t", "0.1.38.11", 3, "Seal U", "de", null,
                FehlerMelden.ART_TACHO, false, null, a).has("anzeige"));
        JSONObject roh = FehlerMelden.koerper("Leiste fehlt", "0.1.38.11", 3, "Seal U", "de", null, null, false, null);
        FehlerMelden.mitAnzeige(roh, a);
        assertEquals(1280, roh.getJSONObject("anzeige").getInt("breite"));
        JSONObject tacho = FehlerMelden.koerper("t", "0.1.38.11", 3, "Seal U", "de", null, FehlerMelden.ART_TACHO);
        FehlerMelden.mitAnzeige(tacho, a);
        assertFalse(tacho.has("anzeige"));
        assertTrue(lies("app/src/main/java/at/greenchili/surfmate/server/RueckmeldungApiHandler.java")
                .contains("FehlerMelden.mitAnzeige(k, FehlerMelden.anzeige(req.optJSONObject(\"anzeige\")));"));
        String h = lies("app/src/main/assets/web/local/fehler-melden.html");
        assertTrue(h.contains("anzeige: anzeigeAngaben()"));
        assertTrue(h.contains("id=\"fmAnzeige\""));
    }

    private static String lies(String rel) throws Exception {
        Path p = Paths.get(rel);
        if (!Files.exists(p)) p = Paths.get("..").resolve(rel);
        if (!Files.exists(p) && rel.startsWith("app/")) p = Paths.get(rel.substring(4));
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
