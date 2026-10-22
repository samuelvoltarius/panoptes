package at.greenchili.surfmate.logging;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Liest das Ende einer Daemon-Logdatei und schwärzt Geheimnisse (Tokens, Schlüssel) – nur lokal.
 *
 * <p>Panoptes 0.1.9: Der frühere Upload zu einem Cloudflare Worker (Overdrive-Erbe, fremder Server) ist entfernt
 * (Koordination, Regel 2). Fehlerberichte kommen später nur mit Einwilligung über den eigenen Community-Server.
 * Hier bleibt nur das lokale Lesen + Schwärzen, das „Frag Panoptes“ für Diagnose-Zusammenfassungen nutzt.
 */
public final class LogUploader {

    private static final long MAX_TAIL_BYTES = 1_500_000; // ~1.5 MB

    private LogUploader() {}

    /** Read at most {@code maxBytes} from the END of the file (UTF-8). */
    static String readTail(String path, long maxBytes) throws Exception {
        File f = new File(path);
        if (!f.exists() || f.length() == 0) return null;
        long len = f.length();
        long start = Math.max(0, len - maxBytes);
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            raf.seek(start);
            int toRead = (int) Math.min(maxBytes, len - start);
            byte[] buf = new byte[toRead];
            raf.readFully(buf);
            // If we started mid-file, drop the partial first line.
            String s = new String(buf, java.nio.charset.StandardCharsets.UTF_8);
            if (start > 0) {
                int nl = s.indexOf('\n');
                if (nl >= 0 && nl + 1 < s.length()) s = s.substring(nl + 1);
            }
            return s;
        }
    }

    // ---- Redaction ----
    // Strip values that could leak from a log shared on a public channel.
    // Conservative + line-oriented: we replace the VALUE after a known
    // key-ish token, and mask anything shaped like a bot token / bearer.
    private static final Pattern[] REDACTORS = new Pattern[] {
        // Telegram bot token: 8-10 digits : 30+ base64-ish chars. NO leading
        // \b — the token is usually preceded by "bot" in the API URL
        // (https://api.telegram.org/bot123:AAH…), where t→1 is word-to-word so
        // \b never matches. A digit run that long followed by ':' + a 30-char
        // secret is unambiguous enough to mask without an anchor.
        Pattern.compile("\\d{8,10}:[A-Za-z0-9_-]{30,}"),
        // Bearer / Authorization tokens
        Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._-]{12,}"),
        // key=value or "key":"value" / key: value for sensitive key names.
        // `device` (not just deviceid/did) catches "Device: <id>" — the
        // AuthManager logs the device id in that prose form.
        Pattern.compile("(?i)(token|secret|password|passwd|apikey|api_key|auth|device|deviceid|device_id|did|chat_?id)" +
                "(\"?\\s*[:=]\\s*\"?)([A-Za-z0-9._:\\-]{4,})"),
    };

    static String redact(String s) {
        if (s == null) return null;
        String out = s;
        // Telegram token → mask whole match (covers both bare and bot<token> URL forms).
        out = REDACTORS[0].matcher(out).replaceAll("[REDACTED_TOKEN]");
        out = REDACTORS[1].matcher(out).replaceAll("$1[REDACTED]");
        // key=value → keep the key, mask the value.
        Matcher m = REDACTORS[2].matcher(out);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + m.group(2) + "[REDACTED]"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Read a bounded local tail and apply the same secret redaction used by
     * support uploads. This performs no upload and opens no network client.
     */
    public static String readRedactedTail(
            String path, long requestedMaxBytes) throws Exception {
        long maxBytes = Math.max(
                1L, Math.min(requestedMaxBytes, MAX_TAIL_BYTES));
        return redact(readTail(path, maxBytes));
    }
}
