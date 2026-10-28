package at.greenchili.surfmate.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** 0.1.24 Meldung „Neues Gerät verbunden“ nach jeder Kopplung mit Code (Koordination 28.10.). */
public class NeueKopplungMeldungTest {

    static String lies(String pfad) throws Exception {
        File f = new File(pfad);
        if (!f.isFile()) f = new File("app/" + pfad);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void nameWirdEntschaerft() {
        assertEquals("?", NeueKopplungMeldung.anzeigeName(null));
        assertEquals("?", NeueKopplungMeldung.anzeigeName("  "));
        assertEquals("Pixel  Alf", NeueKopplungMeldung.anzeigeName("Pixel\n*Alf"));
        String lang = NeueKopplungMeldung.anzeigeName("x".repeat(100));
        assertEquals(41, lang.length());
        assertTrue(lang.endsWith("…"));
    }

    @Test
    public void codeKopplungMeldetUndTexteInAllenSprachen() throws Exception {
        String k = lies("src/main/java/at/greenchili/surfmate/server/AuthApiHandler.java");
        int erfolg = k.indexOf("log(\"Kopplung erfolgreich\");");
        int melden = k.indexOf("NeueKopplungMeldung.melden(name);");
        assertTrue("nach dem Erfolg, vor der Antwort", erfolg > 0 && melden > erfolg
                && melden < k.indexOf("HttpResponse.sendJson(out, response.toString());", erfolg));
        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject n = new JSONObject(lies("src/main/assets/server-i18n/" + l + ".json")).getJSONObject("notifications");
            assertTrue(l, n.getString("neue_kopplung_titel").contains("{0}"));
            String t = n.getString("neue_kopplung_text");
            assertFalse(l + ": kein ASCII-Apostroph (MessageFormat)", t.contains("'"));
            assertFalse(l + ": keine Fachwörter", t.toLowerCase(java.util.Locale.ROOT).contains("brücke")
                    || t.toLowerCase(java.util.Locale.ROOT).contains("bruecke"));
        }
    }
}
