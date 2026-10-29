package at.greenchili.surfmate.genai;

import at.greenchili.surfmate.byd.KlimaStufe;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * 0.1.27 (Projektinhaber, Befund 29.10. am Auto mit MiniMax): Fassung „Auto jetzt“ für den KI-Assistenten.
 *
 * <p>Quelle ist dieselbe Telemetrie wie {@code GET /api/ha/v1/zustand} und MQTT
 * ({@code MqttConnectionManager.getLatestTelemetry()}): Laden, Ladeleistung, Stecker, Verriegelung und Klima sind
 * dort schon aufgelöst. Hier wird daraus Klartext, den ein Mensch ohne Technikwissen versteht (REGEL DAU):
 * <ul>
 *   <li>nur eine feste Liste von Werten wird gelesen – nie Position, VIN, Rohcodes oder Feldnamen</li>
 *   <li>Sentinels (255, 65535, 1048575, −1, −10011 …) und unplausible Werte fallen weg, statt diskutiert zu werden</li>
 *   <li>nichts, was ein E-Auto nicht hat (Öl, Kühlmittel des Motors, Drehzahl, Tank)</li>
 *   <li>Klima-Sollwert als Stufe („Heating level 4“), nie °C, solange das Modell Stufen zeigt (HANDY_API_VERTRAG §3.2)</li>
 *   <li>Zeiten in Ortszeit, keine Millisekunden</li>
 * </ul>
 * Schlüssel und Werte sind englische Wörter (wie die übrigen KI-Texte); das Modell antwortet in der Sprache des Nutzers.
 * Rein, ohne Daemon-Zugriff, testbar.
 */
public final class AssistentZustand {

    private AssistentZustand() {}

    /** Ab diesem Alter gelten die Werte als „vom letzten Wachsein“ (Auto schläft, keine frische Messung). */
    static final long FRISCH_MS = 3 * 60_000L;

    /** Werte, die das Auto statt „unbekannt“ meldet. Nie als Messwert weitergeben. */
    private static final double[] SENTINELS = {
            255, 65535, 1048575, 16777215, -1, -10011, -10013, Integer.MIN_VALUE, Integer.MAX_VALUE
    };

    private static final String[][] ECKEN = {
            {"fl", "front left"}, {"fr", "front right"}, {"rl", "rear left"}, {"rr", "rear right"}};

