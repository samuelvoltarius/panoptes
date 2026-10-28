package at.greenchili.surfmate.bruecke;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

/**
 * Innen-Anfrage → eigene Panoptes-API (BRUECKE_VERTRAG §3.4). Die Brücke ist nur ein Rohr:
 * <ul>
 *   <li>Pflicht: {@code Authorization: Bearer <Kopplungs-JWT>} genau der Kopplung, deren Handy-Schlüssel den
 *       Noise-Handshake gemacht hat. Ein Login-JWT oder der JWT einer anderen Kopplung wird hier schon abgewiesen.</li>
 *   <li>Weiter an den eigenen HTTP-Server auf Loopback mit {@code Forwarded: for=panoptes-bruecke}: Damit gilt die
 *       Loopback-Ausnahme NICHT (AuthMiddleware Tier 2), es zählt nur der JWT, also ExternGuard und Handy-Vertrag.</li>
 *   <li>Nur wenige Kopfzeilen werden durchgereicht; keine Cookies, keine Weiterleitungen.</li>
 *   <li>0.1.24 Kanal „web“ (Innen-Feld {@code "x":"web"}, Kern 10 / HANDY_WEB_SITZUNG_VERTRAG): die Handy-App bedient
 *       ihre WebView über den Noise-Kanal. Geprüft wird weiter der Kopplungs-JWT; weitergereicht wird ein frischer
 *       Web-JWT (scope handyweb, Stufe B) derselben Kopplung – nur mit Recht „web“. Zusätzlich Cookies (ohne
 *       {@code byd_session}) hinein und {@code set-cookie}/{@code location} (nur eigene Pfade) hinaus.</li>
 *   <li>Range für alle: höchstens {@link #MAX_BEREICH} je Bereich; Antworten über {@link #MAX_ANTWORT} ohne Range
 *       → 413 {@code zu_gross} mit {@code range:true} (Server-Limit 2 MiB/min je Postfach, Vertrag §4).</li>
 * </ul>
 */
final class BrueckeWeiterleitung {

    /** Prüft einen JWT: Kopplungs-id bei gültigem Kopplungs-JWT, sonst null. */
    interface JwtPruefer {
        String kopplungsId(String jwt);
    }

    /** Web-JWT (scope handyweb) für die Kopplung, oder null, wenn sie keine Web-Sitzung darf. */
    interface WebZugang {
        String webJwt(String kid);
    }

    /** Antwort größer als {@link #MAX_ANTWORT}. */
    static final class ZuGross extends java.io.IOException {
        ZuGross() { super("Antwort zu groß"); }
    }

    /** Eigentlicher HTTP-Aufruf (im Test ersetzt). */
    interface Aufruf {
        Antwort senden(String methode, String pfad, java.util.Map<String, String> kopf, byte[] body) throws Exception;
    }

    static final class Antwort {
        final int status;
        final java.util.Map<String, String> kopf;
        final byte[] body;

        Antwort(int status, java.util.Map<String, String> kopf, byte[] body) {
            this.status = status;
            this.kopf = kopf;
            this.body = body;
        }
    }

    static final Set<String> METHODEN = new HashSet<>(Arrays.asList("GET", "POST", "PUT", "DELETE"));
    /** Durchgereichte Anfrage-Kopfzeilen (klein geschrieben). */
    static final Set<String> KOPF_REIN = new HashSet<>(Arrays.asList(
            "authorization", "content-type", "accept", "accept-language", "if-none-match", "range", "if-range"));
    static final Set<String> KOPF_RAUS = new HashSet<>(Arrays.asList(
            "content-type", "etag", "cache-control", "retry-after", "content-range", "accept-ranges",
            "set-cookie", "location"));
    /** Nur im Kanal „web“ hinaus (die native App braucht sie nicht). */
    static final Set<String> NUR_WEB_RAUS = new HashSet<>(Arrays.asList("set-cookie", "location"));
    static final int MAX_PFAD = 2048, MAX_ANTWORT = 4 * 1024 * 1024, MAX_BEREICH = 1024 * 1024;

    /** Hat die Kopplung dieses Recht? (Kopplungen.hatRecht) */
    interface RechtPruefer {
        boolean hat(String kid, String recht);
    }

