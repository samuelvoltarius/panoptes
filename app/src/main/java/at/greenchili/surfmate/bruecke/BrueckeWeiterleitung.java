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

    private final JwtPruefer pruefer;
    private final Aufruf aufruf;
    private final WebZugang webZugang;

    BrueckeWeiterleitung(JwtPruefer pruefer, Aufruf aufruf) {
        this(pruefer, aufruf, kid -> null);
    }

    BrueckeWeiterleitung(JwtPruefer pruefer, Aufruf aufruf, WebZugang webZugang) {
        this.pruefer = pruefer;
        this.aufruf = aufruf;
        this.webZugang = webZugang;
    }

    /**
     * Einen Bereich {@code bytes=a-b} bzw. {@code bytes=-n} auf höchstens {@link #MAX_BEREICH} kürzen; mehrere Bereiche
     * oder Unlesbares → null (Kopfzeile fällt weg).
     */
    static String begrenzeRange(String v) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\s*bytes=(\\d{0,15})-(\\d{0,15})\\s*$").matcher(v);
        if (!m.matches() || (m.group(1).isEmpty() && m.group(2).isEmpty())) return null;
        if (m.group(1).isEmpty()) return "bytes=-" + Math.min(Long.parseLong(m.group(2)), MAX_BEREICH);
        long a = Long.parseLong(m.group(1));
        long b = m.group(2).isEmpty() ? Long.MAX_VALUE : Long.parseLong(m.group(2));
        if (b < a) return null;
        return "bytes=" + a + "-" + Math.min(b, a + MAX_BEREICH - 1);
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

    /** Bearbeitet eine Innen-Anfrage (JSON) für die Kopplung {@code kid}; liefert immer eine Antwort (JSON). */
    byte[] bearbeiten(byte[] anfrage, String kid) {
        JSONObject req;
        long id = -1;
        try {
            req = new JSONObject(new String(anfrage, StandardCharsets.UTF_8));
            id = req.optLong("id", -1);
        } catch (Exception e) {
            return fehler(id, 400, "json");
        }
        boolean web = "web".equals(req.optString("x", ""));
        String m = req.optString("m", "").toUpperCase(Locale.ROOT);
        String p = req.optString("p", "");
        if (!METHODEN.contains(m)) return fehler(id, 405, "methode");
        if (p.isEmpty() || p.length() > MAX_PFAD || !p.startsWith("/") || p.startsWith("//")
                || !p.matches("[\\x21-\\x7e]+")) return fehler(id, 400, "pfad");
        java.util.Map<String, String> kopf = new java.util.LinkedHashMap<>();
        JSONObject h = req.optJSONObject("h");
        String jwt = null;
        if (h != null) {
            for (Iterator<String> it = h.keys(); it.hasNext(); ) {
                String k = it.next();
                String kl = k.toLowerCase(Locale.ROOT);
                String v = h.optString(k, "");
                if (!(KOPF_REIN.contains(kl) || (web && kl.equals("cookie")))
                        || v.indexOf('\r') >= 0 || v.indexOf('\n') >= 0 || v.length() > 8192) continue;
                if (kl.equals("authorization")) {
                    if (!v.regionMatches(true, 0, "Bearer ", 0, 7)) continue;
                    jwt = v.substring(7).trim();
                }
                kopf.put(kl, v);
            }
        }
        // Kanal gehört genau einer Kopplung: nur deren Kopplungs-JWT gilt
        String jwtKid = jwt == null ? null : pruefer.kopplungsId(jwt);
        if (jwtKid == null || !jwtKid.equals(kid)) return fehler(id, 401, "kopplung");
        if (web) {
            String wj = webZugang.webJwt(kid);
            if (wj == null) return fehler(id, 403, "nicht_erlaubt");
            kopf.put("authorization", "Bearer " + wj);
            if (kopf.containsKey("cookie")) {
                String c = ohneSitzungsCookie(kopf.get("cookie"));
                if (c.isEmpty()) kopf.remove("cookie");
                else kopf.put("cookie", c);
            }
        }
        if (kopf.containsKey("range")) {
            String r = begrenzeRange(kopf.get("range"));
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
                return fehler(id, 400, "body");
            }
        } else {
            body = String.valueOf(b).getBytes(StandardCharsets.UTF_8);
        }
        return weiterreichen(id, m, p, kopf, body, web);
    }

    private byte[] weiterreichen(long id, String m, String p, java.util.Map<String, String> kopf, byte[] body, boolean web) {
        try {
            Antwort a = aufruf.senden(m, p, kopf, body);
            JSONObject r = new JSONObject();
            r.put("id", id);
            r.put("s", a.status);
            JSONObject rk = new JSONObject();
            for (java.util.Map.Entry<String, String> e : a.kopf.entrySet()) {
                String k = e.getKey() == null ? null : e.getKey().toLowerCase(Locale.ROOT);
                if (k == null || !KOPF_RAUS.contains(k) || (!web && NUR_WEB_RAUS.contains(k))) continue;
                // Weiterleitungen nur auf eigene Pfade (kein fremder Host über die Brücke)
                if (k.equals("location") && (!e.getValue().startsWith("/") || e.getValue().startsWith("//"))) continue;
                rk.put(k, e.getValue());
            }
            r.put("h", rk);
            String ct = a.kopf.containsKey("content-type") ? a.kopf.get("content-type") : "";
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
            byte[] antwort = weiterreichen(id, "POST", EINMAL_KOPPLUNG, kopf, neu.toString().getBytes(StandardCharsets.UTF_8), false);
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

    static boolean istText(String contentType) {
        String c = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        return c.isEmpty() || c.startsWith("text/") || c.contains("json") || c.contains("javascript") || c.contains("xml");
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
        });
    }

    /** Loopback an den eigenen HTTP-Server – mit Tunnel-Kennzeichen, damit die Loopback-Ausnahme nicht greift. */
    static Antwort loopback(String methode, String pfad, java.util.Map<String, String> kopf, byte[] body) throws Exception {
        URL u = new URL("http://127.0.0.1:" + at.greenchili.surfmate.daemon.CameraDaemon.HTTP_PORT + pfad);
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        try {
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
            int status = c.getResponseCode();
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
