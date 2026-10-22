package at.greenchili.surfmate.fahrtenbuch;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Fahrtenbuch E2E (Planer §8): Browser-Seite nachgebaut, Klartext nur im „Browser“, Postfach-Ablauf. */
public class FahrtenbuchTest {

    static final TimeZone WIEN = TimeZone.getTimeZone("Europe/Vienna");

    /** Wie der Browser: eigenes Paar, Rohpunkt raus, später mit Auto-Pub entschlüsseln. */
    static final class Browser {
        final KeyPair kp;
        Browser() throws Exception {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            kp = g.generateKeyPair();
        }
        String pub() { return FahrtenbuchKrypto.b64(FahrtenbuchKrypto.rohpunkt((ECPublicKey) kp.getPublic())); }
        byte[] entschluesseln(String anfrageId, String autoPub, String iv, String ct) throws Exception {
            byte[] geheim = FahrtenbuchKrypto.ecdh(kp, FahrtenbuchKrypto.rohpunkt(FahrtenbuchKrypto.b64d(autoPub)));
            byte[] k = FahrtenbuchKrypto.hkdf(geheim, anfrageId.getBytes(StandardCharsets.UTF_8),
                    "panoptes-fahrtenbuch-v1".getBytes(StandardCharsets.UTF_8), 32);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(k, "AES"), new GCMParameterSpec(128, FahrtenbuchKrypto.b64d(iv)));
            return c.doFinal(FahrtenbuchKrypto.b64d(ct));
        }
    }

    @Test
    public void rundreise() throws Exception {
        Browser b = new Browser();
        byte[] klar = "{\"version\":1,\"fahrten\":[]}".getBytes(StandardCharsets.UTF_8);
        FahrtenbuchKrypto.Paket p = FahrtenbuchKrypto.verschluesseln(b.pub(), "abcdefghijklmnopqrstuv", klar);
        assertEquals(65, FahrtenbuchKrypto.b64d(p.pubkey).length);
        assertEquals(12, FahrtenbuchKrypto.b64d(p.iv).length);
        assertFalse(p.chiffretext.contains("=") || p.chiffretext.contains("+") || p.chiffretext.contains("/"));
        assertArrayEquals(klar, b.entschluesseln("abcdefghijklmnopqrstuv", p.pubkey, p.iv, p.chiffretext));
        // falsche anfrageId (salt) → GCM schlägt fehl
        try {
            b.entschluesseln("anderebaanfrageid12345", p.pubkey, p.iv, p.chiffretext);
            fail();
        } catch (javax.crypto.AEADBadTagException ok) { }
    }

    /** RFC 5869 Testfall 1. */
    @Test
    public void hkdfRfc5869() throws Exception {
        byte[] okm = FahrtenbuchKrypto.hkdf(FahrtenbuchKrypto.hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"),
                FahrtenbuchKrypto.hex("000102030405060708090a0b0c"), FahrtenbuchKrypto.hex("f0f1f2f3f4f5f6f7f8f9"), 42);
        assertArrayEquals(FahrtenbuchKrypto.hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf"
                + "34007208d5b887185865"), okm);
    }

    @Test
    public void ungueltigerPunktAbgelehnt() throws Exception {
        byte[] roh = new byte[65];
        roh[0] = 4;
        roh[64] = 1;
        try {
            FahrtenbuchKrypto.verschluesseln(FahrtenbuchKrypto.b64(roh), "x", new byte[1]);
            fail();
        } catch (IllegalArgumentException ok) { }
        try {
            FahrtenbuchKrypto.verschluesseln("kurz", "x", new byte[1]);
            fail();
        } catch (IllegalArgumentException ok) { }
    }

    static FahrtenbuchSeite.Fahrt fahrt(long start, double km) {
        FahrtenbuchSeite.Fahrt f = new FahrtenbuchSeite.Fahrt();
        f.start = start;
        f.ende = start + 600_000;
        f.km = km;
        f.kwh = km * 0.15;
        f.socStart = 80;
        f.socEnde = 78;
        return f;
    }

    @Test
    public void seitenUndZeitraum() throws Exception {
        assertNull(FahrtenbuchSeite.zeitraum("2026-10-01", "2026-09-01", WIEN));
        assertNull(FahrtenbuchSeite.zeitraum("2026-01-01", "2026-12-31", WIEN));   // > 92 Tage
        assertNull(FahrtenbuchSeite.zeitraum("2026-13-01", "2026-12-31", WIEN));
        long[] z = FahrtenbuchSeite.zeitraum("2026-10-01", "2026-10-21", WIEN);
        assertNotNull(z);
        assertEquals(21 * 86_400_000L, z[1] - z[0]);   // 1.–21.10. ganz, Ende exklusiv
        List<FahrtenbuchSeite.Fahrt> l = new ArrayList<>();
        for (int i = 0; i < 450; i++) l.add(fahrt(z[0] + i * 60_000L, 1.0));
        JSONObject s1 = FahrtenbuchSeite.seite("a-0123456789abcdef", "2026-10-01", "2026-10-21", 1, l, WIEN);
        assertEquals(3, s1.getInt("seiten"));
        assertEquals(200, s1.getJSONArray("fahrten").length());
        JSONObject s3 = FahrtenbuchSeite.seite("a-0123456789abcdef", "2026-10-01", "2026-10-21", 3, l, WIEN);
        assertEquals(50, s3.getJSONArray("fahrten").length());
        assertEquals(450.0, s3.getJSONObject("summe").getDouble("km"), 0.01);
        JSONObject f = s1.getJSONArray("fahrten").getJSONObject(0);
        assertTrue(f.isNull("von") && f.isNull("nach"));
        String alles = s1.toString();
        assertFalse(alles.contains("lat") || alles.contains("lon"));
    }

    /** Fake-Planer: eine Anfrage, Antwort einmal, Klartext nur nach Entschlüsseln lesbar. */
    @Test
    public void postfachAntwortetGenauEinmal() throws Exception {
        Browser b = new Browser();
        final List<String> calls = new ArrayList<>();
        final List<JSONObject> antworten = new ArrayList<>();
        final long[] now = {1_761_040_000_000L};
        final boolean[] an = {true};
        final int[] offenStatus = {200};
        FahrtenbuchPostfach.Quelle q = new FahrtenbuchPostfach.Quelle() {
            public boolean eingeschaltet() { return an[0]; }
            public String autoId() { return "a-0123456789abcdef"; }
            public String[] schluessel() { return new String[]{"X-Geraete-Token", "x"}; }
            public Object[] http(String m, String op, String[] k, String body) {
                calls.add(m + " " + op);
                try {
                    if (op.startsWith("fahrtenbuch/offen")) {
                        JSONObject a = new JSONObject().put("anfrageId", "Anfrage_0123456789ab")
                                .put("zeitraum", new JSONObject().put("von", "2026-10-01").put("bis", "2026-10-21"))
                                .put("seite", 1).put("pubkey", b.pub()).put("ablauf_s", 120);
                        return new Object[]{offenStatus[0], new JSONObject().put("anfragen", new JSONArray().put(a))};
                    }
                    antworten.add(new JSONObject(body));
                    return new Object[]{200, new JSONObject().put("ok", true)};
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            public List<FahrtenbuchSeite.Fahrt> fahrten(long v, long bis) {
                List<FahrtenbuchSeite.Fahrt> l = new ArrayList<>();
                l.add(fahrt(v + 3_600_000L, 12.3));
                return l;
            }
            public TimeZone zone() { return WIEN; }
            public long jetzt() { return now[0]; }
        };
        FahrtenbuchPostfach p = new FahrtenbuchPostfach(q);
        p.takt();
        assertEquals(1, antworten.size());
        JSONObject r = antworten.get(0);
        assertFalse(r.toString().contains("12.3"));        // Planer sieht keinen Klartext
        JSONObject klar = new JSONObject(new String(b.entschluesseln("Anfrage_0123456789ab", r.getString("pubkey"),
                r.getString("iv"), r.getString("chiffretext")), StandardCharsets.UTF_8));
        assertEquals(12.3, klar.getJSONArray("fahrten").getJSONObject(0).getDouble("km"), 0.001);
        assertEquals("a-0123456789abcdef", klar.getString("auto"));
        // Schnellmodus: nach 20 s erneut fragen, aber dieselbe Anfrage nicht noch einmal beantworten
        now[0] += 20_000;
        p.takt();
        assertEquals(2, calls.stream().filter(c -> c.startsWith("GET")).count());
        assertEquals(1, antworten.size());
        // 401 → 10 min Ruhe
        offenStatus[0] = 401;
        now[0] += 20_000;
        p.takt();
        int vorher = calls.size();
        now[0] += 5 * 60_000;
        p.takt();
        assertEquals(vorher, calls.size());
        // ausgeschaltet → kein Aufruf
        an[0] = false;
        offenStatus[0] = 200;
        now[0] += 20 * 60_000;
        p.takt();
        assertEquals(vorher, calls.size());
    }
}
