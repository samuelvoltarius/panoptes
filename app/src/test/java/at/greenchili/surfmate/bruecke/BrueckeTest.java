package at.greenchili.surfmate.bruecke;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/** 0.1.20 Panoptes-Brücke, Auto-Teil (BRUECKE_VERTRAG §9 Auto) – ohne Netz. */
public class BrueckeTest {

    static final class FakeServer implements Bruecke.ServerApi {
        int antwortAnlegen = 201, antwortZugang = 201;
        final List<String> aufrufe = new ArrayList<>();
        public int postfachAnlegen(String s, String p, String gHash) { aufrufe.add("anlegen " + p + " " + gHash); return antwortAnlegen; }
        public int postfachLoeschen(String s, String p, String g) { aufrufe.add("loeschen " + p); return 204; }
        public int zugangHinzu(String s, String p, String g, String h) { aufrufe.add("zugang+ " + h); return antwortZugang; }
        public int zugangWeg(String s, String p, String g, String h) { aufrufe.add("zugang- " + h); return 204; }
    }

    static final class Aufbau {
        final JSONObject[] platte = {new JSONObject()};
        final FakeServer server = new FakeServer();
        final java.util.Set<String> gueltig = new java.util.HashSet<>();
        final Bruecke b = new Bruecke(new Bruecke.Platte() {
            public JSONObject lade() { return platte[0]; }
            public boolean speichere(JSONObject z) {
                try { platte[0] = new JSONObject(z.toString()); } catch (Exception e) { return false; }
                return true;
            }
        }, server, (kid, jetzt) -> gueltig.contains(kid));
    }

    static String b64(byte[] x) { return Bruecke.b64(x); }

    @Test
    public void einschaltenLegtPostfachAnUndAusRaeumtAuf() throws Exception {
        Aufbau x = new Aufbau();
        assertFalse(x.b.ein());
        assertNull(x.b.kopplungsBlock());
        assertEquals(Bruecke.Ergebnis.OK, x.b.einschalten());
        assertTrue(x.b.ein());
        String p = x.b.postfach(), g = x.b.geheimnis();
        assertNotNull(Bruecke.b64_32(p));
        assertNotNull(Bruecke.b64_32(g));
        assertEquals("Server bekommt nur sha256 über die rohen Bytes",
                "anlegen " + p + " " + Bruecke.sha256Hex(Bruecke.b64_32(g)), x.server.aufrufe.get(0));
        JSONObject block = x.b.kopplungsBlock();
        assertEquals(p, block.getString("p"));
        assertEquals(Bruecke.STANDARD_SERVER, block.getString("server"));
        assertArrayEquals(x.b.autoSchluessel().oeffentlich, Bruecke.b64_32(block.getString("a_pub")));
        assertFalse("Status ohne Geheimnisse", x.b.status().toString().contains(g));
        assertFalse(x.b.status().toString().contains(x.platte[0].getString("a")));

        assertEquals(Bruecke.Ergebnis.OK, x.b.ausschalten());
        assertFalse(x.b.ein());
        assertEquals("loeschen " + p, x.server.aufrufe.get(1));
        assertFalse("Schlüssel weg", x.platte[0].has("a") || x.platte[0].has("g") || x.platte[0].has("p"));
    }

    @Test
    public void serverNichtErreichbarBleibtAus() {
        Aufbau x = new Aufbau();
        x.server.antwortAnlegen = -1;
        assertEquals(Bruecke.Ergebnis.SERVER_NICHT_ERREICHBAR, x.b.einschalten());
        assertFalse(x.b.ein());
        x.server.antwortAnlegen = 409;
        assertEquals(Bruecke.Ergebnis.SERVER_ABGELEHNT, x.b.einschalten());
        assertFalse(x.b.ein());
    }

    @Test
    public void serverAdresseNurHttpsUndNichtImBetrieb() {
        Aufbau x = new Aufbau();
        assertFalse(x.b.serverSetzen("http://boese.example/bruecke"));
        assertFalse(x.b.serverSetzen("https://a.example/x?y=1"));
        assertFalse("http nur an 127.0.0.1", x.b.serverSetzen("http://10.0.2.2:8471/bruecke"));
        assertTrue(x.b.serverSetzen("http://127.0.0.1:8471/bruecke"));
        assertTrue(x.b.serverSetzen("https://eigen.example/bruecke/"));
        assertEquals("https://eigen.example/bruecke", x.b.server());
        x.b.einschalten();
        assertFalse("erst aus, dann wechseln", x.b.serverSetzen("https://anders.example/bruecke"));
        assertTrue(x.b.serverSetzen("https://eigen.example/bruecke"));
    }

