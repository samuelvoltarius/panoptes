package at.greenchili.surfmate.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import at.greenchili.surfmate.auth.Kopplungen;
import at.greenchili.surfmate.handy.WebAuthn;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.Signature;
import java.util.Arrays;

/**
 * 0.1.39 Sicherheitspunkt (PLAN_BESITZER_SIGNATUR_013810): heikle Besitzer-Aufrufe nur mit Biometrie-Signatur eines
 * vertrauten Geräteschlüssels; Lücke „first write wins“ geschlossen; Bestand und Display/Login/Telegram nie ausgesperrt.
 */
public class BesitzerSignaturTest {

    static final long JETZT = 1_800_000_000_000L;

    private BesitzerHandyTest.Mem mem;
    private Kopplungen k;
    private final java.util.List<String> meldungen = new java.util.ArrayList<>();
    private String besitzer, zweit;

    @Before
    public void auf() {
        Kopplungen.besitzerMelder = null;
        Kopplungen.besitzerFrageMelder = null;
        mem = new BesitzerHandyTest.Mem();
        k = new Kopplungen(mem);
        besitzer = k.anlegen("Projektinhaber", "handy", JETZT - 60_000L, true);
        zweit = k.anlegen("Zweit", "handy", JETZT - 50_000L, true);
        BesitzerApiHandler.kopplungen = k;
        BesitzerApiHandler.wirkung = new BesitzerApiHandler.Wirkung() {
            @Override public String code(long now, String art, java.util.List<String> rechte) { return "ABCD2345"; }
            @Override public JSONObject brueckeEinmal(long bis) { return new JSONObject(); }
            @Override public boolean entscheiden(String ref, boolean erlauben, long now) { return "r1".equals(ref); }
            @Override public boolean waechter(boolean an) { return true; }
            @Override public boolean dienst(String name) { return true; }
            @Override public boolean pcErlaubt() { return true; }
            @Override public void melde(String text) { meldungen.add(text); }
        };
        BesitzerApiHandler.ratenlimitLeeren();
    }

    @After
    public void ab() {
        BesitzerApiHandler.kopplungen = null;
        BesitzerApiHandler.wirkung = new BesitzerApiHandler.Wirkung() { };
        BesitzerApiHandler.ratenlimitLeeren();
    }

    private JSONObject roh(String pfad, String kid, JSONObject body, long jetzt) throws Exception {
        return BesitzerApiHandler.antwort("POST", pfad, body.toString(), kid, jetzt);
    }

    private JSONObject signiert(String pfad, String kid, JSONObject body) throws Exception {
        return roh(pfad, kid, BesitzerSignaturHilfe.signiert(k, kid, pfad, body, JETZT), JETZT);
    }

    private static JSONObject code(String art, String... rechte) throws Exception {
        return new JSONObject().put("bestaetigt", true).put("art", art).put("rechte", new JSONArray(Arrays.asList(rechte)));
    }

    // ------------------------------------------------------------------ Vertrag: Zweck (Testvektoren für die App)

