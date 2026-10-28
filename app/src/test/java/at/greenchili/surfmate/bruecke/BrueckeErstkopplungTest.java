package at.greenchili.surfmate.bruecke;

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

/**
 * 0.1.24 Erstkopplung durch die Brücke ohne Direktweg (BRUECKE_VERTRAG §2.3, Koordination 28.10.): Einmal-Zugang zum
 * Kopplungs-Code, auf dem Einmal-Kanal nur {@code /auth/kopplung} und {@code /api/handy/v1/bruecke}.
 */
public class BrueckeErstkopplungTest {

    /** Eigene API im Test: Code „GUT“ koppelt (kid „neu1“), alles andere mitschreiben. */
    static final class Api implements BrueckeWeiterleitung.Aufruf {
        final List<String> pfade = new ArrayList<>();
        final List<String> bodies = new ArrayList<>();
        final List<Map<String, String>> koepfe = new ArrayList<>();

        public BrueckeWeiterleitung.Antwort senden(String m, String p, Map<String, String> k, byte[] b) throws Exception {
            String body = new String(b, StandardCharsets.UTF_8);
            pfade.add(m + " " + p);
            bodies.add(body);
            koepfe.add(k);
            Map<String, String> rk = new java.util.HashMap<>();
            rk.put("content-type", "application/json");
            String antwort = "{\"ok\":true}";
            if (p.equals("/auth/kopplung")) {
                antwort = "GUT".equals(new JSONObject(body).optString("code"))
                        ? "{\"success\":true,\"jwt\":\"kid-neu1\",\"kopplungId\":\"neu1\"}" : "{\"success\":false}";
            }
            return new BrueckeWeiterleitung.Antwort(200, rk, antwort.getBytes(StandardCharsets.UTF_8));
        }
    }

    static byte[] req(String m, String p, String jwt, JSONObject body) throws Exception {
        JSONObject h = new JSONObject();
        if (jwt != null) h.put("Authorization", "Bearer " + jwt);
        return new JSONObject().put("id", 1).put("m", m).put("p", p).put("h", h).put("b", body.toString())
                .toString().getBytes(StandardCharsets.UTF_8);
    }

    static JSONObject json(byte[] b) throws Exception {
        return new JSONObject(new String(b, StandardCharsets.UTF_8));
    }

    static BrueckeWeiterleitung weiter(Api api) {
        return new BrueckeWeiterleitung(jwt -> jwt.startsWith("kid-") ? jwt.substring(4) : null, api);
    }

    @Test
    public void einmalZugangLebtMitDemCode() throws Exception {
        BrueckeTest.Aufbau x = new BrueckeTest.Aufbau();
        assertNull("Brücke aus: kein Einmal-Zugang", x.b.einmalAnlegen(1000));
        x.b.einschalten();
        String g1 = x.b.einmalAnlegen(1000);
        assertNotNull(g1);
        String h1 = Bruecke.sha256Hex(Bruecke.b64_32(g1));
        assertTrue("Server kennt nur den Hash", x.server.aufrufe.contains("zugang+ " + h1));
        assertFalse("G_einmal liegt nicht auf der Platte", x.platte[0].toString().contains(g1));
        assertTrue(x.b.einmalOffen(999));
        assertFalse(x.b.einmalOffen(1000));
        assertFalse("nicht im Status", x.b.status().toString().contains(h1));

        // neuer Code ersetzt den alten Zugang
        String g2 = x.b.einmalAnlegen(2000);
        assertTrue(x.server.aufrufe.contains("zugang- " + h1));
        String h2 = Bruecke.sha256Hex(Bruecke.b64_32(g2));

        // Ablauf: Aufräumen löscht ihn am Server (auch nach Neustart, er steht in der Datei)
        x.b.einmalAufraeumen(1999);
        assertFalse(x.server.aufrufe.contains("zugang- " + h2));
        x.b.einmalAufraeumen(2000);
        assertTrue(x.server.aufrufe.contains("zugang- " + h2));
        assertFalse(x.b.einmalOffen(0));
        assertFalse(x.platte[0].has("einmal"));

        // Server lehnt ab → kein Einmal-Zugang, QR ohne z
        x.server.antwortZugang = 409;
        assertNull(x.b.einmalAnlegen(3000));
        assertFalse(x.b.einmalOffen(0));
    }

