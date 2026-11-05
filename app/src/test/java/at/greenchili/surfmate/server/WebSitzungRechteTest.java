package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.auth.Kopplungen;
import at.greenchili.surfmate.auth.PcZugang;
import at.greenchili.surfmate.handy.WebAuthn;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 0.1.38.10 Rechte-Matrix der Web-Sitzung ({@link WebSitzungRechte}): je geregeltem Pfad ohne Recht 403, mit Recht
 * durch; Fenster/Fahrzeug-Einstellungen/System nie; außen Sicht-/Hörbares nur mit Rückfrage; Kofferraum nur mit
 * Passkey (echter Software-Authenticator); jeder schreibende Fahrzeug-Pfad im Quelltext ist eingeordnet.
 */
public class WebSitzungRechteTest {

    final JSONArray[] gespeichert = {new JSONArray()};
    Kopplungen k;
    WebSitzungRechte.Passkey vorher;

    @Before
    public void auf() {
        vorher = WebSitzungRechte.passkey;
        k = new Kopplungen(new Kopplungen.Speicher() {
            public JSONArray lade() { return gespeichert[0]; }
            public boolean speichere(JSONArray g) { gespeichert[0] = g; return true; }
        });
    }

    @After
    public void ab() {
        WebSitzungRechte.passkey = vorher;
        PcZugang.quelle = PcZugang.KONFIG;
        PcZugang.leeren();
    }

    String handy(String... rechte) {
        String id = k.anlegen("Handy", Kopplungen.ART_HANDY, 1_000L);
        assertTrue(k.rechteSetzen(id, Arrays.asList(rechte)));
        return id;
    }

    static String body(String pfad) {
        if ("/api/vehicle/lights".equals(pfad)) return "{\"target\":\"dayTimeLight\",\"enable\":true}";
        if ("/api/audio/avas-tone".equals(pfad)) return "{\"pattern\":1}";
        return "{}";
    }

    final Map<String, String> mitRecht = new java.util.HashMap<>();

    String mitRecht(String r) {
        String id = mitRecht.get(r);
        if (id == null) mitRecht.put(r, id = handy(Kopplungen.RECHT_WEB, r));
        return id;
    }

    @Test
    public void matrixOhneRechtZuMitRechtDurch() throws Exception {
        String ohne = handy(Kopplungen.RECHT_WEB);
        String alle = handy(Kopplungen.RECHTE.toArray(new String[0]));
        for (Map.Entry<String, String> e : WebSitzungRechte.PFADE.entrySet()) {
            String p = e.getKey(), r = e.getValue();
            JSONObject a = WebSitzungRechte.pruefe("POST", p, body(p), ohne, k, 1L);
            if (WebSitzungRechte.FREI.equals(r)) {
                assertNull(p, a);
                continue;
            }
            assertNotNull("ohne Recht zu: " + p, a);
            assertEquals(p, 403, a.getInt("status"));
            assertEquals(p, WebSitzungRechte.NIE.equals(r) ? "nie_web" : "recht_fehlt", a.getString("grund"));
            if (WebSitzungRechte.NIE.equals(r)) {
                // nie heißt nie: auch mit allen Rechten
                assertEquals(p, "nie_web", WebSitzungRechte.pruefe("POST", p, "{\"bestaetigt\":true}", alle, k, 1L).getString("grund"));
                continue;
            }
            assertEquals(p, r, a.getString("recht"));
            String mit = mitRecht(r);
            JSONObject b = WebSitzungRechte.pruefe("POST", p, body(p), mit, k, 1L);
            if (WebSitzungRechte.AUSSEN.contains(p)) {
                assertEquals(p, 409, b.getInt("status"));
                assertEquals(p, "aktion_bestaetigen", b.getString("grund"));
                assertNull(p, WebSitzungRechte.pruefe("POST", p, new JSONObject(body(p)).put("bestaetigt", true).toString(), mit, k, 1L));
            } else if (WebSitzungRechte.PASSKEY.contains(p)) {
                assertEquals(p, "passkey_noetig", b.getString("grund"));
            } else {
                assertNull("mit Recht durch: " + p, b);
            }
        }
    }

