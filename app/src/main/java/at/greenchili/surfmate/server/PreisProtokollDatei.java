package at.greenchili.surfmate.server;

import at.greenchili.surfmate.charging.LadePreis;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Protokoll des Preis-Wegs (alt → neu) als Datei im Daemon-Ordner, für „Rückgängig“. Höchstens {@link #MAX} Einträge,
 * atomar geschrieben (temp + rename). Enthält nur Preisfelder, keine Positionen.
 */
final class PreisProtokollDatei implements LadePreis.Protokoll {

    static final int MAX = 50;
    static final PreisProtokollDatei INSTANZ = new PreisProtokollDatei(new File(
            at.greenchili.surfmate.util.DaemonStorage.rebase("/data/user_de/0/com.android.shell/surfmate/preis_protokoll.json")));

    private final File datei;

    PreisProtokollDatei(File datei) {
        this.datei = datei;
    }

    public synchronized boolean speichere(JSONObject eintrag) {
        try {
            JSONArray alle = alle();
            alle.put(eintrag);
            JSONArray neu = new JSONArray();
            for (int i = Math.max(0, alle.length() - MAX); i < alle.length(); i++) neu.put(alle.get(i));
            File tmp = new File(datei.getPath() + ".tmp");
            File dir = datei.getParentFile();
            if (dir != null && !dir.isDirectory()) dir.mkdirs();
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(neu.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            return tmp.renameTo(datei) || (datei.delete() && tmp.renameTo(datei));
        } catch (Exception e) {
            return false;
        }
    }

    public synchronized JSONObject lies(String id) {
        try {
            JSONArray a = alle();
            for (int i = a.length() - 1; i >= 0; i--) {
                JSONObject e = a.optJSONObject(i);
                if (e != null && id.equals(e.optString("id"))) return e;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private JSONArray alle() throws Exception {
        if (!datei.isFile()) return new JSONArray();
        return new JSONArray(new String(Files.readAllBytes(datei.toPath()), StandardCharsets.UTF_8));
    }
}
