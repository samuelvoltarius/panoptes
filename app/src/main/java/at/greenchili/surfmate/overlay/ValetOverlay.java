package at.greenchili.surfmate.overlay;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;

/**
 * App-Prozess: das Overlay der Display-Sperre (Regeln in {@link ValetRegel}). Der Daemon schickt über die
 * geschützte Brücke etwa alle 1,5 s „zeigen ja/nein“; bleibt das aus ({@link ValetRegel#HERZSCHLAG_MS}), geht das
 * Overlay von selbst weg. Bei einem Anruf (auch Notruf) zieht es sich sofort zurück.
 *
 * <p>Notausgang: {@link ValetRegel#LANG_DRUECKEN_MS} lang drücken, dann PIN. Die PIN prüft nur der Daemon.
 */
public final class ValetOverlay {

    private static final String TAG = "ValetOverlay";
    private static final long TAKT_MS = 1_000L;
    private static final Handler HAUPT = new Handler(Looper.getMainLooper());

    private static Context app;
    private static FrameLayout wurzel;
    private static TextView meldung, punkte;
    private static LinearLayout pinFeld;
    private static final StringBuilder PIN = new StringBuilder();
    private static volatile long letzteBestaetigung;
    private static volatile boolean zeigenLaut;
    private static JSONObject texte = new JSONObject();
    private static boolean taktLaeuft;
    private static Runnable langDruck;

    private ValetOverlay() {}

    /** Von der Brücke: {zeigen, texte{...}}. */
    public static void befehl(Context ctx, JSONObject b) {
        app = ctx.getApplicationContext();
        zeigenLaut = b.optBoolean("zeigen", false);
        letzteBestaetigung = zeigenLaut ? System.currentTimeMillis() : 0L;
        JSONObject t = b.optJSONObject("texte");
        if (t != null) texte = t;
        HAUPT.post(ValetOverlay::abgleichen);
    }

    private static String t(String k, String f) {
        String v = texte.optString(k, "");
        return v.isEmpty() ? f : v;
    }

    private static boolean anruf() {
        try {
            TelephonyManager tm = (TelephonyManager) app.getSystemService(Context.TELEPHONY_SERVICE);
            return tm != null && tm.getCallState() != TelephonyManager.CALL_STATE_IDLE;
        } catch (Throwable t) {
            return false;   // ohne Recht: der Daemon prüft zusätzlich die App im Vordergrund
        }
    }

    private static void abgleichen() {
        boolean soll = app != null && !anruf()
                && ValetRegel.overlayZeigen(zeigenLaut, letzteBestaetigung, System.currentTimeMillis());
        if (soll && wurzel == null) zeigen();
        else if (!soll && wurzel != null) entfernen();
        if (wurzel != null && !taktLaeuft) {
            taktLaeuft = true;
            HAUPT.postDelayed(() -> {
                taktLaeuft = false;
                abgleichen();
            }, TAKT_MS);
        }
    }

    private static int dp(int v) {
        return (int) (v * app.getResources().getDisplayMetrics().density);
    }

