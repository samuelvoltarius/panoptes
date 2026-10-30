package at.greenchili.surfmate.analytics;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * „Anonym mitzählen“ (0.1.17, 27.10.2026): freiwillig, Standard AUS (Frage im Einrichten → „Daten teilen“
 * und in den Einstellungen, Gruppe Datenschutz). Ersetzt den stillgelegten AnalyticsPinger.
 *
 * <p>Gesendet wird höchstens einmal am Tag (UTC) genau {@code {"id":…,"version":…}} – 0.1.24 zusätzlich einmal, wenn
 * sich die Version seit dem letzten Ping geändert hat (sonst meldete ein Auto nach einem Update bis zum nächsten
 * UTC-Tag die alte Version): eine lokal
 * erzeugte Zufallskennung (UUID, nichts vom Auto oder Gerät abgeleitet) und die Versionsnummer. Keine Position,
 * keine Kilometer, keine FIN. Ohne Netz oder bei Fehler passiert nichts: keine Meldung, kein Log, Versuch im nächsten
 * Takt.
 *
 * <p>0.1.31 (30.10.2026): nur mit Zustimmungsstand {@code version >= 2} ({@link #STAND_MODELL}) zusätzlich
 * {@code modell} und {@code dilink}. Alte Zustimmungen gelten dafür NICHT: Wer vorher schon mitzählte, schickt weiter
 * nur id und version, bis er den neuen Text einmal bestätigt (Regel 2).
 *
 * <p>Regel 2: Ausschalten, „neue Kennung“ und „Kennung löschen“ stellen die bisherige Kennung in die Löschliste;
 * der Takt schickt {@code {"id":…,"loeschen":true}}, bis der Server antwortet. 4xx gilt als erledigt (nicht
 * wiederholen), außer 429 (Überlast): dann frühestens im nächsten Stundentakt wieder. 0.1.31: Ausschalten wirkt
 * sofort auf die Kennung ({@link #anZugriff}), Wiedereinschalten bringt immer eine neue.
 *
 * <p>Config-Abschnitt {@code zaehlen}: {@code an}, {@code kennung}, {@code letzterTag}, {@code letzteVersion}, {@code loeschen}
 * (Kennungen mit Komma getrennt), {@code url} (leer = {@link #STANDARD_URL}), {@code version} (Zustimmungsstand),
 * {@code frageErledigt} (einmalige Frage beantwortet), {@code zustimmungAm}.
 */
public final class Mitzaehlen {

    private Mitzaehlen() {}

    public static final String ABSCHNITT = "zaehlen";
    /** Zähler auf dem Projekt-Server (evcount, Vertrag PANOPTES_PING.md der Seiten-Sitzung, 27.10.2026). */
    public static final String STANDARD_URL = "https://byd.serveone.at/panoptes/ping";
    /** So viele ausstehende Löschungen höchstens merken. */
    static final int LOESCHEN_MAX = 5;
    /** Zustimmungsstand, ab dem modell und dilink mitgehen. */
    public static final int STAND_MODELL = 2;

    public interface Speicher {
        /** Abschnitt {@code zaehlen} oder null. */
        JSONObject lese();
        /** Felder in den Abschnitt mischen. */
        void schreibe(JSONObject teil);
    }

    public interface Sender {
        /** HTTP-Status; wirft bei fehlendem Netz. */
        int post(String url, String json) throws Exception;
    }

    /** Was nur mit Zustimmungsstand {@link #STAND_MODELL} mitgeht. */
    public interface Fahrzeug {
        /** Modellname aus der festen Liste oder „unbekannt“. */
        String modell();
        /** 3, 4, 5 oder null, wenn nicht sicher erkannt. */
        Integer dilink();
    }

    /** Neue Zufallskennung (UUID v4). */
    public static String neueKennung() {
        return UUID.randomUUID().toString();
    }

    /** Löschliste plus Kennung (ohne Doppelte, höchstens {@link #LOESCHEN_MAX}). */
    public static String loeschenDazu(String liste, String id) {
        List<String> l = liste(liste);
        if (id != null && !id.trim().isEmpty() && !l.contains(id.trim())) l.add(id.trim());
        while (l.size() > LOESCHEN_MAX) l.remove(0);
        return String.join(",", l);
    }

    static List<String> liste(String s) {
        List<String> l = new ArrayList<>();
        if (s == null) return l;
        for (String x : s.split(",")) if (!x.trim().isEmpty()) l.add(x.trim());
        return l;
    }

    static String tag(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }

    /** Hat der Nutzer dem Text mit Modell und DiLink zugestimmt? */
    public static boolean modellErlaubt(JSONObject z) {
        return z != null && z.optBoolean("an", false) && z.optInt("version", 1) >= STAND_MODELL;
    }

    /** Einmalige Frage noch offen: zählt schon mit, alte Zustimmung, noch nicht beantwortet. */
    public static boolean frageOffen(JSONObject z) {
        return z != null && z.optBoolean("an", false) && z.optInt("version", 1) < STAND_MODELL
                && !z.optBoolean("frageErledigt", false);
    }

    /** Antwort auf die Frage als Config-Teil. Bei „Ja“ geht der nächste Ping sofort mit Modell raus. */
    public static JSONObject antwort(boolean ja, String jetztIso) throws org.json.JSONException {
        JSONObject t = new JSONObject().put("frageErledigt", true);
        if (ja) t.put("version", STAND_MODELL).put("zustimmungAm", jetztIso).put("letzterTag", "");
        return t;
    }

    /**
     * Schalter {@code zaehlen.an}. Einschalten geht nur mit Bestätigung des neuen Texts (Feld.bestaetigung), setzt also
     * den neuen Zustimmungsstand. Ausschalten verwirft die Kennung sofort, auch wenn bis zum nächsten Takt wieder
     * eingeschaltet wird: Die alte Zeile wird gelöscht, nicht weitergeführt.
     */
    public static at.greenchili.surfmate.einstellungen.Feld.Zugriff anZugriff() {
        return new at.greenchili.surfmate.einstellungen.Feld.Zugriff() {
            public Object lese(JSONObject root) {
                JSONObject z = root == null ? null : root.optJSONObject(ABSCHNITT);
                return z == null || !z.has("an") || z.isNull("an") ? null : z.opt("an");
            }
            public java.util.Map<String, java.util.Map<String, Object>> schreibe(Object wert, JSONObject root) {
                JSONObject z = root == null ? null : root.optJSONObject(ABSCHNITT);
                java.util.Map<String, Object> v = new java.util.HashMap<>();
                v.put("an", wert == null ? JSONObject.NULL : wert);
                if (Boolean.TRUE.equals(wert)) {
                    v.put("version", STAND_MODELL);
                    v.put("frageErledigt", true);
                } else if (Boolean.FALSE.equals(wert)) {
                    String alt = z == null ? "" : z.optString("kennung", "");
                    v.put("loeschen", loeschenDazu(z == null ? "" : z.optString("loeschen", ""), alt));
                    v.put("kennung", "");
                    v.put("letzterTag", "");
                    v.put("letzteVersion", "");
                    v.put("version", 1);
                    v.put("frageErledigt", false);
                }
                java.util.Map<String, java.util.Map<String, Object>> out = new java.util.HashMap<>();
                out.put(ABSCHNITT, v);
                return out;
            }
        };
    }

    /** Ein Takt ohne Fahrzeugangaben. */
    public static void takt(long jetztMs, String version, Speicher speicher, Sender sender) {
        takt(jetztMs, version, null, speicher, sender);
    }

    /** Ein Takt (stündlich aus dem Daemon). Wirft nie. */
    public static void takt(long jetztMs, String version, Fahrzeug fahrzeug, Speicher speicher, Sender sender) {
        try {
            JSONObject z = speicher.lese();
            if (z == null) z = new JSONObject();
            String url = z.optString("url", "").trim();
            if (url.isEmpty()) url = STANDARD_URL;
            boolean an = z.optBoolean("an", false);
            String id = z.optString("kennung", "").trim();
            String loeschen = z.optString("loeschen", "");
            JSONObject neu = new JSONObject();

            if (!an && !id.isEmpty()) {
                // Ausgeschaltet: Kennung weg, am Server löschen
                loeschen = loeschenDazu(loeschen, id);
                id = "";
                neu.put("kennung", "").put("letzterTag", "").put("letzteVersion", "").put("loeschen", loeschen);
            }

            List<String> offen = liste(loeschen);
            if (!offen.isEmpty()) {
                List<String> rest = new ArrayList<>(offen);
                for (String alt : offen) {
                    if (ok(sender, url, new JSONObject().put("id", alt).put("loeschen", true))) {
                        rest.remove(alt);
                    }
                }
                if (rest.size() != offen.size()) neu.put("loeschen", String.join(",", rest));
            }

            if (an) {
                if (id.isEmpty()) {
                    id = neueKennung();
                    neu.put("kennung", id);
                }
                String heute = tag(jetztMs);
                String v = version != null && version.matches("[0-9][0-9A-Za-z.-]{0,15}") ? version : "";
                boolean neueVersion = !v.isEmpty() && !v.equals(z.optString("letzteVersion", ""));
                if (!heute.equals(z.optString("letzterTag", "")) || neueVersion) {
                    JSONObject p = new JSONObject().put("id", id);
                    // Server-Format (PANOPTES_PING.md); sonst nur die Kennung
                    if (!v.isEmpty()) p.put("version", v);
                    if (fahrzeug != null && modellErlaubt(z)) {
                        p.put("modell", fahrzeug.modell());
                        Integer d = fahrzeug.dilink();
                        if (d != null) p.put("dilink", (int) d);
                    }
                    if (ok(sender, url, p)) neu.put("letzterTag", heute).put("letzteVersion", v);
                }
            }
            if (neu.length() > 0) speicher.schreibe(neu);
        } catch (Throwable ignored) {
            // still: Zählen ist nie wichtig genug für eine Meldung
        }
    }

    private static boolean ok(Sender s, String url, JSONObject body) {
        try {
            int code = s.post(url, body.toString());
            return code >= 200 && code < 500 && code != 429;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------------- Daemon

    /** Daemon: Config über UnifiedConfigManager, HTTP über den Proxy-Weg wie die übrigen Dienste. */
    public static void taktImDaemon(long jetztMs) {
        takt(jetztMs, at.greenchili.surfmate.BuildConfig.VERSION_NAME, new Fahrzeug() {
            public String modell() {
                return at.greenchili.surfmate.rueckmeldung.AutoAngaben.modell(
                        at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig());
            }
            public Integer dilink() {
                return at.greenchili.surfmate.rueckmeldung.AutoAngaben.dilink();
            }
        }, new Speicher() {
            public JSONObject lese() {
                return at.greenchili.surfmate.config.UnifiedConfigManager.forceReload().optJSONObject(ABSCHNITT);
            }
            public void schreibe(JSONObject teil) {
                at.greenchili.surfmate.config.UnifiedConfigManager.updateSection(ABSCHNITT, teil);
            }
        }, (url, json) -> {
            okhttp3.OkHttpClient c = new okhttp3.OkHttpClient.Builder()
                    .callTimeout(10, TimeUnit.SECONDS)
                    .proxySelector(at.greenchili.surfmate.mqtt.ProxyHelper.chainProxySelector())
                    .build();
            okhttp3.Request req = new okhttp3.Request.Builder().url(url)
                    .post(okhttp3.RequestBody.create(json, okhttp3.MediaType.parse("application/json; charset=utf-8")))
                    .build();
            try (okhttp3.Response r = c.newCall(req).execute()) {
                return r.code();
            }
        });
    }
}
