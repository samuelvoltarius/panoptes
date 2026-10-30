package at.greenchili.surfmate.notifications.push;

import org.json.JSONObject;

import java.math.BigInteger;
import java.net.URI;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;

/**
 * VAPID JWT signer. Builds an ES256 JWT for one push request, with the
 * audience set to the origin of the subscription endpoint (RFC 8292 §2).
 *
 * <p>Push services require this signature to authorize the request and
 * cap unauthenticated abuse. The same signing key is used across every
 * subscription served by this device.
 *
 * <p>Java's {@code Signature.getInstance("SHA256withECDSA")} returns an
 * ASN.1 DER-encoded signature; JWT requires the raw 64-byte (r||s) form,
 * so we transcode.
 */
public final class VapidSigner {

    /**
     * Fester Projekt-Kontakt für den {@code sub}-Eintrag: Apples Push-Dienst verlangt ihn. Für alle Autos gleich,
     * nie eine persönliche Adresse. Der Push-Dienst sieht ihn, sonst geht dadurch nichts nach außen.
     */
    public static final String PROJEKT_KONTAKT = "https://byd.serveone.at/panoptes/";

    private final VapidKeyStore keyStore;
    /** Optional contact email/URL (RFC 8292 §2.1). Empty string is acceptable. */
    private final String contact;

    public VapidSigner(VapidKeyStore keyStore, String contact) {
        this.keyStore = keyStore;
        this.contact = contact == null ? "" : contact;
    }

    /**
     * Build a JWT valid for ~12 hours, scoped to the audience derived from
     * the push endpoint.
     */
    public String signFor(String endpoint) throws Exception {
        // Header
        JSONObject header = new JSONObject();
        header.put("typ", "JWT");
        header.put("alg", "ES256");

        String headerB64 = B64Url.enc(header.toString().getBytes("UTF-8"));
        String claimsB64 = B64Url.enc(claims(endpoint, System.currentTimeMillis() / 1000L).toString().getBytes("UTF-8"));
        String signingInput = headerB64 + "." + claimsB64;

        ECPrivateKey priv = keyStore.privateKey();
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(priv);
        sig.update(signingInput.getBytes("UTF-8"));
        byte[] derSig = sig.sign();

        byte[] rawSig = derToRawP1363(derSig, 32);
        return signingInput + "." + B64Url.enc(rawSig);
    }

    /** Der Inhalt des JWT: nur aud, exp und sub. */
    JSONObject claims(String endpoint, long nowSec) throws Exception {
        URI uri = URI.create(endpoint);
        JSONObject claims = new JSONObject();
        claims.put("aud", uri.getScheme() + "://" + uri.getAuthority());
        claims.put("exp", nowSec + 12 * 3600L);
        if (!contact.isEmpty()) {
            // Per RFC 8292 the sub claim should be a contact URI (mailto: or https:)
            claims.put("sub", contact.startsWith("mailto:") || contact.startsWith("http")
                    ? contact : "mailto:" + contact);
        }
        return claims;
    }

    /**
     * Convert a JCA ECDSA signature (ASN.1 DER {@code SEQUENCE(r,s)}) into
     * the raw IEEE P1363 form ({@code r || s}) required by JWT/JOSE.
     *
     * <p>Each component is left-padded to {@code partLen} bytes.
     */
    static byte[] derToRawP1363(byte[] der, int partLen) {
        // DER:  30 [len] 02 [rLen] r 02 [sLen] s
        // len, rLen, sLen each may be short-form (single byte 0x00..0x7F) or
        // long-form (0x81 LL, 0x82 LL LL ...). For ES256 the sequence is
        // always short-form (≤ 72 bytes), but parse generally to be safe.
        if (der[0] != 0x30) throw new IllegalArgumentException("not a DER sequence");

        int idx = 1 + lengthFieldSize(der, 1);

        if (der[idx] != 0x02) throw new IllegalArgumentException("expected INTEGER (r)");
        int rLenSize = lengthFieldSize(der, idx + 1);
        int rLen = readLength(der, idx + 1);
        int rStart = idx + 1 + rLenSize;
        int rEnd = rStart + rLen;

        if (der[rEnd] != 0x02) throw new IllegalArgumentException("expected INTEGER (s)");
        int sLenSize = lengthFieldSize(der, rEnd + 1);
        int sLen = readLength(der, rEnd + 1);
        int sStart = rEnd + 1 + sLenSize;
        int sEnd = sStart + sLen;

        BigInteger r = new BigInteger(1, java.util.Arrays.copyOfRange(der, rStart, rEnd));
        BigInteger s = new BigInteger(1, java.util.Arrays.copyOfRange(der, sStart, sEnd));

        byte[] out = new byte[partLen * 2];
        byte[] rb = VapidKeyStore.unsignedBytes(r, partLen);
        byte[] sb = VapidKeyStore.unsignedBytes(s, partLen);
        System.arraycopy(rb, 0, out, 0, partLen);
        System.arraycopy(sb, 0, out, partLen, partLen);
        return out;
    }

    /** Number of bytes occupied by an ASN.1 BER/DER length field starting at {@code off}. */
    private static int lengthFieldSize(byte[] buf, int off) {
        int b = buf[off] & 0xFF;
        return (b & 0x80) == 0 ? 1 : 1 + (b & 0x7F);
    }

    /** Decode the length value written at {@code off}. */
    private static int readLength(byte[] buf, int off) {
        int b = buf[off] & 0xFF;
        if ((b & 0x80) == 0) return b;
        int n = b & 0x7F;
        int len = 0;
        for (int i = 1; i <= n; i++) {
            len = (len << 8) | (buf[off + i] & 0xFF);
        }
        return len;
    }
}
