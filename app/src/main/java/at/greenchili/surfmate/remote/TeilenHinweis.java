package at.greenchili.surfmate.remote;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * App-Prozess: rotes Band oben am Auto-Display, solange die Fernansicht läuft (Regel in {@link TeilenHinweisRegel}).
 * Nicht berührbar, nimmt keinen Fokus, verdeckt also keine Bedienung darunter. Der Text kommt vom Daemon in der
 * gewählten Sprache; ohne Text gilt der deutsche Satz.
 */
public final class TeilenHinweis {

    private static final String TAG = "TeilenHinweis";
    static final String STANDARD_TEXT = "Bildschirm wird geteilt";
    private static final long TAKT_MS = 2_000L;

    private static final TeilenHinweisRegel REGEL = new TeilenHinweisRegel();
    private static final Handler HAUPT = new Handler(Looper.getMainLooper());
    private static View band;
    private static String text = STANDARD_TEXT;
    private static Context app;
    private static boolean taktLaeuft;

    private TeilenHinweis() {}

    public static void gestartet(Context ctx, String hinweisText) {
        app = ctx.getApplicationContext();
        if (hinweisText != null && !hinweisText.trim().isEmpty()) text = hinweisText.trim();
        REGEL.gestartet(System.currentTimeMillis());
        HAUPT.post(TeilenHinweis::abgleichen);
    }

    public static void zugriff() {
        REGEL.zugriff(System.currentTimeMillis());
    }

    public static void beendet() {
        REGEL.beendet();
        HAUPT.post(TeilenHinweis::abgleichen);
    }

    /** Für Tests und Anzeige: ist das Band laut Regel gerade sichtbar? */
    public static boolean sichtbar() {
        return REGEL.sichtbar(System.currentTimeMillis());
    }

    private static void abgleichen() {
        boolean soll = REGEL.sichtbar(System.currentTimeMillis());
        if (soll && band == null) zeigen();
        else if (!soll && band != null) entfernen();
        if (soll && band instanceof TextView) ((TextView) band).setText(text);
        if (soll && !taktLaeuft) {
            taktLaeuft = true;
            HAUPT.postDelayed(TeilenHinweis::takt, TAKT_MS);
        }
    }

    private static void takt() {
        taktLaeuft = false;
        abgleichen();
    }

    private static void zeigen() {
        if (app == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(app)) {
                Log.w(TAG, "Kein Overlay-Recht, Hinweis nicht sichtbar");
                return;
            }
            TextView tv = new TextView(app);
            tv.setText(text);
            tv.setTextColor(0xFFFFFFFF);
            tv.setBackgroundColor(0xE6C62828);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            tv.setGravity(Gravity.CENTER);
            int pad = (int) (10 * app.getResources().getDisplayMetrics().density);
            tv.setPadding(pad * 2, pad, pad * 2, pad);
            tv.setContentDescription(text);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = pad;
            ((WindowManager) app.getSystemService(Context.WINDOW_SERVICE)).addView(tv, lp);
            band = tv;
        } catch (Throwable t) {
            Log.w(TAG, "Hinweis zeigen fehlgeschlagen", t);
            band = null;
        }
    }

    private static void entfernen() {
        try {
            ((WindowManager) app.getSystemService(Context.WINDOW_SERVICE)).removeView(band);
        } catch (Throwable ignored) {
        }
        band = null;
    }
}
