package at.greenchili.surfmate.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 0.1.38.14 Vertrag Web-Aufnahmen-Player (events.js): „Zurück“ schließt den Player und führt zur Aufnahmen-Liste
 * (Verlaufseintrag), die App bleibt offen; fehlende Datei → Satz statt schwarzem Bild; Fahrsperre liegt über dem Player.
 */
public class AufnahmenPlayerZurueckTest {

    @Test
    public void zurueckFuehrtZurListeStattAusDerApp() throws Exception {
        String e = lies("src/main/assets/web/shared/events.js");
        int play = e.indexOf("    playVideo(recordingKey) {");
        String p = e.substring(play, e.indexOf("    closeVideo() {", play));
        assertTrue("Player = eigener Verlaufseintrag", p.contains("window.history.pushState({ player: 1 }"));
        assertTrue("Zurück schließt den Player", e.contains("window.addEventListener('popstate'")
                && e.contains("selfPop.closeVideo();"));
        assertTrue("Direktaufruf: Liste ohne id/file liegt darunter", e.contains("if (idParam || fileParam) this.listeImVerlauf();")
                && e.contains("u.searchParams.delete('file');"));
        String c = e.substring(e.indexOf("    closeVideo() {"));
        assertTrue("Schließen per Knopf baut den Eintrag ab", c.contains("window.history.back();"));
        // Android: WebView geht zurück, solange es Verlauf gibt; sonst erst moveTaskToBack
        String w = lies("src/main/java/at/greenchili/surfmate/ui/fragment/WebViewFragment.kt");
        assertTrue("0.1.39 Z1: Zurück über WebZurueck", w.contains("override fun handleOnBackPressed() { webZurueck() }")
                && w.contains("WebZurueck.Schritt.HINTERGRUND -> activity?.moveTaskToBack(true)"));
    }

