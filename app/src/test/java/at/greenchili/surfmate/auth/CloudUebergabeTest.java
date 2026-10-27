package at.greenchili.surfmate.auth;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** CLOUD_UEBERGABE_VERTRAG.md: Testwerte Byte für Byte, alle Fehlerfälle §3, Ratenlimit §4, nie Klartext im Protokoll. */
public class CloudUebergabeTest {

    static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    static final byte[] A_PRIV = hex("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20");
    static final byte[] E_PRIV = hex("4142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f60");
    static final String A_PUB = "B6N8vBQgk8i3VdwbEOhstCY3StFqqFPtC9_AsrhtHHw";
    static final String E_PUB = "ZLEBsdC-WocEvQePmJUAH8A-jp-VIvGI3RKNmEbUhGY";
    static final String REF = "r-0123456789abcdef";
    static final String KLAR = "{\"username\":\"test@example.com\",\"password\":\"Beispiel-Passwort-1\",\"controlPin\":\"123456\","
            + "\"countryCode\":\"AT\",\"language\":\"de\",\"region\":\"eu\"}";
    static final String CHIFFRE = "6-RiJ1phVKoUX3YTfP12uTdSqluwLG6KglRC2CQzsOGR7QLsWfr2DZivSDm_DTHFju13QJD3EIHfjDaThbwHUMIhpVhG-XNfHj-F5kZch3qhVYjnqM-XDKuTYJN5LGJclCeMwSAjufco7M3f6f1og92w24yXxnGHhyVHIsERDn_LmPWO2oLYh05Mz7oGdesVdSsSDUQvfQ";

    @Test
    public void testwerteByteFuerByte() throws Exception {
        assertEquals(A_PUB, CloudUebergabe.b64(CloudUebergabe.x25519Pub(A_PRIV)));
        assertEquals(E_PUB, CloudUebergabe.b64(CloudUebergabe.x25519Pub(E_PRIV)));
        byte[] shared = CloudUebergabe.x25519(A_PRIV, CloudUebergabe.b64d(E_PUB, 32));
        assertArrayEquals(hex("26c2c17fdb82161cb21ad16e721315355b64d1763119b10bfc962530dc7cc163"), shared);
        assertArrayEquals(shared, CloudUebergabe.x25519(E_PRIV, CloudUebergabe.b64d(A_PUB, 32)));
        byte[] okm = CloudUebergabe.okm(shared, CloudUebergabe.b64d(A_PUB, 32), CloudUebergabe.b64d(E_PUB, 32), REF);
        assertEquals("a9d6794e6349d042b23e52ecce1164fb", CloudUebergabe.hex(java.util.Arrays.copyOfRange(okm, 0, 16)));
        assertEquals("5ac02bd40c572fc464f29089", CloudUebergabe.hex(java.util.Arrays.copyOfRange(okm, 16, 28)));
        byte[] c = CloudUebergabe.verschluessele(shared, CloudUebergabe.b64d(A_PUB, 32), CloudUebergabe.b64d(E_PUB, 32), REF,
                KLAR.getBytes(StandardCharsets.UTF_8));
        assertEquals(CHIFFRE, CloudUebergabe.b64(c));
        assertEquals(KLAR, new String(CloudUebergabe.entschluessele(shared, CloudUebergabe.b64d(A_PUB, 32),
                CloudUebergabe.b64d(E_PUB, 32), REF, CloudUebergabe.b64d(CHIFFRE, -1)), StandardCharsets.UTF_8));
    }

    @Test
    public void einBitFalschGibtEntschluesselungsfehler() throws Exception {
        byte[] shared = CloudUebergabe.x25519(A_PRIV, CloudUebergabe.b64d(E_PUB, 32));
        byte[] aPub = CloudUebergabe.b64d(A_PUB, 32);
        byte[] ePub = CloudUebergabe.b64d(E_PUB, 32);
        byte[] c = CloudUebergabe.b64d(CHIFFRE, -1);
        byte[] c1 = c.clone();
        c1[5] ^= 1;
        byte[] e1 = ePub.clone();
        e1[0] ^= 1;
        Object[][] faelle = {{shared, aPub, ePub, "r-0123456789abcdee", c}, {shared, aPub, ePub, REF, c1}, {shared, aPub, e1, REF, c}};
        for (Object[] f : faelle) {
            try {
                CloudUebergabe.entschluessele((byte[]) f[0], (byte[]) f[1], (byte[]) f[2], (String) f[3], (byte[]) f[4]);
                fail("entschlüsselt trotz Bitfehler");
            } catch (Exception erwartet) {
                // AEADBadTagException
            }
        }
    }

    // ------------------------------------------------------------------ Ablauf mit Attrappe

    final AtomicLong jetzt = new AtomicLong(1_800_000_000_000L);
    final List<String> protokoll = new ArrayList<>();
    final List<String> meldungen = new ArrayList<>();
    final List<JSONObject> eingerichtet = new ArrayList<>();
    JSONObject einrichtErgebnis;
    CloudUebergabe u;