    @Test
    public void zweckTestvektoren() throws Exception {
        assertEquals("besitzer.code:art=pc;dauer=30tage;rechte=kamera,web", BesitzerSignatur.zweck(BesitzerApiHandler.CODE,
                new JSONObject().put("art", "pc").put("dauer", "30tage").put("rechte", new JSONArray().put("web").put("kamera"))));
        assertEquals("besitzer.code:art=homeassistant;rechte=", BesitzerSignatur.zweck(BesitzerApiHandler.CODE,
                new JSONObject().put("art", "homeassistant")));
        assertEquals("besitzer.entscheiden:erlauben=1;ref=r1", BesitzerSignatur.zweck(BesitzerApiHandler.ENTSCHEIDEN,
                new JSONObject().put("ref", "r1").put("erlauben", true)));
        assertEquals("besitzer.entfernen:id=abc", BesitzerSignatur.zweck(BesitzerApiHandler.ENTFERNEN,
                new JSONObject().put("id", "abc")));
        assertEquals("besitzer.weitergeben:id=abc", BesitzerSignatur.zweck(BesitzerApiHandler.WEITERGEBEN,
                new JSONObject().put("id", "abc")));
        assertEquals("besitzer.abgeben:", BesitzerSignatur.zweck(BesitzerApiHandler.ABGEBEN, new JSONObject()));
        assertEquals("besitzer.rechte:rechte=dateien,kamera", BesitzerSignatur.zweck(BesitzerApiHandler.SELBST,
                new JSONObject().put("rechte", new JSONArray().put("kamera").put("dateien").put("kamera"))));
        assertEquals("besitzer.waechter:an=0", BesitzerSignatur.zweck(BesitzerApiHandler.WAECHTER, new JSONObject()));
        assertEquals("besitzer.dienst:name=acc", BesitzerSignatur.zweck(BesitzerApiHandler.DIENST,
                new JSONObject().put("name", "acc")));
        // Trennzeichen in Werten: nicht signierbar statt maskiert
        assertNull(BesitzerSignatur.zweck(BesitzerApiHandler.ENTFERNEN, new JSONObject().put("id", "a;rechte=x")));
        assertNull(BesitzerSignatur.zweck(BesitzerApiHandler.SELBST, new JSONObject().put("rechte", new JSONArray().put("a,b"))));
        assertNull(BesitzerSignatur.zweck(BesitzerApiHandler.ENTFERNEN, new JSONObject().put("id", "a|b")));
        assertNull(BesitzerSignatur.zweck(BesitzerApiHandler.LISTE, new JSONObject()));
    }

    // ------------------------------------------------------------------ Pflicht

    @Test
    public void heiklePfadeOhneSignaturAbgelehnt() throws Exception {
        BesitzerSignaturHilfe.paar(k, besitzer);
        String[][] faelle = {
                {BesitzerApiHandler.CODE, code("homeassistant").toString()},
                {BesitzerApiHandler.ENTSCHEIDEN, "{\"bestaetigt\":true,\"ref\":\"r1\",\"erlauben\":true}"},
                {BesitzerApiHandler.ENTFERNEN, "{\"bestaetigt\":true,\"id\":\"x\"}"},
                {BesitzerApiHandler.WEITERGEBEN, "{\"bestaetigt\":true,\"id\":\"" + zweit + "\"}"},
                {BesitzerApiHandler.ABGEBEN, "{\"bestaetigt\":true}"},
                {BesitzerApiHandler.SELBST, "{\"bestaetigt\":true,\"rechte\":[\"kamera\"]}"},
        };
        for (String[] f : faelle) {
            JSONObject r = BesitzerApiHandler.antwort("POST", f[0], f[1], besitzer, JETZT);
            assertEquals(f[0], "signatur_noetig", r.optString("grund"));
            assertEquals(403, r.optInt("_status"));
            BesitzerApiHandler.ratenlimitLeeren();
        }
        assertFalse(k.istBesitzer(zweit));
        assertFalse(k.hatRecht(besitzer, Kopplungen.RECHT_KAMERA));
        assertTrue("jede Ablehnung wird gemeldet", meldungen.size() >= faelle.length);
    }

    @Test
    public void gueltigSigniertGehtDurch() throws Exception {
        JSONObject r = signiert(BesitzerApiHandler.CODE, besitzer, code("pc", "web"));
        assertTrue(r.toString(), r.getBoolean("ok"));
        assertTrue(signiert(BesitzerApiHandler.WEITERGEBEN, besitzer,
                new JSONObject().put("bestaetigt", true).put("id", zweit)).getBoolean("ok"));
        assertTrue(k.istBesitzer(zweit));
        assertTrue(signiert(BesitzerApiHandler.SELBST, besitzer,
                new JSONObject().put("bestaetigt", true).put("rechte", new JSONArray().put("kamera"))).getBoolean("ok"));
        assertTrue(k.hatRecht(besitzer, Kopplungen.RECHT_KAMERA));
    }

