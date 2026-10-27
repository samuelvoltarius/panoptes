package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.auth.Kopplungen;
import at.greenchili.surfmate.byd.BydDataCollector;
import at.greenchili.surfmate.byd.routing.CloudPowerGate;
import at.greenchili.surfmate.byd.routing.VehicleCommandRouter;
import at.greenchili.surfmate.handy.HandySignatur;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/** 0.1.18 Handy-Vertrag: Rechte, Geräteschlüssel, Aktionen über den Router, Aufsperren nur signiert, Push. */
public class HandyApiHandlerTest {

    final List<VehicleCommandRouter.VehicleCommand> befehle = new ArrayList<>();
    final List<String> telegram = new ArrayList<>();
    final List<String> pushAngelegt = new ArrayList<>();
    final List<String> pushEntfernt = new ArrayList<>();
    boolean autoAus = true;
    int echo = BydDataCollector.ECHO_OK;
    VehicleCommandRouter.Path weg = VehicleCommandRouter.Path.CLOUD;
    long jetzt = 1_800_000_000_000L;
    Kopplungen kopplungen;
    final JSONArray[] gespeichert = {new JSONArray()};
    HandyApiHandler.Umgebung vorher;

    @Before
    public void auf() {
        vorher = HandyApiHandler.umgebung;
        kopplungen = new Kopplungen(new Kopplungen.Speicher() {
            public JSONArray lade() { return gespeichert[0]; }
            public boolean speichere(JSONArray g) { gespeichert[0] = g; return true; }
        });
        HandyApiHandler.kopplungen = kopplungen;
        HandyApiHandler.umgebung = new HandyApiHandler.Umgebung() {
            public VehicleCommandRouter.CommandResult ausfuehren(VehicleCommandRouter.VehicleCommand cmd) {
                befehle.add(cmd);
                return VehicleCommandRouter.CommandResult.success(weg, "ok", 5);
            }
            public CloudPowerGate.Decision gate(String befehl) {
                return CloudPowerGate.evaluate(befehl, autoAus ? 0 : 2, jetzt - 1_000, true, !autoAus, jetzt);
            }
            public int echo(String aktion) { return echo; }
            public void telegram(String key, Object... args) { telegram.add(key + Arrays.toString(args)); }
            public String pushAnlegen(String e, byte[] p, byte[] a, String l) { pushAngelegt.add(e); return "abo-" + pushAngelegt.size(); }
            public void pushEntfernen(String id) { pushEntfernt.add(id); }
            public long jetzt() { return jetzt; }
        };
    }

    @After
    public void ab() {
        HandyApiHandler.umgebung = vorher;
        HandyApiHandler.kopplungen = null;
    }

    String handy() { return kopplungen.anlegen("Pixel", Kopplungen.ART_HANDY, jetzt); }

    String post(String pfad, String kid, JSONObject body) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HandyApiHandler.handle("POST", pfad, body.toString(), out, kid);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    static JSONObject json(String antwort) throws Exception {
        return new JSONObject(antwort.substring(antwort.indexOf("\r\n\r\n") + 4));
    }

