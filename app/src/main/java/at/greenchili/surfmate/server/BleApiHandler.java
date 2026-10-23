package at.greenchili.surfmate.server;

import at.greenchili.surfmate.ble.BleZaehlung;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 0.1.16: {@code GET /api/ble/zaehlen} – Lese-Endpunkt „BLE-Zählen“ (siehe {@link BleZaehlung}). Der Daemon stößt
 * den Scan in der App an ({@code am broadcast}, Receiver nur mit DUMP erreichbar) und gibt nur Anzahl und grobe
 * Typen zurück. Schaltet nie Bluetooth oder den Standortdienst ein. Höchstens ein Scan je {@link #ABSTAND_MS}
 * (sonst kommt das letzte Ergebnis mit {@code alterMs}).
 */
public final class BleApiHandler {

    static final long ABSTAND_MS = 30_000L;

    /** Shell-Ausführung (im Test ersetzt). */
    interface Shell {
        String aus(String cmd, int sekunden);
    }

    static Shell shell = BleApiHandler::shellAus;
    private static JSONObject letztes;
    private static long letztesAm;

    private BleApiHandler() {}

    public static boolean handle(String method, String path, OutputStream out) throws Exception {
        String p = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        if (!p.equals("/api/ble/zaehlen")) return false;
        if (!"GET".equals(method)) {
            HttpResponse.sendJson(out, 405, "{\"ok\":false,\"error\":\"nur_get\"}");
            return true;
        }
        HttpResponse.sendJson(out, zaehlen(System.currentTimeMillis()).toString());
        return true;
    }

    static synchronized JSONObject zaehlen(long jetzt) throws Exception {
        if (letztes != null && jetzt - letztesAm < ABSTAND_MS) {
            return new JSONObject(letztes.toString()).put("alterMs", jetzt - letztesAm);
        }
        JSONObject r = BleZaehlung.ausAmAusgabe(shell.aus(BleZaehlung.amBefehl(), 25));
        if (r == null) r = BleZaehlung.nicht("app_antwortet_nicht");
        letztes = r;
        letztesAm = jetzt;
        return new JSONObject(r.toString()).put("alterMs", 0);
    }

    static synchronized void vergessen() {
        letztes = null;
        letztesAm = 0;
    }

    private static String shellAus(String cmd, int sekunden) {
        try {
            Process pr = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start();
            // Aufpasser: hängt am broadcast, wird der Prozess beendet, damit das Lesen unten nie ewig blockiert
            Thread wache = new Thread(() -> {
                try {
                    if (!pr.waitFor(sekunden, java.util.concurrent.TimeUnit.SECONDS)) pr.destroyForcibly();
                } catch (InterruptedException ignored) { }
            }, "BleZaehlen-Wache");
            wache.setDaemon(true);
            wache.start();
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            try (InputStream in = pr.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) b.write(buf, 0, n);
            }
            if (!pr.waitFor(sekunden, java.util.concurrent.TimeUnit.SECONDS)) pr.destroyForcibly();
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
