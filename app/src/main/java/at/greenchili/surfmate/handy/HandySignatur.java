package at.greenchili.surfmate.handy;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.regex.Pattern;

/**
 * 0.1.18 Handy-Vertrag §3.1 (HANDY_API_VERTRAG.md): Prüfung signierter Handy-Befehle.
 *
 * <p>Signiert wird {@code panoptes-handy-v1|<aktion>|<kopplungId>|<zeitMs>|<nonce>} mit ECDSA P-256/SHA-256
 * ({@code sig} = base64 DER). Der Schlüssel liegt am Handy hinter Biometrie (Keystore bzw. Secure Enclave).
 * Reihenfolge der Prüfung: zuerst die Signatur (sonst könnte jeder den Nonce-Speicher füllen), dann die Zeit
 * (±{@link #ZEIT_FENSTER_MS}), dann die Nonce (die letzten {@link #NONCES} gemerkt, je Panoptes-Prozess).
 */
public final class HandySignatur {

    public static final long ZEIT_FENSTER_MS = 30_000L;
    public static final int NONCES = 500;
    static final String PRAEFIX = "panoptes-handy-v1";

    public static final String GRUND_SIGNATUR = "signatur";
    public static final String GRUND_ZEIT = "signatur_zeit";
    public static final String GRUND_NONCE = "signatur_nonce";
    public static final String GRUND_KEIN_GERAET = "kein_geraet";

    private static final Pattern NONCE = Pattern.compile("[A-Za-z0-9_-]{8,64}");
    private final LinkedHashSet<String> gesehen = new LinkedHashSet<>();

    /** Aktion, für die die Freigabe gilt (Aufsperren, Vertrag §3.1). */
    public static final String AUFSPERREN = "aufsperren";
    /** Eine Freigabe gilt nur so lange nach der Prüfung (Router prüft vor dem Senden noch einmal). */
    public static final long FREIGABE_GUELTIG_MS = 60_000L;

    /**
     * Nachweis einer gültig signierten Aufsperr-Anfrage. Entsteht NUR in {@link #pruefeAufsperren} (privater
     * Konstruktor); ohne ein solches Objekt lässt sich der Aufsperr-Befehl im Router nicht bauen.
     */
    public static final class Freigabe {
        public final String kopplungId;
        public final String geraet;
        public final long geprueftMs;

        private Freigabe(String kopplungId, String geraet, long geprueftMs) {
            this.kopplungId = kopplungId;
            this.geraet = geraet;
            this.geprueftMs = geprueftMs;
        }

        public boolean frisch(long jetztMs) {
            return jetztMs - geprueftMs >= 0 && jetztMs - geprueftMs <= FREIGABE_GUELTIG_MS;
        }
    }

    /** Ergebnis: {@code freigabe} ≠ null genau dann, wenn {@code grund} == null. */
    public static final class Ergebnis {
        public final String grund;
        public final Freigabe freigabe;

        private Ergebnis(String grund, Freigabe freigabe) {
            this.grund = grund;
            this.freigabe = freigabe;
        }
    }

    /** Prüft eine Aufsperr-Anfrage (Signatur, Zeit, Nonce) und gibt bei Erfolg die Freigabe heraus. */
    public Ergebnis pruefeAufsperren(String schluesselBase64, String kopplungId, long zeitMs, String nonce,
                                     String sigBase64, String geraet, long jetztMs) {
        String g = pruefe(schluesselBase64, AUFSPERREN, kopplungId, zeitMs, nonce, sigBase64, jetztMs);
        return g != null ? new Ergebnis(g, null) : new Ergebnis(null, new Freigabe(kopplungId, geraet, jetztMs));
    }

    /** Text, der signiert wird. */
    public static String nachricht(String aktion, String kopplungId, long zeitMs, String nonce) {
        return PRAEFIX + "|" + aktion + "|" + kopplungId + "|" + zeitMs + "|" + nonce;
    }

    /** Öffentlicher Schlüssel aus base64 X.509 SubjectPublicKeyInfo; null, wenn kein EC-Schlüssel (P-256). */
    public static PublicKey schluessel(String base64) {
        if (base64 == null || base64.length() > 2_000) return null;
        try {
            byte[] der = Base64.getDecoder().decode(base64.trim());
            PublicKey k = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
            if (!(k instanceof ECPublicKey)) return null;
            if (((ECPublicKey) k).getParams().getCurve().getField().getFieldSize() != 256) return null;
            return k;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Prüft einen signierten Befehl. @return null = gültig (Nonce ist dann verbraucht), sonst der Grund
     * ({@link #GRUND_KEIN_GERAET}, {@link #GRUND_SIGNATUR}, {@link #GRUND_ZEIT}, {@link #GRUND_NONCE}).
     */
    public synchronized String pruefe(String schluesselBase64, String aktion, String kopplungId, long zeitMs,
                                      String nonce, String sigBase64, long jetztMs) {
        PublicKey k = schluessel(schluesselBase64);
        if (k == null) return GRUND_KEIN_GERAET;
        if (nonce == null || !NONCE.matcher(nonce).matches() || sigBase64 == null || sigBase64.length() > 400) {
            return GRUND_SIGNATUR;
        }
        try {
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initVerify(k);
            s.update(nachricht(aktion, kopplungId, zeitMs, nonce).getBytes(StandardCharsets.UTF_8));
            if (!s.verify(Base64.getDecoder().decode(sigBase64.trim()))) return GRUND_SIGNATUR;
        } catch (Exception e) {
            return GRUND_SIGNATUR;
        }
        if (Math.abs(jetztMs - zeitMs) > ZEIT_FENSTER_MS) return GRUND_ZEIT;
        String n = kopplungId + "|" + nonce;
        if (gesehen.contains(n)) return GRUND_NONCE;
        gesehen.add(n);
        while (gesehen.size() > NONCES) {
            Iterator<String> it = gesehen.iterator();
            it.next();
            it.remove();
        }
        return null;
    }
}
