package at.greenchili.surfmate.auth;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 0.1.20 Web-Sitzung aus der Handy-Kopplung (HANDY_WEB_SITZUNG_VERTRAG, Entscheidung Stufe B):
 * Die Handy-App holt mit ihrem Kopplungs-JWT einen Einmal-Code ({@link #GUELTIG_CODE_MS}, einmal einlösbar). Die
 * WebView öffnet damit {@code /auth/web?einmal=<code>} und bekommt einen Web-JWT (scope {@link #SCOPE}) als Cookie,
 * gebunden an die Kopplungs-id, {@link #GUELTIG_JWT_S} gültig. Jede Anfrage prüft die Kopplung mit (Widerruf oder
 * fehlendes Recht {@code web} beendet die Sitzung sofort, {@code AuthManager.kopplungPruefen}).
 * Codes nur im Speicher; ein Neustart verwirft sie.
 */
public final class WebSitzung {

    public static final String SCOPE = "handyweb";
    public static final long GUELTIG_CODE_MS = 60_000L;
    public static final int GUELTIG_JWT_S = 30 * 60;
    /** Cookie für app-shell.js: eigene Leiste und Kopfzeile ausblenden (wie {@code ?einbettung=handy}). */
    public static final String EINBETTUNG_COOKIE = "pn_einbettung=handy";
    static final int MAX_OFFEN = 32;
    /** Höchstens so viele Codes je Kopplung und Minute. */
    static final int MAX_JE_MINUTE = 6;

    private static final SecureRandom ZUFALL = new SecureRandom();

    private static final class Offen {
        final String kid;
        final long bis;

        Offen(String kid, long bis) {
            this.kid = kid;
            this.bis = bis;
        }
    }

    private final Map<String, Offen> offen = new HashMap<>();
    private final Map<String, long[]> zaehler = new HashMap<>();   // kid → {Minutenbeginn, Anzahl}

    /** Neuer Einmal-Code für die Kopplung; null bei Ratenlimit. */
    public synchronized String erzeugen(String kid, long jetzt) {
        if (kid == null || kid.isEmpty()) return null;
        long[] z = zaehler.get(kid);
        if (z == null || jetzt - z[0] >= 60_000L) {
            z = new long[] {jetzt, 0};
            zaehler.put(kid, z);
        }
        if (z[1] >= MAX_JE_MINUTE) return null;
        z[1]++;
        aufraeumen(jetzt);
        if (offen.size() >= MAX_OFFEN) return null;
        byte[] b = new byte[32];
        ZUFALL.nextBytes(b);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        offen.put(code, new Offen(kid, jetzt + GUELTIG_CODE_MS));
        return code;
    }

    /** Einlösen: Kopplungs-id genau einmal, solange gültig; sonst null. */
    public synchronized String einloesen(String code, long jetzt) {
        if (code == null || code.length() != 43) return null;
        Offen o = offen.remove(code);
        if (o == null || jetzt > o.bis) return null;
        return o.kid;
    }

    /** Offene Codes einer Kopplung verwerfen (Widerruf). */
    public synchronized void kopplungWeg(String kid) {
        offen.values().removeIf(o -> o.kid.equals(kid));
    }

    private void aufraeumen(long jetzt) {
        for (Iterator<Offen> it = offen.values().iterator(); it.hasNext(); ) {
            if (jetzt > it.next().bis) it.remove();
        }
    }

    /**
     * Ziel nach dem Einlösen: nur ein Pfad auf diesem Panoptes (beginnt mit „/“, nicht „//“, keine Steuerzeichen,
     * kein „\“), sonst die Startseite {@code /index.html}. Ohne angehängte Query: Seiten wie /trips.html findet
     * HttpServer nur ohne Query; die Einbettung trägt das Cookie {@link #EINBETTUNG_COOKIE}.
     */
    public static String ziel(String gewuenscht) {
        String z = gewuenscht == null ? "" : gewuenscht;
        if (z.isEmpty() || !z.startsWith("/") || z.startsWith("//") || z.indexOf('\\') >= 0
                || !z.matches("[\\x21-\\x7e]+") || z.startsWith("/auth/")) {
            z = "/index.html";
        }
        // „/“ mit Query findet HttpServer nicht (nur exakt „/“) → Startseite direkt
        if (z.equals("/") || z.startsWith("/?")) z = "/index.html" + z.substring(1);
        return z;
    }

    // ------------------------------------------------------------------ Laufzeit

    private static final WebSitzung INSTANZ = new WebSitzung();

    public static WebSitzung get() {
        return INSTANZ;
    }
}
