package at.greenchili.surfmate.bruecke;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 0.1.25 LIVE über die Brücke: eine Sitzung, Rahmengrenzen, Lebenszeichen, Abbruch, Gegendruck, Tagesgrenze. */
public class BrueckeLiveTest {

    @After
    public void frei() { BrueckeLive.freigeben(); }

    /** Nachgebaute Stream-Quelle: der Test schiebt Nachrichten selbst hinein. */
    static final class Quelle implements BrueckeLive.Quelle {
        volatile Empfaenger e;
        final List<String> texte = new CopyOnWriteArrayList<>();
        volatile String stufe;
        volatile boolean zu;

        public Verbindung oeffnen(String stufe, Empfaenger e) {
            this.stufe = stufe;
            this.e = e;
            return new Verbindung() {
                public void text(String s) { texte.add(s); }
                public void schliessen() { zu = true; }
            };
        }
    }

    static final class Ausgang implements BrueckeWeiterleitung.StueckAusgang {
        final List<byte[]> stuecke = new CopyOnWriteArrayList<>();
        volatile long schlange;
        public boolean senden(byte[] s) { stuecke.add(s); return true; }
        public long warteschlange() { return schlange; }
    }

    static JSONObject json(byte[] s) throws Exception {
        return new JSONObject(new String(s, 5, s.length - 5, StandardCharsets.UTF_8));
    }

    static void warte(java.util.function.BooleanSupplier b) throws Exception {
        long ende = System.currentTimeMillis() + 5000;
        while (!b.getAsBoolean() && System.currentTimeMillis() < ende) Thread.sleep(5);
        assertTrue(b.getAsBoolean());
    }

    @Test
    public void rahmenGrenzenStoppUndEineSitzung() throws Exception {
        Quelle q = new Quelle();
        Ausgang aus = new Ausgang();
        long[] uhr = {1000};
        long[] konto = {1_000_000};
        BrueckeWeiterleitung.MedienKonto k = n -> {
            if (konto[0] < n || (n == 0 && konto[0] <= 0)) return false;
            konto[0] -= n;
            return true;
        };
        BrueckeLive.Sitzung s = new BrueckeLive.Sitzung(5, uhr[0]);
        Thread t = new Thread(() -> BrueckeLive.laufen(s, "ULTRA_LOW", q, aus, k, () -> uhr[0]));
        t.start();
        warte(() -> q.e != null);
        assertEquals("ULTRA_LOW", q.stufe);
        assertTrue("Schlüsselbild beim Start angefordert", q.texte.contains("keyframe"));
        JSONObject kopf = json(aus.stuecke.get(0));
        assertEquals(BrueckeWeiterleitung.V2_KOPF, aus.stuecke.get(0)[0]);
        assertTrue(kopf.getBoolean("live"));

        // zweite Sitzung gleichzeitig: 409
        Ausgang aus2 = new Ausgang();
        BrueckeLive.laufen(new BrueckeLive.Sitzung(6, uhr[0]), "ULTRA_LOW", new Quelle(), aus2, k, () -> uhr[0]);
        assertEquals(409, json(aus2.stuecke.get(0)).getInt("s"));

        // kleine Nachricht: ein 0x14; große (100 KB): 0x11 + 0x14
        q.e.nachricht(new byte[1000]);
        q.e.nachricht(new byte[100_000]);
        assertEquals(BrueckeLive.V2_LIVE_ENDE, aus.stuecke.get(1)[0]);
        assertEquals(BrueckeWeiterleitung.V2_DATEN, aus.stuecke.get(2)[0]);
        assertEquals(BrueckeLive.V2_LIVE_ENDE, aus.stuecke.get(3)[0]);
        assertEquals(100_000, aus.stuecke.get(2).length - 5 + aus.stuecke.get(3).length - 5);
        assertEquals("zählt gegen die Tagesgrenze", 1_000_000 - 101_000, konto[0]);

        // Gegendruck: verwerfen, danach Schlüsselbild anfordern
        int vorher = aus.stuecke.size();
        q.texte.clear();
        aus.schlange = 10 * BrueckeWeiterleitung.GEGENDRUCK;
        q.e.nachricht(new byte[500]);
        assertEquals("verworfen", vorher, aus.stuecke.size());
        aus.schlange = 0;
        q.e.nachricht(new byte[500]);
        assertTrue(q.texte.contains("keyframe"));
        assertEquals(vorher + 1, aus.stuecke.size());

        // 120 s ohne Lebenszeichen → Stopp 408
        uhr[0] += BrueckeLive.OHNE_DA_MS - 1;
        s.da(uhr[0]);
        uhr[0] += BrueckeLive.OHNE_DA_MS;
        t.join(5000);
        byte[] letztes = aus.stuecke.get(aus.stuecke.size() - 1);
        assertEquals(BrueckeWeiterleitung.V2_FEHLER, letztes[0]);
        assertEquals(408, json(letztes).getInt("s"));
        assertTrue("lokale Verbindung zu", q.zu);

        // danach wieder frei
        Ausgang aus3 = new Ausgang();
        BrueckeLive.Sitzung s3 = new BrueckeLive.Sitzung(7, uhr[0]);
        Quelle q3 = new Quelle();
        Thread t3 = new Thread(() -> BrueckeLive.laufen(s3, "LOW", q3, aus3, k, () -> uhr[0]));
        t3.start();
        warte(() -> q3.e != null);
        s3.stopp = true;   // wie {"t":"ab"}
        t3.join(5000);
        assertEquals(499, json(aus3.stuecke.get(aus3.stuecke.size() - 1)).getInt("s"));
    }

