package at.greenchili.surfmate.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/** 0.1.26.1 „Gekoppelte Geräte“: echte Rechte als Schalter, Rest eingeklappt, Umbruch, alte Kopplung neu koppeln. */
public class GekoppelteGeraeteAnzeigeTest {

    @Test
    public void nurEchteRechteOffenJeRechtEinSchalter() throws Exception {
        String h = LiveQualitaetNurSitzungTest.lies("src/main/assets/web/local/system.html");
        int a = h.indexOf("handyBlock: function (g) {");
        String block = h.substring(a, h.indexOf("handyRechte: function (cb) {", a));
        assertTrue(block.contains("var hat = alle.filter(function (k) { return r.indexOf(k) >= 0; });"));
        assertTrue("übrige eingeklappt", block.contains("<details class=\"pn-rechte-mehr\">"));
        assertTrue("großer Schalter", block.contains("class=\"toggle-switch\"><input type=\"checkbox\" data-handy-recht="));
        assertFalse("kein Nicht-Umbrechen mehr", block.contains("white-space:nowrap"));
        assertTrue(h.contains(".pn-recht-text { flex: 1; min-width: 0; white-space: normal;"));
        assertTrue("Schalter mind. 48 px hoch", h.contains(".pn-recht { display: flex; align-items: center; gap: 12px; min-height: 48px;"));
        // Rückfragen bleiben
        assertTrue(h.contains("system.handy_kamera_confirm") && h.contains("system.handy_aufsperren_confirm"));
        // ältere Kopplung ohne art
        assertTrue(h.contains("(g.art === 'handy' ? self.handyBlock(g) : (!g.art ? self.altBlock(g) : ''))"));
        assertTrue(h.contains("data-neu-koppeln"));
        // 403 vom Server ehrlich melden (nur am Autobildschirm bzw. angemeldet)
        assertTrue(h.contains("r.status === 403 ? 'login'"));
        for (String l : new String[]{"de", "en", "fr", "it", "nl"}) {
            JSONObject sys = new JSONObject(LiveQualitaetNurSitzungTest.lies("src/main/assets/web/i18n/" + l + ".json")).getJSONObject("system");
            for (String k : new String[]{"handy_wort_klima", "handy_wort_kamera", "handy_wort_update", "handy_mehr", "handy_darf_nichts",
                    "handy_nur_login", "paired_alt", "paired_neu_koppeln"}) {
                assertFalse(l + " " + k, sys.optString(k, "").isEmpty());
            }
            for (String k : new String[]{"klima", "zusperren", "aufsperren", "push", "web", "kamera", "wallbox", "route", "update"}) {
                String w = sys.getString("handy_wort_" + k);
                assertFalse(l + " ein Wort: " + w, w.trim().contains(" "));
            }
        }
    }

    @Test
    public void aendernBleibtAmServerGesperrtFuerKopplungenUndWebSitzung() throws Exception {
        String e = LiveQualitaetNurSitzungTest.lies("src/main/java/at/greenchili/surfmate/server/EinstellungenApiHandler.java");
        int a = e.indexOf("pathOnly.equals(\"/api/kopplung/rechte\")");
        assertTrue(e.indexOf("if (AuthMiddleware.isExternRequest()) return verboten(out);", a) - a < 120);
    }
}