    /** Zufall, der zuerst ref-Bytes, dann Prüfzahl, dann den privaten Schlüssel A_PRIV liefert (für die Testwerte). */
    static final class FesterZufall extends SecureRandom {
        int aufruf;

        @Override
        public void nextBytes(byte[] b) {
            aufruf++;
            if (b.length == 8) System.arraycopy(hex("0123456789abcdef"), 0, b, 0, 8);
            else if (b.length == 32) System.arraycopy(A_PRIV, 0, b, 0, 32);
            else java.util.Arrays.fill(b, (byte) 7);
        }

        @Override
        public int nextInt(int bound) {
            return 4711 % bound;
        }
    }

    @Before
    public void auf() throws Exception {
        einrichtErgebnis = new JSONObject().put("gespeichert", true).put("verified", true);
        u = new CloudUebergabe(jetzt::get, new FesterZufall(), z -> {
            eingerichtet.add(new JSONObject(z.toString()));
            return einrichtErgebnis;
        }, new CloudUebergabe.Melder() {
            public void anfrage(String ref, String geraet, String pz) { meldungen.add("anfrage " + ref + " " + geraet + " " + pz); }
            public void entschieden(String ref, boolean erlaubt) { meldungen.add("entschieden " + ref + " " + erlaubt); }
            public void ergebnis(String geraet, boolean v) { meldungen.add("ergebnis " + geraet + " " + v); }
            public void protokoll(String zeile) { protokoll.add(zeile); }
        });
    }

    CloudUebergabe.Antwort anfrage() throws Exception {
        return u.anfrage("kid1", "cockpit", "EV-Cockpit (PC)");
    }

    @Test
    public void ganzerAblaufMitTestwerten() throws Exception {
        CloudUebergabe.Antwort a = anfrage();
        assertEquals(201, a.status);
        assertEquals(REF, a.json.getString("ref"));
        assertEquals(A_PUB, a.json.getString("a_pub"));
        assertEquals("4711", a.json.getString("pruefzahl"));
        assertEquals(jetzt.get() + CloudUebergabe.GUELTIG_MS, a.json.getLong("gueltigBis"));
        assertEquals("wartet", u.status(REF, "kid1").json.getString("status"));
        assertEquals(1, u.offene().length());
        assertEquals(409, u.senden(REF, "kid1", E_PUB, CHIFFRE).status);   // vor dem Erlauben
        // Senden vor dem Erlauben verbraucht die ref nicht: (erst „erlaubt“ führt zum Verbrauch)
        assertEquals(200, u.entscheide(REF, true).status);
        assertEquals("erlaubt", u.status(REF, "kid1").json.getString("status"));
        CloudUebergabe.Antwort s = u.senden(REF, "kid1", E_PUB, CHIFFRE);
        assertEquals(s.json.toString(), 200, s.status);
        assertTrue(s.json.getBoolean("verified"));
        assertEquals("test@example.com", eingerichtet.get(0).getString("username"));
        assertEquals("Beispiel-Passwort-1", eingerichtet.get(0).getString("password"));
        assertEquals(409, u.senden(REF, "kid1", E_PUB, CHIFFRE).status);
        assertEquals("schon_uebergeben", u.senden(REF, "kid1", E_PUB, CHIFFRE).json.getString("grund"));
        assertTrue(meldungen.contains("ergebnis EV-Cockpit (PC) true"));
        String alles = protokoll + " " + meldungen;
        // (die PIN „123456“ steckt zufällig in der Test-ref r-0123456789abcdef – darum über den Feldnamen prüfen)
        for (String geheim : new String[]{"Beispiel-Passwort-1", "controlPin", "test@example.com", CHIFFRE.substring(0, 20)}) {
            assertFalse("Klartext im Protokoll: " + geheim, alles.contains(geheim));
        }
    }

    @Test
    public void fehlerfaelleParagraph3() throws Exception {
        assertEquals(401, u.anfrage("", "cockpit", "x").status);
        assertEquals("nur_cockpit", u.anfrage("kid2", "handy", "x").json.getString("grund"));
        assertEquals(403, u.anfrage("kid3", "homeassistant", "x").status);
        anfrage();
        assertEquals("laeuft_schon", anfrage().json.getString("grund"));
        assertEquals(404, u.status(REF, "andere-kopplung").status);
        assertEquals(404, u.status("r-ffffffffffffffff", "kid1").status);
        assertEquals("nicht_erlaubt", u.senden(REF, "kid1", E_PUB, CHIFFRE).json.getString("grund"));
        u.entscheide(REF, false);
        assertEquals("abgelehnt", u.status(REF, "kid1").json.getString("status"));
        assertEquals("nicht_erlaubt", u.senden(REF, "kid1", E_PUB, CHIFFRE).json.getString("grund"));
        assertEquals(409, u.entscheide(REF, true).status);   // schon entschieden
    }