    @Test
    public void einmalKanalNurZweiSchritte() throws Exception {
        Api api = new Api();
        BrueckeWeiterleitung w = weiter(api);
        byte[] h = NoiseIK.Schluessel.neu().oeffentlich;

        // alles andere als die zwei Schritte: 403, nichts weitergereicht
        assertEquals(403, json(w.einmal(req("GET", "/api/vehicle/state", null, new JSONObject()), null, h).antwort).getInt("s"));
        assertEquals(403, json(w.einmal(req("POST", "/api/handy/v1/aktion", null, new JSONObject()), null, h).antwort).getInt("s"));
        assertEquals("Schritt 2 vor Schritt 1", 403,
                json(w.einmal(req("POST", "/api/handy/v1/bruecke", "kid-neu1", new JSONObject()), null, h).antwort).getInt("s"));
        assertTrue(api.pfade.isEmpty());

        // falscher Code: kein Kopplungs-id
        BrueckeWeiterleitung.EinmalSchritt s = w.einmal(req("POST", "/auth/kopplung", null,
                new JSONObject().put("code", "FALSCH").put("art", "cockpit")), null, h);
        assertNull(s.neueKid);
        // Art ist immer „handy“, ein mitgeschickter JWT/Login fällt weg
        s = w.einmal(req("POST", "/auth/kopplung", "login-jwt",
                new JSONObject().put("code", "GUT").put("name", "Pixel").put("art", "cockpit")), null, h);
        assertEquals("neu1", s.neueKid);
        assertFalse(s.fertig);
        JSONObject gesendet = new JSONObject(api.bodies.get(api.bodies.size() - 1));
        assertEquals("handy", gesendet.getString("art"));
        assertEquals("Pixel", gesendet.getString("name"));
        assertFalse(api.koepfe.get(api.koepfe.size() - 1).containsKey("authorization"));
        assertEquals("Kopplung nur einmal je Kanal", 403, json(w.einmal(req("POST", "/auth/kopplung", null,
                new JSONObject().put("code", "GUT")), "neu1", h).antwort).getInt("s"));

        // Schritt 2: h_pub muss der Handshake-Schlüssel sein
        JSONObject fremd = new JSONObject().put("h_pub", Bruecke.b64(NoiseIK.Schluessel.neu().oeffentlich)).put("g_handy", "x");
        assertEquals(403, json(w.einmal(req("POST", "/api/handy/v1/bruecke", "kid-neu1", fremd), "neu1", h).antwort).getInt("s"));
        JSONObject eigen = new JSONObject().put("h_pub", Bruecke.b64(h)).put("g_handy", "x");
        assertEquals("JWT einer anderen Kopplung", 401,
                json(w.einmal(req("POST", "/api/handy/v1/bruecke", "kid-andere", eigen), "neu1", h).antwort).getInt("s"));
        s = w.einmal(req("POST", "/api/handy/v1/bruecke", "kid-neu1", eigen), "neu1", h);
        assertEquals(200, json(s.antwort).getInt("s"));
        assertTrue(s.fertig);
        assertEquals("POST /api/handy/v1/bruecke", api.pfade.get(api.pfade.size() - 1));
    }