    static KeyPair paar() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }

    static String pub(KeyPair p) { return Base64.getEncoder().encodeToString(p.getPublic().getEncoded()); }

    JSONObject signatur(KeyPair p, String kid, long zeit, String nonce) throws Exception {
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(p.getPrivate());
        s.update(HandySignatur.nachricht("aufsperren", kid, zeit, nonce).getBytes(StandardCharsets.UTF_8));
        return new JSONObject().put("zeit", zeit).put("nonce", nonce)
                .put("sig", Base64.getEncoder().encodeToString(s.sign()));
    }

    // ------------------------------------------------------------------ Zugang

    @Test
    public void nurHandyKopplungen() throws Exception {
        String cockpit = kopplungen.anlegen("Cockpit", "cockpit", jetzt);
        assertTrue(post("/api/handy/v1/aktion", cockpit, new JSONObject().put("aktion", "klima_aus")).startsWith("HTTP/1.1 403"));
        assertTrue(post("/api/handy/v1/aktion", null, new JSONObject().put("aktion", "klima_aus")).startsWith("HTTP/1.1 403"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HandyApiHandler.handle("GET", "/api/handy/v1/aktion", null, out, handy());
        assertTrue(new String(out.toByteArray(), StandardCharsets.UTF_8).startsWith("HTTP/1.1 405"));
        assertTrue(befehle.isEmpty());
        // Standardrechte einer neuen Handy-Kopplung: aufsperren AUS
        String k = handy();
        assertTrue(kopplungen.hatRecht(k, "klima"));
        assertTrue(kopplungen.hatRecht(k, "zusperren"));
        assertTrue(kopplungen.hatRecht(k, "push"));
        assertFalse(kopplungen.hatRecht(k, "aufsperren"));
        assertFalse("Cockpit hat keine Handy-Rechte", kopplungen.hatRecht(cockpit, "klima"));
    }

    // ------------------------------------------------------------------ §2 Gerät

    @Test
    public void geraeteschluesselEinmalUndTelegram() throws Exception {
        String k = handy();
        JSONObject b = new JSONObject().put("oeffentlicherSchluessel", pub(paar())).put("plattform", "android").put("name", "Pixel 8");
        assertTrue(post("/api/handy/v1/geraet", k, b).startsWith("HTTP/1.1 200"));
        assertEquals("[handy.schluessel[Pixel 8]]", telegram.toString());
        assertTrue("first write wins", post("/api/handy/v1/geraet", k, b.put("oeffentlicherSchluessel", pub(paar())))
                .startsWith("HTTP/1.1 409"));
        assertTrue(post("/api/handy/v1/geraet", handy(), new JSONObject().put("oeffentlicherSchluessel", "xyz"))
                .startsWith("HTTP/1.1 400"));
        // Liste zeigt nur, DASS ein Schlüssel da ist
        String liste = kopplungen.alle(jetzt).toString();
        assertTrue(liste.contains("\"schluessel\":true"));
        assertFalse(liste.contains(b.getString("oeffentlicherSchluessel").substring(0, 30)));
    }

    // ------------------------------------------------------------------ §3 Aktionen

    @Test
    public void klimaUeberDenRouter() throws Exception {
        String k = handy();
        weg = VehicleCommandRouter.Path.SDK;
        JSONObject r = json(post("/api/handy/v1/aktion", k, new JSONObject().put("aktion", "klima_an").put("tempC", 22).put("minuten", 10)));
        assertTrue(r.getBoolean("ok"));
        assertEquals("lokal", r.getString("weg"));
        assertTrue(r.getBoolean("bestaetigt"));
        assertTrue(befehle.get(0) instanceof VehicleCommandRouter.ClimateOnCommand);
        echo = BydDataCollector.ECHO_UNBEKANNT;
        r = json(post("/api/handy/v1/aktion", k, new JSONObject().put("aktion", "klima_aus")));
        assertTrue(r.getBoolean("ok"));
        assertFalse("Echo fehlt → nie als bestätigt", r.getBoolean("bestaetigt"));
        assertTrue(befehle.get(1) instanceof VehicleCommandRouter.ClimateOffCommand);
        assertEquals("werte", json(post("/api/handy/v1/aktion", k, new JSONObject().put("aktion", "klima_an").put("tempC", 40))).getString("grund"));
    }

    @Test
    public void zusperrenNurBeiAutoAus() throws Exception {
        String k = handy();
        autoAus = false;
        JSONObject r = json(post("/api/handy/v1/aktion", k, new JSONObject().put("aktion", "zusperren")));
        assertFalse(r.getBoolean("ok"));
        assertEquals("auto_laeuft", r.getString("grund"));
        assertTrue("Gate vorab: nichts ausgeführt", befehle.isEmpty());
        autoAus = true;
        r = json(post("/api/handy/v1/aktion", k, new JSONObject().put("aktion", "zusperren")));
        assertTrue(r.getBoolean("ok"));
        assertEquals("cloud", r.getString("weg"));
        assertTrue(befehle.get(0) instanceof VehicleCommandRouter.LockCommand);
    }

    @Test
    public void rechteUndRatenlimit() throws Exception {
        String k = handy();
        kopplungen.rechteSetzen(k, Arrays.asList("push"));
        assertEquals("nicht_erlaubt", json(post("/api/handy/v1/aktion", k, new JSONObject().put("aktion", "klima_aus"))).getString("grund"));
        kopplungen.rechteSetzen(k, Arrays.asList("klima"));
        for (int i = 0; i < 6; i++) {
            assertTrue(json(post("/api/handy/v1/aktion", k, new JSONObject().put("aktion", "klima_aus"))).getBoolean("ok"));
        }
        assertEquals("ratenlimit", json(post("/api/handy/v1/aktion", k, new JSONObject().put("aktion", "klima_aus"))).getString("grund"));
        assertEquals(6, befehle.size());
    }

    // ------------------------------------------------------------------ §3.1 Aufsperren

    @Test
    public void aufsperrenNurMitRechtSchluesselUndSignatur() throws Exception {
        String k = handy();
        KeyPair p = paar();
        JSONObject sig = signatur(p, k, jetzt, "nonce-a-0000001");
        // Recht standardmäßig aus
        assertEquals("nicht_erlaubt", json(post("/api/handy/v1/aktion", k, new JSONObject().put("aktion", "aufsperren").put("signatur", sig))).getString("grund"));
        kopplungen.rechteSetzen(k, Kopplungen.RECHTE);
        // ohne hinterlegten Schlüssel
        assertEquals("kein_geraet", json(post("/api/handy/v1/aktion", k, new JSONObject().put("aktion", "aufsperren").put("signatur", sig))).getString("grund"));
        kopplungen.schluesselMelden(k, pub(p), "android", "Pixel 8");
        // ohne Signatur / falsche Signatur
        assertEquals("signatur", json(post("/api/handy/v1/aktion", k, new JSONObject().put("aktion", "aufsperren"))).getString("grund"));
        assertEquals("signatur", json(post("/api/handy/v1/aktion", k, new JSONObject().put("aktion", "aufsperren")
                .put("signatur", signatur(paar(), k, jetzt, "nonce-a-0000002")))).getString("grund"));
        assertTrue("ohne gültige Signatur nie ein Befehl", befehle.isEmpty());
        assertTrue("jede Ablehnung gemeldet", telegram.size() >= 4);
        // Fehlversuche zählen gegen das Aufsperr-Limit (3 je 10 min) – bremst Durchprobieren
        assertEquals("ratenlimit", json(post("/api/handy/v1/aktion", k, new JSONObject().put("aktion", "aufsperren").put("signatur", sig))).getString("grund"));
        jetzt += 11 * 60_000L;
        sig = signatur(p, k, jetzt, "nonce-a-0000003");
        telegram.clear();
        // gültig
        JSONObject r = json(post("/api/handy/v1/aktion", k, new JSONObject().put("aktion", "aufsperren").put("signatur", sig)));
        assertTrue(r.toString(), r.getBoolean("ok"));
        assertEquals("cloud", r.getString("weg"));
        assertTrue(r.getBoolean("bestaetigt"));
        assertEquals(1, befehle.size());
        assertTrue(befehle.get(0) instanceof VehicleCommandRouter.HandyUnlockCommand);
        assertEquals("[handy.aufsperren_ok[Pixel 8]]", telegram.toString());
        // Wiederholung derselben Signatur
        assertEquals("signatur_nonce", json(post("/api/handy/v1/aktion", k, new JSONObject().put("aktion", "aufsperren").put("signatur", sig))).getString("grund"));
        assertEquals(1, befehle.size());
    }

    @Test
    public void aufsperrenZeitGateUndUnbestaetigt() throws Exception {
        String k = handy();
        KeyPair p = paar();
        kopplungen.rechteSetzen(k, Kopplungen.RECHTE);
        kopplungen.schluesselMelden(k, pub(p), "ios", "iPhone");
        assertEquals("signatur_zeit", json(post("/api/handy/v1/aktion", k, new JSONObject().put("aktion", "aufsperren")
                .put("signatur", signatur(p, k, jetzt - 31_000, "nonce-b-0000001")))).getString("grund"));
        autoAus = false;
        JSONObject r = json(post("/api/handy/v1/aktion", k, new JSONObject().put("aktion", "aufsperren")
                .put("signatur", signatur(p, k, jetzt, "nonce-b-0000002"))));
        assertEquals("auto_laeuft", r.getString("grund"));
        assertTrue("bei laufendem Auto nichts ausgeführt", befehle.isEmpty());
        autoAus = true;
        echo = BydDataCollector.ECHO_UNBEKANNT;
        telegram.clear();
        r = json(post("/api/handy/v1/aktion", k, new JSONObject().put("aktion", "aufsperren")
                .put("signatur", signatur(p, k, jetzt, "nonce-b-0000003"))));
        assertTrue(r.getBoolean("ok"));
        assertFalse(r.getBoolean("bestaetigt"));
        assertEquals("[handy.aufsperren_unbestaetigt[iPhone]]", telegram.toString());
    }

    // ------------------------------------------------------------------ §4 Push

    @Test
    public void pushEinAboJeKopplung() throws Exception {
        String k = handy();
        String p256 = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[65]);
        String auth = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]);
        JSONObject b = new JSONObject().put("endpoint", "https://ntfy.sh/abc").put("p256dh", p256).put("auth", auth);
        assertEquals("endpoint", json(post("/api/handy/v1/push", k, new JSONObject(b.toString()).put("endpoint", "http://x"))).getString("grund"));
        assertTrue(json(post("/api/handy/v1/push", k, b)).getBoolean("ok"));
        assertEquals("abo-1", kopplungen.pushId(k));
        assertTrue(json(post("/api/handy/v1/push", k, b)).getBoolean("ok"));
        assertEquals("altes Abo ersetzt", Arrays.asList("abo-1"), pushEntfernt);
        assertTrue(json(post("/api/handy/v1/push/aus", k, new JSONObject())).getBoolean("ok"));
        assertNull(kopplungen.pushId(k));
        assertEquals(Arrays.asList("abo-1", "abo-2"), pushEntfernt);
        kopplungen.rechteSetzen(k, Arrays.asList("klima"));
        assertEquals("nicht_erlaubt", json(post("/api/handy/v1/push", k, b)).getString("grund"));
        assertNotNull(k);
    }
}
