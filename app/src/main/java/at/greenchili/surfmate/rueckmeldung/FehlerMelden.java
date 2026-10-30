package at.greenchili.surfmate.rueckmeldung;

import at.greenchili.surfmate.util.DaemonStorage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * „Fehler melden“ (0.1.31, 30.10.2026). Jede Meldung ist eine eigene, ausdrückliche Handlung des Nutzers und
 * damit die Einwilligung für genau diese Sendung (Regel 2). Der Körper besteht nur aus {@link #koerper}: keine
 * Kennung, kein Ort, keine FIN, kein Telegram- oder Konto-Name. Der Diagnose-Auszug geht nur mit, wenn der Nutzer ihn
 * in der Vorschau gesehen und den Haken gesetzt hat.
 *
 * <p>Ohne Netz bleibt die Meldung im Auto und {@link #takt} sendet sie nach, sobald wieder Netz da ist. Sonst sendet
 * hier nichts im Hintergrund. Höchstens {@link #TAG_MAX} Meldungen je Tag (UTC).
 */
public final class FehlerMelden {

    private FehlerMelden() {}

    public static final String STANDARD_URL = "https://byd.serveone.at/panoptes/fehler";
    public static final int TEXT_MAX = 1000;
    /** Feld {@code art}: nur dieser Wert, sonst fehlt das Feld (normale Meldung). */
    public static final String ART_TACHO = "tacho_test";
    /** Tacho-Ergebnis: längerer Text, aber nur der fertige Bericht der Seite. */
    static final int TEXT_MAX_TACHO = 4000;
    public static final int TAG_MAX = 5;
    static final int OFFEN_MAX = 5;
    static final int CODES_MAX = 10;
    static final int KOERPER_MAX = 64 * 1024;
    static final int DIAGNOSE_MAX = 48 * 1024;

    static final String DATEI = DaemonStorage.rebase(
            "/data/user_de/0/com.android.shell/surfmate/fehlermeldungen.json");

    private static final Pattern VERSION = Pattern.compile("[0-9][0-9A-Za-z.-]{0,15}");
    private static final Pattern CODE = Pattern.compile("[A-Z0-9]{4}-[A-Z0-9]{4}");

    public enum Stand { GESENDET, GEMERKT, ZUVIEL, UNGUELTIG, ABGELEHNT }

    public static final class Ergebnis {
        public final Stand stand;
        public final String code;
        Ergebnis(Stand stand, String code) { this.stand = stand; this.code = code; }
    }

    public static final class Antwort {
        public final int status;
        public final String body;
        public Antwort(int status, String body) { this.status = status; this.body = body; }
    }

    public interface Speicher {
        /** Ganzer Stand oder null. */
        JSONObject lese();
        void schreibe(JSONObject stand) throws Exception;
    }

    public interface Sender {
        /** Wirft bei fehlendem Netz. */
        Antwort post(String url, String json) throws Exception;
    }

    /** Text gültig? Nicht leer, höchstens {@link #TEXT_MAX} Zeichen. */
    public static boolean textOk(String text) {
        if (text == null) return false;
        String t = text.trim();
        return !t.isEmpty() && t.codePointCount(0, t.length()) <= TEXT_MAX;
    }

    /** Normale Meldung. */
    public static JSONObject koerper(String text, String version, Integer dilink, String modell, String sprache,
                                     String diagnose) throws org.json.JSONException {
        return koerper(text, version, dilink, modell, sprache, diagnose, null);
    }

    /** Der ganze Körper. Mehr Felder gibt es nicht. Null bei ungültigem Text. */
    public static JSONObject koerper(String text, String version, Integer dilink, String modell, String sprache,
                                     String diagnose, String art) throws org.json.JSONException {
        boolean tacho = ART_TACHO.equals(art);
        if (tacho ? (text == null || text.trim().isEmpty() || text.trim().length() > TEXT_MAX_TACHO) : !textOk(text)) return null;
        JSONObject k = new JSONObject().put("text", text.trim())
                .put("modell", modell == null || modell.isEmpty() ? AutoAngaben.UNBEKANNT : modell)
                .put("sprache", sprache == null ? "de" : sprache);
        if (version != null && VERSION.matcher(version).matches()) k.put("version", version);
        if (dilink != null) k.put("dilink", (int) dilink);
        if (tacho) k.put("art", ART_TACHO);
        if (diagnose != null && !diagnose.isEmpty()) {
            k.put("diagnose", kuerzen(diagnose, DIAGNOSE_MAX));
            // Escapes können den Text aufblähen: lieber weniger Diagnose als eine abgelehnte Meldung
            while (bytes(k.toString()) > KOERPER_MAX) {
                String d = k.getString("diagnose");
                k.put("diagnose", kuerzen(d, bytes(d) / 2));
            }
        }
        return k;
    }

    /** Behält das Ende (die neuesten Zeilen), höchstens {@code max} Bytes. */
    static String kuerzen(String s, int max) {
        if (bytes(s) <= max) return s;
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        return new String(b, b.length - max, max, StandardCharsets.UTF_8).replace("�", "");
    }

    private static int bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * Zweiter Durchgang über die schon geschwärzten Log-Zeilen: Was GenAiContext nicht fasst und hier nie rausgehen
     * darf, zum Beispiel Koordinaten in JSON oder als Zahlenpaar, Chat-Nummern, Tailnet-Namen, Kennungen.
     */
    public static String nachschwaerzen(String zeile) {
        String v = zeile;
        v = v.replaceAll("(?i)\\b(lat(?:itude)?|lon(?:gitude)?|lng)[\"']?\\s*[=:]\\s*[\"']?-?\\d+(?:\\.\\d+)?",
                "$1=[GESCHWAERZT]");
        v = v.replaceAll("-?\\b\\d{1,3}\\.\\d{3,}\\s*[,;/ ]\\s*-?\\d{1,3}\\.\\d{3,}\\b", "[GESCHWAERZT]");
        v = v.replaceAll("(?i)\\b(chat[_ ]?id|user[_ ]?id|from[_ ]?id)[\"']?\\s*[=:]\\s*[\"']?-?\\d+", "$1=[GESCHWAERZT]");
        v = v.replaceAll("(?i)\\b[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.ts\\.net\\b", "[GESCHWAERZT]");
        v = v.replaceAll("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b", "[GESCHWAERZT]");
        return v;
    }

    /** Diagnose-Auszug als lesbarer Text (GenAiContext.diagnosticLogs), genau das zeigt die Vorschau. */
    public static String diagnoseText(JSONObject logs) {
        StringBuilder sb = new StringBuilder();
        JSONArray q = logs == null ? null : logs.optJSONArray("sources");
        for (int i = 0; q != null && i < q.length(); i++) {
            JSONObject s = q.optJSONObject(i);
            JSONArray l = s == null ? null : s.optJSONArray("lines");
            if (l == null) continue;
            sb.append('[').append(s.optString("component")).append("]\n");
            for (int j = 0; j < l.length(); j++) sb.append(nachschwaerzen(l.optString(j))).append('\n');
        }
        return sb.toString();
    }

    static String tag(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }

    /** Heute noch möglich. */
    public static int rest(JSONObject stand, long jetztMs) {
        if (stand == null || !tag(jetztMs).equals(stand.optString("tag", ""))) return TAG_MAX;
        return Math.max(0, TAG_MAX - stand.optInt("anzahl", 0));
    }

    /** Vom Nutzer ausgelöst: sofort senden, ohne Netz merken. */
    public static synchronized Ergebnis absenden(long jetztMs, JSONObject koerper, Speicher speicher, Sender sender)
            throws Exception {
        if (koerper == null) return new Ergebnis(Stand.UNGUELTIG, null);
        JSONObject st = speicher.lese();
        if (st == null) st = new JSONObject();
        if (rest(st, jetztMs) <= 0) return new Ergebnis(Stand.ZUVIEL, null);
        Antwort a = versuch(url(st), koerper, sender);
        if (a != null && abgelehnt(a.status)) return new Ergebnis(Stand.ABGELEHNT, null);
        String heute = tag(jetztMs);
        st.put("anzahl", heute.equals(st.optString("tag", "")) ? st.optInt("anzahl", 0) + 1 : 1).put("tag", heute);
        Ergebnis e;
        if (a != null && angekommen(a.status)) {
            String code = code(a);
            merkeCode(st, code, jetztMs);
            e = new Ergebnis(Stand.GESENDET, code);
        } else {
            JSONArray offen = st.optJSONArray("offen");
            if (offen == null) offen = new JSONArray();
            if (offen.length() >= OFFEN_MAX) offen.remove(0);
            offen.put(koerper);
            st.put("offen", offen);
            e = new Ergebnis(Stand.GEMERKT, null);
        }
        speicher.schreibe(st);
        return e;
    }

    /** Gemerkte Meldungen nachsenden. Ohne offene Meldung geht nichts raus. Wirft nie. */
    public static synchronized void takt(long jetztMs, Speicher speicher, Sender sender) {
        try {
            JSONObject st = speicher.lese();
            JSONArray offen = st == null ? null : st.optJSONArray("offen");
            if (offen == null || offen.length() == 0) return;
            JSONArray rest = new JSONArray();
            boolean weiter = true;
            for (int i = 0; i < offen.length(); i++) {
                JSONObject k = offen.getJSONObject(i);
                Antwort a = weiter ? versuch(url(st), k, sender) : null;
                if (a != null && angekommen(a.status)) {
                    merkeCode(st, code(a), jetztMs);
                } else if (a == null || !abgelehnt(a.status)) {
                    weiter = false;     // kein Netz oder Überlast: Reihenfolge halten, später nochmal
                    rest.put(k);
                }
            }
            if (rest.length() != offen.length()) {
                st.put("offen", rest);
                speicher.schreibe(st);
            }
        } catch (Throwable ignored) {
            // still: nächster Takt
        }
    }

    /** Feld {@code url} im Stand: eigener Empfänger (Selbstbetrieb, Emulator-Test), sonst {@link #STANDARD_URL}. */
    private static String url(JSONObject st) {
        String u = st.optString("url", "").trim();
        return u.isEmpty() ? STANDARD_URL : u;
    }

    private static Antwort versuch(String url, JSONObject koerper, Sender sender) {
        try {
            return sender.post(url, koerper.toString());
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean angekommen(int status) {
        return status >= 200 && status < 300;
    }

    /** Kann nie ankommen, also nicht wiederholen. 429 und 5xx sind vorübergehend. */
    private static boolean abgelehnt(int status) {
        return status >= 400 && status < 500 && status != 429;
    }

    private static String code(Antwort a) {
        try {
            String c = new JSONObject(a.body).optString("code", "");
            return CODE.matcher(c).matches() ? c : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static void merkeCode(JSONObject st, String code, long jetztMs) throws org.json.JSONException {
        if (code.isEmpty()) return;
        JSONArray c = st.optJSONArray("codes");
        if (c == null) c = new JSONArray();
        c.put(new JSONObject().put("code", code).put("zeit", jetztMs));
        while (c.length() > CODES_MAX) c.remove(0);
        st.put("codes", c);
    }

    // ---------------------------------------------------------------- Daemon

    public static final Speicher DATEI_SPEICHER = new Speicher() {
        public JSONObject lese() {
            try {
                File f = new File(DATEI);
                return f.isFile() ? new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)) : null;
            } catch (Throwable t) {
                return null;
            }
        }
        public void schreibe(JSONObject stand) throws Exception {
            File f = new File(DATEI);
            File d = f.getParentFile();
            if (d != null && !d.isDirectory()) d.mkdirs();
            File tmp = new File(DATEI + ".tmp");
            Files.write(tmp.toPath(), stand.toString().getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(f)) {
                Files.write(f.toPath(), stand.toString().getBytes(StandardCharsets.UTF_8));
                tmp.delete();
            }
        }
    };

    public static final Sender NETZ = (url, json) -> {
        okhttp3.OkHttpClient c = new okhttp3.OkHttpClient.Builder()
                .callTimeout(15, TimeUnit.SECONDS)
                .proxySelector(at.greenchili.surfmate.mqtt.ProxyHelper.chainProxySelector())
                .build();
        okhttp3.Request req = new okhttp3.Request.Builder().url(url)
                .post(okhttp3.RequestBody.create(json, okhttp3.MediaType.parse("application/json; charset=utf-8")))
                .build();
        try (okhttp3.Response r = c.newCall(req).execute()) {
            return new Antwort(r.code(), r.body() == null ? "" : r.body().string());
        }
    };

    public static void taktImDaemon(long jetztMs) {
        takt(jetztMs, DATEI_SPEICHER, NETZ);
    }
}
