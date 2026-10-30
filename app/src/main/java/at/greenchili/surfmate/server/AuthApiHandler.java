package at.greenchili.surfmate.server;

import at.greenchili.surfmate.auth.AuthManager;
import at.greenchili.surfmate.daemon.CameraDaemon;

import org.json.JSONObject;

import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP API handler for authentication endpoints.
 *
 * Endpoints:
 * - GET  /auth/status     - Check auth status (auth required — surfaces deviceId)
 * - POST /auth/token      - Validate device token and get JWT (rate-limited)
 * - POST /auth/logout     - Clear session
 *
 * Rate limiting on /auth/token: 10 attempts per minute per client identity, then
 * 30s lockout. Identity is the X-Forwarded-For value when present (so a tunnel
 * attacker can't share the loopback bucket with the legitimate WebView), falling
 * back to socket address otherwise.
 */
public class AuthApiHandler {

    // Rate-limit constants — small numbers picked for human convenience while
    // making brute-force attempts on a 64-bit token costly.
    private static final int RATE_LIMIT_WINDOW_MS = 60_000;     // 1 minute window
    private static final int RATE_LIMIT_MAX_ATTEMPTS = 10;      // 10 attempts allowed
    private static final long RATE_LIMIT_LOCKOUT_MS = 30_000;   // 30s lockout after exceeded

    private static final ConcurrentHashMap<String, RateLimitBucket> rateLimits = new ConcurrentHashMap<>();

    // Inline GC counter for the rateLimits map. Without this, a publicly
    // tunneled instance accumulates a permanent entry per scanner IP that
    // ever hits /auth/token (typical: 10²–10⁴ unique IPs/week per public
    // endpoint). pruneStaleRateLimits walks the map opportunistically every
    // RATE_LIMIT_GC_EVERY checkRateLimit calls to keep it bounded.
    private static final java.util.concurrent.atomic.AtomicInteger rateLimitOps =
        new java.util.concurrent.atomic.AtomicInteger(0);
    private static final int RATE_LIMIT_GC_EVERY = 64;
    // Stop bucket-keep growth at a hard cap so a burst of fresh IPs faster
    // than the GC cadence can't push the map above this. Far above the
    // legitimate per-tunnel concurrent client count (hundreds) but well
    // below "leak" territory.
    private static final int RATE_LIMIT_MAX_BUCKETS = 1024;

    private static class RateLimitBucket {
        final Deque<Long> attempts = new ArrayDeque<>();
        long lockedUntil = 0L;
    }

    /**
     * Handle auth API requests.
     */
    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        return handle(method, path, body, out, null);
    }

    /**
     * Handle auth API requests with rate-limit identity (X-Forwarded-For or socket).
     */
    public static boolean handle(String method, String path, String body, OutputStream out,
                                  String rateLimitIdentity) throws Exception {
        return handle(method, path, body, out, rateLimitIdentity, null);
    }

    /** Wie oben, mit Authorization-Kopf (für {@code /auth/kopplung/selbst-widerrufen}). */
    public static boolean handle(String method, String path, String body, OutputStream out,
                                  String rateLimitIdentity, String authHeader) throws Exception {
        if (path.equals("/auth/kopplung/selbst-widerrufen") && method.equals("POST")) {
            return handleSelbstWiderruf(authHeader, out);
        }

        // 0.1.20 Web-Sitzung der Handy-App: /auth/web?einmal=<code>[&ziel=<pfad>]
        if ((path.equals("/auth/web") || path.startsWith("/auth/web?")) && method.equals("GET")) {
            return handleWebSitzung(path, out);
        }

        if (path.equals("/auth/status") && method.equals("GET")) {
            return handleStatus(out);
        }

        if (path.equals("/auth/token") && method.equals("POST")) {
            // Rate-limit token validation to slow down brute-force attempts
            // through public tunnels. Identity prefers X-Forwarded-For so a
            // tunnel attacker can't share a bucket with the loopback caller.
            String idForLimit = (rateLimitIdentity != null && !rateLimitIdentity.isEmpty())
                ? rateLimitIdentity : "unknown";
            String rateError = checkRateLimit(idForLimit);
            if (rateError != null) {
                JSONObject resp = new JSONObject();
                resp.put("success", false);
                resp.put("error", rateError);
                HttpResponse.sendJson(out, resp.toString());
                return true;
            }
            return handleTokenValidation(body, out, idForLimit);
        }

        if (path.equals("/auth/kopplung") && method.equals("POST")) {
            String idForLimit = (rateLimitIdentity != null && !rateLimitIdentity.isEmpty())
                ? rateLimitIdentity : "unknown";
            String rateError = checkRateLimit(idForLimit);
            if (rateError != null) {
                JSONObject resp = new JSONObject();
                resp.put("success", false);
                resp.put("error", rateError);
                HttpResponse.sendJson(out, resp.toString());
                return true;
            }
            return handlePairing(body, out, idForLimit);
        }

        // Kopplung per Anfrage (KOPPLUNG_ANFRAGE.md): eigenes Ratenlimit in KopplungsAnfragen.
        if (path.equals("/auth/kopplung/anfrage") && method.equals("POST")) {
            return handleAnfrage(body, out, rateLimitIdentity);
        }
        if (path.startsWith("/auth/kopplung/anfrage/") && method.equals("GET")) {
            return handleAbholung(path.substring("/auth/kopplung/anfrage/".length()), out);
        }

        // 0.1.20 go-e-Token per Handy: Einmal-Kennung aus der Wallbox-Einrichtung (10 min, einmal, ratenbegrenzt)
        if (path.equals("/auth/wallbox-eingabe") && method.equals("POST")) {
            WallboxApiHandler.eingabeVomHandy(body, out);
            return true;
        }

        // 0.1.21 Anmeldung per Telegram-Erlauben (Web-Sitzung ohne Sicherheitsrechte, Stufe B)
        if (path.equals("/auth/telegram/anfrage") && method.equals("POST")) {
            return handleTelegramAnfrage(out, rateLimitIdentity);
        }
        if (path.startsWith("/auth/telegram/abholen") && method.equals("GET")) {
            return handleTelegramAbholen(path, out);
        }

        if (path.equals("/auth/logout") && method.equals("POST")) {
            return handleLogout(out);
        }

        return false;
    }

    /**
     * @return null if request may proceed, error string if rate limited.
     */
    private static String checkRateLimit(String identity) {
        long now = System.currentTimeMillis();
        // Opportunistic GC to bound the map under public-tunnel scanner load.
        // Runs on every Nth invocation so the steady-state cost is negligible.
        if ((rateLimitOps.incrementAndGet() & (RATE_LIMIT_GC_EVERY - 1)) == 0) {
            pruneStaleRateLimits(now);
        }
        RateLimitBucket bucket = rateLimits.computeIfAbsent(identity, k -> new RateLimitBucket());
        synchronized (bucket) {
            if (bucket.lockedUntil > now) {
                long secs = (bucket.lockedUntil - now) / 1000 + 1;
                return Messages.get("errors.rate_limited_locked_for_seconds", secs);
            }
            // Drop attempts outside the window
            long windowStart = now - RATE_LIMIT_WINDOW_MS;
            while (!bucket.attempts.isEmpty() && bucket.attempts.peekFirst() < windowStart) {
                bucket.attempts.pollFirst();
            }
            if (bucket.attempts.size() >= RATE_LIMIT_MAX_ATTEMPTS) {
                bucket.lockedUntil = now + RATE_LIMIT_LOCKOUT_MS;
                bucket.attempts.clear();
                log("Rate limit exceeded for " + identity + " — locked for "
                    + (RATE_LIMIT_LOCKOUT_MS / 1000) + "s");
                return Messages.get("errors.rate_limited_locked_for_seconds", (RATE_LIMIT_LOCKOUT_MS / 1000));
            }
            bucket.attempts.addLast(now);
        }
        return null;
    }

    /**
     * Reset the rate-limit bucket for an identity after a successful login.
     */
    private static void clearRateLimit(String identity) {
        if (identity != null) rateLimits.remove(identity);
    }

    /**
     * Walks the rateLimits map and removes buckets that are no longer
     * useful: lockout has expired AND the attempt window holds no entries.
     * Then enforces RATE_LIMIT_MAX_BUCKETS as a hard ceiling by dropping
     * the oldest-by-attempt bucket if we're over the cap. CHM iteration is
     * weakly consistent and the per-bucket synchronized block in
     * checkRateLimit keeps state-machine consistency, so concurrent
     * iteration here is safe.
     */
    private static void pruneStaleRateLimits(long now) {
        long stalenessCutoff = now - RATE_LIMIT_LOCKOUT_MS - RATE_LIMIT_WINDOW_MS;
        java.util.Iterator<java.util.Map.Entry<String, RateLimitBucket>> it =
            rateLimits.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<String, RateLimitBucket> e = it.next();
            RateLimitBucket b = e.getValue();
            synchronized (b) {
                // Only drop buckets that have a HISTORY (most recent attempt
                // older than stalenessCutoff) or a fully-expired lockout.
                // Critical: do NOT drop a bucket that is empty + lockedUntil=0
                // — that's the freshly-created state from computeIfAbsent
                // before the caller has entered its synchronized block. The
                // caller (checkRateLimit) holds the bucket reference but the
                // map lookup hasn't been synchronized; if we evict here, the
                // caller adds attempts to an orphaned bucket and the next
                // checkRateLimit for the same identity creates a new bucket,
                // silently losing the rate-limit state. Brute-force attackers
                // could time-attack the prune cadence to bypass protection.
                if (b.lockedUntil > 0 && b.lockedUntil < now && b.attempts.isEmpty()) {
                    it.remove();
                } else if (!b.attempts.isEmpty() && b.attempts.peekLast() < stalenessCutoff
                        && b.lockedUntil < now) {
                    it.remove();
                }
            }
        }
        // Hard cap. Under a sustained scanner burst the prune above may not
        // keep up; this enforces a ceiling regardless of bucket recency.
        if (rateLimits.size() > RATE_LIMIT_MAX_BUCKETS) {
            int over = rateLimits.size() - RATE_LIMIT_MAX_BUCKETS;
            java.util.Iterator<java.util.Map.Entry<String, RateLimitBucket>> dropIt =
                rateLimits.entrySet().iterator();
            while (over > 0 && dropIt.hasNext()) {
                dropIt.next();
                dropIt.remove();
                over--;
            }
        }
    }
    
    /**
     * GET /auth/status
     * Returns device info.
     */
    private static boolean handleStatus(OutputStream out) throws Exception {
        AuthManager.AuthState state = AuthManager.getState();
        
        JSONObject response = new JSONObject();
        response.put("status", "ok");
        
        if (state != null) {
            response.put("deviceId", state.deviceId);
        } else {
            response.put("deviceId", "unknown");
        }
        
        HttpResponse.sendJson(out, response.toString());
        return true;
    }
    
    /**
     * POST /auth/token
     * Validates device token and returns JWT session.
     */
    private static boolean handleTokenValidation(String body, OutputStream out, String rateLimitIdentity) throws Exception {
        JSONObject response = new JSONObject();

        try {
            JSONObject request = new JSONObject(body);
            String token = request.optString("token", "");

            boolean valid = AuthManager.validateDeviceToken(token);

            if (valid) {
                // Successful login — wipe attempt counter so the user gets a
                // fresh 10-attempt budget on their next session.
                clearRateLimit(rateLimitIdentity);

                String jwt = AuthManager.generateJwt();
                AuthManager.AuthState state = AuthManager.getState();
                if (jwt == null || state == null) {
                    // Auth state was invalidated between validateDeviceToken
                    // and here (e.g. concurrent regenerateToken). Treat as
                    // a transient failure rather than NPEing on state.deviceId.
                    response.put("success", false);
                    response.put("error", Messages.get("errors.invalid_device_token"));
                    log("Auth state vanished mid-login — asking client to retry");
                } else {
                    response.put("success", true);
                    response.put("jwt", jwt);
                    response.put("deviceId", state.deviceId);
                    response.put("expiresIn", AuthManager.JWT_EXPIRY_SECONDS);

                    log("Token validated for device: " + state.deviceId);
                }
            } else {
                response.put("success", false);
                response.put("error", Messages.get("errors.invalid_device_token"));
                log("Invalid token attempt from " + rateLimitIdentity);
            }

        } catch (Exception e) {
            response.put("success", false);
            response.put("error", Messages.get("errors.invalid_request_with_detail", e.getMessage()));
        }

        HttpResponse.sendJson(out, response.toString());
        return true;
    }
    
    /**
     * POST /auth/logout
     * Logs out the user. Client should clear stored JWT.
     */
    /**
     * POST /auth/kopplung {"code": "…"} – Kopplungs-Code (aus „Gerät koppeln“) gegen einen JWT mit
     * scope "kopplung" tauschen. Ein Login-Code gilt hier nicht, ein Kopplungs-Code nicht an /auth/token.
     */
    private static boolean handlePairing(String body, OutputStream out, String rateLimitIdentity) throws Exception {
        JSONObject response = new JSONObject();
        String code = "";
        String name = "";
        String art = null;
        try {
            JSONObject req = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
            code = req.optString("code", "");
            name = req.optString("name", "");
            // 0.1.18 Handy-Vertrag §1: Art auch beim Code/QR-Weg übernehmen (wie bei der Anfrage); unbekannt = ohne
            art = req.optString("art", "");
        } catch (Exception ignored) {
        }
        long now = System.currentTimeMillis();
        int herkunft = at.greenchili.surfmate.auth.PairingCodes.einloesen(code, now);
        if (herkunft != at.greenchili.surfmate.auth.PairingCodes.UNGUELTIG) {
            clearRateLimit(rateLimitIdentity);
            // Jede Kopplung bekommt einen eigenen Listeneintrag; der JWT trägt dessen id (Widerruf).
            String kid = at.greenchili.surfmate.auth.Kopplungen.get().anlegen(name, art, now);
            String jwt = kid == null ? null
                    : AuthManager.generateJwt(at.greenchili.surfmate.auth.PairingCodes.SCOPE, kid);
            if (jwt != null) {
                response.put("success", true);
                response.put("jwt", jwt);
                response.put("kopplungId", kid);
                // 0.1.20 Brücke (BRUECKE_VERTRAG §2.3): nur bei art=handy und eingeschalteter Brücke
                if (at.greenchili.surfmate.auth.Kopplungen.ART_HANDY.equals(art)) {
                    try {
                        org.json.JSONObject block = at.greenchili.surfmate.bruecke.Bruecke.get().kopplungsBlock();
                        if (block != null) response.put("bruecke", block);
                    } catch (Throwable ignored) { }
                }
                response.put("expiresIn", AuthManager.JWT_EXPIRY_SECONDS);
                log("Kopplung erfolgreich");
                // 0.1.24: Besitzer erfährt jede neue Kopplung (Telegram + Band), „Warst du das nicht? … entfernen“
                try {
                    at.greenchili.surfmate.auth.NeueKopplungMeldung.melden(name, kid);
                } catch (Throwable ignored) { }
                HttpResponse.sendJson(out, response.toString());
                return true;
            }
        }
        response.put("success", false);
        response.put("error", Messages.get("errors.invalid_device_token"));
        HttpResponse.sendJson(out, response.toString());
        return true;
    }

    /**
     * GET /auth/web?einmal=&lt;code&gt; – Einmal-Code (60 s) gegen einen Web-JWT der Kopplung (scope handyweb, 30 min) als
     * Cookie tauschen, Einbettungs-Cookie setzen und auf die Seite weiterleiten. Ungültig: kurze Seite, kein JWT.
     */
    private static boolean handleWebSitzung(String path, OutputStream out) throws Exception {
        String q = path.contains("?") ? path.substring(path.indexOf('?') + 1) : "";
        String code = null, ziel = null;
        for (String teil : q.split("&")) {
            int i = teil.indexOf('=');
            if (i <= 0) continue;
            String n = teil.substring(0, i), v = java.net.URLDecoder.decode(teil.substring(i + 1), "UTF-8");
            if (n.equals("einmal")) code = v;
            else if (n.equals("ziel")) ziel = v;
        }
        long jetzt = System.currentTimeMillis();
        String kid = at.greenchili.surfmate.auth.WebSitzung.get().einloesen(code, jetzt);
        at.greenchili.surfmate.auth.Kopplungen kk = at.greenchili.surfmate.auth.Kopplungen.get();
        String jwt = kid == null || !kk.istHandy(kid) || !kk.hatRecht(kid, at.greenchili.surfmate.auth.Kopplungen.RECHT_WEB)
                ? null
                : AuthManager.generateJwt(at.greenchili.surfmate.auth.WebSitzung.SCOPE, kid,
                        at.greenchili.surfmate.auth.WebSitzung.GUELTIG_JWT_S);
        if (jwt == null) {
            byte[] b = ("<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width\">"
                    + "<p style=\"font:16px sans-serif;padding:24px\">" + Messages.get("errors.web_link_expired") + "</p>")
                    .getBytes("UTF-8");
            out.write(("HTTP/1.1 410 Gone\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\n"
                    + "Referrer-Policy: no-referrer\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
            out.write(b);
            out.flush();
            return true;
        }
        // Cookie nicht HttpOnly: auth.js liest den Token aus document.cookie (sonst schickt requireAuth zur Anmeldung)
        String cookie = "byd_session=" + jwt + "; Path=/; Max-Age=" + at.greenchili.surfmate.auth.WebSitzung.GUELTIG_JWT_S
                + "; SameSite=Strict";
        out.write(("HTTP/1.1 302 Found\r\nLocation: " + at.greenchili.surfmate.auth.WebSitzung.ziel(ziel) + "\r\n"
                + "Set-Cookie: " + cookie + "\r\n"
                + "Set-Cookie: " + at.greenchili.surfmate.auth.WebSitzung.EINBETTUNG_COOKIE + "; Path=/; Max-Age="
                + at.greenchili.surfmate.auth.WebSitzung.GUELTIG_JWT_S + "; SameSite=Strict\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\n"
                + "Content-Length: 0\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
        out.flush();
        log("Web-Sitzung geöffnet");
        return true;
    }

    /** Gültigkeit der Web-Sitzung aus der Telegram-Anmeldung (widerrufbar über die Kopplungsliste). */
    static final int TELEGRAM_SITZUNG_S = 7 * 24 * 3600;

    /** Laufzeit-Instanz der Telegram-Anmeldung (Kopplung Art web + Web-JWT; Meldung an den Telegram-Besitzer). */
    static at.greenchili.surfmate.auth.TelegramAnmeldung telegramAnmeldung() {
        at.greenchili.surfmate.auth.TelegramAnmeldung t = at.greenchili.surfmate.auth.TelegramAnmeldung.get();
        if (t != null) return t;
        synchronized (AuthApiHandler.class) {
            t = at.greenchili.surfmate.auth.TelegramAnmeldung.get();
            if (t == null) {
                t = new at.greenchili.surfmate.auth.TelegramAnmeldung(now -> {
                    String kid = at.greenchili.surfmate.auth.Kopplungen.get().anlegen(
                            Messages.get("telegram_login.geraet_name"), at.greenchili.surfmate.auth.Kopplungen.ART_WEB, now);
                    if (kid == null) return null;
                    return new String[]{kid, AuthManager.generateJwt(at.greenchili.surfmate.auth.WebSitzung.SCOPE, kid, TELEGRAM_SITZUNG_S)};
                }, new at.greenchili.surfmate.auth.TelegramAnmeldung.Melder() {
                    @Override public void neu(String ref, String pruefzahl, String absender) {
                        try {
                            at.greenchili.surfmate.telegram.TelegramNotifier.notifyTelegramAnmeldung(ref, pruefzahl);
                        } catch (Throwable ignored) { }
                    }

                    @Override public void protokoll(String zeile) {
                        log(zeile);
                    }
                });
                at.greenchili.surfmate.auth.TelegramAnmeldung.setzen(t);
            }
            return t;
        }
    }

    /** POST /auth/telegram/anfrage – ohne Login; nur wenn Telegram mit Besitzer eingerichtet ist. */
    private static boolean handleTelegramAnfrage(OutputStream out, String identity) throws Exception {
        if (!at.greenchili.surfmate.telegram.config.UnifiedTelegramConfig.hasOwner()) {
            HttpResponse.sendJson(out, 409, new JSONObject().put("ok", false).put("grund", "kein_telegram").toString());
            return true;
        }
        at.greenchili.surfmate.auth.TelegramAnmeldung.Neu n = telegramAnmeldung().anfrage(identity, System.currentTimeMillis());
        if (n.grund != null) {
            HttpResponse.sendJson(out, "ratenlimit".equals(n.grund) ? 429 : 409,
                    new JSONObject().put("ok", false).put("grund", n.grund).toString());
            return true;
        }
        HttpResponse.sendJson(out, 201, new JSONObject().put("ok", true).put("id", n.abholId)
                .put("pruefzahl", n.pruefzahl).put("gueltigBis", n.gueltigBis).toString());
        return true;
    }

    /** GET /auth/telegram/abholen?id=… – Status; bei „erlaubt“ einmalig die Web-Sitzung als Cookie. */
    private static boolean handleTelegramAbholen(String path, OutputStream out) throws Exception {
        String id = null;
        String q = path.contains("?") ? path.substring(path.indexOf('?') + 1) : "";
        for (String teil : q.split("&")) if (teil.startsWith("id=")) id = teil.substring(3);
        at.greenchili.surfmate.auth.TelegramAnmeldung.Abholung a = telegramAnmeldung().abholen(id, System.currentTimeMillis());
        if (a.status == null) {
            HttpResponse.sendJson(out, 404, new JSONObject().put("ok", false).put("status", "unbekannt").toString());
            return true;
        }
        String status = a.status.name().toLowerCase(java.util.Locale.ROOT);
        if (a.jwt == null) {
            HttpResponse.sendJson(out, a.status == at.greenchili.surfmate.auth.TelegramAnmeldung.Status.ABGELAUFEN ? 410 : 200,
                    new JSONObject().put("ok", true).put("status", status).toString());
            return true;
        }
        // Cookie nicht HttpOnly: auth.js liest den Token aus document.cookie (wie die Web-Sitzung der Handy-App)
        String cookie = "byd_session=" + a.jwt + "; Path=/; Max-Age=" + TELEGRAM_SITZUNG_S + "; SameSite=Strict";
        byte[] b = new JSONObject().put("ok", true).put("status", "erlaubt").toString().getBytes("UTF-8");
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nSet-Cookie: " + cookie
                + "\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nContent-Length: " + b.length
                + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
        out.write(b);
        out.flush();
        log("Web-Sitzung per Telegram geöffnet");
        return true;
    }

    /** POST /auth/kopplung/anfrage {geraet, pruefzahl} – ohne Login, legt eine wartende Anfrage an. */
    private static boolean handleAnfrage(String body, OutputStream out, String identity) throws Exception {
        String geraet = "", pruefzahl = "", art = null;
        try {
            JSONObject req = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
            geraet = req.optString("geraet", "");
            pruefzahl = req.optString("pruefzahl", "");
            art = req.optString("art", "").isEmpty() ? null : req.optString("art");
        } catch (Exception ignored) {
        }
        at.greenchili.surfmate.auth.KopplungsAnfragen.Neu n = at.greenchili.surfmate.auth.KopplungsAnfragen.get()
                .anlegen(geraet, pruefzahl, art, identity, System.currentTimeMillis());
        JSONObject res = new JSONObject();
        if (n.grund != null) {
            res.put("ok", false);
            res.put("grund", n.grund);
            HttpResponse.sendJson(out, "pruefzahl".equals(n.grund) ? 400 : 429, res.toString());
            return true;
        }
        res.put("ok", true);
        res.put("id", n.id);
        res.put("gueltigBis", n.gueltigBis);
        res.put("abfrageAlleMs", 2000);
        log("Kopplungs-Anfrage angelegt");
        HttpResponse.sendJson(out, res.toString());
        return true;
    }

    /** GET /auth/kopplung/anfrage/{id} – Status; JWT nur beim ersten Abruf nach dem Erlauben. */
    private static boolean handleAbholung(String id, OutputStream out) throws Exception {
        int q = id.indexOf('?');
        if (q >= 0) id = id.substring(0, q);
        at.greenchili.surfmate.auth.KopplungsAnfragen.Abholung a = id.matches("^[0-9a-f]{32}$")
                ? at.greenchili.surfmate.auth.KopplungsAnfragen.get().abholen(id, System.currentTimeMillis())
                : null;
        JSONObject res = new JSONObject();
        if (a == null || a.status == null) {
            res.put("status", "unbekannt");
            HttpResponse.sendJson(out, 404, res.toString());
            return true;
        }
        res.put("status", a.status.name().toLowerCase(java.util.Locale.ROOT));
        if (a.status == at.greenchili.surfmate.auth.KopplungsAnfragen.Status.WARTET) res.put("gueltigBis", a.gueltigBis);
        if (a.jwt != null) {
            res.put("status", "erlaubt");
            res.put("jwt", a.jwt);
            res.put("kopplungId", a.kid);
            res.put("expiresIn", AuthManager.JWT_EXPIRY_SECONDS);
            log("Kopplung per Anfrage erfolgreich");
        }
        HttpResponse.sendJson(out, res.toString());
        return true;
    }

    /**
     * POST /auth/kopplung/selbst-widerrufen (HA_VERTRAG.md §2): ein gekoppeltes Gerät widerruft nur die eigene
     * Kopplung (kid im Bearer-JWT). Ungültiges Token → 401 (Ziel schon erreicht). Login-JWT ohne kid → 400,
     * der Login bleibt unberührt (Regel B). Nur der Authorization-Kopf zählt, kein Cookie.
     */
    static boolean handleSelbstWiderruf(String authHeader, OutputStream out) throws Exception {
        String jwt = authHeader != null && authHeader.startsWith("Bearer ") ? authHeader.substring(7).trim() : null;
        JSONObject res = new JSONObject();
        AuthManager.JwtValidation v = jwt == null || jwt.isEmpty() ? null : AuthManager.validateJwt(jwt);
        if (v == null || !v.valid) {
            res.put("ok", false);
            res.put("grund", "token_ungueltig");
            HttpResponse.sendJson(out, 401, res.toString());
            return true;
        }
        if (!at.greenchili.surfmate.auth.PairingCodes.SCOPE.equals(v.scope) || v.kid == null || v.kid.isEmpty()) {
            res.put("ok", false);
            res.put("grund", "keine_kopplung");
            HttpResponse.sendJson(out, 400, res.toString());
            return true;
        }
        int r = at.greenchili.surfmate.auth.Kopplungen.get().widerrufen(v.kid);
        if (r == at.greenchili.surfmate.auth.Kopplungen.UNBEKANNT) {
            res.put("ok", false);
            res.put("grund", "token_ungueltig");
            HttpResponse.sendJson(out, 401, res.toString());
            return true;
        }
        res.put("ok", true);
        if (r == at.greenchili.surfmate.auth.Kopplungen.NUR_IM_SPEICHER) res.put("grund", "nicht_gespeichert");
        log("Kopplung vom Gerät selbst widerrufen");
        HttpResponse.sendJson(out, res.toString());
        return true;
    }

    private static boolean handleLogout(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("message", Messages.get("messages.logged_out"));
        
        HttpResponse.sendJson(out, response.toString());
        return true;
    }
    
    private static void log(String message) {
        CameraDaemon.log("AUTH: " + message);
    }
}
