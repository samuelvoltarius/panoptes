package at.greenchili.surfmate.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Kopplungs-Codes für externe Geräte (EV-Cockpit), getrennt vom Login-Code (Vertrag
 * EINSTELLUNGEN_SCHEMA.md v1.1, 21.10.2026).
 *
 * <p>Ein Code ist {@link #TTL_MS} gültig und genau einmal einlösbar; es gibt immer nur einen
 * offenen Code (ein neuer ersetzt den alten). Gespeichert wird nur der SHA-256-Hash, und nur im
 * Speicher des Daemons: Ein Neustart verwirft offene Codes, was gewollt ist. Ein eingelöster Code
 * ergibt einen JWT mit {@code scope: "kopplung"}, mit dem Fernzugang, Login, Ports und adb nur
 * lesend erreichbar sind (Regel B).
 */
public final class PairingCodes {

    public static final long TTL_MS = 10 * 60 * 1000L;
    public static final String SCOPE = "kopplung";
    /** Ohne leicht verwechselbare Zeichen (0/O, 1/I/L). */
    private static final char[] ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int LENGTH = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private static byte[] openHash;
    private static long openUntil;
    /** 0.1.26.1: offener Code stammt aus „weiteres Gerät koppeln“ (Cockpit), nicht vom Besitzer am Display. */
    private static boolean openWeiteres;

    /** Ergebnis von {@link #einloesen}: ungültig, Code des Besitzers bzw. aus „weiteres Gerät koppeln“. */
    public static final int UNGUELTIG = 0, BESITZER = 1, WEITERES = 2;

    private PairingCodes() {}

    /** Neuen Code erzeugen (ersetzt einen offenen). Rückgabe: Klartext-Code, nur einmal sichtbar. */
    public static synchronized String create(long nowMs) {
        return create(nowMs, false);
    }

    /** Wie {@link #create(long)}; {@code weiteres} = für „weiteres Gerät koppeln“ (Cockpit). */
    public static synchronized String create(long nowMs, boolean weiteres) {
        char[] c = new char[LENGTH];
        for (int i = 0; i < LENGTH; i++) c[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        String code = new String(c);
        openHash = hash(code);
        openUntil = nowMs + TTL_MS;
        openWeiteres = weiteres;
        return code;
    }

    public static synchronized long openUntil() { return openHash == null ? 0L : openUntil; }

    /** Einlösen: true genau einmal für den offenen, nicht abgelaufenen Code. */
    public static synchronized boolean redeem(String code, long nowMs) {
        return einloesen(code, nowMs) != UNGUELTIG;
    }

    /** Wie {@link #redeem}, sagt zusätzlich, woher der Code kam ({@link #BESITZER} | {@link #WEITERES}). */
    public static synchronized int einloesen(String code, long nowMs) {
        if (openHash == null || code == null) return UNGUELTIG;
        if (nowMs > openUntil) {
            openHash = null;
            return UNGUELTIG;
        }
        String norm = code.trim().toUpperCase(java.util.Locale.ROOT).replace("-", "").replace(" ", "");
        boolean ok = MessageDigest.isEqual(openHash, hash(norm));
        if (!ok) return UNGUELTIG;
        openHash = null;   // einmal verwendbar
        return openWeiteres ? WEITERES : BESITZER;
    }

    /** Nur für Tests. */
    static synchronized void reset() {
        openHash = null;
        openUntil = 0L;
    }

    private static byte[] hash(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
