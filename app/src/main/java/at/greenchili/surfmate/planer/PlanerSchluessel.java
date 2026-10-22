package at.greenchili.surfmate.planer;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Welcher Schlüssel geht zum Planer? Bevorzugt der Geräte-Schlüssel aus dem Geräte-Fluss
 * ({@code planner.geraeteSchluessel}, 64 hex, verschlüsselt, Header {@code X-Geraete-Token},
 * Rechte „auto“), sonst der bisherige Mitglieds-Schlüssel ({@code planner.memberToken}, 32 hex,
 * {@code X-Member-Token}). Nie loggen, nie in URLs.
 */
public final class PlanerSchluessel {

    private PlanerSchluessel() {}

    public static final String BASIS = "https://byd.serveone.at/ev/api/";
    public static final String KOPF_GERAET = "X-Geraete-Token";
    public static final String KOPF_MITGLIED = "X-Member-Token";

    /** Entschlüsselung (im Daemon CredentialCipher, im Test eine Funktion). */
    public interface Entschluessler {
        String klar(String gespeichert);
    }

    /** {Header, Wert} oder null, wenn kein gültiger Schlüssel da ist. */
    public static String[] kopf(JSONObject planner, Entschluessler e) {
        if (planner == null) return null;
        String g = planner.optString("geraeteSchluessel", "");
        if (!g.isEmpty()) {
            String klar = null;
            try { klar = e.klar(g); } catch (Throwable ignored) { }
            if (klar != null && klar.matches("^[0-9a-f]{64}$")) return new String[] {KOPF_GERAET, klar};
        }
        String m = planner.optString("memberToken", "").trim();
        if (m.matches("^[0-9a-fA-F]{32}$")) return new String[] {KOPF_MITGLIED, m};
        return null;
    }

    /** Laufzeit: aus der Config, mit CredentialCipher. */
    public static String[] kopf() {
        try {
            JSONObject root = at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig();
            return kopf(root == null ? null : root.optJSONObject("planner"),
                    at.greenchili.surfmate.byd.cloud.crypto.CredentialCipher::decrypt);
        } catch (Throwable t) {
            return null;
        }
    }

    /** POST an den Planer ohne Schlüssel (geraet/start, geraet/abholen). Netzfehler → null. */
    static PlanerVerbindung.Antwort post(String op, JSONObject body) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(BASIS + op).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(5_000);
            c.setReadTimeout(8_000);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);
            try (OutputStream o = c.getOutputStream()) {
                o.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            JSONObject j = new JSONObject();
            if (in != null) {
                try (InputStream i = in) {
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n, total = 0;
                    while ((n = i.read(buf)) > 0 && total < 64_000) { b.write(buf, 0, n); total += n; }
                    try { j = new JSONObject(new String(b.toByteArray(), StandardCharsets.UTF_8)); } catch (Exception ignored) { }
                }
            }
            return new PlanerVerbindung.Antwort(code, j);
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Ablage in Config-Abschnitt planner (Schlüssel verschlüsselt). */
    static final class UcmSpeicher implements PlanerVerbindung.Speicher {
        public boolean speichere(String schluessel, String name, String geraeteId, long seit) {
            try {
                String enc = at.greenchili.surfmate.byd.cloud.crypto.CredentialCipher.encrypt(schluessel);
                if (enc == null || enc.isEmpty() || enc.equals(schluessel)) return false;   // nie Klartext ablegen
                JSONObject s = new JSONObject();
                s.put("geraeteSchluessel", enc);
                s.put("geraeteName", name == null ? "" : name);
                s.put("geraeteId", geraeteId == null ? "" : geraeteId);
                s.put("geraeteSeit", seit);
                return at.greenchili.surfmate.config.UnifiedConfigManager.updateSection("planner", s);
            } catch (Throwable t) {
                return false;
            }
        }

        public boolean loesche() {
            try {
                JSONObject s = new JSONObject();
                s.put("geraeteSchluessel", "");
                s.put("geraeteName", "");
                s.put("geraeteId", "");
                s.put("geraeteSeit", 0);
                return at.greenchili.surfmate.config.UnifiedConfigManager.updateSection("planner", s);
            } catch (Throwable t) {
                return false;
            }
        }

        public String[] verbunden() {
            JSONObject p = at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig().optJSONObject("planner");
            if (p == null || p.optString("geraeteSchluessel", "").isEmpty()) return null;
            return new String[] {p.optString("geraeteName", ""), String.valueOf(p.optLong("geraeteSeit", 0))};
        }
    }
}