    @Test
    public void grenzenVorDemStart() throws Exception {
        Ausgang a = new Ausgang();
        BrueckeLive.laufen(new BrueckeLive.Sitzung(1, 0), "MAX", new Quelle(), a, n -> true, () -> 0);
        assertEquals("nur bis MEDIUM über die Brücke", 400, json(a.stuecke.get(0)).getInt("s"));
        Ausgang b = new Ausgang();
        BrueckeLive.laufen(new BrueckeLive.Sitzung(1, 0), "ULTRA_LOW", new Quelle(), b, n -> false, () -> 0);
        assertEquals(429, json(b.stuecke.get(0)).getInt("s"));
        // 15 min hart
        Quelle q = new Quelle();
        Ausgang c = new Ausgang();
        long[] uhr = {0};
        BrueckeLive.Sitzung s = new BrueckeLive.Sitzung(2, 0);
        Thread t = new Thread(() -> BrueckeLive.laufen(s, "MEDIUM", q, c, n -> true, () -> uhr[0]));
        t.start();
        warte(() -> q.e != null);
        uhr[0] = BrueckeLive.HOECHSTENS_MS;
        s.da(uhr[0]);
        t.join(5000);
        assertEquals(410, json(c.stuecke.get(c.stuecke.size() - 1)).getInt("s"));
    }