    /**
     * 0.1.25 (Koordination 28.10., mit Kern 12 abgesprochen): Kamerabilder, Aufnahmen und Live-Bild gehen über die
     * Brücke nur mit dem Kopplungsrecht „kamera“ (Standard aus) – egal ob Kanal „web“, v1 oder v2.
     */
    static final String[] KAMERA_PFADE = {"/video/", "/thumb/", "/snapshot/", "/api/recordings", "/api/events/",
            "/parking/asset/", "/api/stream", "/api/bs/", "/api/camview/", "/ws", "/h264/"};

    static boolean istKameraPfad(String p) {
        String q = p.contains("?") ? p.substring(0, p.indexOf('?')) : p;
        for (String k : KAMERA_PFADE) {
            if (q.startsWith(k) || (k.endsWith("/") && q.equals(k.substring(0, k.length() - 1)))) return true;
        }
        return false;
    }

    private final JwtPruefer pruefer;
    private final Aufruf aufruf;
    private final WebZugang webZugang;
    private final RechtPruefer recht;

    BrueckeWeiterleitung(JwtPruefer pruefer, Aufruf aufruf) {
        this(pruefer, aufruf, kid -> null);
    }

    BrueckeWeiterleitung(JwtPruefer pruefer, Aufruf aufruf, WebZugang webZugang) {
        this(pruefer, aufruf, webZugang, (kid, r) -> false);
    }

    BrueckeWeiterleitung(JwtPruefer pruefer, Aufruf aufruf, WebZugang webZugang, RechtPruefer recht) {
        this.pruefer = pruefer;
        this.aufruf = aufruf;
        this.webZugang = webZugang;
        this.recht = recht;
    }

    /**
     * Einen Bereich {@code bytes=a-b} bzw. {@code bytes=-n} auf höchstens {@link #MAX_BEREICH} kürzen; mehrere Bereiche
     * oder Unlesbares → null (Kopfzeile fällt weg).
     */
    static String begrenzeRange(String v) {
        return begrenzeRange(v, MAX_BEREICH);
    }