    @Test
    public void bestaetigtBleibtPflicht() throws Exception {
        JSONObject ohne = BesitzerSignaturHilfe.signiert(k, besitzer, BesitzerApiHandler.ABGEBEN, new JSONObject(), JETZT);
        assertEquals("bestaetigung", roh(BesitzerApiHandler.ABGEBEN, besitzer, ohne, JETZT).getString("grund"));
    }

    // ------------------------------------------------------------------ Bindung an die Nutzdaten, Wiederholung, Zeit

    @Test
    public void getauschteNutzdatenUngueltig() throws Exception {
        JSONObject harmlos = BesitzerSignaturHilfe.signiert(k, besitzer, BesitzerApiHandler.CODE, code("homeassistant"), JETZT);
        // Weg dazwischen macht daraus einen PC-Code mit Web-Recht
        JSONObject getauscht = new JSONObject(harmlos.toString()).put("art", "pc").put("rechte", new JSONArray().put("web"));
        assertEquals("signatur", roh(BesitzerApiHandler.CODE, besitzer, getauscht, JETZT).getString("grund"));
        // gleiche Signatur auf einem anderen Pfad
        JSONObject weiter = BesitzerSignaturHilfe.signiert(k, besitzer, BesitzerApiHandler.ENTFERNEN,
                new JSONObject().put("bestaetigt", true).put("id", zweit), JETZT);
        assertEquals("signatur", roh(BesitzerApiHandler.WEITERGEBEN, besitzer, weiter, JETZT).getString("grund"));
        assertFalse(k.istBesitzer(zweit));
    }

    @Test
    public void wiederholungUndZeit() throws Exception {
        JSONObject b = BesitzerSignaturHilfe.signiert(k, besitzer, BesitzerApiHandler.CODE, code("homeassistant"), JETZT);
        assertTrue(roh(BesitzerApiHandler.CODE, besitzer, b, JETZT).getBoolean("ok"));
        assertEquals("signatur_nonce", roh(BesitzerApiHandler.CODE, besitzer, b, JETZT + 1).getString("grund"));
        JSONObject alt = BesitzerSignaturHilfe.signiert(k, besitzer, BesitzerApiHandler.CODE, code("homeassistant"),
                JETZT - 31_000L);
        assertEquals("signatur_zeit", roh(BesitzerApiHandler.CODE, besitzer, alt, JETZT).getString("grund"));
    }

    @Test
    public void fremderSchluesselUngueltig() throws Exception {
        BesitzerSignaturHilfe.paar(k, besitzer);
        KeyPair fremd = BesitzerSignaturHilfe.neuesPaar();
        JSONObject b = code("homeassistant");
        String n = BesitzerSignaturHilfe.nonce();
        b.put("signatur", new JSONObject().put("zeit", JETZT).put("nonce", n).put("sig",
                BesitzerSignaturHilfe.sig(fremd, BesitzerSignatur.zweck(BesitzerApiHandler.CODE, b), besitzer, JETZT, n)));
        assertEquals("signatur", roh(BesitzerApiHandler.CODE, besitzer, b, JETZT).getString("grund"));
    }

    @Test
    public void fehlversucheEigenesLimitNichtDasDesBesitzers() throws Exception {
        BesitzerSignaturHilfe.paar(k, besitzer);
        for (int i = 0; i < BesitzerSignatur.MAX_FEHL_JE_MINUTE; i++) {
            assertEquals("signatur_noetig", roh(BesitzerApiHandler.ABGEBEN, besitzer,
                    new JSONObject().put("bestaetigt", true), JETZT).getString("grund"));
        }
        JSONObject r = roh(BesitzerApiHandler.ABGEBEN, besitzer, new JSONObject().put("bestaetigt", true), JETZT);
        assertEquals("zu_oft", r.getString("grund"));
        assertEquals(429, r.getInt("_status"));
        // eine Minute später geht der echte Besitzer wieder
        assertTrue(roh(BesitzerApiHandler.CODE, besitzer, BesitzerSignaturHilfe.signiert(k, besitzer,
                BesitzerApiHandler.CODE, code("homeassistant"), JETZT + 61_000L), JETZT + 61_000L).getBoolean("ok"));
    }

    // ------------------------------------------------------------------ Lücke „first write wins“