    @Test
    public void handyAnmeldenUndSchluesselZuordnen() throws Exception {
        Aufbau x = new Aufbau();
        NoiseIK.Schluessel h = NoiseIK.Schluessel.neu();
        byte[] gh = new byte[32];
        gh[0] = 7;
        assertEquals("Brücke aus", Bruecke.HandyErgebnis.AUS, x.b.handyAnmelden("k1", b64(h.oeffentlich), b64(gh)));
        x.b.einschalten();
        assertEquals(Bruecke.HandyErgebnis.UNGUELTIG, x.b.handyAnmelden("k1", "kurz", b64(gh)));
        assertEquals(Bruecke.HandyErgebnis.UNGUELTIG, x.b.handyAnmelden("k1", b64(h.oeffentlich), "AAAA"));
        assertEquals(Bruecke.HandyErgebnis.OK, x.b.handyAnmelden("k1", b64(h.oeffentlich), b64(gh)));
        assertTrue(x.server.aufrufe.contains("zugang+ " + Bruecke.sha256Hex(gh)));
        assertNull("Kopplung nicht (mehr) gültig", x.b.kidFuerSchluessel(h.oeffentlich, 1L));
        x.gueltig.add("k1");
        assertEquals("k1", x.b.kidFuerSchluessel(h.oeffentlich, 1L));
        assertNull("fremder Schlüssel", x.b.kidFuerSchluessel(NoiseIK.Schluessel.neu().oeffentlich, 1L));
        assertEquals(1, x.b.anzahlHandys());

        // neuer Zugang ersetzt den alten (alter wird am Server gelöscht)
        byte[] gh2 = new byte[32];
        gh2[0] = 8;
        assertEquals(Bruecke.HandyErgebnis.OK, x.b.handyAnmelden("k1", b64(h.oeffentlich), b64(gh2)));
        assertTrue(x.server.aufrufe.contains("zugang- " + Bruecke.sha256Hex(gh)));

        assertTrue(x.b.handyEntfernen("k1"));
        assertTrue(x.server.aufrufe.contains("zugang- " + Bruecke.sha256Hex(gh2)));
        assertNull(x.b.kidFuerSchluessel(h.oeffentlich, 1L));
        assertFalse("idempotent", x.b.handyEntfernen("k1"));
    }

    // ------------------------------------------------------------------ Weiterleitung

    static final class Mitschnitt implements BrueckeWeiterleitung.Aufruf {
        String methode, pfad;
        Map<String, String> kopf;
        byte[] body;
        public BrueckeWeiterleitung.Antwort senden(String m, String p, Map<String, String> k, byte[] b) {
            methode = m; pfad = p; kopf = k; body = b;
            Map<String, String> rk = new java.util.HashMap<>();
            rk.put("content-type", "application/json");
            rk.put("set-cookie", "geheim=1");
            return new BrueckeWeiterleitung.Antwort(200, rk, "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
        }
    }

