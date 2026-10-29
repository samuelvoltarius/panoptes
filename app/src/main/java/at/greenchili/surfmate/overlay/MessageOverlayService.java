package at.greenchili.surfmate.overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import at.greenchili.surfmate.logging.DaemonLogger;

import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * App-process on-screen message overlay for the "Show Toast" / "Show Dialog"
 * automation + key-mapping actions.
 *
 * <p><b>Why an app-process overlay service.</b> The daemon (UID 2000, {@code app_process})
 * has no usable UI surface — it can't post a Toast or inflate a dialog. This service runs
 * in the REAL app process where a {@link WindowManager} {@code TYPE_APPLICATION_OVERLAY}
 * window works, the same proven mechanism {@link StatusOverlayService} and the RoadSense
 * overlay use. The daemon launches it via {@code am start-foreground-service} (see
 * {@code MessageOverlayController}), exactly like the Play Audio / Speak bridge.
 *
 * <p><b>No focus steal.</b> Both surfaces use {@code FLAG_NOT_FOCUSABLE}, so they float
 * over whatever app is foreground (maps, music) WITHOUT interrupting it or its audio —
 * the deliberate choice for a moving car. A toast is fully non-touchable and auto-dismisses;
 * a dialog is touchable ONLY on its card (OK button + scrim tap) but still doesn't take
 * input focus from the app beneath.
 *
 * <p>Intent extras: {@code kind}=toast|dialog, {@code message}, {@code title} (dialog),
 * {@code button} (dialog OK label), {@code duration}=short|long (toast),
 * {@code position}=top|center|bottom (toast), {@code severity}=info|warning|alert,
 * {@code timeoutSec} (dialog auto-dismiss, 0 = none).
 *
 * <p>{@code kind}=band (F1 „Reicht es noch?“): schmales Band oben rechts, {@code title} +
 * {@code message} (höchstens 2 Zeilen), {@code stufe}=passt|knapp|reicht_nicht färbt den Rand,
 * {@code durationMs} (2–15 s, Standard 8 s). Nicht berührbar, keine Knöpfe.
 */
public final class MessageOverlayService extends Service {

    private static final String TAG = "MessageOverlay";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    private static final String CHANNEL_ID = "message_overlay";
    private static final int NOTIFICATION_ID = 9101;

    /** Broadcast (same family as the media stop) that dismisses any showing message. */
    public static final String ACTION_DISMISS = "at.greenchili.surfmate.action.DISMISS_MESSAGE";

    private static final long TOAST_SHORT_MS = 2500;
    private static final long TOAST_LONG_MS = 5000;

    private WindowManager windowManager;
    private View overlayView;
    /** Ein offener Dialog (Nutzer-Meldung) wird vom F1-Band nicht verdrängt. */
    private boolean dialogShowing;
    /** Griff der gerade gezeigten Kopplungs-Anfrage (null = keine). */
    private String kopplungRef;

