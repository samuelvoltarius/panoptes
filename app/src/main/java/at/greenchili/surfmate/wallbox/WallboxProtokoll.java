package at.greenchili.surfmate.wallbox;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Schreib-Protokoll der Wallbox (WALLBOX_VERTRAG §5.4): eine JSON-Zeile je Steuer- oder Freigabe-Aktion,
 * die letzten 200 Zeilen. Enthält nie URL, Token oder Position – nur Zeit, Quelle, Ladepunkt, Aktion, Wert, Ergebnis.
 */
public final class WallboxProtokoll {

    static final int BEHALTEN = 200;

    private final File datei;

    public WallboxProtokoll(File datei) {
        this.datei = datei;
    }

    public synchronized void schreibe(long zeit, String quelle, String ladepunkt, String aktion, Object wert,
                                      String ergebnis, String herkunft) {
        try {
            JSONObject z = new JSONObject()
                    .put("zeit", zeit)
                    .put("quelle", quelle == null ? JSONObject.NULL : quelle)
                    .put("ladepunkt", ladepunkt)
                    .put("aktion", aktion)
                    .put("wert", wert == null ? JSONObject.NULL : wert)
                    .put("ergebnis", ergebnis)
                    .put("herkunft", herkunft);
            List<String> zeilen = zeilen();
            zeilen.add(z.toString());
            if (zeilen.size() > BEHALTEN) zeilen = new ArrayList<>(zeilen.subList(zeilen.size() - BEHALTEN, zeilen.size()));
            File tmp = new File(datei.getPath() + ".tmp");
            Files.write(tmp.toPath(), (String.join("\n", zeilen) + "\n").getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), datei.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable ignored) {
            // Protokollfehler stören die Aktion nie.
        }
    }

    /** Neueste zuerst. */
    public synchronized JSONArray lies(int max) {
        JSONArray a = new JSONArray();
        List<String> z = zeilen();
        for (int i = z.size() - 1; i >= 0 && a.length() < max; i--) {
            try {
                a.put(new JSONObject(z.get(i)));
            } catch (Exception ignored) { }
        }
        return a;
    }

    private List<String> zeilen() {
        List<String> z = new ArrayList<>();
        try {
            if (datei.exists()) {
                for (String s : Files.readAllLines(datei.toPath(), StandardCharsets.UTF_8)) if (!s.trim().isEmpty()) z.add(s);
            }
        } catch (Exception ignored) { }
        return z;
    }
}