    @Test
    public void entscheideDerKoordination() {
        assertEquals(WebSitzungRechte.NIE, WebSitzungRechte.noetig("POST", "/api/vehicle/window"));
        assertEquals(Kopplungen.RECHT_SIGNAL, WebSitzungRechte.noetig("POST", "/api/vehicle/speak"));
        assertEquals(Kopplungen.RECHT_SIGNAL, WebSitzungRechte.noetig("POST", "/api/vehicle/message"));
        assertEquals(Kopplungen.RECHT_SIGNAL, WebSitzungRechte.noetig("POST", "/api/vehicle/lights"));
        assertTrue(WebSitzungRechte.AUSSEN.contains("/api/vehicle/lights"));
        assertEquals(Kopplungen.RECHT_LADEN, WebSitzungRechte.noetig("POST", "/api/vehicle/start-charging"));
        assertEquals(Kopplungen.RECHT_LADEN, WebSitzungRechte.noetig("POST", "/api/vehicle/charging-schedule"));
        assertEquals(Kopplungen.RECHT_AUFSPERREN, WebSitzungRechte.noetig("POST", "/api/vehicle/trunk"));
        assertTrue(WebSitzungRechte.PASSKEY.contains("/api/vehicle/trunk"));
        // laden ist ein eigenes Recht, Standard aus, nur am Display vergeben
        assertTrue(Kopplungen.RECHTE.contains(Kopplungen.RECHT_LADEN));
        assertTrue(Kopplungen.NUR_DISPLAY_RECHTE.contains(Kopplungen.RECHT_LADEN));
        assertFalse(k.hatRecht(k.anlegen("Neu", Kopplungen.ART_HANDY, 1L), Kopplungen.RECHT_LADEN));
    }

    @Test
    public void lesenUndAndereBereicheRegeltDieTabelleNicht() {
        assertNull(WebSitzungRechte.noetig("GET", "/api/vehicle/state"));
        assertNull(WebSitzungRechte.noetig("GET", "/api/vehicle/charging-schedule"));
        assertNull(WebSitzungRechte.noetig("HEAD", "/api/vehicle/window"));
        assertNull(WebSitzungRechte.noetig("POST", "/api/einstellungen"));
        assertNull(WebSitzungRechte.noetig("POST", "/api/laden/nachfrage"));
        assertNull(WebSitzungRechte.noetig("POST", "/api/campingplatz"));
        // Abfrage und Query-Teil ändern nichts
        assertEquals(Kopplungen.RECHT_ZUSPERREN, WebSitzungRechte.noetig("POST", "/api/vehicle/lock?x=1"));
        assertEquals(Kopplungen.RECHT_ZUSPERREN, WebSitzungRechte.noetig("POST", "/api/vehicle/lock/"));
    }

    @Test
    public void nichtEingeordneteFahrzeugPfadeSindZu() {
        for (String p : new String[]{"/api/vehicle/unlock", "/api/vehicle/hazard", "/api/vehicle/neu", "/api/vehicle/",
                "/api/vehicle", "/api/vehicle/lock/../window", "/api/vehicle//lock", "/api/navi/neu", "/api/camping/neu"}) {
            assertEquals(p, WebSitzungRechte.NIE, WebSitzungRechte.noetig("POST", p));
            assertEquals(p, WebSitzungRechte.NIE, WebSitzungRechte.noetig("DELETE", p));
        }
    }

    @Test
    public void ausschaltenOhneRueckfrage() throws Exception {
        String h = handy(Kopplungen.RECHT_WEB, Kopplungen.RECHT_SIGNAL);
        assertNull(WebSitzungRechte.pruefe("POST", "/api/audio/avas-tone", "{\"stop\":true}", h, k, 1L));
        assertNull(WebSitzungRechte.pruefe("POST", "/api/vehicle/lights", "{\"target\":\"dayTimeLight\",\"enable\":false}", h, k, 1L));
        assertEquals(409, WebSitzungRechte.pruefe("POST", "/api/vehicle/lights", "{\"target\":\"ambientColour\",\"value\":3}", h, k, 1L).getInt("status"));
        // stop-audio braucht kein Recht
        assertNull(WebSitzungRechte.pruefe("POST", "/api/vehicle/stop-audio", "{}", handy(Kopplungen.RECHT_WEB), k, 1L));
        // kaputter Rumpf: Rückfrage, nie durch
        assertEquals(409, WebSitzungRechte.pruefe("POST", "/api/vehicle/lights", "kein json", h, k, 1L).getInt("status"));
    }

