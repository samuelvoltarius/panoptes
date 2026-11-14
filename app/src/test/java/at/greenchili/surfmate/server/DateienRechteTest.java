package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.auth.Kopplungen;
import at.greenchili.surfmate.storage.Dateiablage;
import at.greenchili.surfmate.testutil.Quelltext;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Dateien Handy ↔ Auto, Rechte: Recht „dateien“ ist Standard aus, nie automatisch (auch nicht für Bestand), nur am
 * Auto oder mit Login vergeben; Kopplungen und Web-Sitzung kommen ohne das Recht nicht an {@code /api/dateien}.
 */
public class DateienRechteTest {

    private static final String[] PFADE = {"/api/dateien/start", "/api/dateien/teil", "/api/dateien/fertig",
            "/api/dateien/abbrechen", "/api/dateien/holen", "/api/dateien/loeschen"};

    private static Kopplungen neu(JSONArray start) {
        final JSONArray[] g = {start};
        return new Kopplungen(new Kopplungen.Speicher() {
            public JSONArray lade() { return g[0]; }
            public boolean speichere(JSONArray x) { g[0] = x; return true; }
        });
    }

    @Test
    public void rechtGibtEsAberNieAutomatisch() throws Exception {
        assertTrue(Kopplungen.RECHTE.contains("dateien"));
        assertEquals("dateien", Kopplungen.RECHT_DATEIEN);
        Kopplungen k = neu(new JSONArray());
        String erstes = k.anlegen("Handy", "handy", 1000L);
        String zweites = k.anlegen("Handy 2", "handy", 2000L);
        assertFalse("neue Kopplung", k.hatRecht(erstes, "dateien"));
        assertFalse(k.hatRecht(zweites, "dateien"));
        // Bestand: alte Kopplungen ohne Rechte-Stand (Umstellung gibt route/update, nie dateien)
        JSONArray alt = new JSONArray()
                .put(new JSONObject().put("id", "alt1").put("art", "handy").put("name", "A").put("angelegt", 1L)
                        .put("zuletzt", System.currentTimeMillis()))
                .put(new JSONObject().put("id", "alt2").put("art", "handy").put("name", "B").put("angelegt", 2L)
                        .put("zuletzt", System.currentTimeMillis()).put("rechte", new JSONArray().put("klima")));
        Kopplungen b = neu(alt);
        assertFalse(b.rechte("alt1").contains("dateien"));
        assertFalse(b.rechte("alt2").contains("dateien"));
        // Cockpit/HA/Dienst können es gar nicht tragen
        String cockpit = k.anlegen("Cockpit", "cockpit", 3000L);
        k.rechteSetzen(cockpit, Arrays.asList("dateien", "kamera"));
        assertFalse(k.hatRecht(cockpit, "dateien"));
        // Der Besitzer schaltet es ein
        List<String> r = new ArrayList<>(k.rechte(erstes));
        r.add("dateien");
        assertTrue(k.rechteSetzen(erstes, r));
        assertTrue(k.hatRecht(erstes, "dateien"));
    }

    @Test
    public void vergebenNurAmAutoOderMitLogin() throws Exception {
        // /api/kopplung/rechte: Kopplungen 403 (Handler), Web-Sitzung gesperrt (WebHandyGuard)
        String e = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/EinstellungenApiHandler.java"));
        int i = e.indexOf("pathOnly.equals(\"/api/kopplung/rechte\")");
        assertTrue(i > 0);
        assertTrue(e.substring(i, i + 300).contains("if (AuthMiddleware.isExternRequest()) return verboten(out);"));
        assertTrue(WebHandyGuard.blocks("POST", "/api/kopplung/rechte"));
        assertFalse(ExternGuard.erlaubt("POST", "/api/kopplung/rechte"));
        // Telegram/Display-Zusatzrechte bei der Kopplung kennen nur aufsperren, wallbox, update
        String a = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/auth/KopplungsAnfragen.java"));
        assertFalse(a.contains("RECHT_DATEIEN"));
        String k = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/auth/Kopplungen.java"));
        int s = k.indexOf("HANDY_STANDARD = ");
        assertFalse("nicht im Standard", k.substring(s, s + 250).contains("DATEIEN"));
        // system.html: eigener Schalter mit Rückfrage
        String sys = Quelltext.lies(Paths.get("src/main/assets/web/local/system.html"));
        assertTrue(sys.contains("'protokoll', 'dateien'"));   // 0.1.37: danach folgt 'tacho'
        assertTrue(sys.contains("'protokoll', 'dateien', 'tacho']"));
        assertTrue(sys.contains("cb.getAttribute('data-handy-recht') === 'dateien' && cb.checked"));
    }

