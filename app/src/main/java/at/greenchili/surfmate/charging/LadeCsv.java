package at.greenchili.surfmate.charging;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/**
 * 0.1.43.20 CSV-Export der Ladevorgänge und Ladekosten (Konkurrenz-Check 24.11., Vorbild BYD Trip Stats).
 *
 * <ul>
 *   <li>{@code GET /api/laden/export?liste=ladungen|kosten&von=JJJJ-MM-TT&bis=JJJJ-MM-TT&sprache=de|en|fr|it|nl}</li>
 *   <li>Dieselbe Quelle wie die Ladungsliste ({@link LadeKosten#eintrag}, {@link LadeKosten#ladungen}): keine Koordinaten,
 *       nur der selbst vergebene Ortsname und der Betreiber.</li>
 *   <li>de/fr/it/nl mit „;“ und Dezimalkomma, en mit „,“ und Punkt (wie {@code FahrtenbuchDatei}); UTF-8 mit BOM.</li>
 *   <li>Freitext (Ort, Tarif, Betreiber) wird nach RFC 4180 in Anführungszeichen gesetzt, wenn er Trenner,
 *       Anführungszeichen oder Zeilenumbrüche enthält. Beginnt er mit {@code = + - @}, kommt ein {@code '} davor,
 *       damit Excel keine Formel daraus macht.</li>
 *   <li>Die Datei entsteht im Auto und geht nur an den, der sie abruft. Kein Versand.</li>
 * </ul>
 */
public final class LadeCsv {

    private LadeCsv() {}

    public static final String PFAD = "/api/laden/export";
    public static final String LADUNGEN = "ladungen", KOSTEN = "kosten";
    /** Höchstens zehn Jahre je Datei. */
    static final long MAX_TAGE = 3700;

    static final String[] KOPF_LADUNGEN_DE = {"Datum", "Start", "Ende", "Art", "kWh_Akku", "kWh_abgerechnet",
            "Preis_je_kWh", "Kosten", "Kosten_abgerechnet", "Waehrung", "Preis_Herkunft", "Tarif", "Ort", "Betreiber"};
    static final String[] KOPF_LADUNGEN_EN = {"date", "start", "end", "type", "kwh_battery", "kwh_billed",
            "price_per_kwh", "cost", "cost_billed", "currency", "price_source", "tariff", "place", "operator"};
    static final String[] KOPF_KOSTEN_DE = {"Monat", "Ladungen", "kWh_Akku", "kWh_abgerechnet", "Kosten",
            "Kosten_abgerechnet", "Ohne_Preis", "Vollstaendig"};
    static final String[] KOPF_KOSTEN_EN = {"month", "charges", "kwh_battery", "kwh_billed", "cost", "cost_billed",
            "without_price", "complete"};

    /** {von, bis} in ms (bis = Ende des Tages); null bei falscher Eingabe. Leer = 1. Jänner bis heute. */
    public static long[] bereich(String von, String bis, long jetzt, ZoneId zone) {
        try {
            LocalDate heute = Instant.ofEpochMilli(jetzt).atZone(zone).toLocalDate();
            LocalDate b = bis == null || bis.isEmpty() ? heute : tag(bis);
            LocalDate a = von == null || von.isEmpty() ? b.withDayOfYear(1) : tag(von);
            if (a == null || b == null || a.isAfter(b)) return null;
            if (java.time.temporal.ChronoUnit.DAYS.between(a, b) > MAX_TAGE) return null;
            return new long[] {a.atStartOfDay(zone).toInstant().toEpochMilli(),
                    b.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1};
        } catch (Exception e) {
            return null;
        }
    }

    private static LocalDate tag(String s) {
        if (s == null || !s.matches("\\d{4}-\\d{2}-\\d{2}")) return null;
        LocalDate d = LocalDate.parse(s);
        return d.getYear() < 2000 || d.getYear() > 2100 ? null : d;
    }

    static boolean englisch(String sprache) {
        return "en".equals(sprache);
    }

