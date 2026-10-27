package at.greenchili.surfmate.config;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 0.1.20 (Koordination 27.10.): Geheimnisse gehen nie an einen Client, auch nicht mit PIN-Login. JWT-Schlüssel,
 * Tokens, Passwörter, API- und private Schlüssel werden beim Lesen der Gesamt-Konfiguration als
 * {@code {"gesetzt":true|false}} ausgegeben. Schreiben bleibt möglich (nur ersetzen); eine zurückgeschickte
 * Maske überschreibt nie den gespeicherten Wert.
 *
 * <p>Erkannt am Schlüsselnamen (Groß/klein egal); geschwärzt werden nur Text-Werte – Schalter wie
 * {@code hasPassword} bleiben lesbar.
 */
public final class Geheimnisse {

    private Geheimnisse() {}

    static final Pattern GEHEIM = Pattern.compile(
            "(?i)^(.*secret|.*token|.*passw(or)?d|passwort|.*pwd|.*api_?key|.*private_?key|.*auth_?key|login_?key"
                    + "|local_?key|.*pin|pair_?pin|refresh)$");

    /** true, wenn ein Konfigurations-Schlüssel ein Geheimnis trägt. */
    public static boolean istGeheim(String schluessel) {
        return schluessel != null && GEHEIM.matcher(schluessel).matches();
    }

    /** Tiefe Kopie, Geheimnisse als {@code {"gesetzt":…}}. */
    public static JSONObject schwaerzen(JSONObject quelle) {
        if (quelle == null) return null;
        JSONObject r = new JSONObject();
        for (Iterator<String> it = quelle.keys(); it.hasNext(); ) {
            String k = it.next();
            Object v = quelle.opt(k);
            try {
                r.put(k, schwaerzeWert(k, v));
            } catch (Exception ignored) {
                // Wert, den org.json nicht nimmt: weglassen statt riskieren
            }
        }
        return r;
    }

    private static Object schwaerzeWert(String k, Object v) throws Exception {
        if (v instanceof String && istGeheim(k)) {
            return new JSONObject().put("gesetzt", !((String) v).isEmpty());
        }
        if (v instanceof JSONObject) return schwaerzen((JSONObject) v);
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v, n = new JSONArray();
            for (int i = 0; i < a.length(); i++) n.put(schwaerzeWert(k, a.opt(i)));
            return n;
        }
        return v;
    }

    /**
     * Für Schreibwege, die eine gelesene Konfiguration zurückschicken: geheime Schlüssel, deren Wert kein Text ist
     * (also die Maske), werden entfernt – der gespeicherte Wert bleibt. Liefert die entfernten Pfade.
     */
    public static List<String> ohneMasken(JSONObject daten) {
        List<String> weg = new ArrayList<>();
        ohneMasken(daten, "", weg);
        return weg;
    }

    private static void ohneMasken(JSONObject o, String pfad, List<String> weg) {
        if (o == null) return;
        List<String> keys = new ArrayList<>();
        for (Iterator<String> it = o.keys(); it.hasNext(); ) keys.add(it.next());
        for (String k : keys) {
            Object v = o.opt(k);
            if (istGeheim(k) && v instanceof JSONObject && ((JSONObject) v).has("gesetzt")) {
                o.remove(k);
                weg.add(pfad + k);
            } else if (v instanceof JSONObject) {
                ohneMasken((JSONObject) v, pfad + k + ".", weg);
            }
        }
    }
}
