package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** 0.1.20 Web-Sitzung der Handy-App, Stufe B: Sicherheits-Einstellungen nur am Auto oder mit PIN. */
public class WebHandyGuardTest {

    @Test
    public void sicherheitGesperrtRestOffen() {
        String[] gesperrt = {
                "/api/kopplungen", "/api/kopplung/code", "/api/kopplung/rechte", "/api/kopplung/widerrufen",
                "/api/einrichtung", "/api/einrichtung/schritt",
                "/api/telegram/token", "/api/telegram/owner/clear", "/api/telegram/clear", "/api/telegram/pin",
                "/api/bydcloud/setup", "/api/abrp/token", "/api/backup/export", "/api/backup/import",
                "/api/system/tailscale", "/api/system/tailscale/hostname", "/api/tailscale/https", "/api/wireguard",
                "/api/wireguard/config", "/api/notzugang/an", "/api/hotspot/enable", "/api/bruecke",
                "/api/appupdate/installieren", "/api/update/x", "/api/handy/v1/aktion",
                "/wireguard.html", "/remote-dev-view", "/api/dev-view/frame", "/api/x/../kopplungen"};
        for (String p : gesperrt) assertTrue(p, WebHandyGuard.blocks("GET", p));
        String[] offen = {
                "/", "/index.html", "/local/system.html", "/api/vehicle/state", "/api/vehicle/lock",
                "/api/einstellungen", "/api/settings/unified", "/api/telegram/status", "/api/telegram/preferences",
                "/api/bydcloud/status", "/api/camping", "/api/kopplungX"};
        for (String p : offen) assertFalse(p, WebHandyGuard.blocks("POST", p));
    }

    @Test
    public void unifiedNurUnkritischeAbschnitteSchreiben() {
        assertFalse(WebHandyGuard.blocksEinstellungsAbschnitt("GET", "/api/settings/unified", null));
        assertFalse(WebHandyGuard.blocksEinstellungsAbschnitt("POST", "/api/settings/unified", "{\"section\":\"recording\",\"data\":{}}"));
        assertTrue(WebHandyGuard.blocksEinstellungsAbschnitt("POST", "/api/settings/unified", "{\"section\":\"auth\",\"data\":{}}"));
        assertTrue(WebHandyGuard.blocksEinstellungsAbschnitt("POST", "/api/settings/unified", "{\"section\":\"Update\",\"data\":{}}"));
        assertTrue(WebHandyGuard.blocksEinstellungsAbschnitt("POST", "/api/settings/unified", "{\"section\":\"kopplungen\"}"));
        assertTrue("kaputtes JSON", WebHandyGuard.blocksEinstellungsAbschnitt("POST", "/api/settings/unified", "{"));
        assertFalse(WebHandyGuard.blocksEinstellungsAbschnitt("POST", "/api/camping", "{\"section\":\"auth\"}"));
    }

    @Test
    public void einstellungenGeheimesUndUpdatesNurLesend() throws Exception {
        java.util.List<at.greenchili.surfmate.einstellungen.Feld> felder = java.util.Arrays.asList(
                new at.greenchili.surfmate.einstellungen.Feld("update.token", at.greenchili.surfmate.einstellungen.Feld.Typ.GEHEIM),
                new at.greenchili.surfmate.einstellungen.Feld("update.modus", at.greenchili.surfmate.einstellungen.Feld.Typ.AUSWAHL),
                new at.greenchili.surfmate.einstellungen.Feld("recording.mode", at.greenchili.surfmate.einstellungen.Feld.Typ.AUSWAHL));
        JSONObject req = new JSONObject().put("werte", new JSONObject().put("update.token", "x").put("update.modus", "auto")
                .put("recording.mode", "loop"));
        java.util.List<String> g = WebHandyGuard.gesperrteFelder(felder, req);
        assertEquals(2, g.size());
        assertTrue(g.contains("update.token") && g.contains("update.modus"));
        assertEquals("nur der unkritische Wert bleibt", 1, req.getJSONObject("werte").length());
        assertTrue(req.getJSONObject("werte").has("recording.mode"));
    }

    public static String quelle(String rel) throws Exception {
        java.io.File f = new java.io.File("src/main/" + rel);
        if (!f.isFile()) f = new java.io.File("app/" + f.getPath());
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    public void verdrahtung() throws Exception {
        String h = quelle("java/at/greenchili/surfmate/server/HttpServer.java");
        int auth = h.indexOf("if (!authenticated) {");
        int guard = h.indexOf("if (AuthMiddleware.isHandyWebRequest() && (WebHandyGuard.blocks(method, pathOnly)");
        int route = h.indexOf("if (routeToHandlers(method, path, body, rangeHeader, ifNoneMatchHeader, out)) {");
        assertTrue("Sperre nach der Anmeldung und vor den Handlern", auth > 0 && guard > auth && route > guard);
        String q = quelle("java/at/greenchili/surfmate/server/QualitySettingsApiHandler.java");
        assertTrue(q.contains("for (String a : WebHandyGuard.GEHEIME_ABSCHNITTE) config.remove(a);"));
        assertTrue("JWT-Schlüssel nie in der Web-Sitzung", java.util.Arrays.asList(WebHandyGuard.GEHEIME_ABSCHNITTE).contains("auth"));
        String a = quelle("java/at/greenchili/surfmate/server/AuthApiHandler.java");
        assertTrue(a.contains("return handleWebSitzung(path, out);"));
        assertTrue(ExternGuard.HANDY_SCHREIBEN.contains("/api/handy/v1/websitzung"));
        String s = quelle("assets/web/shared/app-shell.js");
        assertTrue(s.contains("document.documentElement.setAttribute('data-android-embed', '1');"));
    }
}
