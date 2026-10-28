package at.greenchili.surfmate.bruecke;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** 0.1.25 Innen v2: Stück-Antworten ohne Base64, Range 2 MiB, Abbruch, Gegendruck, Medien-Kanal, Tagesgrenze. */
public class BrueckeV2Test {

    /** Lokale Antwort im Test: Status 206, Inhalt {@code inhalt}; merkt sich die Kopfzeilen. */
    static final class Quelle implements BrueckeWeiterleitung.StromAufruf {
        final byte[] inhalt;
        Map<String, String> kopf;
        boolean zu;

        Quelle(byte[] inhalt) { this.inhalt = inhalt; }

        public BrueckeWeiterleitung.Strom oeffnen(String m, String p, Map<String, String> k, byte[] b) {
            kopf = k;
            Map<String, String> rk = new java.util.HashMap<>();
            rk.put("content-type", "video/mp4");
            rk.put("content-range", "bytes 0-" + (inhalt.length - 1) + "/9999999");
            return new BrueckeWeiterleitung.Strom(206, rk, new ByteArrayInputStream(inhalt), () -> zu = true);
        }
    }

    static final class Ausgang implements BrueckeWeiterleitung.StueckAusgang {
        final List<byte[]> stuecke = new ArrayList<>();
        long schlange;
        int abfragen;

        public boolean senden(byte[] s) { stuecke.add(s); return true; }
        public long warteschlange() { abfragen++; long w = schlange; schlange = 0; return w; }
    }

    static byte[] req(String p, int v, String range) throws Exception {
        JSONObject h = new JSONObject().put("Authorization", "Bearer kid-k1");
        if (range != null) h.put("Range", range);
        return new JSONObject().put("id", 9).put("v", v).put("m", "GET").put("p", p).put("h", h)
                .toString().getBytes(StandardCharsets.UTF_8);
    }

    static BrueckeWeiterleitung weiter(Quelle q, long[] konto) {
        return new BrueckeWeiterleitung(jwt -> jwt.startsWith("kid-") ? jwt.substring(4) : null, new BrueckeTest.Mitschnitt(),
                kid -> null, (kid, r) -> true).mitStrom(q, n -> {
                    if (konto[0] < n || (n == 0 && konto[0] <= 0)) return false;
                    konto[0] -= n;
                    return true;
                });
    }

    static int id(byte[] s) {
        return ((s[1] & 0xff) << 24) | ((s[2] & 0xff) << 16) | ((s[3] & 0xff) << 8) | (s[4] & 0xff);
    }

    static JSONObject json(byte[] s) throws Exception {
        return new JSONObject(new String(s, 5, s.length - 5, StandardCharsets.UTF_8));
    }

    @Test
    public void stueckeOhneBase64UndRange2MiB() throws Exception {
        byte[] inhalt = new byte[150_000];
        new java.util.Random(1).nextBytes(inhalt);
        Quelle q = new Quelle(inhalt);
        Ausgang aus = new Ausgang();
        long[] konto = {10_000_000};
        assertTrue(BrueckeWeiterleitung.istV2(req("/video/id/ab", 2, null)));
        assertFalse(BrueckeWeiterleitung.istV2(req("/video/id/ab", 1, null)));
        weiter(q, konto).strom(req("/video/id/ab", 2, "bytes=0-"), "k1", false, aus, i -> false);
        assertEquals("v2 kürzt auf 2 MiB", "bytes=0-" + (2 * 1024 * 1024 - 1), q.kopf.get("range"));
        assertEquals(BrueckeWeiterleitung.V2_KOPF, aus.stuecke.get(0)[0]);
        assertEquals(9, id(aus.stuecke.get(0)));
        JSONObject kopf = json(aus.stuecke.get(0));
        assertEquals(206, kopf.getInt("s"));
        assertEquals("video/mp4", kopf.getJSONObject("h").getString("content-type"));
        ByteArrayOutputStream daten = new ByteArrayOutputStream();
        int i = 1;
        for (; aus.stuecke.get(i)[0] == BrueckeWeiterleitung.V2_DATEN; i++) {
            byte[] s = aus.stuecke.get(i);
            assertTrue("passt in eine Noise-Nachricht", s.length <= BrueckeRahmen.MAX_STUECK);
            daten.write(s, 5, s.length - 5);
        }
        assertEquals(3, i - 1);
        assertEquals(BrueckeWeiterleitung.V2_ENDE, aus.stuecke.get(i)[0]);
        assertEquals(i + 1, aus.stuecke.size());
        assertArrayEquals("roh, ohne Base64", inhalt, daten.toByteArray());
        assertTrue("lokale Verbindung zu", q.zu);
        assertEquals("Kamera-Daten gezählt", 10_000_000 - 150_000, konto[0]);
        // v1 behält 1 MiB
        assertEquals("bytes=0-" + (1024 * 1024 - 1), BrueckeWeiterleitung.begrenzeRange("bytes=0-"));
    }

