package at.greenchili.surfmate.server;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;

/**
 * 0.1.44 (P4) Fahrerprofile: welche gespeicherte Sitzposition passt gerade zum Sitz? NUR LESEN.
 *
 * <p>Der Kern liest die Sitzgeometrie über den vorhandenen Lesepfad ({@code BodyworkSeatProbe.readFullBundle}, wie
 * {@code GET /api/positions/current}) und vergleicht sie mit den gespeicherten Positionen ({@code PositionStore}).
 * Es wird nie eine Position angewendet und nie etwas am Sitz gesetzt. Der Launcher ordnet jedem seiner Profile
 * selbst eine Position zu und schaltet nur seine eigene Anzeige um.
 *
 * <p>Ehrlich statt geraten: nur wenn genau eine Position innerhalb der Toleranz liegt und mindestens
 * {@link #MIN_ACHSEN} Achsen vergleichbar sind, wird sie gemeldet, sonst {@code null}. Die Toleranz ist ein Schätzwert,
 * am Auto ungeprüft. Rein, im Unit-Test belegt.
 */
public final class LauncherSitz {

    private LauncherSitz() {}

    public static final String PFAD = "/api/launcher/v1/sitz";
    /** Höchste Abweichung je Achse (Einheit der Fahrzeugachsen), ungeprüft am Auto. */
    public static final double TOLERANZ = 2.0;
    public static final int MIN_ACHSEN = 3;
    /** Zwischenspeicher: der Lesepfad fragt das Fahrzeug, darum höchstens so oft. */
    public static final long CACHE_MS = 30_000L;
    private static final double SENTINEL = 127.5;

    private static long cacheZeit;
    private static String cacheId;
    private static boolean cacheLesbar;

    /** Id der einzigen passenden Position oder null (keine, mehrere gleich nah, zu wenige Achsen). */
    public static String passend(JSONObject live, JSONArray positionen) {
        if (live == null || positionen == null) return null;
        String beste = null;
        double besteAbw = Double.MAX_VALUE;
        boolean gleich = false;
        for (int i = 0; i < positionen.length(); i++) {
            JSONObject p = positionen.optJSONObject(i);
            JSONObject a = p == null ? null : p.optJSONObject("axes");
            if (a == null || a.length() == 0) continue;
            double summe = 0;
            int n = 0;
            boolean ok = true;
            for (Iterator<String> it = a.keys(); it.hasNext(); ) {
                String k = it.next();
                double gespeichert = a.optDouble(k, Double.NaN);
                double jetzt = live.optDouble(k, Double.NaN);
                if (Double.isNaN(gespeichert) || Double.isNaN(jetzt) || unbekannt(gespeichert) || unbekannt(jetzt)) continue;
                double d = Math.abs(gespeichert - jetzt);
                if (d > TOLERANZ) { ok = false; break; }
                summe += d;
                n++;
            }
            if (!ok || n < MIN_ACHSEN) continue;
            if (summe < besteAbw - 1e-9) { beste = p.optString("id", null); besteAbw = summe; gleich = false; }
            else if (Math.abs(summe - besteAbw) <= 1e-9) gleich = true;
        }
        return gleich ? null : beste;
    }

    private static boolean unbekannt(double v) {
        return Math.abs(v - SENTINEL) < 0.01;
    }

    /** Antwort für {@code GET /api/launcher/v1/sitz}: die Positionen (Kennung, Name) und die passende, falls lesbar. */
    public static JSONObject antwort(JSONArray positionen, JSONObject live) throws JSONException {
        return antwort(positionen, live == null ? null : passend(live, positionen), live != null);
    }

    static JSONObject antwort(JSONArray positionen, String aktuell, boolean lesbar) throws JSONException {
        JSONArray liste = new JSONArray();
        for (int i = 0; positionen != null && i < positionen.length(); i++) {
            JSONObject p = positionen.optJSONObject(i);
            if (p == null || p.optJSONObject("axes") == null || p.optJSONObject("axes").length() == 0) continue;
            String id = p.optString("id", "");
            if (id.isEmpty()) continue;
            String alias = p.optString("alias", "").trim();
            liste.put(new JSONObject().put("id", id).put("name", !alias.isEmpty() ? alias : p.optString("name", "")));
        }
        return new JSONObject().put("ok", true).put("positionen", liste)
                .put("aktuell", aktuell == null ? JSONObject.NULL : aktuell).put("lesbar", lesbar);
    }

    /** Mit Zwischenspeicher; liest nie öfter als alle {@link #CACHE_MS}. Nie schreibend. */
    public static synchronized JSONObject lesen(long jetztMs) throws JSONException {
        JSONArray positionen = at.greenchili.surfmate.byd.PositionStore.getInstance().list();
        if (jetztMs - cacheZeit >= CACHE_MS || cacheZeit == 0) {
            cacheZeit = jetztMs;
            JSONObject live = null;
            try {
                android.content.Context ctx = null;
                try { ctx = at.greenchili.surfmate.daemon.CameraDaemon.getAppContext(); } catch (Throwable ignore) { }
                if (ctx == null) {
                    try { ctx = at.greenchili.surfmate.daemon.DaemonBootstrap.getContext(); } catch (Throwable ignore) { }
                }
                if (ctx != null) live = at.greenchili.surfmate.byd.BodyworkSeatProbe.readFullBundle(ctx);
            } catch (Throwable ignore) { }
            if (live != null && live.length() == 0) live = null;
            cacheLesbar = live != null;
            cacheId = live == null ? null : passend(live, positionen);
        }
        return antwort(positionen, cacheId, cacheLesbar);
    }
}
