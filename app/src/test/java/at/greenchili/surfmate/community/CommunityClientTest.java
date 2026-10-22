package at.greenchili.surfmate.community;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.community.sync.CommunitySyncProvider;
import at.greenchili.surfmate.roadsense.sync.CloudflareEdgeSyncProvider;
import at.greenchili.surfmate.roadsense.sync.RoadSenseSyncProvider;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Community-Client gegen den eigenen Server (Server-Vertrag,
 * COMMUNITY_CLIENT_AENDERUNGEN.md): Standard aus, Mitglieds-Schlüssel nur im Header,
 * Kacheln per POST, nie Overdrive-Worker, Hinweise für 401/422/429.
 */
public class CommunityClientTest {

    static final String TOKEN = "0123456789abcdef0123456789abcdef";

    static JSONObject cfg(String json) throws Exception {
        return new JSONObject(json);
    }

    @Test
    public void offByDefaultEvenWithUrlOrCrowdSwitches() throws Exception {
        assertFalse(CommunityZugang.INSTANCE.aktiv(cfg("{}")));
        assertNull(CommunityZugang.INSTANCE.basis(cfg("{}"), null, CommunityZugang.STANDARD_REGELN));
        assertNull("URL gesetzt, Schalter aus", CommunityZugang.INSTANCE.basis(
                cfg("{\"community\":{\"workerUrl\":\"https://x.example/api\"}}"), "https://x.example/api",
                CommunityZugang.STANDARD_REGELN));
        assertNull("RoadSense crowdUpload an, Schalter aus", CommunityZugang.INSTANCE.basis(
                cfg("{\"roadSense\":{\"crowdUpload\":true}}"), "", CommunityZugang.STANDARD_STRASSE));
    }

    @Test
    public void onUsesOwnServerAndNeverOverdrive() throws Exception {
        JSONObject an = cfg("{\"community\":{\"enabled\":true}}");
        assertEquals("https://byd.serveone.at/community/api",
                CommunityZugang.INSTANCE.basis(an, null, CommunityZugang.STANDARD_REGELN));
        assertEquals("https://byd.serveone.at/community/api/strasse",
                CommunityZugang.INSTANCE.basis(an, "", CommunityZugang.STANDARD_STRASSE));
        assertEquals("alter Overdrive-Worker wird ersetzt", CommunityZugang.STANDARD_STRASSE,
                CommunityZugang.INSTANCE.basis(an, "https://roadsense-edge.yash321sri.workers.dev",
                        CommunityZugang.STANDARD_STRASSE));
        assertEquals("kein Klartext-HTTP", CommunityZugang.STANDARD_REGELN,
                CommunityZugang.INSTANCE.basis(an, "http://x.example", CommunityZugang.STANDARD_REGELN));
        assertEquals("eigener Server (Selbsthoster) bleibt", "https://self.example/c",
                CommunityZugang.INSTANCE.basis(an, "https://self.example/c/", CommunityZugang.STANDARD_REGELN));
    }

    @Test
    public void memberKeyComesFromPlanner() throws Exception {
        assertEquals(TOKEN, CommunityZugang.INSTANCE.schluessel(cfg("{\"planner\":{\"memberToken\":\"" + TOKEN + "\"}}")));
        assertNull(CommunityZugang.INSTANCE.schluessel(cfg("{\"planner\":{\"memberToken\":\"kurz\"}}")));
        assertNull(CommunityZugang.INSTANCE.schluessel(cfg("{}")));
    }

    @Test
    public void hintsForServerErrors() throws Exception {
        assertEquals("mitgliedsschluessel_eintragen", CommunityZugang.INSTANCE.hinweis(401, "Nur fuer Mitglieder"));
        assertEquals("persoenliche_daten", CommunityZugang.INSTANCE.hinweis(422, "Bitte persoenliche Daten entfernen"));
        assertEquals("ratenlimit", CommunityZugang.INSTANCE.hinweis(429, "x"));
        assertEquals("community_aus", CommunityZugang.INSTANCE.hinweis(0, CommunityZugang.AUS));
        assertNull(CommunityZugang.INSTANCE.hinweis(500, "x"));
        assertTrue(CommunityZugang.INSTANCE.hatPlatzhalter(new JSONObject().put("ssid", "BITTE-EINTRAGEN")));
    }

    // ------------------------------------------------------------------ Transport

    /** Fängt Anfragen ab und antwortet mit festem Status/Body (kein Netz). */
    static final class Abfang {
        final List<Request> anfragen = new ArrayList<>();
        final List<String> bodies = new ArrayList<>();
        int code = 200;
        String antwort = "{}";

        OkHttpClient client() {
            return new OkHttpClient.Builder().addInterceptor(chain -> {
                Request r = chain.request();
                anfragen.add(r);
                String b = "";
                if (r.body() != null) {
                    Buffer buf = new Buffer();
                    r.body().writeTo(buf);
                    b = buf.readUtf8();
                }
                bodies.add(b);
                return new Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(code).message("x")
                        .body(ResponseBody.create(antwort, MediaType.get("application/json"))).build();
            }).build();
        }
    }

