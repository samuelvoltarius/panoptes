package at.greenchili.surfmate.tacho;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 0.1.38.11 (C1, Projektinhaber: „dass sie selbst die Größe auch einstellen können“): „Größe im Fahrerdisplay“ auf der
 * Tacho-Seite – eine Quelle (blindspot.clusterSizeProfile), sofort sichtbar, Zurück zum vorherigen, nur im Stand,
 * nur am Auto/mit Login.
 */
public class TachoGroesseTest {

    private final TachoGroesse.Speicher echtSpeicher = TachoGroesse.speicher;
    private final TachoGroesse.Anwender echtAnwender = TachoGroesse.anwender;

    @After
    public void zurueck() {
        TachoGroesse.speicher = echtSpeicher;
        TachoGroesse.anwender = echtAnwender;
    }

    @Test
    public void tippSpeichertUndZeigtSofort() throws Exception {
        List<String> folge = new ArrayList<>();
        TachoGroesse.speicher = p -> { folge.add("speichere " + p); return true; };
        TachoGroesse.anwender = () -> { folge.add("zeige"); return "gesendet"; };
        JSONObject r = TachoGroesse.setze(TachoGroesse.MITTEL);
        assertTrue(r.getBoolean("ok"));
        assertEquals(31, r.getInt("profil"));
        assertEquals("gesendet", r.getString("angewendet"));
        assertEquals("[speichere 31, zeige]", folge.toString());
        // „Zurück zum vorherigen“ ist derselbe Weg mit dem alten Wert, auch „Automatisch“
        assertTrue(TachoGroesse.setze(TachoGroesse.AUTOMATISCH).getBoolean("ok"));
        assertEquals("speichere 0", folge.get(2));
    }

    @Test
    public void nurDieVierGroessen() throws Exception {
        List<Integer> gespeichert = new ArrayList<>();
        TachoGroesse.speicher = p -> { gespeichert.add(p); return true; };
        TachoGroesse.anwender = () -> "gesendet";
        for (int p : new int[] {-1, 1, 16, 18, 35, 1000, 32}) {
            JSONObject r = TachoGroesse.setze(p);
            assertFalse("nie ein fremder Opcode: " + p, r.getBoolean("ok"));
            assertEquals("ungueltige_groesse", r.getString("grund"));
        }
        assertTrue(gespeichert.isEmpty());
        assertEquals("klein/mittel/groß = 8,8/10,25/12,3 Zoll", "29 31 30",
                TachoGroesse.KLEIN + " " + TachoGroesse.MITTEL + " " + TachoGroesse.GROSS);
    }

    @Test
    public void nichtGespeichertHeisstNichtGesendet() throws Exception {
        List<String> folge = new ArrayList<>();
        TachoGroesse.speicher = p -> false;
        TachoGroesse.anwender = () -> { folge.add("zeige"); return "gesendet"; };
        JSONObject r = TachoGroesse.setze(TachoGroesse.GROSS);
        assertFalse(r.getBoolean("ok"));
        assertTrue(folge.isEmpty());
    }

    private static String lies(String rel) throws Exception {
        File f = new File(rel);
        if (!f.exists()) f = new File("app/" + rel);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    public void nurImStandNurAmAutoEineQuelle() throws Exception {
        String h = lies("src/main/java/at/greenchili/surfmate/server/TachoApiHandler.java");
        int a = h.indexOf("p.equals(\"/api/tacho/groesse\") && (method.equals(\"PUT\")");
        String put = h.substring(a, h.indexOf("/api/tacho/normal", a));
        assertTrue("erst Stand prüfen, dann setzen", put.indexOf("bewegungsGrund()") < put.indexOf("TachoGroesse.setze("));
        assertTrue(put.contains("\"nur_im_stand\""));
        assertTrue("Handy-Web gesperrt", lies("src/main/java/at/greenchili/surfmate/server/WebHandyGuard.java").contains("\"/api/tacho/groesse\""));
        assertTrue("Kopplungen: nur Auswahl und normal", h.contains("if (!AUSWAHL.equals(p) && !\"/api/tacho/normal\".equals(p)) return false;"));
        String g = lies("src/main/java/at/greenchili/surfmate/tacho/TachoGroesse.java");
        assertTrue("eine Quelle", g.contains("\"blindspot\", Collections.<String, Object>singletonMap(\"clusterSizeProfile\", profil)"));
        String c = lies("src/main/java/at/greenchili/surfmate/surveillance/ClusterProjectionController.java");
        int s = c.indexOf("private void doCloseSequence(int epoch) {");
        assertTrue("Schließen liest die eben gewählte Größe frisch", c.indexOf("loadTuning();", s) < c.indexOf("final int restoreProfile =", s));
    }

    @Test
    public void seiteZeigtVierKnoepfeUndPasstSoZurueck() throws Exception {
        String d = lies("src/main/assets/web/local/display.html");
        int t = d.indexOf("<div class=\"card\" id=\"tacho\">");
        int g = d.indexOf("<section id=\"tachoGroesse\"");
        assertTrue("gut sichtbar auf der Tacho-Karte", t > 0 && g > t && g < d.indexOf("<section id=\"clusterAppApproval\">"));
        assertTrue(d.contains("id=\"tachoGroesseOk\"") && d.contains("id=\"tachoGroesseZurueck\""));
        assertTrue(d.contains("<script src=\"../shared/tacho-groesse.js?v=1\"></script>"));
        String js = lies("src/main/assets/web/shared/tacho-groesse.js");
        assertTrue(js.contains("{ p: 0, k: 'vehicle_control.tacho_groesse_auto'"));
        assertTrue(js.contains("fetch('/api/tacho/groesse', { method: 'PUT'"));
        String code = js.replaceAll("(?s)/\\*.*?\\*/", "");   // ohne Kommentare (die ES5-Regeln nennen ?. und ??)
        assertFalse("ES5", code.contains("=>") || code.contains("?.") || code.contains("??"));
        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject vc = new JSONObject(lies("src/main/assets/web/i18n/" + l + ".json")).getJSONObject("vehicle_control");
            for (String k : new String[] {"tacho_groesse_titel", "tacho_groesse_lead", "tacho_groesse_auto", "tacho_groesse_klein",
                    "tacho_groesse_mittel", "tacho_groesse_gross", "tacho_groesse_frage", "tacho_groesse_ok", "tacho_groesse_zurueck",
                    "tacho_groesse_zurueck_ok", "tacho_groesse_auto_text", "tacho_groesse_nur_stand", "tacho_groesse_fehler",
                    "tacho_groesse_vorschlag"}) {
                assertFalse(l + " " + k, vc.optString(k, "").isEmpty());
            }
            assertTrue(l + " Zoll stehen dabei", vc.getString("tacho_groesse_mittel").contains("10"));
        }
    }
}