    @Test
    public void ohneKopplungNieDurch() throws Exception {
        assertEquals("recht_fehlt", WebSitzungRechte.pruefe("POST", "/api/vehicle/lock", "{}", null, k, 1L).getString("grund"));
        assertEquals("recht_fehlt", WebSitzungRechte.pruefe("POST", "/api/vehicle/lock", "{}", "unbekannt", k, 1L).getString("grund"));
    }

    @Test
    public void besitzerBekommtKeineFahrzeugRechte() throws Exception {
        String b = handy(Kopplungen.RECHT_WEB, Kopplungen.RECHT_BESITZER);
        for (String p : new String[]{"/api/vehicle/start-charging", "/api/vehicle/lights", "/api/vehicle/trunk"}) {
            assertEquals(p, "recht_fehlt", WebSitzungRechte.pruefe("POST", p, "{\"bestaetigt\":true}", b, k, 1L).getString("grund"));
        }
    }

    @Test
    public void kofferraumMitStellvertreter() throws Exception {
        String h = handy(Kopplungen.RECHT_WEB, Kopplungen.RECHT_AUFSPERREN);
        final List<String> gefragt = new ArrayList<>();
        WebSitzungRechte.passkey = (kk, kid, w, jetzt) -> {
            gefragt.add(kid);
            return "ok".equals(w.optString("x")) ? null : "signatur_ungueltig";
        };
        assertEquals("passkey_noetig", WebSitzungRechte.pruefe("POST", "/api/vehicle/trunk", "{}", h, k, 1L).getString("grund"));
        assertTrue("ohne webauthn gar nicht erst prüfen", gefragt.isEmpty());
        JSONObject a = WebSitzungRechte.pruefe("POST", "/api/vehicle/trunk", "{\"webauthn\":{\"x\":\"nein\"}}", h, k, 1L);
        assertEquals("passkey_noetig", a.getString("grund"));
        assertEquals("signatur_ungueltig", a.getString("detail"));
        assertNull(WebSitzungRechte.pruefe("POST", "/api/vehicle/trunk", "{\"webauthn\":{\"x\":\"ok\"}}", h, k, 1L));
        // die Oberfläche liefert den Passkey noch nicht: Knopf bleibt ausgeblendet, auch mit Recht
        assertTrue(menge(WebSitzungRechte.stand(true, h, k).getJSONArray("gesperrt")).contains("/api/vehicle/trunk"));
        // ohne Recht aufsperren hilft auch ein gültiger Passkey nicht
        String ohne = handy(Kopplungen.RECHT_WEB);
        assertEquals("recht_fehlt", WebSitzungRechte.pruefe("POST", "/api/vehicle/trunk", "{\"webauthn\":{\"x\":\"ok\"}}", ohne, k, 1L).getString("grund"));
    }

    /** Echter Weg: Passkey wie 0.1.33 eingerichtet, Bestätigung mit eigenem Zweck „kofferraum“, einmal gültig. */
    @Test
    public void kofferraumMitEchtemPasskey() throws Exception {
        WebAufsperrenTest t = new WebAufsperrenTest();
        t.auf();
        try {
            t.eingerichtet();
            String kid = t.kid;
            assertTrue(t.kopplungen.hatRecht(kid, Kopplungen.RECHT_AUFSPERREN));
            String nonce = t.nonce();
            byte[] cd = WebAufsperrenTest.clientData("webauthn.get", WebAuthn.challenge(WebSitzungRechte.KOFFERRAUM, kid, t.jetzt, nonce),
                    WebAuthn.ORIGIN, "");
            JSONObject w = t.assertion(t.jetzt, nonce, cd, WebAufsperrenTest.kopf(WebAuthn.RP_ID, WebAufsperrenTest.UP | WebAufsperrenTest.UV, 1), t.paar);
            String b = new JSONObject().put("webauthn", w).toString();
            assertNull(WebSitzungRechte.pruefe("POST", "/api/vehicle/trunk", b, kid, t.kopplungen, t.jetzt));
            // dieselbe Bestätigung noch einmal: Nonce verbraucht
            assertEquals("passkey_noetig", WebSitzungRechte.pruefe("POST", "/api/vehicle/trunk", b, kid, t.kopplungen, t.jetzt).getString("grund"));
            // eine Aufsperr-Bestätigung gilt nie für den Kofferraum (eigener Zweck)
            String n2 = t.nonce();
            JSONObject auf = t.assertion(t.jetzt, n2, t.cdGet(t.jetzt, n2), WebAufsperrenTest.kopf(WebAuthn.RP_ID, WebAufsperrenTest.UP | WebAufsperrenTest.UV, 2), t.paar);
            JSONObject a = WebSitzungRechte.pruefe("POST", "/api/vehicle/trunk", new JSONObject().put("webauthn", auf).toString(), kid, t.kopplungen, t.jetzt);
            assertEquals("passkey_noetig", a.getString("grund"));
            assertTrue(t.befehle.isEmpty());
        } finally {
            t.ab();
        }
    }