    @Test
    public void spaetGemeldeterSchluesselGiltErstNachBestaetigung() throws Exception {
        KeyPair dieb = BesitzerSignaturHilfe.neuesPaar();
        // ohne Schlüssel: klarer Grund statt still
        assertEquals("schluessel_fehlt", roh(BesitzerApiHandler.ABGEBEN, besitzer,
                new JSONObject().put("bestaetigt", true), JETZT).getString("grund"));
        assertEquals(Kopplungen.SIGNATUR_FEHLT, k.besitzerSignatur(besitzer));
        // jemand mit dem JWT meldet seinen eigenen Schlüssel – lange nach dem Koppeln
        long spaet = BesitzerSignaturHilfe.angelegt(k, besitzer) + Kopplungen.KOPPEL_FENSTER_MS + 1;
        assertEquals(Kopplungen.SCHLUESSEL_GESPEICHERT, k.schluesselMelden(besitzer,
                BesitzerSignaturHilfe.oeffentlich(dieb), "android", "Dieb", spaet));
        assertEquals(Kopplungen.SIGNATUR_UNBESTAETIGT, k.besitzerSignatur(besitzer));
        JSONObject b = new JSONObject().put("bestaetigt", true).put("id", zweit);
        String n = BesitzerSignaturHilfe.nonce();
        b.put("signatur", new JSONObject().put("zeit", JETZT).put("nonce", n).put("sig", BesitzerSignaturHilfe.sig(dieb,
                BesitzerSignatur.zweck(BesitzerApiHandler.WEITERGEBEN, b), besitzer, JETZT, n)));
        assertEquals("schluessel_unbestaetigt", roh(BesitzerApiHandler.WEITERGEBEN, besitzer, b, JETZT).getString("grund"));
        assertFalse(k.istBesitzer(zweit));
        // Bestätigen am Display nur mit dem passenden Fingerabdruck
        JSONObject eintrag = eintrag(k.alle(JETZT), besitzer);
        String fp = eintrag.getString("schluesselFingerabdruck");
        assertEquals("unbestaetigt", eintrag.getString("besitzerSignatur"));
        assertFalse(k.schluesselBesitzerBestaetigen(besitzer, "0000000000000000"));
        assertFalse(k.schluesselBesitzerBestaetigen(zweit, fp));
        assertTrue(k.schluesselBesitzerBestaetigen(besitzer, fp));
        assertEquals("bereit", eintrag(k.alle(JETZT), besitzer).getString("besitzerSignatur"));
        b.getJSONObject("signatur").put("nonce", n = BesitzerSignaturHilfe.nonce()).put("sig", BesitzerSignaturHilfe.sig(dieb,
                BesitzerSignatur.zweck(BesitzerApiHandler.WEITERGEBEN, b), besitzer, JETZT, n));
        assertTrue(roh(BesitzerApiHandler.WEITERGEBEN, besitzer, b, JETZT).getBoolean("ok"));
    }

    @Test
    public void schluesselImKoppelFensterGiltSofort() throws Exception {
        long a = BesitzerSignaturHilfe.angelegt(k, zweit);
        assertEquals(Kopplungen.SCHLUESSEL_GESPEICHERT, k.schluesselMelden(zweit,
                BesitzerSignaturHilfe.oeffentlich(BesitzerSignaturHilfe.neuesPaar()), "ios", "iPhone",
                a + Kopplungen.KOPPEL_FENSTER_MS));
        assertTrue(k.schluesselBesitzer(zweit));
    }

    @Test
    public void schluesselLoeschenNimmtVertrauenWeg() throws Exception {
        BesitzerSignaturHilfe.paar(k, besitzer);
        assertTrue(k.schluesselBesitzer(besitzer));
        assertTrue(k.schluesselLoeschen(besitzer));
        assertEquals(Kopplungen.SIGNATUR_FEHLT, k.besitzerSignatur(besitzer));
        // neuer Schlüssel nach neuem Fingerabdruck (lange nach dem Koppeln): erst wieder bestätigen
        KeyPair neu = BesitzerSignaturHilfe.neuesPaar();
        k.schluesselMelden(besitzer, BesitzerSignaturHilfe.oeffentlich(neu), "android", "Projektinhaber",
                JETZT + Kopplungen.KOPPEL_FENSTER_MS);
        assertEquals(Kopplungen.SIGNATUR_UNBESTAETIGT, k.besitzerSignatur(besitzer));
    }