    @Test
    public void fehlendeAufnahmeZeigtEinenSatz() throws Exception {
        String e = lies("src/main/assets/web/shared/events.js");
        assertTrue(e.contains("player.onerror = function () { if (player.getAttribute('src')) selfErr.aufnahmeFehlt(); };"));
        assertTrue("id unbekannt", e.contains("else this.aufnahmeFehlt();"));
        assertTrue("file weder fertig noch in Arbeit", e.contains("if (ausLink && await this.dateiFehlt(filename)) this.aufnahmeFehlt();"));
        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject ev = new JSONObject(lies("src/main/assets/web/i18n/" + l + ".json")).getJSONObject("events");
            assertFalse(l, ev.optString("aufnahme_fehlt").isEmpty());
        }
        assertTrue("Zwischenspeicher neu", lies("src/main/assets/web/local/events.html").contains("events.js?v=zurueck14c"));
        // ohne offenen Player: bleibender Hinweis über der Liste (BYD.core.showToast gibt es nicht)
        assertTrue(e.contains("h.id = 'aufnahmeFehltHinweis';") && e.contains("list.parentNode.insertBefore(h, list);"));
        assertFalse(e.contains("BYD.core.showToast(satz"));
    }

    @Test
    public void seitenUrlMitFileParameterIstKeinVideo() throws Exception {
        // E2E 0.1.38.14: events.html?file=x.mp4 wurde als video/mp4 ausgeliefert (url.endsWith) → schwarzes Bild
        String b = "http://127.0.0.1:8080";
        assertFalse("Seite, kein Video", at.greenchili.surfmate.ui.fragment.WebViewFragment.istVideoUrl(b + "/events.html?file=x.mp4"));
        assertFalse(at.greenchili.surfmate.ui.fragment.WebViewFragment.istVideoUrl(
                b + "/events.html?filter=parking&file=2026-11-10_12-00-00.mp4&parkingSessionId=a1"));
        assertFalse(at.greenchili.surfmate.ui.fragment.WebViewFragment.istVideoUrl(b + "/events.html#x.mp4"));
        assertTrue("Video", at.greenchili.surfmate.ui.fragment.WebViewFragment.istVideoUrl(b + "/media/x.mp4"));
        assertTrue(at.greenchili.surfmate.ui.fragment.WebViewFragment.istVideoUrl(b + "/video/x.mp4?t=1"));
        assertFalse(at.greenchili.surfmate.ui.fragment.WebViewFragment.istVideoUrl(null));
        String w = lies("src/main/java/at/greenchili/surfmate/ui/fragment/WebViewFragment.kt");
        assertTrue("Abfangen nutzt die Prüfung", w.contains("val istVideo = istVideoUrl(url)"));
        assertTrue(w.contains("if (istVideo) mime = \"video/mp4\""));
        assertFalse("nie die ganze URL samt ?query", w.contains("url.endsWith(\".mp4\")"));
    }

    @Test
    public void satzBeiFehlenderAufnahmePerIdUndFile() throws Exception {
        String e = lies("src/main/assets/web/shared/events.js");
        // ?id= ohne Treffer
        assertTrue(e.contains("if (found) this.playVideo(idParam);\n            else this.aufnahmeFehlt();")
                || e.contains("if (found) this.playVideo(idParam);\r\n            else this.aufnahmeFehlt();"));
        // ?file=: nicht in der Liste, nicht in Arbeit, Datei 404 → Satz; Inflight-Fehler (404/kein JSON) zählt als „nicht in Arbeit“
        assertTrue(e.contains("try { data = res.ok ? await res.json() : null; } catch (e) { data = null; }"));
        assertTrue(e.contains("if (ausLink && await this.dateiFehlt(filename)) this.aufnahmeFehlt();"));
        assertTrue(e.contains("return r.status === 404 || r.status === 400;"));
        // Video-Fehler im Player → Satz im Player
        assertTrue(e.contains("player.onerror = function () { if (player.getAttribute('src')) selfErr.aufnahmeFehlt(); };"));
        // der Satz ist sichtbar: im Player als Overlay, sonst als bleibender Hinweis über der Liste
        assertTrue(e.contains("d.className = 'aufnahme-fehlt';") && e.contains("h.className = 'aufnahme-fehlt-hinweis';"));
        String html = lies("src/main/assets/web/local/events.html");
        assertTrue(html.contains(".aufnahme-fehlt-hinweis {") && html.contains(".aufnahme-fehlt {"));
        assertTrue(html.contains("events.js?v=zurueck14c"));
        for (String l : new String[] {"de", "en", "fr", "it", "nl"}) {
            JSONObject ev = new JSONObject(lies("src/main/assets/web/i18n/" + l + ".json")).getJSONObject("events");
            assertFalse(l, ev.optString("aufnahme_fehlt").trim().isEmpty());
        }
        assertTrue(new JSONObject(lies("src/main/assets/web/i18n/de.json")).getJSONObject("events")
                .getString("aufnahme_fehlt").equals("Diese Aufnahme gibt es nicht mehr."));
    }

    @Test
    public void fahrsperreLiegtUeberDemPlayerAuchBeimDirektaufruf() throws Exception {
        String a = lies("src/main/assets/web/shared/anzeige.js");
        assertFalse("events.html fährt nicht frei", a.contains("'events.html'"));
        assertTrue("Seite nach Pfad, ?file= zählt nicht", a.contains("location.pathname.split('/').pop()"));
        assertTrue("beim Fahren Video anhalten", a.contains("if (fahrt && !FAHRT_SEITEN[seite()]) videosAnhalten();"));
        String css = lies("src/main/assets/web/shared/anzeige.css");
        assertTrue("über dem Player (z-index 10000)", css.contains("z-index: 10050"));
        String shell = lies("src/main/assets/web/shared/app-shell.js");
        assertTrue(shell.contains("anzeige.js?v=5") && shell.contains("anzeige.css?v=6"));
    }

    private static String lies(String rel) throws Exception {
        Path p = Paths.get(rel);
        if (!Files.exists(p)) p = Paths.get("app").resolve(rel);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