    @Test
    public void unbekannterSchluesselNurMitOffenemEinmalZugang() throws Exception {
        BrueckeTest.Aufbau x = new BrueckeTest.Aufbau();
        x.b.einschalten();
        long[] jetzt = {100};
        CopyOnWriteArrayList<byte[]> bin = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<String> text = new CopyOnWriteArrayList<>();
        Api api = new Api();
        BrueckeVerbindung.Kanaele kk = new BrueckeVerbindung.Kanaele(x.b, weiter(api), new BrueckeVerbindung.Sender() {
            public boolean text(String t) { text.add(t); return true; }
            public boolean binaer(byte[] b) { bin.add(b); return true; }
        }, () -> jetzt[0]);
        int[] fertig = {0};
        kk.einmalFertig = () -> fertig[0]++;
        byte[] a = Bruecke.b64_32(x.b.autoOeffentlich());

        // ohne Einmal-Zugang: abgewiesen wie bisher
        kk.steuer("{\"t\":\"neu\",\"k\":1}");
        kk.binaer(BrueckeTest.rahmen(1, NoiseIK.initiator(NoiseIK.Schluessel.neu(), a).schreibeNachricht1(new byte[0])));
        assertTrue(bin.isEmpty());
        assertTrue(text.contains("{\"t\":\"zu\",\"k\":1}"));

        // mit Einmal-Zugang: angenommen
        assertNotNull(x.b.einmalAnlegen(1000));
        NoiseIK.Schluessel handy = NoiseIK.Schluessel.neu();
        NoiseIK i = NoiseIK.initiator(handy, a);
        kk.steuer("{\"t\":\"neu\",\"k\":2}");
        kk.binaer(BrueckeTest.rahmen(2, i.schreibeNachricht1(new byte[0])));
        assertEquals(1, bin.size());
        i.leseNachricht2(java.util.Arrays.copyOfRange(bin.get(0), 1, bin.get(0).length));

        // zweiter unbekannter Kanal gleichzeitig: abgewiesen
        kk.steuer("{\"t\":\"neu\",\"k\":3}");
        kk.binaer(BrueckeTest.rahmen(3, NoiseIK.initiator(NoiseIK.Schluessel.neu(), a).schreibeNachricht1(new byte[0])));
        assertTrue(text.contains("{\"t\":\"zu\",\"k\":3}"));
        assertEquals(1, bin.size());

        // Schritt 1 + 2 durch den Kanal
        JSONObject r1 = senden(kk, i, bin, 2, req("POST", "/auth/kopplung", null, new JSONObject().put("code", "GUT")));
        assertEquals(200, r1.getInt("s"));
        assertEquals("neu1", new JSONObject(r1.getString("b")).getString("kopplungId"));
        JSONObject r2 = senden(kk, i, bin, 2, req("POST", "/api/handy/v1/bruecke", "kid-neu1",
                new JSONObject().put("h_pub", Bruecke.b64(handy.oeffentlich)).put("g_handy", "x")));
        assertEquals(200, r2.getInt("s"));
        assertEquals("Einmal-Zugang wird nach der Kopplung gelöscht", 1, fertig[0]);

        // abgelaufener Einmal-Zugang: wieder abgewiesen
        jetzt[0] = 1000;
        kk.steuer("{\"t\":\"neu\",\"k\":4}");
        kk.binaer(BrueckeTest.rahmen(4, NoiseIK.initiator(NoiseIK.Schluessel.neu(), a).schreibeNachricht1(new byte[0])));
        assertTrue(text.contains("{\"t\":\"zu\",\"k\":4}"));
    }

    private static JSONObject senden(BrueckeVerbindung.Kanaele kk, NoiseIK i, List<byte[]> bin, int k, byte[] anfrage)
            throws Exception {
        int vorher = bin.size();
        byte[] st = new byte[anfrage.length + 1];
        st[0] = BrueckeRahmen.LETZTES;
        System.arraycopy(anfrage, 0, st, 1, anfrage.length);
        kk.binaer(BrueckeTest.rahmen(k, i.verschluesseln(st)));
        long ende = System.currentTimeMillis() + 5000;
        while (bin.size() <= vorher && System.currentTimeMillis() < ende) Thread.sleep(10);
        BrueckeRahmen.Sammler sm = new BrueckeRahmen.Sammler(1 << 20);
        byte[] b = bin.get(bin.size() - 1);
        return json(sm.hinzu(i.entschluesseln(java.util.Arrays.copyOfRange(b, 1, b.length))));
    }

    @Test
    public void quelltextCodeUndQr() throws Exception {
        java.io.File f = new java.io.File("src/main/java/at/greenchili/surfmate/server/EinstellungenApiHandler.java");
        if (!f.isFile()) f = new java.io.File("app/" + f.getPath());
        String k = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        assertTrue(k.contains("Bruecke.get().einmalAnlegen(now + PairingCodes.TTL_MS)"));
        java.io.File s = new java.io.File("src/main/assets/web/local/system.html");
        if (!s.isFile()) s = new java.io.File("app/" + s.getPath());
        String html = new String(java.nio.file.Files.readAllBytes(s.toPath()), StandardCharsets.UTF_8);
        assertTrue(html.contains("if (j.b && einmal) j.b.z = einmal;"));
        assertTrue(html.contains("d.brueckeEinmal"));
    }
}
