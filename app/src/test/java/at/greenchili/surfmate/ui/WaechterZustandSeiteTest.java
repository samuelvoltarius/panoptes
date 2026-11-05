package at.greenchili.surfmate.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.surveillance.WaechterGrund;
import at.greenchili.surfmate.testutil.Quelltext;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Paths;

/**
 * 0.1.38.11 W1 (Befund Seal U: im Wächter-Menü „AN“, obwohl der Wächter nicht lief/nicht scharf war): Wächter-Übersicht
 * und Wächter-Menü zeigen EINE Zustandszeile aus status.grund (waechter-zustand.js, eine Stelle), das Abzeichen im
 * Menü zeigt denselben Zustand kurz statt des Schalters; ohne Status ein klarer Text („Dienst antwortet nicht“);
 * „Fehler melden“ schickt Wächter-Zeile und START_STUFE mit. Texte 5 Sprachen, ES5.
 */
public class WaechterZustandSeiteTest {

    private static String lies(String rel) throws Exception {
        return Quelltext.lies(Quelltext.aufloesen(Paths.get(rel))).replace("\r\n", "\n");
    }

    static final String WEB = "src/main/assets/web/";
    static final String[] SPRACHEN = {"de", "en", "fr", "it", "nl"};

    @Test
    public void eineStelleFuerDieZeile() throws Exception {
        String m = lies(WEB + "shared/waechter-zustand.js");
        assertTrue(m.contains("if (s.grund) return s.grund;"));
        for (String g : new String[]{WaechterGrund.RUHEZONE, WaechterGrund.GESTOPPT, WaechterGrund.STARTET,
                WaechterGrund.PAUSIERT, WaechterGrund.PASST_AUF, WaechterGrund.ZUENDUNG_AN, WaechterGrund.BEREIT}) {
            assertTrue(g, m.contains("g === '" + g + "'"));
        }
        assertTrue("ohne Status klarer Text", m.contains("}).catch(function () { fertig(unbekannt(t), null); });"));
        String code = m.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
        for (String v : new String[]{"?.", "??", "=>", "`", "const ", "let "}) assertFalse("ES5: " + v, code.contains(v));
    }

    @Test
    public void uebersichtUndMenueNutzenSie() throws Exception {
        String w = lies(WEB + "local/waechter.html");
        assertTrue(w.contains("waechter-zustand.js?v=1"));
        assertTrue(w.contains("var z = PanoptesWaechterZustand.zeile(s, t);"));
        assertTrue(w.contains("}).catch(function () { zustandUnbekannt(); });"));
        assertFalse("keine zweite Kopie der Regel", w.contains("function grundAus"));
        assertFalse("Stopp nicht als zweite Zeile neben „Bereit“", w.contains("t('guard.stopped_because'"));

        String sh = lies(WEB + "local/surveillance.html");
        assertTrue(sh.contains("waechter-zustand.js?v=1"));
        assertTrue(sh.indexOf("waechter-zustand.js") < sh.indexOf("surveillance.js?v="));
        assertTrue(sh.contains("surveillance.js?v=survtoggle14"));
        assertTrue(sh.contains("id=\"survZustand\""));
        assertFalse("Abzeichen nicht mehr vom i18n-Text „Aus“ überschrieben", sh.contains("id=\"survStatusBadge\" data-i18n"));
        String sj = lies(WEB + "shared/surveillance.js");
        assertTrue(sj.contains("PanoptesWaechterZustand.laden(t, function (z) {"));
        assertTrue("Abzeichen = echter Zustand, nicht der Schalter", sj.contains("badge.textContent = z.kurz;"));
        assertFalse(sj.contains("badge.textContent = this.config.enabled ?"));
        // ES5 auch im neuen Block der (älteren) surveillance.js
        String blk = sj.substring(sj.indexOf("    zustandZeigen() {"), sj.indexOf("    updateUI() {"));
        for (String v : new String[]{"const ", "let ", "=>", "`", "?.", "??"}) assertFalse("ES5 zustandZeigen: " + v, blk.contains(v));
    }

    @Test
    public void kernSchicktGrund() throws Exception {
        String h = lies("src/main/java/at/greenchili/surfmate/server/SurveillanceApiHandler.java");
        assertTrue(h.contains("statusJson.put(\"grund\", at.greenchili.surfmate.surveillance.WaechterGrund.grund(statusMap, zuendung));"));
        assertTrue(h.contains("statusJson.put(\"zuendungAn\""));
        assertTrue(h.contains("statusJson.put(\"verriegelt\""));
    }

    @Test
    public void fehlerMeldenSchicktWaechterZeileUndStartStufe() throws Exception {
        String r = lies("src/main/java/at/greenchili/surfmate/server/RueckmeldungApiHandler.java");
        assertTrue(r.contains("String text = waechterZeile()"));
        assertTrue("geschwärzt", r.contains("FehlerMelden.nachschwaerzen(at.greenchili.surfmate.surveillance.WaechterGrund.zeile("));
        JSONArray l = at.greenchili.surfmate.genai.GenAiContextTestZugang.waehle(
                "2026-11-05 01:00:00 [INFO] START_STUFE stufe=WAECHTER\nirgendwas normales\n"
                        + "2026-11-05 01:00:05 [INFO] START_STUFE stufe=REST zustand=STARTET_NICHT\n");
        assertEquals(2, l.length());
        assertTrue(l.getString(0).contains("START_STUFE stufe=WAECHTER"));
    }

    @Test
    public void texteInFuenfSprachen() throws Exception {
        for (String sp : SPRACHEN) {
            JSONObject d = new JSONObject(lies(WEB + "i18n/" + sp + ".json"));
            JSONObject g = d.getJSONObject("guard");
            for (String k : new String[]{"st_zuendung", "st_offen", "st_unbekannt", "st_unbekannt_kurz", "st_unbekannt_hinweis", "st_ready",
                    "st_active", "st_quiet", "st_off", "st_starting", "st_paused", "stopped_because"}) {
                assertFalse(sp + " " + k, g.getString(k).trim().isEmpty());
            }
            JSONObject sh = d.getJSONObject("shell");
            for (String k : new String[]{"pill_guard_safe", "pill_guard_paused", "pill_guard_starting", "pill_guard_on",
                    "pill_guard_ready", "pill_guard_off"}) {
                assertFalse(sp + " " + k, sh.getString(k).trim().isEmpty());
            }
        }
    }
}