    /**
     * @param roh          Telemetrie wie für HA/MQTT (darf null sein)
     * @param waechter     Wächter laut HA-Vertrag (scharf|startet|pausiert|aus|fehler) oder null
     * @param stufenModell true = Klima-Sollwert als Stufe zeigen (Surf), false = °C (andere Modelle)
     */
    public static JSONObject aus(JSONObject roh, String waechter, boolean stufenModell, long jetztMs, ZoneId zone) {
        JSONObject o = new JSONObject();
        try {
            if (roh == null) roh = new JSONObject();
            long gemessen = roh.optLong("vd_timestamp", 0L) * 1000L;
            if (gemessen > 0L) {
                o.put("values measured", ortszeit(gemessen, jetztMs, zone));
                if (jetztMs - gemessen > FRISCH_MS) {
                    o.put("note", "The car is asleep. These are the last values from when it was awake.");
                }
            }

            double soc = zahl(roh, "soc", 0, 100);
            if (!Double.isNaN(soc)) o.put("battery charge", prozent(soc));
            double reichweite = zahl(roh, "ev_range_km", 1, 1016);
            if (!Double.isNaN(reichweite)) o.put("range", Math.round(reichweite) + " km");
            double ziel = zahl(roh, "target_soc", 1, 100);
            if (!Double.isNaN(ziel)) o.put("charge limit", prozent(ziel));

            // Laden: is_charging/charge_power sind in der Telemetrie schon aufgelöst (BMS + Stecker + Leistung)
            int stecker = janein(roh, "plugged");
            if (stecker >= 0) o.put("charging cable", stecker == 1 ? "connected" : "not connected");
            int laedt = janein(roh, "is_charging");
            if (laedt == 1) {
                double kw = zahl(roh, "charge_power", 0.1, 500);
                double geschaetzt = zahl(roh, "charge_power_estimated", 0.1, 500);
                if (!Double.isNaN(kw)) o.put("charging", "yes, " + kw(kw));
                else if (!Double.isNaN(geschaetzt)) o.put("charging", "yes, about " + kw(geschaetzt));
                else o.put("charging", "yes");
                String rest = dauer(zahl(roh, "charging_eta_hours", 0, 99), zahl(roh, "charging_eta_minutes", 0, 59));
                if (rest != null) o.put("time until full", rest);
            } else if (laedt == 0) {
                o.put("charging", "no");
            }

            // Verriegelung wie im Cockpit (VehicleControlApiHandler.sperrStand → KarosserieTelemetrie): 1 = offen
            int offen = janein(roh, "unlocked");
            if (offen >= 0) o.put("locked", offen == 1 ? "no" : "yes");
            liste(o, roh, "open doors", "door_open_", " door");
            if (janein(roh, "trunk_open") == 1) o.put("boot", "open");
            if (janein(roh, "hood_open") == 1) o.put("front lid", "open");
            liste(o, roh, "open windows", "window_open_", " window");

            int zuendung = janein(roh, "ignition");
            if (zuendung >= 0) o.put("car switched on", zuendung == 1 ? "yes" : "no");
            int geparkt = janein(roh, "is_parked");
            if (geparkt == 0) {
                double kmh = zahl(roh, "speed", 0, 250);
                if (!Double.isNaN(kmh) && kmh >= 1) o.put("driving", Math.round(kmh) + " km/h");
                else o.put("driving", "yes");
            } else if (geparkt == 1) {
                o.put("parked", "yes");
            }

            String klima = klima(roh, stufenModell);
            if (klima != null) o.put("climate", klima);
            double aussen = zahl(roh, "ext_temp", -45, 60);
            if (!Double.isNaN(aussen)) o.put("outside temperature", grad(aussen));
            double innen = zahl(roh, "cabin_temp", -40, 90);
            if (!Double.isNaN(innen)) o.put("inside temperature", grad(innen));
            double akkuT = zahl(roh, "batt_temp", -30, 65);
            if (!Double.isNaN(akkuT)) o.put("battery temperature", grad(akkuT));

            double soh = zahl(roh, "soh", 50, 100);
            if (!Double.isNaN(soh)) o.put("battery health", prozent(soh));
            double v12 = zahl(roh, "volt_12v", 10.5, 15.5);
            if (!Double.isNaN(v12)) o.put("small 12 V battery", String.format(Locale.ROOT, "%.1f V", v12));
            String reifen = reifen(roh);
            if (reifen != null) o.put("tyre pressure", reifen);
            double km = zahl(roh, "odometer", 1, 2_000_000);
            if (!Double.isNaN(km)) o.put("total distance driven", Math.round(km) + " km");

            String w = waechter(waechter);
            if (w != null) o.put("Panoptes guard", w);
        } catch (Exception ignored) {
        }
        return o;
    }

    // ------------------------------------------------------------------ Bausteine

