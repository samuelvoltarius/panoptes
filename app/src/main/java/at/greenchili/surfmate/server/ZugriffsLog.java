package at.greenchili.surfmate.server;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;
import java.util.TreeMap;

/**
 * 0.1.33: Wer ruft wie oft was ab, je Gerät und Minute, zur Strom-Messung (nur im Speicher, 60 Minuten).
 * Nur Pfad und Anzahl: keine Abfrage, kein Inhalt, keine Kennungen aus dem Pfad.
 */
public final class ZugriffsLog {

    static final int MINUTEN = 60;
    static final int MAX_GERAETE = 32;
    static final int MAX_PFADE = 64;
    static final String ANDERES = "anderes";

    private static final TreeMap<Long, Map<String, Map<String, Integer>>> minuten = new TreeMap<>();

    private ZugriffsLog() {}

    public static void zaehle(String geraet, String pfad, long nowMs) {
        long minute = nowMs / 60_000L;
        String wer = geraet == null || geraet.isEmpty() ? "lokal" : geraet;
        String was = pfadKlasse(pfad);
        synchronized (minuten) {
            Map<String, Map<String, Integer>> m = minuten.get(minute);
            if (m == null) {
                m = new TreeMap<>();
                minuten.put(minute, m);
                while (minuten.size() > MINUTEN) minuten.pollFirstEntry();
            }
            Map<String, Integer> pfade = m.get(wer);
            if (pfade == null) {
                if (m.size() >= MAX_GERAETE) return;
                pfade = new TreeMap<>();
                m.put(wer, pfade);
            }
            if (!pfade.containsKey(was) && pfade.size() >= MAX_PFADE) was = ANDERES;
            pfade.merge(was, 1, Integer::sum);
        }
    }

    /** Teile mit Zahlen oder Kennungen werden zu {@code *}, sonst wächst die Liste und Namen stünden drin. */
    static String pfadKlasse(String pfad) {
        if (pfad == null) return ANDERES;
        int q = pfad.indexOf('?');
        String[] teile = (q >= 0 ? pfad.substring(0, q) : pfad).split("/");
        StringBuilder b = new StringBuilder();
        for (int i = 1; i < teile.length && i <= 5; i++) {
            b.append('/').append(teile[i].matches("(.*[0-9]){3}.*") ? "*" : teile[i]);
        }
        return b.length() == 0 ? "/" : b.toString();
    }

    public static JSONObject alsJson() throws Exception {
        JSONArray a = new JSONArray();
        synchronized (minuten) {
            for (Map.Entry<Long, Map<String, Map<String, Integer>>> e : minuten.entrySet()) {
                JSONObject geraete = new JSONObject();
                for (Map.Entry<String, Map<String, Integer>> g : e.getValue().entrySet()) {
                    geraete.put(g.getKey(), new JSONObject(g.getValue()));
                }
                a.put(new JSONObject().put("minute", e.getKey() * 60_000L).put("geraete", geraete));
            }
        }
        return new JSONObject().put("success", true).put("minuten", a);
    }

    static void leerenFuerTest() {
        synchronized (minuten) {
            minuten.clear();
        }
    }
}
