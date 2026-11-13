package at.greenchili.surfmate.server;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 0.1.44 (P4) Home-Assistant-Kachel des Launchers, NUR LESEN.
 *
 * <p>Quelle ist allein der Bestand der HA-Integration ({@code HaGeraeteSpeicher}, gemeldet über
 * {@code POST /api/ha/v1/geraete}, Kopplung mit Recht {@code geraete}, Standard AUS). Der Launcher bekommt
 * hier nur Anzeigefelder: Kennung, Titel, Raum, Art und den zuletzt gemeldeten Zustand. Keine Zugangsdaten, kein
 * Token, keine Fähigkeiten, kein Steuerweg – {@code GET} ist die einzige Methode (siehe {@code LauncherApiHandler}).
 * Gerätenamen sind privat: der Weg ist nur für die Launcher-APK mit Panoptes-Signatur offen.
 *
 * <p>Ohne {@code ids} antwortet der Weg mit der ganzen Liste (für die Auswahl am Gerät), mit {@code ids} nur mit den
 * gewählten (höchstens {@link #MAX_AUSWAHL}, in der gewählten Reihenfolge). Rein, im Unit-Test belegt.
 */
public final class LauncherHaus {

    private LauncherHaus() {}

    public static final String PFAD = "/api/launcher/v1/haus";
    public static final int MAX_AUSWAHL = 6;

    private static final Pattern ENTITAET = Pattern.compile("[a-z_]{1,32}\\.[a-z0-9_]{1,128}");

    /** Kennungen aus {@code ids=a,b,c} (schon URL-dekodiert): nur gültige, ohne Doppelte, höchstens sechs. */
    public static List<String> ids(String roh) {
        List<String> out = new ArrayList<>();
        if (roh == null) return out;
        for (String s : roh.split(",")) {
            String e = s.trim();
            if (ENTITAET.matcher(e).matches() && !out.contains(e) && out.size() < MAX_AUSWAHL) out.add(e);
        }
        return out;
    }

    /**
     * @param bestand Inhalt des Geräte-Speichers (oder null)
     * @param ids     gewählte Kennungen oder null = ganze Liste
     */
    public static JSONObject antwort(JSONObject bestand, List<String> ids, long jetztMs) throws JSONException {
        JSONArray liste = bestand == null ? null : bestand.optJSONArray("geraete");
        JSONArray out = new JSONArray();
        JSONArray fehlt = new JSONArray();
        if (ids == null) {
            for (int i = 0; liste != null && i < liste.length(); i++) {
                JSONObject g = anzeige(liste.optJSONObject(i));
                if (g != null) out.put(g);
            }
        } else {
            for (String e : ids) {
                JSONObject g = anzeige(finde(liste, e));
                if (g != null) out.put(g);
                else fehlt.put(e);
            }
        }
        long zeit = bestand == null ? 0L : bestand.optLong("zeit", 0L);
        JSONObject r = new JSONObject().put("ok", true).put("geraete", out).put("fehlt", fehlt)
                .put("zeit", zeit > 0 ? zeit : JSONObject.NULL)
                .put("alterSek", zeit > 0 && jetztMs >= zeit ? (jetztMs - zeit) / 1000 : JSONObject.NULL);
        return r;
    }

    private static JSONObject finde(JSONArray liste, String entity) {
        for (int i = 0; liste != null && i < liste.length(); i++) {
            JSONObject g = liste.optJSONObject(i);
            if (g != null && entity.equals(g.optString("entity"))) return g;
        }
        return null;
    }

    /** Nur die Anzeigefelder (nie Fähigkeiten, Plattform, Freigaben). */
    static JSONObject anzeige(JSONObject g) throws JSONException {
        if (g == null) return null;
        String e = g.optString("entity", "");
        if (!ENTITAET.matcher(e).matches()) return null;
        JSONObject k = new JSONObject().put("entity", e)
                .put("titel", g.optString("titel", e))
                .put("art", g.optString("art", ""))
                .put("zustand", g.isNull("zustand") ? JSONObject.NULL : g.optString("zustand", ""));
        String raum = g.isNull("raum") ? "" : g.optString("raum", "");
        if (!raum.isEmpty()) k.put("raum", raum);
        return k;
    }
}