    @Test
    public void medienKanalNurKameraUndTagesgrenze() throws Exception {
        Ausgang aus = new Ausgang();
        long[] konto = {100_000};
        weiter(new Quelle(new byte[10]), konto).strom(req("/api/vehicle/state", 2, null), "k1", true, aus, i -> false);
        assertEquals(BrueckeWeiterleitung.V2_FEHLER, aus.stuecke.get(0)[0]);
        assertEquals("medien_kanal", json(aus.stuecke.get(0)).getString("grund"));

        // Grenze mitten im Strom: 0x13 429
        Ausgang aus2 = new Ausgang();
        weiter(new Quelle(new byte[150_000]), konto).strom(req("/video/id/ab", 2, null), "k1", true, aus2, i -> false);
        byte[] letztes = aus2.stuecke.get(aus2.stuecke.size() - 1);
        assertEquals(BrueckeWeiterleitung.V2_FEHLER, letztes[0]);
        assertEquals(429, json(letztes).getInt("s"));
        assertEquals("medien_tageslimit", json(letztes).getString("grund"));

        // Grenze schon erreicht: gar nicht erst öffnen
        Quelle q = new Quelle(new byte[10]);
        Ausgang aus3 = new Ausgang();
        weiter(q, new long[] {0}).strom(req("/snapshot/0", 2, null), "k1", false, aus3, i -> false);
        assertEquals(429, json(aus3.stuecke.get(0)).getInt("s"));
        assertEquals("nicht geöffnet", null, q.kopf);

        // Prüfungen wie v1: falscher JWT → Fehlerstück mit 401
        Ausgang aus4 = new Ausgang();
        weiter(q, konto).strom(req("/video/id/ab", 2, null), "andere", false, aus4, i -> false);
        assertEquals(BrueckeWeiterleitung.V2_FEHLER, aus4.stuecke.get(0)[0]);
        assertEquals(401, json(aus4.stuecke.get(0)).getInt("s"));
    }

    @Test
    public void abbruchUndGegendruck() throws Exception {
        Ausgang aus = new Ausgang();
        int[] n = {0};
        weiter(new Quelle(new byte[200_000]), new long[] {10_000_000}).strom(req("/video/id/ab", 2, null), "k1", false, aus,
                i -> ++n[0] > 2);
        byte[] letztes = aus.stuecke.get(aus.stuecke.size() - 1);
        assertEquals(BrueckeWeiterleitung.V2_FEHLER, letztes[0]);
        assertEquals("abgebrochen", json(letztes).getString("grund"));
        assertTrue("vor dem Ende abgebrochen", aus.stuecke.size() < 5);

        Ausgang voll = new Ausgang();
        voll.schlange = 10 * BrueckeWeiterleitung.GEGENDRUCK;   // einmal voll, dann frei
        weiter(new Quelle(new byte[1000]), new long[] {10_000_000}).strom(req("/video/id/ab", 2, null), "k1", false, voll, i -> false);
        assertTrue("hat gewartet und erneut gefragt", voll.abfragen >= 2);
        assertEquals(BrueckeWeiterleitung.V2_ENDE, voll.stuecke.get(voll.stuecke.size() - 1)[0]);
    }