    /** Ladevorgänge, älteste zuerst. */
    public static byte[] ladungen(JSONArray sitzungen, String sprache, ZoneId zone) throws Exception {
        boolean en = englisch(sprache);
        char trenn = en ? ',' : ';', komma = en ? '.' : ',';
        List<JSONObject> alle = new ArrayList<>();
        for (int i = 0; sitzungen != null && i < sitzungen.length(); i++) {
            JSONObject s = sitzungen.optJSONObject(i);
            if (LadeKosten.zaehlt(s)) alle.add(s);
        }
        alle.sort((x, y) -> Long.compare(x.optLong("startTime"), y.optLong("startTime")));
        StringBuilder sb = kopf(en ? KOPF_LADUNGEN_EN : KOPF_LADUNGEN_DE, trenn);
        DateTimeFormatter hm = DateTimeFormatter.ofPattern("HH:mm");
        for (JSONObject s : alle) {
            JSONObject e = LadeKosten.eintrag(s);
            ZonedDateTime a = Instant.ofEpochMilli(e.optLong("start")).atZone(zone);
            long ende = e.isNull("ende") ? 0 : e.optLong("ende", 0);
            String art = e.isNull("dc") ? "" : (e.optBoolean("dc") ? "DC" : "AC");
            String betreiber = s.isNull("betreiber") ? "" : s.optString("betreiber", "");
            List<String> z = new ArrayList<>();
            z.add(a.format(DateTimeFormatter.ISO_LOCAL_DATE));
            z.add(a.format(hm));
            z.add(ende > 0 ? Instant.ofEpochMilli(ende).atZone(zone).format(hm) : "");
            z.add(art);
            z.add(zahl(e.optDouble("kwh", Double.NaN), 2, komma));
            z.add(zahl(e.optDouble("kwh_abgerechnet", Double.NaN), 2, komma));
            z.add(e.isNull("rate") ? "" : zahl(e.optDouble("rate", Double.NaN), 4, komma));
            z.add(e.isNull("kosten") ? "" : zahl(e.optDouble("kosten", Double.NaN), 2, komma));
            z.add(e.isNull("kosten_abgerechnet") ? "" : zahl(e.optDouble("kosten_abgerechnet", Double.NaN), 2, komma));
            z.add(text(e.optString("waehrung", ""), trenn));
            z.add(text(e.optString("quelle", ""), trenn));
            z.add(text(e.isNull("tarifName") ? "" : e.optString("tarifName", ""), trenn));
            z.add(text(e.isNull("ort") ? "" : e.optString("ort", ""), trenn));
            z.add(text(betreiber, trenn));
            zeile(sb, z, trenn);
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Ladekosten je Monat (Monatssummen wie die Ladungsliste), älteste zuerst. */
    public static byte[] kosten(JSONArray sitzungen, String sprache, ZoneId zone) throws Exception {
        boolean en = englisch(sprache);
        char trenn = en ? ',' : ';', komma = en ? '.' : ',';
        JSONObject l = LadeKosten.ladungen(sitzungen, 0, 1, TimeZone.getTimeZone(zone));
        JSONArray m = l.optJSONArray("monate");
        List<JSONObject> monate = new ArrayList<>();
        for (int i = 0; m != null && i < m.length(); i++) monate.add(m.getJSONObject(i));
        monate.sort((x, y) -> x.optString("monat").compareTo(y.optString("monat")));
        StringBuilder sb = kopf(en ? KOPF_KOSTEN_EN : KOPF_KOSTEN_DE, trenn);
        for (JSONObject x : monate) {
            List<String> z = new ArrayList<>();
            z.add(x.optString("monat"));
            z.add(String.valueOf(x.optInt("ladungen")));
            z.add(zahl(x.optDouble("kwh", Double.NaN), 1, komma));
            z.add(zahl(x.optDouble("kwh_abgerechnet", Double.NaN), 1, komma));
            z.add(zahl(x.optDouble("kosten", Double.NaN), 2, komma));
            z.add(zahl(x.optDouble("kosten_abgerechnet", Double.NaN), 2, komma));
            z.add(String.valueOf(x.optInt("ohne_preis")));
            z.add(x.optBoolean("vollstaendig") ? (en ? "yes" : "ja") : (en ? "no" : "nein"));
            zeile(sb, z, trenn);
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static StringBuilder kopf(String[] k, char trenn) {
        StringBuilder sb = new StringBuilder("﻿");
        sb.append(String.join(String.valueOf(trenn), k)).append("\r\n");
        return sb;
    }

    private static void zeile(StringBuilder sb, List<String> z, char trenn) {
        sb.append(String.join(String.valueOf(trenn), z)).append("\r\n");
    }

    /** Freitext-Zelle: Formel-Schutz, dann Anführungszeichen nach RFC 4180, wenn nötig. */
    static String text(String s, char trenn) {
        if (s == null || s.isEmpty()) return "";
        String v = s;
        char c0 = v.charAt(0);
        if (c0 == '=' || c0 == '+' || c0 == '-' || c0 == '@' || c0 == '\t' || c0 == '\r') v = "'" + v;
        if (v.indexOf(trenn) >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) {
            v = "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    static String zahl(double v, int stellen, char komma) {
        if (!Double.isFinite(v)) return "";
        String s = new java.math.BigDecimal(v).setScale(stellen, java.math.RoundingMode.HALF_UP).stripTrailingZeros()
                .toPlainString();
        return komma == '.' ? s : s.replace('.', komma);
    }
}