    // ------------------------------------------------------------------ nie aussperren

    @Test
    public void ohneSchluesselBleibenLesenWaechterAblehnen() throws Exception {
        assertNull(k.schluessel(besitzer));
        assertTrue(BesitzerApiHandler.antwort("GET", BesitzerApiHandler.LISTE, null, besitzer, JETZT).getBoolean("ok"));
        assertTrue(BesitzerApiHandler.antwort("GET", BesitzerApiHandler.ANFRAGEN, null, besitzer, JETZT).getBoolean("ok"));
        assertTrue(roh(BesitzerApiHandler.WAECHTER, besitzer, new JSONObject().put("bestaetigt", true).put("an", true), JETZT)
                .getBoolean("ok"));
        assertTrue(roh(BesitzerApiHandler.DIENST, besitzer, new JSONObject().put("bestaetigt", true).put("name", "acc"), JETZT)
                .getBoolean("ok"));
        assertTrue("Ablehnen geht immer", roh(BesitzerApiHandler.ENTSCHEIDEN, besitzer,
                new JSONObject().put("bestaetigt", true).put("ref", "r1").put("erlauben", false), JETZT).getBoolean("ok"));
        assertEquals("fehlt", eintrag(k.alle(JETZT), besitzer).getString("besitzerSignatur"));
    }

    @Test
    public void mitgeschickteSignaturWirdAuchBeimWaechterGeprueft() throws Exception {
        JSONObject b = BesitzerSignaturHilfe.signiert(k, besitzer, BesitzerApiHandler.WAECHTER,
                new JSONObject().put("bestaetigt", true).put("an", true), JETZT);
        assertTrue(roh(BesitzerApiHandler.WAECHTER, besitzer, b, JETZT).getBoolean("ok"));
        b.put("an", false);
        assertEquals("signatur", roh(BesitzerApiHandler.WAECHTER, besitzer, b, JETZT).getString("grund"));
    }

    /**
     * Display, Login und Telegram /kopplungen laufen nicht über die Besitzer-API und kennen keine Signatur: ein Besitzer
     * ohne (vertrauten) Schlüssel kann dort weiter alles, was er vorher konnte.
     */
    @Test
    public void displayLoginTelegramOhneSignatur() throws Exception {
        String quelle = new String(Files.readAllBytes(Paths.get(
                "src/main/java/at/greenchili/surfmate/server/EinstellungenApiHandler.java")), StandardCharsets.UTF_8);
        assertFalse("Display/Login-Wege prüfen keine Besitzer-Signatur", quelle.contains("BesitzerSignatur"));
        // der neue Bestätigungs-Weg ist nur Display/Login (keine Kopplung, keine Web-Sitzung des Handys)
        int i = quelle.indexOf("\"/api/kopplung/schluessel-besitzer\"");
        assertTrue(i > 0);
        assertTrue(quelle.substring(i, i + 300).contains(
                "AuthMiddleware.isExternRequest() || AuthMiddleware.isHandyWebRequest()) return verboten(out);"));
        for (String p : BesitzerApiHandler.SCHREIBEN) assertFalse(p, p.startsWith("/api/kopplung"));
        // Telegram /kopplungen, Besitzer-Frage, Rechte und Entkoppeln am Display: alles ohne Schlüssel
        assertFalse(BesitzerApiHandler.handle("GET", "/api/kopplung/telegram-liste", null, besitzer,
                new java.io.ByteArrayOutputStream()));
        assertTrue(k.rechteSetzen(zweit, Arrays.asList("push", Kopplungen.RECHT_BESITZER)));
        assertTrue(k.istBesitzer(zweit));
        assertEquals(Kopplungen.WIDERRUFEN, k.widerrufen(zweit));
        assertEquals(Kopplungen.FRAGE_OK, k.besitzerFrageBeantworten(besitzer, false));
        assertTrue(k.istBesitzer(besitzer));
        assertNull(k.schluessel(besitzer));
    }

