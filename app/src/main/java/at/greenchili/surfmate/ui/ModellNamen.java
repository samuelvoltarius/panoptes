package at.greenchili.surfmate.ui;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Locale;

/**
 * 0.1.21 (Koordination: alte Oberfläche zeigte „BYD Seagull“ statt „BYD Dolphin Surf“): Modellnamen in der nativen
 * Oberfläche nur noch aus dem Modell-Manifest (nameEu vor name), keine eigene Namensliste mehr in Kotlin/strings.xml.
 */
public final class ModellNamen {

    private ModellNamen() {}

    static final String MANIFEST = "web/shared/models/manifest.json";
    private static volatile JSONObject manifest;

    /** Anzeigename für {@code modelId}: nameEu, sonst name, sonst „BYD &lt;Id&gt;“; null → „—“. Rein, testbar. */
    public static String anzeige(JSONObject manifest, String modelId) {
        if (modelId == null || modelId.trim().isEmpty()) return "—";
        JSONObject m = finde(manifest, modelId);
        if (m != null) {
            String eu = m.optString("nameEu", "");
            if (!eu.isEmpty()) return eu;
            String name = m.optString("name", "");
            if (!name.isEmpty()) return name;
        }
        String id = modelId.trim();
        return "BYD " + id.substring(0, 1).toUpperCase(Locale.ROOT) + id.substring(1);
    }

    /** Wie {@link #anzeige(JSONObject, String)}, Manifest aus den App-Assets (einmal gelesen). */
    public static String anzeige(Context ctx, String modelId) {
        return anzeige(manifest(ctx), modelId);
    }

    /** Eintrag zu {@code modelId}; Schreibweisen wie „sealu“, „atto-3“ gelten wie „seal-u“, „atto3“. */
    static JSONObject finde(JSONObject manifest, String modelId) {
        JSONArray arr = manifest == null ? null : manifest.optJSONArray("models");
        if (arr == null) return null;
        String want = modelId.trim().toLowerCase(Locale.ROOT);
        String wantKurz = want.replace("-", "");
        JSONObject ungefaehr = null;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject m = arr.optJSONObject(i);
            if (m == null) continue;
            String id = m.optString("id", "").toLowerCase(Locale.ROOT);
            if (id.equals(want)) return m;
            if (ungefaehr == null && id.replace("-", "").equals(wantKurz)) ungefaehr = m;
        }
        return ungefaehr;
    }

    private static JSONObject manifest(Context ctx) {
        JSONObject m = manifest;
        if (m != null) return m;
        try (InputStream in = ctx.getApplicationContext().getAssets().open(MANIFEST)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            m = new JSONObject(b.toString("UTF-8"));
        } catch (Exception e) {
            m = new JSONObject();
        }
        manifest = m;
        return m;
    }
}
