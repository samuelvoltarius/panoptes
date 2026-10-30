package at.greenchili.surfmate.server;

import at.greenchili.surfmate.notifications.NotificationEvent;

import org.json.JSONObject;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 0.1.30 Kern Sicherheit: genau EIN Standbild je Wächter-Ereignis, z. B. für Xaventra.
 *
 * <p>{@code GET /api/waechter/standbild/<ereignisId>} → {@code image/jpeg} (das Hero-Bild des Ereignisses).
 * Die {@code ereignisId} (32 hex) steht im SSE-Ereignis {@code ereignis} von {@code /api/ha/v1/ereignisse}. Sie ist
 * aus der Meldungs-Kennung des Ereignisses abgeleitet (gleich für Start- und Endmeldung) und verrät weder Dateinamen
 * noch Ort.
 *
 * <p>Grenzen: nur Hero-JPEG (nie MP4, kein Dekodieren, nie Unfall-/Ereignis-Clips), höchstens {@link #MAX_ALTER_MS}
 * alt, nur die letzten {@link #MAX_EINTRAEGE} Ereignisse, je Kopplung höchstens {@link #MAX_JE_MINUTE} Abrufe pro
 * Minute. Recht {@code standbild} (oder {@code kamera}) prüft {@code HttpServer} über {@code ExternGuard}.
 *
 * <p>Antworten: 200 JPEG · 202 {@code noch_nicht} (Bild wird noch geschrieben, {@code Retry-After}) · 404
 * {@code nicht_gefunden} · 410 {@code zu_alt} · 429 {@code zu_oft}.
 */
public final class WaechterStandbild {

    static final long MAX_ALTER_MS = 15 * 60 * 1000L;
    static final int MAX_EINTRAEGE = 64;
    static final int MAX_JE_MINUTE = 10;

    private static final class Eintrag {
        final long zeit;
        volatile String bild;   // Hero-JPEG-Name oder null (noch nicht geschrieben)

        Eintrag(long zeit) {
            this.zeit = zeit;
        }
    }

    private static final Map<String, Eintrag> EREIGNISSE = new LinkedHashMap<String, Eintrag>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Eintrag> eldest) {
            return size() > MAX_EINTRAEGE;
        }
    };
    private static final Map<String, Deque<Long>> ABRUFE = new HashMap<>();

    private WaechterStandbild() {}

    // ---------------------------------------------------------------- rein

    /** 32 hex aus der Meldungs-Kennung ({@code motion:<datei>}); null ohne Kennung. */
    public static String ereignisId(String tag) {
        if (tag == null || tag.isEmpty()) return null;
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(
                    ("panoptes-ereignis|" + tag).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) sb.append(String.format("%02x", h[i]));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Hero-JPEG-Name aus {@code data.snapshot} ({@code /thumb/<name>?t=…}); MP4-Namen → gleichnamiges JPEG. */
    static String bildAusSnapshot(String snapshot) {
        if (snapshot == null || !snapshot.startsWith("/thumb/")) return null;
        String n = snapshot.substring("/thumb/".length());
        int q = n.indexOf('?');
        if (q >= 0) n = n.substring(0, q);
        try {
            n = java.net.URLDecoder.decode(n, "UTF-8");
        } catch (Exception e) {
            return null;
        }
        if (n.isEmpty() || n.contains("/") || n.contains("..") || n.contains("\\")) return null;
        String klein = n.toLowerCase(java.util.Locale.US);
        if (klein.endsWith(".mp4")) n = n.substring(0, n.length() - 4) + ".jpg";
        return n.toLowerCase(java.util.Locale.US).endsWith(".jpg") ? n : null;
    }

    static boolean istWaechterKategorie(String kategorie) {
        return kategorie != null
                && (kategorie.startsWith("surveillance.motion") || kategorie.startsWith("surveillance.proximity"));
    }

    // ---------------------------------------------------------------- Ereignisse merken

    /**
     * Vom NotificationBus (über {@code HaDienst}) für jede Wächter-Meldung. Liefert die ereignisId (auch wenn das
     * Bild noch fehlt) oder null, wenn es keine Wächter-Meldung ist.
     */
    public static String merke(NotificationEvent e) {
        if (e == null || !istWaechterKategorie(e.category)) return null;
        String id = ereignisId(e.tag);
        if (id == null) return null;
        String bild = e.data == null ? null : bildAusSnapshot(e.data.optString("snapshot", null));
        synchronized (EREIGNISSE) {
            Eintrag alt = EREIGNISSE.get(id);
            if (alt == null) {
                alt = new Eintrag(e.timestamp > 0 ? e.timestamp : System.currentTimeMillis());
                EREIGNISSE.put(id, alt);
            }
            if (bild != null) alt.bild = bild;
        }
        return id;
    }

    /** Für Tests. */
    static void leeren() {
        synchronized (EREIGNISSE) {
            EREIGNISSE.clear();
        }
        synchronized (ABRUFE) {
            ABRUFE.clear();
        }
    }

    /** Stand eines Ereignisses: ok (Bildname), noch_nicht, zu_alt, nicht_gefunden. */
    static String[] stand(String id, long now) {
        Eintrag e;
        synchronized (EREIGNISSE) {
            e = EREIGNISSE.get(id);
        }
        if (e == null) return new String[] {"nicht_gefunden", null};
        if (now - e.zeit > MAX_ALTER_MS) return new String[] {"zu_alt", null};
        if (e.bild == null) return new String[] {"noch_nicht", null};
        return new String[] {"ok", e.bild};
    }

    /** Höchstens {@link #MAX_JE_MINUTE} Abrufe je Kopplung (bzw. lokal) in 60 s. */
    static boolean abrufErlaubt(String wer, long now) {
        String k = wer == null ? "lokal" : wer;
        synchronized (ABRUFE) {
            Deque<Long> d = ABRUFE.computeIfAbsent(k, x -> new ArrayDeque<>());
            while (!d.isEmpty() && now - d.peekFirst() > 60_000L) d.pollFirst();
            if (d.size() >= MAX_JE_MINUTE) return false;
            d.addLast(now);
            return true;
        }
    }

    // ---------------------------------------------------------------- HTTP

    public static boolean handle(String method, String path, OutputStream out) throws Exception {
        String p = path;
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        if (!p.startsWith(ExternGuard.STANDBILD_PFAD)) return false;
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            HttpResponse.sendJson(out, 405, fehler("nur_lesen").toString());
            return true;
        }
        String id = p.substring(ExternGuard.STANDBILD_PFAD.length());
        if (!id.matches("[0-9a-f]{32}")) {
            HttpResponse.sendJson(out, 404, fehler("nicht_gefunden").toString());
            return true;
        }
        long now = System.currentTimeMillis();
        if (!abrufErlaubt(AuthMiddleware.requestKopplungsId(), now)) {
            HttpResponse.sendJson(out, 429, fehler("zu_oft").toString());
            return true;
        }
        String[] s = stand(id, now);
        switch (s[0]) {
            case "zu_alt":
                HttpResponse.sendJson(out, 410, fehler("zu_alt").toString());
                return true;
            case "noch_nicht":
                HttpResponse.sendJson(out, 202, fehler("noch_nicht").put("erneutInSekunden", 3).toString());
                return true;
            case "ok":
                File f = RecordingsApiHandler.standbildDatei(s[1]);
                if (f == null) {
                    HttpResponse.sendJson(out, 202, fehler("noch_nicht").put("erneutInSekunden", 3).toString());
                    return true;
                }
                HttpResponse.sendImage(out, f, "image/jpeg");
                return true;
            default:
                HttpResponse.sendJson(out, 404, fehler("nicht_gefunden").toString());
                return true;
        }
    }

    private static JSONObject fehler(String grund) throws Exception {
        return new JSONObject().put("ok", false).put("grund", grund);
    }
}
