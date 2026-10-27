package at.greenchili.surfmate.wallbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.testutil.Quelltext;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** 0.1.19 „Mit Home Assistant anmelden“ (WALLBOX_FERN_KONZEPT §5): OAuth statt Langzeit-Token, REGEL DAU. */
public class HaAnmeldungTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final String HERKUNFT = "http://127.0.0.1:8180";
    private final AtomicLong jetzt = new AtomicLong(Instant.parse("2026-10-27T18:00:00Z").toEpochMilli());
    private HaAttrappe ha;
    private final List<String> tokenAufrufe = new ArrayList<>();
    private int tokenAntwort = 200;
    private WallboxDienst dienst;
    private File konfig;

    @Before
    public void auf() throws Exception {
        ha = new HaAttrappe();
        WallboxHttp weiche = (m, url, k, r) -> {
            if (url.endsWith("/auth/token")) {
                tokenAufrufe.add(r);
                assertEquals("application/x-www-form-urlencoded", k.get("Content-Type"));
                if (tokenAntwort != 200) return new WallboxHttp.Antwort(tokenAntwort, "{\"error\":\"invalid_grant\"}");
                try {
                    JSONObject o = new JSONObject().put("access_token", HaAttrappe.TOKEN).put("expires_in", 1800)
                            .put("token_type", "Bearer");
                    if (r.contains("grant_type=authorization_code")) o.put("refresh_token", "rt-geheim-123");
                    return new WallboxHttp.Antwort(200, o.toString());
                } catch (org.json.JSONException e) {
                    throw new IllegalStateException(e);
                }
            }
            return ha.anfrage(m, url, k, r);
        };
        konfig = new File(tmp.getRoot(), "wallbox.json");
        dienst = new WallboxDienst(konfig, new File(tmp.getRoot(), "p.jsonl"), WallboxTest.GEHEIM, weiche,
                WallboxTest.DNS, () -> "steht", jetzt::get, ms -> { });
    }

    private static Map<String, String> query(String url) throws Exception {
        Map<String, String> m = new HashMap<>();
        for (String p : url.substring(url.indexOf('?') + 1).split("&")) {
            String[] kv = p.split("=", 2);
            m.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8.name()));
        }
        return m;
    }

    private String anmelden() throws Exception {
        WallboxDienst.Ergebnis r = dienst.haAnmeldungStart(new JSONObject().put("url", "http://192.168.1.20:8123")
                .put("herkunft", HERKUNFT));
        assertEquals(r.json.toString(), 200, r.status);
        return r.json.getString("anmeldeUrl");
    }

    @Test
    public void anmeldeAdresseMitClientIdUndRueckrufAufDemselbenHost() throws Exception {
        String url = anmelden();
        assertTrue(url.startsWith("http://192.168.1.20:8123/auth/authorize?response_type=code&"));
        Map<String, String> q = query(url);
        assertEquals(HERKUNFT + "/", q.get("client_id"));
        assertEquals(HERKUNFT + HaAnmeldung.RUECKRUF, q.get("redirect_uri"));
        assertTrue("128 Bit Zufall", q.get("state").matches("[0-9a-f]{32}"));
        // ungültige Herkunft (mit Pfad) bzw. öffentliche HA ohne Häkchen
        assertEquals(400, dienst.haAnmeldungStart(new JSONObject().put("url", "http://192.168.1.20:8123")
                .put("herkunft", "http://127.0.0.1:8180/x")).status);
        assertEquals("adresse_oeffentlich", dienst.haAnmeldungStart(new JSONObject().put("url", "https://185.199.108.153")
                .put("herkunft", HERKUNFT)).json.optString("grund"));
    }

    @Test
    public void rueckrufSpeichertNurVerschluesseltenRefreshToken() throws Exception {
        String state = query(anmelden()).get("state");
        WallboxDienst.Ergebnis r = dienst.haAnmeldungRueckruf(new JSONObject().put("code", "c0de").put("state", state));
        assertEquals(r.json.toString(), 200, r.status);
        assertTrue(tokenAufrufe.get(0).contains("grant_type=authorization_code&code=c0de&client_id="));
        JSONObject oe = r.json.getJSONObject("einstellungen");
        assertEquals("ha", oe.getString("quelle"));
        assertTrue(oe.getJSONObject("ha").getBoolean("angemeldet"));
        assertFalse("nie Geheimnisse in der Antwort", oe.toString().contains("rt-geheim"));
        String platte = new String(Files.readAllBytes(konfig.toPath()), StandardCharsets.UTF_8);
        assertFalse("kein Klartext auf der Platte", platte.contains("rt-geheim-123"));
        assertTrue(platte.contains("ENC:"));
        // State ist verbraucht
        assertEquals("anmeldung_abgelaufen",
                dienst.haAnmeldungRueckruf(new JSONObject().put("code", "c0de").put("state", state)).json.optString("grund"));
    }

    @Test
    public void abgelaufenFremdOderAbgebrochen() throws Exception {
        String state = query(anmelden()).get("state");
        jetzt.addAndGet(HaAnmeldung.GUELTIG_MS + 1);
        assertEquals(409, dienst.haAnmeldungRueckruf(new JSONObject().put("code", "c").put("state", state)).status);
        assertEquals(409, dienst.haAnmeldungRueckruf(new JSONObject().put("code", "c").put("state", "f".repeat(32))).status);
        assertEquals("anmeldung_abgebrochen",
                dienst.haAnmeldungRueckruf(new JSONObject().put("fehler", "access_denied")).json.optString("grund"));
        assertTrue(tokenAufrufe.isEmpty());
        for (int i = 0; i < HaAnmeldung.MAX_OFFEN; i++) anmelden();
        assertEquals(429, dienst.haAnmeldungStart(new JSONObject().put("url", "http://192.168.1.20:8123")
                .put("herkunft", HERKUNFT)).status);
    }

    @Test
    public void accessTokenWirdGenutztUndErneuert() throws Exception {
        String state = query(anmelden()).get("state");
        dienst.haAnmeldungRueckruf(new JSONObject().put("code", "c0de").put("state", state));
        // Vorschlag (lesend) mit dem OAuth-Access-Token, ohne neuen Token-Abruf
        WallboxDienst.Ergebnis v = dienst.haVorschlag(new JSONObject());
        assertEquals(v.json.toString(), 200, v.status);
        assertEquals(1, tokenAufrufe.size());
        // nach Ablauf: Refresh
        jetzt.addAndGet(31 * 60_000L);
        assertEquals(200, dienst.haVorschlag(new JSONObject()).status);
        assertEquals(2, tokenAufrufe.size());
        assertTrue(tokenAufrufe.get(1).startsWith("grant_type=refresh_token&refresh_token=rt-geheim-123&client_id="));
        // widerrufen → ehrlich „neu anmelden“
        jetzt.addAndGet(31 * 60_000L);
        tokenAntwort = 401;
        assertEquals("anmeldung_abgelaufen", dienst.haVorschlag(new JSONObject()).json.optString("grund"));
    }

    /** Fehler gefunden beim Bau: fehlt ein Abschnitt, wurden gespeicherte Geheimnisse noch einmal verschlüsselt. */
    @Test
    public void andereAbschnitteBleibenUnveraendert() throws Exception {
        JSONObject alt = WallboxKonfig.uebernimm(WallboxKonfig.leer(), new JSONObject().put("quelle", "tuya")
                .put("evcc", new JSONObject().put("url", "http://192.168.1.8:7070").put("apiKey", "evcc-schluessel"))
                .put("tuya", new JSONObject().put("zugang", "lan").put("geraete", new org.json.JSONArray().put(new JSONObject()
                        .put("schluessel", "lp1").put("geraeteId", "bf1234567890abcdefgh").put("ip", "192.168.1.50")
                        .put("version", "3.3").put("localKey", "k".repeat(16))))), WallboxTest.GEHEIM, 0);
        JSONObject neu = WallboxKonfig.uebernimm(alt, new JSONObject().put("quelle", "ha")
                .put("ha", new JSONObject().put("url", "http://192.168.1.20:8123")), WallboxTest.GEHEIM, 0);
        assertEquals(alt.getJSONObject("evcc").getString("apiKey"), neu.getJSONObject("evcc").getString("apiKey"));
        assertEquals("k".repeat(16), WallboxTest.GEHEIM.ent(neu.getJSONObject("tuya").getJSONArray("geraete")
                .getJSONObject(0).getString("localKey")));
        assertEquals("evcc-schluessel", WallboxTest.GEHEIM.ent(neu.getJSONObject("evcc").getString("apiKey")));
    }

    @Test
    public void weiterleitungsseiteOhneLoginUndOhneDaten() throws Exception {
        String s = HaAnmeldung.weiterleitungsSeite();
        assertTrue(s.contains("location.replace('/wallbox.html#ha_rueckruf&'"));
        assertTrue(s.contains("no-referrer"));
        String server = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/HttpServer.java"));
        int seite = server.indexOf("pathOnly.equals(at.greenchili.surfmate.wallbox.HaAnmeldung.RUECKRUF)");
        int login = server.indexOf("// Check authentication for all other paths");
        assertTrue("vor der Anmeldeprüfung", seite > 0 && seite < login);
        String api = Quelltext.lies(Paths.get("src/main/java/at/greenchili/surfmate/server/WallboxApiHandler.java"));
        assertTrue(api.contains("\"POST /api/wallbox/ha/rueckruf\""));
        assertNotNull(api);
    }
}
