package at.greenchili.surfmate.weckwort;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sichtbare Anzeige „Mikrofon an (Messung)“ über allen Apps, solange die Weckwort-Messung das Mikrofon offen hat.
 * Ohne Overlay-Recht startet die Messung nicht; verschwindet die Anzeige, endet die Messung.
 */
final class WeckwortMessAnzeige {

    private final Context ctx;
    private final Handler haupt = new Handler(Looper.getMainLooper());
    private TextView ansicht;

    WeckwortMessAnzeige(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    static boolean erlaubt(Context ctx) {
        try {
            return Settings.canDrawOverlays(ctx);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Zeigt die Anzeige an und wartet höchstens 2 s darauf. true nur, wenn sie wirklich am Fenster hängt. */
    boolean zeigen() {
        CountDownLatch fertig = new CountDownLatch(1);
        AtomicBoolean ok = new AtomicBoolean(false);
        Runnable r = () -> {
            try {
                WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                TextView tv = new TextView(ctx);
                tv.setText(text());
                tv.setTextColor(Color.WHITE);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
                int pad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10,
                        ctx.getResources().getDisplayMetrics());
                tv.setPadding(pad * 2, pad, pad * 2, pad);
                GradientDrawable hg = new GradientDrawable();
                hg.setColor(0xE6C62828);
                hg.setCornerRadius(pad * 3);
                tv.setBackground(hg);
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                lp.y = pad * 2;
                wm.addView(tv, lp);
                ansicht = tv;
                ok.set(true);
            } catch (Throwable t) {
                ok.set(false);
            } finally {
                fertig.countDown();
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            haupt.post(r);
            try {
                fertig.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return ok.get();
    }

    /** Text in der Panoptes-Sprache (der Messprozess hat keine Activity, die AppCompat-Sprache greift hier nicht). */
    private String text() {
        try {
            String tag = at.greenchili.surfmate.server.LocaleManager.get();
            android.content.res.Configuration cfg =
                    new android.content.res.Configuration(ctx.getResources().getConfiguration());
            cfg.setLocale(java.util.Locale.forLanguageTag(tag));
            return ctx.createConfigurationContext(cfg).getString(at.greenchili.surfmate.R.string.weckwort_messung_anzeige);
        } catch (Throwable t) {
            return ctx.getString(at.greenchili.surfmate.R.string.weckwort_messung_anzeige);
        }
    }

    boolean istSichtbar() {
        TextView tv = ansicht;
        return tv != null && tv.isAttachedToWindow();
    }

    void entfernen() {
        haupt.post(() -> {
            TextView tv = ansicht;
            ansicht = null;
            if (tv == null) return;
            try {
                WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                wm.removeView(tv);
            } catch (Throwable ignored) {
            }
        });
    }
}
