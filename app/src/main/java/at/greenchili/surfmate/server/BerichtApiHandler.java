package at.greenchili.surfmate.server;

import at.greenchili.surfmate.bericht.Monatsbericht;
import at.greenchili.surfmate.fahrtenbuch.FahrtenbuchPostfach;
import at.greenchili.surfmate.fahrtenbuch.FahrtenbuchSeite;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.TreeSet;

/**
 * H7: {@code GET /api/bericht/monat?monat=YYYY-MM} (Login oder Kopplung, nur lesen; NEUBAU2 §3). Ohne monat = laufender
 * Monat. Gerechnet aus Fahrten (TripDatabase) und Ladevorgängen (SocHistoryDatabase) im Auto.
 */
public final class BerichtApiHandler {

    private BerichtApiHandler() {}

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String pathOnly = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        if (!pathOnly.equals("/api/bericht/monat") || !method.equals("GET")) return false;
        String query = path.contains("?") ? path.substring(path.indexOf('?') + 1) : "";
        TimeZone tz = TimeZone.getDefault();
        long now = System.currentTimeMillis();
        String jetzt = Monatsbericht.monatVon(now, tz);
        String monat = jetzt;
        for (String p : query.split("&")) if (p.startsWith("monat=")) monat = p.substring(6);
        long[] z = Monatsbericht.monat(monat, tz);
        if (z == null) {
            HttpResponse.sendJson(out, 400, "{\"ok\":false,\"grund\":\"monat\"}");
            return true;
        }
        long[] vz = Monatsbericht.monat(Monatsbericht.vormonat(monat), tz);
        JSONObject fahren = Monatsbericht.fahren(fahrten(z[0], z[1]));
        JSONObject vor = Monatsbericht.fahren(fahrten(vz[0], vz[1]));
        JSONArray sitzungen = new JSONArray();
        try {
            sitzungen = at.greenchili.surfmate.monitor.SocHistoryDatabase.getInstance()
                    .getChargingSessionsV2Range(z[0], z[1] - 1, 1000, 0);
        } catch (Throwable ignored) { }
        JSONObject laden = Monatsbericht.laden(sitzungen, zonen());
        HttpResponse.sendJson(out, Monatsbericht.bericht(monat, monate(jetzt, tz), monat.equals(jetzt),
                fahren, laden, vor).toString());
        return true;
    }

    private static List<FahrtenbuchSeite.Fahrt> fahrten(long von, long bis) {
        List<FahrtenbuchSeite.Fahrt> l = new ArrayList<>();
        try {
            at.greenchili.surfmate.trips.TripAnalyticsManager m =
                    at.greenchili.surfmate.daemon.CameraDaemon.getTripAnalyticsManager();
            at.greenchili.surfmate.trips.TripDatabase db = m == null ? null : m.getDatabase();
            if (db == null) return l;
            for (at.greenchili.surfmate.trips.TripRecord t : db.getTripsBetween(von, bis - 1, 10_000, 0)) {
                l.add(FahrtenbuchPostfach.alsFahrt(t));
            }
        } catch (Throwable ignored) { }
        return l;
    }

    private static List<Monatsbericht.Zone> zonen() {
        List<Monatsbericht.Zone> l = new ArrayList<>();
        // 0.1.11 ORTE_VERTRAG: „zuhause“ ist eine ausdrückliche Eigenschaft eines Orts (Migration: alle bisherigen
        // Ruhezonen = zuhause, also gleiches Ergebnis wie vorher). Noch nicht migriert: bisheriger Weg.
        org.json.JSONArray orte = at.greenchili.surfmate.orte.OrteDaemon.listeOhneAbgleich();
        if (orte != null) {
            for (int i = 0; i < orte.length(); i++) {
                org.json.JSONObject o = orte.optJSONObject(i);
                org.json.JSONObject e = o == null ? null : o.optJSONObject("eigenschaften");
                if (e != null && e.optBoolean("zuhause")) {
                    l.add(new Monatsbericht.Zone(o.optDouble("lat"), o.optDouble("lon"), o.optInt("r")));
                }
            }
            return l;
        }
        try {
            for (at.greenchili.surfmate.surveillance.SafeLocation s
                    : at.greenchili.surfmate.surveillance.SafeLocationManager.getInstance().getZones()) {
                l.add(new Monatsbericht.Zone(s.getLatitude(), s.getLongitude(), s.getRadiusMeters()));
            }
        } catch (Throwable ignored) { }
        return l;
    }

    /** Monate mit Fahrten (Monats-Rollups, bis 24 zurück) + laufender Monat, alt → neu. */
    private static JSONArray monate(String jetzt, TimeZone tz) {
        TreeSet<String> s = new TreeSet<>();
        s.add(jetzt);
        try {
            at.greenchili.surfmate.trips.TripAnalyticsManager m =
                    at.greenchili.surfmate.daemon.CameraDaemon.getTripAnalyticsManager();
            at.greenchili.surfmate.trips.TripDatabase db = m == null ? null : m.getDatabase();
            if (db != null) {
                for (at.greenchili.surfmate.trips.MonthlyRollup r : db.getRecentMonthlyRollups(24)) {
                    if (r.tripCount > 0) s.add(String.format(java.util.Locale.ROOT, "%04d-%02d", r.year, r.month));
                }
            }
        } catch (Throwable ignored) { }
        JSONArray a = new JSONArray();
        for (String x : s) a.put(x);
        return a;
    }
}
