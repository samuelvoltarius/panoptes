package at.greenchili.surfmate.wallbox;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * HTTP zur Wallbox-Quelle. Keine Weiterleitungen (3xx = Fehler), 4 s Zeitlimit, Antwort max. 512 KB
 * (WALLBOX_VERTRAG §2). In Tests durch eine Attrappe ersetzt.
 */
public interface WallboxHttp {

    final class Antwort {
        public final int code;
        public final String text;

        public Antwort(int code, String text) {
            this.code = code;
            this.text = text == null ? "" : text;
        }
    }

    Antwort anfrage(String methode, String url, Map<String, String> kopf, String rumpf) throws WallboxFehler;

    int MAX_BYTES = 512 * 1024;
    int ZEIT_MS = 4000;

    WallboxHttp STANDARD = (methode, url, kopf, rumpf) -> {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(ZEIT_MS);
            c.setReadTimeout(ZEIT_MS);
            c.setUseCaches(false);
            c.setRequestMethod(methode);
            c.setRequestProperty("Accept", "application/json");
            if (kopf != null) for (Map.Entry<String, String> e : kopf.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
            if (rumpf != null) {
                byte[] b = rumpf.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                if (kopf == null || !kopf.containsKey("Content-Type")) c.setRequestProperty("Content-Type", "application/json");
                c.setFixedLengthStreamingMode(b.length);
                try (OutputStream o = c.getOutputStream()) { o.write(b); }
            } else if ("POST".equals(methode)) {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(0);
                c.getOutputStream().close();
            }
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) throw new WallboxFehler("weiterleitung", 502);
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = "";
            if (in != null) {
                try (InputStream i = in) {
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    byte[] tmp = new byte[8192];
                    int n;
                    while ((n = i.read(tmp)) > 0) {
                        if (buf.size() + n > MAX_BYTES) throw new WallboxFehler("antwort_ungueltig", 502);
                        buf.write(tmp, 0, n);
                    }
                    text = new String(buf.toByteArray(), StandardCharsets.UTF_8);
                }
            }
            return new Antwort(code, text);
        } catch (WallboxFehler f) {
            throw f;
        } catch (Exception e) {
            throw new WallboxFehler("nicht_erreichbar", 502);
        } finally {
            if (c != null) c.disconnect();
        }
    };
}
