package at.greenchili.surfmate.auth;

import org.json.JSONObject;

import java.util.regex.Pattern;

/**
 * 0.1.43.8 Koppeln gleich nach „Ins Auto installieren“ (Handy-App 0.5.0, docs/INSTALLER_KOPPLUNG.md).
 *
 * <p>Die Handy-App hat Panoptes eben über ADB ins Auto gebracht. Über dieselbe, am Auto schon zugelassene
 * ADB-Verbindung holt sie sich einmal genau den Kopplungs-QR, den sonst „Neues Gerät koppeln“ am Display zeigt, und
 * koppelt sich damit wie nach einem Scan. Rein: Erzeugen des QR-Inhalts aus Code, Adresse und Brücke.
 *
 * <ul>
 *   <li>Gibt es noch keinen Besitzer, ist es ein Code wie vom Display ({@link PairingCodes#create(long)}): das Handy
 *       wird beim Einlösen Besitzer ({@link Kopplungen#anlegen(String, String, long, boolean)}).</li>
 *   <li>Gibt es schon einen Besitzer, ist es ein Code wie aus „weiteres Gerät koppeln“
 *       ({@link PairingCodes#create(long, boolean)} mit {@code weiteres}): normales weiteres Handy, nie Besitzer.</li>
 *   <li>Einmal einlösbar, {@link PairingCodes#TTL_MS} gültig, ersetzt einen offenen Code (wie am Display).</li>
 * </ul>
 * Wer aufrufen darf (nur adb shell am Auto), prüft {@code server.InstallerKopplungApiHandler}.
 */
public final class InstallerKopplung {

    private InstallerKopplung() {}

    /** Was das Gerät liefert (im Test nachgebaut). */
    public interface Quelle {
        boolean besitzerDa();
        /** Neuer Kopplungs-Code; {@code weiteres} = ohne Besitzer-Möglichkeit. */
        String code(long jetzt, boolean weiteres);
        /** Brücke an und zugestimmt? Dann {p, a, qrBasis} aus dem Brücken-Status, sonst null. */
        JSONObject bruecke();
        /** Einmal-Zugang der Brücke bis {@code bis} oder null (kein Internet). */
        String einmal(long bis);
    }

    /** Direktadresse aus der Anfrage: nur http(s) zu einer privaten IPv4 mit Port, sonst null (dann ohne „u“). */
    private static final Pattern ADRESSE = Pattern.compile(
            "^https?://(10(\\.\\d{1,3}){3}|192\\.168(\\.\\d{1,3}){2}|172\\.(1[6-9]|2\\d|3[01])(\\.\\d{1,3}){2}):\\d{1,5}$");

    public static String adresse(String roh) {
        if (roh == null) return null;
        String a = roh.trim();
        if (a.length() > 40 || !ADRESSE.matcher(a).matches()) return null;
        for (String teil : a.substring(a.indexOf("//") + 2, a.lastIndexOf(':')).split("\\.")) {
            if (Integer.parseInt(teil) > 255) return null;
        }
        int port = Integer.parseInt(a.substring(a.lastIndexOf(':') + 1));
        return port >= 1 && port <= 65535 ? a : null;
    }

    /**
     * Antwort {@code {ok, qr, besitzer, gueltigBis, unterwegs}}; {@code unterwegs} = der QR trägt einen Einmal-Zugang
     * der Brücke (geht auch ohne WLAN). Ohne Adresse und ohne Brücke: {@code ok:false, grund:"kein_weg"}.
     */
    public static JSONObject erzeugen(Quelle q, String adresseRoh, long jetzt) throws Exception {
        String adresse = adresse(adresseRoh);
        JSONObject br = q.bruecke();
        String p = br == null ? null : br.optString("p", "");
        String a = br == null ? null : br.optString("a", "");
        boolean brueckeOk = p != null && !p.isEmpty() && a != null && !a.isEmpty();
        if (adresse == null && !brueckeOk) {
            return new JSONObject().put("ok", false).put("grund", "kein_weg");
        }
        boolean besitzer = !q.besitzerDa();
        String code = q.code(jetzt, !besitzer);
        long bis = jetzt + PairingCodes.TTL_MS;
        String einmal = brueckeOk ? q.einmal(bis) : null;
        JSONObject inhalt = KopplungsQr.inhalt(code, adresse, brueckeOk ? p : null, brueckeOk ? a : null, einmal);
        // wie system.html (0.1.38.3): geht der Code nicht unterwegs, sagt der QR warum
        if (!(brueckeOk && einmal != null)) inhalt.put("bruecke", brueckeOk ? "kein_internet" : "aus");
        String qr = KopplungsQr.url(br == null ? "" : br.optString("qrBasis", ""), inhalt);
        return new JSONObject().put("ok", true).put("qr", qr).put("besitzer", besitzer)
                .put("gueltigBis", bis).put("unterwegs", brueckeOk && einmal != null);
    }
}
