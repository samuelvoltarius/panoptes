package at.greenchili.surfmate.trips;

import at.greenchili.surfmate.server.HttpResponse;
import at.greenchili.surfmate.server.Messages;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 0.1.28 P9: Fahrtenbuch als Datei – CSV zum Speichern und eine Druckansicht (Drucken / PDF, wie der Monatsbericht).
 *
 * <ul>
 *   <li>{@code GET /api/trips/datei?monat=JJJJ-MM&format=csv|druck&sprache=de|en|fr|it|nl}</li>
 *   <li>Dieselben Spalten wie das EV-Cockpit ({@code fahrtenbuch.py}: eine Wahrheit) und dieselbe Quelle
 *       ({@code GET /api/trips} mit Energie-Ergänzung). Keine Orte, keine Koordinaten.</li>
 *   <li>Die Datei entsteht im Auto und geht nur an den, der sie abruft (Browser am Auto, Panoptes-Web mit Login,
 *       gekoppeltes Handy). Nie an einen Server; nicht das Planer-Postfach {@code fahrtenbuch/}.</li>
 *   <li>CSV: de/fr/it/nl mit „;“ und Dezimalkomma, en mit „,“ und Punkt; UTF-8 mit BOM (Excel).</li>
 * </ul>
 */
public final class FahrtenbuchDatei {

    public static final String PFAD = "/api/trips/datei";
    static final int SEITE = 200, HOECHSTENS = 5000;
    static final String[] KOPF_DE = {"Datum", "Start", "Ende", "km", "Dauer_min", "kWh", "kWh_pro_100km",
            "Ladestand_Start", "Ladestand_Ende", "Kosten", "Waehrung", "Aussentemp_C"};
    static final String[] KOPF_EN = {"date", "start", "end", "km", "duration_min", "kwh", "kwh_per_100km",
            "soc_start", "soc_end", "cost", "currency", "temp_c"};

    private FahrtenbuchDatei() {}

    /** Fahrten eines Zeitraums als JSON-Zusammenfassungen (wie {@code GET /api/trips}). */
    interface Quelle {
        JSONArray fahrten(long vonMs, long bisMs, int limit, int offset) throws Exception;
    }

    /** {von, bis} in ms für {@code JJJJ-MM} in der Zeitzone des Autos; null bei falscher Eingabe. */
    static long[] monat(String monat, ZoneId zone) {
        if (monat == null || !monat.matches("\\d{4}-\\d{2}")) return null;
        int j = Integer.parseInt(monat.substring(0, 4)), m = Integer.parseInt(monat.substring(5));
        if (m < 1 || m > 12 || j < 2000 || j > 2100) return null;
        LocalDate a = LocalDate.of(j, m, 1);
        long von = a.atStartOfDay(zone).toInstant().toEpochMilli();
        long bis = a.plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1;
        return new long[]{von, bis};
    }

    static boolean englisch(String sprache) {
        return "en".equals(sprache);
    }

    /** Eine Zeile (ohne Trenner) wie {@code fahrtenbuch.knapp} + {@code csv} im Cockpit. */
    static String[] zeile(JSONObject t, ZoneId zone, char komma) {
        long start = t.optLong("startTime", 0), ende = t.optLong("endTime", 0);
        ZonedDateTime a = Instant.ofEpochMilli(start).atZone(zone);
        double km = t.optDouble("distanceKm", Double.NaN), kwh = t.optDouble("energyUsedKwh", Double.NaN);
        String verbrauch = !Double.isNaN(km) && !Double.isNaN(kwh) && km > 0.5 ? zahl(kwh / km * 100, 1, komma) : "";
        int dauer = t.optInt("durationSeconds", 0);
        double kosten = t.optDouble("tripCost", Double.NaN);
        return new String[]{
                a.format(DateTimeFormatter.ISO_LOCAL_DATE),
                a.format(DateTimeFormatter.ofPattern("HH:mm")),
                ende > 0 ? Instant.ofEpochMilli(ende).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm")) : "",
                Double.isNaN(km) ? "" : zahl(km, 1, komma),
                dauer > 0 ? String.valueOf(Math.round(dauer / 60.0)) : "",
                Double.isNaN(kwh) ? "" : zahl(kwh, 2, komma),
                verbrauch,
                ladestand(t.optDouble("socStart", Double.NaN), komma),
                ladestand(t.optDouble("socEnd", Double.NaN), komma),
                Double.isNaN(kosten) ? "" : zahl(kosten, 2, komma),
                t.optString("currency", ""),
                t.has("extTempC") ? String.valueOf(t.optInt("extTempC")) : ""};
    }

