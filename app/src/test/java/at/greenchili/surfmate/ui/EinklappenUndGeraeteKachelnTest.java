package at.greenchili.surfmate.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * 0.1.38.4 („gekoppelte Geräte als Icons, drücken öffnet nur das Gerät … alle Menüpunkte eingeklappt und nur
 * beim Draufdrücken aufgehen, systemweit“): ein Einklapp-Baustein für alle Seiten (standardmäßig zu, nur einer offen,
 * Sprungziel und Suche öffnen) und Geräte-Kacheln mit Detailansicht. REGEL AUFSPERREN bleibt unverändert.
 */
public class EinklappenUndGeraeteKachelnTest {

    private static String lies(String rel) throws Exception {
        Path p = Paths.get(rel);
        if (!Files.exists(p)) p = Paths.get("..").resolve(rel);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    @Test
    public void einBausteinStandardZuNurEinerOffen() throws Exception {
        String js = lies("app/src/main/assets/web/shared/app-collapse.js");
        assertTrue("standardmäßig eingeklappt", js.contains("setze(card, card.getAttribute('data-collapse-default') !== 'open', false);"));
        assertTrue("nur einer offen", js.contains("if (karten[i] !== card && karten[i].getAttribute('data-collapse') !== 'off') setze(karten[i], true, true);"));
        assertTrue("Sprungziel öffnet", js.contains("window.addEventListener('hashchange', zielAusAdresse);"));
        assertTrue("einziger Abschnitt offen", js.contains("function einzigeOffen()"));
        assertTrue(js.contains("window.PanoptesEinklappen = { oeffne: oeffne, schliesseAlle: schliesseAlle, neu: neu };"));
        assertFalse("kein Merken über Seitenwechsel", js.contains("localStorage"));
        assertTrue("app-shell lädt den Baustein auf jeder Seite", lies("app/src/main/assets/web/shared/app-shell.js")
                .contains("s.src = '../shared/app-collapse.js?v=2';"));
        assertTrue("Suche öffnet den Abschnitt", lies("app/src/main/assets/web/shared/system-overview.js")
                .contains("PanoptesEinklappen.oeffne(card, { scrollen: false });"));
    }

    @Test
    public void aussehenUnterAppsUndDisplayEinmal() throws Exception {
        String d = lies("app/src/main/assets/web/local/display.html");
        assertTrue(d.contains("<div class=\"card\" id=\"anzeige\">") && d.contains("id=\"themaWahl\""));
        assertTrue(d.contains("data-thema=\"dark\"") && d.contains("data-thema=\"light\"") && d.contains("data-thema=\"auto\""));
        assertTrue(d.contains("BYD.theme.set(b.getAttribute('data-thema'))"));
        // 0.1.38.7: Auto-Display (Kern) und dieses Gerät (lokal) getrennt, je mit denselben Bausteinen
        assertTrue(d.contains("darst.waehler(ab, 'geraet')") && d.contains("home.waehler(hw, 'geraet')"));
        assertTrue(d.contains("darst.waehler(aa, 'auto')") && d.contains("home.waehler(ha, 'auto')"));
        String s = lies("app/src/main/assets/web/local/system.html");
        assertTrue("Einstellungen: nur der Sprung", s.contains("href=\"display.html#anzeige\""));
        assertFalse("keine zweite Logik", s.contains("PanoptesDarstellung.waehler(") || s.contains("id=\"anzeigeWahl\""));
    }

    @Test
    public void geraeteAlsKachelnUndNurEinGeraetOffen() throws Exception {
        String s = lies("app/src/main/assets/web/local/system.html");
        assertTrue(s.contains("rows.innerHTML = self.kacheln(d.geraete);"));
        assertTrue(s.contains("data-geraet=\"") && s.contains("self.offenesGeraet = b.getAttribute('data-geraet'); self.liste();"));
        assertTrue("großer Zurück-Knopf", s.contains("data-geraete-zurueck=\"1\"") && s.contains(".pn-geraet-zurueck { width: 100%; min-height: 56px;"));
        assertTrue("nur das gewählte Gerät", s.contains("+ [offen].map(function (g) {"));
        for (String art : new String[] {"handy:", "iphone:", "cockpit:", "homeassistant:", "brille:", "dienst:"}) {
            assertTrue(art, s.contains(art + " '<"));
        }
        // REGEL AUFSPERREN: die Kacheln zeigen keine Rechte; Rechte nur in der unveränderten Detailansicht
        int a = s.indexOf("kacheln: function (geraete) {"), b = s.indexOf("offenesGeraet: null,", a);
        assertFalse(s.substring(a, b).contains("aufsperren"));
        assertTrue("wartende Kopplungs-Anfrage nie eingeklappt", s.contains("PanoptesEinklappen.oeffne('kopplung')"));
        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            String k = lies("app/src/main/assets/web/i18n/" + l + ".json");
            assertTrue(l, k.contains("\"paired_back\"") && k.contains("\"paired_art_cockpit\"") && k.contains("\"paired_art_brille\""));
        }
    }
}