    CloudUebergabe.Antwort erlaubtUndSende(String ePub, String chiffre) throws Exception {
        jetzt.addAndGet(86_400_000L);   // alte vergessen, Ratenlimit frei
        CloudUebergabe.Antwort a = anfrage();
        assertEquals(a.json.toString(), 201, a.status);
        u.entscheide(a.json.getString("ref"), true);
        return u.senden(a.json.getString("ref"), "kid1", ePub, chiffre);
    }

    @Test
    public void schluesselEntschluesselnInhaltSpeichernVerified() throws Exception {
        assertEquals("schluessel", erlaubtUndSende("kurz", CHIFFRE).json.getString("grund"));
        // X25519-Ergebnis aus lauter Nullen (Punkt der Ordnung 1) wird abgelehnt
        assertEquals("schluessel", erlaubtUndSende(CloudUebergabe.b64(new byte[32]), CHIFFRE).json.getString("grund"));
        byte[] c = CloudUebergabe.b64d(CHIFFRE, -1);
        c[3] ^= 1;
        CloudUebergabe.Antwort e = erlaubtUndSende(E_PUB, CloudUebergabe.b64(c));
        assertEquals(400, e.status);
        assertEquals("entschluesseln", e.json.getString("grund"));
        // gültig verschlüsselt, aber Pflichtfeld fehlt
        assertEquals("inhalt", sendeKlartext("{\"username\":\"a@b.at\",\"password\":\"x\"}").json.getString("grund"));
        assertEquals("inhalt", sendeKlartext("kein json").json.getString("grund"));
        // Speichern scheitert
        einrichtErgebnis = new JSONObject().put("gespeichert", false).put("grund", "speichern");
        CloudUebergabe.Antwort sp = sendeKlartext(KLAR);
        assertEquals(500, sp.status);
        assertEquals("speichern", sp.json.getString("grund"));
        // gespeichert, Test-Login scheitert → 200 verified:false + Grund
        einrichtErgebnis = new JSONObject().put("gespeichert", true).put("verified", false).put("grund", "pin_falsch");
        CloudUebergabe.Antwort v = sendeKlartext(KLAR);
        assertEquals(200, v.status);
        assertFalse(v.json.getBoolean("verified"));
        assertEquals("pin_falsch", v.json.getString("grund"));
        for (String z : protokoll) assertFalse(z, z.contains("Beispiel-Passwort-1") || z.contains("controlPin"));
    }

    /** Erzeugt eine eigene Cockpit-Seite (e-Schlüssel) für die aktuelle Anfrage und verschlüsselt den Klartext. */
    CloudUebergabe.Antwort sendeKlartext(String klar) throws Exception {
        jetzt.addAndGet(86_400_000L);
        CloudUebergabe.Antwort a = anfrage();
        String ref = a.json.getString("ref");
        u.entscheide(ref, true);
        byte[] aPub = CloudUebergabe.b64d(a.json.getString("a_pub"), 32);
        byte[] shared = CloudUebergabe.x25519(E_PRIV, aPub);
        byte[] c = CloudUebergabe.verschluessele(shared, aPub, CloudUebergabe.b64d(E_PUB, 32), ref, klar.getBytes(StandardCharsets.UTF_8));
        return u.senden(ref, "kid1", E_PUB, CloudUebergabe.b64(c));
    }

    @Test
    public void ablaufNachFuenfMinuten() throws Exception {
        anfrage();
        jetzt.addAndGet(CloudUebergabe.GUELTIG_MS + 1);
        CloudUebergabe.Antwort s = u.status(REF, "kid1");
        assertEquals(410, s.status);
        assertEquals("abgelaufen", s.json.getString("status"));
        assertEquals(410, u.entscheide(REF, true).status);
        assertEquals(0, u.offene().length());
        assertEquals("abgelaufen zählt nicht als offen", 201, anfrage().status);
    }

    @Test
    public void ratenlimit() throws Exception {
        // 3 je Stunde und Kopplung (abgelehnte zählen mit)
        for (int i = 0; i < 3; i++) {
            CloudUebergabe.Antwort a = u.anfrage("kid1", "cockpit", "PC");
            assertEquals(201, a.status);
            u.entscheide(a.json.getString("ref"), false);
            jetzt.addAndGet(60_000);
        }
        assertEquals("ratenlimit", u.anfrage("kid1", "cockpit", "PC").json.getString("grund"));
        assertEquals(429, u.anfrage("kid1", "cockpit", "PC").status);
        // andere Kopplung darf, bis 6 je Tag und Auto
        for (int i = 0; i < 3; i++) {
            CloudUebergabe.Antwort a = u.anfrage("kid2", "cockpit", "PC2");
            assertEquals(a.json.toString(), 201, a.status);
            u.entscheide(a.json.getString("ref"), false);
        }
        assertEquals(429, u.anfrage("kid3", "cockpit", "PC3").status);
        jetzt.addAndGet(3_600_000L);
        assertEquals("Tagesgrenze je Auto gilt weiter", 429, u.anfrage("kid1", "cockpit", "PC").status);
        jetzt.addAndGet(86_400_000L);
        assertEquals(201, u.anfrage("kid1", "cockpit", "PC").status);
    }
}