    @Test
    public void kopplungBrauchtDasRecht() {
        assertTrue(ExternGuard.handyErlaubt("GET", "/api/dateien", "handy"));
        for (String p : PFADE) {
            assertTrue(p, ExternGuard.handyErlaubt("POST", p, "handy"));
            assertFalse("kein GET für Aktionen " + p, ExternGuard.handyErlaubt("GET", p, "handy"));
            assertEquals("dateien", ExternGuard.noetigesRecht(p));
            for (String art : new String[] {"cockpit", "homeassistant", "dienst", null}) {
                assertTrue(art + " " + p, ExternGuard.blocks("POST", p, art));
            }
        }
        assertEquals("dateien", ExternGuard.noetigesRecht("/api/dateien"));
        assertFalse(ExternGuard.rechtReicht("dateien", r -> false));
        assertFalse("kamera reicht nicht", ExternGuard.rechtReicht("dateien", "kamera"::equals));
        assertTrue(ExternGuard.rechtReicht("dateien", "dateien"::equals));
        // keine Pfad-Tricks, kein DELETE, keine fremden Unterpfade
        assertFalse(ExternGuard.handyErlaubt("POST", "/api/dateien/../kopplung/rechte", "handy"));
        assertFalse(ExternGuard.handyErlaubt("DELETE", "/api/dateien/loeschen", "handy"));
        assertFalse(ExternGuard.handyErlaubt("POST", "/api/dateien/alle-loeschen", "handy"));
        assertFalse("allgemeine Erlaubnisliste bleibt zu", ExternGuard.erlaubt("GET", "/api/dateien"));
        JSONObject antwort = ExternGuard.rechtFehltAntwort("dateien");
        assertEquals("dateien", antwort.optString("recht"));
        assertEquals("nicht_erlaubt", antwort.optString("grund"));
    }

    @Test
    public void webSitzungBrauchtDasRecht() {
        assertTrue(WebHandyGuard.blocks("GET", "/api/dateien"));
        assertTrue(WebHandyGuard.blocks("POST", "/api/dateien/holen"));
        assertFalse(WebHandyGuard.dateienErlaubt("/api/dateien/holen", false));
        assertTrue(WebHandyGuard.dateienErlaubt("/api/dateien/holen", true));
        assertTrue(WebHandyGuard.dateienErlaubt("/api/dateien?x=1", true));
        assertFalse(WebHandyGuard.dateienErlaubt("/api/dateien/../kopplung/rechte", true));
        assertFalse(WebHandyGuard.dateienErlaubt("/api/kopplung/rechte", true));
        assertFalse(WebHandyGuard.dateienErlaubt("/api/dateienx", true));
    }

    @Test
    public void httpServerPrueftVorDemHandler() throws Exception {
        String hs = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/HttpServer.java"));
        int guard = hs.indexOf("WebHandyGuard.dateienErlaubt(pathOnly");
        int route = hs.indexOf("if (routeToHandlers(");
        int extern = hs.indexOf("String noetig = ExternGuard.noetigesRecht(pathOnly);");
        int handler = hs.indexOf("DateienApiHandler.handle(method, path, body, out)");
        assertTrue(guard > 0 && extern > 0 && guard < route && extern < route && handler > 0);
        assertTrue(hs.contains("pathOnly.equals(\"/dateien.html\")"));
        // Body-Grenze: ein Teil (Base64) passt unter die allgemeine 16-MB-Grenze
        assertTrue(Dateiablage.TEIL_BYTES * 4 / 3 + 1024 < HttpServer.maxBodyBytesForRequestLine("POST /api/dateien/teil HTTP/1.1"));
    }

