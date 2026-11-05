package at.greenchili.surfmate.server;

import at.greenchili.surfmate.auth.Kopplungen;
import at.greenchili.surfmate.handy.HandySignatur;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * 0.1.39 Tests: Software-Geräteschlüssel wie Keystore/Secure Enclave. {@link #signiert} hinterlegt beim ersten Aufruf
 * einen Schlüssel im Koppel-Fenster (gilt also für Besitzer-Aktionen) und signiert den Zweck des Aufrufs.
 */
final class BesitzerSignaturHilfe {

    private BesitzerSignaturHilfe() {}

    private static final Map<String, KeyPair> PAARE = new HashMap<>();
    private static int nonceNr;

    static KeyPair neuesPaar() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }

    static String oeffentlich(KeyPair p) {
        return Base64.getEncoder().encodeToString(p.getPublic().getEncoded());
    }

    static long angelegt(Kopplungen k, String kid) {
        JSONArray a = k.alle(0L);
        for (int i = 0; i < a.length(); i++) {
            if (kid.equals(a.optJSONObject(i).optString("id"))) return a.optJSONObject(i).optLong("angelegt");
        }
        throw new IllegalStateException("keine Kopplung " + kid);
    }

    /** Schlüssel dieser Kopplung (hinterlegt beim „Koppeln“, falls noch keiner da ist). */
    static KeyPair paar(Kopplungen k, String kid) throws Exception {
        String key = System.identityHashCode(k) + "|" + kid;
        KeyPair p = PAARE.get(key);
        if (p == null || k.schluessel(kid) == null) {
            p = neuesPaar();
            int r = k.schluesselMelden(kid, oeffentlich(p), "android", "Test", angelegt(k, kid) + 1);
            if (r != Kopplungen.SCHLUESSEL_GESPEICHERT) throw new IllegalStateException("Schlüssel: " + r);
            PAARE.put(key, p);
        }
        return p;
    }

    static String nonce() {
        return String.format("nonce-bes-%06d", ++nonceNr);
    }

    static String sig(KeyPair p, String zweck, String kid, long zeit, String nonce) throws Exception {
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(p.getPrivate());
        s.update(HandySignatur.nachricht(zweck, kid, zeit, nonce).getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(s.sign());
    }

    /** {@code body} plus gültige Signatur für {@code pfad} (Kopie, das Original bleibt). */
    static JSONObject signiert(Kopplungen k, String kid, String pfad, JSONObject body, long jetzt) throws Exception {
        JSONObject b = new JSONObject(body.toString());
        String zweck = BesitzerSignatur.zweck(pfad, b);
        if (zweck == null || kid == null || !k.istHandy(kid)) return b;
        String n = nonce();
        return b.put("signatur", new JSONObject().put("zeit", jetzt).put("nonce", n)
                .put("sig", sig(paar(k, kid), zweck, kid, jetzt, n)));
    }
}
