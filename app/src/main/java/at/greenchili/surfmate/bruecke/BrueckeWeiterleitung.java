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
 * </ul>
 */
final class BrueckeWeiterleitung {

    /** Prüft einen JWT: Kopplungs-id bei gültigem Kopplungs-JWT, sonst null. */
    interface JwtPruefer {
        String kopplungsId(String jwt);
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
            "authorization", "content-type", "accept", "accept-language", "if-none-match"));
    static final Set<String> KOPF_RAUS = new HashSet<>(Arrays.asList(
            "content-type", "etag", "cache-control", "retry-after"));
    static final int MAX_PFAD = 2048, MAX_ANTWORT = 4 * 1024 * 1024;

    private final JwtPruefer pruefer;
    private final Aufruf aufruf;

    BrueckeWeiterleitung(JwtPruefer pruefer, Aufruf aufruf) {
        this.pruefer = pruefer;
        this.aufruf = aufruf;
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
                if (!KOPF_REIN.contains(kl) || v.indexOf('\r') >= 0 || v.indexOf('\n') >= 0 || v.length() > 8192) continue;
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
        try {
            Antwort a = aufruf.senden(m, p, kopf, body);
            JSONObject r = new JSONObject();
            r.put("id", id);
            r.put("s", a.status);
            JSONObject rk = new JSONObject();
            for (java.util.Map.Entry<String, String> e : a.kopf.entrySet()) {
                if (e.getKey() != null && KOPF_RAUS.contains(e.getKey().toLowerCase(Locale.ROOT))) {
                    rk.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
                }
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
        } catch (Exception e) {
            return fehler(id, 502, "intern");
        }
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
        }, BrueckeWeiterleitung::loopback);
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
                        if (b.size() + n > MAX_ANTWORT) throw new java.io.IOException("Antwort zu groß");
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
