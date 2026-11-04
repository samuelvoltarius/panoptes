package at.greenchili.surfmate.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 0.1.38.8 Community-Karte in "Hilfe & Info": Knopf + lokal erzeugter QR-Code zur WhatsApp-Gruppe.
 * Der Link steht an genau einer Stelle, es gibt keinen fremden QR-Dienst, die Texte gibt es in fünf Sprachen.
 */
public class CommunityKarteTest {

    private static final String LINK = "https://chat.whatsapp.com/IQRtn9JViH4FhypAQsS3yK";

    private static Path wurzel() {
        Path p = Paths.get("src/main/assets/web");
        return Files.exists(p) ? p : Paths.get("app/src/main/assets/web");
    }

    private static String lesen(String rel) throws Exception {
        return new String(Files.readAllBytes(wurzel().resolve(rel)), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    public void linkStehtNurInDerKonstanten() throws Exception {
        assertTrue(lesen("shared/community-karte.js").contains("var LINK = '" + LINK + "';"));
        int treffer = 0;
        try (java.util.stream.Stream<Path> s = Files.walk(wurzel())) {
            for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                String n = p.getFileName().toString();
                if (!(n.endsWith(".html") || n.endsWith(".js") || n.endsWith(".json"))) continue;
                if (n.startsWith("qrcode-generator")) continue;
                String t = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                if (t.contains("IQRtn9JViH4FhypAQsS3yK")) treffer++;
            }
        }
        assertEquals("Link nur in shared/community-karte.js", 1, treffer);
    }

    @Test
    public void qrWirdLokalErzeugtOhneFremdenDienst() throws Exception {
        String js = lesen("shared/community-karte.js");
        assertTrue(js.contains("window.qrcode(0, 'M')") && js.contains("createSvgTag("));
        assertFalse(js.contains("qrserver") || js.contains("googleapis") || js.contains("<img"));
        assertTrue("weißer Rand (Ruhezone)", js.contains("background:#fff;padding:14px"));
        assertTrue(new File(wurzel().toFile(), "shared/vendor/qrcode-generator-1.4.4.js").exists());
    }

    @Test
    public void karteSitztAufHilfeSeitenMitSkripten() throws Exception {
        for (String seite : new String[]{"local/about.html", "local/fehler-melden.html"}) {
            String h = lesen(seite);
            assertTrue(seite, h.contains("id=\"community\"") && h.contains("data-community-link") && h.contains("data-community-qr"));
            assertTrue(seite, h.contains("communityGruppe.knopf") && h.contains("communityGruppe.fremd") && h.contains("communityGruppe.qr_hinweis"));
            int qr = h.indexOf("vendor/qrcode-generator-1.4.4.js");
            int karte = h.indexOf("shared/community-karte.js");
            assertTrue(seite + ": Bibliothek vor der Karte", qr > 0 && karte > qr);
            assertFalse(seite + ": kein fester Link im HTML", h.contains("chat.whatsapp.com"));
        }
    }

    @Test
    public void textInFuenfSprachenMitFremdDienstHinweis() throws Exception {
        for (String l : new String[]{"de", "en", "fr", "it", "nl"}) {
            JSONObject c = new JSONObject(lesen("i18n/" + l + ".json")).getJSONObject("communityGruppe");
            for (String k : new String[]{"titel", "satz", "knopf", "qr_hinweis", "fremd", "qr_alt"}) {
                assertTrue(l + "." + k, c.optString(k, "").length() > 3);
            }
            assertTrue(l + ": Hinweis nennt WhatsApp", c.getString("fremd").contains("WhatsApp"));
        }
    }
}
