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

    /**
     * Ergebnis von {@link #einloesen}: ungültig, Code vom Display/Login ({@code BESITZER}), aus „weiteres Gerät koppeln“
     * bzw. 0.1.38.8 vom Besitzer-Handy ({@link #HANDY}, Art und Rechte fest im Code, {@link #letzteBindung}).
     */
    public static final int UNGUELTIG = 0, BESITZER = 1, WEITERES = 2, HANDY = 3;

    /** 0.1.38.8: Art + Rechte des offenen Handy-Codes (null = kein Handy-Code). */
    private static String openArt;
    private static java.util.List<String> openRechte;
    /** Art + Rechte des zuletzt eingelösten Handy-Codes, nur für den Thread, der ihn eingelöst hat. */
    private static final ThreadLocal<Object[]> BINDUNG = new ThreadLocal<>();

    /**
     * 0.1.38.8 Koppeln vom Besitzer-Handy: Code mit fester Art und festen Rechten ({@link #TTL_MS}, einmal, ersetzt einen
     * offenen Code). Die neue Kopplung bekommt genau diese Art und Rechte, nicht was das einlösende Gerät angibt.
     */
    public static synchronized String createGebunden(long nowMs, String art, java.util.List<String> rechte) {
        String code = create(nowMs, false);
        openArt = art;
        openRechte = rechte == null ? java.util.Collections.<String>emptyList()
                : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(rechte));
        return code;
    }

    /** Nach {@link #einloesen} mit Ergebnis {@link #HANDY}: {art, List rechte} des Codes, sonst null. */
    public static Object[] letzteBindung() {
        return BINDUNG.get();
    }

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
        openArt = null;
        openRechte = null;
        return code;
    }

    public static synchronized long openUntil() { return openHash == null ? 0L : openUntil; }

    /** Einlösen: true genau einmal für den offenen, nicht abgelaufenen Code. */
    public static synchronized boolean redeem(String code, long nowMs) {
        return einloesen(code, nowMs) != UNGUELTIG;
    }

    /** Wie {@link #redeem}, sagt zusätzlich, woher der Code kam ({@link #BESITZER} | {@link #WEITERES}). */
    public static synchronized int einloesen(String code, long nowMs) {
        BINDUNG.remove();
        if (openHash == null || code == null) return UNGUELTIG;
        if (nowMs > openUntil) {
            openHash = null;
            return UNGUELTIG;
        }
        String norm = code.trim().toUpperCase(java.util.Locale.ROOT).replace("-", "").replace(" ", "");
        boolean ok = MessageDigest.isEqual(openHash, hash(norm));
        if (!ok) return UNGUELTIG;
        openHash = null;   // einmal verwendbar
        if (openArt != null) {
            BINDUNG.set(new Object[]{openArt, openRechte});
            openArt = null;
            openRechte = null;
            return HANDY;
        }
        return openWeiteres ? WEITERES : BESITZER;
    }

    /** Nur für Tests. */
    static synchronized void reset() {
        openHash = null;
        openUntil = 0L;
        openArt = null;
        openRechte = null;
    }

    private static byte[] hash(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