    @Test
    public void handyAktionenAusDerselbenQuelle() {
        String[][] erwartet = {{"klima_an", "klima"}, {"klima_aus", "klima"}, {"sitzheizung", "klima"}, {"akku_heizen", "klima"},
                {"heizen_stoppen", "klima"}, {"tiermodus", "klima"}, {"tiermodus_stand", "klima"}, {"zusperren", "zusperren"},
                {"aufsperren", "aufsperren"}, {"auto_finden", "signal"}, {"licht", "signal"}, {"warnblinker", "signal"},
                {"wallbox", "wallbox"}, {"route", "route"}, {"route_stand", "route"}, {"plan_vorschau", "route"},
                {"plan_vorschau_stand", "route"}, {"update_starten", "update"}};
        assertEquals(erwartet.length, WebSitzungRechte.HANDY_AKTIONEN.size());
        for (String[] e : erwartet) assertEquals(e[0], e[1], WebSitzungRechte.fuerHandyAktion(e[0]));
        assertNull(WebSitzungRechte.fuerHandyAktion("fenster"));
        assertNull(WebSitzungRechte.fuerHandyAktion(null));
        // Handy-App und Web-Sitzung: gleiche Rechte für dieselben Befehle
        assertEquals(WebSitzungRechte.fuerHandyAktion("zusperren"), WebSitzungRechte.noetig("POST", "/api/vehicle/lock"));
        assertEquals(WebSitzungRechte.fuerHandyAktion("klima_an"), WebSitzungRechte.noetig("POST", "/api/vehicle/climate"));
        assertEquals(WebSitzungRechte.fuerHandyAktion("licht"), WebSitzungRechte.noetig("POST", "/api/vehicle/flash"));
        assertEquals(WebSitzungRechte.fuerHandyAktion("auto_finden"), WebSitzungRechte.noetig("POST", "/api/vehicle/find-car"));
    }

    @Test
    public void standFuerDieOberflaeche() throws Exception {
        JSONObject login = WebSitzungRechte.stand(false, null, k);
        assertFalse(login.getBoolean("web"));
        assertFalse(login.has("gesperrt"));

        String h = handy(Kopplungen.RECHT_WEB, Kopplungen.RECHT_ZUSPERREN, Kopplungen.RECHT_KLIMA);
        JSONObject s = WebSitzungRechte.stand(true, h, k);
        assertTrue(s.getBoolean("web"));
        assertEquals("handy", s.getString("art"));
        Set<String> zu = menge(s.getJSONArray("gesperrt"));
        assertFalse(zu.contains("/api/vehicle/lock"));
        assertFalse(zu.contains("/api/vehicle/climate"));
        assertFalse(zu.contains("/api/vehicle/stop-audio"));
        assertTrue(zu.contains("/api/vehicle/window"));
        assertTrue(zu.contains("/api/vehicle/trunk"));
        assertTrue(zu.contains("/api/vehicle/start-charging"));
        assertTrue(zu.contains("/api/vehicle/speak"));
        assertTrue(menge(s.getJSONArray("rueckfrage")).contains("/api/vehicle/lights"));
        // Stand und Prüfung aus derselben Tabelle: jeder freie Pfad geht, jeder gesperrte nicht
        for (String p : WebSitzungRechte.PFADE.keySet()) {
            JSONObject a = WebSitzungRechte.pruefe("POST", p, "{\"bestaetigt\":true}", h, k, 1L);
            boolean abgelehnt = a != null && a.getInt("status") == 403;
            assertEquals(p, zu.contains(p), abgelehnt);
        }
    }

