package at.greenchili.surfmate.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 0.1.38.10 (Alfreds Befund am Auto-Display): „Navigation sollte weiter vorne sein“ und „Ziel ins Auto“ ist im Auto selbst
 * unsinnig. Der Bereich „Navi & Fahrten“ steht an Platz 2 und öffnet die Navigation zuerst; am Auto-Display heißt der Knopf
 * „Starten“ (dieselbe Display-Erkennung wie die Shell, eine Quelle), sonst bleibt alles wie vorher.
 */
public class NaviVorneTest {

    private static File basis() {
        return new File("src/main/assets").isDirectory() ? new File(".") : new File("app");
    }

    private static String lies(String pfad) throws Exception {
        return new String(Files.readAllBytes(new File(basis(), pfad).toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static String sections() throws Exception {
        String js = lies("src/main/assets/web/shared/app-shell.js");
        return js.substring(js.indexOf("var SECTIONS = ["), js.indexOf("var TEILE = {"));
    }

    @Test
    public void bereichStehtAnPlatzZweiUndNaviIstSeineErsteSeite() throws Exception {
        String s = sections();
        int start = s.indexOf("{ id: 'start'"), drive = s.indexOf("{ id: 'drive'"), car = s.indexOf("{ id: 'car'");
        assertTrue("Reihenfolge Start, Navi & Fahrten, Steuerung", start >= 0 && start < drive && drive < car);
        String block = s.substring(drive, car);
        assertTrue(block.contains("label: 'Navi & Fahrten'"));
        assertTrue("ein Tipp auf den Bereich öffnet die Navigation", block.indexOf("href: 'navi.html'") < block.indexOf("href: 'trips.html'"));
        assertTrue("navi.html ist die erste Seite des Bereichs",
                block.indexOf("href: 'navi.html'") == block.indexOf("href: '"));
    }

    @Test
    public void eineDisplayErkennungFuerAlleSeiten() throws Exception {
        String shell = lies("src/main/assets/web/shared/app-shell.js");
        assertTrue("PanoptesShell.istDisplay ist die Erkennung der Shell", shell.contains("istDisplay: dstDisplay,"));
        String navi = lies("src/main/assets/web/local/navi.html");
        assertTrue(navi.contains("PanoptesShell.istDisplay()"));
        assertFalse("keine zweite Erkennung in navi.html", navi.contains("PanoptesDisplay/"));
        assertFalse(navi.contains("data-android-app"));
        // Handy und PC: die bisherigen Texte bleiben als Rückfall in den Schlüsseln navi.send usw.
        assertTrue(navi.contains("t('navi.send', 'Ziel ins Auto')"));
        assertTrue(navi.contains("t('navi.send_display', 'Starten')"));
        assertTrue(navi.contains("t('navi.send_stop_display', 'Mit Ladestopp starten')"));
        assertTrue(navi.contains("t('navi.confirm_send_display', 'Navigation zu „{name}“ starten?'"));
        assertTrue(navi.contains("t('navi.sent_display', 'Navigation läuft.')"));
        assertTrue("auch der Hinweis ohne Standort nennt den neuen Knopf", navi.contains("t('navi.no_car_display',"));
        // das Verhalten (Aufruf) bleibt
        assertTrue(navi.contains("post('/api/telenav/navigate', { name: name, lat: lat, lng: lng, replace: true })"));
    }

    @Test
    public void texteInFuenfSprachen() throws Exception {
        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject d = new JSONObject(lies("src/main/assets/web/i18n/" + l + ".json"));
            JSONObject navi = d.getJSONObject("navi");
            for (String k : new String[] {"send_display", "send_stop_display", "confirm_send_display", "sent_display", "send_fail_display", "no_car_display"}) {
                assertFalse(l + " navi." + k, navi.optString(k, "").isEmpty());
            }
            assertTrue(l, navi.getString("confirm_send_display").contains("{name}"));
            // am Auto-Display kein „ans Auto schicken“
            assertFalse(l, navi.getString("send_display").equalsIgnoreCase(navi.getString("send")));
            JSONObject shell = d.getJSONObject("shell");
            assertTrue(l + " Bereichsname nennt die Navigation", shell.getString("sec_fahrten").toLowerCase().contains("navi"));
            assertFalse(l, shell.getString("kurz_fahrten").isEmpty());
        }
        // Projektinhaber 05.11.: am Auto-Display „Starten“, Rückfrage „Navigation zu …“, Meldung „Navigation läuft.“; der zweite Knopf bleibt
        JSONObject de = new JSONObject(lies("src/main/assets/web/i18n/de.json")).getJSONObject("navi");
        assertEquals("Starten", de.getString("send_display"));
        assertEquals("Mit Ladestopp starten", de.getString("send_stop_display"));
        assertEquals("Navigation zu „{name}“ starten?", de.getString("confirm_send_display"));
        assertEquals("Navigation läuft.", de.getString("sent_display"));
        assertEquals("Navi & Fahrten", new JSONObject(lies("src/main/assets/web/i18n/de.json")).getJSONObject("shell").getString("sec_fahrten"));
    }
}
