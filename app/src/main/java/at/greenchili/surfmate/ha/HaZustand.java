package at.greenchili.surfmate.ha;

import at.greenchili.surfmate.mqtt.TelemetryFieldCatalog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

/**
 * Bausteine für {@code GET /api/ha/v1/zustand} (HA_VERTRAG.md §3) – rein, ohne Daemon-Zugriff, testbar.
 * Grundsatz: nie VIN, nie Koordinaten in {@code telemetrie}; fehlende Werte = {@code null}.
 */
public final class HaZustand {

    private HaZustand() {}

    public static final int VERSION = 1;

    /** Schlüssel, die nie in telemetrie stehen (Position, Kennungen). */
    static final Set<String> NIE = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "lat", "lon", "latitude", "longitude", "elevation", "altitude", "heading", "gps", "vin",
            "position", "location", "address", "adresse")));

    /** Gefilterte Telemetrie: nur einfache Werte, keine Position/VIN, keine Zeitfelder. */
    public static JSONObject telemetrie(JSONObject roh) {
        JSONObject t = new JSONObject();
        if (roh == null) return t;
        for (Iterator<String> it = roh.keys(); it.hasNext(); ) {
            String k = it.next();
            if (verboten(k) || !TelemetryFieldCatalog.isPublishable(k)) continue;
            Object v = roh.opt(k);
            if (v instanceof Number || v instanceof Boolean || v instanceof String) {
                try { t.put(k, v); } catch (Exception ignored) { }
            }
        }
        return t;
    }

    static boolean verboten(String k) {
        String l = k.toLowerCase(Locale.ROOT);
        return NIE.contains(l) || l.contains("vin") || l.startsWith("gps_") || l.startsWith("lat_")
                || l.startsWith("lon_") || l.endsWith("_lat") || l.endsWith("_lon");
    }

    /** Katalog als Daten (§3 felder): nur Schlüssel, die in telemetrie stehen und ein HA-Sensor sein dürfen. */
    public static JSONArray felder(JSONObject telemetrie) {
        JSONArray a = new JSONArray();
        for (Iterator<String> it = telemetrie.keys(); it.hasNext(); ) {
            String k = it.next();
            if (!TelemetryFieldCatalog.isDiscoverable(k)) continue;
            TelemetryFieldCatalog.Field f = TelemetryFieldCatalog.get(k);
            JSONObject o = new JSONObject();
            try {
                o.put("key", k);
                o.put("titel", f.name);
                o.put("typ", f.isBinary() ? "binary" : "sensor");
                o.put("deviceClass", f.deviceClass == null ? JSONObject.NULL : f.deviceClass);
                o.put("stateClass", f.stateClass == null ? JSONObject.NULL : f.stateClass);
                o.put("einheit", f.unit == null ? JSONObject.NULL : f.unit);
                o.put("icon", f.icon == null ? JSONObject.NULL : f.icon);
                o.put("erweitert", f.diagnostic);
            } catch (Exception ignored) { }
            a.put(o);
        }
        return a;
    }

    /** Wächter-Zustand Panoptes (scharf|startet|unterdrueckt|aus) → Vertrag (scharf|startet|pausiert|aus|fehler). */
    public static String waechter(String zustand, boolean gesundheitFehler) {
        if (zustand == null) return gesundheitFehler ? "fehler" : "aus";
        switch (zustand) {
            case "scharf": return "scharf";
            case "startet": return gesundheitFehler ? "fehler" : "startet";
            case "unterdrueckt": return "pausiert";
            case "aus": return "aus";
            default: return "fehler";
        }
    }

    /** Gesundheit (GesundheitsDienst.status) → {zustand ok|warnung|fehler, punkte[]}. */
    public static JSONObject gesundheit(JSONObject g) {
        JSONObject o = new JSONObject();
        try {
            if (g == null || !g.has("ok")) {
                o.put("zustand", JSONObject.NULL);
                o.put("punkte", new JSONArray());
                return o;
            }
            JSONArray befunde = g.optJSONArray("befunde");
            int n = befunde == null ? 0 : befunde.length();
            String stufe = g.optString("letzteStufe", "keine");
            String z = g.optBoolean("ok", false) ? "ok" : ("app".equals(stufe) || n >= 2 ? "fehler" : "warnung");
            o.put("zustand", z);
            JSONArray punkte = new JSONArray();
            for (int i = 0; i < n; i++) {
                String id = befunde.optString(i, "");
                if (id.isEmpty()) continue;
                punkte.put(new JSONObject().put("id", id).put("ok", false).put("titel", id));
            }
            o.put("punkte", punkte);
        } catch (Exception ignored) { }
        return o;
    }

    /** F1 (/api/navi/unterwegs) → {stufe reicht|knapp|reicht_nicht|keine_route|unbekannt, grund}. */
    public static JSONObject f1(JSONObject u) {
        JSONObject o = new JSONObject();
        try {
            if (u == null) {
                o.put("stufe", "unbekannt").put("grund", JSONObject.NULL);
            } else if (!u.optBoolean("aktiv", false)) {
                o.put("stufe", "keine_route").put("grund", u.optString("grund", null) == null
                        ? JSONObject.NULL : u.optString("grund"));
            } else {
                String s = u.isNull("stufe") ? "" : u.optString("stufe", "");
                String v;
                switch (s) {
                    case "passt": v = "reicht"; break;
                    case "knapp": v = "knapp"; break;
                    case "reicht_nicht": v = "reicht_nicht"; break;
                    default: v = "unbekannt";
                }
                o.put("stufe", v).put("grund", JSONObject.NULL);
            }
        } catch (Exception ignored) { }
        return o;
    }

    /** Teile von {@code neu}, die sich gegenüber {@code alt} geändert haben (oberste Ebene; telemetrie je Schlüssel). */
    public static JSONObject diff(JSONObject alt, JSONObject neu) {
        JSONObject d = new JSONObject();
        if (neu == null) return d;
        for (Iterator<String> it = neu.keys(); it.hasNext(); ) {
            String k = it.next();
            Object n = neu.opt(k);
            Object a = alt == null ? null : alt.opt(k);
            try {
                if ("telemetrie".equals(k) && n instanceof JSONObject && a instanceof JSONObject) {
                    JSONObject td = new JSONObject();
                    JSONObject nt = (JSONObject) n, at = (JSONObject) a;
                    for (Iterator<String> i2 = nt.keys(); i2.hasNext(); ) {
                        String tk = i2.next();
                        if (!gleich(nt.opt(tk), at.opt(tk))) td.put(tk, nt.opt(tk));
                    }
                    if (td.length() > 0) d.put(k, td);
                } else if (!gleich(n, a)) {
                    d.put(k, n);
                }
            } catch (Exception ignored) { }
        }
        return d;
    }

    static boolean gleich(Object a, Object b) {
        if (a == null || b == null) return a == b;
        return String.valueOf(a).equals(String.valueOf(b));
    }
}
