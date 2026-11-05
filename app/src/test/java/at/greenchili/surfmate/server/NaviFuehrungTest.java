package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.planer.PlanerBackoff;
import at.greenchili.surfmate.testutil.Quelltext;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * 0.1.39 G2: {@code POST /api/navi/fuehrung} reicht an den Planer {@code POST …/ev/api/fuehrung} (Vertrag fuehrung/1)
 * durch – gleicher Weg wie {@code /api/navi/plan}: Schlüssel aus PlanerSchluessel, Punkte nur im Body, nichts geloggt.
 * Prüfserver lokal (127.0.0.1), Koordinaten künstlich (Ursprung 10°/20°), keine echten Orte.
 */
public class NaviFuehrungTest {

    private static final String SCHLUESSEL = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String ANFRAGE =
            "{\"start\":[10.123456,20.654321],\"ziel\":[10.5,20.5],\"via\":[{\"lat\":10.3,\"lon\":20.3}],\"sprache\":\"de\"}";

    private ServerSocket server;
    private Thread annahme;
    private final List<String[]> gesehen = new CopyOnWriteArrayList<>();   // {methode, pfad, query, kopf, body}
    private volatile int antwortCode = 200;
    private volatile String antwortText = "{\"ok\":true,\"vertrag\":\"fuehrung/1\",\"instanz\":\"eu\",\"halte\":[],\"trip\":{\"legs\":[]}}";
    private volatile String retryAfter;

    private Supplier<String[]> kopfAlt;
    private Supplier<String> basisAlt;