    @Test
    public void handlerEndeZuEnde() throws Exception {
        Path tmp = Files.createTempDirectory("dateien-api");
        DateienApiHandler.setzeAblage(new Dateiablage(tmp.resolve("d").toFile()));
        try {
            JSONObject s = json(post("/api/dateien/start", new JSONObject().put("name", "notiz.txt").put("groesse", 5)));
            assertTrue(s.toString(), s.getBoolean("ok"));
            String id = s.getString("id");
            JSONObject t = json(post("/api/dateien/teil", new JSONObject().put("id", id).put("nr", 0)
                    .put("daten", java.util.Base64.getEncoder().encodeToString("hallo".getBytes("UTF-8")))));
            assertTrue(t.toString(), t.getBoolean("ok"));
            JSONObject f = json(post("/api/dateien/fertig", new JSONObject().put("id", id)));
            assertEquals("notiz.txt", f.getString("name"));
            String h = post("/api/dateien/holen", new JSONObject().put("name", "notiz.txt"));
            assertTrue(h.startsWith("HTTP/1.1 200"));
            assertTrue(h.contains("Content-Type: application/octet-stream"));
            assertTrue(h.contains("Content-Disposition: attachment"));
            assertTrue(h.contains("X-Content-Type-Options: nosniff"));
            assertTrue(h.endsWith("hallo"));
            assertTrue(json(get("/api/dateien")).getJSONArray("dateien").length() == 1);
            JSONObject apk = json(post("/api/dateien/start", new JSONObject().put("name", "x.apk").put("groesse", 5)));
            assertEquals("apk_gesperrt", apk.getString("grund"));
            JSONObject gross = json(post("/api/dateien/start", new JSONObject().put("name", "x.bin")
                    .put("groesse", Dateiablage.MAX_BYTES + 1)));
            assertEquals("zu_gross", gross.getString("grund"));
            JSONObject raus = json(post("/api/dateien/holen", new JSONObject().put("name", "../d/notiz.txt")));
            assertEquals("nicht_gefunden", raus.getString("grund"));
            assertTrue(json(post("/api/dateien/loeschen", new JSONObject().put("name", "notiz.txt"))).getBoolean("ok"));
            assertEquals(0, json(get("/api/dateien")).getJSONArray("dateien").length());
            assertTrue(get("/api/dateien/holen").startsWith("HTTP/1.1 405"));
        } finally {
            DateienApiHandler.setzeAblage(null);
        }
    }

    @Test
    public void jedeAktionImBefehlsLog() throws Exception {
        String h = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/DateienApiHandler.java"));
        for (String e : new String[] {"dateien.hochladen", "dateien.hochgeladen", "dateien.holen", "dateien.loeschen"}) {
            assertTrue(e, h.contains("BefehlsLog.ereignis(\"" + e + "\""));
        }
        // Schreibende Aktionen sind POST und laufen damit durch BefehlsLog.beginne (Pfad ohne Werte, ohne Rumpf)
        for (String p : PFADE) assertEquals(p, SchreibProtokoll.pfadOhneWerte("POST", p));
        assertFalse("nie den Dateinamen ins Log", h.contains("ereignis(\"dateien.\" + "));
        assertFalse("nichts ausführen", h.contains("Runtime.getRuntime()") || h.contains("pm install"));
    }

    private static String post(String pfad, JSONObject body) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(DateienApiHandler.handle("POST", pfad, body.toString(), out));
        return out.toString("UTF-8");
    }

    private static String get(String pfad) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(DateienApiHandler.handle("GET", pfad, null, out));
        return out.toString("UTF-8");
    }

    private static JSONObject json(String http) throws Exception {
        return new JSONObject(http.substring(http.indexOf("\r\n\r\n") + 4));
    }
}
