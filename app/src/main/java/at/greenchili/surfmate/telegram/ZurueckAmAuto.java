package at.greenchili.surfmate.telegram;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 0.1.21 Telegram „zurück am Auto“ (Koordination 27.10.): beim Einschalten nach einer Parkzeit eine ruhige Meldung
 * „Zurück am Auto – 2 h 14 min geparkt, 3 Wächter-Ereignisse“. Abschaltbar, Standard aus ({@code telegram.zurueckAmAuto}).
 * Ohne Ort (Position nur über /wo mit Freigabe). Erst ab {@link #MIN_PARKEN_MS} Parkzeit, damit kurzes Aus/An nicht meldet.
 */
public final class ZurueckAmAuto {

    static final long MIN_PARKEN_MS = 10 * 60_000L;

    private ZurueckAmAuto() {}

    /** Rein: Text-Schlüssel + Werte oder null (nichts melden). */
    static Object[] meldung(boolean an, long ausSeit, long jetzt, int ereignisse) {
        if (!an || ausSeit <= 0 || jetzt - ausSeit < MIN_PARKEN_MS || jetzt - ausSeit > 30L * 86_400_000L) return null;
        String dauer = dauer(jetzt - ausSeit);
        return ereignisse > 0 ? new Object[]{"zurueck.mit_ereignissen", dauer, ereignisse} : new Object[]{"zurueck.text", dauer};
    }

    static String dauer(long ms) {
        long min = ms / 60_000L, h = min / 60, m = min % 60;
        if (h >= 48) return (h / 24) + " d " + (h % 24) + " h";
        return h > 0 ? h + " h " + m + " min" : m + " min";
    }

    static boolean schalter(JSONObject root) {
        JSONObject t = root == null ? null : root.optJSONObject("telegram");
        return t != null && t.optBoolean("zurueckAmAuto", false);
    }

    private static File datei() {
        return new File(at.greenchili.surfmate.util.DaemonStorage.path("zurueck_am_auto.json"));
    }

    /** ACC aus: Zeitpunkt merken (überlebt einen Daemon-Neustart). */
    public static void beiAccAus(long jetzt) {
        try {
            File f = datei();
            File tmp = new File(f.getPath() + ".tmp");
            Files.write(tmp.toPath(), new JSONObject().put("aus", jetzt).toString().getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(f)) Files.write(f.toPath(), Files.readAllBytes(tmp.toPath()));
        } catch (Throwable ignored) { }
    }

    /** ACC an: ggf. melden (eigener Thread, blockiert den Übergang nie). */
    public static void beiAccAn(long jetzt) {
        Thread t = new Thread(() -> {
            try {
                long aus = 0;
                File f = datei();
                if (f.exists()) aus = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)).optLong("aus", 0);
                f.delete();
                boolean an = schalter(at.greenchili.surfmate.config.UnifiedConfigManager.loadConfig());
                int ereignisse = 0;
                try {
                    JSONObject p = new JSONObject();
                    at.greenchili.surfmate.server.ParkingApiHandler.fillStatus(p);
                    JSONObject s = p.optJSONObject("current");
                    if (s == null) s = p.optJSONObject("latest");
                    if (s != null) ereignisse = s.optInt("eventCount", 0);
                } catch (Throwable ignored) { }
                Object[] m = meldung(an, aus, jetzt, ereignisse);
                if (m == null) return;
                Object[] args = new Object[m.length - 1];
                System.arraycopy(m, 1, args, 0, args.length);
                TelegramNotifier.sendMessage(TelegramMessages.get((String) m[0], args), "CRITICAL");
            } catch (Throwable ignored) { }
        }, "ZurueckAmAuto");
        t.setDaemon(true);
        t.start();
    }
}