    static byte[] anfrage(String m, String p, String jwt, String extraKopf) throws Exception {
        JSONObject h = new JSONObject();
        if (jwt != null) h.put("Authorization", "Bearer " + jwt);
        if (extraKopf != null) h.put(extraKopf, "x");
        return new JSONObject().put("id", 7).put("m", m).put("p", p).put("h", h).put("b", "{\"a\":1}")
                .toString().getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void weiterleitungNurMitDemJwtDerEigenenKopplung() throws Exception {
        Mitschnitt mit = new Mitschnitt();
        BrueckeWeiterleitung w = new BrueckeWeiterleitung(jwt -> jwt.startsWith("kid-") ? jwt.substring(4) : null, mit);
        JSONObject r = new JSONObject(new String(w.bearbeiten(anfrage("GET", "/api/vehicle/state", null, null), "k1"), StandardCharsets.UTF_8));
        assertEquals(401, r.getInt("s"));
        r = new JSONObject(new String(w.bearbeiten(anfrage("GET", "/api/vehicle/state", "login-jwt", null), "k1"), StandardCharsets.UTF_8));
        assertEquals("Login-JWT gilt nicht", 401, r.getInt("s"));
        r = new JSONObject(new String(w.bearbeiten(anfrage("GET", "/api/vehicle/state", "kid-k2", null), "k1"), StandardCharsets.UTF_8));
        assertEquals("JWT einer anderen Kopplung", 401, r.getInt("s"));
        assertNull("nichts weitergereicht", mit.pfad);

        r = new JSONObject(new String(w.bearbeiten(anfrage("POST", "/api/handy/v1/aktion", "kid-k1", "Cookie"), "k1"), StandardCharsets.UTF_8));
        assertEquals(7, r.getInt("id"));
        assertEquals(200, r.getInt("s"));
        assertEquals("{\"ok\":true}", r.getString("b"));
        assertFalse("Set-Cookie bleibt drinnen", r.getJSONObject("h").has("set-cookie"));
        assertEquals("POST", mit.methode);
        assertEquals("/api/handy/v1/aktion", mit.pfad);
        assertFalse("Cookie wird nicht durchgereicht", mit.kopf.containsKey("cookie"));
        assertEquals("{\"a\":1}", new String(mit.body, StandardCharsets.UTF_8));

        r = new JSONObject(new String(w.bearbeiten(anfrage("CONNECT", "/x", "kid-k1", null), "k1"), StandardCharsets.UTF_8));
        assertEquals(405, r.getInt("s"));
        r = new JSONObject(new String(w.bearbeiten(anfrage("GET", "http://fremd/x", "kid-k1", null), "k1"), StandardCharsets.UTF_8));
        assertEquals(400, r.getInt("s"));
        r = new JSONObject(new String(w.bearbeiten(anfrage("GET", "/a b", "kid-k1", null), "k1"), StandardCharsets.UTF_8));
        assertEquals(400, r.getInt("s"));
    }

    @Test
    public void quelltextLoopbackMitTunnelKennzeichen() throws Exception {
        java.io.File f = new java.io.File("src/main/java/at/greenchili/surfmate/bruecke/BrueckeWeiterleitung.java");
        if (!f.isFile()) f = new java.io.File("app/" + f.getPath());
        String k = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        // AuthMiddleware: Tunnel-Kopfzeile schaltet die Loopback-Ausnahme ab → nur der Kopplungs-JWT zählt
        assertTrue(k.contains("c.setRequestProperty(\"Forwarded\", \"for=panoptes-bruecke\");"));
        assertTrue(k.contains("c.setInstanceFollowRedirects(false);"));
    }

    // ------------------------------------------------------------------ Kanäle (Handshake + Anfrage)

    @Test
    public void kanalVomHandshakeBisZurAntwort() throws Exception {
        Aufbau x = new Aufbau();
        x.b.einschalten();
        NoiseIK.Schluessel handy = NoiseIK.Schluessel.neu();
        byte[] gh = new byte[32];
        x.b.handyAnmelden("k1", b64(handy.oeffentlich), b64(gh));
        x.gueltig.add("k1");

        CopyOnWriteArrayList<byte[]> bin = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<String> text = new CopyOnWriteArrayList<>();
        BrueckeVerbindung.Sender s = new BrueckeVerbindung.Sender() {
            public boolean text(String t) { text.add(t); return true; }
            public boolean binaer(byte[] b) { bin.add(b); return true; }
        };
        Mitschnitt mit = new Mitschnitt();
        BrueckeWeiterleitung w = new BrueckeWeiterleitung(jwt -> jwt.startsWith("kid-") ? jwt.substring(4) : null, mit);
        BrueckeVerbindung.Kanaele kk = new BrueckeVerbindung.Kanaele(x.b, w, s, () -> 1L);

        kk.steuer("{\"t\":\"neu\",\"k\":3}");
        NoiseIK i = NoiseIK.initiator(handy, Bruecke.b64_32(x.b.autoOeffentlich()));
        kk.binaer(rahmen(3, i.schreibeNachricht1(new byte[0])));
        assertEquals(1, bin.size());
        assertEquals(3, bin.get(0)[0]);
        i.leseNachricht2(java.util.Arrays.copyOfRange(bin.get(0), 1, bin.get(0).length));

        // Anfrage in zwei Stücken (Sammler), Antwort kommt verschlüsselt zurück
        byte[] req = anfrage("GET", "/api/vehicle/state", "kid-k1", null);
        byte[] st1 = new byte[11], st2 = new byte[req.length - 10 + 1];
        st1[0] = BrueckeRahmen.WEITER;
        System.arraycopy(req, 0, st1, 1, 10);
        st2[0] = BrueckeRahmen.LETZTES;
        System.arraycopy(req, 10, st2, 1, req.length - 10);
        kk.binaer(rahmen(3, i.verschluesseln(st1)));
        kk.binaer(rahmen(3, i.verschluesseln(st2)));
        long ende = System.currentTimeMillis() + 5000;
        while (bin.size() < 2 && System.currentTimeMillis() < ende) Thread.sleep(10);
        assertEquals(2, bin.size());
        BrueckeRahmen.Sammler sm = new BrueckeRahmen.Sammler(1 << 20);
        byte[] antwort = sm.hinzu(i.entschluesseln(java.util.Arrays.copyOfRange(bin.get(1), 1, bin.get(1).length)));
        JSONObject r = new JSONObject(new String(antwort, StandardCharsets.UTF_8));
        assertEquals(200, r.getInt("s"));
        assertEquals("/api/vehicle/state", mit.pfad);

        // Widerruf der Kopplung schließt den Kanal
        kk.kopplungSchliessen("k1");
        assertTrue(text.contains("{\"t\":\"zu\",\"k\":3}"));
        assertTrue(kk.offen.isEmpty());
    }

    @Test
    public void unbekannterHandySchluesselWirdAbgewiesen() throws Exception {
        Aufbau x = new Aufbau();
        x.b.einschalten();
        CopyOnWriteArrayList<byte[]> bin = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<String> text = new CopyOnWriteArrayList<>();
        BrueckeVerbindung.Kanaele kk = new BrueckeVerbindung.Kanaele(x.b, new BrueckeWeiterleitung(j -> null, new Mitschnitt()),
                new BrueckeVerbindung.Sender() {
                    public boolean text(String t) { text.add(t); return true; }
                    public boolean binaer(byte[] b) { bin.add(b); return true; }
                }, () -> 1L);
        kk.steuer("{\"t\":\"neu\",\"k\":5}");
        NoiseIK fremd = NoiseIK.initiator(NoiseIK.Schluessel.neu(), Bruecke.b64_32(x.b.autoOeffentlich()));
        kk.binaer(rahmen(5, fremd.schreibeNachricht1(new byte[0])));
        assertTrue("keine Antwort an Fremde", bin.isEmpty());
        assertTrue(text.contains("{\"t\":\"zu\",\"k\":5}"));
        kk.steuer("{\"t\":\"zu\",\"k\":5}");   // idempotent
        // mehr als 4 Kanäle werden sofort geschlossen
        for (int k = 10; k < 15; k++) kk.steuer("{\"t\":\"neu\",\"k\":" + k + "}");
        assertEquals(BrueckeVerbindung.MAX_KANAELE, kk.offen.size());
        assertTrue(text.contains("{\"t\":\"zu\",\"k\":14}"));
        TimeUnit.MILLISECONDS.sleep(1);
    }

    @Test
    public void rahmenStueckelnUndGrenzen() throws Exception {
        byte[] gross = new byte[BrueckeRahmen.MAX_STUECK * 2 + 5];
        for (int i = 0; i < gross.length; i++) gross[i] = (byte) i;
        List<byte[]> st = BrueckeRahmen.stueckeln(gross);
        assertEquals(3, st.size());
        BrueckeRahmen.Sammler s = new BrueckeRahmen.Sammler(gross.length);
        assertNull(s.hinzu(st.get(0)));
        assertNull(s.hinzu(st.get(1)));
        assertArrayEquals(gross, s.hinzu(st.get(2)));
        assertEquals(1, BrueckeRahmen.stueckeln(new byte[0]).size());
        BrueckeRahmen.Sammler klein = new BrueckeRahmen.Sammler(10);
        try {
            klein.hinzu(BrueckeRahmen.stueckeln(new byte[11]).get(0));
            org.junit.Assert.fail();
        } catch (java.io.IOException erwartet) { }
    }

    @Test
    public void externGuardHandyPfade() {
        assertFalse(at.greenchili.surfmate.server.ExternGuard.blocks("POST", "/api/handy/v1/bruecke", "handy"));
        assertFalse(at.greenchili.surfmate.server.ExternGuard.blocks("POST", "/api/handy/v1/bruecke/aus", "handy"));
        assertTrue(at.greenchili.surfmate.server.ExternGuard.blocks("POST", "/api/handy/v1/bruecke", "cockpit"));
        assertTrue("Schalter nie für Kopplungen", at.greenchili.surfmate.server.ExternGuard.blocks("POST", "/api/bruecke", "handy"));
        assertTrue(at.greenchili.surfmate.server.ExternGuard.blocks("GET", "/api/bruecke", "handy"));
    }

    static byte[] rahmen(int k, byte[] m) {
        byte[] r = new byte[m.length + 1];
        r[0] = (byte) k;
        System.arraycopy(m, 0, r, 1, m.length);
        return r;
    }
}