    @Test
    public void imKanalNurMitRechtKameraUndSteuerung() throws Exception {
        BrueckeTest.Aufbau x = new BrueckeTest.Aufbau();
        x.b.einschalten();
        NoiseIK.Schluessel handy = NoiseIK.Schluessel.neu();
        x.b.handyAnmelden("k1", Bruecke.b64(handy.oeffentlich), Bruecke.b64(new byte[32]));
        x.gueltig.add("k1");
        java.util.Set<String> kamera = new java.util.HashSet<>();
        CopyOnWriteArrayList<byte[]> bin = new CopyOnWriteArrayList<>();
        BrueckeVerbindung.Kanaele kk = new BrueckeVerbindung.Kanaele(x.b,
                new BrueckeWeiterleitung(jwt -> jwt.startsWith("kid-") ? jwt.substring(4) : null, new BrueckeTest.Mitschnitt(),
                        kid -> null, (kid, r) -> kamera.contains(kid)).mitStrom((m, p, k, b) -> null, n -> true),
                new BrueckeVerbindung.Sender() {
                    public boolean text(String t) { return true; }
                    public boolean binaer(byte[] b) { bin.add(b); return true; }
                }, System::currentTimeMillis);
        Quelle q = new Quelle();
        kk.liveQuelle = q;
        kk.steuer("{\"t\":\"neu\",\"k\":2,\"art\":\"medien\"}");
        NoiseIK i = NoiseIK.initiator(handy, Bruecke.b64_32(x.b.autoOeffentlich()));
        kk.binaer(BrueckeTest.rahmen(2, i.schreibeNachricht1(new byte[0])));
        i.leseNachricht2(java.util.Arrays.copyOfRange(bin.get(0), 1, bin.get(0).length));
        byte[] live = new JSONObject().put("id", 3).put("v", 2).put("m", "LIVE")
                .put("h", new JSONObject().put("Authorization", "Bearer kid-k1")).toString().getBytes(StandardCharsets.UTF_8);

        senden(kk, i, live);
        warte(() -> bin.size() >= 2);
        byte[] p = i.entschluesseln(java.util.Arrays.copyOfRange(bin.get(1), 1, bin.get(1).length));
        assertEquals(BrueckeWeiterleitung.V2_FEHLER, p[0]);
        assertEquals("ohne Recht kamera", 403, json(p).getInt("s"));

        kamera.add("k1");
        senden(kk, i, live);
        warte(() -> q.e != null && kk.offen.get(2).live != null);
        senden(kk, i, "{\"t\":\"keyframe\",\"id\":3}".getBytes(StandardCharsets.UTF_8));
        warte(() -> q.texte.size() >= 2);
        senden(kk, i, "{\"t\":\"ab\",\"id\":3}".getBytes(StandardCharsets.UTF_8));
        warte(() -> q.zu);
        warte(() -> kk.offen.get(2).live == null);
    }

    private static void senden(BrueckeVerbindung.Kanaele kk, NoiseIK i, byte[] anfrage) throws Exception {
        byte[] st = new byte[anfrage.length + 1];
        st[0] = BrueckeRahmen.LETZTES;
        System.arraycopy(anfrage, 0, st, 1, anfrage.length);
        kk.binaer(BrueckeTest.rahmen(2, i.verschluesseln(st)));
    }

    /** 0.1.26.1 (Befund Deploy): LIVE stellt die vorherige Stream-Qualität zurück, statt ULTRA_LOW dauerhaft zu speichern. */
    @Test
    public void qualitaetNurFuerDieSitzung() throws Exception {
        assertTrue(BrueckeLive.zurueckStellen("HQ", "ULTRA_LOW"));
        assertTrue(BrueckeLive.zurueckStellen("medium", "ULTRA_LOW"));
        assertFalse("gleich", BrueckeLive.zurueckStellen("ULTRA_LOW", "ultra_low"));
        assertFalse("unbekannt", BrueckeLive.zurueckStellen(null, "ULTRA_LOW"));
        assertFalse("unbekannt", BrueckeLive.zurueckStellen("", "ULTRA_LOW"));
        java.io.File f = new java.io.File("src/main/java/at/greenchili/surfmate/bruecke/BrueckeLive.java");
        if (!f.isFile()) f = new java.io.File("app/" + f.getPath());
        String k = new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        int a = k.indexOf("String vorher = lokaleStufe();"), b = k.indexOf("lokalPost(\"/api/stream/quality/\" + stufe);");
        assertTrue("vorher lesen, dann setzen", a > 0 && b > a);
        int z = k.indexOf("public void schliessen()");
        assertTrue("beim Schliessen zurück", z > 0 && k.indexOf("lokalPost(\"/api/stream/quality/\" + vorher);", z) > z);
    }
}
