package at.greenchili.surfmate.bruecke;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 0.1.25 Anklopfen (Routenplaner): Merkmal im Auto, nur sha256 an die Brücke, {"t":"klopf"} → Route lesen. */
public class BrueckeKlopfTest {

    static final class Server implements Bruecke.ServerApi {
        final List<String> klopf = new ArrayList<>();
        int antwort = 204;
        public int postfachAnlegen(String s, String p, String gHash) { return 201; }
        public int postfachLoeschen(String s, String p, String g) { return 204; }
        public int zugangHinzu(String s, String p, String g, String h) { return 201; }
        public int zugangWeg(String s, String p, String g, String h) { return 204; }
        @Override public int klopfAnmelden(String s, String p, String g, String h) { klopf.add(h); return antwort; }
    }

    @Test
    public void merkmalUndAnmeldung() throws Exception {
        Server server = new Server();
        org.json.JSONObject[] platte = {new org.json.JSONObject()};
        Bruecke b = new Bruecke(new Bruecke.Platte() {
            public org.json.JSONObject lade() { return platte[0]; }
            public boolean speichere(org.json.JSONObject z) {
                try { platte[0] = new org.json.JSONObject(z.toString()); } catch (Exception e) { return false; }
                return true;
            }
        }, server, (kid, j) -> true);
        assertNull("Brücke aus: kein Merkmal", b.klopfMerkmal());
        b.einschalten();
        String k = b.klopfMerkmal();
        assertNotNull(Bruecke.b64_32(k));
        assertEquals("bleibt gleich", k, b.klopfMerkmal());
        server.antwort = -1;
        assertFalse(b.klopfAnmelden());
        server.antwort = 204;
        assertTrue(b.klopfAnmelden());
        assertEquals("Server bekommt nur den Hash", Bruecke.sha256Hex(Bruecke.b64_32(k)), server.klopf.get(1));
        assertTrue(b.klopfAnmelden());
        assertEquals("nur einmal angemeldet", 2, server.klopf.size());
        assertFalse("Merkmal nicht im Status", b.status().toString().contains(k));
        b.ausschalten();
        assertNull(b.klopfMerkmal());
    }

    @Test
    public void klopfRahmenLiestRoute() throws Exception {
        BrueckeTest.Aufbau x = new BrueckeTest.Aufbau();
        x.b.einschalten();
        BrueckeVerbindung.Kanaele kk = new BrueckeVerbindung.Kanaele(x.b, new BrueckeWeiterleitung(j -> null, new BrueckeTest.Mitschnitt()),
                new BrueckeVerbindung.Sender() {
                    public boolean text(String t) { return true; }
                    public boolean binaer(byte[] b) { return true; }
                }, () -> 1L);
        int[] n = {0};
        kk.klopfen = () -> n[0]++;
        kk.steuer("{\"t\":\"klopf\"}");
        assertEquals(1, n[0]);
    }

    @Test
    public void quelltextPlanerSchicktMerkmal() throws Exception {
        java.io.File f = new java.io.File("src/main/java/at/greenchili/surfmate/planer/PlanerRoute.java");
        if (!f.isFile()) f = new java.io.File("app/" + f.getPath());
        String k = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        assertTrue(k.contains("if (op.startsWith(\"meine-route\")) {"));
        assertTrue(k.contains("c.setRequestProperty(\"X-Klopf\", k);"));
        int a = k.indexOf("public void angeklopft(long now)");
        String rumpf = k.substring(a, k.indexOf("\n    }", a));
        assertTrue("nur lesen, nie abholen", a > 0 && rumpf.contains("status(now);") && !rumpf.contains("annehmen(")
                && !rumpf.contains("auto-abholen"));
    }
}
