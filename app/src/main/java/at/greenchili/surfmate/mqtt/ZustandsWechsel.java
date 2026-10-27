package at.greenchili.surfmate.mqtt;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 0.1.17 (HA-Sitzung über die Koordination, 27.10.2026): echter Zeitpunkt der letzten Änderung für Schloss
 * ({@code unlocked}) und Ladestecker ({@code plugged}). HA-{@code last_changed} springt beim HA-Neustart zurück;
 * deshalb veröffentlicht Panoptes {@code lock_changed_at} / {@code plugged_changed_at} (ISO-8601, HA-Zeitstempel)
 * und {@code *_ms} (Epoch ms), retained wie alle Telemetrie.
 *
 * <p>Der letzte Wert und sein Zeitpunkt liegen in einer kleinen Datei: Ein Daemon-Neustart mit unverändertem
 * Wert erzeugt keinen falschen Wechsel. Erst eine echte Änderung setzt den Zeitpunkt neu; ist nichts bekannt
 * (erster Start), bleibt das Feld leer, bis sich der Wert ändert.
 */
final class ZustandsWechsel {

    /** Telemetrie-Schlüssel → Präfix der Zeitfelder. */
    static final String[][] FELDER = {{"unlocked", "lock_changed_at"}, {"plugged", "plugged_changed_at"}};

    private final File datei;
    private JSONObject stand;

    ZustandsWechsel(File datei) {
        this.datei = datei;
    }

    static ZustandsWechsel imDaemon() {
        return new ZustandsWechsel(new File(at.greenchili.surfmate.util.DaemonStorage.rebase(
                "/data/user_de/0/com.android.shell/surfmate/zustandswechsel.json")));
    }

    synchronized void eintragen(JSONObject payload, long jetztMs) {
        if (stand == null) stand = lesen();
        boolean geaendert = false;
        for (String[] f : FELDER) {
            if (!payload.has(f[0])) continue;
            int wert = payload.optInt(f[0], -1);
            if (wert < 0) continue;
            JSONObject alt = stand.optJSONObject(f[0]);
            try {
                if (alt == null) {
                    // erster bekannter Wert: Zeitpunkt der letzten Änderung unbekannt
                    stand.put(f[0], new JSONObject().put("wert", wert).put("seit", 0L));
                    geaendert = true;
                } else if (alt.optInt("wert", -1) != wert) {
                    stand.put(f[0], new JSONObject().put("wert", wert).put("seit", jetztMs));
                    geaendert = true;
                }
                long seit = stand.getJSONObject(f[0]).optLong("seit", 0L);
                if (seit > 0) {
                    payload.put(f[1], iso(seit));
                    payload.put(f[1] + "_ms", seit);
                }
            } catch (Exception ignored) {
                // nur Telemetrie
            }
        }
        if (geaendert) schreiben();
    }

    private JSONObject lesen() {
        try {
            if (datei.isFile()) return new JSONObject(new String(Files.readAllBytes(datei.toPath()), StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
        return new JSONObject();
    }

    private void schreiben() {
        try {
            File tmp = new File(datei.getPath() + ".tmp");
            Files.write(tmp.toPath(), stand.toString().getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(datei)) {
                Files.write(datei.toPath(), stand.toString().getBytes(StandardCharsets.UTF_8));
                tmp.delete();
            }
        } catch (Exception ignored) { }
    }

    static String iso(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT).format(new Date(ms));
    }
}