    @SuppressLint("ClickableViewAccessibility")
    private static void zeigen() {
        try {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(app)) {
                Log.w(TAG, "Kein Overlay-Recht, Sperre nicht sichtbar");
                return;
            }
            FrameLayout w = new FrameLayout(app);
            w.setBackgroundColor(0xF2000000);
            LinearLayout mitte = new LinearLayout(app);
            mitte.setOrientation(LinearLayout.VERTICAL);
            mitte.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView titel = text(t("titel", "Display gesperrt"), 34, true);
            TextView hilfe = text(t("hilfe", "Zum Entsperren lange drücken und PIN eingeben."), 18, false);
            meldung = text("", 18, false);
            meldung.setTextColor(0xFFFFB4AB);
            mitte.addView(titel);
            mitte.addView(hilfe);
            mitte.addView(meldung);
            pinFeld = pinFeld();
            pinFeld.setVisibility(View.GONE);
            mitte.addView(pinFeld);
            FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
            w.addView(mitte, mlp);
            // Alle Berührungen schlucken; lang drücken öffnet die PIN-Eingabe.
            w.setOnTouchListener((v, e) -> {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    if (langDruck != null) HAUPT.removeCallbacks(langDruck);
                    langDruck = () -> {
                        PIN.setLength(0);
                        aktualisierePunkte();
                        pinFeld.setVisibility(View.VISIBLE);
                    };
                    HAUPT.postDelayed(langDruck, ValetRegel.LANG_DRUECKEN_MS);
                } else if (e.getActionMasked() == MotionEvent.ACTION_UP
                        || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    if (langDruck != null) HAUPT.removeCallbacks(langDruck);
                }
                return true;
            });
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                    // kein FLAG_LAYOUT_IN_SCREEN: die Leisten des Systems (Status, Klima, Navigation) bleiben frei
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            ((WindowManager) app.getSystemService(Context.WINDOW_SERVICE)).addView(w, lp);
            wurzel = w;
        } catch (Throwable t) {
            Log.w(TAG, "Sperre zeigen fehlgeschlagen", t);
            wurzel = null;
        }
    }

    private static TextView text(String s, int sp, boolean fett) {
        TextView tv = new TextView(app);
        tv.setText(s);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setGravity(Gravity.CENTER);
        if (fett) tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        tv.setPadding(0, dp(8), 0, dp(8));
        return tv;
    }

    private static LinearLayout pinFeld() {
        LinearLayout f = new LinearLayout(app);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setGravity(Gravity.CENTER_HORIZONTAL);
        punkte = text("", 32, true);
        f.addView(punkte);
        GridLayout g = new GridLayout(app);
        g.setColumnCount(3);
        String[] tasten = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "OK"};
        for (String k : tasten) {
            Button b = new Button(app);
            b.setText("OK".equals(k) ? t("ok", "OK") : k);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
            b.setTextColor(0xFFFFFFFF);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor("OK".equals(k) ? 0xFF0B57D0 : 0xFF303134);
            bg.setCornerRadius(dp(14));
            b.setBackground(bg);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = dp(96);
            lp.height = dp(80);
            lp.setMargins(dp(6), dp(6), dp(6), dp(6));
            b.setLayoutParams(lp);
            b.setOnClickListener(v -> taste(k));
            g.addView(b);
        }
        f.addView(g);
        return f;
    }

    private static void aktualisierePunkte() {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < PIN.length(); i++) s.append("● ");
        punkte.setText(s.length() == 0 ? t("pin", "PIN") : s.toString().trim());
    }

    private static void taste(String k) {
        if ("⌫".equals(k)) {
            if (PIN.length() > 0) PIN.setLength(PIN.length() - 1);
        } else if ("OK".equals(k)) {
            String pin = PIN.toString();
            PIN.setLength(0);
            pruefen(pin);
        } else if (PIN.length() < ValetRegel.PIN_MAX) {
            PIN.append(k);
        }
        aktualisierePunkte();
    }

    private static void pruefen(String pin) {
        new Thread(() -> {
            HttpURLConnection c = null;
            JSONObject antwort = null;
            try {
                c = at.greenchili.surfmate.util.DaemonHttpClient.open(
                        "/api/display/sperre/entsperren", "POST", 3000, 5000);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream o = c.getOutputStream()) {
                    o.write(new JSONObject().put("pin", pin).toString().getBytes(StandardCharsets.UTF_8));
                }
                int code = c.getResponseCode();
                InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
                if (in != null) {
                    java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                    byte[] p = new byte[2048];
                    int n;
                    while ((n = in.read(p)) > 0 && b.size() < 16384) b.write(p, 0, n);
                    antwort = new JSONObject(b.toString("UTF-8"));
                }
            } catch (Throwable t) {
                Log.w(TAG, "PIN prüfen fehlgeschlagen: " + t.getMessage());
            } finally {
                if (c != null) c.disconnect();
            }
            final JSONObject a = antwort;
            HAUPT.post(() -> nachPruefung(a));
        }, "ValetPin").start();
    }

    private static void nachPruefung(JSONObject a) {
        if (wurzel == null) return;
        if (a != null && a.optBoolean("ok", false)) {
            zeigenLaut = false;
            entfernen();
            return;
        }
        String grund = a == null ? "fehler" : a.optString("grund", "falsch");
        if ("warten".equals(grund)) {
            long s = Math.max(1, (a.optLong("wartenMs", 10_000) + 999) / 1000);
            meldung.setText(t("warten", "Bitte {s} Sekunden warten.").replace("{s}", String.valueOf(s)));
        } else if ("falsch".equals(grund)) {
            meldung.setText(t("falsch", "PIN falsch."));
        } else {
            meldung.setText(t("fehler", "Gerade nicht möglich. Bitte gleich nochmal."));
        }
    }

    private static void entfernen() {
        try {
            ((WindowManager) app.getSystemService(Context.WINDOW_SERVICE)).removeView(wurzel);
        } catch (Throwable ignored) {
        }
        wurzel = null;
        pinFeld = null;
        PIN.setLength(0);
    }
}
