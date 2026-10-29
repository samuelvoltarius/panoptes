package at.greenchili.surfmate.weather;

import at.greenchili.surfmate.logging.DaemonLogger;
import at.greenchili.surfmate.mqtt.ProxyHelper;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared outside-air temperature, keyed on GPS coordinates. 0.1.27 (Koordination 28.10., API_FUER_SURFMATE §18):
 * das Auto fragt NUR noch den eigenen Server ({@link #STANDARD_URL}, Daten von Open-Meteo, CC BY 4.0) mit einer
 * Rasterzelle von 0,1° (≈ 10 km) – ohne Kennung; die IP des Autos geht nicht mehr an Dritte. Single source of truth for weather temp across the daemon (the
 * automation temperature fallback, ABRP {@code ext_temp}, …) so there is ONE
 * cache and ONE network path, not a copy per caller.
 *
 * <p>Design for the automation use-case: the value is read from the telemetry
 * poll thread (via BydEvent), which must NEVER block on the network. So the
 * public {@link #getCached()} / {@link #refreshAsync} split is:
 * <ul>
 *   <li>{@link #getCached()} returns the last fetched value instantly (NaN if none
 *       yet / too stale), never touching the network.</li>
 *   <li>{@link #refreshAsync(double, double)} kicks a background fetch if the cache
 *       is stale and no fetch is already in flight. Cheap to call every poll.</li>
 * </ul>
 * On ACC-off the head-unit data interface briefly drops ~17s after park but
 * self-recovers ~3 min later while still parked, so an async fetch that fails is
 * simply retried on a later poll; the cache rides out the transient blackout.
 */
public final class WeatherTemperature {
    private static final DaemonLogger logger = DaemonLogger.getInstance("WeatherTemperature");

    /** Serve the cached value for this long before a refresh is attempted. */
    private static final long CACHE_MS = 10 * 60 * 1000; // 10 min
    /** Discard the cache entirely past this age (a parked car's local weather
     *  doesn't change fast, but a day-old value shouldn't drive an automation). */
    private static final long STALE_MS = 60 * 60 * 1000; // 1 h

    private static volatile double cachedTemp = Double.NaN;
    private static volatile long lastFetchTime = 0;
    private static final AtomicBoolean fetchInFlight = new AtomicBoolean(false);

    // Mean precipitation probability (%) over the next PRECIP_HOURS hours (0.1.27: Server-Mittel, sonst Höchstwert), from the same
    // Open-Meteo fetch (one round-trip serves temp + rain). -1 = not yet fetched/stale.
    // Drives the "rain likely" automation trigger; kept on the same cache clock as temp.
    private static final int PRECIP_HOURS = 6;
    private static volatile int cachedPrecipProb = -1;

    private WeatherTemperature() {}

    /** Eigener Wetter-Endpunkt (Server fragt Open-Meteo je Zelle höchstens alle 15 min); überschreibbar: wetter.url. */
    public static final String STANDARD_URL = "https://byd.serveone.at/api/wetter/v1/jetzt";

    /** Rasterzelle „lat_lon“ auf 0,1° (genau eine Nachkommastelle, wie der Server sie verlangt); −0.0 → 0.0. */
    static String zelle(double lat, double lon) {
        double a = Math.round(lat * 10) / 10.0, b = Math.round(lon * 10) / 10.0;
        if (a == 0) a = 0.0;
        if (b == 0) b = 0.0;
        return String.format(Locale.US, "%.1f_%.1f", a, b);
    }

    static String url(double lat, double lon) {
        String basis = STANDARD_URL;
        try {
            JSONObject w = at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig().optJSONObject("wetter");
            String u = w == null ? "" : w.optString("url", "").trim();
            if (u.startsWith("https://")) basis = u;
        } catch (Throwable ignored) {
        }
        return basis + "?zelle=" + zelle(lat, lon);
    }

    /**
     * Mean precipitation probability (%) over roughly the next {@value #PRECIP_HOURS}
     * hours, or -1 if none fetched yet / cache is stale. Never blocks. Pair with
     * {@link #refreshAsync} on the poll thread the same way as {@link #getCached()}.
     */
    public static int getCachedPrecipProbability() {
        if (cachedPrecipProb < 0) return -1;
        if (System.currentTimeMillis() - lastFetchTime > STALE_MS) return -1;
        return cachedPrecipProb;
    }

    /**
     * The last fetched outside temperature (°C), or NaN if none has been fetched
     * yet or the cached value is older than {@link #STALE_MS}. Never blocks.
     */
    public static double getCached() {
        double t = cachedTemp;
        if (Double.isNaN(t)) return Double.NaN;
        if (System.currentTimeMillis() - lastFetchTime > STALE_MS) return Double.NaN;
        return t;
    }

    /**
     * Kick a background refresh if the cache is stale ({@literal >}{@link #CACHE_MS})
     * and no fetch is already running. Returns immediately. Safe to call every poll.
     *
     * @param lat last-known latitude  (parked car doesn't move, so last-known is fine)
     * @param lon last-known longitude
     */
    public static void refreshAsync(final double lat, final double lon) {
        if (lat == 0.0 && lon == 0.0) return; // no location yet
        long now = System.currentTimeMillis();
        if (!Double.isNaN(cachedTemp) && (now - lastFetchTime) < CACHE_MS) return; // fresh enough
        if (!fetchInFlight.compareAndSet(false, true)) return; // one fetch at a time
        Thread t = new Thread(() -> {
            try {
                fetchNow(lat, lon);
            } finally {
                fetchInFlight.set(false);
            }
        }, "weather-temp-fetch");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Synchronous fetch — for callers already off the hot path (e.g. ABRP's own
     * upload thread). Updates the shared cache. Returns the fetched value, or the
     * existing cache (possibly NaN) on failure. Bounded to a few seconds.
     */
    /** Einwilligung „Wetter online“ (wetter.online, Standard aus). Fehler → aus. */
    public static boolean onlineErlaubt() {
        try {
            org.json.JSONObject w = at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig()
                    .optJSONObject("wetter");
            return w != null && w.optBoolean("online", false);
        } catch (Throwable t) {
            return false;
        }
    }

    public static double fetchNow(double lat, double lon) {
        if (lat == 0.0 && lon == 0.0) return getCached();
        // Regel Nr. 2 (21.10.): Position verlässt das Auto nur mit ausdrücklicher Einwilligung.
        // wetter.online Standard AUS; ohne Einwilligung kein Abruf (Außentemperatur dann nur vom Fahrzeug).
        if (!onlineErlaubt()) return getCached();
        long now = System.currentTimeMillis();
        // Serve fresh cache without a network round-trip.
        if (!Double.isNaN(cachedTemp) && (now - lastFetchTime) < CACHE_MS) return cachedTemp;
        try {
            // Ein Aufruf liefert Temperatur und Regenwahrscheinlichkeit (höchste der nächsten 6 h).
            Request request = new Request.Builder()
                    .url(url(lat, lon))
                    .header("User-Agent", "Panoptes/" + at.greenchili.surfmate.BuildConfig.VERSION_NAME)
                    .build();
            // Chain selector (proxy → direct) rather than a frozen proxy: a
            // tailnet-only Tailscale listener would otherwise block the fetch
            // while probing healthy, and weather's 3s budget can't afford it.
            OkHttpClient client = new OkHttpClient.Builder()
                    .proxySelector(ProxyHelper.chainProxySelector())
                    .connectTimeout(3, TimeUnit.SECONDS)
                    .readTimeout(3, TimeUnit.SECONDS)
                    .build();
            try (Response response = client.newCall(request).execute()) {
                // 429 (Ratenbremse) / 503 (Server ohne Wert): letzten Wert behalten
                if (response.isSuccessful() && response.body() != null) {
                    double temp = uebernehmen(new JSONObject(response.body().string()));
                    if (!Double.isNaN(temp)) return temp;
                }
            }
        } catch (Exception e) {
            logger.debug("weather fetch failed: " + e.getMessage());
        }
        return getCached(); // stale cache or NaN
    }

    /**
     * Antwort des eigenen Servers in den Zwischenspeicher: {@code {temp_c, regen_6h_prozent|null, stand, veraltet?, ok}}.
     * Liefert die Temperatur oder NaN. Ein vom Server als „veraltet“ markierter Wert (≤ 1 h) wird übernommen.
     */
    private static int regen(JSONObject root, String feld) {
        if (!root.has(feld) || root.isNull(feld)) return -1;
        int p = root.optInt(feld, -1);
        return p >= 0 && p <= 100 ? p : -1;
    }

    static double uebernehmen(JSONObject root) {
        if (!root.optBoolean("ok", false)) return Double.NaN;
        // „Regen wahrscheinlich“ behält seine Bedeutung (Mittel der nächsten 6 h, wie vor 0.1.27): Mittelwert des
        // Servers, wenn er ihn liefert; sonst die höchste Wahrscheinlichkeit (Koordination 28.10.)
        int p = regen(root, "regen_6h_mittel_prozent");
        if (p < 0) p = regen(root, "regen_6h_prozent");
        if (p >= 0) cachedPrecipProb = p;
        if (!root.has("temp_c") || root.isNull("temp_c")) return Double.NaN;
        double temp = root.optDouble("temp_c", Double.NaN);
        if (Double.isNaN(temp) || temp < -60 || temp > 60) return Double.NaN;
        cachedTemp = temp;
        lastFetchTime = System.currentTimeMillis();
        logger.debug("weather temp=" + String.format(Locale.US, "%.1f", temp) + "°C precip=" + cachedPrecipProb + "%"
                + (root.optBoolean("veraltet", false) ? " (veraltet)" : ""));
        return temp;
    }
}
