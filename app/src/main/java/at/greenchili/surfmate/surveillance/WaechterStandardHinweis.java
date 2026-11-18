package at.greenchili.surfmate.surveillance;

import at.greenchili.surfmate.einstellungen.Katalog;
import at.greenchili.surfmate.server.EinrichtungApiHandler;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 0.1.38.8 Wächter Standard an, Phase 3: der Bestand wird beim ersten Start auf dieser Version EINMAL gefragt, ob der Wächter
 * an soll. Nie still einschalten (der Rechtshinweis fehlt im Bestand): eine Frage mit Rechtshinweis und Knopf über Telegram,
 * Handy und Display. REGEL NR.2 ist für den Wächter von Projektinhaber bewusst aufgehoben.
 *
 * <p>Rein: {@link #entscheide} (Config rein, Entscheidung raus), {@link #standardAn} (Prüfung und Werte für „Wächter
 * einschalten“) und der Done-Marker je versionCode. Der Versand steht in {@link WaechterStandardKanaele}.
 * <ul>
 *   <li>Ein Marker je versionCode: einmal gefragt (oder bewusst nicht gefragt), danach nie wieder auf dieser Version.
 *       0.1.43.13: „gefragt“ heißt, die Frage stand wirklich am Display; vorher nur im sicheren Stand ({@link #standFuerFrage}).</li>
 *   <li>Bewusst aus, schon eingerichtet (gültiger triggerMode): keine Frage ({@link EinrichtungApiHandler#waechterStandardFragen}).</li>
 *   <li>Läuft der Assistent einer Neuinstallation noch, wird nicht gefragt (er fragt den Wächter selbst); der Marker bleibt offen.</li>
 *   <li>Ohne Einrichtungs-Abschnitt (noch nie geöffnet) wird gewartet: erst das erste Öffnen entscheidet Bestand oder neu.</li>
 * </ul>
 */
public final class WaechterStandardHinweis {

    static final String DATEI = ".waechter-standard-hinweis.json";

    /** Ergebnis eines Durchgangs. */
    public static final String NICHTS = "nichts";     // warten, Marker unverändert
    public static final String STILL = "still";       // erledigt, ohne zu fragen (Marker setzen)
    public static final String FRAGEN = "fragen";     // einmal fragen (Marker setzen)

    private WaechterStandardHinweis() {}

    /** Darf der Bestand jetzt gefragt werden (ohne Marker)? Auch für das Banner auf der Wächter-Seite. */
    public static boolean fragenErlaubt(JSONObject root) {
        if (root == null || !EinrichtungApiHandler.waechterStandardFragen(root)) return false;
        JSONObject z = root.optJSONObject("einrichtung");
        if (z == null) return false;                                   // erstes Öffnen entscheidet Bestand oder neu
        if ("neu".equals(z.optString("art")) && !wizardFertig(z)) return false;   // der Assistent fragt den Wächter selbst
        return true;
    }

    private static boolean wizardFertig(JSONObject z) {
        JSONObject s = z.optJSONObject("schritte");
        return s != null && EinrichtungApiHandler.ERLEDIGT.equals(s.optString("fertig"));
    }

    /** 0.1.43.13: höchstens so schnell (km/h) gilt „steht“, wenn der Gang unbekannt ist. */
    static final double STAND_KMH = 0.5;

    /**
     * 0.1.43.13 (E2E 0.1.43.12, B1): Darf die Frage jetzt am Display erscheinen? Nur wenn das Auto sicher steht, fail-closed.
     * <ul>
     *   <li>{@code fahrsperre} aus {@code DrivingSafetyGuard} muss null sein (ACC frisch und sicher, bei Zündung an Gang P
     *       frisch und Tempo bekannt; jeder unbekannte oder veraltete Wert ist ein Grund, also nein);</li>
     *   <li>Gang P, oder Gang unbekannt und 0 km/h bekannt; Gang bekannt außerhalb P ist immer nein;</li>
     *   <li>ein bekanntes Tempo über {@value #STAND_KMH} km/h ist immer nein.</li>
     * </ul>
     * Nein heißt aufschieben, nicht verwerfen: der Marker bleibt offen, der nächste Takt prüft erneut.
     */
    public static boolean standFuerFrage(String fahrsperre, Boolean gangP, double kmh) {
        if (fahrsperre != null) return false;
        if (Boolean.FALSE.equals(gangP)) return false;
        boolean tempoBekannt = !Double.isNaN(kmh) && !Double.isInfinite(kmh);
        if (tempoBekannt && Math.abs(kmh) > STAND_KMH) return false;
        return Boolean.TRUE.equals(gangP) || tempoBekannt;
    }

    /**
     * @param installiertVc versionCode dieser Installation (0 = unbekannt)
     * @param markerVc      versionCode, für den schon gefragt wurde (0 = nie)
     */
    public static String entscheide(JSONObject root, int installiertVc, int markerVc) {
        if (installiertVc <= 0 || markerVc >= installiertVc) return NICHTS;
        if (root == null) return NICHTS;
        JSONObject z = root.optJSONObject("einrichtung");
        if (z == null) return NICHTS;
        if ("neu".equals(z.optString("art")) && !wizardFertig(z)) return NICHTS;
        return EinrichtungApiHandler.waechterStandardFragen(root) ? FRAGEN : STILL;
    }

    /**
     * „Wächter einschalten“ nach der Frage. Fehlergrund oder null. Der Aufrufer muss den Rechtshinweis (Version) mitschicken,
     * damit sicher ist, dass er angezeigt wurde; eine veraltete Frage (inzwischen eingerichtet oder bewusst aus) schaltet nie ein.
     */
    public static String standardAnPruefen(JSONObject root, String rechtshinweis) {
        if (!Katalog.GUARD_LEGAL_VERSION.equals(rechtshinweis)) return "rechtshinweis";
        if (!EinrichtungApiHandler.waechterStandardFragen(root)) return "nicht_mehr_noetig";
        return null;
    }

    /** Werte für den Abschnitt {@code surveillance}: wie der Assistent (Standard „nur bei Erschütterung“). */
    public static Map<String, Object> standardAnWerte() {
        Map<String, Object> w = new HashMap<>();
        w.put("triggerMode", "shock");
        w.put("guardLegalVersion", Katalog.GUARD_LEGAL_VERSION);
        w.put("disabledByUser", false);
        return Collections.unmodifiableMap(w);
    }

    // ------------------------------------------------------------------ Marker

    public static int ladeMarker(File basis) {
        try {
            File f = new File(basis, DATEI);
            if (!f.isFile()) return 0;
            return new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)).optInt("versionCode", 0);
        } catch (Exception e) {
            return 0;
        }
    }

    public static void speichereMarker(File basis, int versionCode) {
        try {
            File tmp = new File(basis, DATEI + ".neu");
            byte[] b = new JSONObject().put("versionCode", versionCode).toString().getBytes(StandardCharsets.UTF_8);
            Files.write(tmp.toPath(), b);
            if (!tmp.renameTo(new File(basis, DATEI))) {
                Files.write(new File(basis, DATEI).toPath(), b);
                tmp.delete();
            }
        } catch (Exception ignored) {
        }
    }
}