    @Before
    public void starte() throws Exception {
        // Kleiner Prüfserver (eine Anfrage je Verbindung); com.sun.net.httpserver fehlt im Android-Testpfad.
        server = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
        annahme = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket so = server.accept()) {
                    beantworte(so);
                } catch (Exception ignored) { }
            }
        });
        annahme.setDaemon(true);
        annahme.start();
        kopfAlt = NaviApiHandler.kopfQuelle;
        basisAlt = NaviApiHandler.basisQuelle;
        NaviApiHandler.kopfQuelle = () -> new String[] {"X-Geraete-Token", SCHLUESSEL};
        NaviApiHandler.basisQuelle = () -> "http://127.0.0.1:" + server.getLocalPort() + "/ev/api/";
        PlanerBackoff.FUEHRUNG.erfolg();
    }

    @After
    public void stoppe() {
        NaviApiHandler.kopfQuelle = kopfAlt;
        NaviApiHandler.basisQuelle = basisAlt;
        PlanerBackoff.FUEHRUNG.erfolg();
        try { server.close(); } catch (Exception ignored) { }
    }

    private void beantworte(Socket so) throws Exception {
        InputStream in = so.getInputStream();
        ByteArrayOutputStream kopf = new ByteArrayOutputStream();
        int b, ende = 0;
        while (ende < 4 && (b = in.read()) >= 0) {
            kopf.write(b);
            ende = (b == '\r' || b == '\n') ? ende + 1 : 0;
        }
        String[] zeilen = new String(kopf.toByteArray(), StandardCharsets.UTF_8).split("\r\n");
        String[] erste = zeilen[0].split(" ");
        String ziel = erste[1];
        int q = ziel.indexOf('?');
        String schluessel = null;
        int laenge = 0;
        for (String z : zeilen) {
            int d = z.indexOf(':');
            if (d < 0) continue;
            String name = z.substring(0, d).trim(), wert = z.substring(d + 1).trim();
            if (name.equalsIgnoreCase("X-Geraete-Token")) schluessel = wert;
            if (name.equalsIgnoreCase("Content-Length")) laenge = Integer.parseInt(wert);
        }
        byte[] koerper = new byte[laenge];
        int gelesen = 0;
        while (gelesen < laenge) {
            int n = in.read(koerper, gelesen, laenge - gelesen);
            if (n < 0) break;
            gelesen += n;
        }
        gesehen.add(new String[] {erste[0], q < 0 ? ziel : ziel.substring(0, q), q < 0 ? null : ziel.substring(q + 1),
                schluessel, new String(koerper, 0, gelesen, StandardCharsets.UTF_8)});
        byte[] aus = antwortText.getBytes(StandardCharsets.UTF_8);
        StringBuilder h = new StringBuilder("HTTP/1.1 " + antwortCode + " X\r\n");
        h.append("Content-Type: application/json\r\n");
        if (retryAfter != null) h.append("Retry-After: ").append(retryAfter).append("\r\n");
        h.append("Content-Length: ").append(aus.length).append("\r\nConnection: close\r\n\r\n");
        OutputStream os = so.getOutputStream();
        os.write(h.toString().getBytes(StandardCharsets.US_ASCII));
        os.write(aus);
        os.flush();
    }

    private static final class Antwort {
        int code;
        JSONObject json;
    }

    private static Antwort rufe(String methode, String body) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(NaviApiHandler.handle(methode, "/api/navi/fuehrung", body, out));
        String roh = new String(out.toByteArray(), StandardCharsets.UTF_8);
        Antwort a = new Antwort();
        a.code = Integer.parseInt(roh.substring(roh.indexOf(' ') + 1, roh.indexOf(' ') + 4));
        int k = roh.indexOf("\r\n\r\n");
        String rumpf = k >= 0 ? roh.substring(k + 4).trim() : "";
        a.json = rumpf.startsWith("{") ? new JSONObject(rumpf) : null;
        return a;
    }

    @Test
    public void reichtDurchWiePlanMitSchluesselImKopf() throws Exception {
        Antwort a = rufe("POST", ANFRAGE);
        assertEquals(200, a.code);
        assertEquals("fuehrung/1", a.json.getString("vertrag"));
        assertEquals(1, gesehen.size());
        String[] g = gesehen.get(0);
        assertEquals("POST", g[0]);
        assertEquals("/ev/api/fuehrung", g[1]);
        assertNull("keine Query, Punkte nur im Body", g[2]);
        assertEquals(SCHLUESSEL, g[3]);
        // Führung braucht die genaue Position: unverändert, nicht gerundet wie umkreis
        JSONObject b = new JSONObject(g[4]);
        assertEquals(10.123456, b.getJSONArray("start").getDouble(0), 1e-9);
        assertEquals(20.654321, b.getJSONArray("start").getDouble(1), 1e-9);
        assertEquals(1, b.getJSONArray("via").length());
        assertFalse("ohne laden kein Plan-Body", b.has("laden"));
    }

    @Test
    public void ohneSchluesselGehtNichtsHinaus() throws Exception {
        NaviApiHandler.kopfQuelle = () -> null;
        Antwort a = rufe("POST", ANFRAGE);
        assertEquals(401, a.code);
        assertEquals("nur_mitglieder", a.json.getString("code"));
        assertFalse(a.json.getBoolean("ok"));
        assertTrue("kein Aufruf ohne Schlüssel", gesehen.isEmpty());
    }

    @Test
    public void planerSagt401KeinZweiterVersuchOhneSchluessel() throws Exception {
        antwortCode = 401;
        antwortText = "{\"ok\":false,\"code\":\"nur_mitglieder\",\"fehler\":\"Nur fuer bestaetigte Mitglieder\"}";
        Antwort a = rufe("POST", ANFRAGE);
        assertEquals(401, a.code);
        assertEquals("nur_mitglieder", a.json.getString("code"));
        assertEquals("anders als plan: kein Versuch ohne Schlüssel", 1, gesehen.size());
    }

    @Test
    public void fremderVertragWirdNichtWeitergegeben() throws Exception {
        antwortText = "{\"ok\":true,\"vertrag\":\"fuehrung/2\",\"trip\":{}}";
        Antwort a = rufe("POST", ANFRAGE);
        assertEquals(502, a.code);
        assertFalse(a.json.getBoolean("ok"));
        assertFalse(a.json.has("trip"));
    }

    @Test
    public void keinWegBleibt422() throws Exception {
        antwortCode = 422;
        antwortText = "{\"ok\":false,\"code\":\"kein_weg\",\"valhalla_code\":442,\"fehler\":\"kein Weg\"}";
        Antwort a = rufe("POST", ANFRAGE);
        assertEquals(422, a.code);
        assertEquals("kein_weg", a.json.getString("code"));
    }

    @Test
    public void retryAfterWirdBeachtet() throws Exception {
        antwortCode = 429;
        retryAfter = "40";
        antwortText = "{\"ok\":false,\"code\":\"zu_viele\",\"fehler\":\"zu viele\"}";
        assertEquals(429, rufe("POST", ANFRAGE).code);
        assertEquals(1, gesehen.size());
        long warte = PlanerBackoff.FUEHRUNG.warteMs(System.currentTimeMillis());
        assertTrue(String.valueOf(warte), warte > 35_000 && warte <= 40_000);
        // In der Pause fragt der Kern nicht erneut, sondern sagt ehrlich, wann wieder.
        antwortCode = 200;
        Antwort zwei = rufe("POST", ANFRAGE);
        assertEquals(503, zwei.code);
        assertEquals("netz", zwei.json.getString("grund"));
        assertEquals("routing_weg", zwei.json.getString("code"));
        assertTrue(zwei.json.getLong("warte_ms") > 0);
        assertEquals(1, gesehen.size());
    }

    @Test
    public void nginx429OhneRetryAfterZehnSekunden() throws Exception {
        antwortCode = 429;
        antwortText = "<html><body>429 Too Many Requests</body></html>";
        Antwort a = rufe("POST", ANFRAGE);
        assertEquals(429, a.code);
        assertFalse(a.json.getBoolean("ok"));
        long warte = PlanerBackoff.FUEHRUNG.warteMs(System.currentTimeMillis());
        assertTrue(String.valueOf(warte), warte > 8_000 && warte <= 10_000);
    }

    @Test
    public void pauseRegeln() {
        assertEquals(30_000L, NaviApiHandler.fuehrungPauseMs(503, null));
        assertEquals(30_000L, NaviApiHandler.fuehrungPauseMs(503, "5"));
        assertEquals(45_000L, NaviApiHandler.fuehrungPauseMs(503, "45"));
        assertEquals(10_000L, NaviApiHandler.fuehrungPauseMs(429, "kaputt"));
        assertEquals(10_000L, NaviApiHandler.fuehrungPauseMs(429, null));
        assertEquals(NaviApiHandler.FUEHRUNG_PAUSE_MAX_MS, NaviApiHandler.fuehrungPauseMs(429, "99999"));
        assertEquals(502, NaviApiHandler.fuehrungStatus(500));
        assertEquals(503, NaviApiHandler.fuehrungStatus(503));
        assertEquals(40_000, NaviApiHandler.lesezeitMs("fuehrung", ANFRAGE));
        assertEquals(150_000, NaviApiHandler.lesezeitMs("fuehrung", "{\"laden\":{\"soc_start\":50}}"));
        assertEquals(150_000, NaviApiHandler.lesezeitMs("plan", "{}"));
    }

    @Test
    public void nurPostUndJson() throws Exception {
        assertEquals(405, rufe("GET", null).code);
        assertEquals(400, rufe("POST", "kein json").code);
        assertTrue(gesehen.isEmpty());
    }

    @Test
    public void ladenWieEinPlanBodyAufbereitet() throws Exception {
        String mit = "{\"start\":[10.1,20.1],\"ziel\":[10.5,20.5],\"laden\":{\"soc_start\":50,\"filter\":{\"bevorzugt\":[]}}}";
        JSONObject b = new JSONObject(NaviApiHandler.fuehrungBody(mit));
        assertEquals(50, b.getJSONObject("laden").getInt("soc_start"));
        // leeres filter.bevorzugt fällt wie bei plan weg (Konto-Vorlieben gelten)
        assertFalse(b.getJSONObject("laden").has("filter"));
        assertEquals(10.1, b.getJSONArray("start").getDouble(0), 1e-12);
        assertEquals(ANFRAGE, NaviApiHandler.fuehrungBody(ANFRAGE));
    }

    @Test
    public void eineQuelleKeinLogDerPunkteKeineNeuenRechte() throws Exception {
        String h = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/NaviApiHandler.java"));
        assertTrue(h.contains("kopfQuelle = at.greenchili.surfmate.planer.PlanerSchluessel::kopf"));
        assertFalse("Führung kopiert die Schlüssel-Logik nicht", h.contains("geraeteSchluessel"));
        assertTrue(h.contains(": fuehrung ? fuehrungBody(body)"));
        for (String zeile : h.split("\n")) {
            if (zeile.contains("logger.")) {
                assertFalse("kein Body im Log: " + zeile, zeile.contains("body") || zeile.contains("text"));
            }
        }
        // Kopplungen erreichen die Führung nicht (Erlaubnisliste), der PC schreibt sie nicht; kein neues Recht.
        assertFalse(ExternGuard.LESEN.contains("/api/navi/fuehrung"));
        assertFalse(ExternGuard.SCHREIBEN.contains("/api/navi/fuehrung"));
        assertFalse(ExternGuard.HANDY_SCHREIBEN.contains("/api/navi/fuehrung"));
        assertTrue(WebHandyGuard.pcBlocks("POST", "/api/navi/fuehrung"));
        assertNull(ExternGuard.noetigesRecht("/api/navi/fuehrung"));
        // Demo-Profil: kein Muster für die Führung → gesperrt, es geht nichts an den Planer.
        assertTrue(DemoProfil.gesperrt("POST", "/api/navi/fuehrung"));
    }
}
