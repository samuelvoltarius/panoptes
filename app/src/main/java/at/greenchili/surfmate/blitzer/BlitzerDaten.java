package at.greenchili.surfmate.blitzer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Blitzer-Datensatz je Land, offline nutzbar (Karten-Sitzung: {@code /tiles/blitzer/<land>.json},
 * aus OSM {@code highway=speed_camera}).
 *
 * <pre>{"land":"at","stand":"2026-10-21","quelle":"© OpenStreetMap-Mitwirkende (ODbL)",
 *  "punkte":[[lat, lon, maxspeed], …]}</pre>
 * maxspeed 0 = unbekannt. Rein: Lesen und Prüfen; Laden/Speichern macht {@link BlitzerDienst}.
 */
public final class BlitzerDaten {

    private BlitzerDaten() {}

    /** Länder, die im Testmodus zusätzlich geladen werden (Alfreds Testschalter). */
    public static final String[] TEST_LAENDER = {"at", "de", "ch", "it", "si", "hu", "cz", "sk", "li"};
    static final int MAX_PUNKTE = 200_000;

    /** Liest einen Datensatz; null bei falschem Land/Format. */
    public static List<BlitzerWarner.Blitzer> lies(String json, String erwartetesLand) {
        try {
            JSONObject o = new JSONObject(json);
            String land = o.optString("land", "").toLowerCase(Locale.ROOT);
            if (!land.equals(erwartetesLand)) return null;
            JSONArray p = o.optJSONArray("punkte");
            if (p == null) return null;
            List<BlitzerWarner.Blitzer> out = new ArrayList<>(Math.min(p.length(), MAX_PUNKTE));
            for (int i = 0; i < p.length() && out.size() < MAX_PUNKTE; i++) {
                JSONArray q = p.optJSONArray(i);
                if (q == null || q.length() < 2) continue;
                double lat = q.optDouble(0, Double.NaN), lon = q.optDouble(1, Double.NaN);
                if (Double.isNaN(lat) || Double.isNaN(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) continue;
                int vmax = q.optInt(2, 0);
                if (vmax < 0 || vmax > 200) vmax = 0;
                String id = land + ":" + Math.round(lat * 100000) + ":" + Math.round(lon * 100000);
                out.add(new BlitzerWarner.Blitzer(id, land, lat, lon, vmax));
            }
            return Collections.unmodifiableList(out);
        } catch (Exception e) {
            return null;
        }
    }
}
