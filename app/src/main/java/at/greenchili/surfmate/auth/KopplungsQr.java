package at.greenchili.surfmate.auth;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 0.1.25 QR v2 für eine Handy-Kopplung (HANDY_API_VERTRAG §7, BRUECKE_VERTRAG §2.3) auf der Autoseite – gleiche Form wie
 * {@code system.html qrV2}: {@code <qrBasis>#k=<base64url(JSON)>} mit {@code {v:2, c:Code, u?:[Adresse], b?:{p,a,z?}}}.
 * Gebraucht von „weiteres Gerät koppeln“ (Cockpit), wo kein Browser den QR baut.
 */
public final class KopplungsQr {

    private KopplungsQr() {}

    public static final String STANDARD_BASIS = "https://byd.serveone.at/p/";
    /** Grenze laut Vertrag (das Cockpit prüft sie auch). */
    public static final int MAX_LAENGE = 1000;

    public static JSONObject inhalt(String code, String adresse, String p, String a, String einmal) throws JSONException {
        JSONObject j = new JSONObject().put("v", 2).put("c", code);
        if (adresse != null && !adresse.isEmpty()) j.put("u", new JSONArray().put(adresse));
        if (p != null && !p.isEmpty() && a != null && !a.isEmpty()) {
            JSONObject b = new JSONObject().put("p", p).put("a", a);
            if (einmal != null && !einmal.isEmpty()) b.put("z", einmal);
            j.put("b", b);
        }
        return j;
    }

    /** Fertiger QR-Text; {@code qrBasis} leer → {@link #STANDARD_BASIS}. */
    public static String url(String qrBasis, JSONObject inhalt) {
        String b64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(inhalt.toString().getBytes(StandardCharsets.UTF_8));
        return (qrBasis == null || qrBasis.isEmpty() ? STANDARD_BASIS : qrBasis) + "#k=" + b64;
    }
}