    static String begrenzeRange(String v, long max) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\s*bytes=(\\d{0,15})-(\\d{0,15})\\s*$").matcher(v);
        if (!m.matches() || (m.group(1).isEmpty() && m.group(2).isEmpty())) return null;
        if (m.group(1).isEmpty()) return "bytes=-" + Math.min(Long.parseLong(m.group(2)), max);
        long a = Long.parseLong(m.group(1));
        long b = m.group(2).isEmpty() ? Long.MAX_VALUE : Long.parseLong(m.group(2));
        if (b < a) return null;
        return "bytes=" + a + "-" + Math.min(b, a + max - 1);
    }

    /** Cookie-Kopfzeile ohne {@code byd_session} (im Kanal „web“ zählt nur der vom Auto ausgestellte Web-JWT). */
    static String ohneSitzungsCookie(String cookie) {
        StringBuilder sb = new StringBuilder();
        for (String teil : cookie.split(";")) {
            String t = teil.trim();
            if (t.isEmpty() || t.startsWith("byd_session=")) continue;
            if (sb.length() > 0) sb.append("; ");
            sb.append(t);
        }
        return sb.toString();
    }

    /** Geprüfte Innen-Anfrage (oder {@link #fehler} als fertige Antwort). */
    static final class Vorbereitet {
        long id = -1;
        String m, p;
        java.util.Map<String, String> kopf;
        byte[] body;
        boolean web;
        /** 0.1.26.1: Handy versteht gzip (accept-encoding) – nur im Kanal „web“. */
        boolean gzip;
        byte[] fehler;
    }

    /** Bearbeitet eine Innen-Anfrage (JSON) für die Kopplung {@code kid}; liefert immer eine Antwort (JSON). */
    byte[] bearbeiten(byte[] anfrage, String kid) {
        Vorbereitet v = vorbereiten(anfrage, kid, MAX_BEREICH);
        return v.fehler != null ? v.fehler : weiterreichen(v.id, v.m, v.p, v.kopf, v.body, v.web, v.gzip);
    }

    /** Alle Prüfungen (JWT der eigenen Kopplung, Recht „kamera“, Kanal „web“, Range höchstens {@code maxBereich}). */
    Vorbereitet vorbereiten(byte[] anfrage, String kid, int maxBereich) {
        Vorbereitet v = new Vorbereitet();
        JSONObject req;
        long id = -1;
        try {
            req = new JSONObject(new String(anfrage, StandardCharsets.UTF_8));
            id = req.optLong("id", -1);
        } catch (Exception e) {
            v.fehler = fehler(id, 400, "json");
            return v;
        }
        v.id = id;
        boolean web = "web".equals(req.optString("x", ""));
        String m = req.optString("m", "").toUpperCase(Locale.ROOT);
        String p = req.optString("p", "");
        if (!METHODEN.contains(m)) { v.fehler = fehler(id, 405, "methode"); return v; }
        if (p.isEmpty() || p.length() > MAX_PFAD || !p.startsWith("/") || p.startsWith("//")
                || !p.matches("[\\x21-\\x7e]+")) { v.fehler = fehler(id, 400, "pfad"); return v; }
        java.util.Map<String, String> kopf = new java.util.LinkedHashMap<>();
        JSONObject h = req.optJSONObject("h");
        String jwt = null;
        boolean gzip = false;
        if (h != null) {
            for (Iterator<String> it = h.keys(); it.hasNext(); ) {
                String k = it.next();
                String kl = k.toLowerCase(Locale.ROOT);
                String wert = h.optString(k, "");
                // nicht weiterreichen: das Auto komprimiert selbst (unten), lokal bleibt alles unkomprimiert
                if (kl.equals("accept-encoding")) {
                    gzip = wert.toLowerCase(Locale.ROOT).contains("gzip");
                    continue;
                }
                if (!(KOPF_REIN.contains(kl) || (web && kl.equals("cookie")))
                        || wert.indexOf('\r') >= 0 || wert.indexOf('\n') >= 0 || wert.length() > 8192) continue;
                if (kl.equals("authorization")) {
                    if (!wert.regionMatches(true, 0, "Bearer ", 0, 7)) continue;
                    jwt = wert.substring(7).trim();
                }
                kopf.put(kl, wert);
            }
        }
        // Kanal gehört genau einer Kopplung: nur deren Kopplungs-JWT gilt
        String jwtKid = jwt == null ? null : pruefer.kopplungsId(jwt);
        if (jwtKid == null || !jwtKid.equals(kid)) { v.fehler = fehler(id, 401, "kopplung"); return v; }
        if (istKameraPfad(p) && !recht.hat(kid, at.greenchili.surfmate.auth.Kopplungen.RECHT_KAMERA)) {
            v.fehler = fehler(id, 403, "kamera");
            return v;
        }
        if (web) {
            String wj = webZugang.webJwt(kid);
            if (wj == null) { v.fehler = fehler(id, 403, "nicht_erlaubt"); return v; }
            kopf.put("authorization", "Bearer " + wj);
            if (kopf.containsKey("cookie")) {
                String c = ohneSitzungsCookie(kopf.get("cookie"));
                if (c.isEmpty()) kopf.remove("cookie");
                else kopf.put("cookie", c);
            }
        }
        if (kopf.containsKey("range")) {
            String r = begrenzeRange(kopf.get("range"), maxBereich);
            if (r == null) kopf.remove("range");
            else kopf.put("range", r);
        }
        byte[] body;
        Object b = req.opt("b");
        if (b == null || JSONObject.NULL.equals(b)) {
            body = new byte[0];
        } else if (req.optBoolean("b64", false)) {
            try {
                body = Base64.getDecoder().decode(String.valueOf(b));
            } catch (IllegalArgumentException e) {
                v.fehler = fehler(id, 400, "body");
                return v;
            }
        } else {
            body = String.valueOf(b).getBytes(StandardCharsets.UTF_8);
        }
        v.m = m;
        v.p = p;
        v.kopf = kopf;
        v.body = body;
        v.web = web;
        v.gzip = web && gzip;
        return v;
    }

    private byte[] weiterreichen(long id, String m, String p, java.util.Map<String, String> kopf, byte[] body, boolean web,
                                 boolean gzip) {
        try {
            Antwort a = aufruf.senden(m, p, kopf, body);
            JSONObject r = new JSONObject();
            r.put("id", id);
            r.put("s", a.status);
            JSONObject rk = kopfRaus(a.kopf, web);
            String ct = a.kopf.containsKey("content-type") ? a.kopf.get("content-type") : "";
            if (gzip && komprimierbar(a.status, a.kopf) && a.body.length >= GZIP_AB) {
                rk.put("content-encoding", "gzip");
                r.put("h", rk);
                r.put("b", Base64.getEncoder().encodeToString(gzip(a.body)));
                r.put("b64", true);
                return r.toString().getBytes(StandardCharsets.UTF_8);
            }
            r.put("h", rk);
            if (istText(ct)) {
                r.put("b", new String(a.body, StandardCharsets.UTF_8));
            } else {
                r.put("b", Base64.getEncoder().encodeToString(a.body));
                r.put("b64", true);
            }
            return r.toString().getBytes(StandardCharsets.UTF_8);
        } catch (ZuGross e) {
            try {
                return new JSONObject().put("id", id).put("s", 413).put("h", new JSONObject())
                        .put("b", new JSONObject().put("ok", false).put("grund", "zu_gross").put("range", true)
                                .put("maxBereich", MAX_BEREICH).toString())
                        .toString().getBytes(StandardCharsets.UTF_8);
            } catch (Exception x) {
                return fehler(id, 413, "zu_gross");
            }
        } catch (Exception e) {
            return fehler(id, 502, "intern");
        }
    }

    /** Ergebnis eines Schritts auf dem Einmal-Kanal. */
    static final class EinmalSchritt {
        final byte[] antwort;
        /** Kopplungs-id nach erfolgreichem {@code /auth/kopplung}, sonst null. */
        final String neueKid;
        /** {@code /api/handy/v1/bruecke} hat geklappt: Erstkopplung fertig. */
        final boolean fertig;

        EinmalSchritt(byte[] antwort, String neueKid, boolean fertig) {
            this.antwort = antwort;
            this.neueKid = neueKid;
            this.fertig = fertig;
        }
    }

    static final String EINMAL_KOPPLUNG = "/auth/kopplung", EINMAL_BRUECKE = "/api/handy/v1/bruecke";

    /**
     * 0.1.24 Erstkopplung durch die Brücke (BRUECKE_VERTRAG §2.3): Ein Handy mit noch unbekanntem Schlüssel kam über den
     * Einmal-Zugang des Kopplungs-QR. Erlaubt sind genau zwei Schritte, sonst nichts:
     * <ol>
     *   <li>{@code POST /auth/kopplung {code, name}} – ohne JWT, die Art ist immer „handy“. Hürde bleibt der Einmal-Code.</li>
     *   <li>{@code POST /api/handy/v1/bruecke {h_pub, g_handy}} mit dem JWT aus Schritt 1; {@code h_pub} muss der Schlüssel
     *       sein, der den Handshake gemacht hat.</li>
     * </ol>
     */
    EinmalSchritt einmal(byte[] anfrage, String bisherKid, byte[] hPub) {
        JSONObject req;
        long id = -1;
        try {
            req = new JSONObject(new String(anfrage, StandardCharsets.UTF_8));
            id = req.optLong("id", -1);
        } catch (Exception e) {
            return new EinmalSchritt(fehler(id, 400, "json"), null, false);
        }
        String m = req.optString("m", "").toUpperCase(Locale.ROOT);
        String p = req.optString("p", "");
        if (!"POST".equals(m)) return new EinmalSchritt(fehler(id, 403, "einmal"), null, false);
        JSONObject body;
        try {
            Object b = req.opt("b");
            String t = b == null || JSONObject.NULL.equals(b) ? "{}" : req.optBoolean("b64", false)
                    ? new String(Base64.getDecoder().decode(String.valueOf(b)), StandardCharsets.UTF_8) : String.valueOf(b);
            body = new JSONObject(t);
        } catch (Exception e) {
            return new EinmalSchritt(fehler(id, 400, "body"), null, false);
        }
        if (EINMAL_KOPPLUNG.equals(p) && bisherKid == null) {
            JSONObject neu = new JSONObject();
            try {
                neu.put("code", body.optString("code", "")).put("name", body.optString("name", "")).put("art", "handy");
            } catch (Exception e) {
                return new EinmalSchritt(fehler(id, 400, "body"), null, false);
            }
            java.util.Map<String, String> kopf = new java.util.LinkedHashMap<>();
            kopf.put("content-type", "application/json");
            byte[] antwort = weiterreichen(id, "POST", EINMAL_KOPPLUNG, kopf, neu.toString().getBytes(StandardCharsets.UTF_8), false,
                    false);
            String kid = null;
            try {
                JSONObject r = new JSONObject(new String(antwort, StandardCharsets.UTF_8));
                JSONObject rb = r.optInt("s") == 200 ? new JSONObject(r.optString("b", "{}")) : null;
                if (rb != null && rb.optBoolean("success", false)) kid = rb.optString("kopplungId", null);
            } catch (Exception ignored) { }
            return new EinmalSchritt(antwort, kid, false);
        }
        if (EINMAL_BRUECKE.equals(p) && bisherKid != null) {
            if (hPub == null || !Bruecke.b64(hPub).equals(body.optString("h_pub", ""))) {
                return new EinmalSchritt(fehler(id, 403, "schluessel"), null, false);
            }
            byte[] antwort = bearbeiten(anfrage, bisherKid);
            boolean ok = false;
            try {
                ok = new JSONObject(new String(antwort, StandardCharsets.UTF_8)).optInt("s") == 200;
            } catch (Exception ignored) { }
            return new EinmalSchritt(antwort, null, ok);
        }
        return new EinmalSchritt(fehler(id, 403, "einmal"), null, false);
    }

    static JSONObject kopfRaus(java.util.Map<String, String> kopf, boolean web) throws Exception {
        JSONObject rk = new JSONObject();
        for (java.util.Map.Entry<String, String> e : kopf.entrySet()) {
            String k = e.getKey() == null ? null : e.getKey().toLowerCase(Locale.ROOT);
            if (k == null || !KOPF_RAUS.contains(k) || (!web && NUR_WEB_RAUS.contains(k))) continue;
            // Weiterleitungen nur auf eigene Pfade (kein fremder Host über die Brücke)
            if (k.equals("location") && (!e.getValue().startsWith("/") || e.getValue().startsWith("//"))) continue;
            rk.put(k, e.getValue());
        }
        if (web) {
            // 0.1.26.1: Merker für die Weboberfläche „kommt über die Brücke“ (kein WebSocket unterwegs):
            // live-view zeigt dann den Hinweis auf Live in der App statt eines Verbindungsversuchs zu /ws.
            String alt = rk.optString("set-cookie", "");
            rk.put("set-cookie", alt.isEmpty() ? WEG_COOKIE : alt + "\n" + WEG_COOKIE);
        }
        return rk;
    }

    /** 0.1.26.1: Sitzungs-Cookie (ohne HttpOnly, damit stream.js es lesen kann). */
    static final String WEG_COOKIE = "pn_weg=bruecke; Path=/; SameSite=Strict";

    // ------------------------------------------------------------------ 0.1.25 Innen v2 (BRUECKE_MEDIEN §2.1)

    /** Stück-Typen v2: je Noise-Nachricht genau ein Stück {@code [Typ][id 4 Byte BE][Daten]} (Format Kern 12). */
    static final byte V2_KOPF = 0x10, V2_DATEN = 0x11, V2_ENDE = 0x12, V2_FEHLER = 0x13;
    static final int V2_MAX_DATEN = BrueckeRahmen.MAX_STUECK - 5;
    /** v2 liefert Bereiche bis 2 MiB (v1 bleibt bei {@link #MAX_BEREICH}). */
    static final int MAX_BEREICH_V2 = 2 * 1024 * 1024;
    /** Gegendruck: nicht weiterlesen, solange so viel im Sende-Puffer zum Server wartet. */
    static final long GEGENDRUCK = 256 * 1024;
    static final long GEGENDRUCK_MAX_MS = 30_000;

    /** Offene lokale Antwort zum stückweisen Lesen. */
    static final class Strom implements java.io.Closeable {
        final int status;
        final java.util.Map<String, String> kopf;
        final InputStream daten;
        private final Runnable zu;

        Strom(int status, java.util.Map<String, String> kopf, InputStream daten, Runnable zu) {
            this.status = status;
            this.kopf = kopf;
            this.daten = daten;
            this.zu = zu;
        }

        @Override
        public void close() {
            try {
                if (daten != null) daten.close();
            } catch (Exception ignored) { }
            if (zu != null) zu.run();
        }
    }

    interface StromAufruf {
        Strom oeffnen(String methode, String pfad, java.util.Map<String, String> kopf, byte[] body) throws Exception;
    }

    /** Ziel der Stücke (verschlüsselt und sendet genau eine Noise-Nachricht je Stück). */
    interface StueckAusgang {
        /** false = Kanal ist zu. */
        boolean senden(byte[] stueck) throws Exception;
        /** Bytes, die noch auf dem Weg zum Server warten (Gegendruck). */
        long warteschlange();
    }

    /** Tagesgrenze für Kamera-Daten am Auto (Standard 500 MB). false = Grenze erreicht. */
    interface MedienKonto {
        boolean verbrauchen(long n);
    }

    private StromAufruf stromAufruf = BrueckeWeiterleitung::loopbackStrom;
    private MedienKonto konto = n -> true;

    MedienKonto konto() { return konto; }

    BrueckeWeiterleitung mitStrom(StromAufruf s, MedienKonto k) {
        this.stromAufruf = s;
        this.konto = k;
        return this;
    }

    static byte[] stueck(byte typ, long id, byte[] daten, int laenge) {
        byte[] r = new byte[5 + laenge];
        r[0] = typ;
        int i = (int) id;
        r[1] = (byte) (i >>> 24);
        r[2] = (byte) (i >>> 16);
        r[3] = (byte) (i >>> 8);
        r[4] = (byte) i;
        if (laenge > 0) System.arraycopy(daten, 0, r, 5, laenge);
        return r;
    }

    private static byte[] v2Fehler(long id, int status, String grund) {
        byte[] j;
        try {
            j = new JSONObject().put("id", id).put("s", status).put("grund", grund).toString().getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            j = ("{\"id\":" + id + ",\"s\":" + status + "}").getBytes(StandardCharsets.UTF_8);
        }
        return stueck(V2_FEHLER, id, j, j.length);
    }

    /** Prüf-Fehler (v1-Form {@code {"id","s","b":{"grund"}}}) als v2-Fehlerstück {@code {"id","s","grund"}}. */
    static byte[] v2Fehler(Vorbereitet v) {
        int s = 400;
        String grund = "fehler";
        try {
            JSONObject f = new JSONObject(new String(v.fehler, StandardCharsets.UTF_8));
            s = f.optInt("s", 400);
            grund = new JSONObject(f.optString("b", "{}")).optString("grund", grund);
        } catch (Exception ignored) { }
        return v2Fehler(v.id, s, grund);
    }

    /** Ist die Innen-Anfrage v2 ({@code "v":2})? */
    static boolean istV2(byte[] anfrage) {
        try {
            return new JSONObject(new String(anfrage, StandardCharsets.UTF_8)).optInt("v", 1) == 2;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * v2-Anfrage stückweise beantworten: Kopf (0x10), Daten (0x11, je höchstens {@link #V2_MAX_DATEN} Byte, ohne Base64,
     * nie die ganze Antwort im Speicher), Ende (0x12) oder Fehler (0x13). Kamera-Daten zählen gegen die Tagesgrenze
     * des Autos; auf einem Medien-Kanal sind nur Kamera-Pfade erlaubt. Abbruch über {@code abgebrochen}.
     */
    void strom(byte[] anfrage, String kid, boolean medienKanal, StueckAusgang aus, java.util.function.LongPredicate abgebrochen) {
        Vorbereitet v = vorbereiten(anfrage, kid, MAX_BEREICH_V2);
        try {
            if (v.fehler != null) {
                aus.senden(v2Fehler(v));
                return;
            }
            boolean kamera = istKameraPfad(v.p);
            if (medienKanal && !kamera) {
                aus.senden(v2Fehler(v.id, 403, "medien_kanal"));
                return;
            }
            if (kamera && !konto.verbrauchen(0)) {
                aus.senden(v2Fehler(v.id, 429, "medien_tageslimit"));
                return;
            }
            try (Strom st = stromAufruf.oeffnen(v.m, v.p, v.kopf, v.body);
                 InputStream daten = v.gzip && !kamera && st.daten != null && komprimierbar(st.status, st.kopf)
                         && laenge(st.kopf) >= GZIP_AB ? new GzipStrom(st.daten) : st.daten) {
                JSONObject hk = kopfRaus(st.kopf, v.web);
                if (daten instanceof GzipStrom) hk.put("content-encoding", "gzip");
                byte[] k = new JSONObject().put("id", v.id).put("s", st.status).put("h", hk)
                        .toString().getBytes(StandardCharsets.UTF_8);
                if (!aus.senden(stueck(V2_KOPF, v.id, k, k.length))) return;
                byte[] buf = new byte[V2_MAX_DATEN];
                while (daten != null) {
                    int n = 0, r;
                    while (n < buf.length && (r = daten.read(buf, n, buf.length - n)) > 0) n += r;
                    if (n == 0) break;
                    if (abgebrochen.test(v.id)) {
                        aus.senden(v2Fehler(v.id, 499, "abgebrochen"));
                        return;
                    }
                    if (kamera && !konto.verbrauchen(n)) {
                        aus.senden(v2Fehler(v.id, 429, "medien_tageslimit"));
                        return;
                    }
                    long warten = System.currentTimeMillis() + GEGENDRUCK_MAX_MS;
                    while (aus.warteschlange() > GEGENDRUCK) {
                        if (System.currentTimeMillis() > warten || abgebrochen.test(v.id)) {
                            aus.senden(v2Fehler(v.id, 504, "gegenstelle_langsam"));
                            return;
                        }
                        Thread.sleep(20);
                    }
                    if (!aus.senden(stueck(V2_DATEN, v.id, buf, n))) return;
                    if (n < buf.length) break;
                }
                aus.senden(stueck(V2_ENDE, v.id, null, 0));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            try {
                aus.senden(v2Fehler(v.id, 502, "intern"));
            } catch (Exception ignored) { }
        }
    }

    static boolean istText(String contentType) {
        String c = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        return c.isEmpty() || c.startsWith("text/") || c.contains("json") || c.contains("javascript") || c.contains("xml");
    }

    // ------------------------------------------------------------------ 0.1.26.1 gzip im Kanal „web“

    /**
     * Ab dieser Größe komprimiert das Auto Text für den Kanal „web“ (Seiten laden über die Brücke sonst ~2 MB je Aufruf,
     * Server-Grenze je Postfach). Darunter lohnt der gzip-Kopf nicht.
     */
    static final int GZIP_AB = 1024;

    /** Nur ganze, erfolgreiche Text-Antworten; nie Bereiche, nie Ereignis-Ströme (SSE würde gepuffert). */
    static boolean komprimierbar(int status, java.util.Map<String, String> kopf) {
        String ct = kopf.get("content-type");
        if (status != 200 || ct == null || ct.isEmpty() || !istText(ct)) return false;
        if (ct.toLowerCase(Locale.ROOT).contains("event-stream")) return false;
        return !kopf.containsKey("content-range") && !kopf.containsKey("content-encoding");
    }

    /** content-length der lokalen Antwort, unbekannt = groß genug. */
    static long laenge(java.util.Map<String, String> kopf) {
        try {
            return Long.parseLong(kopf.get("content-length").trim());
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }

    static byte[] gzip(byte[] d) throws java.io.IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(d.length / 3 + 64);
        try (java.util.zip.GZIPOutputStream z = new java.util.zip.GZIPOutputStream(b)) {
            z.write(d);
        }
        return b.toByteArray();
    }

    /** gzip beim Lesen (v2 stückweise, nie die ganze Antwort im Speicher): Kopf, Deflate-Daten, CRC32 + Länge. */
    static final class GzipStrom extends InputStream {
        private final java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        private final java.util.zip.Deflater deflater = new java.util.zip.Deflater(6, true);
        private final InputStream roh;
        private final java.util.zip.DeflaterInputStream def;
        private final byte[] kopf = {0x1f, (byte) 0x8b, 8, 0, 0, 0, 0, 0, 0, (byte) 0xff};
        private int kopfPos;
        private byte[] schluss;
        private int schlussPos;

        GzipStrom(InputStream quelle) {
            roh = quelle;
            def = new java.util.zip.DeflaterInputStream(new java.util.zip.CheckedInputStream(quelle, crc), deflater);
        }

        @Override
        public int read() throws java.io.IOException {
            byte[] e = new byte[1];
            int n;
            while ((n = read(e, 0, 1)) == 0) { }
            return n < 0 ? -1 : e[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws java.io.IOException {
            if (len == 0) return 0;
            if (kopfPos < kopf.length) {
                int n = Math.min(len, kopf.length - kopfPos);
                System.arraycopy(kopf, kopfPos, b, off, n);
                kopfPos += n;
                return n;
            }
            if (schluss == null) {
                int n = def.read(b, off, len);
                if (n > 0) return n;
                long c = crc.getValue(), l = deflater.getBytesRead();
                schluss = new byte[8];
                for (int i = 0; i < 4; i++) {
                    schluss[i] = (byte) (c >>> (8 * i));
                    schluss[4 + i] = (byte) (l >>> (8 * i));
                }
            }
            if (schlussPos >= schluss.length) return -1;
            int n = Math.min(len, schluss.length - schlussPos);
            System.arraycopy(schluss, schlussPos, b, off, n);
            schlussPos += n;
            return n;
        }

        @Override
        public void close() throws java.io.IOException {
            deflater.end();
            roh.close();
        }
    }

    static byte[] fehler(long id, int status, String grund) {
        try {
            return new JSONObject().put("id", id).put("s", status).put("h", new JSONObject())
                    .put("b", new JSONObject().put("ok", false).put("grund", grund).toString())
                    .toString().getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return ("{\"id\":" + id + ",\"s\":" + status + "}").getBytes(StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------------ Laufzeit

    static BrueckeWeiterleitung imDaemon() {
        return new BrueckeWeiterleitung(jwt -> {
            at.greenchili.surfmate.auth.AuthManager.JwtValidation v = at.greenchili.surfmate.auth.AuthManager.validateJwt(jwt);
            return v != null && v.valid && at.greenchili.surfmate.auth.PairingCodes.SCOPE.equals(v.scope) ? v.kid : null;
        }, BrueckeWeiterleitung::loopback, kid -> {
            // wie GET /auth/web: nur Handy mit Recht „web“ (Stufe B), kurz gültig – je Anfrage neu
            at.greenchili.surfmate.auth.Kopplungen kk = at.greenchili.surfmate.auth.Kopplungen.get();
            return kk.istHandy(kid) && kk.hatRecht(kid, at.greenchili.surfmate.auth.Kopplungen.RECHT_WEB)
                    ? at.greenchili.surfmate.auth.AuthManager.generateJwt(at.greenchili.surfmate.auth.WebSitzung.SCOPE, kid, 120)
                    : null;
        }, (kid, r) -> at.greenchili.surfmate.auth.Kopplungen.get().hatRecht(kid, r))
                .mitStrom(BrueckeWeiterleitung::loopbackStrom, n -> Bruecke.get().medienVerbrauchen(n, System.currentTimeMillis()));
    }

    /** Loopback an den eigenen HTTP-Server – mit Tunnel-Kennzeichen, damit die Loopback-Ausnahme nicht greift. */
    /** v2: lokale Antwort offen lassen und stückweise lesen (nichts gesammelt, keine 4-MiB-Grenze). */
    static Strom loopbackStrom(String methode, String pfad, java.util.Map<String, String> kopf, byte[] body) throws Exception {
        HttpURLConnection c = verbinde(methode, pfad, kopf, body);
        try {
            int status = c.getResponseCode();
            java.util.Map<String, String> rk = kopfVon(c);
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            return new Strom(status, rk, in, c::disconnect);
        } catch (Exception e) {
            c.disconnect();
            throw e;
        }
    }

    private static HttpURLConnection verbinde(String methode, String pfad, java.util.Map<String, String> kopf, byte[] body)
            throws Exception {
        URL u = new URL("http://127.0.0.1:" + at.greenchili.surfmate.daemon.CameraDaemon.HTTP_PORT + pfad);
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setInstanceFollowRedirects(false);
        c.setUseCaches(false);
        c.setConnectTimeout(5000);
        c.setReadTimeout(60000);
        c.setRequestMethod(methode);
        for (java.util.Map.Entry<String, String> e : kopf.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
        c.setRequestProperty("Forwarded", "for=panoptes-bruecke");
        if (body.length > 0 || "POST".equals(methode) || "PUT".equals(methode)) {
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream o = c.getOutputStream()) {
                o.write(body);
            }
        }
        return c;
    }

    private static java.util.Map<String, String> kopfVon(HttpURLConnection c) {
        java.util.Map<String, String> rk = new java.util.HashMap<>();
        for (String k : KOPF_RAUS) {
            if (k.equals("set-cookie")) {
                // mehrere Set-Cookie: zeilenweise (die App setzt jede Zeile einzeln)
                java.util.List<String> alle = null;
                for (java.util.Map.Entry<String, java.util.List<String>> e : c.getHeaderFields().entrySet()) {
                    if (e.getKey() != null && e.getKey().equalsIgnoreCase("set-cookie")) alle = e.getValue();
                }
                if (alle != null && !alle.isEmpty()) rk.put(k, String.join("\n", alle));
                continue;
            }
            String v = c.getHeaderField(k);
            if (v != null) rk.put(k, v);
        }
        // nur für die gzip-Entscheidung (komprimierbar/laenge), gehen nicht hinaus (kopfRaus filtert)
        for (String k : new String[]{"content-length", "content-encoding"}) {
            String v = c.getHeaderField(k);
            if (v != null) rk.put(k, v);
        }
        return rk;
    }

    static Antwort loopback(String methode, String pfad, java.util.Map<String, String> kopf, byte[] body) throws Exception {
        HttpURLConnection c = verbinde(methode, pfad, kopf, body);
        try {
            int status = c.getResponseCode();
            java.util.Map<String, String> rk = kopfVon(c);
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            if (in != null) {
                try (InputStream s = in) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = s.read(buf)) > 0) {
                        if (b.size() + n > MAX_ANTWORT) throw new ZuGross();
                        b.write(buf, 0, n);
                    }
                }
            }
            return new Antwort(status, rk, b.toByteArray());
        } finally {
            c.disconnect();
        }
    }
}
