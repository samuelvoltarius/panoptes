package at.greenchili.surfmate.ladekurve;

import at.greenchili.surfmate.analytics.Mitzaehlen;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 0.1.19 „Ladekurve anonym teilen“ (Regel 2: nur mit Einwilligung, Standard AUS). Schickt höchstens einmal am Tag (UTC)
 * und nur, wenn sich die Kurve geändert hat, die Zusammenfassung aus {@link Ladekurve#zusammenfassung} an den Planer
 * (LADEKURVE_VERTRAG.md): Modell, SoC-Bänder, Median-kW, Anzahl. Ohne Mitglieds-Schlüssel, ohne Ort, Zeit oder VIN;
 * die Kennung ist eine lokale Zufalls-UUID nur für diesen Zweck (eine Kurve je Auto ersetzen bzw. löschen).
 *
 * <p>Ausschalten bzw. „Kennung löschen“ stellt die Kennung in die Löschliste; der Takt schickt
 * {@code {"kennung":…,"loeschen":true}}, bis der Server antwortet (4xx außer 429 gilt als erledigt). Still bei Fehlern.
 *
 * <p>Config-Abschnitt {@code ladekurve}: {@code teilen}, {@code kennung}, {@code letzterTag}, {@code letzterAbdruck},
 * {@code loeschen}, {@code url} (leer = {@link #STANDARD_URL}).
 */
public final class LadekurveTeilen {

    private LadekurveTeilen() {}

    public static final String ABSCHNITT = "ladekurve";
    public static final String STANDARD_URL = "https://byd.serveone.at/ev/api/ladekurve";

    /** Ein Takt. Wirft nie. */
    public static void takt(long jetztMs, String version, JSONObject kurvenSpeicher, String fahrzeug,
                            Mitzaehlen.Speicher speicher, Mitzaehlen.Sender sender) {
        try {
            JSONObject z = speicher.lese();
            if (z == null) z = new JSONObject();
            String url = z.optString("url", "").trim();
            if (url.isEmpty()) url = STANDARD_URL;
            boolean an = z.optBoolean("teilen", false);
            String id = z.optString("kennung", "").trim();
            String loeschen = z.optString("loeschen", "");
            JSONObject neu = new JSONObject();

            if (!an && !id.isEmpty()) {
                loeschen = Mitzaehlen.loeschenDazu(loeschen, id);
                id = "";
                neu.put("kennung", "").put("letzterTag", "").put("letzterAbdruck", "").put("loeschen", loeschen);
            }

            List<String> offen = liste(loeschen);
            if (!offen.isEmpty()) {
                List<String> rest = new ArrayList<>(offen);
                for (String alt : offen) {
                    if (ok(sender, url, new JSONObject().put("kennung", alt).put("loeschen", true))) rest.remove(alt);
                }
                if (rest.size() != offen.size()) neu.put("loeschen", String.join(",", rest));
            }

            if (an) {
                if (id.isEmpty()) {
                    id = Mitzaehlen.neueKennung();
                    neu.put("kennung", id);
                }
                String heute = tag(jetztMs);
                if (!heute.equals(z.optString("letzterTag", ""))) {
                    JSONObject p = Ladekurve.zusammenfassung(kurvenSpeicher, fahrzeug, id, version);
                    String abdruck = Ladekurve.fingerabdruck(p);
                    if (p != null && !abdruck.equals(z.optString("letzterAbdruck", ""))
                            && ok(sender, url, p)) {
                        neu.put("letzterTag", heute).put("letzterAbdruck", abdruck);
                    }
                }
            }
            if (neu.length() > 0) speicher.schreibe(neu);
        } catch (Throwable ignored) {
            // still
        }
    }

    static List<String> liste(String s) {
        List<String> l = new ArrayList<>();
        if (s == null) return l;
        for (String x : s.split(",")) if (!x.trim().isEmpty()) l.add(x.trim());
        return l;
    }

    static String tag(long ms) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date(ms));
    }

    private static boolean ok(Mitzaehlen.Sender s, String url, JSONObject body) {
        try {
            int code = s.post(url, body.toString());
            return code >= 200 && code < 500 && code != 429;
        } catch (Throwable t) {
            return false;
        }
    }
}