    /** Zahl aus der Telemetrie, nur wenn endlich, kein Sentinel und im Bereich; sonst NaN. */
    static double zahl(JSONObject roh, String key, double min, double max) {
        Object v = roh.opt(key);
        if (!(v instanceof Number)) return Double.NaN;
        double d = ((Number) v).doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d)) return Double.NaN;
        for (double s : SENTINELS) {
            if (d == s) return Double.NaN;
        }
        return d < min || d > max ? Double.NaN : d;
    }

    /** 1 / 0 aus einem Binärschlüssel, −1 = unbekannt. */
    static int janein(JSONObject roh, String key) {
        Object v = roh.opt(key);
        if (v instanceof Boolean) return ((Boolean) v) ? 1 : 0;
        if (!(v instanceof Number)) return -1;
        int i = ((Number) v).intValue();
        return i == 0 || i == 1 ? i : -1;
    }

    private static void liste(JSONObject o, JSONObject roh, String titel, String praefix, String nach)
            throws Exception {
        List<String> offen = new ArrayList<>();
        int bekannt = 0;
        for (String[] e : ECKEN) {
            int v = janein(roh, praefix + e[0]);
            if (v < 0) continue;
            bekannt++;
            if (v == 1) offen.add(e[1] + nach);
        }
        if (!offen.isEmpty()) o.put(titel, String.join(", ", offen));
        else if (bekannt == ECKEN.length) o.put(titel, "none");
    }

    static String klima(JSONObject roh, boolean stufenModell) {
        int an = janein(roh, "climate_on");
        if (an < 0) an = janein(roh, "ac_on");
        if (an == 0) return "off";
        if (an != 1) return null;
        double sdk = zahl(roh, "climate_setpoint", KlimaStufe.SDK_MIN, KlimaStufe.SDK_MAX);
        if (Double.isNaN(sdk)) return "on";
        if (!stufenModell) return "on, set to " + Math.round(sdk) + " °C";
        int stufe = KlimaStufe.ausSdk(sdk);
        return KlimaStufe.gueltig(stufe) ? "on, " + KlimaStufe.textEn(stufe) : "on";
    }

    /** Reifendruck in bar, nur wenn alle vier plausibel sind (150–450 kPa). */
    static String reifen(JSONObject roh) {
        StringBuilder sb = new StringBuilder();
        for (String[] e : ECKEN) {
            double kpa = zahl(roh, "tyre_p_" + e[0], 150, 450);
            if (Double.isNaN(kpa)) return null;
            if (sb.length() > 0) sb.append(", ");
            sb.append(e[1]).append(' ').append(String.format(Locale.ROOT, "%.1f bar", kpa / 100.0));
        }
        return sb.toString();
    }

    static String waechter(String z) {
        if (z == null) return null;
        switch (z) {
            case "scharf": return "watching (armed)";
            case "startet": return "starting";
            case "pausiert": return "paused (safe zone or stop)";
            case "aus": return "off";
            case "fehler": return "has a problem";
            default: return null;
        }
    }

    private static String prozent(double v) {
        return Math.round(v) + " %";
    }

    private static String grad(double v) {
        return Math.round(v) + " °C";
    }

    private static String kw(double v) {
        return (v >= 10 ? String.valueOf(Math.round(v)) : String.format(Locale.ROOT, "%.1f", v)) + " kW";
    }

    private static String dauer(double h, double min) {
        if (Double.isNaN(h) && Double.isNaN(min)) return null;
        long hh = Double.isNaN(h) ? 0 : Math.round(h);
        long mm = Double.isNaN(min) ? 0 : Math.round(min);
        if (hh == 0 && mm == 0) return null;
        return hh == 0 ? mm + " min" : mm == 0 ? hh + " h" : hh + " h " + mm + " min";
    }

    // ------------------------------------------------------------------ Ortszeit

    private static final DateTimeFormatter UHR = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final DateTimeFormatter TAG = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH);

    /** „today 14:32“ / „yesterday 08:05“ / „27 Oct 18:40“ in der Zeitzone des Autos. */
    public static String ortszeit(long ms, long jetztMs, ZoneId zone) {
        ZonedDateTime t = Instant.ofEpochMilli(ms).atZone(zone);
        LocalDate heute = Instant.ofEpochMilli(jetztMs).atZone(zone).toLocalDate();
        LocalDate tag = t.toLocalDate();
        if (tag.equals(heute)) return "today " + UHR.format(t);
        if (tag.equals(heute.minusDays(1))) return "yesterday " + UHR.format(t);
        return TAG.format(t);
    }

    /** Zeitstempel in Millisekunden (Fahrten, Ladevorgänge, Ereignisse) rekursiv in Ortszeit umschreiben. */
    static void zeitenInOrtszeit(Object knoten, long jetztMs, ZoneId zone) {
        try {
            if (knoten instanceof JSONObject) {
                JSONObject j = (JSONObject) knoten;
                List<String> keys = new ArrayList<>();
                for (Iterator<String> it = j.keys(); it.hasNext(); ) keys.add(it.next());
                for (String k : keys) {
                    Object v = j.opt(k);
                    if (v instanceof Number && istZeitSchluessel(k)) {
                        long ms = ((Number) v).longValue();
                        if (ms > 1_000_000_000_000L && ms < 10_000_000_000_000L) {
                            j.put(k, ortszeit(ms, jetztMs, zone));
                        } else {
                            j.remove(k);
                        }
                    } else {
                        zeitenInOrtszeit(v, jetztMs, zone);
                    }
                }
            } else if (knoten instanceof JSONArray) {
                JSONArray a = (JSONArray) knoten;
                for (int i = 0; i < a.length(); i++) zeitenInOrtszeit(a.opt(i), jetztMs, zone);
            }
        } catch (Exception ignored) {
        }
    }

    static boolean istZeitSchluessel(String k) {
        return k.equals("timestamp") || k.equals("startTime") || k.equals("endTime")
                || k.endsWith("AtMs") || k.endsWith("TimeMs") || k.equals("lastTriggeredMs");
    }
}
