package at.greenchili.surfmate.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 0.1.38.10 (G4): navi.html startet am Auto-Display die Panoptes-Führung über AndroidBridge.openNavigation, mit Band
 * „Führung läuft“ (Zur Führung / Beenden). Ohne Brücke oder ohne Führung wie bisher das Werksnavi mit kurzem Hinweis,
 * kein toter Knopf. Handy und PC unverändert.
 */
public class NaviFuehrungTest {

    private static String lies(String pfad) throws Exception {
        File b = new File("src/main/assets").isDirectory() ? new File(".") : new File("app");
        return new String(Files.readAllBytes(new File(b, pfad).toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    public void nurAmDisplayMitBrueckeUndVerfuegbar() throws Exception {
        String n = lies("src/main/assets/web/local/navi.html");
        assertTrue(n.contains("function fuehrungBruecke() { return imAuto() && !!window.AndroidBridge && typeof window.AndroidBridge.openNavigation === 'function'; }"));
        assertTrue(n.contains("fuehrungAn: function () { return fuehrungBruecke() && fuehrungStand().verfuegbar === true; },"));
        assertTrue("Handy/PC: bisheriger Weg bleibt", n.contains("if (b) b.onclick = function () { self.send(self.dest.name, self.dest.lat, self.dest.lng, b); };"));
    }

    @Test
    public void auftragOhneRouteUndOhnePosition() throws Exception {
        String n = lies("src/main/assets/web/local/navi.html");
        int a = n.indexOf("fuehre: function (btn) {");
        String f = n.substring(a, n.indexOf("ersteEtappe: function ()", a));
        assertTrue(f.contains("a = { ziel: { name: d.name, lat: d.lat, lon: d.lng }, etappen: [] }"));
        assertTrue(f.contains("a.etappen.push({ art: 'zwischenziel', name: s.name, lat: s.lat, lon: s.lng });"));
        assertFalse("keine Autoposition", f.contains("car()") || f.contains("gps"));
        assertFalse("keine Route", f.contains("geometrie") || f.contains("route"));
        assertTrue(f.contains("window.AndroidBridge.openNavigation(JSON.stringify(a))"));
        // Rückfall: Hinweis + Werksnavi (mit Rückfrage in send)
        assertTrue(f.indexOf("if (r === 'ok') return;") < f.indexOf("toast(t('navi.fue_rueckfall'"));
        assertTrue(f.indexOf("toast(t('navi.fue_rueckfall'") < f.indexOf("this.send(e0.name, e0.lat, e0.lng, btn);"));
    }

    @Test
    public void bandFuehrungLaeuft() throws Exception {
        String n = lies("src/main/assets/web/local/navi.html");
        assertTrue(n.contains("<div class=\"n-uw n-fue\" id=\"nFue\" hidden></div>"));
        assertTrue(n.contains("data-fu=\"zur\"") && n.contains("data-fu=\"ende\""));
        assertTrue("zurück ohne neuen Auftrag", n.contains("r = window.AndroidBridge.openNavigation('');"));
        assertTrue("Beenden mit Rückfrage", n.indexOf("t('navi.fue_beenden_frage'") < n.indexOf("window.AndroidBridge.fuehrungBeenden();"));
        assertTrue("pausiert nicht abfragen", n.contains("if (document.hidden || window.pnPausiert) return;"));
        assertTrue(n.contains("FU.start();"));
    }

    @Test
    public void werksnaviBekommtDieErsteEtappeAuchOhnePlan() throws Exception {
        // E2E B (05.11.): mit Zwischenstopp St. Pölten ging ohne Plan-Etappen das Endziel ans Werksnavi
        String n = lies("src/main/assets/web/local/navi.html");
        int a = n.indexOf("ersteEtappe: function () {");
        String f = n.substring(a, n.indexOf("wireSend: function ()", a));
        int plan = f.indexOf("if (et.length && et[0].art !== 'ziel')");
        int via = f.indexOf("if (this.via.length) return { name: this.via[0].name, lat: this.via[0].lat, lng: this.via[0].lng };");
        int ziel = f.indexOf("return { name: this.dest.name, lat: this.dest.lat, lng: this.dest.lng };");
        assertTrue("Reihenfolge: Planer-Etappe, eigener Zwischenstopp, Ziel", plan > 0 && plan < via && via < ziel);
    }

    @Test
    public void eigeneRueckfrageStattConfirm() throws Exception {
        String n = lies("src/main/assets/web/local/navi.html");
        assertTrue(n.contains("BYD.utils.confirmDialog({ title: titel, body: text, confirmLabel: ja,"));
        int f = n.indexOf("function frage(titel, text, ja, gefahr) {");
        int roh = n.indexOf("window.confirm(");
        assertTrue("window.confirm nur noch als Rückfall in frage()", roh > f && roh < n.indexOf("}", n.indexOf("return Promise.resolve(window.confirm(text));")));
        assertTrue(n.indexOf("window.confirm(", roh + 1) < 0);
        int s = n.indexOf("send: function (name, lat, lng, btn) {");
        assertTrue("erst fragen, dann senden", n.indexOf("frage(", s) < n.indexOf("post('/api/telenav/navigate'", s));
        assertTrue(n.contains("frage(t('navi.fue_beenden_titel'"));
        assertTrue(n.contains("frage(t('navi.fg_weg_titel'"));
        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject navi = new JSONObject(lies("src/main/assets/web/i18n/" + l + ".json")).getJSONObject("navi");
            for (String k : new String[] {"fg_weg_titel", "fg_weg_ja", "fue_beenden_titel", "frage_werk_titel", "frage_send_titel", "frage_send_ja"}) {
                assertFalse(l + " navi." + k, navi.optString(k, "").isEmpty());
            }
            assertFalse(l, new JSONObject(lies("src/main/assets/web/i18n/" + l + ".json")).getJSONObject("common").optString("cancel", "").isEmpty());
        }
    }

    @Test
    public void texteInFuenfSprachen() throws Exception {
        String[] keys = {"fue_band", "fue_ziel", "fue_zur", "fue_beenden", "fue_beenden_frage", "fue_nicht", "fue_rueckfall",
                "fue_hinweis", "send_werk_display"};
        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject navi = new JSONObject(lies("src/main/assets/web/i18n/" + l + ".json")).getJSONObject("navi");
            for (String k : keys) assertFalse(l + " navi." + k, navi.optString(k, "").isEmpty());
            assertTrue(l, navi.getString("fue_ziel").contains("{name}"));
            assertTrue(l + " Hinweis nennt den Knopf", navi.getString("fue_hinweis").contains(navi.getString("send_display")));
        }
    }
}