    private final android.content.BroadcastReceiver kopplungDismissReceiver = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String ref = i == null ? null : i.getStringExtra("ref");
            if (ref != null && ref.equals(kopplungRef)) handler.post(() -> dismissAndStop());
        }
    };
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable autoDismiss;
    private boolean dismissReceiverRegistered;

    /** Tears down any showing message on the ACTION_DISMISS broadcast — the broadcast
     *  half of {@link at.greenchili.surfmate.byd.MessageOverlayController#dismiss()} (the
     *  am stopservice half also works; this makes an explicit dismiss instant and lets a
     *  dismiss reach the service even if a stopservice race leaves it briefly alive). */
    private final android.content.BroadcastReceiver dismissReceiver = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { dismissAndStop(); }
    };

    @Override public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createNotificationChannel();
        startMessageForeground();
        try {
            registerReceiver(dismissReceiver, new android.content.IntentFilter(ACTION_DISMISS));
            registerReceiver(kopplungDismissReceiver,
                    new android.content.IntentFilter("at.greenchili.surfmate.action.DISMISS_KOPPLUNG"));
            dismissReceiverRegistered = true;
        } catch (Throwable t) {
            logger.warn("Dismiss receiver register failed: " + t.getMessage());
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        // A running-app can't render an overlay without the permission; a shell `am`
        // launch bypasses the in-app grant flow, so guard here and cleanly no-op.
        if (!OverlayPermissionChecker.isGranted(this)) {
            logger.warn("SYSTEM_ALERT_WINDOW not granted; cannot show message");
            sendAcknowledgement(intent, false,
                    "Display-over-other-apps permission is not granted in the car");
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            String kind = orDefault(intent.getStringExtra("kind"), "toast");
            boolean displayed;
            if ("dialog".equalsIgnoreCase(kind)) {
                displayed = showDialog(intent);
            } else if ("kopplung".equalsIgnoreCase(kind)) {
                displayed = showKopplung(intent);
            } else if ("band".equalsIgnoreCase(kind)) {
                displayed = showBand(intent);
            } else if ("rueckkehr".equalsIgnoreCase(kind)) {
                displayed = showRueckkehr(intent);
            } else {
                displayed = showToast(intent);
            }
            sendAcknowledgement(intent, displayed,
                    displayed ? "" : "The car could not add the message overlay");
        } catch (Throwable t) {
            logger.warn("Show failed: " + t.getMessage());
            sendAcknowledgement(intent, false,
                    t.getMessage() == null ? "Message display failed" : t.getMessage());
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    // ── Toast ────────────────────────────────────────────────────────────────

    private boolean showToast(Intent intent) {
        String message = orDefault(intent.getStringExtra("message"), "");
        if (message.isEmpty()) { stopSelf(); return false; }
        String position = orDefault(intent.getStringExtra("position"), "bottom");
        long dur = "long".equalsIgnoreCase(orDefault(intent.getStringExtra("duration"), "short"))
                ? TOAST_LONG_MS : TOAST_SHORT_MS;
        int accent = accentFor(intent.getStringExtra("severity"));

        Context ctx = themedContext();
        int pad = dp(ctx, 16);

        TextView tv = new TextView(ctx);
        tv.setText(message);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setMaxLines(4);
        tv.setPadding(pad, dp(ctx, 12), pad, dp(ctx, 12));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF2202124);                 // near-opaque charcoal
        bg.setCornerRadius(dp(ctx, 8));
        bg.setStroke(dp(ctx, 2), accent);         // severity edge
        tv.setBackground(bg);

        if (!replaceOverlay(tv, toastLayoutParams(gravityFor(position)))) {
            return false;
        }
        scheduleDismiss(dur);
        return true;
    }

    // ── Band (F1) ────────────────────────────────────────────────────────────

    private boolean showBand(Intent intent) {
        String title = orDefault(intent.getStringExtra("title"), "");
        String message = orDefault(intent.getStringExtra("message"), "");
        if (title.isEmpty() && message.isEmpty()) { stopSelf(); return false; }
        if (dialogShowing && overlayView != null) return false;
        long dur = 8000;
        try {
            dur = Long.parseLong(orDefault(intent.getStringExtra("durationMs"), "8000").trim());
        } catch (NumberFormatException ignored) {
        }
        dur = Math.max(2000, Math.min(15000, dur));
        int accent = bandFarbe(intent.getStringExtra("stufe"));
        // 0.1.9.1: Größe/Position einstellbar (anzeige.bandGroesse/-Position), Standard groß + oben Mitte
        boolean gross = !"normal".equals(intent.getStringExtra("groesse"));
        boolean mitte = !"oben_rechts".equals(intent.getStringExtra("position"));
        float faktor = gross ? 1.5f : 1f;

        Context ctx = themedContext();
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(ctx, (int) (20 * faktor)), dp(ctx, (int) (12 * faktor)),
                dp(ctx, (int) (20 * faktor)), dp(ctx, (int) (12 * faktor)));
        int maxBreite = (int) (ctx.getResources().getDisplayMetrics().widthPixels * (gross ? 0.7f : 0.45f));

        TextView t1 = new TextView(ctx);
        t1.setText(title);
        t1.setTextColor(Color.WHITE);
        t1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24 * faktor);
        t1.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t1.setMaxLines(1);
        t1.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t1.setMaxWidth(maxBreite);
        box.addView(t1);
        if (!message.isEmpty()) {
            TextView t2 = new TextView(ctx);
            t2.setText(message);
            t2.setTextColor(0xFFE0E0E0);
            t2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18 * faktor);
            t2.setMaxLines(1);
            t2.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t2.setMaxWidth(maxBreite);
            box.addView(t2);
        }

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF2202124);
        bg.setCornerRadius(dp(ctx, 10));
        bg.setStroke(dp(ctx, gross ? 6 : 4), accent);
        box.setBackground(bg);

        WindowManager.LayoutParams lp = toastLayoutParams(Gravity.TOP | (mitte ? Gravity.CENTER_HORIZONTAL : Gravity.END));
        lp.x = mitte ? 0 : dp(this, 24);
        lp.y = dp(this, 24);
        if (!replaceOverlay(box, lp)) {
            return false;
        }
        scheduleDismiss(dur);
        return true;
    }

    /** Stufenfarben aus dem F1-Konzept: Limette, Bernstein, Koralle. */
    private static int bandFarbe(String stufe) {
        String s = stufe == null ? "" : stufe.trim().toLowerCase();
        switch (s) {
            case "passt":        return 0xFFB5D334;
            case "knapp":        return 0xFFF2A900;
            case "reicht_nicht": return 0xFFFF6F5E;
            default:             return 0xFF0B57D0;
        }
    }

    // ── Kopplungs-Anfrage ────────────────────────────────────────────────────

    /**
     * „EV-Cockpit ‚Name‘ will sich verbinden – Prüfzahl 1234“ mit [Ablehnen] [Erlauben]. Die Knöpfe
     * rufen den Daemon mit vollem Login (DaemonHttpClient); ein Tipp neben die Karte tut nichts.
     */
    private boolean showKopplung(Intent intent) {
        String ref = orDefault(intent.getStringExtra("ref"), "");
        if (!ref.matches("^[0-9a-f]{16}$")) { stopSelf(); return false; }
        // 0.1.21: dieselbe Rückfrage auch für die BYD-Cloud-Übergabe (eigener Pfad, nur diese zwei Formen)
        String pfad = orDefault(intent.getStringExtra("pfad"), "/api/kopplung/anfrage/" + ref);
        if (!pfad.equals("/api/kopplung/anfrage/" + ref) && !pfad.equals("/api/cloud-uebergabe/r-" + ref)
                && !pfad.equals("/api/kopplung/weiteres-geraet/r-" + ref)) { stopSelf(); return false; }   // 0.1.25
        String title = orDefault(intent.getStringExtra("title"), "");
        String message = orDefault(intent.getStringExtra("message"), "");
        int timeoutSec = intent.getIntExtra("timeoutSec", 120);

        Context ctx = themedContext();
        int pad = dp(ctx, 24);
        FrameLayout scrim = new FrameLayout(ctx);
        scrim.setBackgroundColor(0x99000000);
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(pad, pad, pad, dp(ctx, 16));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xFF202124);
        cardBg.setCornerRadius(dp(ctx, 18));
        cardBg.setStroke(dp(ctx, 3), 0xFF0B57D0);
        card.setBackground(cardBg);

        TextView tvTitle = new TextView(ctx);
        tvTitle.setText(title);
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        tvTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        card.addView(tvTitle);
        TextView tvBody = new TextView(ctx);
        tvBody.setText(message);
        tvBody.setTextColor(0xFFE3E3E3);
        tvBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(ctx, 12);
        card.addView(tvBody, blp);

        LinearLayout reihe = new LinearLayout(ctx);
        reihe.setOrientation(LinearLayout.HORIZONTAL);
        reihe.setGravity(Gravity.END);
        reihe.addView(kopplungKnopf(ctx, orDefault(intent.getStringExtra("ablehnen"), "X"), 0xFF5F6368, pfad, false));
        reihe.addView(kopplungKnopf(ctx, orDefault(intent.getStringExtra("erlauben"), "OK"), 0xFF0B57D0, pfad, true));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(ctx, 20);
        card.addView(reihe, rlp);

        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(dp(ctx, 520), ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.CENTER;
        scrim.addView(card, clp);
        if (!replaceOverlay(scrim, dialogLayoutParams())) return false;
        dialogShowing = true;
        kopplungRef = ref;
        cancelDismiss();
        scheduleDismiss(Math.max(10, Math.min(300, timeoutSec)) * 1000L);
        return true;
    }

    private Button kopplungKnopf(Context ctx, String text, int farbe, String pfad, boolean erlauben) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(farbe);
        bg.setCornerRadius(dp(ctx, 12));
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(ctx, 12);
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> {
            v.setEnabled(false);
            new Thread(() -> {
                java.net.HttpURLConnection c = null;
                try {
                    c = at.greenchili.surfmate.util.DaemonHttpClient.open(
                            pfad + (erlauben ? "/erlauben" : "/ablehnen"), "POST", 3000, 5000);
                    c.setDoOutput(true);
                    // 0.1.26.1: am Display erlaubte Handys dürfen Panoptes aktualisieren (Recht „update“)
                    byte[] koerper = erlauben && pfad.startsWith("/api/kopplung/anfrage/")
                            ? "{\"update\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8) : new byte[0];
                    if (koerper.length > 0) c.setRequestProperty("Content-Type", "application/json");
                    try (java.io.OutputStream o = c.getOutputStream()) { o.write(koerper); }
                    logger.info("Kopplung am Display: " + (erlauben ? "erlaubt" : "abgelehnt") + " -> " + c.getResponseCode());
                } catch (Throwable t) {
                    logger.warn("Kopplung am Display fehlgeschlagen: " + t.getMessage());
                } finally {
                    if (c != null) c.disconnect();
                }
                handler.post(this::dismissAndStop);
            }, "KopplungKnopf").start();
        });
        return b;
    }

    // ── Rückkehr-Karte (0.1.28) ─────────────────────────────────────────────

    /**
     * „Während du weg warst: 3 Aufnahmen“ mit großen Knöpfen [Schließen] [Ansehen] (REGEL DAU). „Ansehen“ öffnet
     * Panoptes auf der Aufnahmen-Seite und spielt die erste Aufnahme. Kurzkarten (nichts passiert / nicht scharf):
     * nur Text, ein Tipp schließt, verschwinden von selbst. Kein Ton.
     */
    private boolean showRueckkehr(Intent intent) {
        String art = orDefault(intent.getStringExtra("art"), "");
        boolean aufnahmen = "aufnahmen".equals(art);
        if (!aufnahmen && !"nichts".equals(art) && !"nicht_scharf".equals(art)) { stopSelf(); return false; }
        String title = orDefault(intent.getStringExtra("title"), "");
        String message = orDefault(intent.getStringExtra("message"), "");
        String aufnahme = orDefault(intent.getStringExtra("aufnahme"), "");
        int timeoutSec = intent.getIntExtra("timeoutSec", aufnahmen ? 120 : 8);

        Context ctx = themedContext();
        int pad = dp(ctx, 28);
        FrameLayout scrim = new FrameLayout(ctx);
        scrim.setBackgroundColor(aufnahmen ? 0x99000000 : 0x00000000);
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(pad, pad, pad, dp(ctx, 20));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xFF202124);
        cardBg.setCornerRadius(dp(ctx, 20));
        cardBg.setStroke(dp(ctx, 3), aufnahmen ? 0xFFF9AB00 : "nichts".equals(art) ? 0xFF1E8E3E : 0xFF5F6368);
        card.setBackground(cardBg);

        TextView tvTitle = new TextView(ctx);
        tvTitle.setText(title);
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, aufnahmen ? 26 : 24);
        tvTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        card.addView(tvTitle);
        if (!message.isEmpty()) {
            TextView tvBody = new TextView(ctx);
            tvBody.setText(message);
            tvBody.setTextColor(0xFFE3E3E3);
            tvBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, aufnahmen ? 34 : 22);
            if (aufnahmen) tvBody.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.topMargin = dp(ctx, 10);
            card.addView(tvBody, blp);
        }
        if (aufnahmen) {
            LinearLayout reihe = new LinearLayout(ctx);
            reihe.setOrientation(LinearLayout.HORIZONTAL);
            reihe.setGravity(Gravity.END);
            reihe.addView(rueckkehrKnopf(ctx, orDefault(intent.getStringExtra("schliessen"), "X"), 0xFF5F6368, null));
            reihe.addView(rueckkehrKnopf(ctx, orDefault(intent.getStringExtra("ansehen"), "OK"), 0xFF0B57D0, aufnahme));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(ctx, 24);
            card.addView(reihe, rlp);
        } else {
            // Kurzkarte: ein Tipp auf die Karte schließt sie
            card.setOnClickListener(v -> dismissAndStop());
        }

        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(dp(ctx, 560), ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = aufnahmen ? Gravity.CENTER : (Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        if (!aufnahmen) clp.topMargin = dp(ctx, 40);
        scrim.addView(card, clp);
        if (!replaceOverlay(scrim, dialogLayoutParams())) return false;
        dialogShowing = true;
        cancelDismiss();
        scheduleDismiss(Math.max(5, Math.min(300, timeoutSec)) * 1000L);
        return true;
    }

    /** {@code aufnahme == null}: Schließen; sonst Panoptes öffnen und diese Aufnahme abspielen. */
    private Button rueckkehrKnopf(Context ctx, String text, int farbe, String aufnahme) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        b.setMinHeight(dp(ctx, 64));
        b.setMinWidth(dp(ctx, 160));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(farbe);
        bg.setCornerRadius(dp(ctx, 14));
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(ctx, 16);
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> {
            if (aufnahme != null) {
                try {
                    Intent i = new Intent(this, at.greenchili.surfmate.ui.MainActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                            | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    i.putExtra("navigate_to", at.greenchili.surfmate.ui.NavigateZiel.ZIEL_AUFNAHME);
                    if (!aufnahme.isEmpty()) i.putExtra(at.greenchili.surfmate.ui.NavigateZiel.EXTRA_AUFNAHME, aufnahme);
                    startActivity(i);
                } catch (Throwable t) {
                    logger.warn("Rückkehr-Karte: Ansehen fehlgeschlagen: " + t.getMessage());
                }
            }
            dismissAndStop();
        });
        return b;
    }

    // ── Dialog ───────────────────────────────────────────────────────────────

    private boolean showDialog(Intent intent) {
        String title = orDefault(intent.getStringExtra("title"), "");
        String message = orDefault(intent.getStringExtra("message"), "");
        String buttonLabel = orDefault(intent.getStringExtra("button"), "OK");
        int accent = accentFor(intent.getStringExtra("severity"));
        int timeoutSec = intent.getIntExtra("timeoutSec", 0);
        if (title.isEmpty() && message.isEmpty()) { stopSelf(); return false; }

        Context ctx = themedContext();
        int pad = dp(ctx, 20);

        // Dimmed full-screen scrim; tap outside the card dismisses.
        FrameLayout scrim = new FrameLayout(ctx);
        scrim.setBackgroundColor(0x99000000);
        scrim.setOnClickListener(v -> dismissAndStop());

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(pad, pad, pad, dp(ctx, 12));
        // Swallow taps on the card so they don't fall through to the scrim.
        card.setOnClickListener(v -> {});
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xFF202124);
        cardBg.setCornerRadius(dp(ctx, 18));
        cardBg.setStroke(dp(ctx, 3), accent);
        card.setBackground(cardBg);

        if (!title.isEmpty()) {
            TextView tvTitle = new TextView(ctx);
            tvTitle.setText(title);
            tvTitle.setTextColor(Color.WHITE);
            tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            tvTitle.setTypeface(tvTitle.getTypeface(), android.graphics.Typeface.BOLD);
            card.addView(tvTitle);
        }
        if (!message.isEmpty()) {
            TextView tvBody = new TextView(ctx);
            tvBody.setText(message);
            tvBody.setTextColor(0xFFE3E3E3);
            tvBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.topMargin = dp(ctx, 12);
            card.addView(tvBody, blp);
        }

        Button ok = new Button(ctx);
        ok.setText(buttonLabel);
        ok.setAllCaps(false);
        ok.setTextColor(Color.WHITE);
        ok.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        GradientDrawable okBg = new GradientDrawable();
        okBg.setColor(accent);
        okBg.setCornerRadius(dp(ctx, 12));
        ok.setBackground(okBg);
        ok.setOnClickListener(v -> dismissAndStop());
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        olp.topMargin = dp(ctx, 20);
        olp.gravity = Gravity.END;
        card.addView(ok, olp);

        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                dp(ctx, 460), ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.CENTER;
        scrim.addView(card, clp);

        if (!replaceOverlay(scrim, dialogLayoutParams())) return false;
        dialogShowing = true;
        // A dialog holds until OK/scrim tap, unless the user set an auto-dismiss.
        cancelDismiss();
        if (timeoutSec > 0) scheduleDismiss(timeoutSec * 1000L);
        return true;
    }

    // ── Overlay plumbing ───────────────────────────────────────────────────────

    private boolean replaceOverlay(View view, WindowManager.LayoutParams lp) {
        removeOverlay();
        dialogShowing = false;
        kopplungRef = null;
        overlayView = view;
        try {
            windowManager.addView(overlayView, lp);
            return true;
        } catch (Throwable t) {
            logger.error("addView failed: " + t.getMessage());
            overlayView = null;
            stopSelf();
            return false;
        }
    }

    /**
     * Report the real addView outcome to the daemon over a one-use loopback
     * callback. Legacy automation calls omit these extras and remain
     * fire-and-forget.
     */
    private void sendAcknowledgement(Intent intent, boolean displayed, String reason) {
        String portValue = intent.getStringExtra("ackPort");
        String token = intent.getStringExtra("ackToken");
        if (portValue == null || token == null || token.isEmpty()) return;
        final int port;
        try {
            port = Integer.parseInt(portValue);
        } catch (NumberFormatException invalid) {
            return;
        }
        if (port <= 0 || port > 65535) return;

        Thread callback = new Thread(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
                DataOutputStream output =
                        new DataOutputStream(socket.getOutputStream());
                output.writeUTF(token);
                output.writeUTF(displayed ? "DISPLAYED" : "FAILED");
                output.writeUTF(reason == null ? "" : reason);
                output.flush();
            } catch (Throwable error) {
                logger.warn(
                        "Acknowledgement callback failed: "
                                + error.getMessage());
            }
        }, "MessageOverlayAck");
        callback.setDaemon(true);
        callback.start();
    }

    private void removeOverlay() {
        if (overlayView != null) {
            try { windowManager.removeView(overlayView); } catch (Throwable ignored) {}
            overlayView = null;
        }
    }

    /** Toast window: non-focusable AND non-touchable so it never intercepts input. */
    private WindowManager.LayoutParams toastLayoutParams(int gravity) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = gravity;
        int m = dp(this, 40);
        lp.y = m;
        return lp;
    }

    /** Dialog window: non-focusable (no focus steal) but touchable (OK / scrim tap). */
    private WindowManager.LayoutParams dialogLayoutParams() {
        return new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
    }

    private static int overlayWindowType() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
    }

    private void scheduleDismiss(long ms) {
        cancelDismiss();
        autoDismiss = this::dismissAndStop;
        handler.postDelayed(autoDismiss, ms);
    }

    private void cancelDismiss() {
        if (autoDismiss != null) { handler.removeCallbacks(autoDismiss); autoDismiss = null; }
    }

    private void dismissAndStop() {
        cancelDismiss();
        removeOverlay();
        stopSelf();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private static int gravityFor(String position) {
        if (position == null) return Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        switch (position.trim().toLowerCase()) {
            case "top":    return Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            case "center": return Gravity.CENTER;
            case "bottom":
            default:       return Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        }
    }

    /** Severity → accent color, using the app's status tokens. Default info. */
    private int accentFor(String severity) {
        String s = severity == null ? "info" : severity.trim().toLowerCase();
        switch (s) {
            case "warning": return 0xFFA6601C; // status_warning
            case "alert":
            case "danger":  return 0xFFBA1A1A; // status_danger
            case "info":
            default:        return 0xFF0B57D0; // status_info
        }
    }

    private static String orDefault(String v, String def) {
        return (v == null || v.trim().isEmpty()) ? def : v;
    }

    private static int dp(Context ctx, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics());
    }

    /**
     * A context whose configuration honors the app's day/night choice (a bare Service
     * reads the system uiMode). Mirrors StatusOverlayService.themedContext().
     */
    private Context themedContext() {
        try {
            int mode = androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode();
            int uiNight;
            if (mode == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES) {
                uiNight = android.content.res.Configuration.UI_MODE_NIGHT_YES;
            } else if (mode == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO) {
                uiNight = android.content.res.Configuration.UI_MODE_NIGHT_NO;
            } else {
                return this;
            }
            android.content.res.Configuration cfg = new android.content.res.Configuration(
                    getResources().getConfiguration());
            cfg.uiMode = (cfg.uiMode & ~android.content.res.Configuration.UI_MODE_NIGHT_MASK) | uiNight;
            return createConfigurationContext(cfg);
        } catch (Throwable t) {
            return this;
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, getString(at.greenchili.surfmate.R.string.notif_onscreen_channel), NotificationManager.IMPORTANCE_MIN);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
    }

    private void startMessageForeground() {
        Notification n = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, n,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, n,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
        } catch (Throwable e) {
            logger.warn(
                    "startForeground failed, falling back: "
                            + e.getMessage());
            try { startForeground(NOTIFICATION_ID, n); } catch (Throwable ignored) {}
        }
    }

    private Notification buildNotification() {
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle(getString(at.greenchili.surfmate.R.string.notif_onscreen_title))
                .setSmallIcon(at.greenchili.surfmate.R.drawable.ic_stat_panoptes)
                .setPriority(Notification.PRIORITY_MIN)
                .build();
    }

    @Override public void onDestroy() {
        cancelDismiss();
        removeOverlay();
        if (dismissReceiverRegistered) {
            try { unregisterReceiver(dismissReceiver); } catch (Throwable ignored) {}
            try { unregisterReceiver(kopplungDismissReceiver); } catch (Throwable ignored) {}
            dismissReceiverRegistered = false;
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