    /** CSV mit BOM. */
    static byte[] csv(JSONArray fahrten, String sprache, ZoneId zone) {
        boolean en = englisch(sprache);
        char trenn = en ? ',' : ';', komma = en ? '.' : ',';
        StringBuilder sb = new StringBuilder("﻿");
        sb.append(String.join(String.valueOf(trenn), en ? KOPF_EN : KOPF_DE)).append("\r\n");
        for (int i = 0; i < fahrten.length(); i++) {
            JSONObject t = fahrten.optJSONObject(i);
            if (t == null) continue;
            String[] z = zeile(t, zone, komma);
            for (int k = 0; k < z.length; k++) {
                // Trenner, Anführungszeichen und Zeilenumbrüche aus Freitext (Währung) entfernen
                z[k] = z[k].replace(String.valueOf(trenn), " ").replace("\"", "").replace("\r", " ").replace("\n", " ");
            }
            sb.append(String.join(String.valueOf(trenn), z)).append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Druckansicht (eine Seite, Drucken / PDF über den Browser), Texte aus server-i18n. */
    static String druck(JSONArray fahrten, String monat, String sprache, ZoneId zone) {
        char komma = englisch(sprache) ? '.' : ',';
        StringBuilder sb = new StringBuilder();
        String titel = Messages.text("fahrtenbuch_datei.titel", "Logbook {0}", monat);
        sb.append("<!doctype html><html lang=\"").append(esc(sprache)).append("\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>")
                .append(esc(titel)).append("</title><style>")
                .append("body{font:14px system-ui,sans-serif;margin:16px;color:#111;background:#fff}")
                .append("table{border-collapse:collapse;width:100%}th,td{border-bottom:1px solid #ccc;padding:4px 6px;")
                .append("text-align:right;white-space:nowrap}th:first-child,td:first-child{text-align:left}")
                .append("tfoot td{font-weight:600;border-top:2px solid #111}.hinweis{color:#555;margin:8px 0 16px}")
                .append("button{font-size:18px;padding:10px 18px;margin-bottom:12px}@media print{button{display:none}}")
                .append("</style></head><body><h1>").append(esc(titel)).append("</h1><p class=\"hinweis\">")
                .append(esc(Messages.text("fahrtenbuch_datei.hinweis",
                        "Made in your car. The file stays with you. No places, no locations.")))
                .append("</p><button type=\"button\" onclick=\"window.print()\">")
                .append(esc(Messages.text("fahrtenbuch_datei.drucken", "Print / PDF"))).append("</button>");
        if (fahrten.length() == 0) {
            sb.append("<p>").append(esc(Messages.text("fahrtenbuch_datei.leer", "No trips in this month."))).append("</p>");
            return sb.append("</body></html>").toString();
        }
        String[] spalten = {"datum", "start", "ende", "km", "dauer", "kwh", "verbrauch", "soc_start", "soc_ende",
                "kosten", "waehrung", "temp"};
        String[] rueckfall = {"Date", "Start", "End", "km", "Minutes", "kWh", "kWh/100 km", "Battery start %",
                "Battery end %", "Cost", "Currency", "Outside °C"};
        sb.append("<table><thead><tr>");
        for (int i = 0; i < spalten.length; i++) {
            sb.append("<th>").append(esc(Messages.text("fahrtenbuch_datei.spalte." + spalten[i], rueckfall[i]))).append("</th>");
        }
        sb.append("</tr></thead><tbody>");
        double summeKm = 0, summeKwh = 0, summeKosten = 0;
        for (int i = 0; i < fahrten.length(); i++) {
            JSONObject t = fahrten.optJSONObject(i);
            if (t == null) continue;
            sb.append("<tr>");
            for (String z : zeile(t, zone, komma)) sb.append("<td>").append(esc(z)).append("</td>");
            sb.append("</tr>");
            summeKm += Math.max(0, t.optDouble("distanceKm", 0));
            summeKwh += Math.max(0, t.optDouble("energyUsedKwh", 0));
            summeKosten += Math.max(0, t.optDouble("tripCost", 0));
        }
        sb.append("</tbody><tfoot><tr><td>")
                .append(esc(Messages.text("fahrtenbuch_datei.summe", "Total: {0} trips", fahrten.length())))
                .append("</td><td></td><td></td><td>").append(zahl(summeKm, 1, komma)).append("</td><td></td><td>")
                .append(zahl(summeKwh, 2, komma)).append("</td><td>")
                .append(summeKm > 0.5 ? zahl(summeKwh / summeKm * 100, 1, komma) : "")
                .append("</td><td></td><td></td><td>").append(summeKosten > 0 ? zahl(summeKosten, 2, komma) : "")
                .append("</td><td></td><td></td></tr></tfoot></table></body></html>");
        return sb.toString();
    }

    /** Alle Fahrten des Zeitraums, seitenweise, neueste zuerst wie die Liste – für die Datei älteste zuerst. */
    static JSONArray alle(Quelle q, long von, long bis) throws Exception {
        java.util.List<JSONObject> l = new java.util.ArrayList<>();
        for (int offset = 0; offset < HOECHSTENS; offset += SEITE) {
            JSONArray s = q.fahrten(von, bis, SEITE, offset);
            for (int i = 0; i < s.length(); i++) {
                JSONObject t = s.optJSONObject(i);
                if (t != null) l.add(t);
            }
            if (s.length() < SEITE) break;
        }
        l.sort((a, b) -> Long.compare(a.optLong("startTime"), b.optLong("startTime")));
        return new JSONArray(l);
    }

    /** HTTP: {@code GET /api/trips/datei?...}; false = nicht zuständig. */
    public static boolean handle(String method, String path, OutputStream out, Quelle q, ZoneId zone) throws Exception {
        String p = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        if (!p.equals(PFAD)) return false;
        if (!"GET".equals(method)) {
            HttpResponse.sendJson(out, 405, "{\"ok\":false,\"grund\":\"methode\"}");
            return true;
        }
        java.util.Map<String, String> a = abfrage(path);
        String monat = a.getOrDefault("monat", "");
        long[] g = monat(monat, zone);
        if (g == null) {
            HttpResponse.sendJson(out, 400, "{\"ok\":false,\"grund\":\"monat\"}");
            return true;
        }
        if (q == null) {
            HttpResponse.sendJson(out, 503, "{\"ok\":false,\"grund\":\"nicht_bereit\"}");
            return true;
        }
        String sprache = a.getOrDefault("sprache", "de").toLowerCase(Locale.ROOT);
        if (!sprache.matches("de|en|fr|it|nl")) sprache = "de";
        JSONArray f = alle(q, g[0], g[1]);
        if ("druck".equals(a.get("format"))) {
            final String sp = sprache;
            byte[] b = Messages.withLocale(sp, () -> druck(f, monat, sp, zone)).getBytes(StandardCharsets.UTF_8);
            HttpResponse.sendBinaryNoStore(out, "text/html; charset=utf-8", b, null);
            return true;
        }
        byte[] b = csv(f, sprache, zone);
        java.util.Map<String, String> k = new java.util.LinkedHashMap<>();
        k.put("Content-Disposition", "attachment; filename=\"fahrtenbuch-" + monat + ".csv\"");
        HttpResponse.sendBinaryNoStore(out, "text/csv; charset=utf-8", b, k);
        return true;
    }

    /** Laufzeit: Quelle über {@code TripApiHandler} (gleiche Energie-Ergänzung wie die Liste). */
    public static Quelle quelle(TripAnalyticsManager tam) {
        if (tam == null) return null;
        return (von, bis, limit, offset) -> {
            JSONObject r = new TripApiHandler(tam).handleRequest(
                    "/api/trips?from=" + von + "&to=" + bis + "&limit=" + limit + "&offset=" + offset, "GET", null, null);
            JSONArray t = r == null ? null : r.optJSONArray("trips");
            return t == null ? new JSONArray() : t;
        };
    }

    private static java.util.Map<String, String> abfrage(String path) {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        int q = path.indexOf('?');
        if (q < 0) return m;
        for (String kv : path.substring(q + 1).split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0) m.put(kv.substring(0, eq), kv.substring(eq + 1));
        }
        return m;
    }

    static String zahl(double v, int stellen, char komma) {
        String s = new java.math.BigDecimal(v).setScale(stellen, java.math.RoundingMode.HALF_UP).stripTrailingZeros()
                .toPlainString();
        return komma == '.' ? s : s.replace('.', komma);
    }

    private static String ladestand(double v, char komma) {
        if (Double.isNaN(v) || v < 0) return "";
        return zahl(v, 1, komma);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
