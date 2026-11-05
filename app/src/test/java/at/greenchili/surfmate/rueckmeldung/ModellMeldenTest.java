package at.greenchili.surfmate.rueckmeldung;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;

/**
 * 0.1.38.10 Modellabdeckung (PLAN_ENTFLECHTEN_0139, Priorität 4, Teil Kern): „Fehler melden“ schickt Modell und Firmware nur
 * mit ausdrücklicher Zustimmung (Häkchen, Standard aus) für die Liste „Was geht bei meinem Auto“ mit. Keine Fahrgestellnummer,
 * kein Ort. Ein Server, der das Feld nicht kennt, ignoriert es; ohne Häkchen ändert sich am Körper nichts.
 */
public class ModellMeldenTest {

    private static File basis() {
        return new File("src/main/assets").isDirectory() ? new File(".") : new File("app");
    }

    private static String lies(String pfad) throws Exception {
        return new String(Files.readAllBytes(new File(basis(), pfad).toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static HashSet<String> felder(JSONObject k) {
        HashSet<String> f = new HashSet<>();
        for (Iterator<String> it = k.keys(); it.hasNext(); ) f.add(it.next());
        return f;
    }

    @Test
    public void ohneHakenAendertSichAmKoerperNichts() throws Exception {
        JSONObject alt = FehlerMelden.koerper("Karte weiß", "0.1.38", 3, "Dolphin Surf", "de", null, null);
        JSONObject neu = FehlerMelden.koerper("Karte weiß", "0.1.38", 3, "Dolphin Surf", "de", null, null, false, "BYD-FW 1.2.3");
        assertEquals(alt.toString(), neu.toString());
        assertEquals(new HashSet<>(Arrays.asList("text", "version", "dilink", "modell", "sprache")), felder(neu));
        assertFalse(neu.has("abdeckung"));
        assertFalse("ohne Haken keine Firmware, auch wenn sie bekannt wäre", neu.has("firmware"));
    }

    @Test
    public void mitHakenGehenAbdeckungUndFirmwareMit() throws Exception {
        JSONObject k = FehlerMelden.koerper("Karte weiß", "0.1.38", 3, "Dolphin Surf", "de", null, null, true, "BYD-FW 1.2.3");
        assertTrue(k.getBoolean("abdeckung"));
        assertEquals("BYD-FW 1.2.3", k.getString("firmware"));
        assertEquals(new HashSet<>(Arrays.asList("text", "version", "dilink", "modell", "sprache", "abdeckung", "firmware")), felder(k));
        // Firmware unbekannt: die Zustimmung geht trotzdem mit, ein Feld „firmware“ erfindet niemand
        JSONObject ohneFw = FehlerMelden.koerper("x", "0.1.38", null, "Dolphin Surf", "de", null, null, true, null);
        assertTrue(ohneFw.getBoolean("abdeckung"));
        assertFalse(ohneFw.has("firmware"));
        // das Tacho-Ergebnis ist nie Teil der Liste
        JSONObject tacho = FehlerMelden.koerper("Ergebnis", "0.1.38", 3, "Dolphin Surf", "de", null, FehlerMelden.ART_TACHO, true, "FW");
        assertFalse(tacho.has("abdeckung"));
        assertFalse(tacho.has("firmware"));
    }

    @Test
    public void firmwareIstKurzUndNieEineFahrgestellnummer() {
        assertEquals("DiLink3.0_BYD_V2.1.5", AutoAngaben.firmware("DiLink3.0_BYD_V2.1.5", "ignoriert"));
        assertEquals("Fallback auf incremental", "20260101.1234", AutoAngaben.firmware("", "20260101.1234"));
        assertEquals("Sonderzeichen fallen weg", "BYD FW 1.2", AutoAngaben.firmware("BYD  FW <1.2>\n", null));
        assertEquals(AutoAngaben.FIRMWARE_MAX, AutoAngaben.firmware(new String(new char[200]).replace('\0', 'a'), null).length());
        assertNull("zu kurz", AutoAngaben.firmware("ab", null));
        assertNull("nichts", AutoAngaben.firmware(null, null));
        assertNull("17 Zeichen wie eine FIN", AutoAngaben.firmware("LGXC16DG2N0123456", null));
        assertNull("auch klein geschrieben", AutoAngaben.firmware("lgxc16dg2n0123456", null));
        assertEquals("kein Persönliches durch Sonderzeichen", "serial12345", AutoAngaben.firmware("serial:12345", null).replace(" ", ""));
    }

    @Test
    public void dialogUndHandlerVerdrahtung() throws Exception {
        String html = lies("src/main/assets/web/local/fehler-melden.html");
        assertTrue("Häkchen vorhanden", html.contains("id=\"fmAbdeckung\""));
        assertFalse("Standard AUS: kein checked", html.substring(html.indexOf("id=\"fmAbdeckung\""),
                html.indexOf("</label>", html.indexOf("id=\"fmAbdeckung\""))).contains("checked"));
        assertTrue("nur mit Häkchen im Körper", html.contains("if ($('fmAbdeckung').checked) body.abdeckung = true;"));
        assertTrue(html.contains("href=\"auto-funktionen.html\""));
        String h = lies("src/main/java/at/greenchili/surfmate/server/RueckmeldungApiHandler.java");
        assertTrue(h.contains("boolean abdeckung = req.optBoolean(\"abdeckung\", false);"));
        assertTrue("Firmware nur mit Häkchen gelesen", h.contains("abdeckung, abdeckung ? AutoAngaben.firmware() : null)"));
    }

    @Test
    public void hilfeSeiteImMenueUndInFuenfSprachen() throws Exception {
        String shell = lies("src/main/assets/web/shared/app-shell.js");
        assertTrue(shell.contains("href: 'auto-funktionen.html'") && shell.contains("shell.page_auto_funktionen"));
        String seite = lies("src/main/assets/web/local/auto-funktionen.html");
        assertFalse("die Seite sendet nichts", seite.contains("method: 'POST'") || seite.contains("method:'POST'"));
        assertTrue("Verweis auf die Homepage", seite.contains("id=\"afHomepage\""));
        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject d = new JSONObject(lies("src/main/assets/web/i18n/" + l + ".json"));
            JSONObject r = d.getJSONObject("rueckmeldung");
            for (String k : new String[] {"abd_haken", "abd_hilfe", "abd_firmware", "abd_link", "af_titel", "af_intro", "af_dein",
                    "af_modell", "af_firmware", "af_unbekannt", "af_stufen", "af_mach", "af_knopf", "af_homepage", "af_homepage_knopf"}) {
                assertFalse(l + " rueckmeldung." + k, r.optString(k, "").isEmpty());
            }
            assertFalse(l, d.getJSONObject("shell").optString("page_auto_funktionen", "").isEmpty());
            // der Hinweis zur Zustimmung sagt, was NICHT mitgeht
            assertTrue(l, r.getString("abd_hilfe").length() > 80);
        }
    }
}