    @Test
    public void kanalV2AbbruchUndMedienKanal() throws Exception {
        BrueckeTest.Aufbau x = new BrueckeTest.Aufbau();
        x.b.einschalten();
        NoiseIK.Schluessel handy = NoiseIK.Schluessel.neu();
        x.b.handyAnmelden("k1", Bruecke.b64(handy.oeffentlich), Bruecke.b64(new byte[32]));
        x.gueltig.add("k1");
        CopyOnWriteArrayList<byte[]> bin = new CopyOnWriteArrayList<>();
        BrueckeVerbindung.Kanaele kk = new BrueckeVerbindung.Kanaele(x.b, weiter(new Quelle(new byte[70_000]), new long[] {10_000_000}),
                new BrueckeVerbindung.Sender() {
                    public boolean text(String t) { return true; }
                    public boolean binaer(byte[] b) { bin.add(b); return true; }
                }, () -> 1L);
        byte[] a = Bruecke.b64_32(x.b.autoOeffentlich());

        // Medien-Kanal: v1-Anfrage → 403 medien_kanal (v1-Format)
        kk.steuer("{\"t\":\"neu\",\"k\":4,\"art\":\"medien\"}");
        assertTrue(kk.offen.get(4).medien);
        NoiseIK i = NoiseIK.initiator(handy, a);
        kk.binaer(BrueckeTest.rahmen(4, i.schreibeNachricht1(new byte[0])));
        i.leseNachricht2(java.util.Arrays.copyOfRange(bin.get(0), 1, bin.get(0).length));
        senden(kk, i, 4, req("/api/vehicle/state", 1, null));
        warte(bin, 2);
        byte[] p1 = i.entschluesseln(java.util.Arrays.copyOfRange(bin.get(1), 1, bin.get(1).length));
        assertEquals(BrueckeRahmen.LETZTES, p1[0]);
        assertTrue(new String(p1, StandardCharsets.UTF_8).contains("medien_kanal"));

        // v2 über den Kanal: Kopf, 2 Datenstücke, Ende – jedes Stück eine Noise-Nachricht
        senden(kk, i, 4, req("/video/id/ab", 2, null));
        warte(bin, 6);
        byte[][] t = new byte[4][];
        for (int j = 0; j < 4; j++) t[j] = i.entschluesseln(java.util.Arrays.copyOfRange(bin.get(2 + j), 1, bin.get(2 + j).length));
        assertEquals(BrueckeWeiterleitung.V2_KOPF, t[0][0]);
        assertEquals(BrueckeWeiterleitung.V2_DATEN, t[1][0]);
        assertEquals(BrueckeWeiterleitung.V2_DATEN, t[2][0]);
        assertEquals(BrueckeWeiterleitung.V2_ENDE, t[3][0]);
        assertEquals(70_000, t[1].length - 5 + t[2].length - 5);

        // Abbruch wird sofort gemerkt (nicht hinter der Arbeit angestellt)
        senden(kk, i, 4, "{\"t\":\"ab\",\"id\":9}".getBytes(StandardCharsets.UTF_8));
        assertTrue(kk.offen.get(4).abgebrochen.contains(9L));
        assertEquals(9L, BrueckeVerbindung.Kanaele.abbruchId("{\"t\":\"ab\",\"id\":9}".getBytes(StandardCharsets.UTF_8)));
        assertEquals(-1L, BrueckeVerbindung.Kanaele.abbruchId(req("/x", 2, null)));
    }

    @Test
    public void tagesgrenzeAmAuto() throws Exception {
        BrueckeTest.Aufbau x = new BrueckeTest.Aufbau();
        x.b.einschalten();
        long mb = 1024L * 1024L, tag = 20_000L * 86_400_000L;
        assertEquals("Standard 500 MB", 500, x.b.medienTagMb());
        assertFalse("nur Stufen", x.b.medienTagMbSetzen(123));
        assertTrue(x.b.medienTagMbSetzen(100));
        assertTrue(x.b.medienVerbrauchen(60 * mb, tag));
        assertTrue("Stand landet auf der Platte (alle 10 MB)", x.platte[0].getJSONObject("medienHeute").getLong("bytes") >= 60 * mb);
        assertFalse("über 100 MB", x.b.medienVerbrauchen(50 * mb, tag));
        assertTrue(x.b.medienVerbrauchen(40 * mb, tag));
        assertFalse("voll: auch 0 Byte nicht mehr", x.b.medienVerbrauchen(0, tag));
        assertEquals(100 * mb, x.b.medienHeuteBytes(tag));
        assertTrue("Status zeigt Grenze und Verbrauch", x.b.status().has("medienTagMb") && x.b.status().has("medienHeuteMb"));
        assertTrue("neuer Tag", x.b.medienVerbrauchen(10 * mb, tag + 86_400_000L));
        assertTrue(x.b.medienTagMbSetzen(0));
        assertFalse("0 = keine Kamera-Daten übers Internet", x.b.medienVerbrauchen(0, tag + 86_400_000L));
    }

    private static void senden(BrueckeVerbindung.Kanaele kk, NoiseIK i, int k, byte[] anfrage) throws Exception {
        byte[] st = new byte[anfrage.length + 1];
        st[0] = BrueckeRahmen.LETZTES;
        System.arraycopy(anfrage, 0, st, 1, anfrage.length);
        kk.binaer(BrueckeTest.rahmen(k, i.verschluesseln(st)));
    }

    private static void warte(List<byte[]> bin, int n) throws Exception {
        long ende = System.currentTimeMillis() + 5000;
        while (bin.size() < n && System.currentTimeMillis() < ende) Thread.sleep(10);
        assertTrue("erwartet " + n + ", da " + bin.size(), bin.size() >= n);
    }
}
