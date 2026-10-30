package at.greenchili.surfmate.handy;

import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

/**
 * 0.1.33 Web-Aufsperren: Einrichte-Fenster je Kopplung, nur im Speicher. Nach einem Neustart ist jedes Fenster zu,
 * eine noch nicht bestätigte Anlage ist weg. Geöffnet wird nur direkt am Auto ({@code EinstellungenApiHandler}).
 */
public final class PasskeyFenster {

    public static final long FENSTER_MS = 10L * 60 * 1000;
    public static final long CHALLENGE_MS = 120_000L;
    private static final int CHALLENGE_BYTES = 32;

    private static final PasskeyFenster INSTANZ = new PasskeyFenster();
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final class Zustand {
        long bis;
        String challenge;
        long challengeBis;
        WebAuthn.Anlage wartend;
        String name;
    }

    private final Map<String, Zustand> offen = new HashMap<>();

    public static PasskeyFenster get() {
        return INSTANZ;
    }

    private Zustand aktuell(String kid, long jetzt) {
        Zustand z = offen.get(kid);
        if (z != null && jetzt >= z.bis) {
            offen.remove(kid);
            return null;
        }
        return z;
    }

    public synchronized void oeffnen(String kid, long jetzt) {
        Zustand z = new Zustand();
        z.bis = jetzt + FENSTER_MS;
        offen.put(kid, z);
    }

    public synchronized boolean istOffen(String kid, long jetzt) {
        return aktuell(kid, jetzt) != null;
    }

    public synchronized void schliessen(String kid) {
        offen.remove(kid);
    }

    /** Neue Challenge (ersetzt die alte); null, wenn das Fenster zu ist oder schon eine Anlage wartet. */
    public synchronized String neueChallenge(String kid, long jetzt) {
        Zustand z = aktuell(kid, jetzt);
        if (z == null || z.wartend != null) return null;
        byte[] b = new byte[CHALLENGE_BYTES];
        RANDOM.nextBytes(b);
        z.challenge = WebAuthn.kodiere(b);
        z.challengeBis = jetzt + CHALLENGE_MS;
        return z.challenge;
    }

    /** Nimmt die Challenge heraus: Sie gilt genau für einen Versuch, auch wenn er scheitert. null = keine gültige. */
    public synchronized String challengeNehmen(String kid, long jetzt) {
        Zustand z = aktuell(kid, jetzt);
        if (z == null || z.challenge == null) return null;
        String c = jetzt < z.challengeBis ? z.challenge : null;
        z.challenge = null;
        return c;
    }

    /** Merkt die geprüfte Anlage bis zur Bestätigung am Auto; false, wenn das Fenster zu ist oder schon eine wartet. */
    public synchronized boolean anlageMerken(String kid, WebAuthn.Anlage anlage, String name, long jetzt) {
        Zustand z = aktuell(kid, jetzt);
        if (z == null || z.wartend != null) return false;
        z.wartend = anlage;
        z.name = name;
        return true;
    }

    /** Gerätename der wartenden Anlage oder null. */
    public synchronized String wartendName(String kid, long jetzt) {
        Zustand z = aktuell(kid, jetzt);
        return z == null ? null : z.name;
    }

    public synchronized WebAuthn.Anlage wartend(String kid, long jetzt) {
        Zustand z = aktuell(kid, jetzt);
        return z == null ? null : z.wartend;
    }

    /** Für „Gekoppelte Geräte“ am Auto: {@code {bisMs, pruefzahl?}} oder null. Nie Schlüssel oder Challenge. */
    public synchronized JSONObject zustand(String kid, long jetzt) {
        Zustand z = aktuell(kid, jetzt);
        if (z == null) return null;
        try {
            JSONObject o = new JSONObject().put("bisMs", z.bis);
            if (z.wartend != null) o.put("pruefzahl", z.wartend.pruefzahl);
            return o;
        } catch (Exception e) {
            return null;
        }
    }
}