    @Test
    public void pcBleibtStrenger() throws Exception {
        PcZugang.quelle = new PcZugang.Quelle() {
            public boolean an() { return true; }
            public int tagesMb() { return PcZugang.TAGES_MB; }
        };
        String pc = k.anlegenGebunden("PC", Kopplungen.ART_PC, Collections.singletonList(Kopplungen.RECHT_WEB), 1L, 0L);
        assertNotNull(pc);
        Set<String> zu = menge(WebSitzungRechte.stand(true, pc, k).getJSONArray("gesperrt"));
        assertEquals(WebSitzungRechte.PFADE.keySet(), zu);
    }

    /** 0.1.38.10 Ergänzung (handy-048): Favoriten und Umkreis für Art handy, nur mit Recht „route“. */
    @Test
    public void handyRouteOrteUndUmkreis() throws Exception {
        assertFalse(ExternGuard.blocks("GET", "/api/orte", "handy"));
        assertFalse(ExternGuard.blocks("POST", "/api/navi/umkreis", "handy"));
        for (String art : new String[]{"cockpit", "homeassistant", "dienst", "pc", null}) {
            assertTrue("GET /api/orte " + art, ExternGuard.blocks("GET", "/api/orte", art));
            assertTrue("umkreis " + art, ExternGuard.blocks("POST", "/api/navi/umkreis", art));
        }
        // nur lesen, nur die Liste: anlegen, ändern, löschen, „hier“ und Favoriten-Abgleich bleiben zu
        for (String p : new String[]{"/api/orte", "/api/orte/favoriten", "/api/orte/o_abc"}) {
            assertTrue("POST " + p, ExternGuard.blocks("POST", p, "handy"));
        }
        assertTrue(ExternGuard.blocks("GET", "/api/orte/hier", "handy"));
        assertTrue(ExternGuard.blocks("DELETE", "/api/orte/o_abc", "handy"));
        assertTrue(ExternGuard.blocks("GET", "/api/navi/umkreis", "handy"));
        assertTrue("übrige Navi-Wege bleiben zu", ExternGuard.blocks("POST", "/api/navi/plan", "handy"));
        assertEquals(Kopplungen.RECHT_ROUTE, ExternGuard.noetigesRecht("/api/orte"));
        assertEquals(Kopplungen.RECHT_ROUTE, ExternGuard.noetigesRecht("/api/navi/umkreis"));
        String ohneRoute = handy(Kopplungen.RECHT_WEB);
        assertFalse(ExternGuard.rechtReicht(ExternGuard.noetigesRecht("/api/orte"), r -> k.hatRecht(ohneRoute, r)));
        String mitRoute = handy(Kopplungen.RECHT_ROUTE);
        assertTrue(ExternGuard.rechtReicht(ExternGuard.noetigesRecht("/api/navi/umkreis"), r -> k.hatRecht(mitRoute, r)));
        // die Position kommt vom Auto, die des Handys zählt nie
        JSONObject b = new JSONObject(NaviApiHandler.mitAutoPosition(
                "{\"lat\":1.5,\"lon\":2.5,\"lng\":3,\"radius\":5}", new double[]{48.21, 16.37}));
        assertEquals(48.21, b.getDouble("lat"), 1e-9);
        assertEquals(16.37, b.getDouble("lon"), 1e-9);
        assertFalse(b.has("lng"));
        assertEquals(5, b.getInt("radius"));
        assertNull("ohne Auto-Position kein Umkreis", NaviApiHandler.mitAutoPosition("{}", null));
        assertNull(NaviApiHandler.mitAutoPosition("kein json", new double[]{1, 2}));
        // und danach wie immer gerundet
        JSONObject g = new JSONObject(NaviApiHandler.umkreisBody(NaviApiHandler.mitAutoPosition("{}", new double[]{48.21234, 16.37456})));
        assertEquals(48.21, g.getDouble("lat"), 1e-9);
        // Quelltext: nur für Kopplungen, vor dem Senden
        String n = quelle("server/NaviApiHandler.java");
        assertTrue(n.contains("if (umkreis && AuthMiddleware.isExternRequest()) {"));
        assertTrue(n.indexOf("mitAutoPosition(body, autoPosition())") < n.indexOf("umkreisBody(body)"));
        String o = quelle("server/OrteApiHandler.java");
        assertTrue("Handy: nur Favoriten", o.contains("if (handy && !at.greenchili.surfmate.planer.HandyRoute.istFavorit(o)) continue;"));
        assertTrue("Handy: ohne imKreis", o.contains("a.put(handy ? Orte.ohneKoordinaten(o, null, null)"));
    }