    @Test
    public void rulesCallsCarryMemberKeyHeaderOnly() {
        Abfang a = new Abfang();
        a.antwort = "{\"items\":[],\"total\":0}";
        CommunitySyncProvider p = new CommunitySyncProvider(() -> CommunityZugang.STANDARD_REGELN, a::client, () -> TOKEN);
        assertTrue(p.list("sort=updated", "dev").getOk());
        p.rate("abc", "dev", 5);
        p.install("abc");
        p.delete("abc", "dev");
        assertEquals(4, a.anfragen.size());
        for (Request r : a.anfragen) {
            assertEquals(TOKEN, r.header("X-Member-Token"));
            assertFalse("Schlüssel nie in der URL", r.url().toString().contains(TOKEN));
            assertTrue(r.url().toString().startsWith("https://byd.serveone.at/community/api/automations"));
        }
    }

    @Test
    public void rulesOffMeansNoCallAndClearError() {
        Abfang a = new Abfang();
        CommunitySyncProvider p = new CommunitySyncProvider(() -> null, a::client, () -> TOKEN);
        CommunitySyncProvider.Result r = p.list("", null);
        assertFalse(r.getOk());
        assertEquals(CommunityZugang.AUS, r.getError());
        assertTrue(a.anfragen.isEmpty());
    }

    @Test
    public void serverErrorTextReachesTheUser() {
        Abfang a = new Abfang();
        a.code = 422;
        a.antwort = "{\"ok\":false,\"error\":\"Bitte persoenliche Daten entfernen: Telefonnummer in rules.actions[0].variables.message\"}";
        CommunitySyncProvider p = new CommunitySyncProvider(() -> CommunityZugang.STANDARD_REGELN, a::client, () -> TOKEN);
        CommunitySyncProvider.Result r = p.publish("dev", "Anon", "n", "", "", new JSONObject(), 1, null);
        assertEquals(422, r.getHttpCode());
        assertTrue(r.getError(), r.getError().startsWith("Bitte persoenliche Daten entfernen: Telefonnummer"));
    }

    @Test
    public void roadTilesArePostedNeverInUrl() throws Exception {
        Abfang a = new Abfang();
        a.antwort = "{\"hazards\":[{\"tile\":\"-9007199254740993\",\"lat\":47.8,\"lng\":13.04,\"type\":1,"
                + "\"severity\":2,\"heading\":null,\"confidence\":0.8,\"altitude\":null,\"createdMs\":1,\"updatedMs\":77}],\"more\":false}";
        CloudflareEdgeSyncProvider p = new CloudflareEdgeSyncProvider(() -> CommunityZugang.STANDARD_STRASSE, a::client, () -> TOKEN);
        List<Long> tiles = Arrays.asList(-9007199254740993L, -42L);
        RoadSenseSyncProvider.DownloadResult d = p.download(tiles, Collections.singletonMap(-42L, 5L));
        assertTrue(d.getOk());
        assertEquals(1, d.getHazards().size());
        Request r = a.anfragen.get(0);
        assertEquals("POST", r.method());
        assertEquals("https://byd.serveone.at/community/api/strasse/tiles", r.url().toString());
        assertEquals(TOKEN, r.header("X-Member-Token"));
        JSONObject body = new JSONObject(a.bodies.get(0));
        assertEquals("Kachel als String (64 bit)", "-9007199254740993", body.getJSONArray("keys").getString(0));
        assertEquals(0, body.getLong("since"));
    }

    @Test
    public void roadOffMeansNoCall() {
        Abfang a = new Abfang();
        CloudflareEdgeSyncProvider p = new CloudflareEdgeSyncProvider(() -> null, a::client, () -> TOKEN);
        assertFalse(p.download(Collections.singletonList(1L), Collections.emptyMap()).getOk());
        assertTrue(a.anfragen.isEmpty());
    }

    /** Verdrahtung: Schalter im Schema, Config-Leser über CommunityZugang, Hinweise im Handler. */
    @Test
    public void wiring() throws Exception {
        String katalog = lies("app/src/main/java/at/greenchili/surfmate/einstellungen/Katalog.java");
        assertTrue(katalog.contains("new Feld(\"community.enabled\", Feld.Typ.BOOL)"));
        assertTrue(katalog.contains("comm.standard = false"));
        assertTrue(lies("app/src/main/java/at/greenchili/surfmate/community/config/CommunityConfig.kt")
                .contains("CommunityZugang.basis("));
        assertTrue(lies("app/src/main/java/at/greenchili/surfmate/roadsense/config/RoadSenseConfig.kt")
                .contains("CommunityZugang.basis("));
        String handler = lies("app/src/main/java/at/greenchili/surfmate/community/CommunityApiHandler.kt");
        assertTrue(handler.contains("CommunityZugang.hinweis(res.httpCode, res.error)"));
        assertTrue(handler.contains("resp.put(\"riskyActions\""));
        assertTrue(handler.contains("tokenSupplier = { CommunityConfig.snapshot().memberToken }"));
        assertTrue(lies("app/src/main/java/at/greenchili/surfmate/roadsense/RoadSenseController.kt")
                .contains("tokenSupplier = {"));
    }

    static String lies(String rel) throws Exception {
        Path p = Paths.get(rel);
        if (!Files.exists(p)) p = Paths.get("..").resolve(rel);
        if (!Files.exists(p)) p = Paths.get(rel.replaceFirst("^app/", ""));
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
