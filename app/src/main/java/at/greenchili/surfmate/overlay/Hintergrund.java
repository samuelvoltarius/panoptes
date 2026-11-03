package at.greenchili.surfmate.overlay;

import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.DisplayMetrics;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * App-Prozess: Hintergrundbild des Zentraldisplays setzen bzw. auf Standard zurück (Regeln in
 * {@link HintergrundRegel}). Der Daemon prüft das Bild und ruft das hier über die geschützte Brücke
 * ({@code RemoteDevViewBridgeService}, Befehl {@code hintergrund}) mit einer Einmal-Marke auf; die App holt das Bild
 * damit selbst ab ({@code POST /api/display/hintergrund/bild}) und legt es in ihren privaten Ordner.
 */
public final class Hintergrund {

    private static final String TAG = "PanoptesHintergrund";
    private static final String PREFS = "display_hintergrund";

    private Hintergrund() {}

    /** Bild mit der Marke beim Daemon abholen, höchstens {@link HintergrundRegel#MAX_BYTES}. */
    static File abholen(Context ctx, String marke) throws Exception {
        File f = new File(ctx.getFilesDir(), HintergrundRegel.DATEI);
        File tmp = new File(ctx.getFilesDir(), HintergrundRegel.DATEI + ".neu");
        java.net.HttpURLConnection c = null;
        try {
            c = at.greenchili.surfmate.util.DaemonHttpClient.open("/api/display/hintergrund/bild", "POST", 3000, 20000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (java.io.OutputStream o = c.getOutputStream()) {
                o.write(new JSONObject().put("marke", marke).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            if (c.getResponseCode() != 200) return null;
            long summe = 0;
            try (InputStream in = c.getInputStream(); java.io.FileOutputStream w = new java.io.FileOutputStream(tmp)) {
                byte[] b = new byte[64 * 1024];
                int n;
                while ((n = in.read(b)) > 0) {
                    summe += n;
                    if (summe > HintergrundRegel.MAX_BYTES) return null;
                    w.write(b, 0, n);
                }
            }
            if (!tmp.renameTo(f)) return null;
            return f;
        } finally {
            if (c != null) c.disconnect();
            tmp.delete();
        }
    }

    /** Bild setzen. Antwort {success, grund?, vorher?}. */
    public static JSONObject setzen(Context ctx, String marke) {
        JSONObject r = new JSONObject();
        try {
            File f = abholen(ctx, marke);
            if (f == null || !f.isFile()) return r.put("success", false).put("grund", "datei");
            byte[] kopf = new byte[16];
            int n;
            try (InputStream in = new FileInputStream(f)) {
                n = in.read(kopf);
            }
            if (n < 12 || HintergrundRegel.art(kopf) == null || f.length() > HintergrundRegel.MAX_BYTES) {
                return r.put("success", false).put("grund", "art");
            }
            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            int ziel = Math.max(1280, Math.max(dm.widthPixels, dm.heightPixels));
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return r.put("success", false).put("grund", "art");
            int teiler = 1;
            while (Math.max(o.outWidth, o.outHeight) / (teiler * 2) >= ziel) teiler *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = teiler;
            Bitmap bmp = BitmapFactory.decodeFile(f.getPath(), d);
            if (bmp == null) return r.put("success", false).put("grund", "art");
            WallpaperManager wm = WallpaperManager.getInstance(ctx);
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (!p.contains("vorher")) {
                WallpaperInfo info = wm.getWallpaperInfo();
                p.edit().putString("vorher", info == null ? "" : info.getComponent().flattenToShortString()).apply();
            }
            wm.setBitmap(bmp, null, true, WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK);
            bmp.recycle();
            WallpaperInfo jetzt = wm.getWallpaperInfo();
            r.put("success", true).put("live", jetzt == null ? "" : jetzt.getComponent().flattenToShortString());
            r.put("vorher", p.getString("vorher", ""));
            return r;
        } catch (SecurityException e) {
            Log.w(TAG, "setzen: kein Recht", e);
            return safe(r, "recht");
        } catch (Throwable t) {
            Log.w(TAG, "setzen fehlgeschlagen", t);
            return safe(r, "fehler");
        }
    }

    /** Zurück auf Standard ({@code WallpaperManager.clear}). Antwort {success, live, vorher, gleichWieVorher}. */
    public static JSONObject standard(Context ctx) {
        JSONObject r = new JSONObject();
        try {
            WallpaperManager wm = WallpaperManager.getInstance(ctx);
            wm.clear(WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK);
            WallpaperInfo jetzt = wm.getWallpaperInfo();
            String live = jetzt == null ? "" : jetzt.getComponent().flattenToShortString();
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String vorher = p.getString("vorher", null);
            r.put("success", true).put("live", live);
            if (vorher != null) {
                r.put("vorher", vorher).put("gleichWieVorher", vorher.equals(live));
                if (vorher.equals(live)) p.edit().remove("vorher").apply();
            }
            try {
                new File(ctx.getFilesDir(), HintergrundRegel.DATEI).delete();
            } catch (Throwable ignored) {
            }
            return r;
        } catch (SecurityException e) {
            Log.w(TAG, "standard: kein Recht", e);
            return safe(r, "recht");
        } catch (Throwable t) {
            Log.w(TAG, "standard fehlgeschlagen", t);
            return safe(r, "fehler");
        }
    }

    private static JSONObject safe(JSONObject r, String grund) {
        try {
            return r.put("success", false).put("grund", grund);
        } catch (Exception e) {
            return r;
        }
    }
}