    static Set<String> menge(JSONArray a) {
        Set<String> s = new LinkedHashSet<>();
        for (int i = 0; i < a.length(); i++) s.add(a.optString(i));
        return s;
    }

    static String quelle(String rel) throws Exception {
        return new String(Files.readAllBytes(new File("src/main/java/at/greenchili/surfmate/" + rel).toPath()), StandardCharsets.UTF_8);
    }

    /** Vollständigkeit: jeder schreibende Fahrzeug-, Camping-, Navi- und Sound-Pfad im Quelltext ist eingeordnet. */
    @Test
    public void jederSchreibendePfadIstEingeordnet() throws Exception {
        List<String> gefunden = new ArrayList<>();
        Matcher m = Pattern.compile("cleanPath\\.equals\\(\"(/api/vehicle/[^\"]+)\"\\)\\s*&&\\s*method\\.equals\\(\"(POST|PUT|DELETE)\"\\)")
                .matcher(quelle("server/VehicleControlApiHandler.java"));
        while (m.find()) gefunden.add(m.group(1));
        assertTrue("Fahrzeug-Pfade gefunden: " + gefunden, gefunden.size() >= 25);
        m = Pattern.compile("p\\.equals\\(\"(/api/camping[^\"]*)\"\\)\\s*&&\\s*\"POST\"").matcher(quelle("server/CampingApiHandler.java"));
        while (m.find()) gefunden.add(m.group(1));
        m = Pattern.compile("path\\.equals\\(\"(/api/navi/[^\"]+)\"\\)").matcher(quelle("server/NaviApiHandler.java"));
        while (m.find()) if (!m.group(1).equals("/api/navi/unterwegs") && !m.group(1).equals("/api/navi/planer-route")) gefunden.add(m.group(1));
        m = Pattern.compile("\"(/api/audio/[a-z-]+)\"\\.equals\\(cleanPath\\)").matcher(quelle("server/VehicleSoundApiHandler.java"));
        while (m.find()) gefunden.add(m.group(1));
        m = Pattern.compile("cleanPath\\.equals\\(\"(/api/system/[^\"]+)\"\\)").matcher(quelle("server/VehicleControlApiHandler.java"));
        while (m.find()) gefunden.add(m.group(1));
        List<String> fehlt = new ArrayList<>();
        for (String p : gefunden) if (!WebSitzungRechte.PFADE.containsKey(p)) fehlt.add(p);
        assertTrue("nicht eingeordnet (WebSitzungRechte.PFADE): " + fehlt, fehlt.isEmpty());
        for (String p : gefunden) assertTrue(p, WebSitzungRechte.geregelt(p));
    }

    /** Der Kern prüft nur die Web-Sitzung: Login am Display oder im Browser bleibt voll. */
    @Test
    public void nurDieWebSitzungWirdGeprueft() throws Exception {
        String s = quelle("server/HttpServer.java");
        int i = s.indexOf("WebSitzungRechte.pruefe(");
        assertTrue(i > 0);
        String davor = s.substring(Math.max(0, i - 400), i);
        assertTrue(davor.contains("if (AuthMiddleware.isHandyWebRequest()) {"));
        assertTrue("nach dem Stufe-B-Block", s.indexOf("BefehlsLog.grund(\"web_gesperrt\")") < i);
        assertTrue("vor den Handlern", i < s.indexOf("WebManifest.handle(method, pathOnly, out)"));
        // Handy-App: eine Quelle
        String h = quelle("server/HandyApiHandler.java");
        assertTrue(h.contains("WebSitzungRechte.fuerHandyAktion(aktion)"));
        assertFalse("keine zweite Zuordnung", h.contains("recht = Kopplungen.RECHT_KLIMA;"));
    }
}
