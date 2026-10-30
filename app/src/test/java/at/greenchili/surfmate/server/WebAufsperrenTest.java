package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.auth.Kopplungen;
import at.greenchili.surfmate.byd.BydDataCollector;
import at.greenchili.surfmate.byd.routing.CloudPowerGate;
import at.greenchili.surfmate.byd.routing.VehicleCommandRouter;
import at.greenchili.surfmate.handy.HandySignatur;
import at.greenchili.surfmate.handy.PasskeyFenster;
import at.greenchili.surfmate.handy.WebAuthn;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * 0.1.33 Web-Aufsperren: Einrichten und Aufsperren mit Passkey. Ein Software-Authenticator im Test erzeugt
 * authenticatorData, clientDataJSON und Signatur. Maßstab: Jede Prüfung, die fehlschlägt, baut keinen Befehl.
 */
public class WebAufsperrenTest {

    static final String ID_B64 = WebAuthn.kodiere(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16});
    static final int UP = 0x01, UV = 0x04, BE = 0x08, BS = 0x10, AT = 0x40;

    final List<VehicleCommandRouter.VehicleCommand> befehle = new ArrayList<>();
    final List<String> telegram = new ArrayList<>();
    long jetzt = 1_800_000_000_000L;
    Kopplungen kopplungen;
    final JSONArray[] gespeichert = {new JSONArray()};
    boolean speichernGeht = true;
    HandyApiHandler.Umgebung vorher;
    KeyPair paar;
    String kid;
    int zaehlerImAuthenticator = 0;
    int nonceNr = 0;

    @Before
    public void auf() throws Exception {
        vorher = HandyApiHandler.umgebung;
        kopplungen = new Kopplungen(new Kopplungen.Speicher() {
            public JSONArray lade() { return gespeichert[0]; }
            public boolean speichere(JSONArray g) {
                if (!speichernGeht) return false;
                gespeichert[0] = g;
                return true;
            }
        });
        HandyApiHandler.kopplungen = kopplungen;
        HandyApiHandler.umgebung = new HandyApiHandler.Umgebung() {
            public VehicleCommandRouter.CommandResult ausfuehren(VehicleCommandRouter.VehicleCommand cmd) {
                befehle.add(cmd);
                return VehicleCommandRouter.CommandResult.success(VehicleCommandRouter.Path.CLOUD, "ok", 5);
            }
            public CloudPowerGate.Decision gate(String befehl) {
                return CloudPowerGate.evaluate(befehl, 0, jetzt - 1_000, true, false, jetzt);
            }
            public int echo(String aktion) { return BydDataCollector.ECHO_OK; }
            public void telegram(String key, Object... args) { telegram.add(key + Arrays.toString(args)); }
            public String pushAnlegen(String e, byte[] p, byte[] a, String l) { return "abo"; }
            public void pushEntfernen(String id) { }
            public long jetzt() { return jetzt; }
        };
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        paar = g.generateKeyPair();
        kid = kopplungen.anlegen("iPhone", Kopplungen.ART_HANDY, jetzt);
    }

    @After
    public void ab() {
        HandyApiHandler.umgebung = vorher;
        HandyApiHandler.kopplungen = null;
        AuthMiddleware.setRequestPeerForTest(null);
        AuthMiddleware.setRequestScopeForTest(null);
    }

    // ------------------------------------------------------------------ Software-Authenticator

    static byte[] rpHash(String rp) { return WebAuthn.sha256(rp.getBytes(StandardCharsets.UTF_8)); }

    static byte[] verbinde(byte[]... teile) {
        int n = 0;
        for (byte[] t : teile) n += t.length;
        byte[] out = new byte[n];
        int p = 0;
        for (byte[] t : teile) {
            System.arraycopy(t, 0, out, p, t.length);
            p += t.length;
        }
        return out;
    }

    static byte[] kopf(String rp, int flags, int zaehler) {
        return verbinde(rpHash(rp), new byte[]{(byte) flags, (byte) (zaehler >>> 24), (byte) (zaehler >>> 16),
                (byte) (zaehler >>> 8), (byte) zaehler});
    }

    static byte[] koordinate(BigInteger v) {
        byte[] b = v.toByteArray(), out = new byte[32];
        int n = Math.min(b.length, 32);
        System.arraycopy(b, b.length - n, out, 32 - n, n);
        return out;
    }

    byte[] cose() {
        ECPublicKey k = (ECPublicKey) paar.getPublic();
        return verbinde(new byte[]{(byte) 0xA5, 1, 2, 3, 0x26, 0x20, 1, 0x21, 0x58, 0x20}, koordinate(k.getW().getAffineX()),
                new byte[]{0x22, 0x58, 0x20}, koordinate(k.getW().getAffineY()));
    }

    byte[] anlageDaten(String rp, int flags, byte[] cose) {
        byte[] id = WebAuthn.dekodiere(ID_B64);
        return verbinde(kopf(rp, flags, 0), new byte[16], new byte[]{0, (byte) id.length}, id, cose);
    }

    static byte[] clientData(String typ, String challenge, String origin, String zusatz) {
        return ("{\"type\":\"" + typ + "\",\"challenge\":\"" + challenge + "\",\"origin\":\"" + origin + "\"" + zusatz + "}")
                .getBytes(StandardCharsets.UTF_8);
    }

    JSONObject anlage(String challenge) throws Exception {
        return anlage(clientData("webauthn.create", challenge, WebAuthn.ORIGIN, ""),
                anlageDaten(WebAuthn.RP_ID, UP | UV | AT, cose()));
    }

    static JSONObject anlage(byte[] cd, byte[] ad) throws Exception {
        return new JSONObject().put("credentialId", ID_B64).put("clientDataJSON", WebAuthn.kodiere(cd))
                .put("authenticatorData", WebAuthn.kodiere(ad)).put("name", "iPhone (Web-App)");
    }

    JSONObject assertion(long zeit, String nonce, byte[] cd, byte[] ad, KeyPair mit) throws Exception {
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(mit.getPrivate());
        s.update(ad);
        s.update(WebAuthn.sha256(cd));
        return new JSONObject().put("zeit", zeit).put("nonce", nonce).put("credentialId", ID_B64)
                .put("clientDataJSON", WebAuthn.kodiere(cd)).put("authenticatorData", WebAuthn.kodiere(ad))
                .put("signature", WebAuthn.kodiere(s.sign()));
    }

    String nonce() { return String.format("nonce-web-%06d", ++nonceNr); }

    byte[] cdGet(long zeit, String nonce) {
        return clientData("webauthn.get", WebAuthn.aufsperrChallenge(kid, zeit, nonce), WebAuthn.ORIGIN, "");
    }

    JSONObject gueltig(String nonce) throws Exception {
        return assertion(jetzt, nonce, cdGet(jetzt, nonce), kopf(WebAuthn.RP_ID, UP | UV, zaehlerImAuthenticator), paar);
    }

    // ------------------------------------------------------------------ Hilfen

    String post(String pfad, String k, JSONObject body) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HandyApiHandler.handle("POST", pfad, body.toString(), out, k);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    static JSONObject json(String antwort) throws Exception {
        return new JSONObject(antwort.substring(antwort.indexOf("\r\n\r\n") + 4));
    }

    String start() throws Exception {
        jetzt += 11_000;   // allgemeines Ratenlimit 6/min
        String a = post("/api/handy/v1/passkey/start", kid, new JSONObject());
        assertTrue(a, a.startsWith("HTTP/1.1 200"));
        return json(a).getString("challenge");
    }

    void eingerichtet() throws Exception {
        assertTrue(HandyApiHandler.passkeyOeffnen(kid).getBoolean("ok"));
        assertTrue(json(post("/api/handy/v1/passkey", kid, anlage(start()))).getBoolean("ok"));
        assertTrue(HandyApiHandler.passkeyBestaetigen(kid, true).getBoolean("aktiv"));
        telegram.clear();
    }

    JSONObject aufsperren(JSONObject webauthn) throws Exception {
        return json(post("/api/handy/v1/aktion", kid, new JSONObject().put("aktion", "aufsperren").put("webauthn", webauthn)));
    }

    void abgelehnt(String grund, JSONObject webauthn) throws Exception {
        int vor = befehle.size();
        JSONObject r = HandyApiHandler.aufsperren(new JSONObject().put("webauthn", webauthn), kid,
                HandyApiHandler.umgebung, jetzt);
        assertFalse(r.toString(), r.getBoolean("ok"));
        assertEquals(grund, r.getString("grund"));
        assertEquals("kein Befehl bei Ablehnung", vor, befehle.size());
    }

    // ------------------------------------------------------------------ Aufsperren

    @Test
    public void gueltigGenauEinmal() throws Exception {
        eingerichtet();
        String n = nonce();
        JSONObject w = gueltig(n);
        JSONObject r = HandyApiHandler.aufsperren(new JSONObject().put("webauthn", w), kid, HandyApiHandler.umgebung, jetzt);
        assertTrue(r.toString(), r.getBoolean("ok"));
        assertEquals(1, befehle.size());
        assertTrue(befehle.get(0) instanceof VehicleCommandRouter.HandyUnlockCommand);
        assertEquals("[handy.aufsperren_ok[iPhone (Web-App)]]", telegram.toString());
        abgelehnt(HandySignatur.GRUND_NONCE, w);
    }

    @Test
    public void ueberDenEndpunktMitRechtUndRatenlimit() throws Exception {
        eingerichtet();
        JSONObject r = aufsperren(gueltig(nonce()));
        assertTrue(r.toString(), r.getBoolean("ok"));
        kopplungen.rechteSetzen(kid, Arrays.asList("klima"));
        assertEquals("nicht_erlaubt", aufsperren(gueltig(nonce())).getString("grund"));
        assertEquals(1, befehle.size());
    }

    @Test
    public void falscheOrigin() throws Exception {
        eingerichtet();
        for (String o : new String[]{"http://panoptes.serveone.at", "https://panoptes.serveone.at.boese.at",
                "https://byd.serveone.at", "https://panoptes.serveone.at/", "https://PANOPTES.serveone.at",
                "https://panoptes.serveone.at:8443", ""}) {
            String n = nonce();
            byte[] cd = clientData("webauthn.get", WebAuthn.aufsperrChallenge(kid, jetzt, n), o, "");
            abgelehnt(HandySignatur.GRUND_SIGNATUR, assertion(jetzt, n, cd, kopf(WebAuthn.RP_ID, UP | UV, 0), paar));
        }
    }

    @Test
    public void crossOriginUndTypFalsch() throws Exception {
        eingerichtet();
        String n = nonce();
        String c = WebAuthn.aufsperrChallenge(kid, jetzt, n);
        byte[] ad = kopf(WebAuthn.RP_ID, UP | UV, 0);
        abgelehnt("signatur", assertion(jetzt, n, clientData("webauthn.get", c, WebAuthn.ORIGIN, ",\"crossOrigin\":true"), ad, paar));
        abgelehnt("signatur", assertion(jetzt, n, clientData("webauthn.get", c, WebAuthn.ORIGIN, ",\"crossOrigin\":\"ja\""), ad, paar));
        abgelehnt("signatur", assertion(jetzt, n, clientData("webauthn.get", c, WebAuthn.ORIGIN, ",\"topOrigin\":\"https://x\""), ad, paar));
        abgelehnt("signatur", assertion(jetzt, n, clientData("webauthn.create", c, WebAuthn.ORIGIN, ""), ad, paar));
        JSONObject ok = assertion(jetzt, n, clientData("webauthn.get", c, WebAuthn.ORIGIN, ",\"crossOrigin\":false"), ad, paar);
        assertTrue(HandyApiHandler.aufsperren(new JSONObject().put("webauthn", ok), kid, HandyApiHandler.umgebung, jetzt).getBoolean("ok"));
    }

    @Test
    public void falscherRpIdHash() throws Exception {
        eingerichtet();
        String n = nonce();
        abgelehnt("signatur", assertion(jetzt, n, cdGet(jetzt, n), kopf("byd.serveone.at", UP | UV, 0), paar));
    }

    @Test
    public void ohneUvOderUp() throws Exception {
        eingerichtet();
        String n = nonce();
        abgelehnt(HandySignatur.GRUND_UV, assertion(jetzt, n, cdGet(jetzt, n), kopf(WebAuthn.RP_ID, UP, 0), paar));
        abgelehnt("signatur", assertion(jetzt, n, cdGet(jetzt, n), kopf(WebAuthn.RP_ID, UV, 0), paar));
        abgelehnt("signatur", assertion(jetzt, n, cdGet(jetzt, n), kopf(WebAuthn.RP_ID, UP | UV | BS, 0), paar));
    }

    @Test
    public void abgelaufen() throws Exception {
        eingerichtet();
        for (long z : new long[]{jetzt - 30_001, jetzt + 30_001}) {
            String n = nonce();
            abgelehnt(HandySignatur.GRUND_ZEIT, assertion(z, n, cdGet(z, n), kopf(WebAuthn.RP_ID, UP | UV, 0), paar));
        }
    }

    @Test
    public void challengeNichtUebertragbar() throws Exception {
        eingerichtet();
        String n = nonce();
        byte[] ad = kopf(WebAuthn.RP_ID, UP | UV, 0);
        // fremde Kopplung
        String fremd = WebAuthn.aufsperrChallenge("k-andere", jetzt, n);
        abgelehnt("signatur", assertion(jetzt, n, clientData("webauthn.get", fremd, WebAuthn.ORIGIN, ""), ad, paar));
        // andere Aktion
        String zu = WebAuthn.kodiere(WebAuthn.sha256(HandySignatur.nachricht("zusperren", kid, jetzt, n)
                .getBytes(StandardCharsets.UTF_8)));
        abgelehnt("signatur", assertion(jetzt, n, clientData("webauthn.get", zu, WebAuthn.ORIGIN, ""), ad, paar));
        // Zeit oder Nonce im Feld passen nicht zur signierten Challenge
        JSONObject w = assertion(jetzt, n, cdGet(jetzt, n), ad, paar);
        abgelehnt("signatur", new JSONObject(w.toString()).put("zeit", jetzt + 1));
        abgelehnt("signatur", new JSONObject(w.toString()).put("nonce", nonce()));
    }

    @Test
    public void falscherSchluesselOderVeraendert() throws Exception {
        eingerichtet();
        String n = nonce();
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        abgelehnt("signatur", assertion(jetzt, n, cdGet(jetzt, n), kopf(WebAuthn.RP_ID, UP | UV, 0), g.generateKeyPair()));
        JSONObject w = gueltig(n);
        // UV nachträglich gesetzt: Signatur bricht
        JSONObject ohneUv = assertion(jetzt, n, cdGet(jetzt, n), kopf(WebAuthn.RP_ID, UP, 0), paar)
                .put("authenticatorData", WebAuthn.kodiere(kopf(WebAuthn.RP_ID, UP | UV, 0)));
        abgelehnt("signatur", ohneUv);
        abgelehnt("signatur", new JSONObject(w.toString()).put("clientDataJSON", WebAuthn.kodiere(clientData("webauthn.get", "x", WebAuthn.ORIGIN, ""))));
        abgelehnt("signatur", new JSONObject(w.toString()).put("credentialId", WebAuthn.kodiere(new byte[16])));
        abgelehnt("signatur", new JSONObject(w.toString()).put("signature", "AAAA"));
        abgelehnt("signatur", new JSONObject(w.toString()).put("signature", "kein+base64/=="));
        JSONObject ohne = new JSONObject(w.toString());
        ohne.remove("signature");
        abgelehnt("signatur", ohne);
    }

    @Test
    public void groessenGrenzen() throws Exception {
        eingerichtet();
        String n = nonce();
        byte[] gross = new byte[1100];
        Arrays.fill(gross, (byte) ' ');
        byte[] cd = verbinde(cdGet(jetzt, n), gross);
        abgelehnt("signatur", assertion(jetzt, n, cd, kopf(WebAuthn.RP_ID, UP | UV, 0), paar));
        byte[] viel = new byte[5000];
        abgelehnt("signatur", new JSONObject(gueltig(n).toString()).put("authenticatorData", WebAuthn.kodiere(viel)));
        abgelehnt("signatur", assertion(jetzt, n, cdGet(jetzt, n), new byte[36], paar));
    }

    @Test
    public void zaehler() throws Exception {
        eingerichtet();
        zaehlerImAuthenticator = 0;
        assertTrue("0 bleibt erlaubt", HandyApiHandler.aufsperren(new JSONObject().put("webauthn", gueltig(nonce())), kid, HandyApiHandler.umgebung, jetzt).getBoolean("ok"));
        zaehlerImAuthenticator = 5;
        assertTrue(HandyApiHandler.aufsperren(new JSONObject().put("webauthn", gueltig(nonce())), kid, HandyApiHandler.umgebung, jetzt).getBoolean("ok"));
        assertEquals(5, kopplungen.zaehler(kid));
        int vorher = befehle.size();
        zaehlerImAuthenticator = 5;
        abgelehnt(HandySignatur.GRUND_ZAEHLER, gueltig(nonce()));
        zaehlerImAuthenticator = 4;
        abgelehnt(HandySignatur.GRUND_ZAEHLER, gueltig(nonce()));
        zaehlerImAuthenticator = 0;
        abgelehnt(HandySignatur.GRUND_ZAEHLER, gueltig(nonce()));
        assertEquals(vorher, befehle.size());
        // Zähler lässt sich nicht speichern: nicht aufsperren
        zaehlerImAuthenticator = 6;
        speichernGeht = false;
        abgelehnt("speichern", gueltig(nonce()));
    }

    @Test
    public void wegeVertretenSichNie() throws Exception {
        eingerichtet();
        String n = nonce();
        // Web-Kopplung mit altem Signatur-Feld
        JSONObject alt = new JSONObject().put("zeit", jetzt).put("nonce", n).put("sig", "AAAA");
        JSONObject r = HandyApiHandler.aufsperren(new JSONObject().put("signatur", alt), kid, HandyApiHandler.umgebung, jetzt);
        assertEquals("signatur", r.getString("grund"));
        r = HandyApiHandler.aufsperren(new JSONObject().put("signatur", alt).put("webauthn", gueltig(n)), kid, HandyApiHandler.umgebung, jetzt);
        assertEquals("beide Felder", "signatur", r.getString("grund"));
        r = HandyApiHandler.aufsperren(new JSONObject(), kid, HandyApiHandler.umgebung, jetzt);
        assertEquals("signatur", r.getString("grund"));
        // Android-Kopplung mit webauthn-Feld
        String a = kopplungen.anlegen("Pixel", Kopplungen.ART_HANDY, jetzt);
        kopplungen.rechteSetzen(a, Kopplungen.RECHTE);
        kopplungen.schluesselMelden(a, Base64.getEncoder().encodeToString(paar.getPublic().getEncoded()), "android", "Pixel");
        r = HandyApiHandler.aufsperren(new JSONObject().put("webauthn", gueltig(nonce())), a, HandyApiHandler.umgebung, jetzt);
        assertEquals("signatur", r.getString("grund"));
        assertTrue(befehle.isEmpty());
    }

    // ------------------------------------------------------------------ Einrichten

    @Test
    public void ohneFensterKeinPasskey() throws Exception {
        kopplungen.rechteSetzen(kid, Kopplungen.RECHTE);
        String a = post("/api/handy/v1/passkey/start", kid, new JSONObject());
        assertTrue(a, a.startsWith("HTTP/1.1 403"));
        assertEquals("nicht_erlaubt", json(a).getString("grund"));
        assertEquals("nicht_erlaubt", json(post("/api/handy/v1/passkey", kid, anlage("x"))).getString("grund"));
        assertNull(kopplungen.schluessel(kid));
    }

    @Test
    public void ohneRechtKeinPasskey() throws Exception {
        assertTrue(HandyApiHandler.passkeyOeffnen(kid).getBoolean("ok"));
        kopplungen.rechteSetzen(kid, Arrays.asList("klima"));
        assertTrue(post("/api/handy/v1/passkey/start", kid, new JSONObject()).startsWith("HTTP/1.1 403"));
    }

    @Test
    public void fensterLaeuftAb() throws Exception {
        HandyApiHandler.passkeyOeffnen(kid);
        String c = start();
        jetzt += PasskeyFenster.FENSTER_MS;
        assertEquals("nicht_erlaubt", json(post("/api/handy/v1/passkey", kid, anlage(c))).getString("grund"));
        assertTrue(post("/api/handy/v1/passkey/start", kid, new JSONObject()).startsWith("HTTP/1.1 403"));
    }

    @Test
    public void challengeEinmalUndNurKurz() throws Exception {
        HandyApiHandler.passkeyOeffnen(kid);
        String c = start();
        jetzt += PasskeyFenster.CHALLENGE_MS;
        assertEquals("abgelaufen", "nicht_erlaubt", json(post("/api/handy/v1/passkey", kid, anlage(c))).getString("grund"));
        c = start();
        JSONObject falsch = anlage(clientData("webauthn.create", "falsch", WebAuthn.ORIGIN, ""),
                anlageDaten(WebAuthn.RP_ID, UP | UV | AT, cose()));
        assertEquals("signatur", json(post("/api/handy/v1/passkey", kid, falsch)).getString("grund"));
        assertEquals("verbraucht", "nicht_erlaubt", json(post("/api/handy/v1/passkey", kid, anlage(c))).getString("grund"));
    }

    @Test
    public void anlagePrueftAlles() throws Exception {
        HandyApiHandler.passkeyOeffnen(kid);
        byte[] gut = anlageDaten(WebAuthn.RP_ID, UP | UV | AT, cose());
        // falscher Typ, Origin, crossOrigin
        assertEquals("signatur", einmal(c -> clientData("webauthn.get", c, WebAuthn.ORIGIN, ""), gut));
        assertEquals("signatur", einmal(c -> clientData("webauthn.create", c, "http://panoptes.serveone.at", ""), gut));
        assertEquals("signatur", einmal(c -> clientData("webauthn.create", c, WebAuthn.ORIGIN, ",\"crossOrigin\":true"), gut));
        // rpIdHash, Flags
        assertEquals("signatur", einmal(WebAufsperrenTest::cd, anlageDaten("byd.serveone.at", UP | UV | AT, cose())));
        assertEquals(HandySignatur.GRUND_UV, einmal(WebAufsperrenTest::cd, anlageDaten(WebAuthn.RP_ID, UP | AT, cose())));
        assertEquals("signatur", einmal(WebAufsperrenTest::cd, anlageDaten(WebAuthn.RP_ID, UV | AT, cose())));
        assertEquals("ohne AT", "signatur", einmal(WebAufsperrenTest::cd, anlageDaten(WebAuthn.RP_ID, UP | UV, cose())));
        // COSE: anderes alg, crv, kty, gekürzt, verlängert, Punkt nicht auf der Kurve, nur Müll
        byte[] alg = cose(); alg[4] = 0x38;
        assertEquals("signatur", einmal(WebAufsperrenTest::cd, anlageDaten(WebAuthn.RP_ID, UP | UV | AT, alg)));
        byte[] crv = cose(); crv[6] = 2;
        assertEquals("signatur", einmal(WebAufsperrenTest::cd, anlageDaten(WebAuthn.RP_ID, UP | UV | AT, crv)));
        byte[] kty = cose(); kty[2] = 1;
        assertEquals("signatur", einmal(WebAufsperrenTest::cd, anlageDaten(WebAuthn.RP_ID, UP | UV | AT, kty)));
        assertEquals("signatur", einmal(WebAufsperrenTest::cd, anlageDaten(WebAuthn.RP_ID, UP | UV | AT, Arrays.copyOf(cose(), 76))));
        assertEquals("signatur", einmal(WebAufsperrenTest::cd, anlageDaten(WebAuthn.RP_ID, UP | UV | AT, Arrays.copyOf(cose(), 78))));
        byte[] punkt = cose(); punkt[76] ^= 1;
        assertEquals("signatur", einmal(WebAufsperrenTest::cd, anlageDaten(WebAuthn.RP_ID, UP | UV | AT, punkt)));
        assertEquals("signatur", einmal(WebAufsperrenTest::cd, anlageDaten(WebAuthn.RP_ID, UP | UV | AT, new byte[77])));
        assertEquals("signatur", einmal(WebAufsperrenTest::cd, new byte[3]));
        assertEquals("signatur", einmal(WebAufsperrenTest::cd, verbinde(kopf(WebAuthn.RP_ID, UP | UV | AT, 0), new byte[16], new byte[]{(byte) 0xFF, (byte) 0xFF})));
        // credentialId passt nicht
        String c = start();
        JSONObject a = anlage(clientData("webauthn.create", c, WebAuthn.ORIGIN, ""), gut).put("credentialId", WebAuthn.kodiere(new byte[16]));
        assertEquals("signatur", json(post("/api/handy/v1/passkey", kid, a)).getString("grund"));
        assertNull(PasskeyFenster.get().wartend(kid, jetzt));
    }

    interface Cd { byte[] mach(String challenge); }

    static byte[] cd(String c) { return clientData("webauthn.create", c, WebAuthn.ORIGIN, ""); }

    String einmal(Cd cd, byte[] ad) throws Exception {
        return json(post("/api/handy/v1/passkey", kid, anlage(cd.mach(start()), ad))).getString("grund");
    }

    @Test
    public void erstNachStimmtAktiv() throws Exception {
        HandyApiHandler.passkeyOeffnen(kid);
        JSONObject r = json(post("/api/handy/v1/passkey", kid, anlage(start())));
        String zahl = r.getString("pruefzahl");
        assertTrue(zahl, zahl.matches("[0-9]{4}"));
        assertEquals("dieselbe Zahl steht am Auto", zahl, kopplungen.alle(jetzt).getJSONObject(0)
                .getJSONObject("passkeyFenster").getString("pruefzahl"));
        // noch nicht aktiv
        assertNull(kopplungen.schluessel(kid));
        assertFalse(kopplungen.istPasskey(kid));
        assertTrue(telegram.isEmpty());
        JSONObject w = gueltig(nonce());
        JSONObject a = HandyApiHandler.aufsperren(new JSONObject().put("webauthn", w), kid, HandyApiHandler.umgebung, jetzt);
        assertEquals(HandySignatur.GRUND_KEIN_GERAET, a.getString("grund"));
        // zweite Anlage während die erste wartet
        assertTrue(post("/api/handy/v1/passkey", kid, anlage("x")).startsWith("HTTP/1.1 409"));
        // „Stimmt nicht“
        assertFalse(HandyApiHandler.passkeyBestaetigen(kid, false).getBoolean("aktiv"));
        assertNull(kopplungen.schluessel(kid));
        assertFalse("Fenster zu", PasskeyFenster.get().istOffen(kid, jetzt));
        assertFalse(HandyApiHandler.passkeyBestaetigen(kid, true).getBoolean("ok"));
        // neuer Anlauf, jetzt „Stimmt“
        telegram.clear();
        HandyApiHandler.passkeyOeffnen(kid);
        json(post("/api/handy/v1/passkey", kid, anlage(start())));
        assertTrue(HandyApiHandler.passkeyBestaetigen(kid, true).getBoolean("aktiv"));
        assertEquals("[handy.schluessel[iPhone (Web-App)]]", telegram.toString());
        assertTrue(kopplungen.istPasskey(kid));
        assertFalse(PasskeyFenster.get().istOffen(kid, jetzt));
        // zweiter Schlüssel
        assertEquals("schon_gemeldet", HandyApiHandler.passkeyOeffnen(kid).getString("grund"));
        assertTrue(post("/api/handy/v1/passkey/start", kid, new JSONObject()).startsWith("HTTP/1.1 409"));
        // die Liste verrät keinen Schlüssel
        String liste = kopplungen.alle(jetzt).toString();
        assertFalse(liste.contains(ID_B64));
        assertFalse(liste.contains(kopplungen.schluessel(kid).substring(30)));
    }

    @Test
    public void rechtGehtWaehrendDerRueckfrageVerloren() throws Exception {
        HandyApiHandler.passkeyOeffnen(kid);
        json(post("/api/handy/v1/passkey", kid, anlage(start())));
        kopplungen.rechteSetzen(kid, Arrays.asList("klima"));
        assertFalse(HandyApiHandler.passkeyBestaetigen(kid, true).getBoolean("ok"));
        assertNull(kopplungen.schluessel(kid));
    }

    @Test
    public void widerrufUndLoeschenRaeumenAuf() throws Exception {
        eingerichtet();
        assertTrue(kopplungen.schluesselLoeschen(kid));
        assertNull(kopplungen.credentialId(kid));
        assertEquals(0, kopplungen.zaehler(kid));
        assertFalse(kopplungen.istPasskey(kid));
        assertFalse(gespeichert[0].toString().contains("credentialId"));
        assertTrue("alter Weg bleibt gesperrt", kopplungen.nurPasskey(kid));
        // neu einrichten geht, Widerruf schließt ein offenes Fenster und nimmt alles mit
        HandyApiHandler.passkeyOeffnen(kid);
        kopplungen.widerrufen(kid);
        assertFalse(PasskeyFenster.get().istOffen(kid, jetzt));
        assertEquals("[]", gespeichert[0].toString());
    }

    // ------------------------------------------------------------------ Umgehung über den alten Schlüssel-Weg

    @Test
    public void geraetKannPasskeyNichtUmgehen() throws Exception {
        JSONObject b = new JSONObject().put("oeffentlicherSchluessel",
                Base64.getEncoder().encodeToString(paar.getPublic().getEncoded())).put("plattform", "android");
        // plattform „web“ gibt es nie über diesen Weg
        assertTrue(post("/api/handy/v1/geraet", kid, new JSONObject(b.toString()).put("plattform", "web")).startsWith("HTTP/1.1 403"));
        assertNull(kopplungen.schluessel(kid));
        // Einbahnstraße: Web-App sperrt den alten Weg selbst, Rücknahme gibt es nur am Auto
        assertTrue(json(post("/api/handy/v1/passkey/nur-passkey", kid, new JSONObject())).getBoolean("ok"));
        assertTrue(post("/api/handy/v1/geraet", kid, b).startsWith("HTTP/1.1 403"));
        assertNull(kopplungen.schluessel(kid));
        assertTrue(HandyApiHandler.passkeyZuruecknehmen(kid).getBoolean("ok"));
        assertFalse(kopplungen.nurPasskey(kid));
        assertFalse(kopplungen.hatRecht(kid, "aufsperren"));
        // Fenster am Auto sperrt ihn ebenfalls
        HandyApiHandler.passkeyOeffnen(kid);
        assertTrue(post("/api/handy/v1/geraet", kid, b).startsWith("HTTP/1.1 403"));
    }

    @Test
    public void alterSchluesselZaehltNichtBeiNurPasskey() throws Exception {
        kopplungen.rechteSetzen(kid, Kopplungen.RECHTE);
        kopplungen.schluesselMelden(kid, Base64.getEncoder().encodeToString(paar.getPublic().getEncoded()), "android", "Boese");
        kopplungen.nurPasskeySetzen(kid, true);
        String n = nonce();
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(paar.getPrivate());
        s.update(HandySignatur.nachricht("aufsperren", kid, jetzt, n).getBytes(StandardCharsets.UTF_8));
        JSONObject sig = new JSONObject().put("zeit", jetzt).put("nonce", n)
                .put("sig", Base64.getEncoder().encodeToString(s.sign()));
        JSONObject r = HandyApiHandler.aufsperren(new JSONObject().put("signatur", sig), kid, HandyApiHandler.umgebung, jetzt);
        assertEquals(HandySignatur.GRUND_KEIN_GERAET, r.getString("grund"));
        assertTrue(befehle.isEmpty());
        assertEquals("erst Schlüssel entfernen", "schon_gemeldet", HandyApiHandler.passkeyOeffnen(kid).getString("grund"));
    }

    // ------------------------------------------------------------------ Zugang

    @Test
    public void wegeNurFuerHandyUndNieFuerWebSitzung() {
        for (String p : new String[]{"/api/handy/v1/passkey/start", "/api/handy/v1/passkey", "/api/handy/v1/passkey/nur-passkey"}) {
            assertFalse(p, ExternGuard.blocks("POST", p, "handy"));
            assertTrue(ExternGuard.blocks("GET", p, "handy"));
            for (String art : new String[]{null, "cockpit", "homeassistant", "dienst", "web"}) {
                assertTrue(p + " " + art, ExternGuard.blocks("POST", p, art));
            }
        }
        for (String p : new String[]{"/api/kopplung/passkey/oeffnen", "/api/kopplung/passkey/bestaetigen",
                "/api/kopplung/passkey/zuruecknehmen"}) {
            assertTrue(p, ExternGuard.blocks("POST", p, "handy"));
            assertTrue(p, ExternGuard.blocks("POST", p, "cockpit"));
        }
    }

    @Test
    public void amAutoNurDerBildschirmSelbst() {
        AuthMiddleware.RelayProbe alt = AuthMiddleware.relayProbe;
        try {
            AuthMiddleware.relayProbe = (a, b) -> false;
            InetSocketAddress lokal = new InetSocketAddress("127.0.0.1", 40000);
            AuthMiddleware.setRequestScopeForTest(null);
            AuthMiddleware.setRequestPeerForTest(lokal);
            assertTrue(AuthMiddleware.isDirektAmAuto());
            AuthMiddleware.setRequestPeerForTest(new InetSocketAddress("100.64.0.9", 40000));
            assertFalse("aus dem Tailnet", AuthMiddleware.isDirektAmAuto());
            AuthMiddleware.setRequestPeerForTest(null);
            assertFalse("Tunnel-Merkmale", AuthMiddleware.isDirektAmAuto());
            AuthMiddleware.setRequestPeerForTest(lokal);
            AuthMiddleware.relayProbe = (a, b) -> true;
            assertFalse("über tailscaled", AuthMiddleware.isDirektAmAuto());
            AuthMiddleware.relayProbe = (a, b) -> false;
            AuthMiddleware.setRequestScopeForTest(at.greenchili.surfmate.auth.PairingCodes.SCOPE);
            assertFalse("Kopplung", AuthMiddleware.isDirektAmAuto());
            AuthMiddleware.setRequestScopeForTest(at.greenchili.surfmate.auth.WebSitzung.SCOPE);
            assertFalse("Web-Sitzung", AuthMiddleware.isDirektAmAuto());
        } finally {
            AuthMiddleware.relayProbe = alt;
        }
    }

    @Test
    public void standardAus() {
        assertFalse(kopplungen.hatRecht(kid, "aufsperren"));
        assertFalse(kopplungen.nurPasskey(kid));
    }
}