    // ------------------------------------------------------------------ Bestand (Besitzer #7 + Web-App)

    static final String CRED = WebAuthn.kodiere(new byte[]{9, 8, 7, 6, 5, 4, 3, 2, 1, 0, 1, 2, 3, 4, 5, 6});

    /** Stand wie am Auto vor 0.1.39: zwei Besitzer mit Schlüssel (nativ + Web-App-Passkey), ein Relay ohne. */
    private Kopplungen bestand(KeyPair nativ, KeyPair web) throws Exception {
        JSONArray a = new JSONArray();
        long alt = JETZT - 40L * 86_400_000L;
        a.put(new JSONObject().put("id", "quiet").put("name", "Quiet Hub").put("art", "handy").put("angelegt", alt)
                .put("zuletzt", JETZT).put("rechte", new JSONArray().put("push")).put("rechteStand", 3)
                .put("stand01388", 1).put("stand01389", 1));
        a.put(new JSONObject().put("id", "iphone7").put("name", "iPhone #7").put("art", "handy").put("angelegt", alt + 1)
                .put("zuletzt", JETZT).put("rechte", new JSONArray().put("push").put("web").put("besitzer"))
                .put("rechteStand", 3).put("stand01388", 1).put("stand01389", 1)
                .put("geraeteSchluessel", BesitzerSignaturHilfe.oeffentlich(nativ)).put("plattform", "ios")
                .put("geraeteName", "iPhone #7"));
        a.put(new JSONObject().put("id", "webapp").put("name", "iPhone (Web-App)").put("art", "handy").put("angelegt", alt + 2)
                .put("zuletzt", JETZT).put("rechte", new JSONArray().put("push").put("besitzer")).put("rechteStand", 3)
                .put("stand01388", 1).put("stand01389", 1)
                .put("geraeteSchluessel", BesitzerSignaturHilfe.oeffentlich(web)).put("plattform", "web")
                .put("credentialId", CRED).put("rpId", WebAuthn.RP_ID).put("zaehler", 0).put("nurPasskey", true)
                .put("synchronisiert", true).put("geraeteName", "iPhone (Web-App)"));
        BesitzerHandyTest.Mem m = new BesitzerHandyTest.Mem();
        m.inhalt = a;
        Kopplungen b = new Kopplungen(m);
        BesitzerApiHandler.kopplungen = b;
        return b;
    }

    @Test
    public void bestandNachUpdateGehtWeiter() throws Exception {
        KeyPair nativ = BesitzerSignaturHilfe.neuesPaar(), web = BesitzerSignaturHilfe.neuesPaar();
        Kopplungen b = bestand(nativ, web);
        assertTrue(b.schluesselBesitzer("iphone7"));
        assertTrue(b.schluesselBesitzer("webapp"));
        assertEquals(Kopplungen.SIGNATUR_FEHLT, b.besitzerSignatur("quiet"));
        // natives iPhone (Secure Enclave): signiert
        JSONObject c = code("homeassistant");
        String n = BesitzerSignaturHilfe.nonce();
        c.put("signatur", new JSONObject().put("zeit", JETZT).put("nonce", n).put("sig",
                BesitzerSignaturHilfe.sig(nativ, BesitzerSignatur.zweck(BesitzerApiHandler.CODE, c), "iphone7", JETZT, n)));
        JSONObject r1 = roh(BesitzerApiHandler.CODE, "iphone7", c, JETZT);
        assertTrue(r1.toString(), r1.getBoolean("ok"));
        assertEquals("signatur_nonce", roh(BesitzerApiHandler.CODE, "iphone7", c, JETZT).getString("grund"));
        // Web-App mit Passkey: Challenge aus dem Zweck
        JSONObject s = new JSONObject().put("bestaetigt", true).put("rechte", new JSONArray().put("kamera"));
        s.put("webauthn", assertion(web, BesitzerSignatur.zweck(BesitzerApiHandler.SELBST, s), "webapp", 1));
        JSONObject r = roh(BesitzerApiHandler.SELBST, "webapp", s, JETZT);
        assertTrue(r.toString(), r.getBoolean("ok"));
        assertTrue(b.hatRecht("webapp", Kopplungen.RECHT_KAMERA));
        assertEquals(1, b.zaehler("webapp"));
        // gleiche Passkey-Antwort noch einmal: Zähler/Nonce
        assertFalse(roh(BesitzerApiHandler.SELBST, "webapp", s, JETZT).optBoolean("ok"));
        // Passkey-Kopplung nimmt keine Keystore-Signatur (die Wege vertreten sich nie)
        JSONObject x = new JSONObject().put("bestaetigt", true);
        n = BesitzerSignaturHilfe.nonce();
        x.put("signatur", new JSONObject().put("zeit", JETZT).put("nonce", n).put("sig",
                BesitzerSignaturHilfe.sig(web, BesitzerSignatur.zweck(BesitzerApiHandler.ABGEBEN, x), "webapp", JETZT, n)));
        assertEquals("signatur", roh(BesitzerApiHandler.ABGEBEN, "webapp", x, JETZT).getString("grund"));
        // die Marke wirkt nur einmal: neu geladen bleibt es so, ein später gemeldeter Schlüssel ist kein Bestand
        Kopplungen neu = new Kopplungen(memVon(b));
        assertTrue(neu.schluesselBesitzer("iphone7"));
        neu.schluesselMelden("quiet", BesitzerSignaturHilfe.oeffentlich(BesitzerSignaturHilfe.neuesPaar()), "android",
                "Quiet Hub", JETZT);
        assertEquals(Kopplungen.SIGNATUR_UNBESTAETIGT, neu.besitzerSignatur("quiet"));
    }

    @Test
    public void passkeyAufsperrenUnveraendert() throws Exception {
        // die Verallgemeinerung ändert die Aufsperr-Challenge nicht
        assertEquals(WebAuthn.challenge("aufsperren", "kid", 5L, "nonce-123"), WebAuthn.aufsperrChallenge("kid", 5L, "nonce-123"));
    }

    private BesitzerHandyTest.Mem memVon(Kopplungen b) throws Exception {
        BesitzerHandyTest.Mem m = new BesitzerHandyTest.Mem();
        java.lang.reflect.Field f = Kopplungen.class.getDeclaredField("speicher");
        f.setAccessible(true);
        m.inhalt = ((BesitzerHandyTest.Mem) f.get(b)).inhalt;
        return m;
    }

    private static JSONObject assertion(KeyPair p, String zweck, String kid, int zaehler) throws Exception {
        String n = BesitzerSignaturHilfe.nonce();
        byte[] cd = ("{\"type\":\"webauthn.get\",\"challenge\":\"" + WebAuthn.challenge(zweck, kid, JETZT, n)
                + "\",\"origin\":\"" + WebAuthn.ORIGIN + "\"}").getBytes(StandardCharsets.UTF_8);
        byte[] rp = WebAuthn.sha256(WebAuthn.RP_ID.getBytes(StandardCharsets.UTF_8));
        byte[] ad = Arrays.copyOf(rp, rp.length + 5);
        ad[rp.length] = 0x01 | 0x04;   // UP | UV
        ad[rp.length + 4] = (byte) zaehler;
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(p.getPrivate());
        s.update(ad);
        s.update(WebAuthn.sha256(cd));
        return new JSONObject().put("zeit", JETZT).put("nonce", n).put("credentialId", CRED)
                .put("clientDataJSON", WebAuthn.kodiere(cd)).put("authenticatorData", WebAuthn.kodiere(ad))
                .put("signature", WebAuthn.kodiere(s.sign()));
    }

    private static JSONObject eintrag(JSONArray a, String id) {
        for (int i = 0; i < a.length(); i++) if (id.equals(a.optJSONObject(i).optString("id"))) return a.optJSONObject(i);
        throw new AssertionError("kein Eintrag " + id);
    }
}
